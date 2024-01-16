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

package protect

import (
	"context"
	"net"
	"sync"
	"syscall"
	"testing"
)

// The fake protector just records the file descriptors it was given.
type fakeProtector struct {
	mu  sync.Mutex
	fds []int32
}

func (p *fakeProtector) Protect(fd int32) bool {
	p.mu.Lock()
	p.fds = append(p.fds, fd)
	p.mu.Unlock()
	return true
}

func (p *fakeProtector) GetResolvers() string {
	return "8.8.8.8,2001:4860:4860::8888"
}

// This interface serves as a supertype of net.TCPConn and net.UDPConn, so
// that they can share the verifyMatch() function.
type hasSyscallConn interface {
	SyscallConn() (syscall.RawConn, error)
}

func verifyMatch(t *testing.T, conn hasSyscallConn, p *fakeProtector) {
	rawconn, err := conn.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	rawconn.Control(func(fd uintptr) {
		if len(p.fds) == 0 {
			t.Fatalf("No file descriptors")
		}
		if int32(fd) != p.fds[0] {
			t.Fatalf("File descriptor mismatch: %d != %d", fd, p.fds[0])
		}
	})
}

func TestDialTCP(t *testing.T) {
	l, err := net.Listen("tcp", "localhost:0")
	if err != nil {
		t.Fatal(err)
	}
	go l.Accept()

	p := &fakeProtector{}
	d := MakeDialer(p)
	if d.Control == nil {
		t.Errorf("Control function is nil")
	}

	conn, err := d.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	verifyMatch(t, conn.(*net.TCPConn), p)
	l.Close()
	conn.Close()
}

func TestListenUDP(t *testing.T) {
	udpaddr, err := net.ResolveUDPAddr("udp", "localhost:0")
	if err != nil {
		t.Fatal(err)
	}

	p := &fakeProtector{}
	c := MakeListenConfig(p)

	conn, err := c.ListenPacket(context.Background(), udpaddr.Network(), udpaddr.String())
	if err != nil {
		t.Fatal(err)
	}
	verifyMatch(t, conn.(*net.UDPConn), p)
	conn.Close()
}

func TestLookupIPAddr(t *testing.T) {
	p := &fakeProtector{}
	d := MakeDialer(p)
	d.Resolver.LookupIPAddr(context.Background(), "foo.test.")
	// Verify that Protect was called.
	if len(p.fds) == 0 {
		t.Fatal("Protect was not called")
	}
}

func TestNilDialer(t *testing.T) {
	l, err := net.Listen("tcp", "localhost:0")
	if err != nil {
		t.Fatal(err)
	}
	go l.Accept()

	d := MakeDialer(nil)
	conn, err := d.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}

	conn.Close()
	l.Close()
}

func TestNilListener(t *testing.T) {
	udpaddr, err := net.ResolveUDPAddr("udp", "localhost:0")
	if err != nil {
		t.Fatal(err)
	}

	c := MakeListenConfig(nil)
	conn, err := c.ListenPacket(context.Background(), udpaddr.Network(), udpaddr.String())
	if err != nil {
		t.Fatal(err)
	}

	conn.Close()
}
