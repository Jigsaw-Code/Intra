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

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DiversitySamplerTest {

    @Test
    public void testOne() throws Exception {
        List<InetAddress> l = new ArrayList<>();
        InetAddress address = InetAddress.getByName("192.0.2.1");
        l.add(address);
        DiversitySampler d = new DiversitySampler(l);
        assertEquals(address, d.choose());
        assertEquals(address, d.choose());
        assertEquals(address, d.choose());
    }

    @Test
    public void testTwo() throws Exception {
        List<InetAddress> l = new ArrayList<>();
        InetAddress a1 = InetAddress.getByName("192.0.2.1");
        InetAddress a2 = InetAddress.getByName("192.0.2.2");
        l.add(a1);
        l.add(a2);
        DiversitySampler d = new DiversitySampler(l);
        InetAddress c1 = d.choose();
        InetAddress c2 = d.choose();
        assertTrue(c1 == a1 || c1 == a2);
        assertTrue(c2 == a1 || c2 == a2);
        assertNotEquals(c1, c2);
        InetAddress c3 = d.choose();
        assertTrue(c3 == a1 || c3 == a2);
    }

    @Test
    public void testTwoV6() throws Exception {
        List<InetAddress> l = new ArrayList<>();
        InetAddress a1 = InetAddress.getByName("2001:db8::1");
        InetAddress a2 = InetAddress.getByName("2001:db8::2");
        l.add(a1);
        l.add(a2);
        DiversitySampler d = new DiversitySampler(l);
        InetAddress c1 = d.choose();
        InetAddress c2 = d.choose();
        assertTrue(c1 == a1 || c1 == a2);
        assertTrue(c2 == a1 || c2 == a2);
        assertNotEquals(c1, c2);
        InetAddress c3 = d.choose();
        assertTrue(c3 == a1 || c3 == a2);
    }

    private byte[] or(byte[] a, byte[] b) {
        byte[] c = new byte[a.length];
        for (int i = 0; i < a.length; ++i) {
            c[i] = (byte)(a[i] | b[i]);
        }
        return c;
    }

    @Test
    public void testMaxExact() throws Exception {
        // Create a test set of IPv6 addresses with the following structure:
        // [ 2*x 0s] 1100...00y for x = 0-31, y = 0 or 1
        // Then the first 16 choices should each have a different value of x.
        List<InetAddress> population = new ArrayList<>();
        for (int x = 0; x < 32; ++x) {
            byte[] a = new byte[16];
            a[x / 4] = (byte)(0x3 << ((x % 4) * 2));
            population.add(InetAddress.getByAddress(a));
            byte[] b = a.clone();
            b[15] = 1;
            population.add(InetAddress.getByAddress(b));
        }
        DiversitySampler d = new DiversitySampler(population);
        byte[] mask = new byte[16];
        for (int i = 0; i < 16; ++i) {
            mask = or(mask, d.choose().getAddress());
        }
        int bits = 0;
        for (int i = 0; i < 8; ++i) {
            bits += Integer.bitCount(mask[i] & 0xff);
        }
        assertEquals(bits, 32);
    }
}