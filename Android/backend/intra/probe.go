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
	"errors"
	"fmt"

	"github.com/Jigsaw-Code/Intra/Android/backend/intra/internal/doh"
)

var dohQuery = []byte{
	0, 0, // [0-1]   query ID
	1, 0, // [2-3]   flags, RD=1
	0, 1, // [4-5]   QDCOUNT (number of queries) = 1
	0, 0, // [6-7]   ANCOUNT (number of answers) = 0
	0, 0, // [8-9]   NSCOUNT (number of authoritative answers) = 0
	0, 0, // [10-11] ARCOUNT (number of additional records) = 0

	// Start of first query
	7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
	3, 'c', 'o', 'm',
	0,    // null terminator of FQDN (DNS root)
	0, 1, // QTYPE = A
	0, 1, // QCLASS = IN (Internet)
}

// ProbeDoHServer checks if a server can handle DNS-over-HTTP (DoH) requests. It does this by sending a DoH request to
// the `serverURL`. If the URL cannot be resolved to working IP addresses, the function will try the `fallbackAddrs`,
// which is a list of comma-separated IP addresses or hostnames. The `protecter` parameter is an implementation that
// protects a socket from VPN connections (please see [SocketProtector] for more information).
//
// If the server responds correctly, the function returns nil. Otherwise, the function returns an error.
func ProbeDoHServer(serverURL string, fallbackAddrs string, protector SocketProtector) error {
	t, err := doh.MakeTransport(serverURL, fallbackAddrs, protector, nil)
	if err != nil {
		return fmt.Errorf("failed to create DoH transport: %w", err)
	}
	resp, err := t.Query(dohQuery)
	if err != nil {
		return fmt.Errorf("failed to query DoH: %w", err)
	}
	if len(resp) == 0 {
		return errors.New("DoH response is empty")
	}
	return nil
}
