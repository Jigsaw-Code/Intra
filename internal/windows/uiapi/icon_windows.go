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
	wmSize         = 0x0005
	wmSysCommand   = 0x0112
	iconSmall      = 0
	iconBig        = 1
	iconSmall2     = 2
	smallIconSize  = 16
	bigIconSize    = 32
	gwlpWndProc    = ^uintptr(3)
	scMinimize     = 0xf020
	sizeMinimized  = 1
	swHide         = 0
)

var (
	user32              = windows.NewLazySystemDLL("user32.dll")
	kernel32            = windows.NewLazySystemDLL("kernel32.dll")
	procCallWindowProc  = user32.NewProc("CallWindowProcW")
	procEnumWindows     = user32.NewProc("EnumWindows")
	procGetWindowPID    = user32.NewProc("GetWindowThreadProcessId")
	procIsWindowVisible = user32.NewProc("IsWindowVisible")
	procLoadImage       = user32.NewProc("LoadImageW")
	procSendMessage     = user32.NewProc("SendMessageW")
	procSetWindowLong   = user32.NewProc("SetWindowLongPtrW")
	procShowWindow      = user32.NewProc("ShowWindow")
	procGetModuleHandle = kernel32.NewProc("GetModuleHandleW")

	windowProcOriginal uintptr
	windowProcCallback = windows.NewCallback(uiWindowProc)
)

//go:embed assets/intra.ico
var intraWindowIcon []byte

// SetWindowIcon applies the repository-derived Intra icon to the Wails window at runtime.
// The production go build path does not run the Wails resource packager, so this is the
// reliable local prototype path for the window/taskbar icon.
func (a *App) SetWindowIcon() {
	var hwnd windows.Handle
	for i := 0; i < 80; i++ {
		time.Sleep(250 * time.Millisecond)
		hwnd = windowForCurrentProcess()
		if hwnd != 0 && windowIsVisible(hwnd) {
			break
		}
	}
	if hwnd == 0 {
		log.Printf("SetWindowIcon skipped: Wails window handle not found")
		return
	}
	smallIcon := loadWindowIcon(smallIconSize)
	bigIcon := loadWindowIcon(bigIconSize)
	if smallIcon == 0 && bigIcon == 0 {
		log.Printf("SetWindowIcon skipped: icon handles not loaded")
		return
	}
	if smallIcon != 0 {
		retSmall, _, _ := procSendMessage.Call(uintptr(hwnd), wmSetIcon, iconSmall, smallIcon)
		retSmall2, _, _ := procSendMessage.Call(uintptr(hwnd), wmSetIcon, iconSmall2, smallIcon)
		log.Printf("SetWindowIcon hwnd=%#x ICON_SMALL=%#x previous=%#x ICON_SMALL2=%#x previous=%#x", hwnd, smallIcon, retSmall, smallIcon, retSmall2)
	}
	if bigIcon != 0 {
		retBig, _, _ := procSendMessage.Call(uintptr(hwnd), wmSetIcon, iconBig, bigIcon)
		log.Printf("SetWindowIcon hwnd=%#x ICON_BIG=%#x previous=%#x", hwnd, bigIcon, retBig)
	}
}

// InstallWindowLifecycleHooks hides the Wails window when the native minimize
// button is clicked. Close-to-tray is handled by OnBeforeClose in app.go.
func (a *App) InstallWindowLifecycleHooks() {
	var hwnd windows.Handle
	for i := 0; i < 80; i++ {
		time.Sleep(250 * time.Millisecond)
		hwnd = windowForCurrentProcess()
		if hwnd != 0 && windowIsVisible(hwnd) {
			break
		}
	}
	if hwnd == 0 {
		log.Printf("InstallWindowLifecycleHooks skipped: Wails window handle not found")
		return
	}
	if windowProcOriginal != 0 {
		return
	}
	old, _, err := procSetWindowLong.Call(uintptr(hwnd), gwlpWndProc, windowProcCallback)
	if old == 0 {
		log.Printf("InstallWindowLifecycleHooks failed: %v", err)
		return
	}
	windowProcOriginal = old
	log.Printf("InstallWindowLifecycleHooks installed hwnd=%#x oldProc=%#x", hwnd, old)
}

func uiWindowProc(hwnd uintptr, msg uint32, wparam, lparam uintptr) uintptr {
	switch msg {
	case wmSysCommand:
		if wparam&0xfff0 == scMinimize {
			procShowWindow.Call(hwnd, swHide)
			return 0
		}
	case wmSize:
		if wparam == sizeMinimized {
			procShowWindow.Call(hwnd, swHide)
			return 0
		}
	}
	ret, _, _ := procCallWindowProc.Call(windowProcOriginal, hwnd, uintptr(msg), wparam, lparam)
	return ret
}

func windowIsVisible(hwnd windows.Handle) bool {
	visible, _, _ := procIsWindowVisible.Call(uintptr(hwnd))
	return visible != 0
}

func loadWindowIcon(size uintptr) uintptr {
	if icon := loadEmbeddedWindowIcon(size); icon != 0 {
		return icon
	}
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

func loadEmbeddedWindowIcon(size uintptr) uintptr {
	module, _, err := procGetModuleHandle.Call(0)
	if module == 0 {
		log.Printf("GetModuleHandleW failed while loading window icon: %v", err)
		return 0
	}
	icon, _, err := procLoadImage.Call(
		module,
		1,
		imageIcon,
		size,
		size,
		0,
	)
	if icon == 0 {
		log.Printf("LoadImageW embedded window icon failed for size %d: %v", size, err)
	}
	return icon
}

func windowForCurrentProcess() windows.Handle {
	pid := uint32(os.Getpid())
	var found windows.Handle
	var fallback windows.Handle
	cb := windows.NewCallback(func(hwnd uintptr, _ uintptr) uintptr {
		var windowPID uint32
		procGetWindowPID.Call(hwnd, uintptr(unsafe.Pointer(&windowPID)))
		if windowPID == pid {
			if fallback == 0 {
				fallback = windows.Handle(hwnd)
			}
			visible, _, _ := procIsWindowVisible.Call(hwnd)
			if visible != 0 {
				found = windows.Handle(hwnd)
				return 0
			}
		}
		return 1
	})
	procEnumWindows.Call(cb, 0)
	if found != 0 {
		return found
	}
	return fallback
}
