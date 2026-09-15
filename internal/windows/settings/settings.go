//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package settings

import (
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

const fileName = "windows-settings.json"

type Server struct {
	Name        string `json:"name"`
	URL         string `json:"url"`
	IPs         string `json:"ips"`
	Description string `json:"description"`
	Website     string `json:"website"`
}

type Config struct {
	DoHURL            string `json:"dohUrl"`
	DoHIPs            string `json:"dohIps"`
	ShowRecentQueries bool   `json:"showRecentQueries"`
}

var BuiltInServers = []Server{
	{"Google Public DNS", "https://dns.google/dns-query", "8.8.8.8,8.8.4.4,2001:4860:4860::8888,2001:4860:4860::8844", "Large global resolver operated by Google.", "https://developers.google.com/speed/public-dns/"},
	{"Cloudflare 1.1.1.1 DNS", "https://cloudflare-dns.com/dns-query", "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001,cloudflare.com", "Large global resolver operated by Cloudflare.", "https://cloudflare-dns.com/"},
	{"Quad9 Secure DNS", "https://dns.quad9.net/dns-query", "9.9.9.9,149.112.112.112,2620:fe::fe,2620:fe::fe:9", "Large global resolver from IBM and Packet Clearinghouse. Blocks malicious domains.", "https://www.quad9.net/"},
	{"CleanBrowsing Security Filter", "https://doh.cleanbrowsing.org/doh/security-filter/", "185.228.168.9,185.228.169.9,2a0d:2a00:1::2,2a0d:2a00:2::2", "Global DNS filtering provider. Blocks malicious domains.", "https://cleanbrowsing.org/"},
	{"Foundation for Applied Privacy", "https://doh.applied-privacy.net/query", "doh.appliedprivacy.net", "Server in Austria operated by a non-profit privacy group.", "https://applied-privacy.net/services/dns/"},
	{"DNS.SB", "https://doh.dns.sb/dns-query", "cloudflare.net", "Operated by xTom and other international hosting providers. Uses Cloudflare's CDN.", "https://dns.sb/"},
	{"Internet Initiative Japan", "https://public.dns.iij.jp/dns-query", "", "Server in Japan operated by an ISP.", "https://public.dns.iij.jp/"},
	{"TWNIC Quad101", "https://dns.twnic.tw/dns-query", "", "Server in Taiwan operated by the .tw domain registry.", "https://101.101.101.101/"},
	{"FAELIX", "https://rdns.faelix.net/", "", "Served from the UK and Switzerland by a British hosting and networking company.", "https://faelix.net/ref/dns/"},
	{"Andrews & Arnold", "https://dns.aa.net.uk/dns-query", "217.169.20.22,217.169.20.23,2001:8b0::2022,201:8b0::2023", "Server in London, UK, operated by a British ISP.", "https://www.aa.net.uk/dns/"},
	{"42l Association", "https://doh.42l.fr/dns-query", "45.155.171.163,2a09:6382:4000:3:45:155:171:163", "Server in Nice, France, operated by a French non-profit promoting free culture and ethics.", "https://42l.fr/DoH-service"},
	{"Digitale Gesellschaft", "https://dns.digitale-gesellschaft.ch/dns-query", "185.95.218.42,185.95.218.43,2a05:fc84::42,2a05:fc84::43", "Operated by a Swiss-German association for civil rights online, using servers in Switzerland, Germany, or Austria.", "https://www.digitale-gesellschaft.ch/dns/"},
	{"Snopyta", "https://fi.doh.dns.snopyta.org/dns-query", "95.216.24.230,2a01:4f9:2a:1919::9301", "Server in Helsinki, Finland, operated by a German non-profit service provider.", "https://snopyta.org/"},
	{"OpenDNS", "https://doh.opendns.com/dns-query", "146.112.41.2,2620:119:fc::2", "Large global resolver owned by Cisco.", "https://www.opendns.com/about/"},
	{"DNSlify", "https://doh.dnslify.com/dns-query", "185.235.81.1,2a0d:4d00:81::1", "Operated by an IT services company in London and Bulgaria.", "https://www.dnslify.com/services/doh/"},
	{"Canadian Shield", "https://private.canadianshield.cira.ca/dns-query", "149.112.121.10,149.112.122.10,2620:10a:80bb::10,2620:10a:80bc::10", "Operated by the Canadian Internet Registration Authority.", "https://www.cira.ca/cybersecurity-services/canadian-shield"},
}

func Default() Config {
	return Config{
		DoHURL:            BuiltInServers[0].URL,
		DoHIPs:            BuiltInServers[0].IPs,
		ShowRecentQueries: false,
	}
}

func Path() string {
	root := os.Getenv("ProgramData")
	if root == "" {
		root = os.TempDir()
	}
	return filepath.Join(root, "Intra", fileName)
}

func Load() (Config, error) {
	cfg := Default()
	data, err := os.ReadFile(Path())
	if errors.Is(err, os.ErrNotExist) {
		return cfg, nil
	}
	if err != nil {
		return cfg, err
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		return cfg, err
	}
	if cfg.DoHURL == "" {
		cfg.DoHURL = Default().DoHURL
		cfg.DoHIPs = Default().DoHIPs
	}
	return cfg, nil
}

func Save(cfg Config) error {
	if err := ValidateDoHURL(cfg.DoHURL); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(Path()), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(), data, 0666)
}

func ServerName(cfg Config) string {
	for _, server := range BuiltInServers {
		if server.URL == cfg.DoHURL {
			return hostOrURL(server.URL)
		}
	}
	return hostOrURL(cfg.DoHURL)
}

func ValidateDoHURL(raw string) error {
	u, err := url.Parse(stripTemplate(strings.TrimSpace(raw)))
	if err != nil {
		return err
	}
	if u.Scheme != "https" || u.Host == "" || u.Path == "" || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("custom server must be a valid https:// URL")
	}
	return nil
}

func stripTemplate(raw string) string {
	if i := strings.Index(raw, "{"); i >= 0 {
		return raw[:i]
	}
	return raw
}

func hostOrURL(raw string) string {
	u, err := url.Parse(stripTemplate(raw))
	if err != nil || u.Host == "" {
		return raw
	}
	return u.Host
}
