//go:build windows

// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package main

import (
	"localhost/Intra/internal/windows/uiapi"
	windowsui "localhost/Intra/ui/windows"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

func main() {
	app := uiapi.NewApp()
	err := wails.Run(&options.App{
		Title:  "Intra",
		Width:  420,
		Height: 640,
		AssetServer: &assetserver.Options{
			Assets: windowsui.Assets,
		},
		OnStartup: app.Startup,
		Bind: []interface{}{
			app,
		},
	})
	if err != nil {
		println("Intra UI failed:", err.Error())
	}
}
