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

import android.content.Context;
import android.util.Log;
import app.intra.sys.LogWrapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executors;
import sockslib.server.BasicSocksProxyServer;
import sockslib.server.SocksHandler;

/**
 * This is a SocksProxyServer that allows the caller to redirect UDP traffic to a fake DNS server
 * to reach the true server instead.
 */
class SocksServer extends BasicSocksProxyServer {
  private static final String LOG_TAG = "SocksServer";

  // RFC 5382 REQ-5 requires a timeout no shorter than 2 hours and 4 minutes.
  // Sockslib's default is 10 seconds.
  private static final int TIMEOUT_MS = 1000 * 60 * (4 + 60 * 2);

  private final InetSocketAddress fakeDns;
  private final InetSocketAddress trueDns;

  private final Context context;

  SocksServer(Context context, InetSocketAddress fakeDns, InetSocketAddress trueDns) {
    // BasicSocksProxyServer uses a default thread pool of size 100, resulting in a limit of 100
    // active sockets, including DNS queries.  Once the limit is reached, subsequent new sockets
    // are queued until an existing socket decides to close.  To avoid imposing long delays on
    // pending network requests, we replace the default thread pool with one that does not have a
    // size limit.
    super(OverrideSocksHandler.class, Executors.newCachedThreadPool());
    this.fakeDns = fakeDns;
    this.trueDns = trueDns;
    this.context = context;
    setTimeout(TIMEOUT_MS);
  }

  @Override
  public void initializeSocksHandler(SocksHandler socksHandler) {
    super.initializeSocksHandler(socksHandler);
    if (socksHandler instanceof OverrideSocksHandler) {
      OverrideSocksHandler override = (OverrideSocksHandler)socksHandler;
      override.setDns(fakeDns, trueDns);
      override.setContext(context);
    } else {
      LogWrapper.log(Log.WARN, LOG_TAG, "Foreign handler");
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
