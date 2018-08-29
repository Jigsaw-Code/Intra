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
package app.intra;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

// This class listens for network connectivity changes and notifies a NetworkListener of
// connected/disconnected events.
public class NetworkManager {

  private static final String LOG_TAG = "NetworkManager";
  private static final String EXTRA_NETWORK_INFO = "networkInfo";

  public interface NetworkListener {

    void onNetworkConnected(NetworkInfo networkInfo);

    void onNetworkDisconnected();
  }

  private final ConnectivityManager connectivityManager;
  private BroadcastReceiver broadcastReceiver = null;
  private ConnectivityManager.NetworkCallback networkCallback = null;
  private final Context applicationContext;
  private final NetworkListener networkListener;

  public NetworkManager(Context context, final NetworkListener networkListener) {
    applicationContext = context.getApplicationContext();
    connectivityManager =
        (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.networkListener = networkListener;

    // We listen for CONNECTIVITY_ACTION to monitor network disconnection events.
    // On pre-Lollipop devices, it's also used to monitor network connection events.
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

    // NetworkRequest only exists in Lollipop and later.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // We only want to know about internet-connected networks that are not VPNs.
      NetworkRequest.Builder builder = (new NetworkRequest.Builder())
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        // If the device is checking the network for internet access, we don't want to know about
        // the network until after it's validated.  This is important because, empirically, if the
        // Intra VPN captures the IP of the network's DNS servers before validation completes,
        // validation will fail.
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      }

      networkCallback = new ConnectivityManager.NetworkCallback() {
        // This method is called whenever a network is validated, and subsequently whenever its DNS
        // servers change.  (Some networks initially come online with a subset of their DNS servers
        // and later experience a configuration change to add more DNS servers.)
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
          networkChanged(network);
        }
      };
      connectivityManager.registerNetworkCallback(builder.build(), networkCallback);
    }

    // Fire onNetworkConnected listener immediately if we are online.
    NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
    if (networkInfo != null && networkInfo.isConnected()) {
      this.networkListener.onNetworkConnected(networkInfo);
    }
  }

  // Destroys the network receiver.
  public void destroy() {
    try {
      if (broadcastReceiver != null) {
        applicationContext.unregisterReceiver(broadcastReceiver);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && networkCallback != null) {
        connectivityManager.unregisterNetworkCallback(networkCallback);
      }
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
    } else if (networkCallback == null &&
        activeNetworkInfo.getType() != ConnectivityManager.TYPE_VPN) {
      // We have an active network that is not a VPN, and this is a pre-Lollipop device so
      // connectivityChanged() is responsible for surfacing network connection events.
      networkListener.onNetworkConnected(activeNetworkInfo);
    }
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void networkChanged(Network network) {
    Log.v(LOG_TAG, "NETWORK CHANGED " + network);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // getActiveNetwork() requires M or later.
      if (!network.equals(connectivityManager.getActiveNetwork())) {
        Log.v(LOG_TAG, "Skipping network that is not the active network.");
        return;
      }
    }
    // There is a new active network, or the active network's DNS servers may have changed.
    // onNetworkConnected is idempotent, so it's safe to call it whenever we are in that state
    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
    if (isConnectedNetwork(activeNetworkInfo) &&
        activeNetworkInfo.getType() != ConnectivityManager.TYPE_VPN) {
      networkListener.onNetworkConnected(activeNetworkInfo);
    }
  }

  // Returns true if the supplied network is connected and available
  private static boolean isConnectedNetwork(NetworkInfo networkInfo) {
    if (networkInfo == null) {
      return false;
    }
    return networkInfo.isConnected() && networkInfo.isAvailable();
  }
}
