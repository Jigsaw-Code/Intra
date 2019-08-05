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

import java.nio.BufferUnderflowException;
import org.junit.Test;

import java.net.ProtocolException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class DnsPacketTest {

  @Test
  public void testQuery() throws ProtocolException {
    byte[] data = {
        -107, -6,  // [0-1]   query ID
        1, 0,      // [2-3]   flags, RD=1
        0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
        0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
        0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
        // Start of first query
        5, 'm', 't', 'a', 'l', 'k',
        6, 'g', 'o', 'o', 'g', 'l', 'e',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1   // QCLASS = IN (Internet)
    };
    DnsPacket p = new DnsPacket(data);
    assertEquals(-27142, p.getId());
    assertTrue(p.isNormalQuery());
    assertFalse(p.isResponse());
    assertEquals("mtalk.google.com.", p.getQueryName());
    assertEquals(1, p.getQueryType());
    assertEquals(0, p.getResponseAddresses().size());
  }

  @Test
  public void testResponse() throws ProtocolException {
    byte[] data = {
        -107, -6,    // [0-1]   query ID
        -127, -128,  // [2-3]   flags: RD=1, QR=1, RA=1
        0, 1,        // [4-5]   QDCOUNT (number of queries) = 1
        0, 2,        // [6-7]   ANCOUNT (number of answers) = 2
        0, 0,        // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,        // [10-11] ARCOUNT (number of additional records) = 0
        // First query
        5, 'm', 't', 'a', 'l', 'k',
        6, 'g', 'o', 'o', 'g', 'l', 'e',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1,  // QCLASS = IN (Internet)
        // First answer
        -64, 12,  // Compressed name reference, starting at byte 12: mtalk.google.com
        0, 5,     // QTYPE = CNAME
        0, 1,     // QCLASS = IN (Internet)
        0, 0, 84, 44,  // TTL = 21548s
        0, 17,    // RDLENGTH = 17
        12, 'm', 'o', 'b', 'i', 'l', 'e', '-', 'g', 't', 'a', 'l', 'k',
        1, 'l',
        -64, 18,  // Compressed name reference to byte 18: google.com
        // Second answer
        -64, 46,      // Compressed name reference to byte 46: mobile-gtalk.l.google.com
        0, 1,         // QTYPE = A
        0, 1,         // QCLASS = IN (Internet)
        0, 0, 0, -8,  // TTL = 248
        0, 4,         // RDLEN = 4
        -83, -62, -52, -68   // 173.194.204.188
    };
    DnsPacket p = new DnsPacket(data);
    assertEquals(-27142, p.getId());
    assertFalse(p.isNormalQuery());
    assertTrue(p.isResponse());
    assertEquals("mtalk.google.com.", p.getQueryName());
    assertEquals(1, p.getQueryType());
    assertEquals(1, p.getResponseAddresses().size());
    assertEquals("173.194.204.188", p.getResponseAddresses().get(0).getHostAddress());
  }

  @Test
  public void testNXDOMAIN() throws ProtocolException {
    byte[] data = {
        -9, 16,    // [0-1]   query ID
        -127, -93, // [2-3]   flags: RD=1, QR=1, RA=1
        0, 1,      // [4-5]   QDCOUNT=1
        0, 0,      // [6-7]   ANCOUNT=0
        0, 1,      // [8-9]   NSCOUNT=1
        0, 0,      // [10-11] ARCOUNT=0
        // First question
        12, 'z', 'z', 'q', 'u', 'b', 'e', 'q', 'c', 'l', 'g', 'g', 'z',
        0,  // null terminator of FQDN (DNS root)
        0, 28,  // QTYPE=AAAA
        0, 1,   // QCLASS = IN (Internet)
        // First answer
        0,  // null terminator of FQDN (DNS root)
        0, 6,  // TYPE=SOA
        0, 1,  // CLASS=IN (Internet)
        0, 1, 81, 127,  // TTL=86399
        0, 64,  // RDLENGTH=64
        // MNAME=a.root-servers.net.
        1, 'a',
        12, 'r', 'o', 'o', 't', '-', 's', 'e', 'r', 'v', 'e', 'r', 's',
        3, 'n', 'e', 't',
        0,  // null terminator of FQDN (DNS root)
        // RNAME=nstld.verisign-grs.com.
        5, 'n', 's', 't', 'l', 'd',
        12, 'v', 'e', 'r', 'i', 's', 'i', 'g', 'n', '-', 'g', 'r', 's',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        120, 72, -109, -100,  // SERIAL
        0, 0, 7, 8,           // REFRESH=1800s
        0, 0, 3, -124,        // RETRY=900s
        0, 9, 58, -128,       // EXPIRE=604800s
        0, 1, 81, -128        // MINIMUM=86400s
    };
    DnsPacket p = new DnsPacket(data);
    assertEquals(-2288, p.getId());
    assertFalse(p.isNormalQuery());
    assertTrue(p.isResponse());
    assertEquals("zzqubeqclggz.", p.getQueryName());
    assertEquals(28, p.getQueryType());
    assertEquals(0, p.getResponseAddresses().size());
  }

  @Test
  public void testEmpty() {
    byte[] data = {};
    try {
      DnsPacket p = new DnsPacket(data);
      fail();
    } catch (ProtocolException e) {
    }
  }

  @Test
  public void testTruncated() {
    byte[] data = {
        -107, -6,  // [0-1]   query ID
        1, 0,      // [2-3]   flags, RD=1
        0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
        0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
        0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
        // First question
        5, 'm', 't', 'a', 'l', 'k',
        6, 'g', 'o', 'o', 'g', 'l', 'e',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1   // QCLASS = IN (Internet)
    };
    for (int i = 0; i < data.length; ++i) {
      byte[] truncated = Arrays.copyOf(data, i);
      try {
        DnsPacket p = new DnsPacket(truncated);
        fail();
      } catch (ProtocolException e) {
      }
    }

  }

  @Test
  public void testCompression() throws ProtocolException {
    // Regression test for "negative" second offset byte in a compressed name.
    byte[] data = {
        73, -46,     // [0-1]   query ID
        -127, -128,  // [2-3]   flags: RD=1, QR=1, RA=1
        0, 1,        // [4-5]   QDCOUNT=1
        0, 3,        // [6-7]   ANCOUNT=3
        0, 1,        // [8-9]   NSCOUNT=1
        0, 0,        // [10-11] ARCOUNT=0
        // First Question
        3, 'c', 'd', 'n',
        4, 'k', 'r', 'x', 'd',
        3, 'n', 'e', 't',
        0,  // null terminator of FQDN (DNS root)
        0, 28,  // QTYPE=AAAA
        0, 1,   // QCLASS=IN (Internet)
        // First answer
        -64, 12,       // Compressed name reference, offset=12: cdn.krxd.net.
        0, 5,          // TYPE=CNAME
        0, 1,          // CLASS=IN (Internet)
        0, 0, 1, -69,  // TTL=443s
        0, 23,         // RDLENGTH=23
        20, 'c', 'd', 'n', '-', 't', 'r', 'a', 'f', 'f', 'i', 'c',
        '-', 'd', 'i', 'r', 'e', 'c', 't', 'o', 'r',
        -64, 16,  // Compressed name reference, offset=16: krxd.net.
        // Second answer
        -64, 42,       // Compressed name reference, offset=42: cdn-traffic-director.krxd.net.
        0, 5,          // TYPE=CNAME
        0, 1,          // CLASS=IN (Internet)
        0, 0, 1, -69,  // TTL=443s
        0, 13,         // RDLENGTH=13
        10, 'c', 'd', 'n', '-', 'f', 'a', 's', 't', 'l', 'y',
        -64, 16,       // Compressed name reference, offset=16: krxd.net.
        // Third answer
        -64, 77,       // Compressed name reference, offset=77: cdn-fastly.krxd.net.
        0, 5,          // TYPE=CNAME
        0, 1,          // CLASS=IN (Internet)
        0, 0, 14, 9,   // TTL=3593s
        0, 42,         // RDLENGTH=42
        10, 'c', 'd', 'n', '-', 'f', 'a', 's', 't', 'l', 'y',
        4, 'k', 'r', 'x', 'd',
        3, 'n', 'e', 't',
        1, 'c',
        10, 'g', 'l', 'o', 'b', 'a', 'l', '-', 's', 's', 'l',
        6, 'f', 'a', 's', 't', 'l', 'y',
        -64, 21,       // Compressed name reference, offset=21: net.
        // First authoritative
        -64, -121,     // Compressed name refernce, offset=135:
        // cdn-fastly.krxd.net.c.global-ssl.fastly.net.
        0, 6,          // TYPE=SOA
        0, 1,          // CLASS=IN (internet)
        0, 0, 0, 20,   // TTL=20s
        0, 49,         // RDLENGTH=49
        // MNAME=ns1.cdn-fastly.krxd.net.c.global-ssl.fastly.net.
        3, 'n', 's', '1',
        -64, -121,     // Compressed name refernce, offset=135:
        // cdn-fastly.krxd.net.c.global-ssl.fastly.net.
        // RNAME
        10, 'h', 'o', 's', 't', 'm', 'a', 's', 't', 'e', 'r',
        6, 'f', 'a', 's', 't', 'l', 'y',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        120, 57, -58, 41,  // SERIAL
        0, 0, 14, 16,      // REFRESH=3600s
        0, 0, 2, 88,       // RETRY=600s
        0, 9, 58, -128,    // EXPIRE=604800s
        0, 0, 0, 30        // MINIMUM=30s
    };
    DnsPacket p = new DnsPacket(data);
    assertEquals(18898, p.getId());
    assertFalse(p.isNormalQuery());
    assertTrue(p.isResponse());
    assertEquals("cdn.krxd.net.", p.getQueryName());
    assertEquals(28, p.getQueryType());
    assertEquals(0, p.getResponseAddresses().size());
  }

  @Test
  public void testInfiniteLoop() {
    // Regression test for stack overflow in name compression.
    byte[] data = {
        -107, -6,    // [0-1]   query ID
        -127, -128,  // [2-3]   flags: RD=1, QR=1, RA=1
        0, 1,        // [4-5]   QDCOUNT (number of queries) = 1
        0, 0,        // [6-7]   ANCOUNT (number of answers) = 2
        0, 0,        // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,        // [10-11] ARCOUNT (number of additional records) = 0
        // First query, encoded as an infinite loop (example.example.ex....)
        7, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
        -64, 12,
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1  // QCLASS = IN (Internet)
    };

    try {
      new DnsPacket(data);
      fail("Expected parsing to fail");
    } catch (ProtocolException p) {
      assertTrue(p.getCause() instanceof BufferUnderflowException);
    }
  }
}
