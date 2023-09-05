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
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"sync/atomic"
	"time"

	"github.com/Jigsaw-Code/Intra/Android/backend/intra/internal/sni"
	intraLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/doh"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/protect"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/split"
	"github.com/Jigsaw-Code/outline-sdk/transport"
)

type DoHStreamDialer interface {
	transport.StreamDialer

	SetDoHTransport(DoHTransport) error
}

type tcpTrafficStats = intraLegacy.TCPSocketSummary

type dohSplitStreamDialer struct {
	fakeDNSAddr      netip.AddrPort
	dohServer        atomic.Pointer[DoHTransport]
	dialer           *net.Dialer
	alwaysSplitHTTPS atomic.Bool
	listener         intraLegacy.TCPListener
	sniReporter      sni.TCPSNIReporter
}

var _ DoHStreamDialer = (*dohSplitStreamDialer)(nil)

func MakeDoHStreamDialer(fakeDNS netip.AddrPort, dohServer DoHTransport, protector Protector, listener intraLegacy.TCPListener, sniReporter sni.TCPSNIReporter) (DoHStreamDialer, error) {
	if dohServer == nil {
		return nil, errors.New("dohServer is required")
	}

	dohsd := &dohSplitStreamDialer{
		fakeDNSAddr: fakeDNS,
		dialer:      protect.MakeDialer(protector),
		listener:    listener,
		sniReporter: sniReporter,
	}
	dohsd.dohServer.Store(&dohServer)
	return dohsd, nil
}

// Dial implements StreamDialer.Dial.
func (sd *dohSplitStreamDialer) Dial(ctx context.Context, raddr string) (transport.StreamConn, error) {
	if raddr == sd.fakeDNSAddr.String() {
		log.Println("[debug] Doing DoT request over DoH server...")
		conn := makeDoHQueryStreamConn()
		go doh.Accept(*sd.dohServer.Load(), conn.serverConn)
		return conn, nil
	}

	dest, err := netip.ParseAddrPort(raddr)
	if err != nil {
		return nil, fmt.Errorf("invalid raddr (%v): %w", raddr, err)
	}

	stats := makeTCPTrafficStats(dest)
	beforeConn := time.Now()
	conn, err := sd.dial(ctx, dest, stats)
	if err != nil {
		return nil, fmt.Errorf("failed to dial to target: %w", err)
	}
	stats.Synack = int32(time.Since(beforeConn).Milliseconds())

	return makeWrapConnWithStats(conn, stats, sd.listener, sd.sniReporter), nil
}

// SetDoHTransport implements DoHStreamDialer.SetDoHTransport.
func (sd *dohSplitStreamDialer) SetDoHTransport(dohServer DoHTransport) error {
	if dohServer == nil {
		return errors.New("dohServer is required")
	}
	sd.dohServer.Store(&dohServer)
	log.Println("[info] DoH server updated for TCP sessions")
	return nil
}

func (sd *dohSplitStreamDialer) dial(ctx context.Context, dest netip.AddrPort, stats *tcpTrafficStats) (transport.StreamConn, error) {
	if dest.Port() == 443 {
		if sd.alwaysSplitHTTPS.Load() {
			return split.DialWithSplit(sd.dialer, net.TCPAddrFromAddrPort(dest))
		} else {
			stats.Retry = &split.RetryStats{}
			return split.DialWithSplitRetry(sd.dialer, net.TCPAddrFromAddrPort(dest), stats.Retry)
		}
	} else {
		tcpsd := &transport.TCPStreamDialer{
			Dialer: *sd.dialer,
		}
		return tcpsd.Dial(ctx, dest.String())
	}
}

func makeTCPTrafficStats(dest netip.AddrPort) *tcpTrafficStats {
	stats := &tcpTrafficStats{
		ServerPort: int16(dest.Port()),
	}
	if stats.ServerPort != 0 && stats.ServerPort != 80 && stats.ServerPort != 443 {
		stats.ServerPort = -1
	}
	return stats
}
