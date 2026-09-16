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
	"testing"
	"time"

	"localhost/Intra/Android/app/src/go/doh"

	"github.com/stretchr/testify/require"
)

// ctxResolver is a [doh.Resolver] that runs a context-aware function.
type ctxResolver struct {
	doh.Resolver
	query func(ctx context.Context, q []byte) ([]byte, error)
}

func (r *ctxResolver) Query(ctx context.Context, q []byte) ([]byte, error) {
	return r.query(ctx, q)
}

// newTestTunnel creates the minimal [Tunnel] needed to exercise queryDNS.
func newTestTunnel(t *testing.T) *Tunnel {
	t.Helper()

	tun := &Tunnel{}
	tun.ctx, tun.cancel = context.WithCancel(context.Background())
	t.Cleanup(tun.cancel)
	return tun
}

// TestQueryDNSUsesCurrentResolver verifies that queryDNS always uses the resolver that is
// configured at the time of the query, so that SetDNS takes effect immediately.
func TestQueryDNSUsesCurrentResolver(t *testing.T) {
	tun := newTestTunnel(t)

	_, err := tun.queryDNS(context.Background(), []byte("query"))
	require.Error(t, err, "queryDNS must fail when there is no resolver")

	var first doh.Resolver = newFakeTransport(func([]byte) ([]byte, error) {
		return []byte("first"), nil
	})
	tun.dns.Store(&first)
	response, err := tun.queryDNS(context.Background(), []byte("query"))
	require.NoError(t, err)
	require.Equal(t, []byte("first"), response)

	var second doh.Resolver = newFakeTransport(func([]byte) ([]byte, error) {
		return []byte("second"), nil
	})
	tun.dns.Store(&second)
	response, err = tun.queryDNS(context.Background(), []byte("query"))
	require.NoError(t, err)
	require.Equal(t, []byte("second"), response)
}

// TestQueryDNSIsCanceledOnDisconnect verifies that disconnecting the tunnel cancels the
// queries that are still in flight.
func TestQueryDNSIsCanceledOnDisconnect(t *testing.T) {
	tun := newTestTunnel(t)

	started := make(chan struct{})
	var resolver doh.Resolver = &ctxResolver{query: func(ctx context.Context, _ []byte) ([]byte, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	tun.dns.Store(&resolver)

	errs := make(chan error, 1)
	go func() {
		_, err := tun.queryDNS(context.Background(), []byte("query"))
		errs <- err
	}()

	<-started
	tun.cancel()

	select {
	case err := <-errs:
		require.ErrorIs(t, err, context.Canceled)
	case <-time.After(testTimeout):
		t.Fatal("the in-flight query was not canceled")
	}
}
