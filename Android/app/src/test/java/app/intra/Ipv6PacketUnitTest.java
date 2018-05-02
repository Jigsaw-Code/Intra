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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import app.intra.util.Ipv6Packet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Ipv6PacketUnitTest {
  private static final int IPV6_VERSION = 6;
  private static final byte UDP_PROTOCOL = 17;
  private static final int IPV6_HEADER_LENGTH = 40;
  private static final byte[] RAW_IP_PACKET = {
      (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
      (byte) 0x2f, (byte) 0x11, (byte) 0x40, (byte) 0x26, (byte) 0x20,
      (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x03, (byte) 0x05,
      (byte) 0x12, (byte) 0xa9, (byte) 0x5b, (byte) 0xde, (byte) 0x22,
      (byte) 0x50, (byte) 0xa8, (byte) 0x0a, (byte) 0x81, (byte) 0x20,
      (byte) 0x01, (byte) 0x48, (byte) 0x60, (byte) 0x48, (byte) 0x60,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x88, (byte) 0x88,
      (byte) 0xf5, (byte) 0xe7, (byte) 0x00, (byte) 0x35, (byte) 0x00,
      (byte) 0x2f, (byte) 0x45, (byte) 0xf9, (byte) 0x2f, (byte) 0x69,
      (byte) 0x01, (byte) 0x20, (byte) 0x00, (byte) 0x01, (byte) 0x00,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
      (byte) 0x06, (byte) 0x67, (byte) 0x6f, (byte) 0x6f, (byte) 0x67,
      (byte) 0x6c, (byte) 0x65, (byte) 0x03, (byte) 0x63, (byte) 0x6f,
      (byte) 0x6d, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
      (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x29, (byte) 0x10,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
      (byte) 0x00, (byte) 0x00
  };

  private Ipv6Packet ipv6Packet;

  @Before
  public void setUp() throws Exception {
    ipv6Packet = new Ipv6Packet(ByteBuffer.wrap(RAW_IP_PACKET));
  }

  @After
  public void tearDown() throws Exception {}

  @Test
  public void testGetVersion() {
    assertEquals(IPV6_VERSION, ipv6Packet.getVersion());
  }

  @Test
  public void testGetProtocol() {
    assertEquals(UDP_PROTOCOL, ipv6Packet.getProtocol());
  }

  @Test
  public void testGetPayload() {
    byte[] payload = Arrays.copyOfRange(RAW_IP_PACKET, IPV6_HEADER_LENGTH, RAW_IP_PACKET.length);
    assertArrayEquals(payload, ipv6Packet.getPayload());
  }

  @Test
  public void testGetAddresses() throws UnknownHostException {
    assertEquals(
        InetAddress.getByName("2620::1003:512:a95b:de22:50a8:a81"), ipv6Packet.getSourceAddress());
    assertEquals(InetAddress.getByName("2001:4860:4860::8888"), ipv6Packet.getDestAddress());
  }

  @Test
  public void testGetRawPacket() {
    assertArrayEquals(RAW_IP_PACKET, ipv6Packet.getRawPacket());
  }

  @Test
  public void testSerialization() throws UnknownHostException {
    InetAddress sourceAddress = InetAddress.getByName("fddd:dead:beef::1");
    InetAddress destAddress = InetAddress.getByName("8888::8888");
    // Should be at least 8 bytes, the UDP header length.
    byte[] payload = {
        (byte) 't', (byte) 'e', (byte) 's', (byte) 't', (byte) 1, (byte) 2, (byte) 3, (byte) 4
    };

    ipv6Packet = new Ipv6Packet(UDP_PROTOCOL, sourceAddress, destAddress, payload);
    Ipv6Packet deserializedPacket = new Ipv6Packet(ByteBuffer.wrap(ipv6Packet.getRawPacket()));

    assertEquals(IPV6_VERSION, deserializedPacket.getVersion());
    assertEquals(UDP_PROTOCOL, deserializedPacket.getProtocol());
    assertEquals(sourceAddress, deserializedPacket.getSourceAddress());
    assertEquals(destAddress, deserializedPacket.getDestAddress());
    assertArrayEquals(payload, deserializedPacket.getPayload());
    assertArrayEquals(ipv6Packet.getRawPacket(), deserializedPacket.getRawPacket());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidUdpChecksum() {
    // Modify the packet's payload so that its computed checksum does not validate.
    byte[] rawPacket = Arrays.copyOf(RAW_IP_PACKET, RAW_IP_PACKET.length);
    rawPacket[rawPacket.length - 1] = 0xF;
    ipv6Packet = new Ipv6Packet(ByteBuffer.wrap(rawPacket));
  }
}
