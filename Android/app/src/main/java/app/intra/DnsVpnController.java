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

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import app.intra.util.DnsQueryTracker;
import app.intra.util.Names;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

// Singleton class to maintain state related to VPN Tunnel service.
public class DnsVpnController {

  private static DnsVpnController dnsVpnServiceState;

  public Object clone() throws CloneNotSupportedException {
    throw new CloneNotSupportedException();
  }

  public static synchronized DnsVpnController getInstance() {
    if (dnsVpnServiceState == null) {
      dnsVpnServiceState = new DnsVpnController();
    }
    return dnsVpnServiceState;
  }

  private DnsVpnService dnsVpnService = null;
  private ServerConnection.State connectionState = null;
  private DnsQueryTracker tracker = null;

  private DnsVpnController() {}

  public void setDnsVpnService(DnsVpnService dnsVpnService) {
    this.dnsVpnService = dnsVpnService;
  }

  public synchronized void onConnectionStateChanged(Context context, ServerConnection.State state) {
    if (dnsVpnService == null) {
      // User clicked disable while the connection state was changing.
      return;
    }
    connectionState = state;
    stateChanged(context);
  }

  private void stateChanged(Context context) {
    Intent broadcast = new Intent(Names.DNS_STATUS.name());
    LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast);
  }

  public synchronized DnsQueryTracker getTracker(Context context) {
    if (tracker == null) {
      tracker = new DnsQueryTracker(context);
    }
    return tracker;
  }

  public DnsVpnService getDnsVpnService() {
    return dnsVpnService;
  }

  public synchronized void start(Context context) {
    PersistentState.setVpnEnabled(context, true);
    stateChanged(context);
    Intent startServiceIntent = new Intent(context, DnsVpnService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(startServiceIntent);
    } else {
      context.startService(startServiceIntent);
    }
  }

  public synchronized void onStartComplete(Context context, boolean succeeded) {
    if (!succeeded) {
      // VPN setup only fails if VPN permission has been revoked.  If this happens, clear the
      // user intent state and reset to the default state.
      stop(context);
    } else {
      stateChanged(context);
    }
  }

  public synchronized void stop(Context context) {
    PersistentState.setVpnEnabled(context, false);
    connectionState = null;
    if (dnsVpnService != null) {
      dnsVpnService.signalStopService(true);
    }
    dnsVpnService = null;
    stateChanged(context);
  }

  public synchronized DnsVpnState getState(Context context) {
    boolean requested = PersistentState.getVpnEnabled(context);
    boolean on = dnsVpnService != null && dnsVpnService.isOn();
    return new DnsVpnState(requested, on, connectionState);
  }

}
