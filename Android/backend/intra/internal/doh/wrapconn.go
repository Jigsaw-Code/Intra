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
	log.Println("[debug] establishing new TCP session")
	defer func() {
		log.Printf("[info] New TCP session [%p] initialized\n", conn)
	}()

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
		defer func() {
			log.Printf("[info] TCP session [%p] terminated: down = %v, up = %v, span = %v\n",
				conn, conn.stats.DownloadBytes, conn.stats.UploadBytes, conn.stats.Duration)
		}()
		conn.wg.Wait()
		log.Printf("[debug] calculating TCP session [%p] statistics data\n", conn)
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
	log.Printf("[debug] TCP session [%p] terminating...\n", conn)
	defer conn.close(&conn.wDone)
	defer conn.close(&conn.rDone)
	return conn.StreamConn.Close()
}

func (conn *dohConnAdapter) CloseRead() error {
	log.Printf("[debug] TCP read session [%p] terminating...\n", conn)
	defer conn.close(&conn.rDone)
	return conn.StreamConn.CloseRead()
}

func (conn *dohConnAdapter) CloseWrite() error {
	log.Printf("[debug] TCP write session [%p] terminating...\n", conn)
	defer conn.close(&conn.wDone)
	return conn.StreamConn.CloseWrite()
}

func (conn *dohConnAdapter) Read(b []byte) (n int, err error) {
	log.Printf("[debug] start downloading bytes in TCP session [%p]\n", conn)
	defer func() {
		log.Printf("[debug] TCP Session [%p]: download %v bytes, with err = %v\n", conn, n, err)
		conn.stats.DownloadBytes += int64(n)
	}()
	return conn.StreamConn.Read(b)
}

func (conn *dohConnAdapter) WriteTo(w io.Writer) (n int64, err error) {
	log.Printf("[debug] start downloading bytes until EOF in TCP session [%p]\n", conn)
	defer func() {
		log.Printf("[debug] TCP Session [%p]: download %v bytes, with err = %v\n", conn, n, err)
		conn.stats.DownloadBytes += n
	}()
	return io.Copy(w, conn.StreamConn)
}

func (conn *dohConnAdapter) Write(b []byte) (n int, err error) {
	log.Printf("[debug] start uploading bytes in TCP session [%p]\n", conn)
	defer func() {
		log.Printf("[debug] TCP Session [%p]: upload %v bytes, with err = %v\n", conn, n, err)
		conn.stats.UploadBytes += int64(n)
	}()
	return conn.StreamConn.Write(b)
}

func (conn *dohConnAdapter) ReadFrom(r io.Reader) (n int64, err error) {
	log.Printf("[debug] start uploading bytes until EOF in TCP session [%p]\n", conn)
	defer func() {
		log.Printf("[debug] TCP Session [%p]: upload %v bytes, with err = %v\n", conn, n, err)
		conn.stats.UploadBytes += n
	}()
	return io.Copy(conn.StreamConn, r)
}

func (conn *dohConnAdapter) close(done *atomic.Bool) {
	// make sure conn.wg is being called at most once for a specific `done` flag
	if done.CompareAndSwap(false, true) {
		conn.wg.Done()
	}
}
