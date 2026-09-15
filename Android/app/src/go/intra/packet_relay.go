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

package intra

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"localhost/Intra/Android/app/src/go/doh"
	"localhost/Intra/Android/app/src/go/intra/protect"

	"golang.getoutline.org/sdk/network/packetrelay"
	"golang.getoutline.org/sdk/transport"
)

// intraPacketRelay is a [packetrelay.PacketRelay] that answers DNS queries addressed to the
// fake DNS server with the configured DoH resolver, and relays all other packets to their
// destination.
type intraPacketRelay struct {
	fakeDNSAddr netip.AddrPort
	dns         atomic.Pointer[doh.Resolver]
	base        packetrelay.PacketRelay
	listener    UDPListener
	ctx         context.Context
}

var _ packetrelay.PacketRelay = (*intraPacketRelay)(nil)

func newIntraPacketRelay(
	ctx context.Context, fakeDNS netip.AddrPort, dns doh.Resolver, protector protect.Protector, listener UDPListener,
) (*intraPacketRelay, error) {
	if dns == nil {
		return nil, errors.New("dns is required")
	}

	pl := &transport.UDPListener{
		ListenConfig: *protect.MakeListenConfig(protector),
	}

	// RFC 4787 REQ-5 requires a timeout no shorter than 5 minutes. The timeout is reset by
	// outgoing packets only, matching the previous behavior.
	base, err := packetrelay.NewPacketRelayFromPacketListener(pl, 5*time.Minute)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet relay from listener: %w", err)
	}

	r := &intraPacketRelay{
		fakeDNSAddr: fakeDNS,
		base:        base,
		listener:    listener,
		ctx:         ctx,
	}
	r.dns.Store(&dns)

	return r, nil
}

// NewAssociation implements [packetrelay.PacketRelay].NewAssociation.
func (r *intraPacketRelay) NewAssociation() (packetrelay.PacketSender, packetrelay.PacketReceiver, error) {
	sender, receiver, err := r.base.NewAssociation()
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create new association: %w", err)
	}

	a := &dohAssociation{
		relay:        r,
		sender:       sender,
		receiver:     receiver,
		stats:        makeTracker(),
		handlerReady: make(chan struct{}),
		closed:       make(chan struct{}),
	}
	return &dohPacketSender{a}, &dohPacketReceiver{a}, nil
}

func (r *intraPacketRelay) SetDNS(dns doh.Resolver) error {
	if dns == nil {
		return errors.New("dns is required")
	}
	r.dns.Store(&dns)
	return nil
}

// dohAssociation is the shared state of a UDP association. Its sender half intercepts DNS
// queries, and its receiver half reports the association stats once it concludes.
type dohAssociation struct {
	relay    *intraPacketRelay
	sender   packetrelay.PacketSender
	receiver packetrelay.PacketReceiver
	stats    *tracker

	// handlerReady is closed once handler is set by ReceivePackets. handler must not be read
	// before then.
	handlerReady chan struct{}
	handler      packetrelay.PacketHandler
	handlerSet   atomic.Bool

	mu       sync.Mutex // Protects isClosed.
	isClosed bool
	closed   chan struct{} // Closed when the association is closed.

	reportOnce sync.Once
}

// close terminates the association. Closing the underlying sender also unblocks
// ReceivePackets, which performs the remaining cleanup.
func (a *dohAssociation) close() error {
	a.mu.Lock()
	if a.isClosed {
		a.mu.Unlock()
		return packetrelay.ErrClosed
	}
	a.isClosed = true
	a.mu.Unlock()

	close(a.closed)
	return a.sender.Close()
}

// report notifies the listener that the association has concluded.
func (a *dohAssociation) report() {
	a.reportOnce.Do(func() {
		if a.relay.listener == nil {
			return
		}
		a.relay.listener.OnUDPSocketClosed(&UDPSocketSummary{
			Duration:      int32(time.Since(a.stats.start)),
			UploadBytes:   a.stats.upload.Load(),
			DownloadBytes: a.stats.download.Load(),
		})
	})
}

// handleDNSQuery resolves a DNS query with the DoH resolver and delivers the response to the
// network stack, as if it came from the fake DNS server.
func (a *dohAssociation) handleDNSQuery(p []byte) error {
	defer func() {
		// The association was only used for this DNS query, so it's unlikely to be used again.
		if a.stats.download.Load() == 0 && a.stats.upload.Load() == 0 {
			a.close()
		}
	}()

	resp, err := (*a.relay.dns.Load()).Query(a.relay.ctx, p)
	if err != nil {
		return fmt.Errorf("DoH request error: %w", err)
	}
	if len(resp) == 0 {
		return errors.New("empty DoH response")
	}

	// DNS responses are not counted in the association stats.
	select {
	case <-a.handlerReady:
		return a.handler.HandlePacket(resp, a.relay.fakeDNSAddr)
	case <-a.closed:
		return packetrelay.ErrClosed
	}
}

// dohPacketSender is the sender half of a [dohAssociation].
type dohPacketSender struct {
	a *dohAssociation
}

var _ packetrelay.PacketSender = (*dohPacketSender)(nil)

// SendPacket implements [packetrelay.PacketSender].SendPacket. It queries the DoH server if
// the packet is a DNS packet.
func (s *dohPacketSender) SendPacket(p []byte, destination netip.AddrPort) error {
	select {
	case <-s.a.closed:
		return packetrelay.ErrClosed
	default:
	}

	if isEquivalentAddrPort(destination, s.a.relay.fakeDNSAddr) {
		return s.a.handleDNSQuery(p)
	}

	s.a.stats.upload.Add(int64(len(p)))
	return s.a.sender.SendPacket(p, destination)
}

// Close implements [packetrelay.PacketSender].Close.
func (s *dohPacketSender) Close() error {
	return s.a.close()
}

// dohPacketReceiver is the receiver half of a [dohAssociation].
type dohPacketReceiver struct {
	a *dohAssociation
}

var _ packetrelay.PacketReceiver = (*dohPacketReceiver)(nil)

// ReceivePackets implements [packetrelay.PacketReceiver].ReceivePackets. It returns when the
// association is closed, and reports the association stats to the listener.
func (r *dohPacketReceiver) ReceivePackets(handler packetrelay.PacketHandler) error {
	a := r.a
	if handler == nil {
		return errors.New("handler is required")
	}
	if !a.handlerSet.CompareAndSwap(false, true) {
		return errors.New("ReceivePackets called multiple times")
	}
	a.handler = handler
	close(a.handlerReady)

	defer a.report()
	return a.receiver.ReceivePackets(&statsPacketHandler{stats: a.stats, handler: handler})
}

// statsPacketHandler counts the bytes received by the association before handing the packet
// over to the network stack.
type statsPacketHandler struct {
	stats   *tracker
	handler packetrelay.PacketHandler
}

var _ packetrelay.PacketHandler = (*statsPacketHandler)(nil)

func (h *statsPacketHandler) HandlePacket(p []byte, source netip.AddrPort) error {
	h.stats.download.Add(int64(len(p)))
	return h.handler.HandlePacket(p, source)
}
