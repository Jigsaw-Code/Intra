/*
Copyright 2020 Jigsaw Operations LLC

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
package app.intra.sys.firebase;

import org.junit.Test;

import static org.junit.Assert.*;

public class RemoteConfigTest {

    @Test
    public void getExtraIPKey() {
        assertEquals("extra_ips_foo", RemoteConfig.getExtraIPKey("foo"));
        assertEquals("extra_ips_foo_com", RemoteConfig.getExtraIPKey("foo.com"));
        assertEquals("extra_ips_www_foo_com", RemoteConfig.getExtraIPKey("www.foo.com"));
        assertEquals("extra_ips_foo_bar_com", RemoteConfig.getExtraIPKey("foo-bar.com"));
        assertEquals("extra_ips_foo7_com", RemoteConfig.getExtraIPKey("foo7.com"));

        assertEquals("extra_ips_foo_com", RemoteConfig.getExtraIPKey("FOO.COM"));

        assertEquals("extra_ips_192_0_2_1", RemoteConfig.getExtraIPKey("192.0.2.1"));
        assertEquals("extra_ips__1234_abcd__2_", RemoteConfig.getExtraIPKey("[1234:abcd::2]"));
    }
}
