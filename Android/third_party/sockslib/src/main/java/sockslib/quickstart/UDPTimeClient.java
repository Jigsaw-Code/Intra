package sockslib.quickstart;

import sockslib.client.Socks5;
import sockslib.client.Socks5DatagramSocket;
import sockslib.common.UsernamePasswordCredentials;
import sockslib.common.net.MonitorDatagramSocketWrapper;
import sockslib.common.net.NetworkMonitor;
import sockslib.utils.Arguments;
import sockslib.utils.ResourceUtil;
import sockslib.utils.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * @author Youchao Feng
 * @version 1.0
 * @date Sep 24, 2015 9:53 AM
 */
public class UDPTimeClient {

  private static final Logger logger = LoggerFactory.getLogger(UDPTimeClient.class);

  public static void main(String[] args) {
    Timer.open();
    new UDPTimeClient().start(args);
  }

  public void start(@Nullable String[] args) {
    String host = "localhost";
    int port = 5050;
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

    DatagramSocket clientSocket = null;
    try {
      NetworkMonitor networkMonitor = new NetworkMonitor();
      if (useProxy) {
        Socks5 proxy = new Socks5(new InetSocketAddress(proxyHost, proxyPort));
        if (username != null && password != null) {
          proxy.setCredentials(new UsernamePasswordCredentials(username, password));
        }
        logger.info("Connect server [{}:{}] by proxy:{}", host, port, proxy);
        clientSocket = new Socks5DatagramSocket(proxy);
      } else {
        logger.info("Connect server [{}:{}]", host, port);
        clientSocket = new DatagramSocket();
      }
      clientSocket = new MonitorDatagramSocketWrapper(clientSocket, networkMonitor);
      byte[] sendBuffer = message.getBytes();
      DatagramPacket packet =
          new DatagramPacket(sendBuffer, sendBuffer.length, new InetSocketAddress(host, port));
      clientSocket.send(packet);

      //Received response message from UDP server.
      byte[] receiveBuf = new byte[100];
      DatagramPacket receivedPacket = new DatagramPacket(receiveBuf, receiveBuf.length);
      clientSocket.receive(receivedPacket);
      String receiveStr = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
      logger.info("Server response:{}", receiveStr);
      logger.info("Total Sent:{}, Total Received:{}, Total:{}", networkMonitor.getTotalSend(),
          networkMonitor.getTotalReceive(), networkMonitor.getTotal());

    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    } finally {
      ResourceUtil.close(clientSocket);
    }

  }

  public void showHelp() {
    System.out.println("Usage: [Options]");
    System.out.println("    --host=<val>            UDP time server host, default \"localhost\"");
    System.out.println("    --port=<val>            UDP time server port, default \"5050\"");
    System.out.println("    --proxy-host=<val>      Host of SOCKS5 proxy server");
    System.out.println("    --proxy-port=<val>      Port of SOCKS5 proxy server, default \"1080\"");
    System.out.println("    --proxy-user=<val>      Username of SOCKS5 proxy server");
    System.out.println("    --proxy-password=<val>  Password of SOCKS5 proxy server");
    System.out.println("    --message=<val>         The message which will send to server");
    System.out.println("    -h or --help            Show help");
  }

}
