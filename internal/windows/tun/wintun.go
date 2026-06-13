//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package tun

import (
	"errors"
	"fmt"
	"net"
	"os"

	wgtun "golang.zx2c4.com/wireguard/tun"
)

// Device is an io.ReadWriteCloser wrapper around Wintun.
type Device struct {
	name string
	dev  wgtun.Device
}

func Open(name string, mtu int) (*Device, error) {
	if name == "" {
		return nil, errors.New("adapter name is required")
	}
	dev, err := wgtun.CreateTUN(name, mtu)
	if err != nil {
		return nil, fmt.Errorf("create wintun adapter: %w", err)
	}
	actualName, err := dev.Name()
	if err != nil {
		_ = dev.Close()
		return nil, fmt.Errorf("read wintun adapter name: %w", err)
	}
	return &Device{name: actualName, dev: dev}, nil
}

func (d *Device) Name() string { return d.name }

func (d *Device) InterfaceIndex() (int, error) {
	iface, err := net.InterfaceByName(d.name)
	if err != nil {
		return 0, err
	}
	return iface.Index, nil
}

func (d *Device) Read(p []byte) (int, error) {
	if d == nil || d.dev == nil {
		return 0, os.ErrClosed
	}
	sizes := []int{0}
	bufs := [][]byte{p}
	_, err := d.dev.Read(bufs, sizes, 0)
	return sizes[0], err
}

func (d *Device) Write(p []byte) (int, error) {
	if d == nil || d.dev == nil {
		return 0, os.ErrClosed
	}
	_, err := d.dev.Write([][]byte{p}, 0)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (d *Device) Close() error {
	if d == nil || d.dev == nil {
		return nil
	}
	return d.dev.Close()
}
