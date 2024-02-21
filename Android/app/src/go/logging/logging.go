// Copyright 2024 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/*
Package logging is a centralized logging system for Intra's Go backend.
It offers efficient logging methods that save CPU power by only formatting
messages that need to be logged.
*/
package logging

import (
	"context"
	"fmt"
	"log/slog"
	"os"
)

var logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{
	Level: slog.LevelWarn,
}))

func Dbg(msg string, args ...any) {
	logger.Debug(msg, args...)
}

func Dbgf(format string, args ...any) {
	if !logger.Enabled(context.Background(), slog.LevelDebug) {
		return
	}
	logger.Debug(fmt.Sprintf(format, args...))
}

func Info(msg string, args ...any) {
	logger.Info(msg, args...)
}

func Infof(format string, args ...any) {
	if !logger.Enabled(context.Background(), slog.LevelInfo) {
		return
	}
	logger.Info(fmt.Sprintf(format, args...))
}

func Warn(msg string, args ...any) {
	logger.Warn(msg, args...)
}

func Warnf(format string, args ...any) {
	if !logger.Enabled(context.Background(), slog.LevelWarn) {
		return
	}
	logger.Warn(fmt.Sprintf(format, args...))
}

func Err(msg string, args ...any) {
	logger.Error(msg, args...)
}

func Errf(format string, args ...any) {
	if !logger.Enabled(context.Background(), slog.LevelError) {
		return
	}
	logger.Error(fmt.Sprintf(format, args...))
}
