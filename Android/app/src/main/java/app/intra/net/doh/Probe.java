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

import androidx.annotation.NonNull;
import app.intra.net.dns.DnsUdpQuery;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Response;

/**
 * Implements an asynchronous check to determine whether a DOH server is working.  Each instance can
 * only be used once.
 */
class Probe extends Thread {
  private static final byte[] QUERY_DATA = {
      0, 0,  // [0-1]   query ID
      1, 0,  // [2-3]   flags, RD=1
      0, 1,  // [4-5]   QDCOUNT (number of queries) = 1
      0, 0,  // [6-7]   ANCOUNT (number of answers) = 0
      0, 0,  // [8-9]   NSCOUNT (number of authoritative answers) = 0
      0, 0,  // [10-11] ARCOUNT (number of additional records) = 0
      // Start of first query
      7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
      3, 'c', 'o', 'm',
      0,  // null terminator of FQDN (DNS root)
      0, 1,  // QTYPE = A
      0, 1   // QCLASS = IN (Internet)
  };

  private static final DnsUdpQuery QUERY = DnsUdpQuery.fromUdpBody(QUERY_DATA);

  enum Status { NEW, RUNNING, SUCCEEDED, FAILED }
  private Status status = Status.NEW;

  interface Callback {
    void onSuccess();
    void onFailure();
  }

  private final ServerConnectionFactory factory;
  private final String url;
  private final Callback callback;

  /**
   * Creates a Probe.  Call start() to run the probe asynchronously.
   * @param factory This factory is used exactly once, to connect to the specified URL.
   * @param url The URL of the DOH server.
   * @param callback A callback indicating whether the connection succeeded or failed.  Runs on an
   *   arbitrary thread.
   */
  Probe(ServerConnectionFactory factory, String url, Callback callback) {
    this.factory = factory;
    this.url = url;
    this.callback = callback;
  }

  synchronized Status getStatus() {
    return status;
  }

  private synchronized void setStatus(Status s) {
    status = s;
  }

  private class QueryCallback implements okhttp3.Callback {
    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
      setStatus(Status.FAILED);
      callback.onFailure();
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
      setStatus(Status.SUCCEEDED);
      callback.onSuccess();
    }
  }

  @Override
  public void run() {
    setStatus(Status.RUNNING);
    ServerConnection conn = factory.get(url);
    if (isInterrupted() || conn == null) {
      setStatus(Status.FAILED);
      callback.onFailure();
      return;
    }
    conn.performDnsRequest(QUERY, QUERY_DATA, new QueryCallback());
  }
}
