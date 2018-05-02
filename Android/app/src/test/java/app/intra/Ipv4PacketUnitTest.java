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

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import app.intra.util.Ipv4Packet;

public class Ipv4PacketUnitTest {
  private static final int IPV4_VERSION = 4;
  private static final int IPV4_HEADER_LENGTH = 20;
  private static final byte UDP_PROTOCOL = 17;
  private static final byte[] RAW_IP_PACKET = {
      (byte) 0x45, (byte) 0x00, (byte) 0x00, (byte) 0x43, (byte) 0xcb, (byte) 0xd8, (byte) 0x00,
      (byte) 0x00, (byte) 0x40, (byte) 0x11, (byte) 0x36, (byte) 0xd1, (byte) 0x64, (byte) 0x6a,
      (byte) 0x68, (byte) 0x84, (byte) 0xac, (byte) 0x10, (byte) 0xff, (byte) 0x01, (byte) 0xc6,
      (byte) 0x2d, (byte) 0x00, (byte) 0x35, (byte) 0x00, (byte) 0x2f, (byte) 0x71, (byte) 0x21,
      (byte) 0x13, (byte) 0x21, (byte) 0x01, (byte) 0x20, (byte) 0x00, (byte) 0x01, (byte) 0x00,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x06, (byte) 0x67,
      (byte) 0x6f, (byte) 0x6f, (byte) 0x67, (byte) 0x6c, (byte) 0x65, (byte) 0x03, (byte) 0x63,
      (byte) 0x6f, (byte) 0x6d, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
      (byte) 0x00, (byte) 0x00, (byte) 0x29, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
  };

  private Ipv4Packet ipv4Packet;

  @Before
  public void setUp() throws Exception {
    ipv4Packet = new Ipv4Packet(ByteBuffer.wrap(RAW_IP_PACKET));
  }

  @After
  public void tearDown() throws Exception {}

  @Test
  public void testGetVersion() {
    assertEquals(IPV4_VERSION, ipv4Packet.getVersion());
  }

  @Test
  public void testGetProtocol() {
    assertEquals(UDP_PROTOCOL, ipv4Packet.getProtocol());
  }

  @Test
  public void testGetPayload() {
    byte[] payload = Arrays.copyOfRange(RAW_IP_PACKET, IPV4_HEADER_LENGTH, RAW_IP_PACKET.length);
    assertArrayEquals(payload, ipv4Packet.getPayload());
  }

  @Test
  public void testGetAddresses() throws UnknownHostException {
    assertEquals(InetAddress.getByName("100.106.104.132"), ipv4Packet.getSourceAddress());
    assertEquals(InetAddress.getByName("172.16.255.1"), ipv4Packet.getDestAddress());
  }

  @Test
  public void testGetRawPacket() {
    assertArrayEquals(RAW_IP_PACKET, ipv4Packet.getRawPacket());
  }

  @Test
  public void testSerialization() throws UnknownHostException {
    InetAddress sourceAddress = InetAddress.getByName("192.0.0.1");
    InetAddress destAddress = InetAddress.getByName("8.8.8.8");
    byte[] payload = {(byte) 't', (byte) 'e', (byte) 's', (byte) 't'};

    ipv4Packet = new Ipv4Packet(UDP_PROTOCOL, sourceAddress, destAddress, payload);
    Ipv4Packet deserializedPacket = new Ipv4Packet(ByteBuffer.wrap(ipv4Packet.getRawPacket()));

    assertEquals(IPV4_VERSION, deserializedPacket.getVersion());
    assertEquals(UDP_PROTOCOL, deserializedPacket.getProtocol());
    assertEquals(sourceAddress, deserializedPacket.getSourceAddress());
    assertEquals(destAddress, deserializedPacket.getDestAddress());
    assertArrayEquals(payload, deserializedPacket.getPayload());
    assertArrayEquals(ipv4Packet.getRawPacket(), deserializedPacket.getRawPacket());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidChecksum() {
    // Modify the raw packet so that its computed checksum does not validate.
    byte[] rawPacket = Arrays.copyOf(RAW_IP_PACKET, RAW_IP_PACKET.length);
    rawPacket[1] = 0xF;
    ipv4Packet = new Ipv4Packet(ByteBuffer.wrap(rawPacket));
  }
}
