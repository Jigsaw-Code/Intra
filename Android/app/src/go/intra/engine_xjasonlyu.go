//go:build xjasonlyu_experiment || windows

// Copyright 2026 Jigsaw Operations LLC
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

package intra

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	"localhost/Intra/Android/app/src/go/logging"

	"github.com/Jigsaw-Code/outline-sdk/network"
	"github.com/Jigsaw-Code/outline-sdk/transport"
	"github.com/xjasonlyu/tun2socks/v2/buffer"
	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

const xjasonlyuMTU = 1500

type xjasonlyuEngine struct {
	ctx    context.Context
	cancel context.CancelFunc
	device *xjasonlyuPacketDevice
	stack  interface{ Close() }
	sd     *intraStreamDialer
	pp     *intraPacketProxy
}

var _ adapter.TransportHandler = (*xjasonlyuEngine)(nil)
var _ network.IPDevice = (*xjasonlyuEngine)(nil)

func newXJasonlyuEngine(ctx context.Context, sd *intraStreamDialer, pp *intraPacketProxy) (network.IPDevice, error) {
	engineCtx, cancel := context.WithCancel(ctx)
	engine := &xjasonlyuEngine{
		ctx:    engineCtx,
		cancel: cancel,
		device: newXJasonlyuPacketDevice(xjasonlyuMTU),
		sd:     sd,
		pp:     pp,
	}

	linkEndpoint, err := iobased.New(engine.device.endpointRW(), xjasonlyuMTU, 0)
	if err != nil {
		cancel()
		return nil, err
	}
	stack, err := core.CreateStack(&core.Config{
		LinkEndpoint:     linkEndpoint,
		TransportHandler: engine,
	})
	if err == nil {
		engine.stack = stack
		return engine, nil
	}
	cancel()
	return nil, err
}

func (e *xjasonlyuEngine) HandleTCP(conn adapter.TCPConn) {
	go e.handleTCP(conn)
}

func (e *xjasonlyuEngine) handleTCP(conn adapter.TCPConn) {
	defer conn.Close()

	id := conn.ID()
	dest, err := tcpipAddrPort(id.LocalAddress, id.LocalPort)
	if err != nil {
		logging.Warn("xjasonlyu TCP destination parse failed", "error", err)
		return
	}

	remoteConn, err := e.sd.Dial(e.ctx, dest.String())
	if err != nil {
		logging.Warn("xjasonlyu TCP dial failed", "dest", dest.String(), "error", err)
		return
	}
	defer remoteConn.Close()

	pipeXJasonlyuTCP(conn, remoteConn)
}

func (e *xjasonlyuEngine) HandleUDP(conn adapter.UDPConn) {
	go e.handleUDP(conn)
}

func (e *xjasonlyuEngine) handleUDP(conn adapter.UDPConn) {
	defer conn.Close()

	id := conn.ID()
	dest, err := tcpipAddrPort(id.LocalAddress, id.LocalPort)
	if err != nil {
		logging.Warn("xjasonlyu UDP destination parse failed", "error", err)
		return
	}

	resp := &xjasonlyuUDPResponseReceiver{conn: conn}
	req, err := e.pp.NewSession(resp)
	if err != nil {
		logging.Warn("xjasonlyu UDP session failed", "dest", dest.String(), "error", err)
		return
	}
	defer req.Close()
	defer resp.Close()

	buf := buffer.Get(buffer.MaxSegmentSize)
	defer buffer.Put(buf)

	for {
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Minute))
		n, _, err := conn.ReadFrom(buf)
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return
		}
		if err == io.EOF {
			return
		}
		if err != nil {
			logging.Debug("xjasonlyu UDP read failed", "dest", dest.String(), "error", err)
			return
		}
		if _, err := req.WriteTo(buf[:n], dest); err != nil {
			logging.Warn("xjasonlyu UDP write failed", "dest", dest.String(), "error", err)
			return
		}
	}
}

func (e *xjasonlyuEngine) Read(p []byte) (int, error) {
	return e.device.Read(p)
}

func (e *xjasonlyuEngine) Write(p []byte) (int, error) {
	return e.device.Write(p)
}

func (e *xjasonlyuEngine) Close() error {
	e.cancel()
	if e.stack != nil {
		e.stack.Close()
	}
	return e.device.Close()
}

func (e *xjasonlyuEngine) MTU() int {
	return xjasonlyuMTU
}

func tcpipAddrPort(addr tcpip.Address, port uint16) (netip.AddrPort, error) {
	ip, ok := netip.AddrFromSlice(addr.AsSlice())
	if !ok {
		return netip.AddrPort{}, errors.New("invalid TCP endpoint address")
	}
	return netip.AddrPortFrom(ip, port), nil
}

func pipeXJasonlyuTCP(origin adapter.TCPConn, remote transport.StreamConn) {
	var wg sync.WaitGroup
	wg.Add(2)

	go copyXJasonlyuTCP(remote, origin, &wg)
	go copyXJasonlyuTCP(origin, remote, &wg)

	wg.Wait()
}

func copyXJasonlyuTCP(dst io.Writer, src io.Reader, wg *sync.WaitGroup) {
	defer wg.Done()

	buf := buffer.Get(buffer.RelayBufferSize)
	_, _ = io.CopyBuffer(dst, src, buf)
	_ = buffer.Put(buf)

	if cr, ok := src.(interface{ CloseRead() error }); ok {
		_ = cr.CloseRead()
	}
	if cw, ok := dst.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
	if deadline, ok := dst.(interface{ SetReadDeadline(time.Time) error }); ok {
		_ = deadline.SetReadDeadline(time.Now().Add(10 * time.Second))
	}
}

type xjasonlyuPacketDevice struct {
	mtu        int
	tunToStack chan []byte
	stackToTun chan []byte
	done       chan struct{}
	once       sync.Once
}

func newXJasonlyuPacketDevice(mtu int) *xjasonlyuPacketDevice {
	return &xjasonlyuPacketDevice{
		mtu:        mtu,
		tunToStack: make(chan []byte, 1024),
		stackToTun: make(chan []byte, 1024),
		done:       make(chan struct{}),
	}
}

func (d *xjasonlyuPacketDevice) Read(p []byte) (int, error) {
	return d.readPacket(p, d.stackToTun)
}

func (d *xjasonlyuPacketDevice) Write(p []byte) (int, error) {
	return d.writePacket(p, d.tunToStack)
}

func (d *xjasonlyuPacketDevice) Close() error {
	d.once.Do(func() {
		close(d.done)
	})
	return nil
}

func (d *xjasonlyuPacketDevice) MTU() int {
	return d.mtu
}

func (d *xjasonlyuPacketDevice) endpointRW() io.ReadWriter {
	return &xjasonlyuEndpointRW{device: d}
}

func (d *xjasonlyuPacketDevice) readPacket(p []byte, ch <-chan []byte) (int, error) {
	select {
	case pkt := <-ch:
		return copy(p, pkt), nil
	case <-d.done:
		return 0, io.EOF
	}
}

func (d *xjasonlyuPacketDevice) writePacket(p []byte, ch chan<- []byte) (int, error) {
	if len(p) > d.mtu {
		return 0, network.ErrMsgSize
	}
	pkt := append([]byte(nil), p...)
	select {
	case ch <- pkt:
		return len(p), nil
	case <-d.done:
		return 0, io.ErrClosedPipe
	}
}

type xjasonlyuEndpointRW struct {
	device *xjasonlyuPacketDevice
}

func (rw *xjasonlyuEndpointRW) Read(p []byte) (int, error) {
	return rw.device.readPacket(p, rw.device.tunToStack)
}

func (rw *xjasonlyuEndpointRW) Write(p []byte) (int, error) {
	return rw.device.writePacket(p, rw.device.stackToTun)
}

type xjasonlyuUDPResponseReceiver struct {
	conn adapter.UDPConn
	once sync.Once
}

var _ network.PacketResponseReceiver = (*xjasonlyuUDPResponseReceiver)(nil)

func (r *xjasonlyuUDPResponseReceiver) WriteFrom(p []byte, source net.Addr) (int, error) {
	return r.conn.WriteTo(p, nil)
}

func (r *xjasonlyuUDPResponseReceiver) Close() error {
	var err error
	r.once.Do(func() {
		err = r.conn.Close()
	})
	return err
}
