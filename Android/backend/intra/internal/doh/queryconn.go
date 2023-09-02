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

// TODO: this file can be removed once we add StreamProxy to Outline SDK

import (
	"errors"
	"io"
	"log"
	"net"
	"time"

	"github.com/Jigsaw-Code/outline-sdk/transport"
)

var errUnsupported = errors.New("feature is not supported")

// twoWayPipe connects two I/O endpoints (source and dest) bidirectionally.
// The type itself also acts as the dest's [io.ReadWriteCloser].
type dohQueryServerConn struct {
	conn *dohQueryStreamConn
}

// dohQueryStreamConn is an "outbound" [transport.StreamConn] that handles DNS-over-TCP (DoT) traffic.
// "Outbound" means Reading responses from the remote DoT server, and Writing requests to DoT server.
//
// dohQueryStreamConn also contains a corresponding serverConn, which is an "inbound" [io.ReadWriteCloser].
// "Inbound" means Reading requests from the client, and Writing responses to the client.
type dohQueryStreamConn struct {
	reqReader, respReader *io.PipeReader
	reqWriter, respWriter *io.PipeWriter
	serverConn            io.ReadWriteCloser
}

var _ io.ReadWriteCloser = (*dohQueryServerConn)(nil)
var _ transport.StreamConn = (*dohQueryStreamConn)(nil)

func makeDoHQueryStreamConn() (conn *dohQueryStreamConn) {
	defer log.Println("[info] DoT over DoH session initialized")
	conn = &dohQueryStreamConn{}
	conn.reqReader, conn.reqWriter = io.Pipe()
	conn.respReader, conn.respWriter = io.Pipe()
	conn.serverConn = &dohQueryServerConn{conn}
	return
}

func (p *dohQueryServerConn) Close() error {
	return p.conn.Close()
}

func (p *dohQueryServerConn) Read(data []byte) (int, error) {
	log.Printf("[debug] Sending DoH request (%v bytes)\n", len(data))
	return p.conn.reqReader.Read(data)
}

func (p *dohQueryServerConn) Write(data []byte) (int, error) {
	log.Printf("[debug] Got DoH response (%v bytes)\n", len(data))
	return p.conn.respWriter.Write(data)
}

func (conn *dohQueryStreamConn) Close() error {
	return errors.Join(conn.CloseRead(), conn.CloseWrite())
}

func (conn *dohQueryStreamConn) CloseRead() error {
	defer log.Println("[info] DoT over DoH read session terminated")
	return errors.Join(conn.respReader.Close(), conn.respWriter.Close())
}

func (conn *dohQueryStreamConn) CloseWrite() error {
	defer log.Println("[info] DoT over DoH write session terminated")
	return errors.Join(conn.reqReader.Close(), conn.respWriter.Close())
}

func (conn *dohQueryStreamConn) Read(b []byte) (int, error) {
	log.Printf("[debug] Got DoT response (%v bytes)\n", len(b))
	return conn.respReader.Read(b)
}

func (conn *dohQueryStreamConn) Write(b []byte) (int, error) {
	log.Printf("[debug] Handling DoT request (%v bytes)\n", len(b))
	return conn.reqWriter.Write(b)
}

// LocalAddr returns nil.
func (*dohQueryStreamConn) LocalAddr() net.Addr {
	return nil
}

// RemoteAddr returns nil.
func (*dohQueryStreamConn) RemoteAddr() net.Addr {
	return nil
}

// SetDeadline is not supported.
func (*dohQueryStreamConn) SetDeadline(t time.Time) error {
	return errUnsupported
}

// SetReadDeadline is not supported.
func (*dohQueryStreamConn) SetReadDeadline(t time.Time) error {
	return errUnsupported
}

// SetWriteDeadline is not supported.
func (*dohQueryStreamConn) SetWriteDeadline(t time.Time) error {
	return errUnsupported
}
