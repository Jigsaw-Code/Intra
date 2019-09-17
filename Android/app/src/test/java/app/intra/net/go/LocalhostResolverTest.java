/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.net.go;

import app.intra.net.doh.Transaction;
import app.intra.net.doh.ServerConnection;
import app.intra.sys.IntraVpnService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

import app.intra.net.dns.DnsUdpQuery;
import app.intra.net.doh.IpTagInterceptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LocalhostResolverTest {
  private LocalhostResolver resolver;
  private IntraVpnService mockVpn;
  private ServerConnection mockConnection;

  @Captor
  private ArgumentCaptor<DnsUdpQuery> queryCaptor;
  @Captor
  private ArgumentCaptor<byte[]> dataCaptor;
  @Captor
  private ArgumentCaptor<Callback> callbackCaptor;
  @Captor ArgumentCaptor<Transaction> transactionCaptor;

  DatagramSocket clientSocket;

  private static final byte[] QUERY_DATA = {
      -107, -6,  // [0-1]   query ID
      1, 0,      // [2-3]   flags, RD=1
      0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
      0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
      0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
      0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
      // Start of first query
      7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
      3, 'c', 'o', 'm',
      0,  // null terminator of FQDN (DNS root)
      0, 1,  // QTYPE = A
      0, 1   // QCLASS = IN (Internet)
  };

  private static final DnsUdpQuery QUERY = DnsUdpQuery.fromUdpBody(QUERY_DATA);

  // This is a fake IP address for the remote DOH server.
  private static final String SERVER_IP = "any:string.will.do";

  @Before
  public void init(){
    MockitoAnnotations.initMocks(this);
  }

  @Before
  public void setUp() throws Exception {
    mockVpn = mock(IntraVpnService.class);
    mockConnection = mock(ServerConnection.class);
    when(mockVpn.getServerConnection()).thenReturn(mockConnection);
    resolver = LocalhostResolver.get(mockVpn);
    assertNotNull(resolver);
    resolver.start();
    clientSocket = new DatagramSocket();
    clientSocket.connect(resolver.getAddress());
  }

  @After
  public void tearDown() throws Exception {
    resolver.shutdown();
    clientSocket.close();
  }

  @Test
  public void getAddress() throws Exception {
    InetSocketAddress boundAddress = resolver.getAddress();
    assertEquals(InetAddress.getByName("localhost"), boundAddress.getAddress());
    assertNotEquals(0, boundAddress.getPort());
    assertNotEquals(-1, boundAddress.getPort());
  }

  private void sendQuery() throws Exception {
    DatagramPacket queryPacket = new DatagramPacket(QUERY_DATA, QUERY_DATA.length);
    queryPacket.setSocketAddress(resolver.getAddress());

    // Make a request.
    clientSocket.send(queryPacket);

    // Expect a call to performDnsRequest
    verify(mockConnection, timeout(1000)).
        performDnsRequest(queryCaptor.capture(), dataCaptor.capture(), callbackCaptor.capture());

    DnsUdpQuery query = queryCaptor.getValue();
    assertEquals(QUERY.name, query.name);
    assertEquals(QUERY.type, query.type);
    assertEquals(QUERY.requestId, query.requestId);
    assertEquals(clientSocket.getLocalAddress(), query.sourceAddress);
    assertEquals((short)clientSocket.getLocalPort(), query.sourcePort);
    assertEquals(resolver.getAddress().getAddress(), query.destAddress);
    assertEquals((short)resolver.getAddress().getPort(), query.destPort);

    assertArrayEquals(QUERY_DATA, dataCaptor.getValue());
  }

  @Test
  public void success() throws Exception {
    sendQuery();

    // Send a mock response.
    Call mockCall = mock(Call.class);
    // Echo the query as a fake success response.
    Request fakeRequest = (new Request.Builder()).url("https://example/").build();
    ResponseBody fakeBody =
        ResponseBody.create(MediaType.get("application/dns-message"), QUERY_DATA);
    Response fakeResponse = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .code(200)
        .message("OK")
        .addHeader(IpTagInterceptor.HEADER_NAME, SERVER_IP)
        .body(fakeBody)
        .build();
    callbackCaptor.getValue().onResponse(mockCall, fakeResponse);

    // Receive the response
    byte[] responseBuffer = new byte[4096];
    DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
    clientSocket.receive(responsePacket);

    // Check that the response is just the echo of the query.
    assertArrayEquals(QUERY_DATA, Arrays.copyOfRange(responsePacket.getData(),
        responsePacket.getOffset(),
        responsePacket.getLength()));

    // Check that the transaction was correctly recorded in the VPN service.
    verify(mockVpn).recordTransaction(transactionCaptor.capture());

    Transaction transaction = transactionCaptor.getValue();
    assertEquals(QUERY.name, transaction.name);
    assertEquals(QUERY.type, transaction.type);
    assertEquals(QUERY.timestamp, transaction.queryTime);
    assertEquals(SERVER_IP, transaction.serverIp);
    assertEquals(Transaction.Status.COMPLETE, transaction.status);
    assertArrayEquals(QUERY_DATA, transaction.response);
  }

  @Test
  public void error404() throws Exception {
    sendQuery();

    // Send a mock response.
    Call mockCall = mock(Call.class);
    // Simulate a 404 with an empty response body.
    Request fakeRequest = (new Request.Builder()).url("https://example/").build();
    ResponseBody fakeBody =
        ResponseBody.create(MediaType.get("text/html"), new byte[0]);
    Response fakeResponse = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .code(404)
        .message("Not Found")
        .addHeader(IpTagInterceptor.HEADER_NAME, SERVER_IP)
        .body(fakeBody)
        .build();
    callbackCaptor.getValue().onResponse(mockCall, fakeResponse);

    // Check that the transaction was correctly recorded in the VPN service.
    verify(mockVpn).recordTransaction(transactionCaptor.capture());

    Transaction transaction = transactionCaptor.getValue();
    assertEquals(QUERY.name, transaction.name);
    assertEquals(QUERY.type, transaction.type);
    assertEquals(QUERY.timestamp, transaction.queryTime);
    assertEquals(SERVER_IP, transaction.serverIp);
    assertEquals(Transaction.Status.HTTP_ERROR, transaction.status);
    assertNull(transaction.response);
  }

  @Test
  public void sendFailed() throws Exception {
    sendQuery();

    // Send a mock failure response.
    Call mockCall = mock(Call.class);
    when(mockCall.isCanceled()).thenReturn(false);
    callbackCaptor.getValue().onFailure(mockCall, new IOException("foo"));

    // Check that the failed transaction was correctly recorded in the VPN service.
    verify(mockVpn).recordTransaction(transactionCaptor.capture());

    Transaction transaction = transactionCaptor.getValue();
    DnsUdpQuery query = DnsUdpQuery.fromUdpBody(QUERY_DATA);
    assertEquals(query.name, transaction.name);
    assertEquals(query.type, transaction.type);
    assertNull(transaction.serverIp);
    assertEquals(Transaction.Status.SEND_FAIL, transaction.status);
    assertNull(transaction.response);
  }

  @Test
  public void canceled() throws Exception {
    sendQuery();

    // Simulate a canceled request.
    Call mockCall = mock(Call.class);
    when(mockCall.isCanceled()).thenReturn(true);
    callbackCaptor.getValue().onFailure(mockCall, new IOException("foo"));

    // Check that the failed transaction was correctly recorded in the VPN service.
    verify(mockVpn).recordTransaction(transactionCaptor.capture());

    Transaction transaction = transactionCaptor.getValue();
    DnsUdpQuery query = DnsUdpQuery.fromUdpBody(QUERY_DATA);
    assertEquals(query.name, transaction.name);
    assertEquals(query.type, transaction.type);
    assertNull(transaction.serverIp);
    assertEquals(Transaction.Status.CANCELED, transaction.status);
    assertNull(transaction.response);
  }

  @Test
  public void nullConnection() throws Exception {
    // Set the serverConnection to null.  This is allowed, and should cause queries to fail.
    when(mockVpn.getServerConnection()).thenReturn(null);

    DatagramPacket queryPacket = new DatagramPacket(QUERY_DATA, QUERY_DATA.length);
    queryPacket.setSocketAddress(resolver.getAddress());

    // Make a request.
    clientSocket.send(queryPacket);

    // Check that the transaction failed and was correctly recorded in the VPN service.
    verify(mockVpn, timeout(1000)).recordTransaction(transactionCaptor.capture());

    Transaction transaction = transactionCaptor.getValue();
    DnsUdpQuery query = DnsUdpQuery.fromUdpBody(QUERY_DATA);
    assertEquals(query.name, transaction.name);
    assertEquals(query.type, transaction.type);
    assertNull(transaction.serverIp);
    assertEquals(Transaction.Status.SEND_FAIL, transaction.status);
    assertNull(transaction.response);
  }

  @Test
  public void badPacket() throws Exception {
    // Send a single-byte packet.  This is not a valid DNS query, and LocalhostResolver should
    // ignore it.
    DatagramPacket queryPacket = new DatagramPacket(QUERY_DATA, 1);
    queryPacket.setSocketAddress(resolver.getAddress());
    clientSocket.send(queryPacket);

    // Now run a correct query, to verify that the resolver is still working.
    success();
  }

  @Test
  public void sequentialQueries() throws Exception {
    // Try three queries in a row.
    success();
    reset(mockConnection);
    reset(mockVpn);

    when(mockVpn.getServerConnection()).thenReturn(mockConnection);
    success();
    reset(mockConnection);
    reset(mockVpn);

    when(mockVpn.getServerConnection()).thenReturn(mockConnection);
    success();
  }
}
