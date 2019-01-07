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

import android.annotation.TargetApi;
import android.os.AsyncTask;
import android.os.Build.VERSION_CODES;
import androidx.annotation.NonNull;
import app.intra.net.dns.DnsPacket;
import app.intra.net.doh.cache.AsyncLoadingCache;
import app.intra.net.doh.cache.Expiry;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

@TargetApi(VERSION_CODES.N)  // Caffeine needs CompletableFuture, which requires Android N.
public class CachingServerConnection implements ServerConnection {
  static String STATUS_HEADER = "X-Intra-Cache-Status";

  public enum Status {
    HIT("hit"), MISS("miss"), PENDING("pending");

    public String id;
    Status(String id) {
      this.id = id;
    }
  }

  private final ServerConnection impl;
  private final AsyncLoadingCache<DnsPacket, HttpResult> cache;

  private static class HttpResult {
    final Call call;
    private final Response response;
    private final byte[] responseBody;
    final IOException exception;

    HttpResult(Call call, IOException e) {
      this.call = call;
      this.exception = e;
      this.response = null;
      this.responseBody = null;
    }

    HttpResult(Call call, Response response) throws IOException {
      this.call = call;
      this.response = response;
      // A ResponseBody can only be read once, so we have to read it here and then reconstitute it
      // in the getter.
      this.responseBody = response.body().bytes();
      this.exception = null;
    }

    Response getResponse(Status status) {
      // ResponseBody.create() makes a copy of responseBody.
      ResponseBody newBody = ResponseBody.create(response.body().contentType(), responseBody);
      return response.newBuilder().
          header(STATUS_HEADER, status.id).
          body(newBody).
          build();
    }

    int getTTL() throws ProtocolException {
      DnsPacket packet = new DnsPacket(responseBody);
      return packet.getTTL();
    }
  }

  private static class DohExpiry implements Expiry<DnsPacket, HttpResult> {

    @Override
    public long expireAfterCreate(@NonNull DnsPacket query, @NonNull HttpResult httpResult,
        long currentTime) {
      if (httpResult.response == null) {
        // If we didn't get a response, it's not cacheable.
        return 0;
      }

      final int ttl;
      try {
        ttl = httpResult.getTTL();
      } catch (Exception e) {
        // Can't compute TTL, so the result is not cacheable.
        return 0;
      }

      long remainingTtlMs = ttl * 1000 - Resolver.getResponseAgeMs(httpResult.response);
      return remainingTtlMs * 1_000_000;
    }
  }

  public CachingServerConnection(ServerConnection impl) {
    this.impl = impl;
    cache = new AsyncLoadingCache<>((key) -> forwardRequestToImpl(key),
        1000, new DohExpiry());
  }

  private class FollowupParams {
    boolean keyIsPresent;
    boolean valueIsPresent;
    Callback cb;
    Future<HttpResult> entry;
  }

  private static class FollowupTask extends AsyncTask<FollowupParams, Void, Void> {
    @Override
    public Void doInBackground(FollowupParams... params) {
      for (FollowupParams p : params) {
        try {
          HttpResult httpResult = p.entry.get();  // Blocks for the network on a cache miss.
          afterArrival(p.keyIsPresent, p.valueIsPresent, httpResult, p.cb);
        } catch (Exception e) {
          return null;
        }
      }
      return null;
    }

    private void afterArrival(final boolean keyIsPresent, final boolean valueIsPresent, final HttpResult httpResult, final Callback cb) {
      if (httpResult.response != null) {
        CachingServerConnection.Status status =
            valueIsPresent ? CachingServerConnection.Status.HIT
                : keyIsPresent ? CachingServerConnection.Status.PENDING
                    : CachingServerConnection.Status.MISS;
        try {
          cb.onResponse(httpResult.call, httpResult.getResponse(status));
        } catch (IOException e) {
          cb.onFailure(httpResult.call, e);
        }
      } else {
        cb.onFailure(httpResult.call, httpResult.exception);
      }
    }
  }

  @Override
  public void performDnsRequest(final DnsPacket query, final Callback cb) {
    FollowupParams p = new FollowupParams();
    p.cb = cb;
    p.entry = cache.getIfPresent(query);
    p.keyIsPresent = p.entry != null;
    p.valueIsPresent = p.entry != null && p.entry.isDone();

    if (p.entry == null) {
      p.entry = cache.get(query);
    }

    FollowupTask followup = new FollowupTask();
    followup.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, p);
  }

  private CompletableFuture<HttpResult> forwardRequestToImpl(final DnsPacket query) {
    CompletableFuture<HttpResult> ret = new CompletableFuture<>();
    impl.performDnsRequest(query, new Callback() {
      @Override
      public void onFailure(@NonNull Call call, @NonNull IOException e) {
        ret.complete(new HttpResult(call, e));
      }

      @Override
      public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        ret.complete(new HttpResult(call, response));
      }
    });
    return ret;
  }

  @Override
  public String getUrl() {
    return impl.getUrl();
  }

  @Override
  public void reset() {
    impl.reset();
  }
}
