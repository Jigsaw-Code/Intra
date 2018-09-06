package sockslib.quickstart;

import sockslib.client.Socks5;
import sockslib.client.SocksSocket;
import sockslib.common.UsernamePasswordCredentials;
import sockslib.common.net.MonitorSocketWrapper;
import sockslib.common.net.NetworkMonitor;
import sockslib.utils.Arguments;
import sockslib.utils.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The class <code>TCPTimeClient</code> is client for {@link TCPTimeServer}.
 *
 * @author Youchao Feng
 * @version 1.0
 * @date Sep 24, 2015 2:45 PM
 */
public class TCPTimeClient {

  private static final Logger logger = LoggerFactory.getLogger(TCPTimeClient.class);

  public static void main(@Nullable String[] args) {
    new TCPTimeClient().start(args);
  }

  public void start(@Nullable String[] args) {
    Timer.open();
    String host = "localhost";
    int port = 5051;
    String proxyHost = null;
    int proxyPort = 1080;
    boolean useProxy = false;
    String username = null;
    String password = null;
    String message = "Hi, I am UDP client";

    if (args != null) {
      for (String arg : args) {
        if (arg.equals("-h") || arg.equals("--help")) {
          showHelp();
          System.exit(0);
        } else if (arg.startsWith("--proxy-host=")) {
          proxyHost = Arguments.valueOf(arg);
          useProxy = true;
        } else if (arg.startsWith("--proxy-port=")) {
          proxyPort = Arguments.intValueOf(arg);
        } else if (arg.startsWith("--proxy-user=")) {
          username = Arguments.valueOf(arg);
        } else if (arg.startsWith("--proxy-password=")) {
          password = Arguments.valueOf(arg);
        } else if (arg.startsWith("--host=")) {
          host = Arguments.valueOf(arg);
        } else if (arg.startsWith("--port=")) {
          port = Arguments.intValueOf(arg);
        } else if (arg.startsWith("--message=")) {
          message = Arguments.valueOf(arg);
        } else {
          logger.error("Unknown argument [{}]", arg);
          System.exit(-1);
        }
      }
    }
    if (useProxy && proxyHost == null) {
      logger.error("Please use [--proxy-host] to set proxy server's hostname if you want to use "
          + "SOCKS proxy");
      System.exit(-1);
    }
    try {
      Socket socket = null;
      if (useProxy) {
        Socks5 proxy = new Socks5(new InetSocketAddress(proxyHost, proxyPort));
        if (username != null && password != null) {
          proxy.setCredentials(new UsernamePasswordCredentials(username, password));
        }
        logger.info("Connect server [{}:{}] by proxy:{}", host, port, proxy);
        socket = new SocksSocket(proxy, host, port);
      } else {
        socket = new Socket(host, port);
      }
      NetworkMonitor monitor = new NetworkMonitor();
      socket = new MonitorSocketWrapper(socket, monitor);
      InputStream inputStream = socket.getInputStream();
      OutputStream outputStream = socket.getOutputStream();
      String sendMessage = "Hi, I am TCP time client";
      if (message != null) {
        sendMessage = message;
      }
      sendMessage += "\n";
      outputStream.write(sendMessage.getBytes());
      outputStream.flush();
      byte[] buffer = new byte[1045 * 5];
      int length = inputStream.read(buffer);
      String receiveMessage = new String(buffer, 0, length);
      logger.info("Server response:{}", receiveMessage);
      inputStream.close();
      outputStream.close();
      socket.close();
      logger.info("Total send:{}, Total receive:{}, Total:{}", monitor.getTotalSend(), monitor
          .getTotalReceive(), monitor.getTotal());
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }

  }

  public void showHelp() {
    String a = "1";
    System.out.println("Usage: [Options]");
    System.out.println("    --host=<val>            TCP time server host, default \"localhost\"");
    System.out.println("    --port=<val>            TCP time server port, default \"5051\"");
    System.out.println("    --proxy-host=<val>      Host of SOCKS5 proxy server");
    System.out.println("    --proxy-port=<val>      Port of SOCKS5 proxy server, default \"1080\"");
    System.out.println("    --proxy-user=<val>      Username of SOCKS5 proxy server");
    System.out.println("    --proxy-password=<val>  Password of SOCKS5 proxy server");
    System.out.println("    --message=<val>         The message which will send to server");
    System.out.println("    -h or --help            Show help");
  }
}
