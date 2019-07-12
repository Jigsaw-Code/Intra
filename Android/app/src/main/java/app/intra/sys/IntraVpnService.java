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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.WorkerThread;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import app.intra.R;
import app.intra.net.VpnAdapter;
import app.intra.net.doh.ServerConnection;
import app.intra.net.doh.ServerConnectionFactory;
import app.intra.net.doh.Transaction;
import app.intra.net.doh.google.GoogleServerConnection;
import app.intra.net.socks.SocksVpnAdapter;
import app.intra.net.split.SplitVpnAdapter;
import app.intra.sys.NetworkManager.NetworkListener;
import app.intra.ui.MainActivity;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.util.Calendar;

public class IntraVpnService extends VpnService implements NetworkListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

  private static final String LOG_TAG = "IntraVpnService";
  private static final int SERVICE_ID = 1; // Only has to be unique within this app.
  private static final String MAIN_CHANNEL_ID = "vpn";
  private static final String WARNING_CHANNEL_ID = "warning";
  private static final String NO_PENDING_CONNECTION = "This value is not a possible URL.";

  // Factory for producing DoH connections.
  private final ServerConnectionFactory dohFactory = new ServerConnectionFactory(this);
  
  // The network manager is populated in onStartCommand.  Its main function is to enable delayed
  // initialization if the network is initially disconnected.
  private NetworkManager networkManager;

  // The state of the device's network access, recording the latest update from networkManager.
  private boolean networkConnected = false;

  // The VPN adapter runs within this service and is responsible for establishing the VPN and
  // passing packets to the network.  vpnAdapter is only null before startup and after shutdown,
  // but it may be atomically replaced by restartVpn().
  private VpnAdapter vpnAdapter = null;

  // The server connection represents a DNS query transport.  It is null before shutdown, during a
  // reconfiguration, and if connection bootstrap fails.
  private ServerConnection serverConnection = null;

  // The URL of the DNS server.  null and "" are special values indicating the default server.
  // This value can change if the user changes their configuration after starting the VPN.
  private String url = null;

  // The URL of a pending connection attempt, or a special value if there is no pending connection
  // attempt.  This value is only used within updateServerConnection(), where it serves to avoid
  // the creation of duplicate outstanding server connection attempts.  Whenever pendingUrl
  // indicates a pending connection, serverConnection should be null to avoid sending queries to the
  // previously selected server.
  private String pendingUrl = NO_PENDING_CONNECTION;

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
    if (networkManager != null) {
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
    Log.i(LOG_TAG, String.format("Starting DNS VPN service, url=%s", url));
    url = PersistentState.getServerUrl(this);

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
    networkManager = new NetworkManager(IntraVpnService.this, IntraVpnService.this);

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
      NotificationChannel channel = new NotificationChannel(MAIN_CHANNEL_ID, name, importance);
      channel.setDescription(description);

      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
      builder = new Notification.Builder(this, MAIN_CHANNEL_ID);
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

  public ServerConnection getServerConnection() {
    return serverConnection;
  }

  @WorkerThread
  private void updateServerConnection() {
    // This method consists of three steps:
    // 1. a synchronized block to check if an update is necessary and set a flag (pendingUrl)
    // 2. an unsynchronized section to perform the update without holding the lock
    // 3. a synchronized block to unset the flag and confirm the update

    // Step 1: Check if an update is necessary and set a flag (pendingUrl).
    synchronized (this) {
      if (serverConnection != null && dohFactory.equalUrls(url, serverConnection.getUrl())) {
        // Connection state is consistent.  No need for an update.
        return;
      }

      if (dohFactory.equalUrls(url, pendingUrl)) {
        // There's a pending update for this URL already.
        return;
      }

      // Indicate that there is a pending update.
      pendingUrl = url;

      // Stop using the old connection if present.
      serverConnection = null;
    }

    // Step 2: Perform the update (which blocks on network activity) without holding the lock.

    // Inform the controller that we are starting a new connection.
    VpnController controller = VpnController.getInstance();
    controller.onConnectionStateChanged(this, ServerConnection.State.NEW);

    // Bootstrap the new server connection, which may require resolving the new server's name, using
    // the current DNS configuration.
    Bundle bootstrap = new Bundle();
    bootstrap.putString(Names.SERVER.name(),
        PersistentState.extractHostForAnalytics(this, url));
    long beforeBootstrap = SystemClock.elapsedRealtime();
    final ServerConnection newConnection = dohFactory.get(url);

    if (newConnection != null) {
      if (newConnection instanceof GoogleServerConnection &&
          ((GoogleServerConnection)newConnection).didBootstrapWithFallback()) {
        bootstrap.putString(Names.FALLBACK.name(), Names.ALTERNATE_HOSTNAME.name());
      }
      controller.onConnectionStateChanged(this, ServerConnection.State.WORKING);

      // Measure bootstrap delay.
      long afterBootStrap = SystemClock.elapsedRealtime();
      bootstrap.putInt(Names.LATENCY.name(), (int) (afterBootStrap - beforeBootstrap));
      firebaseAnalytics.logEvent(Names.BOOTSTRAP.name(), bootstrap);
    } else {
      controller.onConnectionStateChanged(this, ServerConnection.State.FAILING);
      firebaseAnalytics.logEvent(Names.BOOTSTRAP_FAILED.name(), bootstrap);
    }

    // Step 3: Unset the flag and confirm the update.
    synchronized (this) {
      pendingUrl = NO_PENDING_CONNECTION;

      if (serverConnection != null && dohFactory.equalUrls(url, serverConnection.getUrl())) {
        // Connection state has somehow become consistent, so an update is no longer needed.
        return;
      }

      if (newConnection != null && dohFactory.equalUrls(url, newConnection.getUrl())) {
        // Current connection state is not consistent, but newConnection is consistent with the
        // current URL, so perform the update.
        serverConnection = newConnection;
      }
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

    startVpnAdapter();

    VpnController.getInstance().onStartComplete(this, vpnAdapter != null);
    if (vpnAdapter == null) {
      LogWrapper.log(Log.WARN, LOG_TAG, "Failed to startVpn VPN adapter");
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
      LogWrapper.log(Log.WARN, LOG_TAG, "Restart failed");
    }
  }

  @Override
  public void onCreate() {
    LogWrapper.log(Log.INFO, LOG_TAG, "Creating DNS VPN service");
    VpnController.getInstance().setIntraVpnService(this);

    firebaseAnalytics = FirebaseAnalytics.getInstance(this);

    syncNumRequests();
  }

  public void signalStopService(boolean userInitiated) {
    LogWrapper.log(
        Log.INFO,
        LOG_TAG,
        String.format("Received stop signal. User initiated: %b", userInitiated));

    if (!userInitiated) {
      final long[] vibrationPattern = {1000}; // Vibrate for one second.
      // Show revocation warning
      Notification.Builder builder;
      NotificationManager notificationManager =
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (VERSION.SDK_INT >= VERSION_CODES.O) {
        CharSequence name = getString(R.string.warning_channel_name);
        String description = getString(R.string.warning_channel_description);
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(WARNING_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        channel.enableVibration(true);
        channel.setVibrationPattern(vibrationPattern);

        notificationManager.createNotificationChannel(channel);
        builder = new Notification.Builder(this, WARNING_CHANNEL_ID);
      } else {
        builder = new Notification.Builder(this);
        builder.setVibrate(vibrationPattern);
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
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

      if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
        builder.setCategory(Notification.CATEGORY_ERROR);
      }

      notificationManager.notify(0, builder.getNotification());
    }

    stopVpnAdapter();
    stopSelf();
  }

  private VpnAdapter makeVpnAdapter() {
    // On M and later, Chrome uses getActiveNetwork() to determine which DNS servers to use.
    // SocksVpnAdapter makes this VPN the active network, whereas SplitVpnAdapter (which uses a
    // split-tunnel configuration) does not.  Therefore, on M and later, we have to use
    // SocksVpnAdapter.  Additionally, M and later also exhibit DownloadManager bugs when used
    // with a split-tunnel VPN.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return SocksVpnAdapter.establish(this);
    }
    // Pre-M we prefer SplitVpnAdapter, which uses much less CPU and RAM (important for older
    // devices).  This is also necessary because SocksVpnAdapter relies on VpnService.Builder
    // .addDisallowedApplication(this) to bypass its own VPN, and this method is only available in
    // Lollipop and later.
    return SplitVpnAdapter.establish(this);
  }

  private synchronized void startVpnAdapter() {
    if (vpnAdapter == null) {
      LogWrapper.log(Log.INFO, LOG_TAG, "Starting DNS resolver");
      vpnAdapter = makeVpnAdapter();
      if (vpnAdapter != null) {
        vpnAdapter.start();
      } else {
        LogWrapper.log(Log.ERROR, LOG_TAG, "Failed to start VPN adapter!");
      }
    }
  }

  private synchronized void stopVpnAdapter() {
    if (vpnAdapter != null) {
      vpnAdapter.close();
      vpnAdapter = null;
      VpnController.getInstance().onConnectionStateChanged(this, null);
    }
  }

  @Override
  public synchronized void onDestroy() {
    LogWrapper.log(Log.INFO, LOG_TAG, "Destroying DNS VPN service");

    PreferenceManager.getDefaultSharedPreferences(this).
        unregisterOnSharedPreferenceChangeListener(this);

    if (networkManager != null) {
      networkManager.destroy();
    }

    syncNumRequests();
    serverConnection = null;

    VpnController.getInstance().setIntraVpnService(null);

    stopForeground(true);
    if (vpnAdapter != null) {
      signalStopService(false);
    }
  }

  @Override
  public void onRevoke() {
    LogWrapper.log(Log.WARN, LOG_TAG, "VPN service revoked.");
    stopSelf();

    // Disable autostart if VPN permission is revoked.
    PersistentState.setVpnEnabled(this, false);
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
        // Play Store incompatibility is a known issue, so always exclude it.
        builder = builder.addDisallowedApplication("com.android.vending");
      } catch (PackageManager.NameNotFoundException e) {
        LogWrapper.logException(e);
        Log.e(LOG_TAG, "Failed to exclude an app", e);
      }
    }
    return builder;
  }

  public void recordTransaction(Transaction transaction) {
    transaction.responseTime = SystemClock.elapsedRealtime();
    transaction.responseCalendar = Calendar.getInstance();

    getTracker().recordTransaction(this, transaction);

    Intent intent = new Intent(Names.RESULT.name());
    intent.putExtra(Names.TRANSACTION.name(), transaction);
    LocalBroadcastManager.getInstance(IntraVpnService.this).sendBroadcast(intent);

    if (!networkConnected) {
      // No need to update the user-visible connection state while there is no network.
      return;
    }

    // Update the connection state.  If the transaction succeeded, then the connection is working.
    // If the transaction failed, then the connection is not working.
    // If the transaction was canceled, then we don't have any new information about the status
    // of the connection, so we don't send an update.
    VpnController controller = VpnController.getInstance();
    if (transaction.status == Transaction.Status.COMPLETE) {
      controller.onConnectionStateChanged(this, ServerConnection.State.WORKING);
    } else if (transaction.status != Transaction.Status.CANCELED) {
      controller.onConnectionStateChanged(this, ServerConnection.State.FAILING);
    }
  }

  private QueryTracker getTracker() {
    return VpnController.getInstance().getTracker(this);
  }

  private void syncNumRequests() {
    getTracker().sync(this);
  }

  private void setNetworkConnected(boolean connected) {
    networkConnected = connected;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // Indicate that traffic will be sent over the current active network.
      // See e.g. https://issuetracker.google.com/issues/68657525
      final Network activeNetwork = getSystemService(ConnectivityManager.class).getActiveNetwork();
      setUnderlyingNetworks(connected ? new Network[]{ activeNetwork } : null);
    }
  }

  // NetworkListener interface implementation
  @Override
  public void onNetworkConnected(NetworkInfo networkInfo) {
    LogWrapper.log(Log.INFO, LOG_TAG, "Connected event.");
    setNetworkConnected(true);
    // This code is used to start the VPN for the first time, but startVpn is idempotent, so we can
    // call it every time. startVpn performs network activity so it has to run on a separate thread.
    new Thread(
        new Runnable() {
          public void run() {
            updateServerConnection();
            startVpn();
          }
        }, "startVpn-onNetworkConnected")
        .start();
  }

  @Override
  public void onNetworkDisconnected() {
    LogWrapper.log(Log.INFO, LOG_TAG, "Disconnected event.");
    setNetworkConnected(false);
    VpnController.getInstance().onConnectionStateChanged(this, null);
  }
}
