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

import android.util.Log;
import app.intra.net.dns.DnsUdpPacket;
import app.intra.net.doh.Resolver;
import app.intra.net.doh.ResponseWriter;
import app.intra.net.doh.Transaction;
import app.intra.sys.IntraVpnService;
import app.intra.sys.LogWrapper;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Implements a DNS resolver that binds to an arbitrary port on localhost and forwards queries
 * over a ServerConnection.  Call get() to acquire one, then start() to start it listening and
 * shutdown() to terminate it.
 */
class LocalhostResolver extends Thread implements ResponseWriter {
  private static final String LOG_TAG = "LocalhostResolver";
  private static final int MAX_SIZE = 4096;

  private final DatagramSocket socket;
  private final IntraVpnService vpnService;

  /**
   * Get a localhost resolver, bound to a port and ready to be started by calling start().
   * @param vpnService The VPN service that owns this server.
   * @return The localhost resolver, or null if it couldn't be started.
   */
  static LocalhostResolver get(IntraVpnService vpnService) {
    DatagramSocket socket = null;
    try {
      InetAddress localhost = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
      SocketAddress bindaddr = new InetSocketAddress(localhost, 0);
      socket = new DatagramSocket(bindaddr);
    } catch (UnknownHostException e) {
      LogWrapper.report(e);
      return null;
    } catch (SocketException e) {
      LogWrapper.report(e);
      return null;
    }
    return new LocalhostResolver(vpnService, socket);
  }

  private LocalhostResolver(IntraVpnService vpnService, DatagramSocket socket) {
    this.vpnService = vpnService;
    this.socket = socket;
  }

  @Override
  public void run() {
    byte[] buffer = new byte[MAX_SIZE];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

    while (receive(packet)) {
      byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getLength());
      DnsUdpPacket udpQuery = DnsUdpPacket.fromUdpBody(data);
      if (udpQuery == null) {
        LogWrapper.logcat(Log.ERROR, LOG_TAG, "Failed to parse DNS request");
        continue;
      }
      Log.d(
          LOG_TAG,
          "NAME: "
              + udpQuery.dns.getQuestion().name
              + " ID: "
              + udpQuery.dns.getId()
              + " TYPE: "
              + udpQuery.dns.getQuestion().qtype);

      udpQuery.sourceAddress = packet.getAddress();
      udpQuery.destAddress = socket.getLocalAddress();
      udpQuery.sourcePort = (short)packet.getPort();
      udpQuery.destPort = (short)socket.getLocalPort();

      Resolver.processQuery(vpnService.getServerConnection(), udpQuery, this);
    }
  }

  InetSocketAddress getAddress() {
    return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
  }

  /**
   * Block until a packet arrives, which will be written into |packet|.
   * @param packet The destination for an incoming packet.
   * @return False if the socket is closed.
   */
  private boolean receive(DatagramPacket packet) {
    try {
      socket.receive(packet);
      return true;
    } catch (IOException e) {
      Log.i(LOG_TAG, "Read failed", e);
      return false;
    }
  }

  @Override
  public void sendResult(DnsUdpPacket query, Transaction transaction) {
    if (transaction.status == Transaction.Status.COMPLETE &&
        transaction.response != null) {
      // Construct a reply to the query's source port.
      DatagramPacket responsePacket = new DatagramPacket(transaction.response,
          transaction.response.length, query.sourceAddress, query.sourcePort & 0xFFFF);
      try {
        socket.send(responsePacket);
      } catch (IOException e) {
        LogWrapper.report(e);
        transaction.status = Transaction.Status.INTERNAL_ERROR;
      }
    }

    vpnService.recordTransaction(transaction);
  }

  void shutdown() {
    // This will cause receive() to return false, thus exiting the main loop.
    socket.close();
  }
}
