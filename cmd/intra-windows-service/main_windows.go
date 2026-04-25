//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"localhost/Intra/internal/windows/netconfig"
	winservice "localhost/Intra/internal/windows/service"
	"localhost/Intra/internal/windows/servicecontrol"
	"localhost/Intra/internal/windows/state"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const serviceName = "IntraTunnel"
const serviceCommandTimeout = 90 * time.Second

func main() {
	if isServiceProcess() {
		if err := runService(os.Args[1:]); err != nil {
			log.Fatal(err)
		}
		return
	}
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	var err error
	switch os.Args[1] {
	case "install":
		err = install(os.Args[2:])
	case "uninstall":
		err = uninstall()
	case "start":
		err = start()
	case "stop":
		err = stop()
	case "status":
		err = status()
	case "run-debug":
		err = runDebug(parseConfig("run-debug", os.Args[2:]))
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		log.Fatal(err)
	}
}

func isServiceProcess() bool {
	isService, err := svc.IsWindowsService()
	return err == nil && isService
}

func parseConfig(command string, args []string) winservice.Config {
	fs := flag.NewFlagSet(command, flag.ExitOnError)
	dohURL := fs.String("doh", "", "DoH server URL")
	dohIPs := fs.String("doh-ips", "", "comma-separated DoH bootstrap IPs")
	_ = fs.Parse(args)
	return winservice.Config{DoHURL: *dohURL, DoHIPs: *dohIPs}
}

func runDebug(cfg winservice.Config) error {
	runner := winservice.NewRunner(cfg)
	if err := runner.Start(); err != nil {
		return err
	}
	fmt.Println("Intra Windows tunnel is running. Press Ctrl+C to stop.")
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
	<-ch
	return runner.Stop()
}

func runService(args []string) error {
	initServiceLog()
	if len(args) > 0 && args[0] == "service" {
		args = args[1:]
	}
	cfg := parseConfig("service", args)
	return svc.Run(serviceName, &handler{runner: winservice.NewRunner(cfg)})
}

type handler struct {
	runner *winservice.Runner
}

func (h *handler) Execute(_ []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	const accepted = svc.AcceptStop | svc.AcceptShutdown
	changes <- svc.Status{State: svc.StartPending, WaitHint: 30000}
	log.Printf("service start pending")
	if err := h.runner.Start(); err != nil {
		log.Printf("failed to start tunnel: %v", err)
		changes <- svc.Status{State: svc.Stopped}
		return false, 1
	}
	changes <- svc.Status{State: svc.Running, Accepts: accepted}
	log.Printf("service running")

	for req := range requests {
		switch req.Cmd {
		case svc.Interrogate:
			changes <- req.CurrentStatus
		case svc.Stop, svc.Shutdown:
			log.Printf("service stop pending")
			changes <- svc.Status{State: svc.StopPending, WaitHint: 30000}
			if err := h.runner.Stop(); err != nil {
				log.Printf("failed to stop tunnel: %v", err)
			}
			changes <- svc.Status{State: svc.Stopped}
			log.Printf("service stopped")
			return false, 0
		default:
			changes <- svc.Status{State: svc.Running, Accepts: accepted}
		}
	}
	return false, 0
}

func install(args []string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	serviceArgs := append([]string{"service"}, args...)
	m, err := servicecontrol.CreateManager()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	if s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_QUERY_STATUS); err == nil {
		cleanup()
		_ = s
		return fmt.Errorf("service %s already exists", serviceName)
	}
	s, err := m.CreateService(serviceName, exe, mgr.Config{
		DisplayName: "Intra Full-Tunnel Service",
		StartType:   mgr.StartManual,
		Description: "Runs the Intra Windows full-tunnel backend.",
	}, serviceArgs...)
	if err != nil {
		return err
	}
	defer s.Close()
	if err := allowInteractiveControl(); err != nil {
		return fmt.Errorf("configure service permissions: %w", err)
	}
	fmt.Printf("Installed %s as LocalSystem. Journal: %s\n", serviceName, state.Path())
	return nil
}

func uninstall() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_QUERY_STATUS|windows.DELETE)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err == nil && st.State != svc.Stopped {
		return fmt.Errorf("service %s must be stopped before uninstall", serviceName)
	}
	if err := s.Delete(); err != nil {
		return err
	}
	fmt.Printf("Uninstalled %s\n", serviceName)
	return nil
}

func start() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_START|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return err
	}
	switch st.State {
	case svc.Running:
		return printStatus(st)
	case svc.StartPending:
		st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
		if err != nil {
			return err
		}
		if st.State != svc.Running {
			return fmt.Errorf("%s reached %s while starting", serviceName, serviceState(st.State))
		}
		return printStatus(st)
	case svc.StopPending:
		st, err = waitForState(s, serviceCommandTimeout, svc.Stopped)
		if err != nil {
			return err
		}
	}
	if err := s.Start(); err != nil {
		return err
	}
	fmt.Printf("Starting %s\n", serviceName)
	st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
	if err != nil {
		return err
	}
	if st.State != svc.Running {
		return fmt.Errorf("%s reached %s while starting", serviceName, serviceState(st.State))
	}
	return printStatus(st)
}

func stop() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_STOP|windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return err
	}
	switch st.State {
	case svc.Stopped:
		return reconcileStoppedJournal(st)
	case svc.StartPending:
		st, err = waitForState(s, serviceCommandTimeout, svc.Running, svc.Stopped)
		if err != nil {
			return err
		}
		if st.State == svc.Stopped {
			return reconcileStoppedJournal(st)
		}
	case svc.StopPending:
		st, err = waitForState(s, serviceCommandTimeout, svc.Stopped)
		if err != nil {
			return err
		}
		return reconcileStoppedJournal(st)
	}
	st, err = s.Control(svc.Stop)
	if err != nil {
		return err
	}
	st, err = waitForState(s, serviceCommandTimeout, svc.Stopped)
	if err != nil {
		return err
	}
	return reconcileStoppedJournal(st)
}

func status() error {
	s, cleanup, err := servicecontrol.Open(serviceName, windows.SERVICE_QUERY_STATUS)
	if err != nil {
		return err
	}
	defer cleanup()
	st, err := s.Query()
	if err != nil {
		return err
	}
	return printStatus(st)
}

func waitForState(s *mgr.Service, timeout time.Duration, targets ...svc.State) (svc.Status, error) {
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
			return st, fmt.Errorf("timed out waiting for %s; current state: %s", serviceName, serviceState(st.State))
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func reconcileStoppedJournal(st svc.Status) error {
	if st.State == svc.Stopped && journalPresent() {
		if !netconfig.InterfaceExists(winservice.AdapterName) {
			if err := state.Clear(); err != nil {
				_ = printStatus(st)
				return err
			}
			return printStatus(st)
		}
		if err := recoverStoppedJournal(); err != nil {
			_ = printStatus(st)
			return err
		}
	}
	return printStatus(st)
}

func recoverStoppedJournal() error {
	journal, err := state.LoadActive()
	if err != nil {
		return fmt.Errorf("load stopped journal: %w", err)
	}
	if journal == nil {
		return nil
	}
	log.Printf("service stopped with active journal; restoring journaled state")
	if errs := winservice.RestoreJournal(journal); len(errs) > 0 {
		return fmt.Errorf("restore stopped journal failed: %v", errs)
	}
	return state.Clear()
}

func printStatus(st svc.Status) error {
	fmt.Printf("%s state: %s\n", serviceName, serviceState(st.State))
	fmt.Printf("Journal: %s\n", state.Path())
	fmt.Printf("Journal present: %t\n", journalPresent())
	fmt.Printf("Log: %s\n", state.LogPath())
	return nil
}

func journalPresent() bool {
	_, err := os.Stat(state.Path())
	return err == nil
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

func initServiceLog() {
	if err := os.MkdirAll(filepath.Dir(state.LogPath()), 0700); err != nil {
		return
	}
	f, err := os.OpenFile(state.LogPath(), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0666)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(os.Stderr, f))
	log.Printf("service process logging initialized")
}

func allowInteractiveControl() error {
	// Allow LocalSystem and Administrators full control, and authenticated
	// interactive users enough access for tray status/start/stop.
	const sddl = "D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;LCRPLORCWP;;;AU)"
	cmd := exec.Command("sc.exe", "sdset", serviceName, sddl)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func usage() {
	fmt.Fprintf(os.Stderr, "Usage: %s <command> [options]\n\n", os.Args[0])
	fmt.Fprintln(os.Stderr, "Commands:")
	fmt.Fprintln(os.Stderr, "  install    Install the Windows service")
	fmt.Fprintln(os.Stderr, "  uninstall  Uninstall the Windows service")
	fmt.Fprintln(os.Stderr, "  start      Start the Windows service")
	fmt.Fprintln(os.Stderr, "  stop       Stop the Windows service")
	fmt.Fprintln(os.Stderr, "  status     Show service status")
	fmt.Fprintln(os.Stderr, "  run-debug  Run the tunnel in the foreground")
	fmt.Fprintln(os.Stderr)
	fmt.Fprintln(os.Stderr, "Options for install, service, and run-debug:")
	fmt.Fprintln(os.Stderr, "  -doh URL")
	fmt.Fprintln(os.Stderr, "  -doh-ips comma,separated,ips")
	fmt.Fprintln(os.Stderr)
	fmt.Fprintln(os.Stderr, "Wintun runtime dependency:")
	fmt.Fprintln(os.Stderr, "  Place amd64 wintun.dll beside this exe before running.")
	fmt.Fprintln(os.Stderr, "  Example commands: "+strings.Join([]string{"install", "start", "status", "stop", "uninstall"}, ", "))
}
