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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Object representation of a UDP packet.
class UdpPacket {

  private static final int HEADER_LENGTH = 8;
  private static final int OFFSET_SOURCE_PORT = 0;
  private static final int OFFSET_DEST_PORT = 2;
  private static final int OFFSET_LENGTH = 4;
  private static final int OFFSET_CHECKSUM = 6;

  final short sourcePort;
  final short destPort;
  final short length;
  final short checksum;
  final byte[] data;

  UdpPacket(ByteBuffer packet) {
    sourcePort = packet.getShort(OFFSET_SOURCE_PORT);
    destPort = packet.getShort(OFFSET_DEST_PORT);
    length = packet.getShort(OFFSET_LENGTH);
    checksum = packet.getShort(OFFSET_CHECKSUM);

    data = new byte[length - HEADER_LENGTH];
    packet.position(HEADER_LENGTH);
    packet.get(data);
    packet.clear();
  }

  UdpPacket(short sourcePort, short destPort, byte[] data) {
    this.sourcePort = sourcePort;
    this.destPort = destPort;
    this.length = (short) (HEADER_LENGTH + data.length);
    this.checksum = 0;
    this.data = data;
  }

  byte[] getRawPacket() {
    ByteBuffer packet = ByteBuffer.allocate(length);
    packet.order(ByteOrder.BIG_ENDIAN); // Network order.
    packet.putShort(OFFSET_SOURCE_PORT, sourcePort);
    packet.putShort(OFFSET_DEST_PORT, destPort);
    packet.putShort(OFFSET_CHECKSUM, checksum);
    packet.putShort(OFFSET_LENGTH, length);
    packet.position(HEADER_LENGTH);
    packet.put(data);
    return packet.array();
  }

  @Override
  public String toString() {
    return String.format(
        "source_port: %d | dest_port: %d | length: %d | checksum: %d",
        sourcePort, destPort, length, checksum);
  }
}
