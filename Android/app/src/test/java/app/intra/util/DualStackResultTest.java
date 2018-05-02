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

import org.junit.Before;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedList;

import static org.junit.Assert.*;

public class DualStackResultTest {
    private static String[] IPV4_SAMPLES = {"192.0.2.1", "192.0.2.2", "192.0.2.3"};
    private static String[] IPV6_SAMPLES = {"2001:db8::1", "2001:db8::2", "2001:db8::3"};

    private InetAddress[] IPV4_OBJECTS = new InetAddress[IPV4_SAMPLES.length];
    private InetAddress[] IPV6_OBJECTS = new InetAddress[IPV6_SAMPLES.length];

    @Before
    public void setUp() throws Exception {
        for (int i = 0; i < IPV4_SAMPLES.length; ++i) {
            IPV4_OBJECTS[i] = Inet4Address.getByName(IPV4_SAMPLES[i]);
        }
        for (int i = 0; i < IPV6_SAMPLES.length; ++i) {
            IPV6_OBJECTS[i] = Inet6Address.getByName(IPV6_SAMPLES[i]);
        }
    }

    private void assertIpsEqual(String[] a, String[] b) throws Exception {
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; ++i) {
            assertEquals(InetAddress.getByName(a[i]), InetAddress.getByName(b[i]));
        }
    }

    @Test
    public void testGetInterleaved() throws Exception {
        DualStackResult r = new DualStackResult(IPV4_SAMPLES, IPV6_SAMPLES);
        assertIpsEqual(IPV4_SAMPLES, r.getV4());
        assertIpsEqual(IPV6_SAMPLES, r.getV6());

        LinkedList<InetAddress> interleaved = r.getInterleaved();
        assertEquals(IPV6_OBJECTS[0], interleaved.pop());
        assertEquals(IPV4_OBJECTS[0], interleaved.pop());
        assertEquals(IPV6_OBJECTS[1], interleaved.pop());
        assertEquals(IPV4_OBJECTS[1], interleaved.pop());
        assertEquals(IPV6_OBJECTS[2], interleaved.pop());
        assertEquals(IPV4_OBJECTS[2], interleaved.pop());
        assertEquals(0, interleaved.size());
    }

    @Test
    public void testGetInterleavedV4Only() throws Exception {
        DualStackResult r = new DualStackResult(IPV4_SAMPLES, new String[0]);
        assertIpsEqual(IPV4_SAMPLES, r.getV4());
        assertEquals(0, r.getV6().length);

        LinkedList<InetAddress> interleaved = r.getInterleaved();
        assertEquals(IPV4_OBJECTS[0], interleaved.pop());
        assertEquals(IPV4_OBJECTS[1], interleaved.pop());
        assertEquals(IPV4_OBJECTS[2], interleaved.pop());
        assertEquals(0, interleaved.size());
    }

    @Test
    public void testGetInterleavedV6Only() throws Exception {
        DualStackResult r = new DualStackResult(new String[0], IPV6_SAMPLES);
        assertEquals(0, r.getV4().length);
        assertIpsEqual(IPV6_SAMPLES, r.getV6());

        LinkedList<InetAddress> interleaved = r.getInterleaved();
        assertEquals(IPV6_OBJECTS[0], interleaved.pop());
        assertEquals(IPV6_OBJECTS[1], interleaved.pop());
        assertEquals(IPV6_OBJECTS[2], interleaved.pop());
        assertEquals(0, interleaved.size());
    }

    @Test
    public void testGetInterleavedV6Longer() throws Exception {
        DualStackResult r = new DualStackResult(Arrays.copyOfRange(IPV4_SAMPLES, 0, 1),
                IPV6_SAMPLES);
        assertEquals(1, r.getV4().length);
        assertIpsEqual(IPV6_SAMPLES, r.getV6());

        LinkedList<InetAddress> interleaved = r.getInterleaved();
        assertEquals(IPV6_OBJECTS[0], interleaved.pop());
        assertEquals(IPV4_OBJECTS[0], interleaved.pop());
        assertEquals(IPV6_OBJECTS[1], interleaved.pop());
        assertEquals(IPV6_OBJECTS[2], interleaved.pop());
        assertEquals(0, interleaved.size());
    }

    @Test
    public void testGetInterleavedV4Longer() throws Exception {
        DualStackResult r = new DualStackResult(IPV4_SAMPLES,
                Arrays.copyOfRange(IPV6_SAMPLES, 0, 2));
        assertIpsEqual(IPV4_SAMPLES, r.getV4());
        assertEquals(2, r.getV6().length);

        LinkedList<InetAddress> interleaved = r.getInterleaved();
        assertEquals(IPV6_OBJECTS[0], interleaved.pop());
        assertEquals(IPV4_OBJECTS[0], interleaved.pop());
        assertEquals(IPV6_OBJECTS[1], interleaved.pop());
        assertEquals(IPV4_OBJECTS[1], interleaved.pop());
        assertEquals(IPV4_OBJECTS[2], interleaved.pop());
        assertEquals(0, interleaved.size());
    }
}