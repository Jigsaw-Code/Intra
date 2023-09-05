// Copyright 2023 Jigsaw Operations LLC
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

package intra

import (
	intraLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra"
	dohLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra/doh"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/split"
)

// We will only export types defined in this package. So we need to redefine the event types here.
// The legacy code is exporting structs, which is not ideal, we will replace them with interfaces.

type EventListener interface {
	OnTCPSocketClosed(TCPSocketStats)
	OnUDPSocketClosed(UDPSocketStats)
	OnQuery(string) DoHToken
	OnResponse(DoHToken, DoHQueryStats)
}

// eventListenerAdapter is an bridge connecting EventListener and the listener types defined in legacy code.
// We cannot reuse EventListener because we redefined all function parameter types, which is inconsistent with the
// original types.
type eventListenerAdapter struct {
	listener EventListener
}

func (e eventListenerAdapter) OnTCPSocketClosed(s *intraLegacy.TCPSocketSummary) {
	e.listener.OnTCPSocketClosed(tcpSocketStatsAdapter{s})
}

func (e eventListenerAdapter) OnUDPSocketClosed(s *intraLegacy.UDPSocketSummary) {
	e.listener.OnUDPSocketClosed(udpSocketStatsAdapter{s})
}

func (e eventListenerAdapter) OnQuery(s string) dohLegacy.Token {
	return e.listener.OnQuery(s)
}

func (e eventListenerAdapter) OnResponse(t dohLegacy.Token, s *dohLegacy.Summary) {
	e.listener.OnResponse(t, dohStatsAdapter{s})
}

////////// TCPListener type redefinitions

type TCPRetryStats interface {
	GetSNI() string   // TLS SNI observed, if present.
	GetBytes() int32  // Number of bytes uploaded before the retry.
	GetChunks() int16 // Number of writes before the retry.
	GetSplit() int16  // Number of bytes in the first retried segment.
	GetTimeout() bool // True if the retry was caused by a timeout.
}

type TCPSocketStats interface {
	GetDownloadBytes() int64 // Total bytes downloaded.
	GetUploadBytes() int64   // Total bytes uploaded.
	GetDuration() int32      // Duration in seconds.
	GetServerPort() int16    // The server port.  All values except 80, 443, and 0 are set to -1.
	GetSynack() int32        // TCP handshake latency (ms)
	GetRetry() TCPRetryStats // Retry is non-nil if retry was possible.  Retry.Split is non-zero if a retry occurred.
}

type tcpRetryStatsAdapter struct {
	*split.RetryStats
}

func (s tcpRetryStatsAdapter) GetSNI() string   { return s.SNI }
func (s tcpRetryStatsAdapter) GetBytes() int32  { return s.Bytes }
func (s tcpRetryStatsAdapter) GetChunks() int16 { return s.Chunks }
func (s tcpRetryStatsAdapter) GetSplit() int16  { return s.Split }
func (s tcpRetryStatsAdapter) GetTimeout() bool { return s.Timeout }

type tcpSocketStatsAdapter struct {
	*intraLegacy.TCPSocketSummary
}

func (s tcpSocketStatsAdapter) GetDownloadBytes() int64 { return s.DownloadBytes }
func (s tcpSocketStatsAdapter) GetUploadBytes() int64   { return s.UploadBytes }
func (s tcpSocketStatsAdapter) GetDuration() int32      { return s.Duration }
func (s tcpSocketStatsAdapter) GetServerPort() int16    { return s.ServerPort }
func (s tcpSocketStatsAdapter) GetSynack() int32        { return s.Synack }
func (s tcpSocketStatsAdapter) GetRetry() TCPRetryStats { return tcpRetryStatsAdapter{s.Retry} }

////////// UDPListener type redefinitions

type UDPSocketStats interface {
	GetUploadBytes() int64   // Amount uploaded (bytes)
	GetDownloadBytes() int64 // Amount downloaded (bytes)
	GetDuration() int32      // How long the socket was open (seconds)
}

type udpSocketStatsAdapter struct {
	*intraLegacy.UDPSocketSummary
}

func (s udpSocketStatsAdapter) GetUploadBytes() int64   { return s.UploadBytes }
func (s udpSocketStatsAdapter) GetDownloadBytes() int64 { return s.DownloadBytes }
func (s udpSocketStatsAdapter) GetDuration() int32      { return s.Duration }

////////// DoHListener type redefinitions

const (
	DoHStatusComplete      int = dohLegacy.Complete
	DoHStatusSendFailed    int = dohLegacy.SendFailed
	DoHStatusHTTPError     int = dohLegacy.HTTPError
	DoHStatusBadQuery      int = dohLegacy.BadQuery
	DoHStatusBadResponse   int = dohLegacy.BadResponse
	DoHStatusInternalError int = dohLegacy.InternalError
)

type DoHToken dohLegacy.Token

type DoHQueryStats interface {
	GetLatency() float64 // Response (or failure) latency in seconds
	GetQuery() []byte
	GetResponse() []byte
	GetServer() string
	GetStatus() int
	GetHTTPStatus() int // Zero unless Status is Complete or HTTPError
}

type dohStatsAdapter struct {
	*dohLegacy.Summary
}

func (s dohStatsAdapter) GetLatency() float64 { return s.Latency }
func (s dohStatsAdapter) GetQuery() []byte    { return s.Query }
func (s dohStatsAdapter) GetResponse() []byte { return s.Response }
func (s dohStatsAdapter) GetServer() string   { return s.Server }
func (s dohStatsAdapter) GetStatus() int      { return s.Status }
func (s dohStatsAdapter) GetHTTPStatus() int  { return s.HTTPStatus }
