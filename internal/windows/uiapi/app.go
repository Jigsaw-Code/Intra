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
	"sync"
	"time"

	"localhost/Intra/internal/windows/queryhistory"
	"localhost/Intra/internal/windows/servicecontrol"
	winsettings "localhost/Intra/internal/windows/settings"
	"localhost/Intra/internal/windows/state"

	"github.com/wailsapp/wails/v2/pkg/runtime"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

const (
	serviceName           = "IntraTunnel"
	serviceCommandTimeout = 90 * time.Second
)

type App struct {
	mu             sync.Mutex
	ctx            context.Context
	allowQuit      bool
	minimiseCancel context.CancelFunc
}

type Status struct {
	ProtectionState    string                     `json:"protectionState"`
	ServiceState       string                     `json:"serviceState"`
	JournalPresent     bool                       `json:"journalPresent"`
	WintunPresent      bool                       `json:"wintunPresent"`
	LogPath            string                     `json:"logPath"`
	ServerName         string                     `json:"serverName"`
	ServerURL          string                     `json:"serverUrl"`
	LifetimeQueries    int64                      `json:"lifetimeQueries"`
	RecentQueries      int                        `json:"recentQueries"`
	RecentTransactions []queryhistory.Transaction `json:"recentTransactions"`
	Message            string                     `json:"message"`
}

type UISettings struct {
	Servers           []winsettings.Server `json:"servers"`
	SelectedURL       string               `json:"selectedUrl"`
	SelectedIPs       string               `json:"selectedIps"`
	ServerName        string               `json:"serverName"`
	ShowRecentQueries bool                 `json:"showRecentQueries"`
	SettingsPath      string               `json:"settingsPath"`
}

type SaveSettingsRequest struct {
	DoHURL            string `json:"dohUrl"`
	DoHIPs            string `json:"dohIps"`
	ShowRecentQueries bool   `json:"showRecentQueries"`
}

func NewApp() *App {
	return &App{}
}

func (a *App) Startup(ctx context.Context) {
	a.mu.Lock()
	if a.minimiseCancel != nil {
		a.minimiseCancel()
	}
	minimiseCtx, cancel := context.WithCancel(context.Background())
	defer a.mu.Unlock()
	a.ctx = ctx
	a.allowQuit = false
	a.minimiseCancel = cancel
	go a.watchMinimiseToTray(minimiseCtx, ctx)
	go a.SetWindowIcon()
}

func (a *App) BeforeClose(ctx context.Context) bool {
	a.mu.Lock()
	allowQuit := a.allowQuit
	a.mu.Unlock()
	if allowQuit {
		return false
	}
	runtime.WindowHide(ctx)
	return true
}

func (a *App) ShowWindow() {
	a.mu.Lock()
	ctx := a.ctx
	a.mu.Unlock()
	if ctx == nil {
		return
	}
	runtime.WindowShow(ctx)
	runtime.WindowUnminimise(ctx)
	runtime.WindowCenter(ctx)
}

func (a *App) HideToTray() {
	a.mu.Lock()
	ctx := a.ctx
	a.mu.Unlock()
	if ctx == nil {
		return
	}
	runtime.WindowHide(ctx)
}

func (a *App) ExitApp() {
	a.mu.Lock()
	a.allowQuit = true
	if a.minimiseCancel != nil {
		a.minimiseCancel()
		a.minimiseCancel = nil
	}
	ctx := a.ctx
	a.mu.Unlock()
	if ctx == nil {
		return
	}
	runtime.Quit(ctx)
}

func (a *App) watchMinimiseToTray(done context.Context, ctx context.Context) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-done.Done():
			return
		case <-ticker.C:
			a.mu.Lock()
			allowQuit := a.allowQuit
			a.mu.Unlock()
			if allowQuit {
				return
			}
			if runtime.WindowIsMinimised(ctx) {
				runtime.WindowHide(ctx)
			}
		}
	}
}

func (a *App) GetStatus() Status {
	st, err := queryService()
	if err != nil {
		queryStats := queryhistory.LoadStats()
		return Status{
			ProtectionState:    "Needs attention",
			ServiceState:       "unavailable",
			JournalPresent:     fileExists(state.Path()),
			WintunPresent:      wintunPresent(),
			LogPath:            state.LogPath(),
			ServerName:         currentServerName(),
			ServerURL:          currentServerURL(),
			LifetimeQueries:    queryStats.LifetimeQueries,
			RecentQueries:      queryStats.RecentQueries,
			RecentTransactions: queryStats.RecentTransactions,
			Message:            err.Error(),
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

func (a *App) GetSettings() (UISettings, error) {
	cfg, err := winsettings.Load()
	if err != nil {
		return UISettings{}, err
	}
	return UISettings{
		Servers:           winsettings.BuiltInServers,
		SelectedURL:       cfg.DoHURL,
		SelectedIPs:       cfg.DoHIPs,
		ServerName:        winsettings.ServerName(cfg),
		ShowRecentQueries: cfg.ShowRecentQueries,
		SettingsPath:      winsettings.Path(),
	}, nil
}

func (a *App) SaveSettings(req SaveSettingsRequest) (Status, error) {
	oldCfg, _ := winsettings.Load()
	cfg := winsettings.Config{
		DoHURL:            req.DoHURL,
		DoHIPs:            req.DoHIPs,
		ShowRecentQueries: req.ShowRecentQueries,
	}
	if err := winsettings.Save(cfg); err != nil {
		return a.GetStatus(), err
	}
	dohChanged := oldCfg.DoHURL != cfg.DoHURL || oldCfg.DoHIPs != cfg.DoHIPs
	if !dohChanged {
		return a.GetStatus(), nil
	}
	st, err := queryService()
	if err != nil {
		return a.GetStatus(), nil
	}
	if st.State == svc.Running {
		if _, err := stopService(); err != nil {
			return a.GetStatus(), err
		}
		if _, err := startService(); err != nil {
			return a.GetStatus(), err
		}
	}
	return a.GetStatus(), nil
}

func makeStatus(st svc.Status, message string) Status {
	cfg, _ := winsettings.Load()
	queryStats := queryhistory.LoadStats()
	return Status{
		ProtectionState:    protectionState(st.State),
		ServiceState:       serviceState(st.State),
		JournalPresent:     fileExists(state.Path()),
		WintunPresent:      wintunPresent(),
		LogPath:            state.LogPath(),
		ServerName:         winsettings.ServerName(cfg),
		ServerURL:          cfg.DoHURL,
		LifetimeQueries:    queryStats.LifetimeQueries,
		RecentQueries:      queryStats.RecentQueries,
		RecentTransactions: queryStats.RecentTransactions,
		Message:            message,
	}
}

func currentServerName() string {
	cfg, _ := winsettings.Load()
	return winsettings.ServerName(cfg)
}

func currentServerURL() string {
	cfg, _ := winsettings.Load()
	return cfg.DoHURL
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
