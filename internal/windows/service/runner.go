//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package service

import (
	"errors"
	"fmt"
	"log"
	"sync"

	"localhost/Intra/Android/app/src/go/backend"
	"localhost/Intra/Android/app/src/go/intra"
	"localhost/Intra/internal/windows/netconfig"
	winprotect "localhost/Intra/internal/windows/protect"
	wintun "localhost/Intra/internal/windows/tun"
)

const (
	AdapterName = "Intra"
	AdapterIP   = "10.111.222.1"
	AdapterMask = "255.255.255.0"
	FakeDNSIP   = "10.111.222.3"
	FakeDNS     = FakeDNSIP + ":53"
	MTU         = 1500
)

type Config struct {
	DoHURL string
	DoHIPs string
}

type Runner struct {
	mu       sync.Mutex
	cfg      Config
	tun      *wintun.Device
	session  *backend.Session
	snapshot netconfig.Snapshot
}

func NewRunner(cfg Config) *Runner {
	if cfg.DoHURL == "" {
		cfg.DoHURL = "https://dns.google/dns-query"
	}
	if cfg.DoHIPs == "" {
		cfg.DoHIPs = "8.8.8.8,8.8.4.4,2001:4860:4860::8888,2001:4860:4860::8844"
	}
	return &Runner{cfg: cfg}
}

func (r *Runner) Start() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.session != nil {
		return nil
	}

	netCfg := netconfig.Config{
		InterfaceName: AdapterName,
		Address:       AdapterIP,
		Mask:          AdapterMask,
		DNS:           FakeDNSIP,
	}
	tun, err := wintun.Open(AdapterName, MTU)
	if err != nil {
		return err
	}

	snapshot := netconfig.CaptureSnapshot(tun.Name())
	protector := &winprotect.Protector{
		InterfaceIndex: snapshot.PhysicalInterfaceIndex,
		Resolvers:      snapshot.PhysicalResolvers,
	}
	listener := noopListener{}

	if err := netconfig.ApplyFullTunnel(netCfg); err != nil {
		logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot))
		_ = tun.Close()
		return err
	}

	dohServer, err := backend.NewDoHServer(r.cfg.DoHURL, r.cfg.DoHIPs, protector, listener)
	if err != nil {
		logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot))
		_ = tun.Close()
		return fmt.Errorf("create DoH server: %w", err)
	}

	session, err := backend.ConnectReadWriteCloser(tun, FakeDNS, dohServer, protector, listener)
	if err != nil {
		logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot))
		_ = tun.Close()
		return fmt.Errorf("connect Intra tunnel: %w", err)
	}

	r.tun = tun
	r.session = session
	r.snapshot = snapshot
	return nil
}

func (r *Runner) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	netCfg := netconfig.Config{
		InterfaceName: AdapterName,
		Address:       AdapterIP,
		Mask:          AdapterMask,
		DNS:           FakeDNSIP,
	}
	logRestoreErrors("stop cleanup", netconfig.Restore(netCfg, r.snapshot))

	var err error
	if r.session != nil {
		r.session.Disconnect()
		r.session = nil
	} else if r.tun != nil {
		err = errors.Join(err, r.tun.Close())
	}
	r.tun = nil
	r.snapshot = netconfig.Snapshot{}
	return err
}

func logRestoreErrors(context string, errs []error) {
	for _, err := range errs {
		log.Printf("%s: restore failed: %v", context, err)
	}
}

type noopListener struct{}

func (noopListener) OnQuery(string) backend.DoHQueryToken                      { return nil }
func (noopListener) OnResponse(backend.DoHQueryToken, *backend.DoHQuerySumary) {}
func (noopListener) OnUDPSocketClosed(*intra.UDPSocketSummary)                 {}
func (noopListener) OnTCPSocketClosed(*intra.TCPSocketSummary)                 {}
