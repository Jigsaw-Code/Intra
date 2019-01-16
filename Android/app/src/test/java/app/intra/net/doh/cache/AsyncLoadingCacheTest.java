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
package app.intra.net.doh.cache;

import static org.junit.Assert.*;

import androidx.annotation.NonNull;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class AsyncLoadingCacheTest {
  private Expiry<String, Long> expiry = (@NonNull String key, @NonNull Long value) -> value;

  private class Loader implements AsyncCacheLoader<String, Long> {
    Map<String, SettableFuture<Long>> map = new HashMap<>();

    @Override
    public ListenableFuture<Long> asyncLoad(String key) {
      SettableFuture<Long> future = SettableFuture.create();
      map.put(key, future);
      return future;
    }
  }

  private long time;

  private int limit = 10;
  private Loader loader;
  private AsyncLoadingCache<String, Long> cache;

  @Before
  public void setUp() throws Exception {
    loader = new Loader();
    cache = new AsyncLoadingCache<>(loader, limit, expiry);

    time = 0;
    cache.setTicker(() -> time);
  }

  @Test
  public void getIfPresent() {
    assertNull(cache.getIfPresent("a"));
    assertEquals(0, loader.map.size());
  }

  @Test
  public void get() throws Exception {
    ListenableFuture<Long> f = cache.get("a");
    assertEquals(1, loader.map.size());
    assertFalse(f.isDone());

    // Pending value should already be available.
    assertEquals(f, cache.getIfPresent("a"));

    // Load the value.
    Long value = 1000L;
    loader.map.get("a").set(value);
    assertTrue(f.isDone());
    assertEquals(value, f.get());

    // After load completes, subsequent loads should continue to return the same future.
    assertEquals(f, cache.getIfPresent("a"));
    assertEquals(f, cache.get("a"));
  }

  @Test
  public void uncacheable() throws Exception {
    ListenableFuture<Long> f = cache.get("a");

    // A zero value is not cacheable.
    Long value = 0L;
    loader.map.get("a").set(value);
    assertTrue(f.isDone());
    assertEquals(value, f.get());

    // The value is no longer in the cache.
    assertNull(cache.getIfPresent("a"));
  }

  @Test
  public void expiration() throws Exception {
    ListenableFuture<Long> f = cache.get("a");

    // Value is only cacheable for 1 ms.
    Long value = 1L;
    loader.map.get("a").set(value);
    assertTrue(f.isDone());
    assertEquals(value, f.get());
    assertNotNull(cache.getIfPresent("a"));

    time += 10;

    // The value is no longer in the cache.
    assertNull(cache.getIfPresent("a"));
  }

  @Test
  public void eviction() throws Exception {
    Long value = 10000L;
    // Fill the cache.
    for (int i = 0; i < limit; ++i) {
      String key = Integer.toString(i);
      ListenableFuture<Long> f = cache.get(key);
      loader.map.get(key).set(value);
      assertTrue(f.isDone());

      ++time;
    }

    // Now "0" is the least recently used entry.
    ListenableFuture<Long> f = cache.get("a");
    assertFalse(f.isDone());
    // Pending entries don't count toward the eviction limit, so zero has not been evicted.
    assertNotNull(cache.getIfPresent("0"));
    // Now "1" is the least recently used entry.
    // Complete the extra load.
    loader.map.get("a").set(value);
    assertTrue(f.isDone());
    assertEquals(value, f.get());
    // Now the least recently used entry has been evicted.
    assertNull(cache.getIfPresent("1"));

    // The other entries remain
    assertNotNull(cache.getIfPresent("0"));
    assertNotNull(cache.getIfPresent("a"));
    for (int i = 2; i < limit; ++i) {
      String key = Integer.toString(i);
      assertNotNull(cache.getIfPresent(key));
    }
  }

  @Test
  public void manyPending() throws Exception {
    Long value = 10000L;
    // Create an excess of pending queries.
    ArrayList<ListenableFuture<Long>> list = new ArrayList<>();
    for (int i = 0; i < 2 * limit; ++i) {
      String key = Integer.toString(i);
      list.add(cache.get(key));
    }

    // Complete all pending loads.
    for (int i = 0; i < 2 * limit; ++i) {
      String key = Integer.toString(i);
      loader.map.get(key).set(value);
    }

    // Confirm that all futures are complete
    for (ListenableFuture<Long> f : list) {
      assertTrue(f.isDone());
      assertEquals(value, f.get());
    }

    // Only |limit| entries should remain in cache.
    int cached = 0;
    for (int i = 0; i < 2 * limit; ++i) {
      String key = Integer.toString(i);
      if (cache.getIfPresent(key) != null) {
        ++cached;
      }
    }
    assertEquals(limit, cached);
  }

  @Test
  public void invalidation() throws Exception {
    cache.get("a");
    cache.get("b");

    loader.map.get("a").set(1000L);
    cache.invalidateAll();
    assertNull(cache.getIfPresent("a"));
    assertNotNull(cache.getIfPresent("b"));
  }
}