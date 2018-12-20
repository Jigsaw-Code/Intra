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
import androidx.annotation.NonNull;
import app.intra.net.dns.DnsUdpPacket;
import app.intra.net.dns.Question;
import app.intra.sys.LogWrapper;
import com.google.firebase.crash.FirebaseCrash;
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
   * @param response HTTP response
   * @return Elapsed time since this response's implied creation, in milliseconds.
   */
  static long getResponseAgeMs(Response response) {
    int age = 0;
    try {
      String ageHeader = response.header("Age");
      if (ageHeader != null) {
        age = Integer.parseInt(ageHeader);
      }
    } catch (NumberFormatException e) {
    }

    long impliedDate = response.receivedResponseAtMillis() - age * 1000;
    return System.currentTimeMillis() - impliedDate;
  }

  /**
   * Send a query.
   * @param serverConnection The connection to use for the query
   * @param query The parsed query packet
   * @param responseWriter The object that will receive the response when it's ready.
   */
  public static void processQuery(ServerConnection serverConnection, DnsUdpPacket query,
      ResponseWriter responseWriter) {
    try {
      serverConnection.performDnsRequest(query.dns,
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
    private final DnsUdpPacket udpQuery;
    private final Transaction transaction;

    /**
     * Constructs a callback object to listen for a DNS response
     *
     * @param request Represents the request. Used to know the request ID, and the client's ip and
     * port.
     * @param responseWriter Receives the response
     */
    DnsResponseCallback(ServerConnection serverConnection, DnsUdpPacket request,
                        ResponseWriter responseWriter) {
      this.serverConnection = serverConnection;
      udpQuery = request;
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
      responseWriter.sendResult(udpQuery, transaction);
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
      transaction.status = call.isCanceled() ?
          Transaction.Status.CANCELED : Transaction.Status.SEND_FAIL;
      LogWrapper.logcat(Log.WARN, LOG_TAG, "Failed to read HTTPS response: " + e.toString());
      if (e instanceof SocketTimeoutException) {
        FirebaseCrash.logcat(Log.WARN, LOG_TAG, "Workaround for OkHttp3 #3146: resetting");
        try {
          serverConnection.reset();
        } catch (NullPointerException npe) {
          FirebaseCrash.logcat(Log.WARN, LOG_TAG,
              "Unlikely race: Null server connection at reset.");
        }
      }
      sendResult();
    }

    // Populate |transaction| from the headers and body of |response|.
    private void processResponse(Response response) {
      transaction.serverIp = response.header(IpTagInterceptor.HEADER_NAME);
      transaction.cacheStatus = response.header(CachingServerConnection.STATUS_HEADER);
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
        writeRequestIdToDnsResponse(dnsResponse, udpQuery.dns.getId());
      } catch (BufferOverflowException e) {
        FirebaseCrash.logcat(Log.WARN, LOG_TAG, "ID replacement failed");
        transaction.status = Transaction.Status.BAD_RESPONSE;
        return;
      }
      DnsUdpPacket parsedDnsResponse = DnsUdpPacket.fromUdpBody(dnsResponse);
      if (parsedDnsResponse != null) {
        Question originalQuestion = udpQuery.dns.getQuestion();
        Question echoQuestion = parsedDnsResponse.dns.getQuestion();
        Log.d(LOG_TAG, "RNAME: " + echoQuestion.name + " NAME: " + originalQuestion.name);
        if (!originalQuestion.equals(echoQuestion)) {
          FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Mismatch in request and response.");
          transaction.status = Transaction.Status.BAD_RESPONSE;
          return;
        }
      }

      // Update TTL based on how long the response has been in the local cache or an upstream cache.
      parsedDnsResponse.dns.reduceTTL((int)(getResponseAgeMs(response) / 1000));

      transaction.status = Transaction.Status.COMPLETE;
      transaction.response = parsedDnsResponse.dns.getData();
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
      processResponse(response);
      sendResult();
    }
  }
}
