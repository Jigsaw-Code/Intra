//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package uiapi

import (
	_ "embed"
	"log"
	"os"
	"path/filepath"
	"time"
	"unsafe"

	"localhost/Intra/internal/windows/state"

	"golang.org/x/sys/windows"
)

const (
	imageIcon      = 1
	lrLoadFromFile = 0x00000010
	wmSetIcon      = 0x0080
	iconSmall      = 0
	iconBig        = 1
	smallIconSize  = 16
	bigIconSize    = 32
)

var (
	user32              = windows.NewLazySystemDLL("user32.dll")
	procEnumWindows     = user32.NewProc("EnumWindows")
	procGetWindowPID    = user32.NewProc("GetWindowThreadProcessId")
	procIsWindowVisible = user32.NewProc("IsWindowVisible")
	procLoadImage       = user32.NewProc("LoadImageW")
	procSendMessage     = user32.NewProc("SendMessageW")
)

//go:embed assets/intra.ico
var intraWindowIcon []byte

// SetWindowIcon applies the repository-derived Intra icon to the Wails window at runtime.
// The production go build path does not run the Wails resource packager, so this is the
// reliable local prototype path for the window/taskbar icon.
func (a *App) SetWindowIcon() {
	var hwnd windows.Handle
	for i := 0; i < 20; i++ {
		time.Sleep(250 * time.Millisecond)
		hwnd = windowForCurrentProcess()
		if hwnd != 0 {
			break
		}
	}
	if hwnd == 0 {
		return
	}
	smallIcon := loadWindowIcon(smallIconSize)
	bigIcon := loadWindowIcon(bigIconSize)
	if smallIcon == 0 && bigIcon == 0 {
		return
	}
	if smallIcon != 0 {
		procSendMessage.Call(uintptr(hwnd), wmSetIcon, iconSmall, smallIcon)
	}
	if bigIcon != 0 {
		procSendMessage.Call(uintptr(hwnd), wmSetIcon, iconBig, bigIcon)
	}
}

func loadWindowIcon(size uintptr) uintptr {
	path := filepath.Join(filepath.Dir(state.LogPath()), "intra-window.ico")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		log.Printf("create icon cache dir failed: %v", err)
		return 0
	}
	if err := os.WriteFile(path, intraWindowIcon, 0600); err != nil {
		log.Printf("write window icon cache failed: %v", err)
		return 0
	}
	icon, _, err := procLoadImage.Call(
		0,
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(path))),
		imageIcon,
		size,
		size,
		lrLoadFromFile,
	)
	if icon == 0 {
		log.Printf("LoadImageW window icon failed for size %d: %v", size, err)
	}
	return icon
}

func windowForCurrentProcess() windows.Handle {
	pid := uint32(os.Getpid())
	var found windows.Handle
	cb := windows.NewCallback(func(hwnd uintptr, _ uintptr) uintptr {
		visible, _, _ := procIsWindowVisible.Call(hwnd)
		if visible == 0 {
			return 1
		}
		var windowPID uint32
		procGetWindowPID.Call(hwnd, uintptr(unsafe.Pointer(&windowPID)))
		if windowPID == pid {
			found = windows.Handle(hwnd)
			return 0
		}
		return 1
	})
	procEnumWindows.Call(cb, 0)
	return found
}
