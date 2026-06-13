//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package uiapi

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

func ensureTrayRunning() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	trayPath := filepath.Join(filepath.Dir(exe), "intra-windows-tray.exe")
	if _, err := os.Stat(trayPath); err != nil {
		return fmt.Errorf("find tray helper: %w", err)
	}
	if processForExecutable(trayPath) {
		log.Printf("tray helper already running")
		return nil
	}
	cmd := exec.Command(trayPath)
	cmd.Dir = filepath.Dir(trayPath)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start tray helper: %w", err)
	}
	log.Printf("started tray helper pid=%d", cmd.Process.Pid)
	return nil
}

func processForExecutable(exePath string) bool {
	expected, err := filepath.Abs(exePath)
	if err != nil {
		expected = exePath
	}
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		log.Printf("process snapshot failed: %v", err)
		return false
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	for err = windows.Process32First(snapshot, &entry); err == nil; err = windows.Process32Next(snapshot, &entry) {
		if processPathMatches(entry.ProcessID, expected) {
			return true
		}
	}
	return false
}

func processPathMatches(pid uint32, expected string) bool {
	handle, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(handle)
	buf := make([]uint16, windows.MAX_PATH)
	size := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(handle, 0, &buf[0], &size); err != nil {
		return false
	}
	return strings.EqualFold(windows.UTF16ToString(buf[:size]), expected)
}
