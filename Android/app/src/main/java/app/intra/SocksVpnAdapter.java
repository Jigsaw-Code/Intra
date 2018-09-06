package app.intra;

import com.google.firebase.crash.FirebaseCrash;

import android.annotation.TargetApi;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import net.sourceforge.jsocks.Authentication;
import net.sourceforge.jsocks.AuthenticationNone;
import net.sourceforge.jsocks.ProxyServer;
import net.sourceforge.jsocks.Socks5Proxy;
import net.sourceforge.jsocks.server.ServerAuthenticatorNone;

import org.outline.tun2socks.Tun2SocksJni;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Random;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.intra.util.LogWrapper;
import app.intra.util.SafeTun2Socks;

public class SocksVpnAdapter extends VpnAdapter {
  private static final String LOG_TAG = "SocksVpnAdapter";


  private static final String IPV4_PRIVATE_LAN = "10.111.222.%s";
  private static final int IPV4_PREFIX_LENGTH = 24;
  private static final String IPV4_NETMASK = "255.255.255.0";
  private static final int MTU = 32767;
  private static final int TRANSPARENT_DNS_ENABLED = 1;
  private static final int SOCKS5_UDP_ENABLED = 1;

  private LocalhostResolver resolver;
  private ProxyServer proxy;
  private ParcelFileDescriptor tunFd;


  private boolean stopping = false;

  static SocksVpnAdapter get(@NonNull DnsVpnService vpnService) {
    LocalhostResolver resolver = LocalhostResolver.get(vpnService);
    if (resolver == null) {
      return null;
    }
    ParcelFileDescriptor tunFd = establishVpn(vpnService);
    if (tunFd == null) {
      return null;
    }
    return new SocksVpnAdapter(tunFd, resolver);
  }

  private SocksVpnAdapter(ParcelFileDescriptor tunFd, LocalhostResolver resolver) {
    super(LOG_TAG);
    this.resolver = resolver;
    this.tunFd = tunFd;
    proxy = new ProxyServer(new ServerAuthenticatorNone());
  }

  @Override
  public void run() {
    // Proxy parameters
    final int BACKLOG = 5;  // This is jsocks' default backlog.
    final InetAddress localhost;
    try {
      localhost = InetAddress.getByName("127.0.0.1");
    } catch (UnknownHostException e) {
      LogWrapper.report(e);
      return;
    }

    // VPN parameters
    final String resolverAddress = "127.0.0.1:" + resolver.getAddress().getPort();
    final String ipv4Router = String.format(Locale.ROOT, IPV4_PRIVATE_LAN, "2");

    // IANA ephemeral port range.
    final int PORT_MIN = 49152, PORT_MAX = 65536;
    final Random random = new Random();

    // This design is necessary because (1) proxy.start() blocks on success, (2) it provides no
    // indication of success or failure, and (3) it will fail if the chosen port is in use.
    // To solve this, we start tun2socks before the proxy, and assume that proxy.start() has failed
    // unless isInterrupted() returns true.
    while (!stopping) {
      final int port = PORT_MIN + random.nextInt(PORT_MAX - PORT_MIN);
      final String proxyAddress = "127.0.0.1:" + port;

      SafeTun2Socks tun2Socks = new SafeTun2Socks(tunFd, MTU, ipv4Router, IPV4_NETMASK,
          proxyAddress, proxyAddress, resolverAddress, TRANSPARENT_DNS_ENABLED, SOCKS5_UDP_ENABLED);

      // TODO: Address race condition if close() runs at this point.
      proxy.start(port, BACKLOG, localhost);
      // When proxy.start() returns, either (1) someone called proxy.stop, or (2) the port was in
      // use.  Either way, we have to shut down Tun2Socks.  close() calls interrupt() before
      // proxy.stop(), so once it is called we will not go through this loop again.
      tun2Socks.stop();
    }
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private static ParcelFileDescriptor establishVpn(VpnService vpnService) {
    String gatewayIp = String.format(Locale.ROOT, IPV4_PRIVATE_LAN, "1");
    String dnsIp = String.format(Locale.ROOT, IPV4_PRIVATE_LAN, "3");
    try {
      return vpnService.new Builder()
          .setSession("Intra tun2socks VPN")
          .setMtu(MTU)
          .addAddress(gatewayIp, IPV4_PREFIX_LENGTH)
          .addRoute("0.0.0.0", 0)
          .addDnsServer(dnsIp)
          .addDisallowedApplication(vpnService.getPackageName())
          .establish();
    } catch (Exception e) {
      FirebaseCrash.report(e);
      return null;
    }
  }

  @Override
  void close() {
    // The run() method relies on stopping being set to true before any call to proxy.stop.
    stopping = true;
    proxy.stop();
    // After the proxy stops, the run() method will stop Tun2Socks, and then complete.
    try {
      join();
    } catch (InterruptedException e) {
      // This is weird: the calling thread has _also_ been interrupted.
      LogWrapper.report(e);
    }

    try {
      tunFd.close();
    } catch (IOException e) {
      LogWrapper.report(e);
    }
  }
}
