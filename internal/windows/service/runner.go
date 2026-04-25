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
	"localhost/Intra/internal/windows/state"
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
	log.Printf("wintun opened: %s", tun.Name())
	if journal, err := state.LoadActive(); err != nil {
		_ = tun.Close()
		return fmt.Errorf("load tunnel journal: %w", err)
	} else if journal != nil {
		log.Printf("active tunnel journal found; restoring previous state")
		logRestoreErrors("crash recovery", netconfig.Restore(journal.Config, journal.Snapshot))
		if err := state.Clear(); err != nil {
			log.Printf("crash recovery: clear journal failed: %v", err)
		}
	}

	snapshot := netconfig.CaptureSnapshot(tun.Name())
	log.Printf("snapshot captured: physical_if=%d tunnel_dns=%v tunnel_routes=%v", snapshot.PhysicalInterfaceIndex, snapshot.TunnelDNSServers, snapshot.TunnelRoutes)
	protector := &winprotect.Protector{
		InterfaceIndex: snapshot.PhysicalInterfaceIndex,
		Resolvers:      snapshot.PhysicalResolvers,
	}
	listener := noopListener{}

	if err := state.SaveActive(netCfg, snapshot); err != nil {
		_ = tun.Close()
		return fmt.Errorf("save tunnel journal: %w", err)
	}
	log.Printf("journal saved: %s", state.Path())
	if err := netconfig.ApplyFullTunnel(netCfg); err != nil {
		if logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot)) {
			_ = state.Clear()
		}
		_ = tun.Close()
		return err
	}
	log.Printf("full tunnel network config applied")

	dohServer, err := backend.NewDoHServer(r.cfg.DoHURL, r.cfg.DoHIPs, protector, listener)
	if err != nil {
		if logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot)) {
			_ = state.Clear()
		}
		_ = tun.Close()
		return fmt.Errorf("create DoH server: %w", err)
	}

	session, err := backend.ConnectReadWriteCloser(tun, FakeDNS, dohServer, protector, listener)
	if err != nil {
		if logRestoreErrors("failed-start cleanup", netconfig.Restore(netCfg, snapshot)) {
			_ = state.Clear()
		}
		_ = tun.Close()
		return fmt.Errorf("connect Intra tunnel: %w", err)
	}

	r.tun = tun
	r.session = session
	r.snapshot = snapshot
	log.Printf("tunnel session connected")
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
	log.Printf("stop requested")
	restoreOK := logRestoreErrors("stop cleanup", netconfig.Restore(netCfg, r.snapshot))
	var err error
	if restoreOK {
		log.Printf("clearing journal")
		if clearErr := state.Clear(); clearErr != nil {
			restoreOK = false
			err = errors.Join(err, clearErr)
			log.Printf("clear journal failed: %v", clearErr)
		}
	}

	if r.session != nil {
		log.Printf("disconnecting tunnel session")
		r.session.Disconnect()
		r.session = nil
	} else if r.tun != nil {
		log.Printf("closing wintun without active session")
		err = errors.Join(err, r.tun.Close())
	}
	r.tun = nil
	r.snapshot = netconfig.Snapshot{}
	log.Printf("stop complete")
	return err
}

func RestoreJournal(journal *state.Journal) []error {
	if journal == nil {
		return nil
	}
	return netconfig.Restore(journal.Config, journal.Snapshot)
}

func logRestoreErrors(context string, errs []error) bool {
	for _, err := range errs {
		log.Printf("%s: restore failed: %v", context, err)
	}
	return len(errs) == 0
}

type noopListener struct{}

func (noopListener) OnQuery(string) backend.DoHQueryToken                      { return nil }
func (noopListener) OnResponse(backend.DoHQueryToken, *backend.DoHQuerySumary) {}
func (noopListener) OnUDPSocketClosed(*intra.UDPSocketSummary)                 {}
func (noopListener) OnTCPSocketClosed(*intra.TCPSocketSummary)                 {}
