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
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import app.intra.net.doh.Prober;
import app.intra.sys.VpnController;
import backend.Backend;
import backend.DoHServer;
import protect.Protector;

/**
 * Implements a Probe using the Go-based DoH client.
 */
public class GoProber extends Prober {

  private final Context context;

  public GoProber(Context context) {
    this.context = context;
  }

  @Override
  public void probe(String url, Callback callback) {
    new Thread(() -> {
      String dohIPs = GoVpnAdapter.getIpString(context, url);
      try {
        // Protection isn't needed for Lollipop+, or if the VPN is not active.
        Protector protector = VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP ? null :
            VpnController.getInstance().getIntraVpnService();
        Backend.probe(new DoHServer(url, dohIPs, protector, null));
        callback.onCompleted(true);
      } catch (Exception e) {
        callback.onCompleted(false);
      }
    }).start();
  }
}
