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
package app.intra.net.doh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import app.intra.net.dns.DnsUdpQuery;
import app.intra.net.dns.DnsPacket;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class StandardServerConnectionIntegrationTest {

    private static final String CLOUDFLARE_URL = "https://cloudflare-dns.com/dns-query";
    private static final List<InetAddress> EMPTY_LIST = new ArrayList<>();

    private static final byte[] QUERY_DATA = {
        -107, -6,  // [0-1]   query ID
        1, 0,      // [2-3]   flags, RD=1
        0, 1,      // [4-5]   QDCOUNT (number of queries) = 1
        0, 0,      // [6-7]   ANCOUNT (number of answers) = 0
        0, 0,      // [8-9]   NSCOUNT (number of authoritative answers) = 0
        0, 0,      // [10-11] ARCOUNT (number of additional records) = 0
        // Start of first query
        7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
        3, 'c', 'o', 'm',
        0,  // null terminator of FQDN (DNS root)
        0, 1,  // QTYPE = A
        0, 1   // QCLASS = IN (Internet)
    };


    @Test
    public void testGet() throws Exception {
        StandardServerConnection s = StandardServerConnection.get(CLOUDFLARE_URL, EMPTY_LIST);
        assertNotNull(s);
    }

    @Test
    public void testGetBadUrl() throws Exception {
        assertNull(StandardServerConnection.get("ftp://www.example.com", EMPTY_LIST));
        assertNull(StandardServerConnection.get("https://www.example", EMPTY_LIST));
    }

    @Test
    public void testPerformDnsRequest() throws Exception {
        StandardServerConnection s = StandardServerConnection.get(CLOUDFLARE_URL, EMPTY_LIST);

        TestDnsCallback cb = new TestDnsCallback();
        DnsUdpQuery metadata = new DnsUdpQuery();
        metadata.name = "youtube.com";
        metadata.type = 1;
        s.performDnsRequest(metadata, QUERY_DATA, cb);
        cb.semaphore.acquire();  // Wait for the response.
        assertNotNull(cb.response);
        assertEquals(200, cb.response.code());
        DnsPacket response = new DnsPacket(cb.response.body().bytes());
        assertEquals(0, response.getId());
        assertTrue(response.isResponse());
        assertEquals("youtube.com.", response.getQueryName());
        assertEquals(1, response.getQueryType());
        assertFalse(response.getResponseAddresses().isEmpty());
    }
}
