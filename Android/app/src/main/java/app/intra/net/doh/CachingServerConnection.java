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

import androidx.annotation.NonNull;
import app.intra.net.dns.DnsPacket;
import app.intra.net.doh.cache.AsyncLoadingCache;
import app.intra.net.doh.cache.Expiry;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
  private AsyncLoadingCache<DnsPacket, HttpResult> cache;

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
    public long expireAfterCreate(@NonNull DnsPacket query, @NonNull HttpResult httpResult) {
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
      return remainingTtlMs;
    }
  }

  public CachingServerConnection(ServerConnection impl, int cacheSize) {
    this.impl = impl;
    cache = new AsyncLoadingCache<>((key) -> forwardRequestToImpl(key),
        cacheSize, new DohExpiry());
  }

  @Override
  public void performDnsRequest(final DnsPacket query, final Callback cb) {
    ListenableFuture<HttpResult> entry = cache.getIfPresent(query);
    final boolean keyIsPresent = entry != null;
    final boolean valueIsPresent = entry != null && entry.isDone();

    if (entry == null) {
      entry = cache.get(query);
    }

    Futures.addCallback(entry, new FutureCallback<HttpResult>() {
      @Override
      public void onSuccess(HttpResult httpResult) {
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

      @Override
      public void onFailure(Throwable throwable) {
      }
    }, MoreExecutors.directExecutor());
  }

  private ListenableFuture<HttpResult> forwardRequestToImpl(final DnsPacket query) {
    SettableFuture<HttpResult> ret = SettableFuture.create();
    impl.performDnsRequest(query, new Callback() {
      @Override
      public void onFailure(@NonNull Call call, @NonNull IOException e) {
        ret.set(new HttpResult(call, e));
      }

      @Override
      public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        ret.set(new HttpResult(call, response));
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

  /**
   * Clear all loaded entries from the cache to release memory.
   */
  public void clear() {
    cache.invalidateAll();
  }
}
