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
	"net"
	"net/netip"
	"testing"
	"time"

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

// startAssociation creates a relay and one association, running the receive loop in the
// background. It returns the association halves, the captured packets, and the summary
// reported when the association concludes.
func startAssociation(t *testing.T, query qfunc) (
	packetrelay.PacketSender, *captureHandler, <-chan *UDPSocketSummary, <-chan error,
) {
	t.Helper()

	summaries := make(chan *UDPSocketSummary, 1)
	relay, err := newIntraPacketRelay(
		context.Background(), testFakeDNSAddr, newFakeTransport(query), nil,
		udpListenerFunc(func(s *UDPSocketSummary) { summaries <- s }),
	)
	require.NoError(t, err)

	sender, receiver, err := relay.NewAssociation()
	require.NoError(t, err)
	t.Cleanup(func() { sender.Close() })

	handler := newCaptureHandler()
	received := make(chan error, 1)
	go func() { received <- receiver.ReceivePackets(handler) }()

	return sender, handler, summaries, received
}

// TestAssociationAnswersDNSLocally verifies that queries to the fake DNS server are answered
// by the resolver, that the response appears to come from the fake DNS server, and that the
// DNS-only association is torn down immediately afterwards without reporting any traffic.
func TestAssociationAnswersDNSLocally(t *testing.T) {
	response := []byte("fake-dns-response")
	queries := make(chan []byte, 1)
	sender, handler, summaries, received := startAssociation(t, func(q []byte) ([]byte, error) {
		queries <- q
		return response, nil
	})

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

	summary := <-summaries
	require.Zero(t, summary.UploadBytes, "DNS queries must not be counted")
	require.Zero(t, summary.DownloadBytes, "DNS responses must not be counted")
}

// TestAssociationReportsDNSErrors verifies that resolution failures are reported to the caller.
func TestAssociationReportsDNSErrors(t *testing.T) {
	t.Run("query error", func(t *testing.T) {
		sender, _, _, _ := startAssociation(t, func([]byte) ([]byte, error) {
			return nil, errors.New("no service")
		})
		require.Error(t, sender.SendPacket([]byte("dns-query"), testFakeDNSAddr))
	})

	t.Run("empty response", func(t *testing.T) {
		sender, _, _, _ := startAssociation(t, func([]byte) ([]byte, error) {
			return nil, nil
		})
		require.Error(t, sender.SendPacket([]byte("dns-query"), testFakeDNSAddr))
	})
}

// TestAssociationRelaysPackets verifies that non-DNS packets reach their destination, that
// responses are delivered back to the network stack, and that both directions are counted.
func TestAssociationRelaysPackets(t *testing.T) {
	echo := startEchoServer(t)
	sender, handler, summaries, _ := startAssociation(t, failingQuery(t))

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
	sender, _, _, received := startAssociation(t, failingQuery(t))

	require.NoError(t, sender.Close())
	require.ErrorIs(t, sender.Close(), packetrelay.ErrClosed)
	require.ErrorIs(t, sender.SendPacket([]byte("ping"), echo), packetrelay.ErrClosed)
	// A closed association must not issue DNS queries either.
	require.ErrorIs(t, sender.SendPacket([]byte("dns-query"), testFakeDNSAddr), packetrelay.ErrClosed)

	// Closing the sender must terminate ReceivePackets.
	select {
	case <-received:
	case <-time.After(testTimeout):
		t.Fatal("ReceivePackets did not return after Close")
	}
}

func failingQuery(t *testing.T) qfunc {
	return func([]byte) ([]byte, error) {
		t.Error("unexpected DNS query")
		return nil, errors.New("unexpected DNS query")
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
