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

import app.intra.util.DnsQueryTracker;

import android.content.Context;

// Singleton class to maintain state related to VPN Tunnel service.
public class DnsVpnServiceState {

  private static DnsVpnServiceState m_dnsVpnServiceState;

  public Object clone() throws CloneNotSupportedException {
    throw new CloneNotSupportedException();
  }

  public static synchronized DnsVpnServiceState getInstance() {
    if (m_dnsVpnServiceState == null) {
      m_dnsVpnServiceState = new DnsVpnServiceState();
    }
    return m_dnsVpnServiceState;
  }

  private DnsVpnService m_dnsVpnService = null;
  private boolean m_startingService = false;
  private DnsQueryTracker m_tracker = null;

  private DnsVpnServiceState() {
  }

  public synchronized void setDnsVpnService(DnsVpnService dnsVpnService) {
    m_dnsVpnService = dnsVpnService;
    m_startingService = false;
  }

  public DnsQueryTracker getTracker(Context context) {
    if (m_tracker == null) {
      m_tracker = new DnsQueryTracker(context);
    }
    return m_tracker;
  }

  public synchronized DnsVpnService getDnsVpnService() {
    return m_dnsVpnService;
  }

  public synchronized void setDnsVpnServiceStarting() {
    if (m_dnsVpnService == null) {
      m_startingService = true;
    }
  }

  public synchronized boolean isDnsVpnServiceStarting() {
    return m_startingService;
  }
}
