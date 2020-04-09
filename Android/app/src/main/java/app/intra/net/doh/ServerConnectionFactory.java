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
import android.content.res.Resources;
import android.util.Log;
import app.intra.R;
import app.intra.sys.PersistentState;
import app.intra.sys.firebase.LogWrapper;
import app.intra.sys.firebase.RemoteConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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
  public static boolean equalUrls(Context context, String url1, String url2) {
    // Convert null and "" into "https://dns.google/dns-query", which is the default URL.
    url1 = PersistentState.expandUrl(context, url1);
    url2 = PersistentState.expandUrl(context, url2);
    return url1.equals(url2);
  }

  public static String getIpString(Context context, String url) {
    Resources res = context.getResources();
    String[] urls = res.getStringArray(R.array.urls);
    String[] ips = res.getStringArray(R.array.ips);
    String ret = "";
    for (int i = 0; i < urls.length; ++i) {
      // TODO: Consider relaxing this equality condition to a match on just the domain.
      if (urls[i].equals(url)) {
        ret = ips[i];
        break;
      }
    }

    try {
      String domain = new URL(url).getHost();
      String extraIPs = RemoteConfig.getExtraIPs(domain);
      if (ret.isEmpty()) {
        ret = extraIPs;
      } else if (!extraIPs.isEmpty()) {
        ret += "," + extraIPs;
      }
    } catch (MalformedURLException e) {
      LogWrapper.logException(e);
    }

    return ret;
  }

  private Collection<InetAddress> getKnownIps(String url) {
    Set<InetAddress> ret = new HashSet<>();
    for (String ip : getIpString(context, url).split(",")) {
      if (ip.isEmpty()) {
        continue;
      }
      try {
        ret.addAll(Arrays.asList(InetAddress.getAllByName(ip)));
      } catch (IOException e) {
        Log.e(LOG_TAG, "Invalid IP address in servers resource");
      }
    }
    return ret;
  }

  public ServerConnection get(String url) {
    return StandardServerConnection.get(PersistentState.expandUrl(context, url), getKnownIps(url));
  }
}

