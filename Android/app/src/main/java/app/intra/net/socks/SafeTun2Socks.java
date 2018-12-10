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

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import app.intra.sys.LogWrapper;
import java.util.concurrent.Semaphore;
import org.outline.tun2socks.Tun2SocksJni;

/**
 * This class is designed avoid crashes that can occur when using Tun2Socks.  It currently
 * avoids two known issues:
 *  1. Closing the TUN device before Tun2Socks.stop() has completed results in an abort().
 *  2. Only one instance of Tun2Socks can exist at a time, i.e. stop() must be called before
 *     a second call to start().
 */
class SafeTun2Socks  {
  // The global semaphore represents ownership of the Tun2Socks library.  It must be held from
  // before calling Tun2SocksJni.start() until after both start() and stop() have returned.
  // It is initially available (permits = 1).
  private static final Semaphore globalSemaphore = new Semaphore(1);

  // This is the thread where Tun2Socks is running.
  private final Thread thread;

  // startupSemaphore is used to ensure that stop() cannot run until after the thread has started,
  // including initializing |startTime| and |started|.
  private final Semaphore startupSemaphore;
  private boolean started = false;

  // Because Tun2SocksJni.start() blocks, it's not possible to know exactly when it is safe to call
  // stop().  As a precaution, we refuse to call stop() until one second after the call to start().
  private static final long STARTUP_WAIT_MS = 1000;
  private long startTime = 0;

  // The tunFd in use by this object.  This object takes ownership of the tun device while it's in
  // use and returns it from stop() when it can be reused or closed.
  private final ParcelFileDescriptor tunFd;

  /**
   * Start tun2socks asynchronously.  If an instance of tun2socks is already running, the
   * asynchronous startup will be delayed until that instance stops.
   *
   * This constructor takes temporary ownership of tunFd, which must not be modified or reused until
   * it is returned from stop().
   */
  SafeTun2Socks(final ParcelFileDescriptor tunFd,
                final int vpnInterfaceMTU,
                final String vpnIpAddress,
                final String vpnNetMask,
                final String vpnIpV6Address,
                final String socksServerAddress,
                final String udpRelayAddress,
                final String dnsResolverAddress,
                final int transparentDNS,
                final int socks5UDP) {
    this.tunFd = tunFd;
    // startupSemaphore is initially unavailable, until after |startTime| and |started|
    // are initialized.
    startupSemaphore = new Semaphore(0);
    thread = new Thread(new Runnable() {
      @Override public void run() {
        try {
          globalSemaphore.acquire();
          started = true;
          startTime = SystemClock.elapsedRealtime();
        } catch (InterruptedException e) {
          // |started| is false.
          return;
        } finally {
          startupSemaphore.release();
        }
        Tun2SocksJni.start(tunFd.getFd(), vpnInterfaceMTU, vpnIpAddress, vpnNetMask, vpnIpV6Address,
            socksServerAddress, udpRelayAddress, dnsResolverAddress, transparentDNS, socks5UDP);
      }
    });
    thread.start();
  }

  /**
   * Stop this instance of tun2socks.  This method blocks until shutdown is complete.
   * @return The TUN device, still open and safe to reuse or close.
   */
  ParcelFileDescriptor stop() {
    thread.interrupt();
    startupSemaphore.acquireUninterruptibly();
    if (started) {
      long now = SystemClock.elapsedRealtime();
      if (now < startTime + STARTUP_WAIT_MS) {
        // Grace period: Wait for at least a STARTUP_WAIT_MS after startup before calling stop().
        try {
          thread.join(startTime + STARTUP_WAIT_MS - now);
        } catch (InterruptedException e) {
          LogWrapper.report(e);
        }
      }
      Tun2SocksJni.stop();
    }
    try {
      thread.join();
    } catch (InterruptedException e) {
      LogWrapper.report(e);
    }
    if (started) {
      globalSemaphore.release();
    }
    return tunFd;
  }
}
