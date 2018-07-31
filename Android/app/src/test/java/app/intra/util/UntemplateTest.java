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

import static org.junit.Assert.*;

public class UntemplateTest {

  private void assertUnmodified(String input) {
    assertEquals(input, Untemplate.strip(input));
  }

  @Test
  public void testUnmodified() throws Exception {
    assertUnmodified("");
    assertUnmodified("https://foo.example/");
  }

  @Test
  public void testTemplateBasic() throws Exception {
    assertEquals("", Untemplate.strip("{test}"));
    assertEquals("https://foo.example/query",
        Untemplate.strip("https://foo.example/query{?dns}"));
  }

  @Test
  public void testTemplateWeird() throws Exception {
    assertEquals("https://foo.example/prefix/suffix",
        Untemplate.strip("https://foo.example/prefix{/dns}/suffix"));
  }

}