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
package app.intra.net.doh.google;

import android.util.Log;

import androidx.annotation.WorkerThread;
import app.intra.BuildConfig;
import app.intra.net.dns.DnsUdpQuery;
import app.intra.net.doh.DualStackResult;
import app.intra.net.doh.IpTagInterceptor;
import app.intra.net.doh.ServerConnection;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a connection to the DNS-over-HTTPS server. Contains functionality for finding a
 * server, establishing a connection, and making queries.
 */
public class GoogleServerConnection implements ServerConnection {

  private static final String LOG_TAG = "GoogleServerConnection";

  static final String HTTP_HOSTNAME = "dns.google.com";
  static final String PRIMARY_TLS_HOSTNAME = HTTP_HOSTNAME;
  static final String FALLBACK_TLS_HOSTNAME = "google.com";

  // Client, with DNS fixed to the selectedServer.  Initialized by bootstrap.
  private OkHttpClient client = null;
  final private GoogleServerDatabase db;
  private String tlsHostname = PRIMARY_TLS_HOSTNAME;

  /**
   * Gets a working GoogleServerConnection, or null if the GoogleServerConnection cannot be made to
   * work. Blocks for the duration of bootstrap if it has not yet succeeded. This method performs
   * network activity, so it cannot be called on the application's main thread.
   */
  @WorkerThread
  public static GoogleServerConnection get(GoogleServerDatabase db) {
    GoogleServerConnection s = new GoogleServerConnection(db);
    DualStackResult redirects = s.bootstrap();
    if (redirects == null) {
      return null;  // No working server.
    }
    db.setPreferred(redirects);
    return s;
  }

  private GoogleServerConnection(GoogleServerDatabase db) {
    this.db = db;
    reset();
  }

  /**
   * Attempts to connect to the DNS server, and checks the connection by fetching fresh IPs for
   * dns.google.com
   *
   * @return The a list of preferred IPs, or null if the connection failed.
   */
  private DualStackResult bootstrap() {
    String[] names4 = resolve(HTTP_HOSTNAME, "A");
    if (names4 == null || names4.length == 0) {
      return null;
    }
    String[] names6 = resolve(HTTP_HOSTNAME, "AAAA");
    if (names6 == null) {
      names6 = new String[0];
    }

    return new DualStackResult(names4, names6);
  }

  // Returns whether the connection bootstrapped using the fallback TLS hostname.
  public boolean didBootstrapWithFallback() {
    return FALLBACK_TLS_HOSTNAME.equals(tlsHostname);
  }

  private String[] resolve(final String name, final String type) {
    String response;
    try {
      response = performJsonDnsRequest(name, type);
    } catch (IOException e) {
      // Retry with fallback hostname.
      tlsHostname = FALLBACK_TLS_HOSTNAME;
      try {
        response = performJsonDnsRequest(name, type);
      } catch (IOException fallbackE) {
        // No working connection
        return null;
      }
    }
    if (response == null) {
      return null;  // Unexpected
    }
    return parseJsonDnsResponse(response);
  }

  private String urlEncode(final String name) {
    String encodedName;
    try {
      encodedName = URLEncoder.encode(name, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      Log.wtf(LOG_TAG, e);
      return "";
    }
    return encodedName;
  }

  @Override
  public void performDnsRequest(final DnsUdpQuery metadata, final byte[] data, Callback cb) {
    Request request = buildDnsRequest(metadata);
    Callback fallbackCb = new Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        tlsHostname = PRIMARY_TLS_HOSTNAME.equals(tlsHostname) ? FALLBACK_TLS_HOSTNAME : PRIMARY_TLS_HOSTNAME;
        reset();
        Request fallbackRequest = buildDnsRequest(metadata);
        client.newCall(fallbackRequest).enqueue(cb);
      }

      @Override
      public void onResponse(Call call, Response response) throws IOException {
        cb.onResponse(call, response);
      }
    };
    client.newCall(request).enqueue(fallbackCb);
  }

  private Request buildDnsRequest(final DnsUdpQuery metadata) {
    final int unsignedType = metadata.type & 0xffff; // Convert Java's signed short to unsigned int
    String url =
      String.format(Locale.ROOT,
        "https://%s/resolve?name=%s&type=%d&encoding=raw", tlsHostname, urlEncode(metadata.name),
        unsignedType);
    return new Request.Builder()
        .url(url)
        .header("Host", HTTP_HOSTNAME)
        .header("User-Agent", String.format("Jigsaw-DNS/%s", BuildConfig.VERSION_NAME))
        .build();
  }

  @Override
  public String getUrl() {
    // GoogleServerConnection doesn't have a caller-controlled URL.
    return null;
  }

  /**
   * Performs a synchronous JSON DNS request for |name| over HTTP using a provided client.
   *
   * @param name The domain name to look up
   * @param type The query type string to look up, e.g. "AAAA". Case-insensitive.
   * @return The entire JSON response, as a string.
   */
  private String performJsonDnsRequest(final String name, final String type) throws IOException {
    String url = String.format("https://%s/resolve?name=%s&type=%s", tlsHostname, urlEncode(name),
        type);
    Request request = new Request.Builder().url(url).header("Host", HTTP_HOSTNAME).build();
    Response response = client.newCall(request).execute();
    return response.body().string();
  }

  /**
   * Reads the results from a JSON DNS-over-HTTPS response.
   *
   * Only used during bootstrap.
   *
   * @param response The JSON string returned by the server
   * @return The list of IP addresses (usually just 1), or null if the response is not valid.
   */
  private String[] parseJsonDnsResponse(String response) {
    try {
      JSONObject json = new JSONObject(response);
      JSONArray answers = json.getJSONArray("Answer");
      ArrayList<String> names = new ArrayList<String>();
      for (int i = 0; i < answers.length(); ++i) {
        JSONObject answer = answers.getJSONObject(i);
        names.add(answer.getString("data"));
      }
      return names.toArray(new String[names.size()]);
    } catch (JSONException e) {
      Log.w(LOG_TAG, e);
      return null;
    }
  }

  @Override
  public void reset() {
    OkHttpClient oldClient = client;
    client = new OkHttpClient.Builder()
        .dns(db)
        .connectTimeout(3, TimeUnit.SECONDS)  // Detect blocked connections.  TODO: tune.
        .addNetworkInterceptor(new IpTagInterceptor())
        .build();
    if (oldClient != null) {
      for (Call call : oldClient.dispatcher().queuedCalls()) {
        call.cancel();
      }
      for (Call call : oldClient.dispatcher().runningCalls()) {
        call.cancel();
      }
    }
  }
}
