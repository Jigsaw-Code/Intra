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
package app.intra.net.split;

import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import app.intra.net.VpnAdapter;
import app.intra.net.dns.DnsUdpQuery;
import app.intra.net.doh.Resolver;
import app.intra.net.doh.ResponseWriter;
import app.intra.net.doh.Transaction;
import app.intra.sys.IntraVpnService;
import com.crashlytics.android.Crashlytics;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Implements a split-tunnel VPN that only receives DNS traffic.  All other traffic skips the VPN.
 * Reads DNS requests over UDP from |tunFd| and forwards them to a DNS over HTTPS server using
 * Resolver and ServerConnection.  When responses arrive, it writes them back to the
 * tun device and updates the connection status in DnsVpnServiceController.
 */
public class SplitVpnAdapter extends VpnAdapter implements ResponseWriter {
  private static final String LOG_TAG = "SplitVpnAdapter";

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

  // VPN parameters
  // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193.
  private static final String IPV6_SUBNET = "fd66:f83a:c650::%s";

  // Misc constants
  private static final int SLEEP_MILLIS = 1500;

  private final @NonNull
  IntraVpnService vpnService;
  @NonNull private final ParcelFileDescriptor tunFd;
  private final FileInputStream in;
  private final FileOutputStream out;

  public static SplitVpnAdapter establish(IntraVpnService vpnService) {
    ParcelFileDescriptor tunFd = establishVpn(vpnService);
    if (tunFd == null) {
      return null;
    }
    return new SplitVpnAdapter(vpnService, tunFd);
  }

  private SplitVpnAdapter(@NonNull IntraVpnService vpnService, ParcelFileDescriptor tunFd) {
    super(LOG_TAG);
    this.vpnService = vpnService;
    this.tunFd = tunFd;
    in = new FileInputStream(tunFd.getFileDescriptor());
    out = new FileOutputStream(tunFd.getFileDescriptor());
  }

  private static ParcelFileDescriptor establishVpn(IntraVpnService vpnService) {
    PrivateAddress privateIpv6Address = new PrivateAddress(IPV6_SUBNET, 120);
    PrivateAddress privateIpv4Address = selectPrivateAddress();
    if (privateIpv4Address == null) {
      Crashlytics.log(
          Log.ERROR, LOG_TAG, "Unable to find a private address on which to establish a VPN.");
      return null;
    }
    Log.i(LOG_TAG, String.format("VPN address: { IPv4: %s, IPv6: %s }",
        privateIpv4Address, privateIpv6Address));

    final String establishVpnErrorMsg = "Failed to establish VPN ";
    ParcelFileDescriptor tunFd = null;
    try {
      VpnService.Builder builder = vpnService.newBuilder()
              .setSession("Intra split-tunnel VPN")
              .setMtu(VPN_INTERFACE_MTU)
              .addAddress(privateIpv4Address.address, privateIpv4Address.prefix)
              .addRoute(privateIpv4Address.subnet, privateIpv4Address.prefix)
              .addDnsServer(privateIpv4Address.router)
              .addAddress(privateIpv6Address.address, privateIpv6Address.prefix)
              .addRoute(privateIpv6Address.subnet, privateIpv6Address.prefix)
              .addDnsServer(privateIpv6Address.router);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // Only available in API >= 21
        builder = builder.setBlocking(true);
      }
      tunFd = builder.establish();
    } catch (IllegalArgumentException e) {
      Crashlytics.logException(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (SecurityException e) {
      Crashlytics.logException(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (IllegalStateException e) {
      Crashlytics.logException(e);
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    }

    return tunFd;
  }

  private static class PrivateAddress {
    final String address;
    final String subnet;
    final String router;
    final int prefix;

    PrivateAddress(String addressFormat, int subnetPrefix) {
      subnet = String.format(addressFormat, "0");
      address = String.format(addressFormat, "1");
      router = String.format(addressFormat, "2");
      prefix = subnetPrefix;
    }

    @Override
    public String toString() {
      return String.format("{ subnet: %s, address: %s, router: %s, prefix: %d }",
          subnet, address, router, prefix);
    }
  }

  private static PrivateAddress selectPrivateAddress() {
    // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
    // which private address range isn't in use.
    HashMap<String, PrivateAddress> candidates = new HashMap<>();
    candidates.put("10", new PrivateAddress("10.0.0.%s", 8));
    candidates.put("172", new PrivateAddress("172.16.0.%s", 12));
    candidates.put("192", new PrivateAddress("192.168.0.%s", 16));
    candidates.put("169", new PrivateAddress("169.254.1.%s", 24));
    List<NetworkInterface> netInterfaces;
    try {
      netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
    } catch (SocketException e) {
      Crashlytics.logException(e);
      e.printStackTrace();
      return null;
    }

    for (NetworkInterface netInterface : netInterfaces) {
      for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses())) {
        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
          String ipAddress = inetAddress.getHostAddress();
          if (ipAddress.startsWith("10.")) {
            candidates.remove("10");
          } else if (ipAddress.length() >= 6
              && ipAddress.substring(0, 6).compareTo("172.16") >= 0
              && ipAddress.substring(0, 6).compareTo("172.31") <= 0) {
            candidates.remove("172");
          } else if (ipAddress.startsWith("192.168")) {
            candidates.remove("192");
          }
        }
      }
    }

    if (candidates.size() > 0) {
      return candidates.values().iterator().next();
    }

    return null;
  }

  @Override
  // This thread reads DNS requests from the VPN interface and forwards them via |serverConnection|.
  public void run() {
    Crashlytics.log(Log.DEBUG, LOG_TAG, "Query thread starting");
    if (tunFd == null) {
      // This check is necessary due to a race, where the VPN has been closed and the device regains
      // network connectivity before the service stops. As a result the TUN file descriptor is null
      // at the time the resolver is created.
      Crashlytics.log(Log.WARN, LOG_TAG, "VPN/TUN file descriptor is null");
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
            Crashlytics.log(Log.ERROR, LOG_TAG, "Failed to read from tun interface.");
            Crashlytics.logException(e);
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
          Crashlytics.log(Log.WARN, LOG_TAG, "Received malformed IP packet.");
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
          Crashlytics.log(Log.WARN, LOG_TAG, "Received malformed IP packet: " + e.getMessage());
          continue;
        }
        byte protocol = ipPacket.getProtocol();
        if (protocol != UDP_PROTOCOL) {
          Crashlytics.log(Log.WARN, LOG_TAG, getProtocolErrorMessage(protocol));
          continue;
        }

        UdpPacket udpPacket = new UdpPacket(ByteBuffer.wrap(ipPacket.getPayload()));
        if (udpPacket.destPort != DNS_DEFAULT_PORT) {
          Crashlytics.log(Log.WARN, LOG_TAG, "Received non-DNS UDP packet");
          continue;
        }

        if (udpPacket.length == 0) {
          Crashlytics.log(Log.INFO, LOG_TAG, "Received interrupt UDP packet.");
          continue;
        }

        DnsUdpQuery dnsRequest = DnsUdpQuery.fromUdpBody(udpPacket.data);
        if (dnsRequest == null) {
          Crashlytics.log(Log.ERROR, LOG_TAG, "Failed to parse DNS request");
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

        Resolver.processQuery(vpnService.getServerConnection(),
            dnsRequest, udpPacket.data, this);
      } catch (Exception e) {
        if (!isInterrupted()) {
          Crashlytics.log(Log.WARN, LOG_TAG, "Unexpected exception in UDP loop.");
          Crashlytics.logException(e);
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
  public void sendResult(DnsUdpQuery dnsUdpQuery, Transaction transaction) {
    if (transaction.response != null) {
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
        Crashlytics.log(Log.ERROR, LOG_TAG, "Failed to write to VPN/TUN interface.");
        Crashlytics.logException(e);
        transaction.status = Transaction.Status.INTERNAL_ERROR;
      }
    }

    vpnService.recordTransaction(transaction);
  }

  @Override
  public void close() {
    try {
      tunFd.close();
    } catch (IOException e) {
      Crashlytics.logException(e);
    }
  }
}
