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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Object representation of an IPv6 packet.
public class Ipv6Packet extends IpPacket {

  private static final byte VERSION_IPV6 = 6;
  private static final short FIXED_HEADER_LENGTH = 40;
  private static final byte MIN_HOP_LIMIT = 64;

  private static final short OFFSET_PAYLOAD_LENGTH = 4;
  private static final short OFFSET_NEXT_HEADER = 6;
  private static final short OFFSET_HOP_LIMIT = 7;
  private static final short OFFSET_SOURCE_ADDRESS = 8;
  private static final short OFFSET_DEST_ADDRESS = 24;

  private static final short PSEUDO_OFFSET_SOURCE_ADDRESS = 0;
  private static final short PSEUDO_OFFSET_DEST_ADDRESS = 16;
  private static final short PSEUDO_OFFSET_UDP_LENGTH = 32;
  private static final short PSEUDO_OFFSET_NEXT_HEADER = 39;
  private static final short OFFSET_UDP_CHECKSUM = 6;

  private static final short EXTHDR_HOP = 0; // Hop-by-hop option header.
  private static final short EXTHDR_IPV6 = 41; // IPv6 in IPv6
  private static final short EXTHDR_ROUTING = 43; // Routing header.
  private static final short EXTHDR_FRAGMENT = 44; // Fragmentation/reassembly header.
  private static final short EXTHDR_ESP = 50; // Encapsulating security payload.
  private static final short EXTHDR_AUTH = 51; // Authentication header.
  private static final short EXTHDR_NONE = 59; // No next header
  private static final short EXTHDR_DEST = 60; // Destination options header.

  private static final short EXTHDR_OFFSET_NEXTHDR = 0;
  private static final short EXTHDR_OFFSET_LENGTH = 1;
  private static final short EXTHDR_FRAGMENT_LENGTH = 8;

  private static final byte UDP_PROTOCOL = 17;

  private short payloadLength;
  private short totalHeaderLength;
  private short hopLimit;

  public Ipv6Packet(ByteBuffer packet) throws IllegalArgumentException {
    payloadLength = packet.getShort(OFFSET_PAYLOAD_LENGTH);
    hopLimit = packet.get(OFFSET_HOP_LIMIT);
    sourceAddress = getInetAddress(packet, OFFSET_SOURCE_ADDRESS);
    destAddress = getInetAddress(packet, OFFSET_DEST_ADDRESS);
    protocol = getProtocol(packet);

    if (payloadLength == 0) {
      // Payload length set to zero when a Hop-by-Hop header carries a Jumbo Payload option.
      protocol = EXTHDR_HOP;
      return;
    } else if (protocol == EXTHDR_NONE) {
      payloadLength = 0;
      return;
    }

    int dataLength = payloadLength - totalHeaderLength + FIXED_HEADER_LENGTH;
    byte[] data = new byte[dataLength];
    packet.position(totalHeaderLength);
    packet.get(data);
    payload = data;

    // Verify UDP checksum
    if (protocol == UDP_PROTOCOL) {
      ByteBuffer udpPacket = ByteBuffer.wrap(payload);
      short udpChecksum = udpPacket.getShort(OFFSET_UDP_CHECKSUM);
      short computedUdpChecksum = computeUdpIpv6Checksum();
      if (udpChecksum != computedUdpChecksum) {
        throw new IllegalArgumentException("UDP/IPv6 checksum does not match computed checksum");
      }
    }
  }

  public Ipv6Packet(
      byte protocol, InetAddress sourceAddress, InetAddress destAddress, byte[] data) {
    this.protocol = protocol;
    this.sourceAddress = sourceAddress;
    this.destAddress = destAddress;
    this.payload = data;
    this.payloadLength = (short) payload.length;
    this.totalHeaderLength = FIXED_HEADER_LENGTH;
  }

  private InetAddress getInetAddress(ByteBuffer buffer, int offset) {
    byte[] byteAddress = new byte[16];
    buffer.position(offset);
    buffer.get(byteAddress);
    try {
      return InetAddress.getByAddress(byteAddress);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Invalid IP address.", e);
    }
  }

  // Parses the extension headers present in the packet and returns upper layer protocol.
  // Sets the total header length variable.
  private byte getProtocol(ByteBuffer packet) {
    byte nextHeader = packet.get(OFFSET_NEXT_HEADER);
    byte protocol = nextHeader;
    totalHeaderLength = FIXED_HEADER_LENGTH;

    while (isIpv6ExtHeader(nextHeader)) {
      switch (nextHeader) {
        case EXTHDR_AUTH:
          totalHeaderLength += (2 + packet.get(totalHeaderLength + EXTHDR_OFFSET_LENGTH)) * 4;
          break;
        case EXTHDR_FRAGMENT:
          totalHeaderLength += EXTHDR_FRAGMENT_LENGTH;
          break;
        default:
          totalHeaderLength += packet.get(totalHeaderLength + EXTHDR_OFFSET_LENGTH);
          break;
      }
      nextHeader = packet.get(totalHeaderLength + EXTHDR_OFFSET_NEXTHDR);
      protocol = nextHeader;
    }

    return protocol;
  }

  // Returns true if the protocol is an IPv6 extension header.
  private boolean isIpv6ExtHeader(short nextHeader) {
    return nextHeader == EXTHDR_HOP
        || nextHeader == EXTHDR_ROUTING
        || nextHeader == EXTHDR_FRAGMENT
        || nextHeader == EXTHDR_AUTH
        || nextHeader == EXTHDR_DEST;
  }

  public byte[] getRawPacket() {
    ByteBuffer packet = ByteBuffer.allocate(payloadLength + totalHeaderLength);
    packet.order(ByteOrder.BIG_ENDIAN);
    packet.put(VERSION_OFFSET, (byte) (VERSION_IPV6 << 4));
    packet.putShort(OFFSET_PAYLOAD_LENGTH, payloadLength);
    packet.put(OFFSET_NEXT_HEADER, protocol); // No extension headers.
    packet.put(OFFSET_HOP_LIMIT, MIN_HOP_LIMIT);
    packet.position(OFFSET_SOURCE_ADDRESS);
    packet.put(sourceAddress.getAddress());
    packet.position(OFFSET_DEST_ADDRESS);
    packet.put(destAddress.getAddress());
    if (protocol == UDP_PROTOCOL) {
      // UDP checksum is mandatory in IPv6.
      short udpChecksum = computeUdpIpv6Checksum();
      ByteBuffer udpPacket = ByteBuffer.wrap(payload);
      udpPacket.putShort(OFFSET_UDP_CHECKSUM, udpChecksum);
    }
    packet.position(totalHeaderLength);
    packet.put(payload);

    return packet.array();
  }

  private short computeUdpIpv6Checksum() {
    // Copy the payload so as not to modify the original checksum.
    ByteBuffer udpPacket = ByteBuffer.wrap(payload.clone());
    udpPacket.putShort(OFFSET_UDP_CHECKSUM, (short) 0);
    byte[] pseudoPacket = getPseudoPacket(udpPacket.array());
    short udpChecksum = computeChecksum(pseudoPacket);
    if (udpChecksum == 0) {
      // If the checksum calculation results in zero, it should be sent as the one's complement.
      udpChecksum = 0xFF;
    }
    return udpChecksum;
  }

  // Returns a raw packet that contains the Ipv6 pseudo header used for UDP checksums.
  // zeroChecksumPayload is a copy of the payload, with the UDP checksum zeroed out.
  private byte[] getPseudoPacket(byte[] zeroChecksumPayload) {
    ByteBuffer pseudoPacket = ByteBuffer.allocate(payloadLength + FIXED_HEADER_LENGTH);
    pseudoPacket.position(PSEUDO_OFFSET_SOURCE_ADDRESS);
    pseudoPacket.put(sourceAddress.getAddress());
    pseudoPacket.position(PSEUDO_OFFSET_DEST_ADDRESS);
    pseudoPacket.put(destAddress.getAddress());
    pseudoPacket.putInt(PSEUDO_OFFSET_UDP_LENGTH, payloadLength);
    pseudoPacket.put(PSEUDO_OFFSET_NEXT_HEADER, protocol);
    pseudoPacket.position(FIXED_HEADER_LENGTH);
    pseudoPacket.put(zeroChecksumPayload);
    return pseudoPacket.array();
  }

  @Override
  public byte getVersion() {
    return VERSION_IPV6;
  }

  @Override
  public String toString() {
    return String.format(
        "version: %d | payload_length: %d | data_length: %d | hop_limit: %d | protocol: %d | "
            + " source_addr: %s | dest_addr: %s",
        VERSION_IPV6,
        payloadLength,
        payloadLength - totalHeaderLength + FIXED_HEADER_LENGTH,
        hopLimit,
        protocol,
        sourceAddress,
        destAddress);
  }
}
