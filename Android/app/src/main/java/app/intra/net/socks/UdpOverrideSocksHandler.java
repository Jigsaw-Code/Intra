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
class UdpOverrideSocksHandler extends Socks5Handler {
  // UDP server buffer size in bytes.
  protected static final int BUFFER_SIZE = 5 * 1024;
  // SOCKS protocol version
  protected static final int VERSION = 0x5;

  // DNS redirection information, used to move UDP packets from the nominal DNS server (port 53 on
  // some random IP address) to the true DNS server (a high-numbered port on localhost).
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

    // Reduce buffer size  (The default is 5 MB, which causes OOM crashes.)
    udpRelayServer.setBufferSize(BUFFER_SIZE);

    // Replace its datagram packet handler with one that will perform DNS address replacement.
    udpRelayServer.setDatagramPacketHandler(new DnsPacketHandler());

    // Start the server.
    InetSocketAddress socketAddress = (InetSocketAddress) udpRelayServer.start();

    // Inform the client where the server is located, by writing a success response on the TCP
    // socket used to establish the association.
    session.write(new CommandResponseMessage(VERSION, ServerReply.SUCCEEDED, InetAddress
        .getLocalHost(), socketAddress.getPort()));

    try {
      // The client should never send any more data on the control socket, so read() should hang
      // until the client closes the socket (returning -1) or this thread is interrupted (throwing
      // InterruptedIOException).
      int nextByte = session.getInputStream().read();
    } catch (IOException e) {
      // This is expected on a thread interrupt.
    }
    session.close();
    udpRelayServer.stop();
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
