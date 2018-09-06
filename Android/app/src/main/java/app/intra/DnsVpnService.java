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

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.Calendar;

import androidx.annotation.WorkerThread;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import app.intra.socks.SocksVpnAdapter;
import app.intra.util.DnsQueryTracker;
import app.intra.util.DnsTransaction;
import app.intra.util.Names;

public class DnsVpnService extends VpnService implements NetworkManager.NetworkListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

  private static final String LOG_TAG = "DnsVpnService";
  private static final int SERVICE_ID = 1; // Only has to be unique within this app.
  private static final String CHANNEL_ID = "vpn";

  private NetworkManager networkManager;
  private VpnAdapter vpnAdapter = null;
  private ServerConnection serverConnection = null;
  private boolean networkConnected = false;
  private String url = null;

  private FirebaseAnalytics firebaseAnalytics;

  public boolean isOn() {
    return vpnAdapter != null;
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
    if (PersistentState.APPS_KEY.equals(key) && vpnAdapter != null) {
      // Restart the VPN so the new app exclusion choices take effect immediately.
      restartVpn();
    }
    if (PersistentState.URL_KEY.equals(key)) {
      url = PersistentState.getServerUrl(this);
      spawnServerUpdate();
    }
  }

  private synchronized void spawnServerUpdate() {
    if (networkManager != null && serverConnection != null) {
      new Thread(
          new Runnable() {
            public void run() {
              updateServerConnection();
            }
          }, "updateServerConnection-onStartCommand")
          .start();
    }
  }

  @Override
  public synchronized int onStartCommand(Intent intent, int flags, int startId) {
    String newUrl = PersistentState.getServerUrl(this);
    if (TextUtils.equals(url, newUrl)) {
      Log.i(LOG_TAG, "Ignoring redundant start command");
      return START_REDELIVER_INTENT;
    }

    url = newUrl;
    Log.i(LOG_TAG, String.format("Starting DNS VPN service, url=%s", url));

    // Registers this class as a listener for user preference changes.
    PreferenceManager.getDefaultSharedPreferences(this).
        registerOnSharedPreferenceChangeListener(this);


    if (networkManager != null) {
      spawnServerUpdate();
      return START_REDELIVER_INTENT;
    }

    // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
    // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
    // until we come online.
    networkManager = new NetworkManager(DnsVpnService.this, DnsVpnService.this);

    // Mark this as a foreground service.  This is normally done to ensure that the service
    // survives under memory pressure.  Since this is a VPN service, it is presumably protected
    // anyway, but the foreground service mechanism allows us to set a persistent notification,
    // which helps users understand what's going on, and return to the app if they want.
    PendingIntent mainActivityIntent =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = getString(R.string.channel_name);
      String description = getString(R.string.channel_description);
      // LOW is the lowest importance that is allowed with startForeground in Android O.
      int importance = NotificationManager.IMPORTANCE_LOW;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
      channel.setDescription(description);

      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
      builder = new Notification.Builder(this, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(this);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        // Min-priority notifications don't show an icon in the notification bar, reducing clutter.
        // Only available in API >= 16.  Deprecated in API 26.
        builder = builder.setPriority(Notification.PRIORITY_MIN);
      }
    }

    builder.setSmallIcon(R.drawable.ic_status_bar)
            .setContentTitle(getResources().getText(R.string.notification_title))
            .setContentText(getResources().getText(R.string.notification_content))
            .setContentIntent(mainActivityIntent);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Secret notifications are not shown on the lock screen.  No need for this app to show there.
      // Only available in API >= 21
      builder = builder.setVisibility(Notification.VISIBILITY_SECRET);
    }

    startForeground(SERVICE_ID, builder.getNotification());

    return START_REDELIVER_INTENT;
  }

  ServerConnection getServerConnection() {
    return serverConnection;
  }

  @WorkerThread
  private synchronized void updateServerConnection() {
    if (serverConnection != null && TextUtils.equals(url, serverConnection.getUrl())) {
      return;
    }

    // Inform the controller that we are starting a new connection.
    DnsVpnController controller = DnsVpnController.getInstance();
    controller.onConnectionStateChanged(this, ServerConnection.State.NEW);

    // Bootstrap the new server connection, which may require resolving the new server's name, using
    // the current DNS configuration.
    Bundle bootstrap = new Bundle();
    long beforeBootstrap = SystemClock.elapsedRealtime();
    if (url == null || url.isEmpty()) {
      // Use the Google Resolver
      AssetManager assets = this.getApplicationContext().getAssets();
      serverConnection = GoogleServerConnection.get(new GoogleServerDatabase(this, assets));
    } else {
      serverConnection = StandardServerConnection.get(url);
    }

    if (serverConnection != null) {
      controller.onConnectionStateChanged(this, ServerConnection.State.WORKING);

      // Measure bootstrap delay.
      long afterBootStrap = SystemClock.elapsedRealtime();
      bootstrap.putInt(Names.LATENCY.name(), (int) (afterBootStrap - beforeBootstrap));
      firebaseAnalytics.logEvent(Names.BOOTSTRAP.name(), bootstrap);
    } else {
      controller.onConnectionStateChanged(this, ServerConnection.State.FAILING);
      firebaseAnalytics.logEvent(Names.BOOTSTRAP_FAILED.name(), bootstrap);
    }
  }

  /**
   * Starts the VPN. This method performs network activity, so it must not run on the main thread.
   * This method is idempotent, and is marked synchronized so that it can safely be called from a
   * freshly spawned thread.
   */
  @WorkerThread
  private synchronized void startVpn() {
    if (vpnAdapter != null) {
      return;
    }

    // The server connection setup process may rely on DNS, so it has to occur before we set up
    // the VPN.
    updateServerConnection();
    startVpnAdapter();

    DnsVpnController.getInstance().onStartComplete(this, vpnAdapter != null);
    if (vpnAdapter == null) {
      FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Failed to startVpn VPN adapter");
      stopSelf();
    }
  }

  private synchronized void restartVpn() {
    // Attempt seamless handoff as described in the docs for VpnService.Builder.establish().
    final VpnAdapter oldAdapter = vpnAdapter;
    vpnAdapter = makeVpnAdapter();
    if (serverConnection != null) {
      // Cancel all outstanding queries, and establish a new client bound to the new active network.
      // Subsequent queries will flow into the new tunFd, and then into the new HTTP client.
      serverConnection.reset();
    }
    oldAdapter.close();
    if (vpnAdapter != null) {
      vpnAdapter.start();
    } else {
      FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Restart failed");
    }
  }

  @Override
  public void onCreate() {
    FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Creating DNS VPN service");
    DnsVpnController.getInstance().setDnsVpnService(this);

    firebaseAnalytics = FirebaseAnalytics.getInstance(this);

    syncNumRequests();
  }

  public void signalStopService(boolean userInitiated) {
    // TODO(alalama): display alert if not user initiated
    FirebaseCrash.logcat(
        Log.INFO,
        LOG_TAG,
        String.format("Received stop signal. User initiated: %b", userInitiated));

    stopVpnAdapter();
    stopSelf();
  }

  private VpnAdapter makeVpnAdapter() {
    // On M and later, Chrome uses getActiveNetwork() to determine which DNS servers to use.
    // SocksVpnAdapter makes this VPN the active network, whereas DnsVpnAdapter (which uses a
    // split-tunnel configuration) does not.  Therefore, on M and later, we have to use
    // SocksVpnAdapter.  Additionally, M and later also exhibit DownloadManager bugs when used
    // with a split-tunnel VPN.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return SocksVpnAdapter.get(this);
    }
    // Pre-M we prefer DnsVpnAdapter, which uses much less CPU and RAM (important for older
    // devices).  This is also necessary because SocksVpnAdapter relies on VpnService.Builder
    // .addDisallowedApplication(this) to bypass its own VPN, and this method is only available in
    // Lollipop and later.
    return DnsVpnAdapter.get(this);
  }

  private synchronized void startVpnAdapter() {
    if (vpnAdapter == null) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Starting DNS resolver");
      vpnAdapter = makeVpnAdapter();
      if (vpnAdapter != null) {
        vpnAdapter.start();
      } else {
        FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to start VPN adapter!");
      }
    }
  }

  private synchronized void stopVpnAdapter() {
    if (vpnAdapter != null) {
      vpnAdapter.close();
      vpnAdapter = null;
      DnsVpnController.getInstance().onConnectionStateChanged(this, null);
    }
  }

  @Override
  public synchronized void onDestroy() {
    FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Destroying DNS VPN service");

    PreferenceManager.getDefaultSharedPreferences(this).
        unregisterOnSharedPreferenceChangeListener(this);

    if (networkManager != null) {
      networkManager.destroy();
    }

    syncNumRequests();
    serverConnection = null;

    DnsVpnController.getInstance().setDnsVpnService(null);

    stopForeground(true);
    if (vpnAdapter != null) {
      signalStopService(false);
    }
  }

  @Override
  public void onRevoke() {
    FirebaseCrash.logcat(Log.WARN, LOG_TAG, "VPN service revoked.");
    stopVpnAdapter();
    stopSelf();

    // Disable autostart if VPN permission is revoked.
    PersistentState.setVpnEnabled(this, false);

    // Show revocation warning
    Notification.Builder builder;
    NotificationManager notificationManager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = getString(R.string.warning_channel_name);
      String description = getString(R.string.warning_channel_description);
      int importance = NotificationManager.IMPORTANCE_HIGH;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
      channel.setDescription(description);

      notificationManager.createNotificationChannel(channel);
      builder = new Notification.Builder(this, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(this);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        // Only available in API >= 16.  Deprecated in API 26.
        builder = builder.setPriority(Notification.PRIORITY_MAX);
      }
    }

    PendingIntent mainActivityIntent = PendingIntent.getActivity(
        this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

    builder.setSmallIcon(R.drawable.ic_status_bar)
        .setContentTitle(getResources().getText(R.string.warning_title))
        .setContentText(getResources().getText(R.string.notification_content))
        .setFullScreenIntent(mainActivityIntent, true)  // Open the main UI if possible.
        .setAutoCancel(true);

    notificationManager.notify(0, builder.getNotification());
  }

  public VpnService.Builder newBuilder() {
    VpnService.Builder builder = new VpnService.Builder();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Some WebRTC apps rely on the ability to bind to specific interfaces, which is only
      // possible if we allow bypass.
      builder = builder.allowBypass();

      try {
        // Workaround for any app incompatibility bugs.
        for (String packageName : PersistentState.getExcludedPackages(this)) {
          builder = builder.addDisallowedApplication(packageName);
        }
      } catch (PackageManager.NameNotFoundException e) {
        FirebaseCrash.report(e);
        Log.e(LOG_TAG, "Failed to exclude an app", e);
      }
    }
    return builder;
  }

  public void recordTransaction(DnsTransaction transaction) {
    transaction.responseTime = SystemClock.elapsedRealtime();
    transaction.responseCalendar = Calendar.getInstance();

    getTracker().recordTransaction(this, transaction);

    Intent intent = new Intent(Names.RESULT.name());
    intent.putExtra(Names.TRANSACTION.name(), transaction);
    LocalBroadcastManager.getInstance(DnsVpnService.this).sendBroadcast(intent);

    if (!networkConnected) {
      // No need to update the user-visible connection state while there is no network.
      return;
    }

    // Update the connection state.  If the transaction succeeded, then the connection is working.
    // If the transaction failed, then the connection is not working.
    // If the transaction was canceled, then we don't have any new information about the status
    // of the connection, so we don't send an update.
    DnsVpnController controller = DnsVpnController.getInstance();
    if (transaction.status == DnsTransaction.Status.COMPLETE) {
      controller.onConnectionStateChanged(this, ServerConnection.State.WORKING);
    } else if (transaction.status != DnsTransaction.Status.CANCELED) {
      controller.onConnectionStateChanged(this, ServerConnection.State.FAILING);
    }
  }

  private DnsQueryTracker getTracker() {
    return DnsVpnController.getInstance().getTracker(this);
  }

  private void syncNumRequests() {
    getTracker().sync(this);
  }

  // NetworkListener interface implementation
  @Override
  public void onNetworkConnected(NetworkInfo networkInfo) {
    FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Connected event.");
    networkConnected = true;
    // This code is used to start the VPN for the first time, but startVpn is idempotent, so we can
    // call it every time. startVpn performs network activity so it has to run on a separate thread.
    new Thread(
        new Runnable() {
          public void run() {
            startVpn();
          }
        }, "startVpn-onNetworkConnected")
        .start();
  }

  @Override
  public void onNetworkDisconnected() {
    FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Disconnected event.");
    networkConnected = false;
    DnsVpnController.getInstance().onConnectionStateChanged(this, null);
  }
}
