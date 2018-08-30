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
package app.intra;

import app.intra.util.DnsUdpQuery;

import okhttp3.Callback;

/**
 * Generic representation of a connection to a DNS-over-HTTPS server.
 */
public interface ServerConnection {

  /**
   * NONE: There is no connection
   * NEW: The connection has not yet completed bootstrap.
   * WORKING: The last query (or bootstrap) succeeded.
   * FAILING: The last query (or bootstrap) failed.
   */
  enum State {
    NEW { int message() { return R.string.connecting; } },
    WORKING { int message() { return R.string.connected; } },
    FAILING { int message() { return R.string.connection_warning; } };
    abstract int message();
  };

  /**
   * Performs a binary, asynchronous DNS request over HTTPS.
   *
   * @param metadata Information about the request
   * @param data The request body
   * @param cb An OkHttp response callback to receive the result.
   */
  void performDnsRequest(final DnsUdpQuery metadata, final byte[] data, Callback cb);

  /**
   * @return The URL identifying this ServerConnection.
   */
  String getUrl();

  /**
   * Reset the state of the connection.  Users can call this if they believe the connection has
   * gotten into an invalid state.
   * Workaround for https://github.com/square/okhttp/issues/3146
   */
  void reset();
}
