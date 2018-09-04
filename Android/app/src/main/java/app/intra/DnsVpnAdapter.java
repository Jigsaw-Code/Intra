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
package app.intra;

import com.google.firebase.crash.FirebaseCrash;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.intra.util.DnsTransaction;
import app.intra.util.DnsUdpQuery;
import app.intra.util.IpPacket;
import app.intra.util.Ipv4Packet;
import app.intra.util.Ipv6Packet;
import app.intra.util.UdpPacket;

/**
 * Reads DNS requests over UDP from|tunFd| and forwards them to a DNS over HTTPS server using
 * DnsResolverUdpToHttps and ServerConnection.  When responses arrive, it writes them back to the
 * tun device and updates the connection status in DnsVpnServiceController.
 */
public class DnsVpnAdapter extends Thread implements DnsResponseWriter {
  private static final String LOG_TAG = "DnsVpnAdapter";

  // IP constants
  private static final int IP_MIN_HEADER_LENGTH = 20;
  private static final byte IPV4_VERSION = 4;

  // ICMP constants
  private static final byte ICMP_PROTOCOL = 1;
  private static final byte ICMP_IPV6_PROTOCOL = 58;

  // TCP constants
  private static final byte TCP_PROTOCOL = 6;

  // UDP constants
  private static final int UDP_MAX_DATAGRAM_LEN = 32767;
  private static final byte UDP_PROTOCOL = 17;

  private static final int DNS_DEFAULT_PORT = 53;

  // Misc constants
  private static final int SLEEP_MILLIS = 1500;

  private final @NonNull
  DnsVpnService vpnService;
  private final ParcelFileDescriptor tunFd;
  private final FileInputStream in;
  private final FileOutputStream out;

  // Owners can safely mutate serverConnection at any time, including setting it to null.
  @Nullable
  ServerConnection serverConnection = null;

  DnsVpnAdapter(@NonNull DnsVpnService vpnService, ParcelFileDescriptor tunFd,
                        ServerConnection serverConnection) {
    super(LOG_TAG);
    this.vpnService = vpnService;
    this.tunFd = tunFd;
    this.serverConnection = serverConnection;

    in = new FileInputStream(tunFd.getFileDescriptor());
    out = new FileOutputStream(tunFd.getFileDescriptor());
  }

  @Override
  // This thread reads DNS requests from the VPN interface and forwards them via |serverConnection|.
  public void run() {
    FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Query thread starting");
    if (tunFd == null) {
      // This check is necessary due to a race, where the VPN has been closed and the device regains
      // network connectivity before the service stops. As a result the TUN file descriptor is null
      // at the time the resolver is created.
      FirebaseCrash.logcat(Log.WARN, LOG_TAG, "VPN/TUN file descriptor is null");
      return;
    }

    ByteBuffer buffer = ByteBuffer.allocate(UDP_MAX_DATAGRAM_LEN);

    while (!isInterrupted()) {
      try {
        buffer.clear(); // Reset buffer before reading.
        int length;
        try {
          length = in.read(buffer.array());
        } catch (IOException e) {
          if (!isInterrupted()) {
            FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to read from tun interface.");
            FirebaseCrash.report(e);
          }
          return;
        }
        if (length <= 0) {
          // This should only happen on API < 21, where tunFd is nonblocking.
          try {
            Thread.sleep(SLEEP_MILLIS);
          } catch (InterruptedException e) {
            return;
          }
          continue;
        }
        if (length < IP_MIN_HEADER_LENGTH) {
          FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Received malformed IP packet.");
          continue;
        }
        buffer.limit(length);

        byte version = IpPacket.getIpVersion(buffer);
        Log.d(LOG_TAG, String.format("Received IPv%d packet", version));
        IpPacket ipPacket;
        try {
          if (version == IPV4_VERSION) {
            ipPacket = new Ipv4Packet(buffer);
          } else {
            ipPacket = new Ipv6Packet(buffer);
          }
        } catch (IllegalArgumentException e) {
          FirebaseCrash
              .logcat(Log.WARN, LOG_TAG, "Received malformed IP packet: " + e.getMessage());
          continue;
        }
        byte protocol = ipPacket.getProtocol();
        if (protocol != UDP_PROTOCOL) {
          FirebaseCrash.logcat(Log.WARN, LOG_TAG, getProtocolErrorMessage(protocol));
          continue;
        }

        UdpPacket udpPacket = new UdpPacket(ByteBuffer.wrap(ipPacket.getPayload()));
        if (udpPacket.destPort != DNS_DEFAULT_PORT) {
          FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Received non-DNS UDP packet");
          continue;
        }

        if (udpPacket.length == 0) {
          FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Received interrupt UDP packet.");
          continue;
        }

        DnsUdpQuery dnsRequest = DnsUdpQuery.fromUdpBody(udpPacket.data);
        if (dnsRequest == null) {
          FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to parse DNS request");
          continue;
        }
        Log.d(
            LOG_TAG,
            "NAME: "
                + dnsRequest.name
                + " ID: "
                + dnsRequest.requestId
                + " TYPE: "
                + dnsRequest.type);

        dnsRequest.sourceAddress = ipPacket.getSourceAddress();
        dnsRequest.destAddress = ipPacket.getDestAddress();
        dnsRequest.sourcePort = udpPacket.sourcePort;
        dnsRequest.destPort = udpPacket.destPort;

        DnsResolverUdpToHttps.processQuery(serverConnection, dnsRequest, udpPacket.data, this);
      } catch (Exception e) {
        if (!isInterrupted()) {
          FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Unexpected exception in UDP loop.");
          FirebaseCrash.report(e);
        }
      }
    }
  }


  // Returns an error string for unexpected, non-UDP protocols.
  private String getProtocolErrorMessage(byte protocol) {
    String msg;
    switch (protocol) {
      case ICMP_IPV6_PROTOCOL:
      case ICMP_PROTOCOL:
        msg = "Received ICMP packet.";
        break;
      case TCP_PROTOCOL:
        msg = "Received TCP packet.";
        break;
      default:
        msg = String.format("Received non-UDP IP packet: %d", protocol);
        break;
    }
    return msg;
  }

  @Override
  public void sendResult(DnsUdpQuery dnsUdpQuery, DnsTransaction transaction) {
    // Construct a reply to the query's source port by switching the source and destination.
    UdpPacket udpResponsePacket =
        new UdpPacket(dnsUdpQuery.destPort, dnsUdpQuery.sourcePort, transaction.response);
    byte[] rawUdpResponse = udpResponsePacket.getRawPacket();

    IpPacket ipPacket;
    if (dnsUdpQuery.sourceAddress instanceof Inet4Address) {
      ipPacket = new Ipv4Packet(
          UDP_PROTOCOL,
          dnsUdpQuery.destAddress,
          dnsUdpQuery.sourceAddress,
          rawUdpResponse);
    } else {
      ipPacket = new Ipv6Packet(
          UDP_PROTOCOL,
          dnsUdpQuery.destAddress,
          dnsUdpQuery.sourceAddress,
          rawUdpResponse);
    }

    byte[] rawIpResponse = ipPacket.getRawPacket();
    try {
      out.write(rawIpResponse);
    } catch (IOException e) {
      FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Failed to write to VPN/TUN interface.");
      FirebaseCrash.report(e);
      transaction.status = DnsTransaction.Status.INTERNAL_ERROR;
    }

    vpnService.recordTransaction(transaction);
  }
}
