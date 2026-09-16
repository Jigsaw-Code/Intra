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
	"fmt"
	"net/netip"
	"sync"
	"time"

	"localhost/Intra/Android/app/src/go/intra/protect"

	"golang.getoutline.org/sdk/network/packetrelay"
	"golang.getoutline.org/sdk/transport"
)

// statsPacketRelay is a [packetrelay.PacketRelay] that relays UDP packets to their destination
// and reports the traffic of each association to a [UDPListener] once it concludes.
//
// DNS is not handled here. Queries addressed to the fake DNS server are intercepted before they
// reach this relay (see [NewTunnel]), so they are structurally excluded from the reported traffic.
type statsPacketRelay struct {
	base     packetrelay.PacketRelay
	listener UDPListener
}

var _ packetrelay.PacketRelay = (*statsPacketRelay)(nil)

func newIntraPacketRelay(protector protect.Protector, listener UDPListener) (packetrelay.PacketRelay, error) {
	pl := &transport.UDPListener{
		ListenConfig: *protect.MakeListenConfig(protector),
	}

	// RFC 4787 REQ-5 requires a timeout no shorter than 5 minutes. The timeout is reset by
	// outgoing packets only, matching the previous behavior.
	base, err := packetrelay.NewPacketRelayFromPacketListener(pl, 5*time.Minute)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet relay from listener: %w", err)
	}

	return &statsPacketRelay{base: base, listener: listener}, nil
}

// NewAssociation implements [packetrelay.PacketRelay].NewAssociation.
func (r *statsPacketRelay) NewAssociation() (packetrelay.PacketSender, packetrelay.PacketReceiver, error) {
	sender, receiver, err := r.base.NewAssociation()
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create new association: %w", err)
	}

	stats := makeTracker()
	return &statsPacketSender{sender: sender, stats: stats},
		&statsPacketReceiver{receiver: receiver, stats: stats, listener: r.listener},
		nil
}

// statsPacketSender counts the bytes sent by an association.
type statsPacketSender struct {
	sender packetrelay.PacketSender
	stats  *tracker
}

var _ packetrelay.PacketSender = (*statsPacketSender)(nil)

// SendPacket implements [packetrelay.PacketSender].SendPacket.
func (s *statsPacketSender) SendPacket(p []byte, destination netip.AddrPort) error {
	s.stats.upload.Add(int64(len(p)))
	return s.sender.SendPacket(p, destination)
}

// Close implements [packetrelay.PacketSender].Close.
func (s *statsPacketSender) Close() error {
	return s.sender.Close()
}

// statsPacketReceiver counts the bytes received by an association and reports its stats to the
// listener once the association concludes.
type statsPacketReceiver struct {
	receiver   packetrelay.PacketReceiver
	stats      *tracker
	listener   UDPListener
	reportOnce sync.Once
}

var _ packetrelay.PacketReceiver = (*statsPacketReceiver)(nil)

// ReceivePackets implements [packetrelay.PacketReceiver].ReceivePackets. It returns when the
// association is closed, and reports the association stats to the listener.
func (r *statsPacketReceiver) ReceivePackets(handler packetrelay.PacketHandler) error {
	defer r.report()
	return r.receiver.ReceivePackets(&statsPacketHandler{stats: r.stats, handler: handler})
}

// report notifies the listener that the association has concluded.
func (r *statsPacketReceiver) report() {
	r.reportOnce.Do(func() {
		if r.listener == nil {
			return
		}
		r.listener.OnUDPSocketClosed(&UDPSocketSummary{
			Duration:      int32(time.Since(r.stats.start)),
			UploadBytes:   r.stats.upload.Load(),
			DownloadBytes: r.stats.download.Load(),
		})
	})
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
