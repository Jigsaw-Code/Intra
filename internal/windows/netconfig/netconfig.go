//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package netconfig

import (
	"bytes"
	"errors"
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
)

type Config struct {
	InterfaceName string
	Address       string
	Mask          string
	DNS           string
}

type Snapshot struct {
	PhysicalInterfaceIndex int
	PhysicalResolvers      []string
	TunnelDNSServers       []string
	TunnelRoutes           map[string]bool
}

func CaptureSnapshot(tunName string) Snapshot {
	idx := defaultInterfaceIndex(tunName)
	return Snapshot{
		PhysicalInterfaceIndex: idx,
		PhysicalResolvers:      dnsServers(idx),
		TunnelDNSServers:       dnsServersByName(tunName),
		TunnelRoutes: map[string]bool{
			"0.0.0.0/0":   routeExists(tunName, "0.0.0.0/0"),
			"0.0.0.0/1":   routeExists(tunName, "0.0.0.0/1"),
			"128.0.0.0/1": routeExists(tunName, "128.0.0.0/1"),
		},
	}
}

func ApplyFullTunnel(cfg Config) error {
	if cfg.InterfaceName == "" || cfg.Address == "" || cfg.Mask == "" || cfg.DNS == "" {
		return errors.New("interface name, address, mask, and DNS are required")
	}
	if err := run("netsh", "interface", "ipv4", "set", "address", "name="+cfg.InterfaceName, "static", cfg.Address, cfg.Mask, "none"); err != nil {
		return err
	}
	if err := run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+cfg.InterfaceName, "static", cfg.DNS, "primary", "validate=no"); err != nil {
		return err
	}
	if err := run("netsh", "interface", "ipv4", "add", "route", "prefix=0.0.0.0/0", "interface="+cfg.InterfaceName, "nexthop=0.0.0.0", "metric=1", "store=active"); err != nil {
		// Some Windows builds reject a single on-link default; split defaults are equivalent for capture.
		if err1 := run("netsh", "interface", "ipv4", "add", "route", "prefix=0.0.0.0/1", "interface="+cfg.InterfaceName, "nexthop=0.0.0.0", "metric=1", "store=active"); err1 != nil {
			return fmt.Errorf("add default route failed: %w; split route failed: %v", err, err1)
		}
		return run("netsh", "interface", "ipv4", "add", "route", "prefix=128.0.0.0/1", "interface="+cfg.InterfaceName, "nexthop=0.0.0.0", "metric=1", "store=active")
	}
	return nil
}

// Restore reverts only the Milestone 1 state that ApplyFullTunnel modifies:
// the Intra adapter's IPv4 DNS servers and the Intra-owned default/split routes.
// It does not restore route metrics for routes that existed before startup.
func Restore(cfg Config, snapshot Snapshot) []error {
	var errs []error
	for _, prefix := range []string{"0.0.0.0/0", "0.0.0.0/1", "128.0.0.0/1"} {
		if snapshot.TunnelRoutes[prefix] {
			continue
		}
		if err := run("netsh", "interface", "ipv4", "delete", "route", "prefix="+prefix, "interface="+cfg.InterfaceName); err != nil && !isMissingRouteError(err) {
			errs = append(errs, err)
		}
	}
	if err := restoreDNS(cfg.InterfaceName, snapshot.TunnelDNSServers); err != nil {
		errs = append(errs, err)
	}
	return errs
}

func run(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%s %s: %w: %s", name, strings.Join(args, " "), err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func defaultInterfaceIndex(tunName string) int {
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command",
		"Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Sort-Object RouteMetric,InterfaceMetric | Select-Object -First 1 -ExpandProperty InterfaceIndex").Output()
	if err == nil {
		if idx, parseErr := strconv.Atoi(strings.TrimSpace(string(out))); parseErr == nil && idx > 0 {
			return idx
		}
	}
	for _, iface := range upInterfaces(tunName) {
		return iface.Index
	}
	return 0
}

func dnsServers(index int) []string {
	if index == 0 {
		return nil
	}
	cmd := fmt.Sprintf("(Get-DnsClientServerAddress -AddressFamily IPv4 -InterfaceIndex %d).ServerAddresses", index)
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", cmd).Output()
	if err != nil {
		return nil
	}
	return parseIPFields(string(out))
}

func dnsServersByName(name string) []string {
	cmd := fmt.Sprintf("(Get-DnsClientServerAddress -AddressFamily IPv4 -InterfaceAlias %s).ServerAddresses", powerShellQuote(name))
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", cmd).Output()
	if err != nil {
		return nil
	}
	return parseIPFields(string(out))
}

func restoreDNS(name string, servers []string) error {
	if len(servers) == 0 {
		return run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+name, "dhcp")
	}
	if err := run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+name, "static", servers[0], "primary", "validate=no"); err != nil {
		return err
	}
	for _, server := range servers[1:] {
		if err := run("netsh", "interface", "ipv4", "add", "dnsservers", "name="+name, "address="+server, "validate=no"); err != nil {
			return err
		}
	}
	return nil
}

func routeExists(name, prefix string) bool {
	cmd := fmt.Sprintf("Get-NetRoute -AddressFamily IPv4 -InterfaceAlias %s -DestinationPrefix %s -ErrorAction SilentlyContinue", powerShellQuote(name), powerShellQuote(prefix))
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", cmd).Output()
	return err == nil && strings.TrimSpace(string(out)) != ""
}

func InterfaceExists(name string) bool {
	_, err := net.InterfaceByName(name)
	return err == nil
}

func parseIPFields(out string) []string {
	var servers []string
	for _, line := range strings.Fields(out) {
		if ip := net.ParseIP(strings.TrimSpace(line)); ip != nil && ip.To4() != nil {
			servers = append(servers, ip.String())
		}
	}
	return servers
}

func powerShellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}

func isMissingRouteError(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "not found") ||
		strings.Contains(msg, "element not found") ||
		strings.Contains(msg, "object was not found")
}

func upInterfaces(tunName string) []net.Interface {
	ifaces, _ := net.Interfaces()
	var ret []net.Interface
	for _, iface := range ifaces {
		if iface.Name == tunName || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok && ipnet.IP.To4() != nil && ipnet.IP.IsGlobalUnicast() {
				ret = append(ret, iface)
				break
			}
		}
	}
	return ret
}
