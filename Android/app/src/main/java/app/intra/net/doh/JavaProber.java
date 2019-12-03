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
 * Implements a Prober using the OkHttp-based DoH client.
 */
class JavaProber extends Prober {

  private final ServerConnectionFactory factory;
  JavaProber(ServerConnectionFactory factory) {
    this.factory = factory;
  }

  private class QueryCallback implements okhttp3.Callback {
    private Callback callback;
    QueryCallback(Callback callback) {
      this.callback = callback;
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
      callback.onCompleted(false);
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
      callback.onCompleted(true);
    }
  }

  @Override
  public void probe(String url, Callback callback) {
    new Thread(() -> {
      // Factory.get() is a slow, synchronous call, so it has to run in a new thread.
      ServerConnection conn = factory.get(url);
      if (conn == null) {
        callback.onCompleted(false);
        return;
      }
      conn.performDnsRequest(QUERY_DATA, new QueryCallback(callback));
    }).start();
  }
}
