//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package state

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"

	"localhost/Intra/internal/windows/netconfig"
)

const journalFile = "windows-tunnel-journal.json"
const logFile = "windows-service.log"

type Journal struct {
	Active    bool
	CreatedAt time.Time
	Config    netconfig.Config
	Snapshot  netconfig.Snapshot
}

func Path() string {
	return filepath.Join(dir(), journalFile)
}

func LogPath() string {
	return filepath.Join(dir(), logFile)
}

func dir() string {
	root := os.Getenv("ProgramData")
	if root == "" {
		root = os.TempDir()
	}
	return filepath.Join(root, "Intra")
}

func LoadActive() (*Journal, error) {
	data, err := os.ReadFile(Path())
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var journal Journal
	if err := json.Unmarshal(data, &journal); err != nil {
		return nil, err
	}
	if !journal.Active {
		return nil, nil
	}
	return &journal, nil
}

func SaveActive(cfg netconfig.Config, snapshot netconfig.Snapshot) error {
	path := Path()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(Journal{
		Active:    true,
		CreatedAt: time.Now().UTC(),
		Config:    cfg,
		Snapshot:  snapshot,
	}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0666)
}

func Clear() error {
	err := os.Remove(Path())
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}
