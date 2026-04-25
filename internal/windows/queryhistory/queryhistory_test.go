//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package queryhistory

import (
	"path/filepath"
	"testing"
	"time"

	"localhost/Intra/Android/app/src/go/backend"
	winsettings "localhost/Intra/internal/windows/settings"
)

func newTestTracker(t *testing.T, now time.Time) *Tracker {
	t.Helper()
	tracker := New(filepath.Join(t.TempDir(), "history.json"))
	tracker.SetClock(func() time.Time { return now })
	if err := tracker.Load(); err != nil {
		t.Fatal(err)
	}
	return tracker
}

func TestSuccessfulQueryIncrementsLifetime(t *testing.T) {
	tracker := newTestTracker(t, time.Unix(1000, 0))
	tracker.Record(Transaction{Status: backend.DoHStatusComplete})
	if got := tracker.Stats().LifetimeQueries; got != 1 {
		t.Fatalf("LifetimeQueries = %d, want 1", got)
	}
}

func TestFailedQueryDoesNotIncrementLifetime(t *testing.T) {
	tracker := newTestTracker(t, time.Unix(1000, 0))
	tracker.Record(Transaction{Status: backend.DoHStatusSendFailed})
	if got := tracker.Stats().LifetimeQueries; got != 0 {
		t.Fatalf("LifetimeQueries = %d, want 0", got)
	}
}

func TestRecentWindowExpires(t *testing.T) {
	now := time.Unix(1000, 0)
	tracker := newTestTracker(t, now)
	tracker.Record(Transaction{Status: backend.DoHStatusComplete})
	tracker.SetClock(func() time.Time { return now.Add(61 * time.Second) })
	if got := tracker.Stats().RecentQueries; got != 0 {
		t.Fatalf("RecentQueries = %d, want 0", got)
	}
}

func TestMaxHistorySize(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	cfg := winsettings.Default()
	cfg.ShowRecentQueries = true
	if err := winsettings.Save(cfg); err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1000, 0)
	tracker := newTestTracker(t, now)
	for i := 0; i < historySize+10; i++ {
		tracker.Record(Transaction{Name: "example.com", Status: backend.DoHStatusComplete})
	}
	if got := len(tracker.Stats().RecentTransactions); got != historySize {
		t.Fatalf("RecentTransactions = %d, want %d", got, historySize)
	}
}

func TestDisabledHistorySuppressesTransactions(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	cfg := winsettings.Default()
	cfg.ShowRecentQueries = false
	if err := winsettings.Save(cfg); err != nil {
		t.Fatal(err)
	}
	tracker := newTestTracker(t, time.Unix(1000, 0))
	tracker.Record(Transaction{Name: "example.com", Status: backend.DoHStatusComplete})
	if got := len(tracker.Stats().RecentTransactions); got != 0 {
		t.Fatalf("RecentTransactions = %d, want 0", got)
	}
}

func TestPersistenceRoundTrip(t *testing.T) {
	now := time.Unix(1000, 0)
	path := filepath.Join(t.TempDir(), "history.json")
	tracker := New(path)
	tracker.SetClock(func() time.Time { return now })
	tracker.Record(Transaction{Status: backend.DoHStatusComplete})
	tracker.Record(Transaction{Status: backend.DoHStatusComplete})
	if err := tracker.Save(); err != nil {
		t.Fatal(err)
	}
	reloaded := New(path)
	if err := reloaded.Load(); err != nil {
		t.Fatal(err)
	}
	if got := reloaded.Stats().LifetimeQueries; got != 2 {
		t.Fatalf("LifetimeQueries = %d, want 2", got)
	}
}

func TestTransactionPersistenceWhenHistoryEnabled(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	cfg := winsettings.Default()
	cfg.ShowRecentQueries = true
	if err := winsettings.Save(cfg); err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1000, 0)
	path := filepath.Join(t.TempDir(), "history.json")
	tracker := New(path)
	tracker.SetClock(func() time.Time { return now })
	tracker.Record(Transaction{
		Name:      "www.example.com",
		Type:      1,
		LatencyMS: 25,
		Server:    "8.8.8.8",
		Status:    backend.DoHStatusComplete,
	})
	reloaded := New(path)
	reloaded.SetClock(func() time.Time { return now })
	if err := reloaded.Load(); err != nil {
		t.Fatal(err)
	}
	stats := reloaded.Stats()
	if stats.RecentQueries != 1 {
		t.Fatalf("RecentQueries = %d, want 1", stats.RecentQueries)
	}
	if got := len(stats.RecentTransactions); got != 1 {
		t.Fatalf("RecentTransactions = %d, want 1", got)
	}
	tx := stats.RecentTransactions[0]
	if tx.Name != "www.example.com" || tx.Type != 1 || tx.LatencyMS != 25 || tx.Server != "8.8.8.8" {
		t.Fatalf("unexpected transaction: %+v", tx)
	}
}
