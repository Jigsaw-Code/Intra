package app.intra.socks;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import app.intra.util.LogWrapper;
import sockslib.server.BasicSocksProxyServer;
import sockslib.server.SocksHandler;

/**
 * This is a SocksProxyServer that allows the caller to redirect UDP traffic to a fake DNS server
 * to reach the true server instead.
 */
public class SocksServer extends BasicSocksProxyServer {
  private static final String LOG_TAG = "SocksServer";

  private final InetSocketAddress fakeDns;
  private final InetSocketAddress trueDns;

  SocksServer(InetSocketAddress fakeDns, InetSocketAddress trueDns) {
    super(UdpOverrideSocksHandler.class);
    this.fakeDns = fakeDns;
    this.trueDns = trueDns;
  }

  @Override
  public void initializeSocksHandler(SocksHandler socksHandler) {
    super.initializeSocksHandler(socksHandler);
    if (socksHandler instanceof UdpOverrideSocksHandler) {
      ((UdpOverrideSocksHandler)socksHandler).setDns(fakeDns, trueDns);
    } else {
      LogWrapper.logcat(Log.WARN, LOG_TAG, "Foreign handler");
    }
  }

  @Override
  protected ServerSocket createServerSocket(int bindPort, InetAddress bindAddr) throws IOException {
    // Workaround for upstream's lack of support for port 0.  TODO: Add this capability to Sockslib.
    ServerSocket serverSocket = super.createServerSocket(bindPort, bindAddr);
    this.setBindPort(serverSocket.getLocalPort());
    return serverSocket;
  }
}
