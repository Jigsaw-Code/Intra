//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package uiapi

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"localhost/Intra/internal/windows/servicecontrol"
	"localhost/Intra/internal/windows/state"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

const (
	serviceName           = "IntraTunnel"
	serviceCommandTimeout = 90 * time.Second
)

type App struct {
	ctx context.Context
}

type Status struct {
	ProtectionState string `json:"protectionState"`
	ServiceState    string `json:"serviceState"`
	JournalPresent  bool   `json:"journalPresent"`
	WintunPresent   bool   `json:"wintunPresent"`
	LogPath         string `json:"logPath"`
	Message         string `json:"message"`
}

func NewApp() *App {
	return &App{}
}

func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
}

func (a *App) GetStatus() Status {
	st, err := queryService()
	if err != nil {
		return Status{
			ProtectionState: "Needs attention",
			ServiceState:    "unavailable",
			JournalPresent:  fileExists(state.Path()),
			WintunPresent:   wintunPresent(),
			LogPath:         state.LogPath(),
			Message:         err.Error(),
		}
	}
	return makeStatus(st, "")
}

func (a *App) StartIntra() Status {
	st, err := startService()
	if err != nil {
		status := a.GetStatus()
		status.ProtectionState = "Needs attention"
		status.Message = err.Error()
		return status
	}
	return makeStatus(st, "")
}

func (a *App) StopIntra() Status {
	st, err := stopService()
	if err != nil {
		status := a.GetStatus()
		status.ProtectionState = "Needs attention"
		status.Message = err.Error()
		return status
	}
	return makeStatus(st, "")
}

func (a *App) OpenDiagnostics() error {
	dir := filepath.Dir(state.Path())
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return exec.Command("explorer.exe", dir).Start()
}

func makeStatus(st svc.Status, message string) Status {
	return Status{
		ProtectionState: protectionState(st.State),
		ServiceState:    serviceState(st.State),
		JournalPresent:  fileExists(state.Path()),
		WintunPresent:   wintunPresent(),
		LogPath:         state.LogPath(),
		Message:         message,
	}
}

func queryService() (svc.Status, error) {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return svc.Status{}, err
	}
	defer cleanup()
	return s.Query()
}

func startService() (svc.Status, error) {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_START|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return svc.Status{}, err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return svc.Status{}, err
	}
	switch st.State {
	case svc.Running:
		return st, nil
	case svc.StartPending:
		return waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
	case svc.StopPending:
		if _, err := waitForState(s, serviceCommandTimeout, svc.Stopped); err != nil {
			return svc.Status{}, err
		}
	}
	if err := s.Start(); err != nil {
		return svc.Status{}, err
	}
	return waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
}

func stopService() (svc.Status, error) {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_STOP|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return svc.Status{}, err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return svc.Status{}, err
	}
	switch st.State {
	case svc.Stopped:
		return st, nil
	case svc.StartPending:
		st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
		if err != nil || st.State == svc.Stopped {
			return st, err
		}
	case svc.StopPending:
		return waitForState(s, serviceCommandTimeout, svc.Stopped)
	}
	if _, err := s.Control(svc.Stop); err != nil {
		return svc.Status{}, err
	}
	return waitForState(s, serviceCommandTimeout, svc.Stopped)
}

type serviceQuerier interface {
	Query() (svc.Status, error)
}

func waitForState(s serviceQuerier, timeout time.Duration, targets ...svc.State) (svc.Status, error) {
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
			return st, context.DeadlineExceeded
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func protectionState(st svc.State) string {
	switch st {
	case svc.Running:
		return "Protected"
	case svc.StartPending:
		return "Starting"
	case svc.StopPending:
		return "Stopping"
	case svc.Stopped:
		return "Not protected"
	default:
		return "Needs attention"
	}
}

func serviceState(st svc.State) string {
	switch st {
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
		return "unknown"
	}
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func wintunPresent() bool {
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	return fileExists(filepath.Join(filepath.Dir(exe), "wintun.dll"))
}
