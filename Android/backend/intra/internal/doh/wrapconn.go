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

package doh

import (
	"io"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Jigsaw-Code/Intra/Android/backend/intra/internal/sni"
	intraLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra"
	"github.com/Jigsaw-Code/outline-sdk/transport"
)

type dohConnAdapter struct {
	transport.StreamConn

	wg           *sync.WaitGroup
	rDone, wDone atomic.Bool

	beginTime time.Time
	stats     *tcpTrafficStats

	listener    intraLegacy.TCPListener
	sniReporter sni.TCPSNIReporter
}

var _ transport.StreamConn = (*dohConnAdapter)(nil)

func makeWrapConnWithStats(c transport.StreamConn, stats *tcpTrafficStats, listener intraLegacy.TCPListener, sniReporter sni.TCPSNIReporter) (conn *dohConnAdapter) {
	defer log.Println("[info] New TCP session initialized")

	conn = &dohConnAdapter{
		StreamConn:  c,
		wg:          &sync.WaitGroup{},
		beginTime:   time.Now(),
		stats:       stats,
		listener:    listener,
		sniReporter: sniReporter,
	}

	// Wait until both read and write are done
	conn.wg.Add(2)
	go func() {
		defer func() { log.Printf("[info] TCP session terminated, stats = %v\n", conn.stats) }()
		conn.wg.Wait()
		conn.stats.Duration = int32(time.Since(conn.beginTime))
		if conn.listener != nil {
			conn.listener.OnTCPSocketClosed(conn.stats)
		}
		if conn.stats.Retry != nil && conn.sniReporter != nil {
			conn.sniReporter.Report(*conn.stats)
		}
	}()

	return
}

func (conn *dohConnAdapter) Close() error {
	log.Println("[debug] TCP session terminating...")
	defer conn.close(&conn.wDone)
	defer conn.close(&conn.rDone)
	return conn.StreamConn.Close()
}

func (conn *dohConnAdapter) CloseRead() error {
	log.Println("[debug] TCP read session terminating...")
	defer conn.close(&conn.rDone)
	return conn.StreamConn.CloseRead()
}

func (conn *dohConnAdapter) CloseWrite() error {
	log.Println("[debug] TCP write session terminating...")
	defer conn.close(&conn.wDone)
	return conn.StreamConn.CloseWrite()
}

func (conn *dohConnAdapter) Read(b []byte) (n int, err error) {
	defer func() {
		log.Printf("[debug] TCP Session: download %v bytes, with err = %v\n", n, err)
		conn.stats.DownloadBytes += int64(n)
	}()
	return conn.StreamConn.Read(b)
}

func (conn *dohConnAdapter) WriteTo(w io.Writer) (n int64, err error) {
	defer func() {
		log.Printf("[debug] TCP Session: download %v bytes, with err = %v\n", n, err)
		conn.stats.DownloadBytes += n
	}()
	return io.Copy(w, conn)
}

func (conn *dohConnAdapter) Write(b []byte) (n int, err error) {
	defer func() {
		log.Printf("[debug] TCP Session: upload %v bytes, with err = %v\n", n, err)
		conn.stats.UploadBytes += int64(n)
	}()
	return conn.StreamConn.Write(b)
}

func (conn *dohConnAdapter) ReadFrom(r io.Reader) (n int64, err error) {
	defer func() {
		log.Printf("[debug] TCP Session: upload %v bytes, with err = %v\n", n, err)
		conn.stats.UploadBytes += n
	}()
	return io.Copy(conn, r)
}

func (conn *dohConnAdapter) close(done *atomic.Bool) {
	// make sure conn.wg is being called at most once for a specific `done` flag
	if done.CompareAndSwap(false, true) {
		conn.wg.Done()
	}
}
