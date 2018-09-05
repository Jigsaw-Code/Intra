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

import android.util.Log;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import app.intra.util.DnsUdpQuery;
import app.intra.util.DnsTransaction;
import app.intra.util.IpTagInterceptor;
import app.intra.util.LogWrapper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Static class for performing queries on a DNS-over-HTTPS ServerConnection.
 */
class DnsResolverUdpToHttps {
  private static final String LOG_TAG = "DnsResolverUdpToHttps";

  /**
   * Send a query.
   * @param serverConnection The connection to use for the query
   * @param query The query information parsed from the packet
   * @param dnsPacketData The raw data of the DNS query (starting with the ID number)
   * @param responseWriter The object that will receive the response when it's ready.
   */
  static void processQuery(ServerConnection serverConnection, DnsUdpQuery query,
                           byte[] dnsPacketData, DnsResponseWriter responseWriter) {
    try {
      serverConnection.performDnsRequest(query, dnsPacketData,
          new DnsResolverUdpToHttps.DnsResponseCallback(serverConnection, query, responseWriter));
    } catch (NullPointerException e) {
      DnsTransaction transaction = new DnsTransaction(query);
      transaction.status = DnsTransaction.Status.SEND_FAIL;
      responseWriter.sendResult(query, transaction);
    }
  }

  /**
   * A callback object to listen for a DNS response. The caller should create one such object for
   * each DNS request. Responses will run on a reader thread owned by OkHttp.
   */
  private static class DnsResponseCallback implements Callback {

    private final ServerConnection serverConnection;
    private final DnsResponseWriter responseWriter;
    private final DnsUdpQuery dnsUdpQuery;
    private final DnsTransaction transaction;

    /**
     * Constructs a callback object to listen for a DNS response
     *
     * @param request Represents the request. Used to know the request ID, and the client's ip and
     * port.
     * @param responseWriter Receives the response
     */
    DnsResponseCallback(ServerConnection serverConnection, DnsUdpQuery request,
                        DnsResponseWriter responseWriter) {
      this.serverConnection = serverConnection;
      dnsUdpQuery = request;
      this.responseWriter = responseWriter;
      transaction = new DnsTransaction(request);
    }

    // Writes |dnsRequestId| to |dnsResponse|'s ID header.
    private void writeRequestIdToDnsResponse(byte[] dnsResponse, short dnsRequestId) {
      ByteBuffer buffer = ByteBuffer.wrap(dnsResponse);
      buffer.putShort(dnsRequestId);
    }

    private void sendResult() {
      responseWriter.sendResult(dnsUdpQuery, transaction);
    }

    @Override
    public void onFailure(Call call, IOException e) {
      transaction.status = call.isCanceled() ?
          DnsTransaction.Status.CANCELED : DnsTransaction.Status.SEND_FAIL;
      LogWrapper.logcat(Log.WARN, LOG_TAG, "Failed to read HTTPS response: " + e.toString());
      if (e instanceof SocketTimeoutException) {
        LogWrapper.logcat(Log.WARN, LOG_TAG, "Workaround for OkHttp3 #3146: resetting");
        try {
          serverConnection.reset();
        } catch (NullPointerException npe) {
          LogWrapper.logcat(Log.WARN, LOG_TAG,
              "Unlikely race: Null server connection at reset.");
        }
      }
      sendResult();
    }

    @Override
    public void onResponse(Call call, Response response) {
      transaction.serverIp = response.header(IpTagInterceptor.HEADER_NAME);
      if (!response.isSuccessful()) {
        transaction.status = DnsTransaction.Status.HTTP_ERROR;
        sendResult();
        return;
      }
      byte[] dnsResponse;
      try {
        dnsResponse = response.body().bytes();
      } catch (IOException e) {
        transaction.status = DnsTransaction.Status.BAD_RESPONSE;
        sendResult();
        return;
      }
      writeRequestIdToDnsResponse(dnsResponse, dnsUdpQuery.requestId);
      DnsUdpQuery parsedDnsResponse = DnsUdpQuery.fromUdpBody(dnsResponse);
      if (parsedDnsResponse != null) {
        Log.d(LOG_TAG, "RNAME: " + parsedDnsResponse.name + " NAME: " + dnsUdpQuery.name);
        if (!dnsUdpQuery.name.equals(parsedDnsResponse.name)) {
          LogWrapper.logcat(Log.ERROR, LOG_TAG, "Mismatch in request and response names.");
          transaction.status = DnsTransaction.Status.BAD_RESPONSE;
          sendResult();
          return;
        }
      }

      transaction.status = DnsTransaction.Status.COMPLETE;
      transaction.response = dnsResponse;

      sendResult();
    }
  }
}
