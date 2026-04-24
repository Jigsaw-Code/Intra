//go:build xjasonlyu_experiment

// Copyright 2026 Jigsaw Operations LLC
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
	"context"
	"errors"
	"io"

	"github.com/Jigsaw-Code/outline-sdk/network"
	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
)

type xjasonlyuEngine struct {
	ctx context.Context
	sd  *intraStreamDialer
	pp  *intraPacketProxy
}

var _ adapter.TransportHandler = (*xjasonlyuEngine)(nil)

func newXJasonlyuEngine(ctx context.Context, sd *intraStreamDialer, pp *intraPacketProxy) (network.IPDevice, error) {
	engine := &xjasonlyuEngine{
		ctx: ctx,
		sd:  sd,
		pp:  pp,
	}

	// Compile-only API probe: the next spike will provide the io.ReadWriter that connects Android
	// TUN packets to this endpoint and replace the nil link endpoint below with a real one.
	var rw io.ReadWriter
	linkEndpoint, err := iobased.New(rw, 1500, 0)
	if err == nil {
		_, _ = core.CreateStack(&core.Config{
			LinkEndpoint:     linkEndpoint,
			TransportHandler: engine,
		})
	}

	return nil, errors.New("xjasonlyu tun2socks engine is compile-only; bridging is not implemented")
}

func (e *xjasonlyuEngine) HandleTCP(conn adapter.TCPConn) {
	conn.Close()
}

func (e *xjasonlyuEngine) HandleUDP(conn adapter.UDPConn) {
	conn.Close()
}
