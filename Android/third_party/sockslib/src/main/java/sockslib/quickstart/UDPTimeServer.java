package sockslib.quickstart;

import sockslib.utils.Arguments;
import sockslib.utils.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Date;

/**
 * @author Youchao Feng
 * @version 1.0
 * @date Sep 24, 2015 8:54 AM
 */
public class UDPTimeServer implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(UDPTimeServer.class);

  private int port = 5050;
  private String[] args;
  private Thread thread;
  private boolean stop = false;

  public static void main(@Nullable String[] args) {
    Timer.open();
    UDPTimeServer server = new UDPTimeServer();
    server.start(args);
  }

  public void start(@Nullable String[] args) {
    this.args = args;
    thread = new Thread(this);
    thread.setName("udp-time-server-thread");
    thread.start();
    stop = false;
  }

  public void shutdown() {
    try {
      DatagramSocket clientSocket = new DatagramSocket();
      String shutdownSignal = "shutdown";
      byte[] sendBuffer = shutdownSignal.getBytes();
      DatagramPacket packet =
          new DatagramPacket(sendBuffer, sendBuffer.length, new InetSocketAddress("localhost",
              port));
      clientSocket.send(packet);
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }
    stop = true;
    if (thread != null) {
      thread.interrupt();
    }
  }

  public void showHelp() {
    System.out.println("Usage: [Options]");
    System.out.println("    --port=<val>             UDP server Test port");
    System.out.println("    --always-response=<val>  Server always responses <val> to client");
    System.out.println("    -h or --help             Show help");
  }

  public void startInCurrentThread(String[] args) {
    this.args = args;
    run();
  }

  @Override
  public void run() {
    String alwaysResponse = null;
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
      logger.info("Starting UDP Time server...");
      DatagramSocket server = new DatagramSocket(port);
      logger.info("UDP Time server is created at port [{}]", server.getLocalPort());
      logger.info("This server will print client request message and response current server time");
      byte[] receiveBuffer = new byte[1024];
      DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
      while (!stop) {
        server.receive(receivePacket);
        String clientMessage = new String(receivePacket.getData(), 0, receivePacket.getLength());
        logger.info("Client[{}] send:[{}]", receivePacket.getSocketAddress(), clientMessage);
        if (clientMessage.equals("shutdown")) {
          break;
        }
        String responseMsg = new Date().toString();
        if (alwaysResponse != null) {
          responseMsg = alwaysResponse;
        }
        byte[] data = responseMsg.getBytes();
        DatagramPacket packet =
            new DatagramPacket(data, data.length, receivePacket.getAddress(), receivePacket
                .getPort());
        server.send(packet);
      }
      server.close();
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }
    logger.info("UDP time server shutdown");
  }
}
