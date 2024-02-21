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

import android.content.Context;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import app.intra.R;
import app.intra.sys.CountryCode;
import app.intra.sys.IntraVpnService;
import app.intra.sys.PersistentState;
import app.intra.sys.VpnController;
import app.intra.sys.firebase.AnalyticsWrapper;
import app.intra.sys.firebase.LogWrapper;
import app.intra.sys.firebase.RemoteConfig;
import backend.Backend;
import backend.DoHServer;
import backend.Session;
import protect.Protector;

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter {
  private static final String LOG_TAG = "GoVpnAdapter";

  // This value must match the hardcoded MTU in outline-go-tun2socks.
  // TODO: Make outline-go-tun2socks's MTU configurable.
  private static final int VPN_INTERFACE_MTU = 1500;
  private static final int DNS_DEFAULT_PORT = 53;

  // IPv4 VPN constants
  private static final String IPV4_TEMPLATE = "10.111.222.%d";
  private static final int IPV4_PREFIX_LENGTH = 24;

  // Choir salt file name
  private static final String CHOIR_FILENAME = "choir-salt";

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

  public static final String FAKE_DNS_IP = LanIp.DNS.make(IPV4_TEMPLATE);

  // Service context in which the VPN is running.
  private final IntraVpnService vpnService;

  // TUN device representing the VPN.
  private ParcelFileDescriptor tunFd;

  // The Intra session object from go-tun2socks.  Initially null.
  private Session session;
  private GoIntraListener listener;

  public static GoVpnAdapter establish(@NonNull IntraVpnService vpnService) {
    ParcelFileDescriptor tunFd = establishVpn(vpnService);
    if (tunFd == null) {
      return null;
    }
    return new GoVpnAdapter(vpnService, tunFd);
  }

  private GoVpnAdapter(IntraVpnService vpnService, ParcelFileDescriptor tunFd) {
    this.vpnService = vpnService;
    this.tunFd = tunFd;
  }

  public synchronized void start() {
    connectTunnel();
  }

  private void connectTunnel() {
    if (session != null) {
      return;
    }
    // VPN parameters
    final String fakeDns = FAKE_DNS_IP + ":" + DNS_DEFAULT_PORT;

    // Strip leading "/" from ip:port string.
    listener = new GoIntraListener(vpnService);
    String dohURL = PersistentState.getServerUrl(vpnService);

    try {
      LogWrapper.log(Log.INFO, LOG_TAG, "Starting go-tun2socks");
      DoHServer server = makeDoHServer(dohURL);
      // connectIntraTunnel makes a copy of the file descriptor.
      session = Backend.connectSession(tunFd.getFd(), fakeDns,
          server, getProtector(), listener);
    } catch (Exception e) {
      LogWrapper.logException(e);
      VpnController.getInstance().onConnectionStateChanged(vpnService, IntraVpnService.State.FAILING);
      return;
    }

    if (RemoteConfig.getChoirEnabled()) {
      enableChoir();
    }
  }

  // Set up failure reporting with Choir.
  private void enableChoir() {
    CountryCode countryCode = new CountryCode(vpnService);
    @NonNull String country = countryCode.getNetworkCountry();
    if (country.isEmpty()) {
      country = countryCode.getDeviceCountry();
    }
    if (country.isEmpty()) {
      // Country code is mandatory for Choir.
      Log.i(LOG_TAG, "No country code found");
      return;
    }
    String file = vpnService.getFilesDir() + File.separator + CHOIR_FILENAME;
    try {
      session.enableSNIReporter(file, "intra.metrics.gstatic.com", country);
    } catch (Exception e) {
      // Choir setup failure is logged but otherwise ignored, because it does not prevent Intra
      // from functioning correctly.
      LogWrapper.logException(e);
    }
  }

  private static ParcelFileDescriptor establishVpn(IntraVpnService vpnService) {
    try {
      VpnService.Builder builder = vpnService.newBuilder()
          .setSession("Intra go-tun2socks VPN")
          .setMtu(VPN_INTERFACE_MTU)
          .addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
          .addRoute("0.0.0.0", 0)
          .addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
      if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
        builder.addDisallowedApplication(vpnService.getPackageName());
      }
      if (VERSION.SDK_INT >= VERSION_CODES.Q) {
        builder.setMetered(false); // There's no charge for using Intra.
      }
      return builder.establish();
    } catch (Exception e) {
      LogWrapper.logException(e);
      return null;
    }
  }

  private @Nullable Protector getProtector() {
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      // We don't need socket protection in these versions because the call to
      // "addDisallowedApplication" effectively protects all sockets in this app.
      return null;
    }
    return vpnService;
  }

  public synchronized void close() {
    if (session != null) {
      session.disconnect();
    }
    if (tunFd != null) {
      try {
        tunFd.close();
      } catch (IOException e) {
        LogWrapper.logException(e);
      }
    }
    tunFd = null;
  }

  private DoHServer makeDoHServer(@Nullable String url) throws Exception {
    @NonNull String realUrl = PersistentState.expandUrl(vpnService, url);
    String dohIPs = getIpString(vpnService, realUrl);
    String host = new URL(realUrl).getHost();
    long startTime = SystemClock.elapsedRealtime();
    final DoHServer server;
    try {
      server = new DoHServer(realUrl, dohIPs, getProtector(), listener);
    } catch (Exception e) {
      AnalyticsWrapper.get(vpnService).logBootstrapFailed(host);
      throw e;
    }
    int delta = (int) (SystemClock.elapsedRealtime() - startTime);
    AnalyticsWrapper.get(vpnService).logBootstrap(host, delta);
    return server;
  }

  /**
   * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
   * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
   * has no effect.
   */
  public synchronized void updateDohUrl() {
    if (tunFd == null) {
      // Adapter is closed.
      return;
    }
    if (session == null) {
      // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
      // server could not be reached.  This will update the DoH URL as well.
      connectTunnel();
      return;
    }
    // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
    // is called on network changes, and it's important to switch to a fresh transport because the
    // old transport may be using sockets on a deleted interface, which may block until they time
    // out.
    String url = PersistentState.getServerUrl(vpnService);
    try {
      session.setDoHServer(makeDoHServer(url));
    } catch (Exception e) {
      LogWrapper.logException(e);
      session.disconnect();
      session = null;
      VpnController.getInstance().onConnectionStateChanged(vpnService, IntraVpnService.State.FAILING);
    }
  }

  // Returns the known IPs for this URL as a string containing a comma-separated list.
  static String getIpString(Context context, String url) {
    Resources res = context.getResources();
    String[] urls = res.getStringArray(R.array.urls);
    String[] ips = res.getStringArray(R.array.ips);
    String ret = "";
    for (int i = 0; i < urls.length; ++i) {
      // TODO: Consider relaxing this equality condition to a match on just the domain.
      if (urls[i].equals(url)) {
        ret = ips[i];
        break;
      }
    }

    try {
      String domain = new URL(url).getHost();
      String extraIPs = RemoteConfig.getExtraIPs(domain);
      if (ret.isEmpty()) {
        ret = extraIPs;
      } else if (!extraIPs.isEmpty()) {
        ret += "," + extraIPs;
      }
    } catch (MalformedURLException e) {
      LogWrapper.logException(e);
    }

    return ret;
  }
}
