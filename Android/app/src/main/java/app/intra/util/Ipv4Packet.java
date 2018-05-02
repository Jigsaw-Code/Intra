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

// Object representation of an IPv4 packet.
public class Ipv4Packet extends IpPacket {

  private static final short OFFSET_VERSION_IHL = 0;
  private static final short OFFSET_TOTAL_LENGTH = 2;
  private static final short OFFSET_IDENTIFICATON = 4;
  private static final short OFFSET_TTL = 8;
  private static final short OFFSET_PROTOCOL = 9;
  private static final short OFFSET_CHECKSUM = 10;
  private static final short OFFSET_SOURCE_ADDRESS = 12;
  private static final short OFFSET_DEST_ADDRESS = 16;

  private static final byte VERSION_IPV4 = (byte) 4;
  private static final byte IHL_MASK = (byte) 0xF;
  private static final short IHL_TO_BYTES = 4;

  private static final short MIN_HEADER_LENGTH = 20;
  private static final byte MIN_TTL = (byte) 64;

  private final byte headerLength;
  private final byte ttl;
  private final short totalLength;
  private final short checksum;
  private final short computedChecksum;
  private final short identification;

  public Ipv4Packet(ByteBuffer packet) throws IllegalArgumentException {
    headerLength = (byte) ((packet.get(OFFSET_VERSION_IHL) & IHL_MASK) * IHL_TO_BYTES);
    totalLength = packet.getShort(OFFSET_TOTAL_LENGTH);
    identification = packet.getShort(OFFSET_IDENTIFICATON);
    ttl = packet.get(OFFSET_TTL);
    protocol = packet.get(OFFSET_PROTOCOL);
    checksum = packet.getShort(OFFSET_CHECKSUM);
    sourceAddress = getInetAddress(packet.getInt(OFFSET_SOURCE_ADDRESS));
    destAddress = getInetAddress(packet.getInt(OFFSET_DEST_ADDRESS));

    ByteBuffer header = ByteBuffer.allocate(headerLength);
    packet.position(0);
    packet.get(header.array());
    header.putShort(OFFSET_CHECKSUM, (short) 0); // Checksum field must be 0 to compute checksum.
    computedChecksum = computeChecksum(header.array());
    if (checksum != computedChecksum) {
      throw new IllegalArgumentException("IP packet checksum does not match computed checksum.");
    }

    payload = new byte[totalLength - headerLength];
    packet.position(headerLength);
    packet.get(payload);
    packet.clear(); // Reset buffer.
  }

  // Returns an object representation of the IP address. Assumes network order.
  private InetAddress getInetAddress(int address) throws IllegalArgumentException {
    // Convert int to network order
    byte[] byteAddress = new byte[4];
    ByteBuffer buffer = ByteBuffer.wrap(byteAddress);
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.putInt(0, address);
    try {
      return InetAddress.getByAddress(byteAddress);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Invalid IP address.", e);
    }
  }

  public Ipv4Packet(
      byte protocol, InetAddress sourceAddress, InetAddress destAddress, byte[] data) {
    this.sourceAddress = sourceAddress;
    this.destAddress = destAddress;
    this.ttl = MIN_TTL;
    this.identification = 0;
    this.protocol = protocol;
    this.payload = data;
    // We don't use options, so the header length is constant.
    this.headerLength = MIN_HEADER_LENGTH;
    this.totalLength = (short) (headerLength + data.length);

    byte[] header = getRawPacket(true);
    this.checksum = computeChecksum(header);
    this.computedChecksum = checksum;
  }

  @Override
  public byte[] getRawPacket() {
    return getRawPacket(false);
  }

  private byte[] getRawPacket(boolean headerOnly) {
    int packetLength = headerOnly ? headerLength : totalLength;
    ByteBuffer packet = ByteBuffer.allocate(packetLength);
    packet.put(OFFSET_VERSION_IHL, makeVersionIhl());
    packet.putShort(OFFSET_TOTAL_LENGTH, totalLength);
    packet.putShort(OFFSET_IDENTIFICATON, identification);
    packet.put(OFFSET_TTL, ttl);
    packet.put(OFFSET_PROTOCOL, protocol);
    packet.putShort(OFFSET_CHECKSUM, headerOnly ? (byte) 0 : checksum);

    packet.position(OFFSET_SOURCE_ADDRESS);
    packet.put(sourceAddress.getAddress());
    packet.position(OFFSET_DEST_ADDRESS);
    packet.put(destAddress.getAddress());
    if (!headerOnly) {
      packet.position(headerLength);
      packet.put(payload);
    }
    return packet.array();
  }

  private byte makeVersionIhl() {
    return (byte) (((headerLength) / IHL_TO_BYTES) + (VERSION_IPV4 << 4));
  }

  @Override
  public String toString() {
    return String.format(
        "version: %d | ihl: %d | total_length: %d | ttl: %d | protocol: %d | checksum: %d | "
            + "computed_checksum: %d | source_addr: %s | dest_addr: %s",
        VERSION_IPV4,
        headerLength,
        totalLength,
        ttl,
        protocol,
        checksum,
        computedChecksum,
        sourceAddress,
        destAddress);
  }

  @Override
  public byte getVersion() {
    return VERSION_IPV4;
  }
}
