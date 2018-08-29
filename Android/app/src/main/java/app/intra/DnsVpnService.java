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

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import androidx.annotation.CheckResult;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import app.intra.util.DnsQueryTracker;
import app.intra.util.DnsTransaction;
import app.intra.util.Names;

public class DnsVpnService extends VpnService implements NetworkManager.NetworkListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

  private static final String LOG_TAG = "DnsVpnService";
  private static final int SERVICE_ID = 1; // Only has to be unique within this app.
  private static final int VPN_INTERFACE_MTU = 32767;
  // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193.
  private static final String IPV6_SUBNET = "fd66:f83a:c650::%s";
  private static final String CHANNEL_ID = "vpn";

  private NetworkManager networkManager;
  private PrivateAddress privateIpv4Address = null;
  private PrivateAddress privateIpv6Address = null;
  private ParcelFileDescriptor tunFd = null;
  private DnsResolverUdpToHttps dnsResolver = null;
  private ServerConnection serverConnection = null;
  private String url = null;

  private boolean shouldRestart = false;

  private FirebaseAnalytics firebaseAnalytics;

  public boolean isOn() {
    return tunFd != null;
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
    if (PersistentState.APPS_KEY.equals(key) && tunFd != null) {
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

    if (dnsResolver != null) {
      dnsResolver.serverConnection = serverConnection;
    }
  }

  /**
   * Starts the VPN. This method performs network activity, so it must not run on the main thread.
   * This method is idempotent, and is marked synchronized so that it can safely be called from a
   * freshly spawned thread.
   *
   * TODO: Consider cancellation races more carefully.
   */
  private synchronized void startVpn() {
    if (tunFd != null) {
      return;
    }

    // The server connection setup process may rely on DNS, so it has to occur before we set up
    // the VPN.
    updateServerConnection();

    tunFd = establishVpn();
    DnsVpnController.getInstance().onStartComplete(this, tunFd != null);
    if (tunFd == null) {
      FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Failed to get TUN device");
      stopSelf();
      return;
    }

    startDnsResolver();
  }

  private void restartVpn() {
    // Attempt seamless handoff as described in the docs for VpnService.Builder.establish().
    final ParcelFileDescriptor oldTunFd = tunFd;
    tunFd = establishVpn();
    if (serverConnection != null) {
      // Cancel all outstanding queries, and establish a new client bound to the new active network.
      // Subsequent queries will flow into the new tunFd, and then into the new HTTP client.
      serverConnection.reset();
    }
    new Thread(
        new Runnable() {
          public void run() {
            // Regenerate dnsResolver to use the new tunFd.
            stopDnsResolver();
            startDnsResolver();

            // Close the old FD to release resources.
            try {
              if (oldTunFd != null) {
                oldTunFd.close();
              }
            } catch (IOException e) {
              FirebaseCrash.report(e);
            }
          }
        }, "restartVpn")
        .start();
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

    // stopDnsResolver() performs network activity, so it can't run on the main thread due to
    // Android rules.  closeVpnInterface() can't be run until after the network action, because
    // we need the packet to go through the VPN interface.  Therefore, both actions have to run in
    // a new thread, in this order.
    new Thread(
            new Runnable() {
              public void run() {
                stopDnsResolver();
                closeVpnInterface();
              }
            }, "Stop VPN on signal")
        .start();
    stopSelf();
  }

  private void closeVpnInterface() {
    if (tunFd != null) {
      try {
        tunFd.close();
      } catch (IOException e) {
        FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to close the VPN interface.");
        FirebaseCrash.report(e);
      } finally {
        tunFd = null;
      }
    }
  }

  private void pingLocalDns() {
    try {
      // Send an empty UDP packet to the DNS server, which will cause the read() call in
      // DnsResolverUdpToHttps to unblock and return.  This wakes up that thread, so it can detect
      // that it has been interrupted and perform a clean shutdown.
      byte zero[] = {0};
      DatagramPacket packet =
          new DatagramPacket(
              zero, zero.length, 0, InetAddress.getByName(privateIpv4Address.router), 53);
      DatagramSocket datagramSocket = new DatagramSocket();
      datagramSocket.send(packet);
    } catch (IOException e) {
      // An IOException likely means that the VPN has already been torn down, so there's no need for
      // this ping.
      FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to send UDP ping: " + e.getMessage());
    }
  }

  private synchronized void startDnsResolver() {
    if (dnsResolver == null && serverConnection != null) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Starting DNS resolver");
      dnsResolver = new DnsResolverUdpToHttps(this, tunFd, serverConnection);
      dnsResolver.start();
    }
  }

  private synchronized void stopDnsResolver() {
    if (dnsResolver != null) {
      dnsResolver.interrupt();
      pingLocalDns(); // Try to wake up the resolver thread if it's blocked on a read() call.
      dnsResolver = null;
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
    if (dnsResolver != null) {
      signalStopService(false);
    }

    if (shouldRestart) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Restarting from onDestroy");
      startService(new Intent(this, DnsVpnService.class));
    }
  }

  @Override
  public void onRevoke() {
    FirebaseCrash.logcat(Log.WARN, LOG_TAG, "VPN service revoked.");
    stopDnsResolver();
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

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  private ParcelFileDescriptor establishVpn() {
    privateIpv6Address = new PrivateAddress(IPV6_SUBNET, 120);
    privateIpv4Address = selectPrivateAddress();
    if (privateIpv4Address == null) {
      FirebaseCrash.logcat(
          Log.ERROR, LOG_TAG, "Unable to find a private address on which to establish a VPN.");
      return null;
    }
    Log.i(LOG_TAG, String.format("VPN address: { IPv4: %s, IPv6: %s }",
                                 privateIpv4Address, privateIpv6Address));

    final String establishVpnErrorMsg = "Failed to establish VPN ";
    ParcelFileDescriptor tunFd = null;
    try {
      VpnService.Builder builder =
          (new VpnService.Builder())
              .setSession("Jigsaw DNS Protection")
              .setMtu(VPN_INTERFACE_MTU)
              .addAddress(privateIpv4Address.address, privateIpv4Address.prefix)
              .addRoute(privateIpv4Address.subnet, privateIpv4Address.prefix)
              .addDnsServer(privateIpv4Address.router)
              .addAddress(privateIpv6Address.address, privateIpv6Address.prefix)
              .addRoute(privateIpv6Address.subnet, privateIpv6Address.prefix)
              .addDnsServer(privateIpv6Address.router);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // Only available in API >= 21
        builder = builder.setBlocking(true);
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
          FirebaseCrash.report(e);
          Log.e(LOG_TAG, "Failed to exclude an app", e);
        }
      }
      tunFd = builder.establish();
    } catch (IllegalArgumentException e) {
      FirebaseCrash.report(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (SecurityException e) {
      FirebaseCrash.report(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (IllegalStateException e) {
      FirebaseCrash.report(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    }

    return tunFd;
  }

  private static class PrivateAddress {
    final String address;
    final String subnet;
    final String router;
    final int prefix;

    PrivateAddress(String addressFormat, int subnetPrefix) {
      subnet = String.format(addressFormat, "0");
      address = String.format(addressFormat, "1");
      router = String.format(addressFormat, "2");
      prefix = subnetPrefix;
    }

    @Override
    public String toString() {
      return String.format("{ subnet: %s, address: %s, router: %s, prefix: %d }",
                           subnet, address, router, prefix);
    }
  }

  private static PrivateAddress selectPrivateAddress() {
    // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
    // which private address range isn't in use.
    HashMap<String, PrivateAddress> candidates = new HashMap<>();
    candidates.put("10", new PrivateAddress("10.0.0.%s", 8));
    candidates.put("172", new PrivateAddress("172.16.0.%s", 12));
    candidates.put("192", new PrivateAddress("192.168.0.%s", 16));
    candidates.put("169", new PrivateAddress("169.254.1.%s", 24));
    List<NetworkInterface> netInterfaces;
    try {
      netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
    } catch (SocketException e) {
      FirebaseCrash.report(e);
      e.printStackTrace();
      return null;
    }

    for (NetworkInterface netInterface : netInterfaces) {
      for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses())) {
        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
          String ipAddress = inetAddress.getHostAddress();
          if (ipAddress.startsWith("10.")) {
            candidates.remove("10");
          } else if (ipAddress.length() >= 6
              && ipAddress.substring(0, 6).compareTo("172.16") >= 0
              && ipAddress.substring(0, 6).compareTo("172.31") <= 0) {
            candidates.remove("172");
          } else if (ipAddress.startsWith("192.168")) {
            candidates.remove("192");
          }
        }
      }
    }

    if (candidates.size() > 0) {
      return candidates.values().iterator().next();
    }

    return null;
  }

  public void recordTransaction(DnsTransaction transaction) {
    transaction.responseTime = SystemClock.elapsedRealtime();
    transaction.responseCalendar = Calendar.getInstance();

    getTracker().recordTransaction(this, transaction);

    Intent intent = new Intent(Names.RESULT.name());
    intent.putExtra(Names.TRANSACTION.name(), transaction);
    LocalBroadcastManager.getInstance(DnsVpnService.this).sendBroadcast(intent);
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
    if (tunFd != null) {
      startDnsResolver();
    } else {
      // startVpn performs network activity so it has to run on a separate thread.
      new Thread(
              new Runnable() {
                public void run() {
                  startVpn();
                }
              }, "startVpn-onNetworkConnected")
          .start();
    }
  }

  @Override
  public void onNetworkDisconnected() {
    FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Disconnected event.");
    // stopDnsResolver performs network activity so it has to run on a separate thread.
    new Thread(
            new Runnable() {
              public void run() {
                stopDnsResolver();
              }
            }, "stopDnsResolver-onNetworkDisconnected")
        .start();
  }
}
