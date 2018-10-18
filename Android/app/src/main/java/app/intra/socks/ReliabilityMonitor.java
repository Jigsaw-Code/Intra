package app.intra.socks;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import app.intra.util.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.analytics.FirebaseAnalytics.Param;
import java.net.Socket;
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

    ReliabilityListener(Context context) {
      this.context = context;
    }

    private static int getPort(Pipe pipe) {
      final Socket remoteSocket;
      if (SocketPipe.INPUT_PIPE_NAME.equals(pipe.getName())) {
        remoteSocket = (Socket) pipe.getAttribute(SocketPipe.ATTR_SOURCE_SOCKET);
      } else if (SocketPipe.OUTPUT_PIPE_NAME.equals(pipe.getName())){
        remoteSocket = (Socket) pipe.getAttribute(SocketPipe.ATTR_DESTINATION_SOCKET);
      } else {
        remoteSocket = null;
      }
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
      } else {
        uploadBytes += bufferLength;
        ++uploadCount;
      }
    }

    @Override
    public void onError(Pipe pipe, Exception exception) {
      if (!RESET_MESSAGE.equals(exception.getMessage())) {
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
      FirebaseAnalytics.getInstance(context).logEvent(Names.EARLY_RESET.name(), event);
    }
  }
}
