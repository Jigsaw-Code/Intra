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
import app.intra.util.DualStackResult;
import app.intra.util.IpTagInterceptor;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Allows the caller to perform DNS-over-HTTPS queries using the IETF draft protocol.
 */
public class StandardServerConnection implements ServerConnection {

  private final String url;
  private OkHttpClient client;
  private final List<InetAddress> ips;

  private class PinnedDns implements Dns {

    private final List<InetAddress> ips;

    public PinnedDns(List<InetAddress> ips) {
      DualStackResult result = new DualStackResult(ips);
      this.ips = result.getInterleaved();
    }

    @Override
    public List<InetAddress> lookup(String hostname) {
      return ips;
    }

  }

  public static StandardServerConnection get(String url) {
    URL parsedUrl;
    try {
      parsedUrl = new URL(url);
    } catch (MalformedURLException e) {
      // TODO: log
      return null;
    }
    if (!"https".equals(parsedUrl.getProtocol())) {
      return null;
    }
    InetAddress[] ips;
    try {
      ips = InetAddress.getAllByName(parsedUrl.getHost());
    } catch (UnknownHostException e) {
      // TODO: log
      return null;
    }
    if (ips.length == 0) {
      return null;
    }
    return new StandardServerConnection(url, Arrays.asList(ips));
  }

  private StandardServerConnection(String url, List<InetAddress> ips) {
    this.url = url;
    this.ips = ips;

    reset();
  }

  @Override
  public void performDnsRequest(DnsUdpQuery metadata, byte[] data, Callback cb) {
    // Zero out the ID
    data[0] = 0;
    data[1] = 0;

    MediaType mediaType = MediaType.parse("application/dns-udpwireformat");
    RequestBody body = RequestBody.create(mediaType, data);
    Request request = new Request.Builder()
        .url(url)
        .header("User-Agent", String.format("Jigsaw-DNS/%s", BuildConfig.VERSION_NAME))
        .post(body)
        .build();
    client.newCall(request).enqueue(cb);
  }

  @Override
  public String getUrl() {
    return url;
  }

  @Override
  public void reset() {
    OkHttpClient oldClient = client;
    client = new OkHttpClient.Builder()
        .dns(new PinnedDns(ips))
        .connectTimeout(3, TimeUnit.SECONDS) // Detect blocked connections.  TODO: tune.
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
