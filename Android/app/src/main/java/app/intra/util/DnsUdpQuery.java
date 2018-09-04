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
package app.intra.util;

import com.google.firebase.crash.FirebaseCrash;

import android.os.SystemClock;
import android.util.Log;

import java.net.InetAddress;
import java.net.ProtocolException;

// Class to maintain state about a DNS query over UDP.
public class DnsUdpQuery {
  private static final String LOG_TAG = "DnsUdpQuery";

  public String name;
  public short requestId;
  public short type;
  public InetAddress sourceAddress;
  public InetAddress destAddress;
  public short sourcePort;
  public short destPort;
  public long timestamp;

  // Returns the question name, type, and id  present in |dnsPacket|, as a DnsUdpQuery object.
  // Assumes the DNS packet has been validated.
  public static DnsUdpQuery fromUdpBody(byte[] dnsPacketData) {
    DnsUdpQuery dnsUdpQuery = new DnsUdpQuery();
    dnsUdpQuery.timestamp = SystemClock.elapsedRealtime();

    DnsPacket dnsPacket;
    try {
      dnsPacket = new DnsPacket(dnsPacketData);
    } catch (ProtocolException e) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Received invalid DNS request");
      return null;
    }
    if (!dnsPacket.isNormalQuery() && !dnsPacket.isResponse()) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Dropping strange DNS query");
      return null;
    }

    dnsUdpQuery.type = dnsPacket.getQueryType();
    dnsUdpQuery.name = dnsPacket.getQueryName();
    if (dnsUdpQuery.name == null || dnsUdpQuery.type == 0) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "No question in DNS packet");
      return null;
    }
    dnsUdpQuery.requestId = dnsPacket.getId();

    return dnsUdpQuery;
  }
}
