//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	winservice "localhost/Intra/internal/windows/service"
)

func main() {
	dohURL := flag.String("doh", "", "DoH server URL")
	dohIPs := flag.String("doh-ips", "", "comma-separated DoH bootstrap IPs")
	flag.Parse()

	cfg := winservice.Config{DoHURL: *dohURL, DoHIPs: *dohIPs}
	if err := runDebug(cfg); err != nil {
		log.Fatal(err)
	}
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
