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
	"io"
	"io/fs"
	"log"
	"os"

	"github.com/Jigsaw-Code/outline-sdk/network"
)

type IPDevice io.ReadWriteCloser

const commonMTU = 1500

func BridgeAsync(d1, d2 IPDevice) error {
	if d1 == nil || d2 == nil {
		return errors.New("both devices are required")
	}
	go copyUntilEOF(d1, d2)
	go copyUntilEOF(d2, d1)
	return nil
}

func copyUntilEOF(dst, src IPDevice) {
	log.Printf("[debug] copying traffic from %v to %v\n", src, dst)
	buf := make([]byte, commonMTU)
	defer dst.Close()
	for {
		n, err := io.CopyBuffer(dst, src, buf)
		log.Printf("[debug] %v -> %v traffic copy session: %v %v\n", src, dst, n, err)
		if err == nil || isErrClosed(err) {
			log.Printf("[info] %v -> %v traffic copy session terminated\n", src, dst)
			return
		}
	}
}

func isErrClosed(err error) bool {
	return errors.Is(err, os.ErrClosed) || errors.Is(err, fs.ErrClosed) || errors.Is(err, network.ErrClosed)
}
