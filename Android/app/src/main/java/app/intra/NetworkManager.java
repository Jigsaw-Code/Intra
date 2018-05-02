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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

// This class listens for network connectivity changes and notifies a NetworkListener of
// connected/disconnected events.
public class NetworkManager {

  private static final String LOG_TAG = "NetworkManager";
  private static final String EXTRA_NETWORK_INFO = "networkInfo";

  public interface NetworkListener {

    public void onNetworkConnected(NetworkInfo networkInfo);

    public void onNetworkDisconnected();
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
  public void destroy() {
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
}
