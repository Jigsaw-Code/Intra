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
package app.intra.sys;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// Singleton class to maintain state related to VPN Tunnel service.
public class VpnController {

  private static VpnController dnsVpnServiceState;

  public Object clone() throws CloneNotSupportedException {
    throw new CloneNotSupportedException();
  }

  public static synchronized VpnController getInstance() {
    if (dnsVpnServiceState == null) {
      dnsVpnServiceState = new VpnController();
    }
    return dnsVpnServiceState;
  }

  private IntraVpnService intraVpnService = null;
  private IntraVpnService.State connectionState = null;
  private IntraVpnService.AuthRequirement connectionAuthReq = null;
  private QueryTracker tracker = null;

  private VpnController() {}

  void setIntraVpnService(IntraVpnService intraVpnService) {
    this.intraVpnService = intraVpnService;
  }

  public @Nullable IntraVpnService getIntraVpnService() {
    return this.intraVpnService;
  }

  public synchronized void onConnectionStateChanged(Context context, IntraVpnService.State state, IntraVpnService.AuthRequirement authReq) {
    if (state == connectionState) {
      // The state has not changed.
      return;
    }
    if (intraVpnService == null) {
      // User clicked disable while the connection state was changing.
      return;
    }
    connectionState = state;
    connectionAuthReq = authReq;
    stateChanged(context);
  }

  private void stateChanged(Context context) {
    Intent broadcast = new Intent(InternalNames.DNS_STATUS.name());
    LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast);
  }

  public synchronized QueryTracker getTracker(Context context) {
    if (tracker == null) {
      tracker = new QueryTracker(context);
    }
    return tracker;
  }

  public synchronized void start(Context context) {
    if (intraVpnService != null) {
      return;
    }
    PersistentState.setVpnEnabled(context, true);
    stateChanged(context);
    Intent startServiceIntent = new Intent(context, IntraVpnService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(startServiceIntent);
    } else {
      context.startService(startServiceIntent);
    }
  }

  synchronized void onStartComplete(Context context, boolean succeeded) {
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
    connectionAuthReq = null;
    if (intraVpnService != null) {
      intraVpnService.signalStopService(true);
    }
    intraVpnService = null;
    stateChanged(context);
  }

  public synchronized VpnState getState(Context context) {
    boolean requested = PersistentState.getVpnEnabled(context);
    boolean on = intraVpnService != null && intraVpnService.isOn();
    return new VpnState(requested, on, connectionState, connectionAuthReq);
  }
}
