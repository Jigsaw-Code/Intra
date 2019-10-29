/*
Copyright 2019 Jigsaw Operations LLC

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
package app.intra.net.go;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import app.intra.net.VpnAdapter;
import app.intra.net.doh.ServerConnectionFactory;
import app.intra.net.go.TLSProbe.Result;
import app.intra.sys.IntraVpnService;
import app.intra.sys.LogWrapper;
import app.intra.sys.PersistentState;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import doh.Transport;
import java.io.IOException;
import java.util.Locale;
import tunnel.IntraTunnel;
import tun2socks.Tun2socks;

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter extends VpnAdapter {
  private static final String LOG_TAG = "GoVpnAdapter";

  // IPv4 VPN constants
  private static final String IPV4_TEMPLATE = "10.111.222.%d";
  private static final int IPV4_PREFIX_LENGTH = 24;

  // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
  // assign the following values to the final byte of an address within a subnet.
  private enum LanIp {
    GATEWAY(1), ROUTER(2), DNS(3);

    // Value of the final byte, to be substituted into the template.
    private final int value;

    LanIp(int value) {
      this.value = value;
    }

    String make(String template) {
      return String.format(Locale.ROOT, template, value);
    }
  }

  // Service context in which the VPN is running.
  private final IntraVpnService vpnService;

  // DNS resolver running on localhost.
  private final LocalhostResolver resolver;

  // TUN device representing the VPN.
  private final ParcelFileDescriptor tunFd;

  // The Intra session object from go-tun2socks.  Initially null.
  private IntraTunnel tunnel;
  private GoIntraListener listener;

  public static GoVpnAdapter establish(@NonNull IntraVpnService vpnService) {
    LocalhostResolver resolver = LocalhostResolver.get(vpnService);
    if (resolver == null) {
      return null;
    }
    ParcelFileDescriptor tunFd = establishVpn(vpnService);
    if (tunFd == null) {
      return null;
    }
    return new GoVpnAdapter(vpnService, tunFd, resolver);
  }

  private GoVpnAdapter(IntraVpnService vpnService, ParcelFileDescriptor tunFd, LocalhostResolver resolver) {
    this.vpnService = vpnService;
    this.resolver = resolver;
    this.tunFd = tunFd;
  }

  @Override
  public synchronized void start() {
    resolver.start();

    // VPN parameters
    final String fakeDnsIp = LanIp.DNS.make(IPV4_TEMPLATE);
    final String fakeDns = fakeDnsIp + ":" + DNS_DEFAULT_PORT;


    // Strip leading "/" from ip:port string.
    String trueDns = resolver.getAddress().toString().substring(1);
    listener = new GoIntraListener(vpnService);
    String dohURL = PersistentState.getServerUrl(vpnService);

    try {
      LogWrapper.log(Log.INFO, LOG_TAG, "Starting go-tun2socks");
      final IntraTunnel t = Tun2socks.connectIntraTunnel(tunFd.getFd(), fakeDns, trueDns, trueDns,
          makeDohTransport(dohURL), listener);
      tunnel = t;

      new Thread(() -> {
        Result r = TLSProbe.run(vpnService);
        LogWrapper.log(Log.INFO, LOG_TAG, "TLS probe result: " + r.name());
        t.setAlwaysSplitHTTPS(r == Result.TLS_FAILED);
      }).start();
    } catch (Exception e) {
      LogWrapper.logException(e);
      tunnel = null;
      close();
    }
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private static ParcelFileDescriptor establishVpn(IntraVpnService vpnService) {
    try {
      return vpnService.newBuilder()
          .setSession("Intra go-tun2socks VPN")
          .setMtu(VPN_INTERFACE_MTU)
          .addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
          .addRoute("0.0.0.0", 0)
          .addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
          .addDisallowedApplication(vpnService.getPackageName())
          .establish();
    } catch (Exception e) {
      LogWrapper.logException(e);
      return null;
    }
  }

  @Override
  public synchronized void close() {
    if (tunnel != null) {
      tunnel.disconnect();
    }
    try {
      tunFd.close();
    } catch (IOException e) {
      LogWrapper.logException(e);
    }
    if (resolver != null) {
      resolver.shutdown();
    }
  }

  private static boolean useGoDoh() {
    return FirebaseRemoteConfig.getInstance().getBoolean("use_go_doh");
  }

  private doh.Transport makeDohTransport(String url) throws Exception {
    if (!useGoDoh()) {
      return null;
    }
    String dohIPs = ServerConnectionFactory.getIpString(vpnService, url);
    return Tun2socks.newDoHTransport(url, dohIPs, listener);
  }

  /**
   * Sets a DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
   * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
   * has no effect.
   * @param url The DOH server URL.  Must be valid and nonempty.
   */
  public void setDohUrl(String url) {
    if (!useGoDoh()) {
      return;
    }
    // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
    // is called on network changes, and it's important to switch to a fresh transport because the
    // old transport may be using sockets on a deleted interface, which may block until they time
    // out.
    try {
      tunnel.setDNS(makeDohTransport(url));
    } catch (Exception e) {
      LogWrapper.logException(e);
    }
  }
}
