//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package servicecontrol

import (
	"syscall"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc/mgr"
)

func Open(name string, access uint32) (*mgr.Service, func(), error) {
	m, err := openManager(windows.SC_MANAGER_CONNECT)
	if err != nil {
		return nil, nil, err
	}
	namePointer, err := syscall.UTF16PtrFromString(name)
	if err != nil {
		_ = windows.CloseServiceHandle(m.Handle)
		return nil, nil, err
	}
	handle, err := windows.OpenService(m.Handle, namePointer, access)
	if err != nil {
		_ = windows.CloseServiceHandle(m.Handle)
		return nil, nil, err
	}
	s := &mgr.Service{Name: name, Handle: handle}
	return s, func() {
		_ = s.Close()
		_ = windows.CloseServiceHandle(m.Handle)
	}, nil
}

func CreateManager() (*mgr.Mgr, error) {
	return openManager(windows.SC_MANAGER_CONNECT | windows.SC_MANAGER_CREATE_SERVICE)
}

func openManager(access uint32) (*mgr.Mgr, error) {
	handle, err := windows.OpenSCManager(nil, nil, access)
	if err != nil {
		return nil, err
	}
	return &mgr.Mgr{Handle: handle}, nil
}
