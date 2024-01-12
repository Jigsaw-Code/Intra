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
	"sync/atomic"
)

// Atomic is atomic.Value, specialized for doh.Transport.
type Atomic struct {
	v atomic.Value
}

// Store a DNSTransport.  d must not be nil.
func (a *Atomic) Store(t Transport) {
	a.v.Store(t)
}

// Load the DNSTransport, or nil if it has not been stored.
func (a *Atomic) Load() Transport {
	v := a.v.Load()
	if v == nil {
		return nil
	}
	return v.(Transport)
}
