//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package queryhistory

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"localhost/Intra/Android/app/src/go/backend"
	winsettings "localhost/Intra/internal/windows/settings"
)

const (
	fileName       = "windows-query-history.json"
	historySize    = 100
	activityMemory = time.Minute
)

type Transaction struct {
	Name         string    `json:"name"`
	Type         uint16    `json:"type"`
	QueryTime    time.Time `json:"queryTime"`
	ResponseTime time.Time `json:"responseTime"`
	LatencyMS    int64     `json:"latencyMs"`
	Server       string    `json:"server"`
	Status       int       `json:"status"`
}

type Stats struct {
	LifetimeQueries    int64         `json:"lifetimeQueries"`
	RecentQueries      int           `json:"recentQueries"`
	RecentTransactions []Transaction `json:"recentTransactions"`
}

type Store struct {
	LifetimeQueries    int64         `json:"lifetimeQueries"`
	RecentActivity     []time.Time   `json:"recentActivity,omitempty"`
	RecentTransactions []Transaction `json:"recentTransactions,omitempty"`
}

type Tracker struct {
	mu           sync.Mutex
	path         string
	now          func() time.Time
	loaded       bool
	lifetime     int64
	activity     []time.Time
	transactions []Transaction
}

var defaultTracker = New(Path())

func DefaultTracker() *Tracker {
	return defaultTracker
}

func Path() string {
	root := os.Getenv("ProgramData")
	if root == "" {
		root = os.TempDir()
	}
	return filepath.Join(root, "Intra", fileName)
}

func New(path string) *Tracker {
	return &Tracker{
		path: path,
		now:  time.Now,
	}
}

func LoadStats() Stats {
	tracker := New(Path())
	_ = tracker.Load()
	return tracker.Stats()
}

func (t *Tracker) SetClock(now func() time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.now = now
}

func (t *Tracker) Load() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.loadLocked()
}

func (t *Tracker) RecordSummary(summary *backend.DoHQuerySumary) {
	if summary == nil {
		return
	}
	t.Record(Transaction{
		Name:      queryName(summary.GetQuery()),
		Type:      queryType(summary.GetQuery()),
		LatencyMS: int64(1000 * summary.GetLatency()),
		Server:    summary.GetServer(),
		Status:    summary.GetStatus(),
	})
}

func (t *Tracker) Record(tx Transaction) {
	t.mu.Lock()
	defer t.mu.Unlock()
	_ = t.loadLocked()
	now := t.now()
	if tx.ResponseTime.IsZero() {
		tx.ResponseTime = now
	}
	if tx.LatencyMS < 0 {
		tx.LatencyMS = 0
	}
	if tx.QueryTime.IsZero() {
		tx.QueryTime = tx.ResponseTime.Add(-time.Duration(tx.LatencyMS) * time.Millisecond)
	}
	t.activity = append(t.activity, tx.QueryTime)
	t.pruneActivityLocked(now)
	if tx.Status == backend.DoHStatusComplete {
		t.lifetime++
	}
	if historyEnabled() {
		t.transactions = append(t.transactions, tx)
		if len(t.transactions) > historySize {
			t.transactions = t.transactions[len(t.transactions)-historySize:]
		}
	} else {
		t.transactions = nil
	}
	_ = t.saveLocked()
}

func (t *Tracker) Stats() Stats {
	t.mu.Lock()
	defer t.mu.Unlock()
	_ = t.loadLocked()
	t.pruneActivityLocked(t.now())
	if !historyEnabled() {
		t.transactions = nil
	}
	transactions := append([]Transaction(nil), t.transactions...)
	return Stats{
		LifetimeQueries:    t.lifetime,
		RecentQueries:      len(t.activity),
		RecentTransactions: transactions,
	}
}

func (t *Tracker) Save() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	_ = t.loadLocked()
	return t.saveLocked()
}

func (t *Tracker) loadLocked() error {
	if t.loaded {
		return nil
	}
	t.loaded = true
	data, err := os.ReadFile(t.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var store Store
	if err := json.Unmarshal(data, &store); err != nil {
		return err
	}
	t.lifetime = store.LifetimeQueries
	t.activity = append([]time.Time(nil), store.RecentActivity...)
	t.pruneActivityLocked(t.now())
	if historyEnabled() {
		t.transactions = append([]Transaction(nil), store.RecentTransactions...)
		if len(t.transactions) > historySize {
			t.transactions = t.transactions[len(t.transactions)-historySize:]
		}
	}
	return nil
}

func (t *Tracker) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(t.path), 0700); err != nil {
		return err
	}
	t.pruneActivityLocked(t.now())
	store := Store{
		LifetimeQueries: t.lifetime,
		RecentActivity:  append([]time.Time(nil), t.activity...),
	}
	if historyEnabled() {
		store.RecentTransactions = append([]Transaction(nil), t.transactions...)
	}
	data, err := json.MarshalIndent(store, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(t.path, data, 0666)
}

func (t *Tracker) pruneActivityLocked(now time.Time) {
	cutoff := now.Add(-activityMemory)
	keep := 0
	for ; keep < len(t.activity); keep++ {
		if !t.activity[keep].Before(cutoff) {
			break
		}
	}
	if keep > 0 {
		copy(t.activity, t.activity[keep:])
		t.activity = t.activity[:len(t.activity)-keep]
	}
}

func historyEnabled() bool {
	cfg, err := winsettings.Load()
	return err == nil && cfg.ShowRecentQueries
}

func queryName(query []byte) string {
	offset := 12
	var name []byte
	for offset < len(query) {
		n := int(query[offset])
		offset++
		if n == 0 {
			if len(name) == 0 {
				return "."
			}
			return string(name)
		}
		if n&0xc0 != 0 || offset+n > len(query) {
			return ""
		}
		if len(name) > 0 {
			name = append(name, '.')
		}
		name = append(name, query[offset:offset+n]...)
		offset += n
	}
	return ""
}

func queryType(query []byte) uint16 {
	offset := 12
	for offset < len(query) {
		n := int(query[offset])
		offset++
		if n == 0 {
			if offset+1 >= len(query) {
				return 0
			}
			return uint16(query[offset])<<8 | uint16(query[offset+1])
		}
		if n&0xc0 != 0 || offset+n > len(query) {
			return 0
		}
		offset += n
	}
	return 0
}
