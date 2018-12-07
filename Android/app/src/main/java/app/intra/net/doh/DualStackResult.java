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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

public class DualStackResult {

  private final List<InetAddress> v4 = new LinkedList<>();
  private final List<InetAddress> v6 = new LinkedList<>();

  public DualStackResult(String[] v4, String[] v6) {
    for (String address : v4) {
      try {
        this.v4.add(Inet4Address.getByName(address));
      } catch (UnknownHostException e) {
        // This should never happen.
      }
    }

    for (String address : v6) {
      try {
        this.v6.add(Inet6Address.getByName(address));
      } catch (UnknownHostException e) {
        // This should never happen.
      }
    }
  }

  DualStackResult(List<InetAddress> addresses) {
    for (InetAddress address : addresses) {
      if (address instanceof Inet4Address) {
        v4.add(address);
      } else if (address instanceof Inet6Address) {
        v6.add(address);
      }
    }

  }

  private String[] getStrings(List<InetAddress> addresses) {
    String[] strings = new String[addresses.size()];
    for (int i = 0; i < addresses.size(); ++i) {
      strings[i] = addresses.get(i).getHostAddress();
    }
    return strings;
  }

  public String[] getV4() {
    return getStrings(v4);
  }

  public String[] getV6() {
    return getStrings(v6);
  }

  public LinkedList<InetAddress> getInterleaved() {
    LinkedList<InetAddress> interleaved = new LinkedList<InetAddress>();
    for (int i = 0; i < Math.max(v4.size(), v6.size()); ++i) {
      if (i < v6.size()) {
        interleaved.add(v6.get(i));
      }
      if (i < v4.size()) {
        interleaved.add(v4.get(i));
      }
    }
    return interleaved;
  }
}
