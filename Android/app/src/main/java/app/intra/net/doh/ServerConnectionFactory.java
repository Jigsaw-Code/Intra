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

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;
import app.intra.R;
import app.intra.net.doh.google.GoogleServerConnection;
import app.intra.net.doh.google.GoogleServerDatabase;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Factory for ServerConnections.  Used by IntraVpnService and Probe.
 */
public class ServerConnectionFactory {
  private static final String LOG_TAG = "ServerConnectionFactory";

  private final Context context;
  public ServerConnectionFactory(Context context) {
    this.context = context;
  }

  /**
   * @return True if these URLs represents the same server.
   */
  public static boolean equalUrls(String url1, String url2) {
    // null and "" are equivalent, representing the default server.
    if (url1 == null) {
      url1 = "";
    }
    if (url2 == null) {
      url2 = "";
    }
    return url1.equals(url2);
  }

  private Collection<InetAddress> getKnownIps(String url) {
    Resources res = context.getResources();
    String[] urls = res.getStringArray(R.array.urls);
    String[] ips = res.getStringArray(R.array.ips);
    for (int i = 0; i < urls.length; ++i) {
      // TODO: Consider relaxing this equality condition to a match on just the domain.
      if (urls[i].equals(url)) {
        String[] ipStrings = ips[i].split(",");
        List<InetAddress> ret = new ArrayList<>();
        for (String ip : ipStrings) {
          try {
            ret.addAll(Arrays.asList(InetAddress.getAllByName(ip)));
          } catch (IOException e) {
            Log.e(LOG_TAG, "Invalid IP address in servers resource");
          }
        }
        return ret;
      }
    }
    return new ArrayList<>();
  }

  public ServerConnection get(String url) {
    if (equalUrls(url, null)) {
      // Use the Google Resolver
      AssetManager assets = context.getAssets();
      return GoogleServerConnection.get(new GoogleServerDatabase(context, assets), null);
    }

    return StandardServerConnection.get(url, getKnownIps(url));
  }
}

