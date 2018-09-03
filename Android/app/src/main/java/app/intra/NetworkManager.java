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

import com.google.firebase.crash.FirebaseCrash;

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

    void onNetworkConnected();

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

      // TODO: Consider requiring NET_CAPABILITY_VALIDATED on >=M.

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
      this.networkListener.onNetworkConnected();
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
    NetworkInfo intentNetworkInfo = intent.getParcelableExtra(EXTRA_NETWORK_INFO);
    Log.v(LOG_TAG, "INTENT NETWORK " + intentNetworkInfo);

    if (intentNetworkInfo != null
        && intentNetworkInfo.getType() == ConnectivityManager.TYPE_VPN) {
      // VPN state changed, ignore.
      return;
    }
    sendUpdate();
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void networkChanged(Network network) {
    Log.v(LOG_TAG, "NETWORK CHANGED " + connectivityManager.getNetworkInfo(network));
    // In the future, it might make sense to ignore changes to networks that are not the active
    // network.

    sendUpdate();
  }

  private void sendUpdate() {
    if (networkListener == null) {
      return;
    }

    if (isConnected()) {
      networkListener.onNetworkConnected();
    } else {
      networkListener.onNetworkDisconnected();
    }
  }

  private boolean isConnected() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Network activeNetwork = connectivityManager.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }
      Log.v(LOG_TAG, "ACTIVE NETWORK " + connectivityManager.getNetworkInfo(activeNetwork));
      NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
      if (capabilities == null) {
        FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Got null capabilities for non-null network");
        return false;
      }
      // If the device is checking the network for internet access, we don't want to try to use
      // the network until after it's validated.  This is a workaround for an observed
      // misbehavior: if the Intra VPN captures the IP of the network's DNS servers before
      // validation completes, validation seems to fail.
      return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      //    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // Version < M
    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
    Log.v(LOG_TAG, "ACTIVE NETWORK " + activeNetworkInfo);
    if (activeNetworkInfo == null) {
      return false;
    }
    return activeNetworkInfo.isConnected() && activeNetworkInfo.isAvailable();
  }
}
