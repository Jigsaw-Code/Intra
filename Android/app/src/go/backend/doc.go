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
Package backend exposes objects and functions for Intra's application code (i.e., Java code).
It is the only packages that should be used by the application code, and is not intended for
use by other Go code.

This package provides the following features:

# DoHServer

[DoHServer] connects to a DNS-over-HTTPS (DoH) server that handles DNS requests.

# Session

Intra [Session] reads from a local tun device and:

- redirects DNS requests to a specific [DoHServer]
- splits TLS packets into two randomly sized packets
- Forwards all other traffic untouched
*/
package backend
