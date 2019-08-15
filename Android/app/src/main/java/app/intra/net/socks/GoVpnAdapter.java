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
package app.intra.net.socks;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import app.intra.net.VpnAdapter;
import app.intra.net.socks.TLSProbe.Result;
import app.intra.sys.IntraVpnService;
import app.intra.sys.LogWrapper;
import java.io.IOException;
import java.util.Locale;
import tun2socks.IntraTunnel;
import tun2socks.Tun2socks;

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter extends VpnAdapter {
  private static final String LOG_TAG = "SocksVpnAdapter";

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
  private final Context context;

  // DNS resolver running on localhost.
  private final LocalhostResolver resolver;

  // TUN device representing the VPN.
  private final ParcelFileDescriptor tunFd;

  // The Intra session object from go-tun2socks.  Initially null.
  private IntraTunnel tunnel;

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

  private GoVpnAdapter(Context context, ParcelFileDescriptor tunFd, LocalhostResolver resolver) {
    this.context = context;
    this.resolver = resolver;
    this.tunFd = tunFd;
  }

  @Override
  public synchronized void start() {
    resolver.start();

    // VPN parameters
    final String fakeDnsIp = LanIp.DNS.make(IPV4_TEMPLATE);
    final String fakeDns = fakeDnsIp + ":" + DNS_DEFAULT_PORT;

    Result r = TLSProbe.run(context);
    LogWrapper.log(Log.INFO, LOG_TAG, "TLS probe result: " + r.name());
    boolean alwaysSplitHttps = r == Result.TLS_FAILED;

    String trueDns = resolver.getAddress().toString().substring(1);
    GoIntraListener listener = new GoIntraListener(context);

    try {
      LogWrapper.log(Log.INFO, LOG_TAG, "Starting go-tun2socks");
      tunnel = Tun2socks.connectIntraTunnel(tunFd.getFd(), fakeDns, trueDns, trueDns, alwaysSplitHttps, listener);
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
}
