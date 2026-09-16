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
	"net"
	"net/netip"
	"testing"
	"time"

	"golang.getoutline.org/sdk/dns"
	"golang.getoutline.org/sdk/network/dnsintercept"
	"golang.getoutline.org/sdk/network/packetrelay"

	"github.com/stretchr/testify/require"
)

const testTimeout = 10 * time.Second

var testFakeDNSAddr = netip.MustParseAddrPort("10.111.222.3:53")

// capturedPacket is a packet delivered to the network stack.
type capturedPacket struct {
	payload []byte
	source  netip.AddrPort
}

// captureHandler is a [packetrelay.PacketHandler] that records the packets it receives.
type captureHandler struct {
	packets chan capturedPacket
}

var _ packetrelay.PacketHandler = (*captureHandler)(nil)

func newCaptureHandler() *captureHandler {
	return &captureHandler{packets: make(chan capturedPacket, 16)}
}

func (h *captureHandler) HandlePacket(p []byte, source netip.AddrPort) error {
	// p must not be retained, so make a copy.
	h.packets <- capturedPacket{payload: append([]byte(nil), p...), source: source}
	return nil
}

func (h *captureHandler) next(t *testing.T) capturedPacket {
	t.Helper()
	select {
	case p := <-h.packets:
		return p
	case <-time.After(testTimeout):
		t.Fatal("timed out waiting for a packet")
		return capturedPacket{}
	}
}

// udpListenerFunc adapts a function to the [UDPListener] interface.
type udpListenerFunc func(*UDPSocketSummary)

func (f udpListenerFunc) OnUDPSocketClosed(s *UDPSocketSummary) { f(s) }

// startAssociation creates one association on relay and runs its receive loop in the
// background. It returns the sender half, the captured packets, and the error returned by the
// receive loop.
func startAssociation(t *testing.T, relay packetrelay.PacketRelay) (
	packetrelay.PacketSender, *captureHandler, <-chan error,
) {
	t.Helper()

	sender, receiver, err := relay.NewAssociation()
	require.NoError(t, err)
	t.Cleanup(func() { sender.Close() })

	handler := newCaptureHandler()
	received := make(chan error, 1)
	go func() { received <- receiver.ReceivePackets(handler) }()

	return sender, handler, received
}

// newTestPacketRelay creates the Intra packet relay, and the summaries it reports.
func newTestPacketRelay(t *testing.T) (packetrelay.PacketRelay, <-chan *UDPSocketSummary) {
	t.Helper()

	summaries := make(chan *UDPSocketSummary, 1)
	relay, err := newIntraPacketRelay(nil, udpListenerFunc(func(s *UDPSocketSummary) { summaries <- s }))
	require.NoError(t, err)

	return relay, summaries
}

// TestAssociationRelaysPackets verifies that packets reach their destination, that responses
// are delivered back to the network stack, and that both directions are counted.
func TestAssociationRelaysPackets(t *testing.T) {
	echo := startEchoServer(t)
	relay, summaries := newTestPacketRelay(t)
	sender, handler, _ := startAssociation(t, relay)

	require.NoError(t, sender.SendPacket([]byte("ping"), echo))

	packet := handler.next(t)
	require.Equal(t, []byte("ping"), packet.payload)
	// The source may be reported as a 4-in-6 address.
	require.True(t, isEquivalentAddrPort(packet.source, echo), "got %v, want %v", packet.source, echo)

	require.NoError(t, sender.Close())
	summary := <-summaries
	require.EqualValues(t, 4, summary.UploadBytes)
	require.EqualValues(t, 4, summary.DownloadBytes)
}

// TestAssociationCloseIsIdempotent verifies the [packetrelay.PacketSender] close semantics.
func TestAssociationCloseIsIdempotent(t *testing.T) {
	echo := startEchoServer(t)
	relay, _ := newTestPacketRelay(t)
	sender, _, received := startAssociation(t, relay)

	require.NoError(t, sender.Close())
	require.ErrorIs(t, sender.Close(), packetrelay.ErrClosed)
	require.ErrorIs(t, sender.SendPacket([]byte("ping"), echo), packetrelay.ErrClosed)

	// Closing the sender must terminate ReceivePackets.
	select {
	case <-received:
	case <-time.After(testTimeout):
		t.Fatal("ReceivePackets did not return after Close")
	}
}

// TestInterceptedDNSIsNotRelayed verifies the composition used by [NewTunnel]: queries to the
// fake DNS server are answered by the resolver, never reach the relay, and are therefore not
// counted nor reported.
func TestInterceptedDNSIsNotRelayed(t *testing.T) {
	response := []byte("fake-dns-response")
	queries := make(chan []byte, 1)
	exchanger := dns.FuncExchanger(func(_ context.Context, q []byte) ([]byte, error) {
		queries <- q
		return response, nil
	})

	relay, summaries := newTestPacketRelay(t)
	sender, handler, received := startAssociation(
		t, dnsintercept.New(relay, testFakeDNSAddr, exchanger))

	require.NoError(t, sender.SendPacket([]byte("dns-query"), testFakeDNSAddr))
	require.Equal(t, []byte("dns-query"), <-queries)

	packet := handler.next(t)
	require.Equal(t, response, packet.payload)
	require.Equal(t, testFakeDNSAddr, packet.source)

	// The association was only used for this DNS query, so it is closed right away.
	select {
	case <-received:
	case <-time.After(testTimeout):
		t.Fatal("the DNS-only association was not closed")
	}

	// No UDP socket was ever opened, so there is nothing to report.
	select {
	case summary := <-summaries:
		t.Fatalf("unexpected summary for a DNS-only association: %+v", summary)
	default:
	}
}

// startEchoServer runs a UDP server that echoes back everything it receives.
func startEchoServer(t *testing.T) netip.AddrPort {
	t.Helper()

	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { conn.Close() })

	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := conn.ReadFrom(buf)
			if err != nil {
				return
			}
			if _, err := conn.WriteTo(buf[:n], addr); err != nil {
				return
			}
		}
	}()

	addr, err := netip.ParseAddrPort(conn.LocalAddr().String())
	require.NoError(t, err)
	return addr
}
