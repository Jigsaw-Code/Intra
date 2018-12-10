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
package app.intra.net.split;

import java.net.InetAddress;
import java.nio.ByteBuffer;

// Abstract class that represents a version-agnostic IP packet.
abstract class IpPacket {

  static final int VERSION_OFFSET = 0;
  static final byte VERSION_MASK = (byte) 0xF0;

  protected InetAddress sourceAddress;
  protected InetAddress destAddress;
  protected byte[] payload;
  protected byte protocol;

  static byte getIpVersion(ByteBuffer packet) {
    // The version octet is the first byte of both IPv4 and IPv6 headers.
    return (byte) (((byte) (packet.get(VERSION_OFFSET) & VERSION_MASK)) >> 4);
  }

  InetAddress getSourceAddress() {
    return sourceAddress;
  }

  InetAddress getDestAddress() {
    return destAddress;
  }

  byte[] getPayload() {
    return payload;
  }

  public byte getProtocol() {
    return protocol;
  }

  public abstract byte[] getRawPacket();

  public abstract byte getVersion();

  /**
   * Calculate the Internet Checksum of a buffer (RFC 1071 - http://www.faqs.org/rfcs/rfc1071.html)
   * Algorithm is 1) apply a 16-bit 1's complement sum over all octets (adjacent 8-bit pairs [A,B],
   * final odd length is [A,0]) 2) apply 1's complement to this final sum
   *
   * <p>Notes: 1's complement is bitwise NOT of positive value. Ensure that any carry bits are added
   * back to avoid off-by-one errors
   *
   * <p>Source: http://stackoverflow.com/questions/4113890/how-to-calculate-the-internet-checksum-from-a-byte-in-java
   *
   * @param buf The message
   * @return The checksum
   */
  protected static short computeChecksum(byte[] buf) {
    int length = buf.length;
    int i = 0;

    long sum = 0;
    long data;

    // Handle all pairs
    while (length > 1) {
      data = (((buf[i] << 8) & 0xFF00) | ((buf[i + 1]) & 0xFF));
      sum += data;
      // 1's complement carry bit correction in 16-bits (detecting sign extension)
      if ((sum & 0xFFFF0000) > 0) {
        sum = sum & 0xFFFF;
        sum += 1;
      }
      i += 2;
      length -= 2;
    }
    // Handle remaining byte in odd length buffers
    if (length > 0) {
      sum += (buf[i] << 8 & 0xFF00);
      // 1's complement carry bit correction in 16-bits (detecting sign extension)
      if ((sum & 0xFFFF0000) > 0) {
        sum = sum & 0xFFFF;
        sum += 1;
      }
    }
    // Final 1's complement value correction to 16-bits
    sum = ~sum;
    sum = sum & 0xFFFF;
    return (short) sum;
  }
}
