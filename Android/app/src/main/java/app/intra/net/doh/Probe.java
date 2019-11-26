/*
Copyright 2019 Jigsaw Operations LLC

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

/**
 * Implements an asynchronous check to determine whether a DOH server is working.  Each instance can
 * only be used once.
 */
public abstract class Probe extends Thread {
  protected static final byte[] QUERY_DATA = {
      0, 0,  // [0-1]   query ID
      1, 0,  // [2-3]   flags, RD=1
      0, 1,  // [4-5]   QDCOUNT (number of queries) = 1
      0, 0,  // [6-7]   ANCOUNT (number of answers) = 0
      0, 0,  // [8-9]   NSCOUNT (number of authoritative answers) = 0
      0, 0,  // [10-11] ARCOUNT (number of additional records) = 0
      // Start of first query
      7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
      3, 'c', 'o', 'm',
      0,  // null terminator of FQDN (DNS root)
      0, 1,  // QTYPE = A
      0, 1   // QCLASS = IN (Internet)
  };

  enum Status { NEW, RUNNING, SUCCEEDED, FAILED }
  protected Status status = Status.NEW;

  public interface Callback {
    void onSuccess();
    void onFailure();
  }
  protected Callback callback;

  public synchronized Status getStatus() {
    return status;
  }

  protected synchronized void setStatus(Status s) {
    status = s;
  }

  protected void succeed() {
    setStatus(Status.SUCCEEDED);
    callback.onSuccess();
  }

  protected void fail() {
    setStatus(Status.FAILED);
    callback.onFailure();
  }
}
