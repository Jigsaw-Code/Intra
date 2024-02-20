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
type Token = interface{}

// Listener receives Summaries.
type Listener interface {
	OnQuery(url string) Token
	OnResponse(Token, *Summary)
}

// Transport represents a DNS query transport.
type Transport interface {
	// Query sends a DNS query represented by q (including ID) to this DoH server
	// (located at GetURL) using the provided context, and returns the correponding
	//
	// A non-nil error will be returned if no response was received from the DoH server,
	// the error may also be accompanied by a SERVFAIL response if appropriate.
	Query(ctx context.Context, q []byte) ([]byte, error)

	// Return the server URL used to initialize this transport.
	GetURL() string
}

// TODO: Keep a context here so that queries can be canceled.
type transport struct {
	Transport
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

func (t *transport) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	logging.Debug.Printf("Dialing %s\n", addr)
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
	ips := t.ips.Get(domain)
	confirmed := ips.Confirmed()
	if confirmed != nil {
		logging.Debug.Printf("Trying confirmed IP %s for addr %s\n", confirmed.String(), addr)
		if conn, err = split.DialWithSplitRetry(ctx, t.dialer, tcpaddr(confirmed), nil); err == nil {
			logging.Info.Printf("Confirmed IP %s worked\n", confirmed.String())
			return conn, nil
		}
		logging.Debug.Printf("Confirmed IP %s failed with err %v\n", confirmed.String(), err)
		ips.Disconfirm(confirmed)
	}

	logging.Debug.Println("Trying all IPs")
	for _, ip := range ips.GetAll() {
		if ip.Equal(confirmed) {
			// Don't try this IP twice.
			continue
		}
		if conn, err = split.DialWithSplitRetry(ctx, t.dialer, tcpaddr(ip), nil); err == nil {
			logging.Info.Printf("Found working IP: %s\n", ip.String())
			return conn, nil
		}
	}
	return nil, err
}

// NewTransport returns a DoH DNSTransport, ready for use.
// This is a POST-only DoH implementation, so the DoH template should be a URL.
//
// `rawurl` is the DoH template in string form.
//
// `addrs` is a list of domains or IP addresses to use as fallback, if the hostname lookup fails or
// returns non-working addresses.
//
// `dialer` is the dialer that the transport will use.  The transport will modify the dialer's
// timeout but will not mutate it otherwise.
//
// `auth` will provide a client certificate if required by the TLS server.
//
// `listener` will receive the status of each DNS query when it is complete.
func NewTransport(rawurl string, addrs []string, dialer *net.Dialer, auth ClientAuth, listener Listener) (Transport, error) {
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

	t := &transport{
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
func (t *transport) doQuery(ctx context.Context, q []byte) (response []byte, server *net.TCPAddr, qerr *queryError) {
	if len(q) < 2 {
		qerr = &queryError{BadQuery, fmt.Errorf("Query length is %d", len(q))}
		return
	}

	t.hangoverLock.RLock()
	inHangover := time.Now().Before(t.hangoverExpiration)
	t.hangoverLock.RUnlock()
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
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.url, bytes.NewBuffer(q))
	if err != nil {
		qerr = &queryError{InternalError, err}
		return
	}

	var hostname string
	response, hostname, server, qerr = t.sendRequest(id, req)

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
			t.hangoverLock.Lock()
			t.hangoverExpiration = time.Now().Add(hangoverDuration)
			t.hangoverLock.Unlock()
		}

		response = tryServfail(q)
	} else if server != nil {
		// Record a working IP address for this server iff qerr is nil
		t.ips.Get(hostname).Confirm(server.IP)
	}
	return
}

func (t *transport) sendRequest(id uint16, req *http.Request) (response []byte, hostname string, server *net.TCPAddr, qerr *queryError) {
	hostname = t.hostname

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
		logging.Info.Printf("%d Query failed: %v\n", id, qerr)
		if server != nil {
			logging.Debug.Printf("%d Disconfirming %s\n", id, server.IP.String())
			t.ips.Get(hostname).Disconfirm(server.IP)
		}
		if conn != nil {
			logging.Info.Printf("%d Closing failing DoH socket\n", id)
			conn.Close()
		}
	}()

	// Add a trace to the request in order to expose the server's IP address.
	// Only GotConn performs any action; the other methods just provide debug logs.
	// GotConn runs before client.Do() returns, so there is no data race when
	// reading the variables it has set.
	trace := httptrace.ClientTrace{
		GetConn: func(hostPort string) {
			logging.Debug.Printf("%d GetConn(%s)\n", id, hostPort)
		},
		GotConn: func(info httptrace.GotConnInfo) {
			logging.Debug.Printf("%d GotConn(%v)\n", id, info)
			if info.Conn == nil {
				return
			}
			conn = info.Conn
			// info.Conn is a DuplexConn, so RemoteAddr is actually a TCPAddr.
			server = conn.RemoteAddr().(*net.TCPAddr)
		},
		PutIdleConn: func(err error) {
			logging.Debug.Printf("%d PutIdleConn(%v)\n", id, err)
		},
		GotFirstResponseByte: func() {
			logging.Debug.Printf("%d GotFirstResponseByte()\n", id)
		},
		Got100Continue: func() {
			logging.Debug.Printf("%d Got100Continue()\n", id)
		},
		Got1xxResponse: func(code int, header textproto.MIMEHeader) error {
			logging.Debug.Printf("%d Got1xxResponse(%d, %v)\n", id, code, header)
			return nil
		},
		DNSStart: func(info httptrace.DNSStartInfo) {
			logging.Debug.Printf("%d DNSStart(%v)\n", id, info)
		},
		DNSDone: func(info httptrace.DNSDoneInfo) {
			logging.Debug.Printf("%d, DNSDone(%v)\n", id, info)
		},
		ConnectStart: func(network, addr string) {
			logging.Debug.Printf("%d ConnectStart(%s, %s)\n", id, network, addr)
		},
		ConnectDone: func(network, addr string, err error) {
			logging.Debug.Printf("%d ConnectDone(%s, %s, %v)\n", id, network, addr, err)
		},
		TLSHandshakeStart: func() {
			logging.Debug.Printf("%d TLSHandshakeStart()\n", id)
		},
		TLSHandshakeDone: func(state tls.ConnectionState, err error) {
			logging.Debug.Printf("%d TLSHandshakeDone(%v, %v)\n", id, state, err)
		},
		WroteHeaders: func() {
			logging.Debug.Printf("%d WroteHeaders()\n", id)
		},
		WroteRequest: func(info httptrace.WroteRequestInfo) {
			logging.Debug.Printf("%d WroteRequest(%v)\n", id, info)
		},
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), &trace))

	const mimetype = "application/dns-message"
	req.Header.Set("Content-Type", mimetype)
	req.Header.Set("Accept", mimetype)
	req.Header.Set("User-Agent", "Intra")
	logging.Debug.Printf("%d Sending query\n", id)
	httpResponse, err := t.client.Do(req)
	if err != nil {
		qerr = &queryError{SendFailed, err}
		return
	}
	logging.Debug.Printf("%d Got response\n", id)
	response, err = io.ReadAll(httpResponse.Body)
	if err != nil {
		qerr = &queryError{BadResponse, err}
		return
	}
	httpResponse.Body.Close()
	logging.Debug.Printf("%d Closed response\n", id)

	// Update the hostname, which could have changed due to a redirect.
	hostname = httpResponse.Request.URL.Hostname()

	if httpResponse.StatusCode != http.StatusOK {
		reqBuf := new(bytes.Buffer)
		req.Write(reqBuf)
		respBuf := new(bytes.Buffer)
		httpResponse.Write(respBuf)
		logging.Debug.Printf("%d request: %s\nresponse: %s\n", id, reqBuf.String(), respBuf.String())

		qerr = &queryError{HTTPError, &httpError{httpResponse.StatusCode}}
		return
	}

	return
}

func (t *transport) Query(ctx context.Context, q []byte) ([]byte, error) {
	var token Token
	if t.listener != nil {
		token = t.listener.OnQuery(t.url)
	}

	before := time.Now()
	response, server, qerr := t.doQuery(ctx, q)
	after := time.Now()

	var err error
	status := Complete
	httpStatus := http.StatusOK
	if qerr != nil {
		err = qerr
		status = qerr.status
		httpStatus = 0

		var herr *httpError
		if errors.As(qerr.err, &herr) {
			httpStatus = herr.status
		}
	}

	if t.listener != nil {
		latency := after.Sub(before)
		var ip string
		if server != nil {
			ip = server.IP.String()
		}

		t.listener.OnResponse(token, &Summary{
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

func (t *transport) GetURL() string {
	return t.url
}

// Perform a query using the transport, and send the response to the writer.
func forwardQuery(t Transport, q []byte, c io.Writer) error {
	resp, qerr := t.Query(context.Background(), q)
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

// Perform a query using the transport, send the response to the writer,
// and close the writer if there was an error.
func forwardQueryAndCheck(t Transport, q []byte, c io.WriteCloser) {
	if err := forwardQuery(t, q, c); err != nil {
		logging.Warn.Printf("Query forwarding failed: %v\n", err)
		c.Close()
	}
}

// Accept a DNS-over-TCP socket from a stub resolver, and connect the socket
// to this DNSTransport.
func Accept(t Transport, c io.ReadWriteCloser) {
	qlbuf := make([]byte, 2)
	for {
		n, err := c.Read(qlbuf)
		if n == 0 {
			logging.Debug.Println("TCP query socket clean shutdown")
			break
		}
		if err != nil {
			logging.Warn.Printf("Error reading from TCP query socket: %v\n", err)
			break
		}
		if n < 2 {
			logging.Warn.Println("Incomplete query length")
			break
		}
		qlen := binary.BigEndian.Uint16(qlbuf)
		q := make([]byte, qlen)
		n, err = c.Read(q)
		if err != nil {
			logging.Warn.Printf("Error reading query: %v\n", err)
			break
		}
		if n != int(qlen) {
			logging.Warn.Printf("Incomplete query: %d < %d\n", n, qlen)
			break
		}
		go forwardQueryAndCheck(t, q, c)
	}
	// TODO: Cancel outstanding queries at this point.
	c.Close()
}

// Servfail returns a SERVFAIL response to the query q.
func Servfail(q []byte) ([]byte, error) {
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
		logging.Warn.Printf("Error constructing servfail: %v\n", err)
	}
	return response
}
