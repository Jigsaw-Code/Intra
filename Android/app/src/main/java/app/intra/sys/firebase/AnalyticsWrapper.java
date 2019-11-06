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
package app.intra.sys.firebase;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.intra.sys.NetworkManager;
import app.intra.sys.NetworkManager.NetworkListener;
import app.intra.sys.firebase.BundleBuilder.Params;
import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Singleton class for reporting Analytics events.  In addition to improving the reporting API,
 * this class also attaches a country code and network type to each event.
 */
public class AnalyticsWrapper implements NetworkListener {
  private static AnalyticsWrapper singleton;

  // Analytics events
  private enum Events {
    BOOTSTRAP,
    BOOTSTRAP_FAILED,
    BYTES,
    EARLY_RESET,
    STARTVPN,
    TLS_PROBE,
    TRY_ALL_ACCEPTED,
    TRY_ALL_CANCELLED,
    TRY_ALL_DIALOG,
    TRY_ALL_FAILED,
    TRY_ALL_REQUESTED,
    UDP,
  }

  private enum NetworkTypes {
    MOBILE,
    WIFI,
    OTHER,
    NONE
  }

  private final @NonNull CountryCode countryCode;
  private final @NonNull FirebaseAnalytics analytics;
  private final @NonNull NetworkManager networkManager;

  private @Nullable NetworkInfo networkInfo = null;

  private AnalyticsWrapper(Context context) {
    countryCode = new CountryCode(context);
    analytics = FirebaseAnalytics.getInstance(context);

    // Register for network info updates.  Calling this constructor will synchronously call
    // onNetworkConnected if there is an active network.
    networkManager = new NetworkManager(context, this);
  }

  public static AnalyticsWrapper get(Context context) {
    if (singleton == null) {
      singleton = new AnalyticsWrapper(context);
    }
    return singleton;
  }

  @Override
  public void onNetworkConnected(NetworkInfo networkInfo) {
    this.networkInfo = networkInfo;
  }

  @Override
  public void onNetworkDisconnected() {
    this.networkInfo = null;
  }

  private NetworkTypes getNetworkType() {
    NetworkInfo info = networkInfo;  // For atomicity against network updates in a different thread.
    if (info == null) {
      return NetworkTypes.NONE;
    }
    int type = info.getType();
    if (type == ConnectivityManager.TYPE_MOBILE) {
      return NetworkTypes.MOBILE;
    } else if (type == ConnectivityManager.TYPE_WIFI) {
      return NetworkTypes.WIFI;
    }
    return NetworkTypes.OTHER;
  }

  private void log(Events e, @NonNull BundleBuilder b) {
    b.put(Params.COUNTRY, countryCode.getCombinedCountry());
    b.put(Params.NETWORK, getNetworkType().name());
    analytics.logEvent(e.name(), b.build());
  }

  /**
   * The DOH server connection was established successfully.
   * @param server The DOH server hostname for analytics.
   * @param latencyMs How long bootstrap took.
   */
  public void logBootstrap(String server, int latencyMs) {
    log(Events.BOOTSTRAP, new BundleBuilder()
        .put(Params.SERVER, server)
        .put(Params.LATENCY, latencyMs));
  }

  /**
   * The DOH server connection failed to establish successfully.
   * @param server The DOH server hostname.
   */
  public void logBootstrapFailed(String server) {
    log(Events.BOOTSTRAP_FAILED, new BundleBuilder().put(Params.SERVER, server));
  }

  /**
   * A connected TCP socket closed.
   * @param upload total bytes uploaded over the lifetime of a socket
   * @param download total bytes downloaded
   * @param port TCP port number (i.e. protocol type)
   * @param tcpHandshakeMs TCP handshake latency in milliseconds
   * @param duration socket lifetime in seconds
   * TODO: Add firstByteMs, the time between socket open and first byte from server.
   */
  public void logTCP(long upload, long download, int port, int tcpHandshakeMs, int duration) {
    log(Events.BYTES, new BundleBuilder()
        .put(Params.UPLOAD, upload)
        .put(Params.DOWNLOAD, download)
        .put(Params.PORT, port)
        .put(Params.TCP_HANDSHAKE_MS, tcpHandshakeMs)
        .put(Params.DURATION, duration));
  }

  /**
   * A TCP socket connected, but then failed after some bytes were uploaded, without receiving any
   * downstream data, triggering a retry.
   * @param bytes Amount uploaded before failure
   * @param chunks Number of upload writes before reset
   * @param timeout Whether the initial connection failed with a timeout
   * @param split Number of bytes included in the first retry segment
   * @param success Whether retry resulted in success (i.e. downstream data received)
   */
  public void logEarlyReset(int bytes, int chunks, boolean timeout, int split, boolean success) {
    log(Events.EARLY_RESET, new BundleBuilder()
        .put(Params.BYTES, bytes)
        .put(Params.CHUNKS, 1)
        .put(Params.TIMEOUT, timeout ? 1 : 0)
        .put(Params.SPLIT, split)
        .put(Params.RETRY, success ? 1 : 0));
  }

  /**
   * The VPN was established.
   * @param mode The VpnAdapter implementation in use.
   */
  public void logStartVPN(String mode) {
    log(Events.STARTVPN, new BundleBuilder().put(Params.MODE, mode));
  }

  /**
   * A TLS probe completed
   * @param server The name of the server used for TLS probing
   * @param result The TLSProbe.Result of the measurement.
   */
  public void logTLSProbe(String server, String result) {
    log(Events.TLS_PROBE, new BundleBuilder()
        .put(Params.SERVER, server)
        .put(Params.RESULT, result));
  }

  /**
   * Try-all concluded with the user approving a server.
   * @param server The selected server.
   */
  public void logTryAllAccepted(String server) {
    log(Events.TRY_ALL_ACCEPTED, new BundleBuilder().put(Params.SERVER, server));
  }

  /**
   * Try-all concluded with a user cancellation.
   * @param server The selected server.
   */
  public void logTryAllCancelled(String server) {
    log(Events.TRY_ALL_CANCELLED, new BundleBuilder().put(Params.SERVER, server));
  }

  /**
   * Try-all reached the point of showing the user a confirmation dialog.
   * @param server The selected server.
   */
  public void logTryAllDialog(String server) {
    log(Events.TRY_ALL_DIALOG, new BundleBuilder().put(Params.SERVER, server));
  }

  /**
   * Try-all failed to find any working server.
   */
  public void logTryAllFailed() {
    log(Events.TRY_ALL_FAILED, new BundleBuilder());
  }

  /**
   * The user requested try-all.
   */
  public void logTryAllRequested() {
    log(Events.TRY_ALL_REQUESTED, new BundleBuilder());
  }

  /**
   * A UDP association has concluded
   * @param upload Number of bytes uploaded on this association
   * @param download Number of bytes downloaded on this association
   * @param duration Duration of the association in seconds.
   */
  public void logUDP(long upload, long download, int duration) {
    log(Events.UDP, new BundleBuilder()
        .put(Params.UPLOAD, upload)
        .put(Params.DOWNLOAD, download)
        .put(Params.DURATION, duration));
  }
}
