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

public class JavaProberTest {
  private static final String URL = "foo";
  private ServerConnectionFactory mockFactory;
  private Semaphore done = new Semaphore(0);

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
    ServerConnection mockConn = mock(ServerConnection.class);
    when(mockFactory.get(URL)).then((InvocationOnMock invocation) -> {
      done.release();
      return mockConn;
    });

    doAnswer((InvocationOnMock invocation) -> {
      done.release();
      return null;
    }).when(mockConn).performDnsRequest(
        dataCaptor.capture(),
        callbackCaptor.capture());

    Prober prober = new JavaProber(mockFactory);
    prober.probe(URL, succeeded -> {
      if (!succeeded) {
        fail();
      }
      done.release();
    });
    // Wait for call to ServerConnectionFactory.get()
    done.acquire();
    // Wait for call to ServerConnection.performDnsRequest()
    done.acquire();
    // Simulate query success.
    callbackCaptor.getValue().onResponse(null, null);
    // Wait for success callback.
    done.acquire();
  }

  @Test
  public void QueryFailed() throws Exception {
    ServerConnection mockConn = mock(ServerConnection.class);
    when(mockFactory.get(URL)).then((InvocationOnMock invocation) -> {
      done.release();
      return mockConn;
    });

    doAnswer((InvocationOnMock invocation) -> {
      done.release();
      return null;
    }).when(mockConn).performDnsRequest(
        dataCaptor.capture(),
        callbackCaptor.capture());

    Prober prober = new JavaProber(mockFactory);
    prober.probe(URL, succeeded -> {
      if (succeeded) {
        fail();
      }
      done.release();
    });
    // Wait for call to ServerConnectionFactory.get()
    done.acquire();
    // Wait for call to ServerConnection.performDnsRequest()
    done.acquire();
    // Simulate query failure.
    callbackCaptor.getValue().onFailure(null, null);
    // Wait for failure callback.
    done.acquire();
  }

  @Test
  public void FactoryFailed() throws Exception {
    when(mockFactory.get(URL)).then((InvocationOnMock invocation) -> {
      return null;  // Indicates bootstrap failure.
    });

    Prober prober = new JavaProber(mockFactory);
    prober.probe(URL, succeeded -> {
      if (succeeded) {
        fail();
      }
      done.release();
    });
    // Wait for failure callback.
    done.acquire();
  }

}

