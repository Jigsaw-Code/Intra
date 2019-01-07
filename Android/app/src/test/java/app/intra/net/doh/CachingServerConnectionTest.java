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
package app.intra.net.doh;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.intra.net.dns.DnsPacket;
import app.intra.net.doh.CachingServerConnection.Status;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

public class CachingServerConnectionTest {

  private CachingServerConnection testConnection;
  private ServerConnection mockBackend;

  @Captor
  private ArgumentCaptor<DnsPacket> queryCaptor;
  @Captor
  private ArgumentCaptor<Callback> callbackCaptor;
  @Captor
  private ArgumentCaptor<Response> responseCaptor;

  private static final byte[] QUERY_DATA = {
      -107, -6,  // [0-1]   query ID
      1, 0,      // [2-3]   flags, RD=1
      0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
      0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
      0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
      0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
      // Start of first question
      7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
      3, 'c', 'o', 'm',
      0,  // null terminator of FQDN (DNS root)
      0, 1,  // QTYPE = A
      0, 1   // QCLASS = IN (Internet)
  };

  private static final byte[] RESPONSE_DATA = {
      0, 0,    // [0-1]   query ID
      -127, -128,  // [2-3]   flags: RD=1, QR=1, RA=1
      0, 1,        // [4-5]   QDCOUNT (number of queries) = 1
      0, 1,        // [6-7]   ANCOUNT (number of answers) = 2
      0, 0,        // [8-9]   NSCOUNT (number of authoritative answers) = 0
      0, 0,        // [10-11] ARCOUNT (number of additional records) = 0
      // First question
      7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
      3, 'c', 'o', 'm',
      0,  // null terminator of FQDN (DNS root)
      0, 1,  // QTYPE = A
      0, 1,  // QCLASS = IN (Internet)
      // First answer
      -64, 12,  // Compressed name reference, starting at byte 12: google.com
      0, 1,         // QTYPE = A
      0, 1,         // QCLASS = IN (Internet)
      0, 0, 0, -8,  // TTL = 248
      0, 4,         // RDLEN = 4
      -83, -62, -52, -68   // 173.194.204.188
  };

  private DnsPacket query;

  @Before
  public void init() {
    MockitoAnnotations.initMocks(this);
  }

  @Before
  public void setUp() throws Exception {
    mockBackend = mock(ServerConnection.class);
    testConnection = new CachingServerConnection(mockBackend, 256);
    query = new DnsPacket(QUERY_DATA);
  }

  @Test
  public void basic() throws IOException {
    Callback mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);

    // Expect a call to performDnsRequest
    verify(mockBackend, timeout(1000)).
        performDnsRequest(queryCaptor.capture(), callbackCaptor.capture());

    Call mockCall = mock(Call.class);
    Request fakeRequest = (new Request.Builder()).url("https://example/").build();
    ResponseBody fakeBody =
        ResponseBody.create(MediaType.get("application/dns-message"), RESPONSE_DATA);
    Response fakeResponse = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .code(200)
        .message("OK")
        .body(fakeBody)
        .build();

    callbackCaptor.getValue().onResponse(mockCall, fakeResponse);

    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output = responseCaptor.getValue();
    assertEquals(fakeResponse.request(), output.request());
    assertEquals(fakeResponse.protocol(), output.protocol());
    assertEquals(fakeResponse.code(), output.code());
    assertEquals(fakeResponse.message(), output.message());
    assertArrayEquals(RESPONSE_DATA, output.body().bytes());
    assertEquals(Status.MISS.id, output.header(CachingServerConnection.STATUS_HEADER));

    // Run the same query again
    mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);
    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output2 = responseCaptor.getAllValues().get(1);
    assertEquals(fakeResponse.request(), output2.request());
    assertEquals(fakeResponse.protocol(), output2.protocol());
    assertEquals(fakeResponse.code(), output2.code());
    assertEquals(fakeResponse.message(), output2.message());
    assertArrayEquals(RESPONSE_DATA, output2.body().bytes());
    assertEquals(Status.HIT.id, output2.header(CachingServerConnection.STATUS_HEADER));
  }

  @Test
  public void slightlyAged() throws IOException {
    Callback mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);

    // Expect a call to performDnsRequest
    verify(mockBackend, timeout(1000)).
        performDnsRequest(queryCaptor.capture(), callbackCaptor.capture());

    Call mockCall = mock(Call.class);
    Request fakeRequest = (new Request.Builder()).url("https://example/").build();
    ResponseBody fakeBody =
        ResponseBody.create(MediaType.get("application/dns-message"), RESPONSE_DATA);
    Response fakeResponse = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .header("Age", "100")
        .code(200)
        .message("OK")
        .body(fakeBody)
        .build();

    callbackCaptor.getValue().onResponse(mockCall, fakeResponse);

    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output = responseCaptor.getValue();
    assertEquals(fakeResponse.request(), output.request());
    assertEquals(fakeResponse.protocol(), output.protocol());
    assertEquals(fakeResponse.code(), output.code());
    assertEquals(fakeResponse.message(), output.message());
    assertArrayEquals(RESPONSE_DATA, output.body().bytes());
    assertEquals(Status.MISS.id, output.header(CachingServerConnection.STATUS_HEADER));

    // Run the same query again.  It should be a cache hit because there is still plenty of
    // TTL left (248 - 100).
    mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);
    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output2 = responseCaptor.getAllValues().get(1);
    assertEquals(fakeResponse.request(), output2.request());
    assertEquals(fakeResponse.protocol(), output2.protocol());
    assertEquals(fakeResponse.code(), output2.code());
    assertEquals(fakeResponse.message(), output2.message());
    assertArrayEquals(RESPONSE_DATA, output2.body().bytes());
    assertEquals(Status.HIT.id, output2.header(CachingServerConnection.STATUS_HEADER));
  }

  @Test
  public void tooAged() throws IOException {
    Callback mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);

    // Expect a call to performDnsRequest
    InOrder inOrder = inOrder(mockBackend);
    inOrder.verify(mockBackend, timeout(1000)).
        performDnsRequest(queryCaptor.capture(), callbackCaptor.capture());

    Call mockCall = mock(Call.class);
    Request fakeRequest = (new Request.Builder()).url("https://example/").build();
    ResponseBody fakeBody =
        ResponseBody.create(MediaType.get("application/dns-message"), RESPONSE_DATA);
    Response fakeResponse = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .header("Age", "300")
        .header("Fake-Request", "1")
        .code(200)
        .message("OK")
        .body(fakeBody)
        .build();

    callbackCaptor.getValue().onResponse(mockCall, fakeResponse);

    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output = responseCaptor.getValue();
    assertEquals("1", output.header("Fake-Request"));
    assertEquals(fakeResponse.request(), output.request());
    assertEquals(fakeResponse.protocol(), output.protocol());
    assertEquals(fakeResponse.code(), output.code());
    assertEquals(fakeResponse.message(), output.message());
    assertArrayEquals(RESPONSE_DATA, output.body().bytes());
    assertEquals(Status.MISS.id, output.header(CachingServerConnection.STATUS_HEADER));

    // Run the same query again.  The lookup should miss the cache, because the Age
    // header exceeded the TTL, making the response uncacheable.
    mockCallback = mock(Callback.class);
    testConnection.performDnsRequest(query, mockCallback);

    ResponseBody fakeBody2 =
        ResponseBody.create(MediaType.get("application/dns-message"), RESPONSE_DATA);
    Response fakeResponse2 = (new Response.Builder())
        .request(fakeRequest)
        .protocol(Protocol.HTTP_2)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .header("Age", "300")
        .header("Fake-Request", "2")
        .code(200)
        .message("OK")
        .body(fakeBody2)
        .build();

    // Expect a call to performDnsRequest
    inOrder.verify(mockBackend, timeout(1000)).
        performDnsRequest(queryCaptor.capture(), callbackCaptor.capture());
    callbackCaptor.getAllValues().get(1).onResponse(mockCall, fakeResponse2);
    verify(mockCallback, timeout(1000).times(1)).onResponse(eq(mockCall), responseCaptor.capture());
    Response output2 = responseCaptor.getAllValues().get(1);
    assertEquals("2", output2.header("Fake-Request"));
    assertEquals(fakeResponse2.request(), output2.request());
    assertEquals(fakeResponse2.protocol(), output2.protocol());
    assertEquals(fakeResponse2.code(), output2.code());
    assertEquals(fakeResponse2.message(), output2.message());
    assertArrayEquals(RESPONSE_DATA, output2.body().bytes());
    assertEquals(Status.MISS.id, output2.header(CachingServerConnection.STATUS_HEADER));
  }

  @Test
  public void getUrl() {
    String test = "test";
    when(mockBackend.getUrl()).thenReturn(test);
    assertEquals(test, testConnection.getUrl());
  }

  @Test
  public void reset() {
    testConnection.reset();
    verify(mockBackend).reset();
  }
}