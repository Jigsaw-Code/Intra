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
package app.intra.net.go;

import android.content.Context;
import app.intra.net.doh.Probe;
import app.intra.net.doh.ServerConnectionFactory;
import doh.Transport;
import tun2socks.Tun2socks;

/**
 * Implements a Probe using the Go-based DoH client.
 */
public class GoProbe extends Probe {
  private final Context context;
  private final String url;

  public GoProbe(Context context, String url, Callback callback) {
    this.context = context;
    this.callback = callback;
    this.url = url;
  }

  @Override
  public void run() {
    String dohIPs = ServerConnectionFactory.getIpString(context, url);
    if (isInterrupted()) {
      fail();
      return;
    }
    try {
      Transport transport = Tun2socks.newDoHTransport(url, dohIPs, null);
      if (transport == null || isInterrupted()) {
        fail();
        return;
      }
      byte[] response = transport.query(QUERY_DATA);
      if (response != null && response.length > 0) {
        succeed();
        return;
      }
      fail();
    } catch (Exception e) {
      fail();
    }
  }
}
