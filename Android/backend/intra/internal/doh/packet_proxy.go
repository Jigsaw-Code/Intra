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
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"sync/atomic"
	"time"

	intraLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/protect"
	"github.com/Jigsaw-Code/outline-sdk/network"
	"github.com/Jigsaw-Code/outline-sdk/transport"
)

type DoHPacketProxy interface {
	network.PacketProxy

	SetDoHTransport(DoHTransport) error
}

type dohPacketProxy struct {
	fakeDNSAddr netip.AddrPort
	dohServer   atomic.Pointer[DoHTransport]
	proxy       network.PacketProxy
	listener    intraLegacy.UDPListener
}

var _ DoHPacketProxy = (*dohPacketProxy)(nil)

func MakeDoHPacketProxy(fakeDNS netip.AddrPort, dohServer DoHTransport, protector Protector, listener intraLegacy.UDPListener) (DoHPacketProxy, error) {
	if dohServer == nil {
		return nil, errors.New("dohServer is required")
	}

	pl := &transport.UDPPacketListener{
		ListenConfig: *protect.MakeListenConfig(protector),
		Address:      ":0",
	}

	pp, err := network.NewPacketProxyFromPacketListener(pl)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet proxy from listener: %w", err)
	}

	dohpp := &dohPacketProxy{
		fakeDNSAddr: fakeDNS,
		proxy:       pp,
		listener:    listener,
	}
	dohpp.dohServer.Store(&dohServer)

	return dohpp, nil
}

// NewSession implements PacketProxy.NewSession.
func (p *dohPacketProxy) NewSession(resp network.PacketResponseReceiver) (network.PacketRequestSender, error) {
	log.Println("[debug] initializing a new UDP session...")
	defer log.Println("[info] New UDP session initialized")

	dohResp := &dohPacketRespReceiver{
		PacketResponseReceiver: resp,
		stats: &udpTrafficStats{
			sessionStartTime: time.Now(),
		},
		listener: p.listener,
	}
	req, err := p.proxy.NewSession(dohResp)
	if err != nil {
		log.Printf("[error] failed to create UDP session: %v\n", err)
		return nil, fmt.Errorf("failed to create new session: %w", err)
	}
	return &dohPacketReqSender{
		PacketRequestSender: req,
		proxy:               p,
		response:            dohResp,
		stats:               dohResp.stats,
	}, nil
}

// SetDoHTransport implements DoHPacketProxy.SetDoHTransport.
func (p *dohPacketProxy) SetDoHTransport(dohServer DoHTransport) error {
	if dohServer == nil {
		return errors.New("dohServer is required")
	}
	p.dohServer.Store(&dohServer)
	log.Println("[info] DoH server updated for UDP sessions")
	return nil
}

// DoH UDP session statistics data
type udpTrafficStats struct {
	sessionStartTime time.Time
	downloadBytes    atomic.Int64
	uploadBytes      atomic.Int64
}

// DoH PacketRequestSender wrapper
type dohPacketReqSender struct {
	network.PacketRequestSender

	response *dohPacketRespReceiver
	proxy    *dohPacketProxy
	stats    *udpTrafficStats
}

// DoH PacketResponseReceiver wrapper
type dohPacketRespReceiver struct {
	network.PacketResponseReceiver

	stats    *udpTrafficStats
	listener intraLegacy.UDPListener
}

var _ network.PacketRequestSender = (*dohPacketReqSender)(nil)
var _ network.PacketResponseReceiver = (*dohPacketRespReceiver)(nil)

// WriteTo implements PacketRequestSender.WriteTo. It will query the DoH server if the packet a DNS packet.
func (req *dohPacketReqSender) WriteTo(p []byte, destination netip.AddrPort) (int, error) {
	if destination == req.proxy.fakeDNSAddr {
		defer func() {
			// conn was only used for this DNS query, so it's unlikely to be used again
			// see: https://github.com/Jigsaw-Code/outline-go-tun2socks/blob/master/intra/udp.go#L144C3-L144C79
			if req.stats.downloadBytes.Load() == 0 && req.stats.uploadBytes.Load() == 0 {
				log.Println("[debug] DoH dedicated session finished, Closing...")
				req.Close()
			}
		}()

		log.Println("[debug] Doing DNS request over DoH server...")
		resp, err := (*req.proxy.dohServer.Load()).Query(p)
		if err != nil {
			log.Printf("[error] DoH request failed: %v\n", err)
			return 0, fmt.Errorf("DoH request error: %w", err)
		}
		if len(resp) == 0 {
			log.Println("[error] DoH response is empty")
			return 0, errors.New("empty DoH response")
		}

		log.Printf("[info] Write DoH response (%v bytes) from %v\n", len(resp), req.proxy.fakeDNSAddr)
		return req.response.writeFrom(resp, net.UDPAddrFromAddrPort(req.proxy.fakeDNSAddr), false)
	}

	log.Printf("[debug] UDP Session: upload %v bytes to %v\n", len(p), destination)
	req.stats.uploadBytes.Add(int64(len(p)))
	return req.PacketRequestSender.WriteTo(p, destination)
}

// Close terminates the UDP session, and reports session stats to the listener.
func (resp *dohPacketRespReceiver) Close() error {
	defer log.Printf("[info] UDP session terminated, stats = %v\n", resp.stats)
	log.Println("[debug] UDP session terminating...")
	if resp.listener != nil {
		resp.listener.OnUDPSocketClosed(&intraLegacy.UDPSocketSummary{
			Duration:      int32(time.Since(resp.stats.sessionStartTime)),
			UploadBytes:   resp.stats.uploadBytes.Load(),
			DownloadBytes: resp.stats.downloadBytes.Load(),
		})
	}
	return resp.PacketResponseReceiver.Close()
}

// WriteFrom implements PacketResponseReceiver.WriteFrom.
func (resp *dohPacketRespReceiver) WriteFrom(p []byte, source net.Addr) (int, error) {
	return resp.writeFrom(p, source, true)
}

// writeFrom writes to the underlying PacketResponseReceiver.
// It will also add len(p) to downloadBytes if doStat is true.
func (resp *dohPacketRespReceiver) writeFrom(p []byte, source net.Addr, doStat bool) (int, error) {
	if doStat {
		log.Printf("[debug] UDP Session: download %v bytes from %v\n", len(p), source)
		resp.stats.downloadBytes.Add(int64(len(p)))
	}
	return resp.PacketResponseReceiver.WriteFrom(p, source)
}
