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
 * Implements a Probe using the OkHttp-based DoH client.
 */
class JavaProbe extends Probe {

  private static final DnsUdpQuery QUERY = DnsUdpQuery.fromUdpBody(QUERY_DATA);

  static Probe.Factory factory = (context, url, callback) ->
      new JavaProbe(new ServerConnectionFactory(context), url, callback);

  private final ServerConnectionFactory serverConnectionFactory;
  private final String url;

  /**
   * Creates a Probe.  Call start() to run the probe asynchronously.
   * @param serverConnectionFactory This factory is used to connect to the specified URL.
   * @param url The URL of the DOH server.
   * @param callback A callback indicating whether the connection succeeded or failed.  Runs on an
   *   arbitrary thread.
   */
  JavaProbe(ServerConnectionFactory serverConnectionFactory, String url, Callback callback) {
    this.serverConnectionFactory = serverConnectionFactory;
    this.url = url;
    this.callback = callback;
  }

  private class QueryCallback implements okhttp3.Callback {
    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
      fail();
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
      succeed();
    }
  }

  @Override
  protected void execute() {
    ServerConnection conn = serverConnectionFactory.get(url);
    if (Thread.interrupted() || conn == null) {
      fail();
      return;
    }
    conn.performDnsRequest(QUERY, QUERY_DATA, new QueryCallback());
  }
}
