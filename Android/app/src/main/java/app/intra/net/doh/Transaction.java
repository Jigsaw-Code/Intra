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

import app.intra.net.dns.DnsPacket;
import java.io.Serializable;
import java.util.Calendar;

/**
 * A representation of a complete DNS transaction, whether it succeeded or failed.
 */
public class Transaction implements Serializable {

  public enum Status {
    COMPLETE,
    SEND_FAIL,
    HTTP_ERROR,
    BAD_RESPONSE,
    INTERNAL_ERROR,
    CANCELED
  }

  public enum StatusDetail {
    NO_DETAIL,
    AUTHENTICATION_REQUESTED,
    CERTIFICATE_REQUESTED
  }

  public Transaction(DnsPacket query, long timestamp) {
    this.name = query.getQueryName();
    this.type = query.getQueryType();
    this.queryTime = timestamp;
  }

  public final long queryTime;
  public final String name;
  public final short type;
  public long responseTime;
  public Status status;
  public StatusDetail statusDetail;
  public byte[] response;
  public Calendar responseCalendar;
  public String serverIp;
}
