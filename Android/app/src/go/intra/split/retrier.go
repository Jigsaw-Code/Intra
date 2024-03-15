// Copyright 2023 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package split

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"localhost/Intra/Android/app/src/go/logging"
	"math/rand"
	"net"
	"sync"
	"time"

	"github.com/Jigsaw-Code/getsni"
)

type RetryStats struct {
	SNI     string // TLS SNI observed, if present.
	Bytes   int32  // Number of bytes uploaded before the retry.
	Chunks  int16  // Number of writes before the retry.
	Split   int16  // Number of bytes in the first retried segment.
	Timeout bool   // True if the retry was caused by a timeout.
}

// retrier implements the DuplexConn interface.
type retrier struct {
	// mutex is a lock that guards `conn`, `hello`, and `retryCompleteFlag`.
	// These fields must not be modified except under this lock.
	// After retryCompletedFlag is closed, these values will not be modified
	// again so locking is no longer required for reads.
	mutex   sync.Mutex
	dialer  *net.Dialer
	network string
	addr    *net.TCPAddr
	// conn is the current underlying connection.  It is only modified by the reader
	// thread, so the reader functions may access it without acquiring a lock.
	conn *net.TCPConn
	// External read and write deadlines.  These need to be stored here so that
	// they can be re-applied in the event of a retry.
	readDeadline  time.Time
	writeDeadline time.Time
	// Time to wait between the first write and the first read before triggering a
	// retry.
	timeout time.Duration
	// hello is the contents written before the first read.  It is initially empty,
	// and is cleared when the first byte is received.
	hello []byte
	// Flag indicating when retry is finished or unnecessary.
	retryCompleteFlag chan struct{}
	// Flags indicating whether the caller has called CloseRead and CloseWrite.
	readCloseFlag  chan struct{}
	writeCloseFlag chan struct{}
	stats          *RetryStats
}

// Helper functions for reading flags.
// In this package, a "flag" is a thread-safe single-use status indicator that
// starts in the "open" state and transitions to "closed" when close() is called.
// It is implemented as a channel over which no data is ever sent.
// Some advantages of this implementation:
//   - The language enforces the one-way transition.
//   - Nonblocking and blocking access are both straightforward.
//   - Checking the status of a closed flag should be extremely fast (although currently
//     it's not optimized: https://github.com/golang/go/issues/32529)
func closed(c chan struct{}) bool {
	select {
	case <-c:
		// The channel has been closed.
		return true
	default:
		return false
	}
}

func (r *retrier) readClosed() bool {
	return closed(r.readCloseFlag)
}

func (r *retrier) writeClosed() bool {
	return closed(r.writeCloseFlag)
}

func (r *retrier) retryCompleted() bool {
	return closed(r.retryCompleteFlag)
}

// Given timestamps immediately before and after a successful socket connection
// (i.e. the time the SYN was sent and the time the SYNACK was received), this
// function returns a reasonable timeout for replies to a hello sent on this socket.
func timeout(before, after time.Time) time.Duration {
	// These values were chosen to have a <1% false positive rate based on test data.
	// False positives trigger an unnecessary retry, which can make connections slower, so they are
	// worth avoiding.  However, overly long timeouts make retry slower and less useful.
	rtt := after.Sub(before)
	return 1200*time.Millisecond + 2*rtt
}

// DefaultTimeout is the value that will cause DialWithSplitRetry to use the system's
// default TCP timeout (typically 2-3 minutes).
const DefaultTimeout time.Duration = 0

// DialWithSplitRetry returns a TCP connection that transparently retries by
// splitting the initial upstream segment if the socket closes without receiving a
// reply.  Like net.Conn, it is intended for two-threaded use, with one thread calling
// Read and CloseRead, and another calling Write, ReadFrom, and CloseWrite.
// `dialer` will be used to establish the connection.
// `addr` is the destination.
// If `stats` is non-nil, it will be populated with retry-related information.
func DialWithSplitRetry(ctx context.Context, dialer *net.Dialer, addr *net.TCPAddr, stats *RetryStats) (DuplexConn, error) {
	logging.Debug("SplitRetry(DialWithSplitRetry) - dialing", "addr", addr)
	before := time.Now()
	conn, err := dialer.DialContext(ctx, addr.Network(), addr.String())
	logging.Debug("SplitRetry(DialWithSplitRetry) - dialed", "err", err)
	if err != nil {
		return nil, err
	}
	after := time.Now()

	if stats == nil {
		// This is a fake stats object that will be written but never read.  Its purpose
		// is to avoid the need for nil checks at each point where stats are updated.
		stats = &RetryStats{}
	}

	r := &retrier{
		dialer:            dialer,
		addr:              addr,
		conn:              conn.(*net.TCPConn),
		timeout:           timeout(before, after),
		retryCompleteFlag: make(chan struct{}),
		readCloseFlag:     make(chan struct{}),
		writeCloseFlag:    make(chan struct{}),
		stats:             stats,
	}

	return r, nil
}

// Read-related functions.
func (r *retrier) Read(buf []byte) (n int, err error) {
	n, err = r.conn.Read(buf)
	if n == 0 && err == nil {
		// If no data was read, a nil error doesn't rule out the need for a retry.
		return
	}
	if !r.retryCompleted() {
		r.mutex.Lock()
		if err != nil {
			var neterr net.Error
			if errors.As(err, &neterr) {
				r.stats.Timeout = neterr.Timeout()
			}
			// Read failed.  Retry.
			n, err = r.retry(buf)
		} else {
			logging.Debug("SplitRetry(retrier.Read) - direct conn succeeded, no need to split")
		}
		close(r.retryCompleteFlag)
		// Unset read deadline.
		r.conn.SetReadDeadline(time.Time{})
		r.hello = nil
		r.mutex.Unlock()
	}
	return
}

func (r *retrier) retry(buf []byte) (n int, err error) {
	logging.Debug("SplitRetry(retrier.retry) - retrying...")
	defer func() { logging.Debug("SplitRetry(retrier.retry) - retried", "n", n, "err", err) }()

	r.conn.Close()
	var newConn net.Conn
	if newConn, err = r.dialer.Dial(r.addr.Network(), r.addr.String()); err != nil {
		return
	}
	r.conn = newConn.(*net.TCPConn)
	pkts, split := splitHello(r.hello)
	r.stats.Split = split

	// We did not use pkts.WriteTo(r.conn), because under the hood, the connection
	// will use writev system call to write buffers, and writev may combine these
	// buffers into one single write.
	for _, pkt := range pkts {
		if _, err = r.conn.Write(pkt); err != nil {
			return
		}
	}

	// While we were creating the new socket, the caller might have called CloseRead
	// or CloseWrite on the old socket.  Copy that state to the new socket.
	// CloseRead and CloseWrite are idempotent, so this is safe even if the user's
	// action actually affected the new socket.
	if r.readClosed() {
		r.conn.CloseRead()
	}
	if r.writeClosed() {
		r.conn.CloseWrite()
	}
	// The caller might have set read or write deadlines before the retry.
	r.conn.SetReadDeadline(r.readDeadline)
	r.conn.SetWriteDeadline(r.writeDeadline)
	return r.conn.Read(buf)
}

func (r *retrier) CloseRead() error {
	if !r.readClosed() {
		close(r.readCloseFlag)
	}
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return r.conn.CloseRead()
}

func splitHello(hello []byte) (pkts net.Buffers, splitLen int16) {
	if len(hello) == 0 {
		return net.Buffers{hello}, 0
	}
	const (
		MIN_SPLIT         int = 32
		MAX_SPLIT         int = 64
		MIN_TLS_HELLO_LEN int = 6

		TYPE_HANDSHAKE byte   = 22
		VERSION_TLS10  uint16 = 0x0301
		VERSION_TLS11  uint16 = 0x0302
		VERSION_TLS12  uint16 = 0x0303
		VERSION_TLS13  uint16 = 0x0304
	)

	// Random number in the range [MIN_SPLIT, MAX_SPLIT]
	s := MIN_SPLIT + rand.Intn(MAX_SPLIT+1-MIN_SPLIT)
	limit := len(hello) / 2
	if s > limit {
		s = limit
	}
	splitLen = int16(s)
	pkts = net.Buffers{hello[:s], hello[s:]}

	if len(pkts[0]) > MIN_TLS_HELLO_LEN {
		// todo: Replace the following TLS fragmentation logic with tlsfrag.StreamDialer
		//
		// TLS record layout from RFC 8446:
		//   [RecordType:1B][Ver:2B][Len:2B][Data...]
		// RecordType := ... | handshake(22) | ...
		//        Ver := 0x0301 ("TLS 1.0") | 0x0302 ("TLS 1.1") | 0x0303 ("TLS 1.2")
		//
		// Now we have already TCP-splitted into pkts0 (len >= 6) and pkts1.
		// We just need to deal with pkts0 and fragment it:
		//
		//   original:   pkts[0]=[Header][data0],
		//               pkts[1]=[data1]
		//   fragmented: pkts[0]=[Header]
		//               pkts[1]=[data0_0],
		//               pkts[2]=[Header],
		//               pkts[3]=[data0_1],
		//               pkts[4]=[data1]

		h1 := make([]byte, 5)
		copy(h1, pkts[0][:5])
		payload := pkts[0][5:] // len(payload) > 1 is guaranteed

		typ := h1[0]
		ver := binary.BigEndian.Uint16(h1[1:3])
		recordLen := binary.BigEndian.Uint16(h1[3:5])

		if typ == TYPE_HANDSHAKE && int(recordLen) >= len(payload) &&
			(ver == VERSION_TLS10 || ver == VERSION_TLS11 ||
				ver == VERSION_TLS12 || ver == VERSION_TLS13) {
			rest := pkts[1]
			frag := uint16(1 + rand.Intn(len(payload)-1)) // 1 <= frag <= len(payload)-1

			binary.BigEndian.PutUint16(h1[3:5], frag)
			payload1 := payload[:frag]

			h2 := make([]byte, 5)
			copy(h2, h1)
			binary.BigEndian.PutUint16(h2[3:5], recordLen-frag) // recordLen >= len(payload) > frag
			payload2 := payload[frag:]

			pkts = net.Buffers{h1, payload1, h2, payload2, rest}
		}
	}

	return
}

// Write-related functions
func (r *retrier) Write(b []byte) (int, error) {
	// Double-checked locking pattern.  This avoids lock acquisition on
	// every packet after retry completes, while also ensuring that r.hello is
	// empty at steady-state.
	if !r.retryCompleted() {
		n := 0
		var err error
		attempted := false
		r.mutex.Lock()
		if !r.retryCompleted() {
			n, err = r.conn.Write(b)
			attempted = true
			r.hello = append(r.hello, b[:n]...)

			r.stats.Chunks++
			r.stats.Bytes = int32(len(r.hello))
			if r.stats.SNI == "" {
				r.stats.SNI, _ = getsni.GetSNI(r.hello)
			}

			// We require a response or another write within the specified timeout.
			r.conn.SetReadDeadline(time.Now().Add(r.timeout))
		}
		r.mutex.Unlock()
		if attempted {
			if err == nil {
				return n, nil
			}
			// A write error occurred on the provisional socket.  This should be handled
			// by the retry procedure.  Block until we have a final socket (which will
			// already have replayed b[:n]), and retry.
			<-r.retryCompleteFlag
			r.mutex.Lock()
			r.mutex.Unlock()
			m, err := r.conn.Write(b[n:])
			return n + m, err
		}
	}

	// retryCompleted() is true, so r.conn is final and doesn't need locking.
	return r.conn.Write(b)
}

// Copy one buffer from src to dst, using dst.Write.
func copyOnce(dst io.Writer, src io.Reader) (int64, error) {
	// This buffer is large enough to hold any ordinary first write
	// without introducing extra splitting.
	buf := make([]byte, 2048)
	n, err := src.Read(buf)
	if err != nil {
		return 0, err
	}
	n, err = dst.Write(buf[:n])
	return int64(n), err
}

func (r *retrier) ReadFrom(reader io.Reader) (bytes int64, err error) {
	for !r.retryCompleted() {
		if bytes, err = copyOnce(r, reader); err != nil {
			return
		}
	}

	var b int64
	b, err = r.conn.ReadFrom(reader)
	bytes += b
	return
}

func (r *retrier) CloseWrite() error {
	if !r.writeClosed() {
		close(r.writeCloseFlag)
	}
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return r.conn.CloseWrite()
}

func (r *retrier) Close() error {
	if err := r.CloseWrite(); err != nil {
		return err
	}
	return r.CloseRead()
}

// LocalAddr behaves slightly strangely: its value may change as a
// result of a retry.  However, LocalAddr is largely useless for
// TCP client sockets anyway, so nothing should be relying on this.
func (r *retrier) LocalAddr() net.Addr {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return r.conn.LocalAddr()
}

func (r *retrier) RemoteAddr() net.Addr {
	return r.addr
}

func (r *retrier) SetReadDeadline(t time.Time) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	r.readDeadline = t
	// Don't enforce read deadlines until after the retry
	// is complete.  Retry relies on setting its own read
	// deadline, and we don't want this to interfere.
	if r.retryCompleted() {
		return r.conn.SetReadDeadline(t)
	}
	return nil
}

func (r *retrier) SetWriteDeadline(t time.Time) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	r.writeDeadline = t
	return r.conn.SetWriteDeadline(t)
}

func (r *retrier) SetDeadline(t time.Time) error {
	e1 := r.SetReadDeadline(t)
	e2 := r.SetWriteDeadline(t)
	if e1 != nil {
		return e1
	}
	return e2
}
