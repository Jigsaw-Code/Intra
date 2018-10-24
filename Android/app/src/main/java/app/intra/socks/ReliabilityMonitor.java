package app.intra.socks;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import app.intra.util.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.analytics.FirebaseAnalytics.Param;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import sockslib.server.io.Pipe;
import sockslib.server.io.PipeListener;
import sockslib.server.io.SocketPipe;
import sockslib.server.listener.PipeInitializer;

/**
 * This is a pipe initializer that monitors post-connection socket reliability.
 */
public class ReliabilityMonitor implements PipeInitializer {
  private final Context context;

  ReliabilityMonitor(Context context) {
    this.context = context;
  }

  @Override
  public Pipe initialize(Pipe pipe) {
    pipe.addPipeListener(new ReliabilityListener(context));
    return pipe;
  }

  /**
   * This class converts pipe lifecycle callbacks into network reliability monitoring events.
   * Events:
   *  - BYTES event : value = total transfer over the lifetime of a socket
   *    - PORT : TCP port number (i.e. protocol type)
   *    - DURATION: socket lifetime in seconds
   *  - EARLY_RESET (only on HTTPS connections that are reset after sending and before receiving)
   *    - BYTES : Amount uploaded before reset
   *    - CHUNKS : Number of upload writes before reset
   */
  private static class ReliabilityListener implements PipeListener {
    private static final int HTTPS_PORT = 443;
    private static final int HTTP_PORT = 80;
    private static final String RESET_MESSAGE = "Connection reset";
    private final Context context;
    private int downloadBytes = 0;
    private int uploadBytes = 0;
    private int uploadCount = 0;
    private long startTime = -1;

    // The upload buffer is only intended to hold the first flight from the client.
    private static final int MAX_BUFFER = 1024;
    private ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(MAX_BUFFER);

    ReliabilityListener(Context context) {
      this.context = context;
    }

    private static Socket getRemoteSocket(Pipe pipe) {
      if (SocketPipe.INPUT_PIPE_NAME.equals(pipe.getName())) {
        return (Socket) pipe.getAttribute(SocketPipe.ATTR_SOURCE_SOCKET);
      } else if (SocketPipe.OUTPUT_PIPE_NAME.equals(pipe.getName())){
        return (Socket) pipe.getAttribute(SocketPipe.ATTR_DESTINATION_SOCKET);
      }
      return null;
    }

    private static SocketAddress getAddress(Pipe pipe) {
      final Socket remoteSocket = getRemoteSocket(pipe);
      if (remoteSocket == null) {
        return null;
      }
      return remoteSocket.getRemoteSocketAddress();
    }

    private static int getPort(Pipe pipe) {
      final Socket remoteSocket = getRemoteSocket(pipe);
      if (remoteSocket == null) {
        return -1;
      }
      return remoteSocket.getPort();
    }

    @Override
    public void onStart(Pipe pipe) {
      startTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void onStop(Pipe pipe) {
      FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(context);

      Bundle event = new Bundle();
      event.putInt(Param.VALUE, uploadBytes + downloadBytes);

      int port = getPort(pipe);
      if (port >= 0) {
        if (port != HTTP_PORT && port != HTTPS_PORT && port != 0) {
          // Group all other ports together into an "other" bucket.
          port = -1;
        }
        event.putInt(Names.PORT.name(), port);
      }

      if (startTime >= 0) {
        // Socket connection duration is in seconds.
        long duration = (SystemClock.elapsedRealtime() - startTime) / 1000;
        event.putInt(Names.DURATION.name(), (int)duration);
      }

      analytics.logEvent(Names.BYTES.name(), event);
    }

    @Override
    public void onTransfer(Pipe pipe, byte[] buffer, int bufferLength) {
      if (SocketPipe.INPUT_PIPE_NAME.equals(pipe.getName())) {
        downloadBytes += bufferLength;
        // Free memory in uploadBuffer, since it's no longer needed.
        uploadBuffer = null;
      } else {
        uploadBytes += bufferLength;
        if (uploadBuffer != null) {
          try {
            uploadBuffer.put(buffer, 0, bufferLength);
          } catch (Exception e) {
            uploadBuffer = null;
          }
        }
        ++uploadCount;
      }
    }

    @Override
    public void onError(Pipe pipe, Exception exception) {
      if (!RESET_MESSAGE.equals(exception.getMessage())) {
        // Currently we're only analyzing TCP RST failures.
        return;
      }
      if (uploadBytes == 0 || downloadBytes > 0) {
        // Only consider reset events received after first upload and before first download.
        return;
      }
      if (getPort(pipe) != HTTPS_PORT) {
        // Ignore non-HTTPS events.
        return;
      }
      Bundle event = new Bundle();
      event.putInt(Names.BYTES.name(), uploadBytes);
      event.putInt(Names.CHUNKS.name(), uploadCount);
      if (uploadBuffer != null) {
        new Thread(new SplitTest(context, getAddress(pipe), uploadBuffer, event)).start();
      }
    }
  }

  /**
   * Class for testing whether an unreachable server becomes reachable if we reduce the size of
   * the initial packet.
   */
  private static class SplitTest implements Runnable {
    private static final Random RANDOM = new Random();
    // Limit the first packet to 32-64 bytes (inclusive).
    private static final int MIN_SPLIT = 32, MAX_SPLIT = 64;
    private final Context context;
    private final SocketAddress dest;
    private final ByteBuffer uploadBuffer;
    private final Bundle event;

    SplitTest(Context context, SocketAddress dest, ByteBuffer uploadBuffer, Bundle event) {
      this.context = context;
      this.dest = dest;
      this.uploadBuffer = uploadBuffer;
      this.event = event;
    }

    /** @return The limit on the size of the first packet.  */
    private static int chooseLimit() {
      return MIN_SPLIT + RANDOM.nextInt() % (MAX_SPLIT + 1 - MIN_SPLIT);
    }

    private boolean retryTest(int limit) {
      Socket socket = new Socket();
      try {
        socket.setTcpNoDelay(true);
        socket.connect(dest);
        OutputStream out = socket.getOutputStream();

        // Send the first 32-64 bytes in the first packet.
        final int position = uploadBuffer.position();
        final int offset = uploadBuffer.arrayOffset();
        final int length = position - offset;
        final int split = Math.min(limit, length / 2);
        out.write(uploadBuffer.array(), offset, split);
        out.flush();

        // Send the remainder in a second packet.
        out.write(uploadBuffer.array(), offset + split, offset + length - split);
        final int firstByte = socket.getInputStream().read();

        // Test is complete.
        socket.close();

        // If we received any response from the server, the connection is considered successful.
        return firstByte >= 0;
      } catch (Exception e) {
        return false;
      }
    }

    @Override
    public void run() {
      final int limit = chooseLimit();
      final boolean retryWorks = retryTest(limit);  // This blocks on I/O.
      if (event != null && context != null) {
        event.putInt(Names.SPLIT.name(), limit);
        event.putInt(Names.RETRY.name(), retryWorks ? 1 : 0);
        FirebaseAnalytics.getInstance(context).logEvent(Names.EARLY_RESET.name(), event);
      }
    }
  }
}
