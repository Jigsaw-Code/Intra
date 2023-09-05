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
	"errors"
	"fmt"
	"log"
	"net"
	"os"

	"github.com/Jigsaw-Code/Intra/Android/backend/intra/internal/doh"
	"github.com/Jigsaw-Code/Intra/Android/backend/intra/internal/sni"
	"github.com/Jigsaw-Code/outline-sdk/network"
	"github.com/Jigsaw-Code/outline-sdk/network/lwip2transport"
)

// SocketProtector is a way to make certain sockets or DNS lookups bypassing the VPN connection. This is only needed
// for devices running Android versions older than Lollipop (21). Once a socket is protected, data sent through it will
// go directly to the internet, bypassing the VPN. The Android VpnService implements the protect() method.
type SocketProtector doh.Protector

type IntraDevice struct {
	t2s       network.IPDevice
	protector SocketProtector
	listener  eventListenerAdapter

	sd  doh.DoHStreamDialer
	pp  doh.DoHPacketProxy
	sni sni.TCPSNIReporter
}

func NewIntraDevice(fakeDNS, serverURL, fallbackAddrs string, protector SocketProtector, listener EventListener) (d *IntraDevice, err error) {
	log.Println("[debug] initializing Intra device...")

	d = &IntraDevice{
		protector: protector,
		listener:  eventListenerAdapter{listener},
	}

	fakeDNSAddr, err := net.ResolveUDPAddr("udp", fakeDNS)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve fakeDNS: %w", err)
	}

	dohServer, err := doh.MakeTransport(serverURL, fallbackAddrs, d.protector, d.listener)
	if err != nil {
		return nil, fmt.Errorf("failed to create DoH transport: %w", err)
	}

	d.sni = sni.MakeTCPReporter(dohServer)

	d.sd, err = doh.MakeDoHStreamDialer(fakeDNSAddr.AddrPort(), dohServer, d.protector, d.listener, d.sni)
	if err != nil {
		return nil, fmt.Errorf("failed to create stream dialer: %w", err)
	}

	d.pp, err = doh.MakeDoHPacketProxy(fakeDNSAddr.AddrPort(), dohServer, d.protector, d.listener)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet proxy: %w", err)
	}

	if d.t2s, err = lwip2transport.ConfigureDevice(d.sd, d.pp); err != nil {
		return nil, fmt.Errorf("failed to configure lwIP stack: %w", err)
	}

	log.Println("[info] Intra device initialized")
	return
}

func (d *IntraDevice) Close() error {
	return d.t2s.Close()
}

func (d *IntraDevice) UpdateDoHServer(serverURL, fallbackAddrs string) error {
	dohServer, err := doh.MakeTransport(serverURL, fallbackAddrs, d.protector, d.listener)
	if err != nil {
		return fmt.Errorf("failed to create DoH transport: %w", err)
	}
	return errors.Join(
		d.pp.SetDoHTransport(dohServer),
		d.pp.SetDoHTransport(dohServer),
		d.sni.SetDoHTransport(dohServer))
}

func (d *IntraDevice) EnableSNIReporter(filename, suffix, country string) error {
	f, err := os.OpenFile(filename, os.O_RDWR|os.O_CREATE, 0600)
	if err != nil {
		return err
	}
	return d.sni.Configure(f, suffix, country)
}
