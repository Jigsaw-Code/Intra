/*
Copyright 2019 Jigsaw Operations LLC

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

import static org.junit.Assert.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.intra.net.dns.DnsUdpQuery;
import java.util.concurrent.Semaphore;
import okhttp3.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

public class RaceTest {
  private ServerConnectionFactory mockFactory;

  @Captor
  private ArgumentCaptor<DnsUdpQuery> queryCaptor;
  @Captor
  private ArgumentCaptor<byte[]> dataCaptor;
  @Captor
  private ArgumentCaptor<Callback> callbackCaptor;

  @Before
  public void init(){
    MockitoAnnotations.initMocks(this);
  }

  @Before
  public void setUp() throws Exception {
    mockFactory = mock(ServerConnectionFactory.class);
  }

  @After
  public void tearDown() throws Exception {
    mockFactory = null;
  }

  @Test
  public void Success() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    ServerConnection[] connections = new ServerConnection[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("server%d", i);
      connections[i] = mock(ServerConnection.class);
      when(mockFactory.get(urls[i])).thenReturn(connections[i]);
      doAnswer((InvocationOnMock invocation) -> {
        callbackCaptor.getValue().onResponse(null, null);
        return null;
      }).when(connections[i]).performDnsRequest(
          queryCaptor.capture(),
          dataCaptor.capture(),
          callbackCaptor.capture());
    }
    Semaphore done = new Semaphore(0);
    Race race = new Race(mockFactory, urls, (int index) -> {
      assertTrue(index >= 0);
      assertTrue(index < N);
      done.release();
      if (done.availablePermits() > 1) {
        // Multiple success callbacks.
        fail();
      }
    });
    race.start();
    // Wait for listener to run.
    done.acquire();
  }

  @Test
  public void AllFail() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("server%d", i);
      when(mockFactory.get(urls[i])).thenReturn(null);
    }
    Semaphore done = new Semaphore(0);
    Race race = new Race(mockFactory, urls, (int index) -> {
      assertEquals(-1, index);
      done.release();
    });
    race.start();
    done.acquire();
  }

  @Test
  public void HalfFail() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    ServerConnection[] connections = new ServerConnection[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("server%d", i);
      connections[i] = mock(ServerConnection.class);
      if (i % 2 == 0) {
        // Even-number servers succeed.
        when(mockFactory.get(urls[i])).thenReturn(connections[i]);
        doAnswer((InvocationOnMock invocation) -> {
          callbackCaptor.getValue().onResponse(null, null);
          return null;
        }).when(connections[i]).performDnsRequest(
            queryCaptor.capture(),
            dataCaptor.capture(),
            callbackCaptor.capture());
      } else {
        // Odd-number servers fail.
        when(mockFactory.get(urls[i])).thenReturn(null);
      }
    }
    Semaphore done = new Semaphore(0);
    Race race = new Race(mockFactory, urls, (int index) -> {
      assertTrue(index >= 0);
      assertTrue(index < N);
      // Only the even-numbered servers succeeded.
      assertEquals(0, index % 2);
      done.release();
    });
    race.start();
    done.acquire();
  }

}

