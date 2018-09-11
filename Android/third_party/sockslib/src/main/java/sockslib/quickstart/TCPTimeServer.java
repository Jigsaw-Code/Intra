package sockslib.quickstart;

import sockslib.client.SocksProxy;
import sockslib.utils.Arguments;
import sockslib.utils.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;

/**
 * @author Youchao Feng
 * @version 1.0
 * @date Sep 24, 2015 2:21 PM
 */
public class TCPTimeServer implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(TCPTimeServer.class);
  private static final int DEFAULT_PORT = 5051;

  private String[] args;
  private boolean stop = false;
  private Thread thread;
  private ServerSocket server;

  public static void main(@Nullable String[] args) {
    Timer.open();
    TCPTimeServer server = new TCPTimeServer();
    server.start(args);
  }

  public void start(@Nullable String[] args) {
    this.args = args;
    stop = false;
    thread = new Thread(this);
    thread.start();
  }

  public void shutdown() {
    stop = true;
    if (thread != null) {
      thread.interrupt();
    }
    if (server != null && server.isBound()) {
      try {
        server.close();
      } catch (IOException e) {
        logger.error(e.getMessage(), e);
      }
    }
  }

  public void showHelp() {
    System.out.println("Usage: [Options]");
    System.out.println("    --port=<val>             UDP server Test port");
    System.out.println("    --always-response=<val>  Server always responses <val> to client");
    System.out.println("    -h or --help             Show help");
  }

  @Override
  public void run() {
    int port = DEFAULT_PORT;
    SocksProxy socksProxy = null;
    String alwaysResponse = null;
    String proxyHost = null;
    String proxyUsername = null;
    String proxyPassword = null;
    if (args != null) {
      for (String arg : args) {
        if (arg.equals("-h") || arg.equals("--help")) {
          showHelp();
          System.exit(0);
        } else if (arg.startsWith("--port=")) {
          try {
            port = Arguments.intValueOf(arg);
          } catch (NumberFormatException e) {
            logger.error("Value of [--port] should be a number");
            System.exit(-1);
          }
        } else if (arg.startsWith("--always-response=")) {
          alwaysResponse = Arguments.valueOf(arg);
        } else {
          logger.error("Unknown argument [{}]", arg);
          return;
        }
      }
    }

    try {
      server = new ServerSocket(port);
      logger.info("TCP time server created at {}", server.getInetAddress(), port);
      while (!stop) {
        Socket socket = server.accept();
        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int b;
        while ((b = (byte) inputStream.read()) != -1) {
          if (b == '\n') {
            break;
          }
          byteArrayOutputStream.write(b);
        }
        byte[] buffer = byteArrayOutputStream.toByteArray();
        String receive = new String(buffer);
        logger.info("Client from {} send:{}", socket.getRemoteSocketAddress(), receive);
        if (receive.equals("shutdown")) {
          break;
        }
        String response = new Date().toString();
        if (alwaysResponse != null) {
          response = alwaysResponse;
        }
        response += "\n";
        outputStream.write(response.getBytes());
        outputStream.flush();
        inputStream.close();
        outputStream.close();
        socket.close();
      }
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }
  }
}
