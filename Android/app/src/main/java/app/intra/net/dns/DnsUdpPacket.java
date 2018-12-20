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
package app.intra.net.dns;

import android.os.SystemClock;
import android.util.Log;
import app.intra.sys.LogWrapper;
import com.google.firebase.crash.FirebaseCrash;
import java.net.InetAddress;
import java.net.ProtocolException;

/**
 * Class to maintain state about a DNS packet over UDP.
 */
public class DnsUdpPacket {
  private static final String LOG_TAG = "DnsUdpPacket";

  public InetAddress sourceAddress;
  public InetAddress destAddress;
  public short sourcePort;
  public short destPort;
  public long timestamp;
  public DnsPacket dns;

  // Returns the question and id present in |dnsPacket|, as a DnsUdpPacket object.
  // Assumes the DNS packet has been validated.
  public static DnsUdpPacket fromUdpBody(byte[] dnsPacketData) {
    DnsUdpPacket dnsUdpPacket = new DnsUdpPacket();
    dnsUdpPacket.timestamp = SystemClock.elapsedRealtime();

    try {
      dnsUdpPacket.dns = new DnsPacket(dnsPacketData);
    } catch (ProtocolException e) {
      LogWrapper.logcat(Log.INFO, LOG_TAG, "Received invalid DNS request");
      return null;
    }
    if (!dnsUdpPacket.dns.isNormalQuery() && !dnsUdpPacket.dns.isResponse()) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "Dropping strange DNS question");
      return null;
    }

    Question question = dnsUdpPacket.dns.getQuestion();
    if (question.name == null || question.qtype == 0) {
      FirebaseCrash.logcat(Log.INFO, LOG_TAG, "No question in DNS packet");
      return null;
    }

    return dnsUdpPacket;
  }
}
