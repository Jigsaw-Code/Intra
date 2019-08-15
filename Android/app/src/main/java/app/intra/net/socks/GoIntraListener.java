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
package app.intra.net.socks;

import android.content.Context;
import android.os.Bundle;
import app.intra.sys.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import intra.RetryStats;
import intra.TCPSocketSummary;
import intra.UDPSocketSummary;

/**
 * This is a callback class that is passed to our go-tun2socks code.  Go calls this class's methods
 * when a socket has concluded, with performance metrics for that socket, and this class forwards
 * those metrics to Firebase.
 */
public class GoIntraListener implements tunnel.IntraListener {

  // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
  // on these short messages would be large, so we only report metrics on sockets that transfer at
  // least this many bytes.
  private static final int UDP_THRESHOLD_BYTES = 10000;

  private final Context context;
  GoIntraListener(Context context) {
    this.context = context;
  }

  @Override
  public void onTCPSocketClosed(TCPSocketSummary summary) {
    // We had a functional socket long enough to record statistics.
    // Report the BYTES event :
    // - UPLOAD: total bytes uploaded over the lifetime of a socket
    // - DOWNLOAD: total bytes downloaded
    // - PORT: TCP port number (i.e. protocol type)
    // - TCP_HANDSHAKE_MS: TCP handshake latency in milliseconds
    // - DURATION: socket lifetime in seconds
    // - TODO: FIRST_BYTE_MS: Time between socket open and first byte from server, in milliseconds.

    Bundle bytesEvent = new Bundle();
    bytesEvent.putLong(Names.UPLOAD.name(), summary.getUploadBytes());
    bytesEvent.putLong(Names.DOWNLOAD.name(), summary.getDownloadBytes());
    bytesEvent.putInt(Names.PORT.name(), summary.getServerPort());
    bytesEvent.putInt(Names.TCP_HANDSHAKE_MS.name(), summary.getSynack());
    bytesEvent.putInt(Names.DURATION.name(), summary.getDuration());
    FirebaseAnalytics.getInstance(context).logEvent(Names.BYTES.name(), bytesEvent);

    RetryStats retry = summary.getRetry();
    if (retry != null) {
      // Prepare an EARLY_RESET event to collect metrics on success rates for splitting:
      // - BYTES : Amount uploaded before reset
      // - CHUNKS : Number of upload writes before reset
      // - TIMEOUT : Whether the initial connection failed with a timeout.
      // - SPLIT : Number of bytes included in the first retry segment
      // - RETRY : 1 if retry succeeded, otherwise 0
      Bundle resetEvent = new Bundle();
      resetEvent.putInt(Names.BYTES.name(), retry.getBytes());
      resetEvent.putInt(Names.CHUNKS.name(), retry.getChunks());
      resetEvent.putInt(Names.TIMEOUT.name(), retry.getTimeout() ? 1 : 0);
      resetEvent.putInt(Names.SPLIT.name(), retry.getSplit());
      boolean success = summary.getDownloadBytes() > 0;
      resetEvent.putInt(Names.RETRY.name(), success ? 1 : 0);
      FirebaseAnalytics.getInstance(context).logEvent(Names.EARLY_RESET.name(), resetEvent);
    }
 }

  @Override
  public void onUDPSocketClosed(UDPSocketSummary summary) {
    long totalBytes = summary.getUploadBytes() + summary.getDownloadBytes();
    if (totalBytes < UDP_THRESHOLD_BYTES) {
      return;
    }
    Bundle event = new Bundle();
    event.putLong(Names.UPLOAD.name(), summary.getUploadBytes());
    event.putLong(Names.DOWNLOAD.name(), summary.getDownloadBytes());
    event.putLong(Names.DURATION.name(), summary.getDuration());
    FirebaseAnalytics.getInstance(context).logEvent(Names.UDP.name(), event);
  }
}
