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
package app.intra.sys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import app.intra.sys.firebase.LogWrapper;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// This class listens for network connectivity changes and notifies a NetworkListener of
// connected/disconnected events.
public class NetworkManager {

  private static final String LOG_TAG = "NetworkManager";
  private static final String EXTRA_NETWORK_INFO = "networkInfo";

  public interface NetworkListener {

    void onNetworkConnected(NetworkInfo networkInfo);

    void onNetworkDisconnected();
  }

  private ConnectivityManager connectivityManager;
  private BroadcastReceiver broadcastReceiver;
  private Context applicationContext;
  private NetworkListener networkListener;

  public NetworkManager(Context context, NetworkListener networkListener) {
    applicationContext = context.getApplicationContext();
    connectivityManager =
        (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.networkListener = networkListener;

    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
    broadcastReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            connectivityChanged(intent);
          }
        };
    applicationContext.registerReceiver(this.broadcastReceiver, intentFilter);

    // Fire onNetworkConnected listener immediately if we are online.
    NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
    if (networkInfo != null && networkInfo.isConnected()) {
      this.networkListener.onNetworkConnected(networkInfo);
    }
  }

  // Destroys the network receiver.
  void destroy() {
    if (broadcastReceiver == null) {
      return;
    }
    try {
      applicationContext.unregisterReceiver(broadcastReceiver);
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error unregistering network receiver: " + e.getMessage(), e);
    } finally {
      broadcastReceiver = null;
    }
  }

  // Handles changes in connectivity by binding the process to the latest
  // connected network.
  private void connectivityChanged(Intent intent) {
    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
    NetworkInfo intentNetworkInfo = intent.getParcelableExtra(EXTRA_NETWORK_INFO);

    Log.v(LOG_TAG, "ACTIVE NETWORK " + activeNetworkInfo);
    Log.v(LOG_TAG, "INTENT NETWORK " + intentNetworkInfo);
    if (networkListener == null) {
      return;
    }

    if (isConnectedNetwork(activeNetworkInfo)
        && intentNetworkInfo != null
        && intentNetworkInfo.getType() == ConnectivityManager.TYPE_VPN) {
      // VPN state changed, we have connectivity, ignore.
      return;
    } else if (!isConnectedNetwork(activeNetworkInfo)) {
      // No active network, signal disconnect event.
      networkListener.onNetworkDisconnected();
    } else if (activeNetworkInfo.getType() != ConnectivityManager.TYPE_VPN) {
      // We have an active network, make sure it is not a VPN to signal a connected event.
      networkListener.onNetworkConnected(activeNetworkInfo);
    }
  }

  // Returns true if the supplied network is connected and available
  private static boolean isConnectedNetwork(NetworkInfo networkInfo) {
    if (networkInfo == null) {
      return false;
    }
    return networkInfo.isConnectedOrConnecting() && networkInfo.isAvailable();
  }

  /**
   * Post-Lollipop, we can use VpnService.Builder.addDisallowedApplication to exclude Intra from its
   * own VPN.  If for some reason we needed to get the IP address of the non-VPN resolvers, we could
   * use ConnectivityManager.getActiveNetwork().getLinkProperties().getDnsServers().
   *
   * Pre-Lollipop, there is no official way to get the non-VPN DNS servers, but we need them in
   * order to do DNS lookups on protected sockets in Go, in order to resolve DNS server names when
   * the current server connection is broken, without disabling the VPN.  This implementation
   * exposes the hidden version of LinkProperties that was present prior to Lollipop, in order to
   * extract the DNS server IPs.
   *
   * @return The (unencrypted) DNS servers used by the underlying networks.
   */
  List<InetAddress> getSystemResolvers() {
    // This list of network types is in roughly descending priority order, so that the first
    // entries in the returned list are most likely to be the appropriate resolvers.
    int[] networkTypes = new int[]{
        ConnectivityManager.TYPE_ETHERNET,
        ConnectivityManager.TYPE_WIFI,
        ConnectivityManager.TYPE_MOBILE,
        ConnectivityManager.TYPE_WIMAX,
    };

    List<InetAddress> resolvers = new ArrayList<>();
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      LogWrapper.log(Log.ASSERT, LOG_TAG, "This function should never be called in L+.");
      return resolvers;
    }

    try {
      // LinkProperties ConnectivityManager.getLinkProperties(int type) (removed in Lollipop)
      Method getLinkProperties = ConnectivityManager.class
          .getMethod("getLinkProperties", int.class);
      // LinkProperties, which existed before Lollipop but had a different API and was not exposed.
      Class<?> linkPropertiesClass = Class.forName("android.net.LinkProperties");
      // Collection<InetAddress> LinkProperties.getDnses() (replaced by getDnsServers in Lollipop).
      Method getDnses = linkPropertiesClass.getMethod("getDnses");

      for (int networkType : networkTypes) {
        Object linkProperties = getLinkProperties.invoke(connectivityManager, networkType);
        if (linkProperties == null) {
          // No network of this type.
          continue;
        }
        Collection<?> addresses = (Collection<?>) getDnses.invoke(linkProperties);
        for (Object address : addresses) {
          resolvers.add((InetAddress)address);
        }
      }
    } catch (Exception e) {
      LogWrapper.logException(e);
    }

    return resolvers;
  }
}
