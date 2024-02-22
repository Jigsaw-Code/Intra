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
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/textproto"
	"net/url"
	"strconv"
	"sync"
	"time"

	"localhost/Intra/Android/app/src/go/doh/ipmap"
	"localhost/Intra/Android/app/src/go/intra/split"
	"localhost/Intra/Android/app/src/go/logging"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	// Complete : Transaction completed successfully
	Complete = iota
	// SendFailed : Failed to send query
	SendFailed
	// HTTPError : Got a non-200 HTTP status
	HTTPError
	// BadQuery : Malformed input
	BadQuery
	// BadResponse : Response was invalid
	BadResponse
	// InternalError : This should never happen
	InternalError
)

// If the server sends an invalid reply, we start a "servfail hangover"
// of this duration, during which all queries are rejected.
// This rate-limits queries to misconfigured servers (e.g. wrong URL).
const hangoverDuration = 10 * time.Second

// Summary is a summary of a DNS transaction, reported when it is complete.
type Summary struct {
	Latency    float64 // Response (or failure) latency in seconds
	Query      []byte
	Response   []byte
	Server     string
	Status     int
	HTTPStatus int // Zero unless Status is Complete or HTTPError
}

// A Token is an opaque handle used to match responses to queries.
type Token interface{}

// Listener receives Summaries.
type Listener interface {
	OnQuery(url string) Token
	OnResponse(Token, *Summary)
}

// Resolver represents a DNS-over-HTTPS (DoH) resolver.
type Resolver interface {
	// Query sends a DNS query represented by q (including ID) to this DoH resolver
	// (located at GetURL) using the provided context, and returns the correponding
	//
	// A non-nil error will be returned if no response was received from the DoH resolver,
	// the error may also be accompanied by a SERVFAIL response if appropriate.
	Query(ctx context.Context, q []byte) ([]byte, error)

	// Return the server URL used to initialize this DoH resolver.
	GetURL() string
}

// TODO: Keep a context here so that queries can be canceled.
type resolver struct {
	Resolver
	url                string
	hostname           string
	port               int
	ips                ipmap.IPMap
	client             http.Client
	dialer             *net.Dialer
	listener           Listener
	hangoverLock       sync.RWMutex
	hangoverExpiration time.Time
}

// Wait up to three seconds for the TCP handshake to complete.
const tcpTimeout time.Duration = 3 * time.Second

func (r *resolver) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	logging.Debug("DoH(resolver.dial) - dialing", "addr", addr)
	domain, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, err
	}

	tcpaddr := func(ip net.IP) *net.TCPAddr {
		return &net.TCPAddr{IP: ip, Port: port}
	}

	// TODO: Improve IP fallback strategy with parallelism and Happy Eyeballs.
	var conn net.Conn
	ips := r.ips.Get(domain)
	confirmed := ips.Confirmed()
	if confirmed != nil {
		logging.Debug("DoH(resolver.dial) - trying confirmed IP", "confirmedIP", confirmed, "addr", addr)
		if conn, err = split.DialWithSplitRetry(ctx, r.dialer, tcpaddr(confirmed), nil); err == nil {
			logging.Info("DoH(resolver.dial) - confirmed IP worked", "confirmedIP", confirmed)
			return conn, nil
		}
		logging.Debug("DoH(resolver.dial) - confirmed IP failed", "confirmedIP", confirmed, "err", err)
		ips.Disconfirm(confirmed)
	}

	logging.Debug("DoH(resolver.dial) - trying all IPs")
	for _, ip := range ips.GetAll() {
		if ip.Equal(confirmed) {
			// Don't try this IP twice.
			continue
		}
		if conn, err = split.DialWithSplitRetry(ctx, r.dialer, tcpaddr(ip), nil); err == nil {
			logging.Info("DoH(resolver.dial) - found working IP", "ip", ip)
			return conn, nil
		}
	}
	return nil, err
}

// NewResolver returns a DoH [Resolver], ready for use.
// This is a POST-only DoH implementation, so the DoH template should be a URL.
//
// `rawurl` is the DoH template in string form.
//
// `addrs` is a list of domains or IP addresses to use as fallback, if the hostname lookup fails or
// returns non-working addresses.
//
// `dialer` is the dialer that the [Resolver] will use.  The [Resolver] will modify the dialer's
// timeout but will not mutate it otherwise.
//
// `auth` will provide a client certificate if required by the TLS server.
//
// `listener` will receive the status of each DNS query when it is complete.
func NewResolver(rawurl string, addrs []string, dialer *net.Dialer, auth ClientAuth, listener Listener) (Resolver, error) {
	if dialer == nil {
		dialer = &net.Dialer{}
	}
	parsedurl, err := url.Parse(rawurl)
	if err != nil {
		return nil, err
	}
	if parsedurl.Scheme != "https" {
		return nil, fmt.Errorf("Bad scheme: %s", parsedurl.Scheme)
	}
	// Resolve the hostname and put those addresses first.
	portStr := parsedurl.Port()
	var port int
	if len(portStr) > 0 {
		port, err = strconv.Atoi(portStr)
		if err != nil {
			return nil, err
		}
	} else {
		port = 443
	}

	t := &resolver{
		url:      rawurl,
		hostname: parsedurl.Hostname(),
		port:     port,
		listener: listener,
		dialer:   dialer,
		ips:      ipmap.NewIPMap(dialer.Resolver),
	}
	ips := t.ips.Get(t.hostname)
	for _, addr := range addrs {
		ips.Add(addr)
	}
	if ips.Empty() {
		return nil, fmt.Errorf("No IP addresses for %s", t.hostname)
	}

	// Supply a client certificate during TLS handshakes.
	var tlsconfig *tls.Config
	if auth != nil {
		signer := newClientAuthWrapper(auth)
		tlsconfig = &tls.Config{
			GetClientCertificate: signer.GetClientCertificate,
		}
	}

	// Override the dial function.
	t.client.Transport = &http.Transport{
		DialContext:           t.dial,
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second, // Same value as Android DNS-over-TLS
		TLSClientConfig:       tlsconfig,
	}
	return t, nil
}

type queryError struct {
	status int
	err    error
}

func (e *queryError) Error() string {
	return e.err.Error()
}

func (e *queryError) Unwrap() error {
	return e.err
}

type httpError struct {
	status int
}

func (e *httpError) Error() string {
	return fmt.Sprintf("HTTP request failed: %d", e.status)
}

// Given a raw DNS query (including the query ID), this function sends the
// query.  If the query is successful, it returns the response and a nil qerr.  Otherwise,
// it returns a SERVFAIL response and a qerr with a status value indicating the cause.
// Independent of the query's success or failure, this function also returns the
// address of the server on a best-effort basis, or nil if the address could not
// be determined.
func (r *resolver) doQuery(ctx context.Context, q []byte) (response []byte, server *net.TCPAddr, qerr *queryError) {
	if len(q) < 2 {
		qerr = &queryError{BadQuery, fmt.Errorf("Query length is %d", len(q))}
		return
	}

	r.hangoverLock.RLock()
	inHangover := time.Now().Before(r.hangoverExpiration)
	r.hangoverLock.RUnlock()
	if inHangover {
		response = tryServfail(q)
		qerr = &queryError{HTTPError, errors.New("Forwarder is in servfail hangover")}
		return
	}

	// Add padding to the raw query
	q, err := AddEdnsPadding(q)
	if err != nil {
		qerr = &queryError{InternalError, err}
		return
	}

	// Zero out the query ID.
	id := binary.BigEndian.Uint16(q)
	binary.BigEndian.PutUint16(q, 0)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.url, bytes.NewBuffer(q))
	if err != nil {
		qerr = &queryError{InternalError, err}
		return
	}

	var hostname string
	response, hostname, server, qerr = r.sendRequest(id, req)

	// Restore the query ID.
	binary.BigEndian.PutUint16(q, id)
	if qerr == nil {
		if len(response) >= 2 {
			if binary.BigEndian.Uint16(response) == 0 {
				binary.BigEndian.PutUint16(response, id)
			} else {
				qerr = &queryError{BadResponse, errors.New("Nonzero response ID")}
			}
		} else {
			qerr = &queryError{BadResponse, fmt.Errorf("Response length is %d", len(response))}
		}
	}

	if qerr != nil {
		if qerr.status != SendFailed {
			r.hangoverLock.Lock()
			r.hangoverExpiration = time.Now().Add(hangoverDuration)
			r.hangoverLock.Unlock()
		}

		response = tryServfail(q)
	} else if server != nil {
		// Record a working IP address for this server iff qerr is nil
		r.ips.Get(hostname).Confirm(server.IP)
	}
	return
}

func (r *resolver) sendRequest(id uint16, req *http.Request) (response []byte, hostname string, server *net.TCPAddr, qerr *queryError) {
	hostname = r.hostname

	// The connection used for this request.  If the request fails, we will close
	// this socket, in case it is no longer functioning.
	var conn net.Conn

	// Error cleanup function.  If the query fails, this function will close the
	// underlying socket and disconfirm the server IP.  Empirically, sockets often
	// become unresponsive after a network change, causing timeouts on all requests.
	defer func() {
		if qerr == nil {
			return
		}
		logging.Info("DoH(resolver.sendRequest) - done", "id", id, "queryError", qerr)
		if server != nil {
			logging.Debug("DoH(resolver.sendRequest) - disconfirming IP", "id", id, "ip", server.IP)
			r.ips.Get(hostname).Disconfirm(server.IP)
		}
		if conn != nil {
			logging.Info("DoH(resolver.sendRequest) - closing failing DoH socket", "id", id)
			conn.Close()
		}
	}()

	// Add a trace to the request in order to expose the server's IP address.
	// Only GotConn performs any action; the other methods just provide debug logs.
	// GotConn runs before client.Do() returns, so there is no data race when
	// reading the variables it has set.
	trace := httptrace.ClientTrace{
		GetConn: func(hostPort string) {
			logging.Debugf("%d GetConn(%s)", id, hostPort)
		},
		GotConn: func(info httptrace.GotConnInfo) {
			logging.Debugf("%d GotConn(%v)", id, info)
			if info.Conn == nil {
				return
			}
			conn = info.Conn
			// info.Conn is a DuplexConn, so RemoteAddr is actually a TCPAddr.
			server = conn.RemoteAddr().(*net.TCPAddr)
		},
		PutIdleConn: func(err error) {
			logging.Debugf("%d PutIdleConn(%v)", id, err)
		},
		GotFirstResponseByte: func() {
			logging.Debugf("%d GotFirstResponseByte()", id)
		},
		Got100Continue: func() {
			logging.Debugf("%d Got100Continue()", id)
		},
		Got1xxResponse: func(code int, header textproto.MIMEHeader) error {
			logging.Debugf("%d Got1xxResponse(%d, %v)", id, code, header)
			return nil
		},
		DNSStart: func(info httptrace.DNSStartInfo) {
			logging.Debugf("%d DNSStart(%v)", id, info)
		},
		DNSDone: func(info httptrace.DNSDoneInfo) {
			logging.Debugf("%d, DNSDone(%v)", id, info)
		},
		ConnectStart: func(network, addr string) {
			logging.Debugf("%d ConnectStart(%s, %s)", id, network, addr)
		},
		ConnectDone: func(network, addr string, err error) {
			logging.Debugf("%d ConnectDone(%s, %s, %v)", id, network, addr, err)
		},
		TLSHandshakeStart: func() {
			logging.Debugf("%d TLSHandshakeStart()", id)
		},
		TLSHandshakeDone: func(state tls.ConnectionState, err error) {
			logging.Debugf("%d TLSHandshakeDone(%v, %v)", id, state, err)
		},
		WroteHeaders: func() {
			logging.Debugf("%d WroteHeaders()", id)
		},
		WroteRequest: func(info httptrace.WroteRequestInfo) {
			logging.Debugf("%d WroteRequest(%v)", id, info)
		},
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), &trace))

	const mimetype = "application/dns-message"
	req.Header.Set("Content-Type", mimetype)
	req.Header.Set("Accept", mimetype)
	req.Header.Set("User-Agent", "Intra")
	logging.Debug("DoH(resolver.sendRequest) - sending query", "id", id)
	httpResponse, err := r.client.Do(req)
	if err != nil {
		qerr = &queryError{SendFailed, err}
		return
	}
	logging.Debug("DoH(resolver.sendRequest) - got response", "id", id)
	response, err = io.ReadAll(httpResponse.Body)
	if err != nil {
		qerr = &queryError{BadResponse, err}
		return
	}
	httpResponse.Body.Close()
	logging.Debug("DoH(resolver.sendRequest) - response closed", "id", id)

	// Update the hostname, which could have changed due to a redirect.
	hostname = httpResponse.Request.URL.Hostname()

	if httpResponse.StatusCode != http.StatusOK {
		reqBuf := new(bytes.Buffer)
		req.Write(reqBuf)
		respBuf := new(bytes.Buffer)
		httpResponse.Write(respBuf)
		logging.Debug("DoH(resolver.sendRequest) - response invalid", "id", id, "req", reqBuf, "resp", respBuf)

		qerr = &queryError{HTTPError, &httpError{httpResponse.StatusCode}}
		return
	}

	return
}

func (r *resolver) Query(ctx context.Context, q []byte) ([]byte, error) {
	var token Token
	if r.listener != nil {
		token = r.listener.OnQuery(r.url)
	}

	before := time.Now()
	response, server, qerr := r.doQuery(ctx, q)
	after := time.Now()

	errIsCancel := false
	var err error
	status := Complete
	httpStatus := http.StatusOK
	if qerr != nil {
		err = qerr
		status = qerr.status
		httpStatus = 0
		errIsCancel = errors.Is(qerr, context.Canceled)

		var herr *httpError
		if errors.As(qerr.err, &herr) {
			httpStatus = herr.status
		}
	}

	// Stop sending OnResponse when the error is cancelled because the cancelled
	// error is typically triggered by a Disconnect() operation, which will cause
	// the following deadlock:
	//   1. Java - synchronized VpnController.stop()
	//   2. Go   -   context.Cancel()
	//   3. Go   -   (if we don't stop sending OnResponse)
	//   4. Java -   GoIntraListener.onResponse
	//   5. Java -   synchronized VpnController.onConnectionStateChanged()
	// Deadlock happens (both Step 1 and Step 5 are marked as synchronized)!
	//
	// TODO: make stop() an asynchronized function
	if r.listener != nil && !errIsCancel {
		latency := after.Sub(before)
		var ip string
		if server != nil {
			ip = server.IP.String()
		}

		r.listener.OnResponse(token, &Summary{
			Latency:    latency.Seconds(),
			Query:      q,
			Response:   response,
			Server:     ip,
			Status:     status,
			HTTPStatus: httpStatus,
		})
	}
	return response, err
}

func (r *resolver) GetURL() string {
	return r.url
}

// Perform a query using the Resolver, and send the response to the writer.
func forwardQuery(r Resolver, q []byte, c io.Writer) error {
	resp, qerr := r.Query(context.Background(), q)
	if resp == nil && qerr != nil {
		return qerr
	}
	rlen := len(resp)
	if rlen > math.MaxUint16 {
		return fmt.Errorf("Oversize response: %d", rlen)
	}
	// Use a combined write to ensure atomicity.  Otherwise, writes from two
	// responses could be interleaved.
	rlbuf := make([]byte, rlen+2)
	binary.BigEndian.PutUint16(rlbuf, uint16(rlen))
	copy(rlbuf[2:], resp)
	n, err := c.Write(rlbuf)
	if err != nil {
		return err
	}
	if int(n) != len(rlbuf) {
		return fmt.Errorf("Incomplete response write: %d < %d", n, len(rlbuf))
	}
	return qerr
}

// Perform a query using the Resolver, send the response to the writer,
// and close the writer if there was an error.
func forwardQueryAndCheck(r Resolver, q []byte, c io.WriteCloser) {
	if err := forwardQuery(r, q, c); err != nil {
		logging.Warn("DoH(forwardQueryAndCheck) - forwarding failed", "err", err)
		c.Close()
	}
}

// Accept a DNS-over-TCP socket, and connect the socket to a DoH Resolver.
func Accept(r Resolver, c io.ReadWriteCloser) {
	qlbuf := make([]byte, 2)
	for {
		n, err := c.Read(qlbuf)
		if n == 0 {
			logging.Debug("DoH(Accept) - TCP query socket clean shutdown")
			break
		}
		if err != nil {
			logging.Warn("DoH(Accept) - failed to read from TCP query socket", "err", err)
			break
		}
		if n < 2 {
			logging.Warn("DoH(Accept) - incomplete query length")
			break
		}
		qlen := binary.BigEndian.Uint16(qlbuf)
		q := make([]byte, qlen)
		n, err = c.Read(q)
		if err != nil {
			logging.Warn("DoH(Accept) - failed to read query", "err", err)
			break
		}
		if n != int(qlen) {
			logging.Warn("DoH(Accept) - incomplete query (n < qlen)", "n", n, "qlen", qlen)
			break
		}
		go forwardQueryAndCheck(r, q, c)
	}
	// TODO: Cancel outstanding queries at this point.
	c.Close()
}

// Servfail returns a SERVFAIL response to the query q.
func Servfail(q []byte) ([]byte, error) {
	defer logging.Debug("DoH(SERVFAIL) - response generated")
	var msg dnsmessage.Message
	if err := msg.Unpack(q); err != nil {
		return nil, err
	}
	msg.Response = true
	msg.RecursionAvailable = true
	msg.RCode = dnsmessage.RCodeServerFailure
	msg.Additionals = nil // Strip EDNS
	return msg.Pack()
}

func tryServfail(q []byte) []byte {
	response, err := Servfail(q)
	if err != nil {
		logging.Warn("DoH(SERVFAIL) - failed to construct response", "err", err)
	}
	return response
}
