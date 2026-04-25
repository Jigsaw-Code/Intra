//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package main

import (
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"

	"localhost/Intra/internal/windows/servicecontrol"
	"localhost/Intra/internal/windows/state"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

const (
	serviceName           = "IntraTunnel"
	windowClass           = "IntraTrayWindow"
	serviceCommandTimeout = 90 * time.Second

	wmDestroy          = 0x0002
	wmNull             = 0x0000
	wmCommand          = 0x0111
	wmUserTray         = 0x0400 + 1
	wmLButtonUp        = 0x0202
	wmRButtonUp        = 0x0205
	wmLButtonDbl       = 0x0203
	wmContextMenu      = 0x007b
	ninSelect          = 0x0400
	ninKeySelect       = 0x0401
	nimAdd             = 0x00000000
	nimDelete          = 0x00000002
	nimSetVersion      = 0x00000004
	nifMessage         = 0x00000001
	nifIcon            = 0x00000002
	nifTip             = 0x00000004
	tpmRightAlign      = 0x0008
	tpmBottomAlign     = 0x0020
	tpmReturnCmd       = 0x0100
	notifyIconVersion4 = 4
	iconApplication    = 32512

	menuStatus = 1001
	menuStart  = 1002
	menuStop   = 1003
	menuLogs   = 1004
	menuExit   = 1005
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassEx     = user32.NewProc("RegisterClassExW")
	procRegisterWindowMsg   = user32.NewProc("RegisterWindowMessageW")
	procCreateWindowEx      = user32.NewProc("CreateWindowExW")
	procDefWindowProc       = user32.NewProc("DefWindowProcW")
	procDestroyWindow       = user32.NewProc("DestroyWindow")
	procPostMessage         = user32.NewProc("PostMessageW")
	procGetMessage          = user32.NewProc("GetMessageW")
	procTranslateMessage    = user32.NewProc("TranslateMessage")
	procDispatchMessage     = user32.NewProc("DispatchMessageW")
	procPostQuitMessage     = user32.NewProc("PostQuitMessage")
	procLoadIcon            = user32.NewProc("LoadIconW")
	procCreatePopupMenu     = user32.NewProc("CreatePopupMenu")
	procAppendMenu          = user32.NewProc("AppendMenuW")
	procTrackPopupMenu      = user32.NewProc("TrackPopupMenu")
	procDestroyMenu         = user32.NewProc("DestroyMenu")
	procSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	procGetCursorPos        = user32.NewProc("GetCursorPos")
	procMessageBox          = user32.NewProc("MessageBoxW")

	procShellNotifyIcon = shell32.NewProc("Shell_NotifyIconW")
	procShellExecute    = shell32.NewProc("ShellExecuteW")
	procGetModuleHandle = kernel32.NewProc("GetModuleHandleW")

	trayIcon         notifyIconData
	wmTaskbarCreated uint32
)

type point struct {
	X int32
	Y int32
}

type msg struct {
	HWnd    windows.Handle
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
}

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   windows.Handle
	Icon       windows.Handle
	Cursor     windows.Handle
	Background windows.Handle
	MenuName   *uint16
	ClassName  *uint16
	IconSm     windows.Handle
}

type notifyIconData struct {
	Size             uint32
	HWnd             windows.Handle
	ID               uint32
	Flags            uint32
	CallbackMessage  uint32
	Icon             windows.Handle
	Tip              [128]uint16
	State            uint32
	StateMask        uint32
	Info             [256]uint16
	TimeoutOrVersion uint32
	InfoTitle        [64]uint16
	InfoFlags        uint32
	GuidItem         windows.GUID
	BalloonIcon      windows.Handle
}

func main() {
	initTrayLog()
	log.Printf("tray starting")
	registerTaskbarMessage()
	hwnd, err := createHiddenWindow()
	if err != nil {
		log.Printf("create hidden window failed: %v", err)
		fatalBox(err)
		os.Exit(1)
	}
	log.Printf("hidden window created: hwnd=%#x", hwnd)
	if err := addTrayIcon(hwnd); err != nil {
		log.Printf("add tray icon failed: %v", err)
		fatalBox(err)
		os.Exit(1)
	}
	log.Printf("tray icon added")
	messageLoop()
	log.Printf("message loop exited")
}

func initTrayLog() {
	if err := os.MkdirAll(filepath.Dir(state.LogPath()), 0700); err != nil {
		return
	}
	f, err := os.OpenFile(filepath.Join(filepath.Dir(state.LogPath()), "windows-tray.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0666)
	if err != nil {
		return
	}
	log.SetOutput(f)
}

func registerTaskbarMessage() {
	msg, _, _ := procRegisterWindowMsg.Call(uintptr(unsafe.Pointer(windows.StringToUTF16Ptr("TaskbarCreated"))))
	wmTaskbarCreated = uint32(msg)
}

func createHiddenWindow() (windows.Handle, error) {
	instance, _, err := procGetModuleHandle.Call(0)
	if instance == 0 {
		return 0, err
	}
	className := windows.StringToUTF16Ptr(windowClass)
	wc := wndClassEx{
		Size:      uint32(unsafe.Sizeof(wndClassEx{})),
		WndProc:   windows.NewCallback(windowProc),
		Instance:  windows.Handle(instance),
		ClassName: className,
	}
	icon, _, _ := procLoadIcon.Call(0, uintptr(unsafe.Pointer(uintptr(iconApplication))))
	wc.Icon = windows.Handle(icon)
	wc.IconSm = windows.Handle(icon)
	atom, _, err := procRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))
	if atom == 0 && !errors.Is(err, windows.ERROR_CLASS_ALREADY_EXISTS) {
		return 0, err
	}
	hwnd, _, err := procCreateWindowEx.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr("Intra"))),
		0,
		0, 0, 0, 0,
		0, 0,
		instance,
		0,
	)
	if hwnd == 0 {
		return 0, err
	}
	return windows.Handle(hwnd), nil
}

func addTrayIcon(hwnd windows.Handle) error {
	icon, _, err := procLoadIcon.Call(0, uintptr(unsafe.Pointer(uintptr(iconApplication))))
	if icon == 0 {
		return fmt.Errorf("LoadIconW failed: %w", err)
	}
	trayIcon = notifyIconData{
		Size:            uint32(unsafe.Sizeof(notifyIconData{})),
		HWnd:            hwnd,
		ID:              1,
		Flags:           nifMessage | nifIcon | nifTip,
		CallbackMessage: wmUserTray,
		Icon:            windows.Handle(icon),
	}
	log.Printf("adding tray icon: hwnd=%#x flags=%#x callback=%#x icon=%#x", hwnd, trayIcon.Flags, trayIcon.CallbackMessage, trayIcon.Icon)
	copy(trayIcon.Tip[:], windows.StringToUTF16("Intra"))
	ok, _, err := procShellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&trayIcon)))
	if ok == 0 {
		return fmt.Errorf("Shell_NotifyIconW(NIM_ADD) failed: %w", err)
	}
	trayIcon.TimeoutOrVersion = notifyIconVersion4
	ok, _, err = procShellNotifyIcon.Call(nimSetVersion, uintptr(unsafe.Pointer(&trayIcon)))
	if ok == 0 {
		log.Printf("Shell_NotifyIconW(NIM_SETVERSION) failed: %v", err)
	}
	return nil
}

func removeTrayIcon() {
	if trayIcon.HWnd != 0 {
		procShellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&trayIcon)))
	}
}

func messageLoop() {
	var m msg
	for {
		ret, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(ret) <= 0 {
			return
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
}

func windowProc(hwnd uintptr, message uint32, wparam, lparam uintptr) uintptr {
	switch message {
	case wmTaskbarCreated:
		if wmTaskbarCreated != 0 {
			log.Printf("taskbar recreated; re-adding icon")
			if err := addTrayIcon(windows.Handle(hwnd)); err != nil {
				log.Printf("re-add tray icon failed: %v", err)
			}
			return 0
		}
	case wmUserTray:
		event := trayEvent(lparam)
		log.Printf("tray callback received: hwnd=%#x wparam=%#x lparam=%#x event=%#x", hwnd, wparam, lparam, event)
		switch event {
		case wmContextMenu, ninSelect, ninKeySelect, wmRButtonUp, wmLButtonDbl, wmLButtonUp:
			showMenu(windows.Handle(hwnd))
			return 0
		}
	case wmCommand:
		handleCommand(uint32(wparam & 0xffff))
		return 0
	case wmDestroy:
		removeTrayIcon()
		procPostQuitMessage.Call(0)
		return 0
	}
	ret, _, _ := procDefWindowProc.Call(hwnd, uintptr(message), wparam, lparam)
	return ret
}

func trayEvent(lparam uintptr) uint32 {
	return uint32(lparam & 0xffff)
}

func showMenu(hwnd windows.Handle) {
	log.Printf("show tray menu")
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer procDestroyMenu.Call(menu)
	appendMenu(menu, menuStatus, "Status")
	appendMenu(menu, menuStart, "Start Intra")
	appendMenu(menu, menuStop, "Stop Intra")
	appendMenu(menu, menuLogs, "Open diagnostics")
	appendMenu(menu, menuExit, "Exit tray")

	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroundWindow.Call(uintptr(hwnd))
	cmd, _, _ := procTrackPopupMenu.Call(menu, tpmRightAlign|tpmBottomAlign|tpmReturnCmd, uintptr(pt.X), uintptr(pt.Y), 0, uintptr(hwnd), 0)
	procPostMessage.Call(uintptr(hwnd), wmNull, 0, 0)
	log.Printf("track popup returned command: %d", cmd)
	if cmd != 0 {
		handleCommand(uint32(cmd))
	}
}

func appendMenu(menu uintptr, id uint32, label string) {
	procAppendMenu.Call(menu, 0, uintptr(id), uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(label))))
}

func handleCommand(id uint32) {
	log.Printf("handle menu command: %d", id)
	switch id {
	case menuStatus:
		st, err := queryService()
		if err != nil {
			message("Intra status", err.Error())
			return
		}
		message("Intra status", fmt.Sprintf("Service state: %s\nJournal: %s", serviceState(st), state.Path()))
	case menuStart:
		if err := startService(); err != nil {
			message("Start Intra", err.Error())
			return
		}
		message("Start Intra", "Start requested.")
	case menuStop:
		if err := stopService(); err != nil {
			message("Stop Intra", err.Error())
			return
		}
		message("Stop Intra", "Stop requested.")
	case menuLogs:
		openDiagnostics()
	case menuExit:
		procDestroyWindow.Call(uintptr(trayIcon.HWnd))
	}
}

func queryService() (svc.State, error) {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return svc.Stopped, err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return svc.Stopped, err
	}
	return st.State, nil
}

func startService() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_START|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return err
	}
	if st.State == svc.Running {
		return nil
	}
	if st.State == svc.StartPending {
		st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
		if err != nil {
			return err
		}
		if st.State != svc.Running {
			return fmt.Errorf("service reached %s while starting", serviceState(st.State))
		}
		return nil
	}
	if st.State == svc.StopPending {
		if _, err := waitForState(s, serviceCommandTimeout, svc.Stopped); err != nil {
			return err
		}
	}
	return s.Start()
}

func stopService() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_STOP|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return err
	}
	if st.State == svc.Stopped {
		return nil
	}
	if st.State == svc.StartPending {
		st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
		if err != nil {
			return err
		}
		if st.State == svc.Stopped {
			return nil
		}
	}
	if st.State == svc.StopPending {
		_, err = waitForState(s, serviceCommandTimeout, svc.Stopped)
		return err
	}
	_, err = s.Control(svc.Stop)
	if err != nil {
		return err
	}
	_, err = waitForState(s, serviceCommandTimeout, svc.Stopped)
	return err
}

func waitForState(s interface{ Query() (svc.Status, error) }, timeout time.Duration, targets ...svc.State) (svc.Status, error) {
	deadline := time.Now().Add(timeout)
	for {
		st, err := s.Query()
		if err != nil {
			return svc.Status{}, err
		}
		for _, target := range targets {
			if st.State == target {
				return st, nil
			}
		}
		if time.Now().After(deadline) {
			return st, fmt.Errorf("timed out waiting for service; current state: %s", serviceState(st.State))
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func openDiagnostics() {
	dir := filepath.Dir(state.Path())
	_ = os.MkdirAll(dir, 0700)
	verb := windows.StringToUTF16Ptr("open")
	target := windows.StringToUTF16Ptr(dir)
	procShellExecute.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(target)), 0, 0, 1)
}

func message(title, text string) {
	procMessageBox.Call(0,
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(text))),
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(title))),
		0,
	)
}

func fatalBox(err error) {
	if err == nil || errors.Is(err, syscall.Errno(0)) {
		return
	}
	message("Intra tray", err.Error())
}

func serviceState(state svc.State) string {
	switch state {
	case svc.Stopped:
		return "stopped"
	case svc.StartPending:
		return "start-pending"
	case svc.StopPending:
		return "stop-pending"
	case svc.Running:
		return "running"
	case svc.ContinuePending:
		return "continue-pending"
	case svc.PausePending:
		return "pause-pending"
	case svc.Paused:
		return "paused"
	default:
		return fmt.Sprintf("unknown(%d)", state)
	}
}
