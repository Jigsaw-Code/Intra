/*
Copyright 2026 Jigsaw Operations LLC

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
package app.intra.ui.settings;

import org.junit.Test;

import static org.junit.Assert.*;

public class ServerChooserFragmentTest {

  @Test
  public void testValidCustomUrl() throws Exception {
    assertTrue(ServerChooserFragment.isValidCustomUrl("https://foo.example/dns-query"));
    assertTrue(ServerChooserFragment.isValidCustomUrl("https://foo.example/"));
    assertTrue(ServerChooserFragment.isValidCustomUrl(" https://foo.example/dns-query\n"));
  }

  @Test
  public void testValidCustomUrlTemplate() throws Exception {
    assertTrue(ServerChooserFragment.isValidCustomUrl("https://foo.example/dns-query{?dns}"));
  }

  @Test
  public void testInvalidCustomUrlScheme() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("http://foo.example/dns-query"));
  }

  @Test
  public void testInvalidCustomUrlHost() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("https:///dns-query"));
  }

  @Test
  public void testInvalidCustomUrlPath() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("https://foo.example"));
  }

  @Test
  public void testInvalidCustomUrlQuery() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("https://foo.example/dns-query?dns=abc"));
  }

  @Test
  public void testInvalidCustomUrlFragment() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("https://foo.example/dns-query#dns"));
  }

  @Test
  public void testInvalidCustomUrlMalformed() throws Exception {
    assertFalse(ServerChooserFragment.isValidCustomUrl("not a url"));
    assertFalse(ServerChooserFragment.isValidCustomUrl("https://foo.example/dns query"));
    assertFalse(ServerChooserFragment.isValidCustomUrl("https://foo.example:bad/dns-query"));
    assertFalse(ServerChooserFragment.isValidCustomUrl(null));
  }

  @Test
  public void testNormalizeCustomUrl() throws Exception {
    assertEquals("https://foo.example/dns-query",
        ServerChooserFragment.normalizeCustomUrl(" https://foo.example/dns-query\n"));
  }
}
