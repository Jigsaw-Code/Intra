package app.intra.socks;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import sockslib.common.Socks5DatagramPacketHandler;
import sockslib.common.SocksException;
import sockslib.server.Session;
import sockslib.server.Socks5Handler;
import sockslib.server.UDPRelayServer;
import sockslib.server.msg.CommandMessage;
import sockslib.server.msg.CommandResponseMessage;
import sockslib.server.msg.ServerReply;

/**
 * This SocksHandler acts as a normal Socks5Handler, except that UDP packets to |fakeDns| are
 * actually redirected to |trueDns|.
 */
public class UdpOverrideSocksHandler extends Socks5Handler {
  private InetSocketAddress fakeDns = null;
  private InetSocketAddress trueDns = null;

  // This class is instantiated by a call to newInstance() in
  // BasicSocksProxyServer.createSocksHandler() (i.e. via the default constructor).
  // We can't provide it with the DNS information in the constructor, so we need a new method for
  // the purpose.

  /**
   * @param fakeDns Where the system thinks the DNS server is.  Typically has port 53.
   * @param trueDns Where the DNS server actually is.  Typically a high-numbered port on localhost.
   */
  void setDns(InetSocketAddress fakeDns, InetSocketAddress trueDns) {
    this.fakeDns = fakeDns;
    this.trueDns = trueDns;
  }

  @Override
  public void doUDPAssociate(Session session, CommandMessage commandMessage) throws IOException {
    // Construct a UDP relay server, just like upstream.
    UDPRelayServer udpRelayServer =
        new UDPRelayServer(((InetSocketAddress) session.getClientAddress()).getAddress(),
            commandMessage.getPort());

    // Set buffer size to 5 KB.  (The default is 5 MB, which causes OOM crashes.)
    final int BUFFER_SIZE = 5 * 1024;
    udpRelayServer.setBufferSize(BUFFER_SIZE);

    // Replace its datagram packet handler with one that will perform DNS address replacement.
    udpRelayServer.setDatagramPacketHandler(new DnsPacketHandler());

    // Start the server.
    InetSocketAddress socketAddress = (InetSocketAddress) udpRelayServer.start();

    // Inform the client where the server is located.
    final int VERSION = 0x5;
    session.write(new CommandResponseMessage(VERSION, ServerReply.SUCCEEDED, InetAddress
        .getLocalHost(), socketAddress.getPort()));

    // This polling loop matches upstream's implementation.  Evidently, an event-based solution is
    // not possible.
    final int IDLE_TIME_MS = 2000;
    while (udpRelayServer.isRunning()) {
      try {
        Thread.sleep(IDLE_TIME_MS);
      } catch (InterruptedException e) {
        session.close();
      }
      if (session.isClose()) {
        udpRelayServer.stop();
      }
    }
  }

  private class DnsPacketHandler extends Socks5DatagramPacketHandler {
    @Override
    public void decapsulate(DatagramPacket packet) throws SocksException {
      super.decapsulate(packet);
      if (packet.getSocketAddress().equals(fakeDns)) {
        packet.setSocketAddress(trueDns);
      }
    }

    @Override
    public DatagramPacket encapsulate(DatagramPacket packet, SocketAddress destination) throws
        SocksException {
      if (packet.getSocketAddress().equals(trueDns)) {
        packet.setSocketAddress(fakeDns);
      }
      return super.encapsulate(packet, destination);
    }
  }
}
