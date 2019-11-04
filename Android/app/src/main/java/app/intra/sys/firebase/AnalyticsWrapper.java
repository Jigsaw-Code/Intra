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
import androidx.annotation.NonNull;
import app.intra.sys.firebase.BundleBuilder.Params;
import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Library of Analytics events reported by Intra.  In addition to improving the reporting API,
 * this class also attaches the Mobile Country Code (MCC) to each event, which indicates the client
 * country, to compensate for country classification errors in Firebase.
 */
public class AnalyticsWrapper {

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

  // Mobile Country Code, or 0 if it is unknown.
  private final int mcc;
  private final FirebaseAnalytics analytics;
  private AnalyticsWrapper(int mcc, FirebaseAnalytics analytics) {
    this.mcc = mcc;
    this.analytics = analytics;
  }

  public static AnalyticsWrapper get(Context context) {
    return new AnalyticsWrapper(MobileCountryCode.get(context),
        FirebaseAnalytics.getInstance(context));
  }

  private void log(Events e, @NonNull BundleBuilder b) {
    b.put(Params.MCC, mcc);
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
