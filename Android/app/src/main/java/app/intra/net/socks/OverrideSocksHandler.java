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
package app.intra.net.socks;

import android.os.Bundle;
import android.os.SystemClock;
import app.intra.sys.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import sockslib.server.Session;
import sockslib.server.io.Pipe;
import sockslib.server.io.PipeListener;
import sockslib.server.io.StreamPipe;
import sockslib.server.msg.CommandMessage;
import sockslib.server.msg.CommandResponseMessage;
import sockslib.server.msg.ServerReply;


/**
 * This SocksHandler acts as a normal Socks5Handler, except that TCP sockets can be retried after
 * some failure types.  This class inherits from UdpOverrideSocksHandler so that SocksServer can
 * have both behaviors.  This class is separate from UdpOverrideSocksHandler to make the code more
 * comprehensible.
 */
public class OverrideSocksHandler extends UdpOverrideSocksHandler {
  // Names for the upload and download pipes for monitoring.
  private static final String UPLOAD = "upload";
  private static final String DOWNLOAD = "download";

  // On retries, limit the first packet to a random length 32-64 bytes (inclusive).
  private static final int MIN_SPLIT = 32, MAX_SPLIT = 64;
  private static final Random RANDOM = new Random();

  // Protocol and error identification constants.
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;

  // Error string when the HTTPS timeout is exceeded.
  private static final String TIMEOUT_MESSAGE = "HTTPS Server timeout";

  // Timer thread for keeping track of socket timeouts.
  private static final Timer TIMEOUTS = new Timer("HTTPS server timeout");

  /**
   * @param tcpHandshakeMs The duration of the TCP handshake for this socket (typically 1 RTT).
   * @return Maximum time to wait for an HTTPS handshake response before engaging split-retry.
   */
  private static int httpsTimeoutMs(int tcpHandshakeMs) {
    // These values were chosen to have a <1% false positive rate based on test data.
    // False positives trigger an unnecessary retry, which can make connections slower, so they are
    // worth avoiding.  However, overly long timeouts make retry slower and less useful.
    return 2 * tcpHandshakeMs + 1200;
  }

  // If true, apply splitting on HTTPS automatically, without waiting for an error condition.
  private boolean alwaysSplitHttps = false;
  void setAlwaysSplitHttps(boolean v) {
    alwaysSplitHttps = v;
  }

  @Override
  public void doConnect(Session session, CommandMessage commandMessage) throws IOException {
    ServerReply reply = null;
    Socket socket = null;
    InetAddress bindAddress = null;
    int bindPort = 0;
    InetAddress remoteServerAddress = commandMessage.getInetAddress();
    int remoteServerPort = commandMessage.getPort();

    // set default bind address.
    byte[] defaultAddress = {0, 0, 0, 0};
    bindAddress = InetAddress.getByAddress(defaultAddress);
    int tcpHandshakeMs = -1;
    // DO connect
    try {
      // Connect directly.
      long beforeConnect = SystemClock.elapsedRealtime();
      socket = new Socket(remoteServerAddress, remoteServerPort);
      tcpHandshakeMs = (int)(SystemClock.elapsedRealtime() - beforeConnect);
      bindAddress = socket.getLocalAddress();
      bindPort = socket.getLocalPort();
      reply = ServerReply.SUCCEEDED;

    } catch (IOException e) {
      if (e.getMessage().equals("Connection refused")) {
        reply = ServerReply.CONNECTION_REFUSED;
      } else if (e.getMessage().equals("Operation timed out")) {
        reply = ServerReply.TTL_EXPIRED;
      } else if (e.getMessage().equals("Network is unreachable")) {
        reply = ServerReply.NETWORK_UNREACHABLE;
      } else if (e.getMessage().equals("Connection timed out")) {
        reply = ServerReply.TTL_EXPIRED;
      } else {
        reply = ServerReply.GENERAL_SOCKS_SERVER_FAILURE;
      }
      logger.info("SESSION[{}] connect {} [{}] exception:{}", session.getId(), new
          InetSocketAddress(remoteServerAddress, remoteServerPort), reply, e.getMessage());
    }

    CommandResponseMessage responseMessage =
        new CommandResponseMessage(VERSION, reply, bindAddress, bindPort);
    session.write(responseMessage);
    if (reply != ServerReply.SUCCEEDED) {
      session.close();
      return;
    }

    // Code prior to this point is essentially unmodified from sockslib.  At this point, sockslib
    // normally creates a SocketPipe to pass the data.  We change the behavior here to allow retry.

    final OutputStream sessionDownload = session.getOutputStream();
    final InputStream sessionUpload = session.getInputStream();
    InputStream socketDownload = socket.getInputStream();
    OutputStream socketUpload = socket.getOutputStream();

    final StreamPipe upload = new StreamPipe(sessionUpload, socketUpload, UPLOAD);
    upload.setBufferSize(BUFFER_SIZE);
    StreamPipe download = new StreamPipe(socketDownload, sessionDownload, DOWNLOAD);
    download.setBufferSize(BUFFER_SIZE);

    StatsListener listener = null;
    try {
      if (alwaysSplitHttps && remoteServerPort == HTTPS_PORT) {
        listener = runWithImmediateSplit(upload, download, sessionUpload, socketUpload);
      } else {
        listener = runPipes(upload, download, remoteServerPort, httpsTimeoutMs(tcpHandshakeMs));
        // We consider a termination event as a potentially recoverable failure if
        // (1) It is HTTPS
        // (2) Some data has been uploaded (i.e. the TLS ClientHello)
        // (3) No data has been received
        // In this situation, the listener's uploadBuffer should be nonempty.
        if (remoteServerPort == HTTPS_PORT && listener.uploadBytes > 0 && listener.downloadBytes == 0
            && listener.uploadBuffer != null) {
          // To attempt recovery, we
          // (1) Close the remote socket (which has already failed)
          // (2) Open a new one
          // (3) Connect the new remote socket to the existing StreamPipe
          // (4) Retry the ClientHello via splitRetry
          socket.close();
          download.stop();
          socket = new Socket(remoteServerAddress, remoteServerPort);
          socket.setTcpNoDelay(true);
          socketDownload = socket.getInputStream();
          socketUpload = socket.getOutputStream();

          download = new StreamPipe(socketDownload, sessionDownload, DOWNLOAD);
          download.setBufferSize(BUFFER_SIZE);

          // upload is blocked in a read from sessionUpload, which cannot be canceled.
          // However, we have modified StreamPipe to have a setter for the destination stream, so
          // that the next transfer will be uploaded into the new socket instead of the old one.
          upload.setDestination(socketUpload);

          // Retry the connection while splitting the first flight into smaller packets.
          // The retry doesn't trigger further retries if it fails, so it doesn't apply a timeout.
          listener = splitRetry(upload, download, socketUpload, listener);
        }
      }
    } catch (InterruptedException e) {
      // An interrupt is a normal lifecycle event and indicates that we should perform a clean
      // shutdown.
    }

    // Terminate and release both sockets.
    upload.stop();
    download.stop();
    socket.close();
    session.close();

    if (listener != null) {
      // We had a functional socket long enough to record statistics.
      // Report the BYTES event :
      // - VALUE : total transfer over the lifetime of a socket
      // - PORT : TCP port number (i.e. protocol type)
      // - DURATION: socket lifetime in seconds
      // - TCP_HANDSHAKE_MS : TCP handshake latency in milliseconds
      // - FIRST_BYTE_MS : Time between socket open and first byte from server, in milliseconds.

      Bundle event = new Bundle();
      event.putLong(Names.UPLOAD.name(), listener.uploadBytes);
      event.putLong(Names.DOWNLOAD.name(), listener.downloadBytes);

      int port = listener.port;
      if (port >= 0) {
        if (port != HTTP_PORT && port != HTTPS_PORT && port != 0) {
          // Group all other ports together into an "other" bucket.
          port = -1;
        }
        event.putInt(Names.PORT.name(), port);
      }

      if (tcpHandshakeMs >= 0) {
        event.putInt(Names.TCP_HANDSHAKE_MS.name(), tcpHandshakeMs);
      }

      int firstByteMs = listener.getFirstByteMs();
      if (firstByteMs >= 0) {
        event.putInt(Names.FIRST_BYTE_MS.name(), firstByteMs);
      }

      int durationMs = listener.getDurationMs();
      if (durationMs >= 0) {
        // Socket connection duration is in seconds.
        event.putInt(Names.DURATION.name(), durationMs / 1000);
      }

      FirebaseAnalytics.getInstance(context).logEvent(Names.BYTES.name(), event);
    }
  }

  /**
   * This class collects metadata about a proxy socket, including its duration, the amount
   * transferred, and whether it was terminated normally or by an error.
   *
   * It also replicates the wasStopped() and await() functions from Socks5Handler.StopListener.
   */
  private static class StatsListener implements PipeListener {
    // System clock start and stop time for this socket pair.
    long startTime = -1;
    long responseTime = -1;
    long stopTime = -1;

    // If non-null, the socket was closed with an error.
    Exception error = null;

    // Counters for upload and download total size and number of transfers.
    int uploadBytes = 0;
    int uploadCount = 0;
    int downloadBytes = 0;

    // Server port, for protocol identification.
    final int port;

    // Initial server response timeout.  -1 means disabled  (no timeout).
    final int httpsTimeoutMs;
    // This task is only non-null during the interval when we have sent the first flight of an
    // HTTPS connection and are waiting for a reply.
    TimerTask timeoutTask;
    boolean timeoutExceeded = false;

    // The upload buffer is only intended to hold the first flight from the client.
    // It is only used for HTTPS, and is null after the first flight.
    private static final int MAX_BUFFER = 1024;
    private ByteBuffer uploadBuffer = null;

    // Incorporate the functionality of StopListener in Socks5Handler.
    private final CountDownLatch latch = new CountDownLatch(1);
    private boolean stopped = false;

    /**
     * Blocks until onStop is called.
     * @throws InterruptedException if this thread is interrupted.
     */
    void await() throws InterruptedException {
      latch.await();
    }

    boolean wasStopped() {
      return stopped;
    }

    StatsListener(int port, int httpsTimeoutMs) {
      this.port = port;
      this.httpsTimeoutMs = httpsTimeoutMs;
      if (port == HTTPS_PORT) {
        uploadBuffer = ByteBuffer.allocateDirect(MAX_BUFFER);
      }
    }

    int getDurationMs() {
      if (startTime < 0 || stopTime < 0) {
        return -1;
      }
      return (int)(stopTime - startTime);
    }

    int getFirstByteMs() {
      if (startTime < 0 || responseTime < 0) {
        return -1;
      }
      return (int)(responseTime - startTime);
    }

    @Override
    public void onStart(Pipe pipe) {
      startTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void onStop(Pipe pipe) {
      stopTime = SystemClock.elapsedRealtime();
      stopTimeout();
      stopped = true;
      latch.countDown();
    }

    @Override
    public void onTransfer(Pipe pipe, byte[] buffer, int bufferLength) {
      if (stopped) {
        return;
      }
      if (DOWNLOAD.equals(pipe.getName())) {
        downloadBytes += bufferLength;
        // Free memory in uploadBuffer, since it's no longer needed.
        uploadBuffer = null;
        if (responseTime < 0) {
          responseTime = SystemClock.elapsedRealtime();
        }
        stopTimeout();
      } else {
        uploadBytes += bufferLength;
        if (uploadBuffer != null) {
          try {
            uploadBuffer.put(buffer, 0, bufferLength);
            // uploadBuffer != null implies that this is the first flight of an HTTPS connection,
            // which is the point at which we want to set a timeout.
            startTimeout(pipe);
          } catch (Exception e) {
            uploadBuffer = null;
          }
        }
        ++uploadCount;
      }
    }

    @Override
    public void onError(Pipe pipe, Exception exception) {
      this.error = exception;
    }

    private void startTimeout(final Pipe pipe) {
      if (httpsTimeoutMs < 0) {
        return;
      }

      // Get rid of any existing task.
      stopTimeout();

      final StatsListener listener = this;
      timeoutTask = new TimerTask() {
        @Override
        public void run() {
          timeoutExceeded = true;
          pipe.removePipeListener(listener);
          onError(pipe, new Exception(TIMEOUT_MESSAGE));
          onStop(pipe);
        }
      };
      TIMEOUTS.schedule(timeoutTask, httpsTimeoutMs);
    }

    private void stopTimeout() {
      TimerTask timeoutTask = this.timeoutTask;
      this.timeoutTask = null;
      if (timeoutTask != null) {
        timeoutTask.cancel();
      }
    }
  }

  /**
   * This function starts pipes for upload and download and blocks until one of them stops.
   * @param upload A pipe that has not yet been started
   * @param download A pipe that has not yet been started
   * @param port The remote server port number
   * @param httpsTimeoutMs How long to wait for a response from an HTTPS server.
   * @throws InterruptedException if this thread is interrupted.
   * @return A StatsListener containing final stats on the socket, which has now been closed.
   */
  private static StatsListener runPipes(final Pipe upload, final Pipe download, int port, int httpsTimeoutMs) throws InterruptedException {
    StatsListener listener = new StatsListener(port, httpsTimeoutMs);
    upload.addPipeListener(listener);
    download.addPipeListener(listener);

    // TODO: Propagate half-closed socket state correctly.  (Sockslib does not do this.)
    upload.start();
    download.start();

    // In normal operation, pipe.isRunning() and !listener.wasStopped() should be
    // equal.  However, they can be different if start() failed, or if there is a
    // bug in SocketPipe or StreamPipe.  Checking both ensures that there is no
    // possibility of a hang or busy-loop here.
    while (upload.isRunning() && download.isRunning() && !listener.wasStopped()) {
      listener.await();
    }
    return listener;
  }

  private int chooseLimit() {
    // Limit the first segment to 32-64 bytes and at most half of uploadBuffer.
    return MIN_SPLIT + (Math.abs(RANDOM.nextInt()) % (MAX_SPLIT + 1 - MIN_SPLIT));
  }

  /**
   * Retry a connection, splitting the initial segment.  Blocks until the new attempt succeeds or
   * fails.
   * @param upload A started pipe on which some data has already been transferred
   * @param download A new pipe on which no data has been transfered
   * @param socketUpload The OutputStream half of upload.
   * @param listener The listener from the previous (failed) proxy attempt.
   * @return A new StatsListener if the connection succeeded, or the old listener if it failed.
   * @throws InterruptedException if this thread is interrupted.
   */
  private StatsListener splitRetry(final Pipe upload, final Pipe download, final OutputStream socketUpload,
      StatsListener listener) throws InterruptedException {
    // Prepare an EARLY_RESET event to collect metrics on success rates for splitting:
    // - BYTES : Amount uploaded before reset
    // - CHUNKS : Number of upload writes before reset
    // - TIMEOUT : Whether the initial connection failed with a timeout.
    // - SPLIT : Number of bytes included in the first retry segment
    // - RETRY : 1 if retry succeeded, otherwise 0
    Bundle event = new Bundle();
    event.putInt(Names.BYTES.name(), listener.uploadBytes);
    event.putInt(Names.CHUNKS.name(), listener.uploadCount);
    event.putInt(Names.TIMEOUT.name(), listener.timeoutExceeded ? 1 : 0);

    try {

      // Send the first 32-64 bytes in the first packet.
      final int length = listener.uploadBuffer.position();
      // A typical offset is 4 bytes.
      final int offset = listener.uploadBuffer.arrayOffset();
      final int limit = chooseLimit();
      final int split = Math.min(limit, length / 2);
      // Record the split for performance analysis.
      event.putInt(Names.SPLIT.name(), split);
      // Send the first packet.
      socketUpload.write(listener.uploadBuffer.array(), offset, split);
      socketUpload.flush();

      // Send the remainder in a second packet.
      socketUpload.write(listener.uploadBuffer.array(), offset + split, length - split);

      listener = runPipes(upload, download, listener.port, -1);

      // Account for the retried segment.
      listener.uploadBytes += length;
      listener.uploadCount += 2;

      event.putInt(Names.RETRY.name(), listener.downloadBytes > 0 ? 1 : 0);
    } catch (IOException e) {
      // Retry failed.
      event.putInt(Names.RETRY.name(), 0);
    }
    FirebaseAnalytics.getInstance(context).logEvent(Names.EARLY_RESET.name(), event);
    return listener;
  }

  /**
   * Connects pipes like runPipes, but splits the first upstream segment.
   * @param upload A new pipe on which no data has been transferred
   * @param download A new pipe on which no data has been transfered
   * @param client The InputStream half of upload.
   * @param server The OutputStream half of upload.
   * @return A new StatsListener for the connected pipes.
   * @throws InterruptedException if this thread is interrupted.
   * @throws IOException if the first upstream transfer failed.
   */
  private StatsListener runWithImmediateSplit(final Pipe upload, final Pipe download,
      final InputStream client, final OutputStream server)
      throws InterruptedException, IOException {
    byte[] buf = new byte[chooseLimit()];

    // Transfer the first segment.
    int n = client.read(buf);
    server.write(buf, 0, n);

    // Let runPipes handle the rest.
    StatsListener listener = runPipes(upload, download, HTTPS_PORT, -1);

    // Account for the first segment.
    listener.uploadBytes += n;
    listener.uploadCount += 1;

    return listener;
  }
}
