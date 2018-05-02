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

import java.net.InetAddress;

// Class to maintain state about a DNS query over UDP.
public class DnsMetadata {

  public String name;
  public short requestId;
  public short type;
  public InetAddress sourceAddress;
  public InetAddress destAddress;
  public short sourcePort;
  public short destPort;
  public long timestamp;

  public DnsMetadata() {
  }
}
