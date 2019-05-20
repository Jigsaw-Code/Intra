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

import android.util.Log;
import app.intra.net.dns.DnsUdpQuery;
import app.intra.sys.LogWrapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Static class for performing queries on a DNS-over-HTTPS ServerConnection.
 */
public class Resolver {
  private static final String LOG_TAG = "Resolver";

  /**
   * Send a query.
   * @param serverConnection The connection to use for the query
   * @param query The query information parsed from the packet
   * @param dnsPacketData The raw data of the DNS query (starting with the ID number)
   * @param responseWriter The object that will receive the response when it's ready.
   */
  public static void processQuery(ServerConnection serverConnection, DnsUdpQuery query,
                           byte[] dnsPacketData, ResponseWriter responseWriter) {
    try {
      serverConnection.performDnsRequest(query, dnsPacketData,
          new Resolver.DnsResponseCallback(serverConnection, query, responseWriter));
    } catch (NullPointerException e) {
      Transaction transaction = new Transaction(query);
      transaction.status = Transaction.Status.SEND_FAIL;
      responseWriter.sendResult(query, transaction);
    }
  }

  /**
   * A callback object to listen for a DNS response. The caller should create one such object for
   * each DNS request. Responses will run on a reader thread owned by OkHttp.
   */
  private static class DnsResponseCallback implements Callback {

    private final ServerConnection serverConnection;
    private final ResponseWriter responseWriter;
    private final DnsUdpQuery dnsUdpQuery;
    private final Transaction transaction;

    /**
     * Constructs a callback object to listen for a DNS response
     *
     * @param request Represents the request. Used to know the request ID, and the client's ip and
     * port.
     * @param responseWriter Receives the response
     */
    DnsResponseCallback(ServerConnection serverConnection, DnsUdpQuery request,
                        ResponseWriter responseWriter) {
      this.serverConnection = serverConnection;
      dnsUdpQuery = request;
      this.responseWriter = responseWriter;
      transaction = new Transaction(request);
    }

    // Writes |dnsRequestId| to |dnsResponse|'s ID header.
    private void writeRequestIdToDnsResponse(byte[] dnsResponse, short dnsRequestId)
        throws BufferOverflowException {
      ByteBuffer buffer = ByteBuffer.wrap(dnsResponse);
      buffer.putShort(dnsRequestId);
    }

    private void sendResult() {
      responseWriter.sendResult(dnsUdpQuery, transaction);
    }

    @Override
    public void onFailure(Call call, IOException e) {
      transaction.status = call.isCanceled() ?
          Transaction.Status.CANCELED : Transaction.Status.SEND_FAIL;
      LogWrapper.log(Log.WARN, LOG_TAG, "Failed to read HTTPS response: " + e.toString());
      if (e instanceof SocketTimeoutException) {
        LogWrapper.log(Log.WARN, LOG_TAG, "Workaround for OkHttp3 #3146: resetting");
        try {
          serverConnection.reset();
        } catch (NullPointerException npe) {
          LogWrapper.log(Log.WARN, LOG_TAG,
              "Unlikely race: Null server connection at reset.");
        }
      }
      sendResult();
    }

    // Populate |transaction| from the headers and body of |response|.
    private void processResponse(Response response) {
      transaction.serverIp = response.header(IpTagInterceptor.HEADER_NAME);
      if (!response.isSuccessful()) {
        transaction.status = Transaction.Status.HTTP_ERROR;
        return;
      }
      byte[] dnsResponse;
      try {
        dnsResponse = response.body().bytes();
      } catch (IOException e) {
        transaction.status = Transaction.Status.BAD_RESPONSE;
        return;
      }
      try {
        writeRequestIdToDnsResponse(dnsResponse, dnsUdpQuery.requestId);
      } catch (BufferOverflowException e) {
        LogWrapper.log(Log.WARN, LOG_TAG, "ID replacement failed");
        transaction.status = Transaction.Status.BAD_RESPONSE;
        return;
      }
      DnsUdpQuery parsedDnsResponse = DnsUdpQuery.fromUdpBody(dnsResponse);
      if (parsedDnsResponse != null) {
        Log.d(LOG_TAG, "RNAME: " + parsedDnsResponse.name + " NAME: " + dnsUdpQuery.name);
        if (!dnsUdpQuery.name.equals(parsedDnsResponse.name)) {
          LogWrapper.log(Log.ERROR, LOG_TAG, "Mismatch in request and response names.");
          transaction.status = Transaction.Status.BAD_RESPONSE;
          return;
        }
      }

      transaction.status = Transaction.Status.COMPLETE;
      transaction.response = dnsResponse;
    }

    @Override
    public void onResponse(Call call, Response response) {
      processResponse(response);
      sendResult();
    }
  }
}
