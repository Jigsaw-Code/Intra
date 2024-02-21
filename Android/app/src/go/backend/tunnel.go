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

package backend

import (
	"errors"
	"io"
	"io/fs"
	"localhost/Intra/Android/app/src/go/intra"
	"localhost/Intra/Android/app/src/go/intra/protect"
	"localhost/Intra/Android/app/src/go/logging"
	"localhost/Intra/Android/app/src/go/tuntap"
	"os"

	"github.com/Jigsaw-Code/outline-sdk/network"
)

// Session represents an Intra communication session.
//
// TODO: copy methods of intra.Tunnel here and do not expose intra package
type Session struct {
	// TODO: hide this internal Tunnel when finished moving everything to backend
	*intra.Tunnel
}

func (s *Session) SetDoHServer(svr *DoHServer) { s.SetDNS(svr.r) }

// ConnectSession reads packets from a TUN device and applies the Intra routing
// rules. Currently, this only consists of redirecting DNS packets to a specified
// server; all other data flows directly to its destination.
//
// fd is the TUN device. The intra [Session] acquires an additional reference to it,
// which is released by [Session].Disconnect(), so the caller must close `fd` _and_ call
// Disconnect() in order to close the TUN device.
//
// fakedns is the DNS server that the system believes it is using, in "host:port" style.
// The port is normally 53.
//
// dohdns is the initial DoH transport and must not be nil.
//
// protector is a wrapper for Android's VpnService.protect() method.
//
// eventListener will be provided with a summary of each TCP and UDP socket when it is closed.
func ConnectSession(
	fd int, fakedns string, dohdns *DoHServer, protector protect.Protector, listener intra.Listener,
) (*Session, error) {
	// TODO: define Tunnel type in this backend package, and do not export intra package
	tun, err := tuntap.MakeTunDeviceFromFD(fd)
	if err != nil {
		return nil, err
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
	logging.Dbg("IntraSession(copyUntilEOF) - start relaying traffic", "src", src, "dst", dst)
	defer logging.Dbg("IntraSession(copyUntilEOF) - stop relaying traffic", "src", src, "dst", dst)

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
