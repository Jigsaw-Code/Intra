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

package backend

import (
	"errors"
	"io"
	"io/fs"
	"localhost/Intra/Android/app/src/go/intra"
	"localhost/Intra/Android/app/src/go/intra/protect"
	"localhost/Intra/Android/app/src/go/logging"
	"os"

	"github.com/Jigsaw-Code/outline-sdk/network"
)

// Session represents an Intra communication session.
type Session struct {
	*intra.Tunnel
}

func (s *Session) SetDoHServer(svr *DoHServer) { s.SetDNS(svr.r) }

// ConnectReadWriteCloser connects an already-created TUN device to Intra.
//
// Windows Wintun does not expose a Unix file descriptor, so the Windows backend
// uses this path instead of ConnectSession.
func ConnectReadWriteCloser(
	tun io.ReadWriteCloser, fakedns string, dohdns *DoHServer, protector protect.Protector, listener intra.Listener,
) (*Session, error) {
	if tun == nil {
		return nil, errors.New("tun must not be nil")
	}
	if dohdns == nil {
		return nil, errors.New("dohdns must not be nil")
	}
	t, err := intra.NewTunnel(fakedns, dohdns.r, tun, protector, listener)
	if err != nil {
		return nil, err
	}
	go copyUntilEOF(t, tun)
	go copyUntilEOF(tun, t)
	return &Session{t}, nil
}

func copyUntilEOF(dst, src io.ReadWriteCloser) {
	logging.Debug("IntraSession(copyUntilEOF) - start relaying traffic", "src", src, "dst", dst)
	defer logging.Debug("IntraSession(copyUntilEOF) - stop relaying traffic", "src", src, "dst", dst)

	const commonMTU = 1500
	buf := make([]byte, commonMTU)
	for {
		_, err := io.CopyBuffer(dst, src, buf)
		if err == nil || isErrClosed(err) {
			return
		}
	}
}

func isErrClosed(err error) bool {
	return errors.Is(err, os.ErrClosed) || errors.Is(err, fs.ErrClosed) || errors.Is(err, network.ErrClosed)
}
