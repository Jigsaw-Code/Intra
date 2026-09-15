//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package protect

import (
	"encoding/binary"
	"net"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

const ipUnicastIf = 31

type Protector struct {
	InterfaceIndex int
	Resolvers      []string
}

func (p *Protector) Protect(socket int32) bool {
	if p == nil || p.InterfaceIndex == 0 {
		return true
	}
	index := make([]byte, 4)
	binary.BigEndian.PutUint32(index, uint32(p.InterfaceIndex))
	v4Index := *(*uint32)(unsafe.Pointer(&index[0]))
	if err := windows.SetsockoptInt(windows.Handle(socket), windows.IPPROTO_IP, ipUnicastIf, int(v4Index)); err != nil {
		return false
	}
	_ = windows.SetsockoptInt(windows.Handle(socket), windows.IPPROTO_IPV6, ipUnicastIf, p.InterfaceIndex)
	return true
}

func (p *Protector) GetResolvers() string {
	if p == nil || len(p.Resolvers) == 0 {
		return "8.8.8.8,1.1.1.1"
	}
	var valid []string
	for _, resolver := range p.Resolvers {
		if ip := net.ParseIP(strings.TrimSpace(resolver)); ip != nil {
			valid = append(valid, ip.String())
		}
	}
	if len(valid) == 0 {
		return "8.8.8.8,1.1.1.1"
	}
	return strings.Join(valid, ",")
}
