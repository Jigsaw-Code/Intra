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
package app.intra.net.doh.google;

import app.intra.net.dns.DnsPacket;
import app.intra.net.dns.DnsUdpPacket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import app.intra.net.doh.DualStackResult;
import app.intra.net.doh.TestDnsCallback;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GoogleServerConnectionIntegrationTest {
    private static final byte[] QUERY_DATA = {
        -107, -6,  // [0-1]   query ID
        1, 0,      // [2-3]   flags, RD=1
        0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
        0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
        0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
        // Start of first question
        7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1   // QCLASS = IN (Internet)
    };

    private GoogleServerDatabase mockDb;

    @Before
    public void setUp() throws Exception {
        mockDb = mock(GoogleServerDatabase.class);
    }

    @After
    public void tearDown() throws Exception {
        mockDb = null;
    }

    @Test
    public void testGet() throws Exception {
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getAllByName
                ("dns.google.com")));
        GoogleServerConnection s = GoogleServerConnection.get(mockDb);
        assertNotNull(s);
        verify(mockDb, times(1)).setPreferred(any(DualStackResult.class));
    }

    @Test
    public void testPerformDnsRequest() throws Exception {
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getAllByName
                ("dns.google.com")));
        GoogleServerConnection s = GoogleServerConnection.get(mockDb);

        TestDnsCallback cb = new TestDnsCallback();
        DnsPacket query = new DnsPacket(QUERY_DATA);
        s.performDnsRequest(query, cb);
        cb.semaphore.acquire();  // Wait for the response.
        assertNotNull(cb.response);
        assertEquals(200, cb.response.code());
        assertTrue(cb.response.body().contentLength() > 6);

        verify(mockDb, times(1)).setPreferred(any(DualStackResult.class));
    }

    @Test
    public void testGetSomeBadAddresses() throws Exception {
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getByName
                ("192.0.2.1"), InetAddress.getByName("dns.google.com")));

        GoogleServerConnection s = GoogleServerConnection.get(mockDb);
        assertNotNull(s);

        verify(mockDb, times(1)).setPreferred(any(DualStackResult.class));
    }

    @Test
    public void testGetAllBadAddresses() throws Exception {
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getByName
                ("192.0.2.1")));

        GoogleServerConnection s = GoogleServerConnection.get(mockDb);
        assertNull(s);
        verify(mockDb, never()).setPreferred(any(DualStackResult.class));
    }

    @Test
    public void testGetIpV4() throws Exception {
        Inet4Address address = null;
        for (InetAddress a : InetAddress.getAllByName("dns.google.com")) {
            if (a instanceof Inet4Address) {
                address = (Inet4Address) a;
                break;
            }
        }
        assertNotNull("Client has no IPv4 support!", address);
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getByName
                ("192.0.2.1"), address));

        GoogleServerConnection s = GoogleServerConnection.get(mockDb);
        assertNotNull(s);

        verify(mockDb, times(1)).setPreferred(any(DualStackResult.class));
    }

    @Test
    public void testGetIpV6() throws Exception {
        Inet6Address address = null;
        for (InetAddress a : InetAddress.getAllByName("dns.google.com")) {
            if (a instanceof Inet6Address) {
                address = (Inet6Address) a;
                break;
            }
        }
        if (address == null) {
            // No IPv6 support.
            return;
        }
        when(mockDb.lookup("dns.google.com")).thenReturn(Arrays.asList(InetAddress.getByName
                ("192.0.2.1"), address));

        GoogleServerConnection s = GoogleServerConnection.get(mockDb);
        assertNotNull(s);

        TestDnsCallback cb = new TestDnsCallback();
        DnsPacket query = new DnsPacket(QUERY_DATA);
        s.performDnsRequest(query, cb);
        cb.semaphore.acquire();  // Wait for the response.
        assertNotNull(cb.response);
        assertEquals(200, cb.response.code());
        assertTrue(cb.response.body().contentLength() > 6);

        verify(mockDb, times(1)).setPreferred(any(DualStackResult.class));
    }
}
