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

package doh

import (
	dohLegacy "github.com/Jigsaw-Code/outline-go-tun2socks/intra/doh"
	"github.com/Jigsaw-Code/outline-go-tun2socks/intra/protect"
)

type DoHTransport = dohLegacy.Transport

func MakeTransport(serverURL, fallbackAddrs string, protector Protector, listener dohLegacy.Listener) (DoHTransport, error) {
	fallbacks := parseFallbackAddrs(fallbackAddrs)
	dialer := protect.MakeDialer(protector)
	return dohLegacy.NewTransport(serverURL, fallbacks, dialer, nil, listener)
}