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

import android.os.SystemClock;
import android.util.Log;
import app.intra.BuildConfig;
import app.intra.sys.LogWrapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * LRU cache based on a small subset of Caffeine's AsyncLoadingCache type.
 * Caffeine does not support most Android versions, and is not intended to work well on Android.
 *
 * In addition to an LRU eviction policy (which is already available with Android's built-in
 * LruCache), this class adds expiration with per-item TTLs.
 *
 * All timestamps are monotonic time from time.read().
 */
public class AsyncLoadingCache<K extends Comparable<K>, V> {

  private static final String LOG_TAG = "AsyncLoadingCache";

  /**
   * A Tracker represents a key-value pair after the value has loaded.  Once a value has loaded,
   * it has a fixed key and expiration time, but its useTime is updated every time the value is
   * accessed.
   */
  private class Tracker {
    final K key;
    final long expirationTime;
    long useTime;

    Tracker(K key, long expirationTime, long useTime) {
      this.key = key;
      this.expirationTime = expirationTime;
      this.useTime = useTime;
    }
  }

  private class ExpirationComparator implements Comparator<Tracker> {
    @Override
    public int compare(Tracker a, Tracker b) {
      // Sort by increasing expiration time
      if (a.expirationTime != b.expirationTime) {
        return Long.compare(a.expirationTime, b.expirationTime);
      }
      // Break ties consistently.
      return a.key.compareTo(b.key);
    }
  }

  private class EvictionComparator implements Comparator<Tracker> {
    @Override
    public int compare(Tracker a, Tracker b) {
      // Sort by most recently used
      if (a.useTime != b.useTime) {
        return Long.compare(a.useTime, b.useTime);
      }
      // Break ties consistently.
      return a.key.compareTo(b.key);
    }
  }

  // Ticker reports the time in ms, but can be overridden for unit tests.
  private Ticker time = SystemClock::elapsedRealtime;
  void setTicker(Ticker newTicker) {
    time = newTicker;
  }
  
  // These fields represent the internal state of the cache
  // |values| contains futures for all values that are loading or loaded.
  private Map<K, ListenableFuture<V>> values = new HashMap<>();
  // Once a value is loaded, it has a single Tracker associated with it.
  // |trackers|, |expirationQueue|, and |evictionQueue| all contain the exact same Trackers
  // (referential equality), organized respectively by key, expiration time, and LRU.
  private Map<K, Tracker> trackers = new HashMap<>();
  private TreeSet<Tracker> expirationQueue = new TreeSet<>(new ExpirationComparator());
  private TreeSet<Tracker> evictionQueue = new TreeSet<>(new EvictionComparator());

  // These are the constructor arguments.
  private final AsyncCacheLoader<K, V> loader;
  private final int maxItems;
  private final Expiry<K, V> expiry;

  /**
   * @param loader Source of items on cache miss.
   * @param maxItems Limit on the number of items stored in the cache.  Pending items do not count.
   * @param expiry Expiration policy.
   */
  public AsyncLoadingCache(AsyncCacheLoader<K, V> loader, int maxItems, Expiry<K, V> expiry) {
    this.loader = loader;
    this.maxItems = maxItems;
    this.expiry = expiry;
    checkInvariants();
  }

  private void checkInvariants() {
    int N = trackers.size();  // Number of items stored in the cache.
    boolean consistentSizes = expirationQueue.size() == N &&
        evictionQueue.size() == N &&
        N <= values.size() &&
        N <= maxItems;
    if (!consistentSizes) {
      String msg = String.format(Locale.ROOT, "Cache size error: %d %d %d %d %d",
          N, expirationQueue.size(), evictionQueue.size(), values.size(), maxItems);
      Log.e(LOG_TAG, msg);
      LogWrapper.report(new InternalError(msg));
    } else if (BuildConfig.DEBUG) {
      // Deep consistency check.  May be slow.
      for (Tracker tracker : expirationQueue) {
        if (trackers.get(tracker.key) != tracker) {
          throw new InternalError("expiration-trackers referential inconsistency");
        }
        if (!values.get(tracker.key).isDone()) {
          throw new InternalError("expiration-values referential inconsistency");
        }
      }
      for (Tracker tracker : evictionQueue) {
        if (trackers.get(tracker.key) != tracker) {
          throw new InternalError("eviction-trackers inconsistency");
        }
      }
      for (ListenableFuture<V> value : values.values()) {
        if (value == null) {
          throw new InternalError("null future");
        }
      }
    }
  }

  /**
   * Completely remove all expired values from the cache.
   * @param now The current time.
   */
  private synchronized void expire(long now) {
    while (expirationQueue.size() > 0 && expirationQueue.first().expirationTime <= now) {
      Tracker tracker = expirationQueue.pollFirst();
      evictionQueue.remove(tracker);
      values.remove(tracker.key);
      trackers.remove(tracker.key);
    }
  }

  /**
   * Evict LRU items as needed to stay under the size limit.  This should be called after adding
   * new items.
   */
  private synchronized void evict() {
    while (evictionQueue.size() > maxItems) {
      Tracker tracker = evictionQueue.pollFirst();
      expirationQueue.remove(tracker);
      values.remove(tracker.key);
      trackers.remove(tracker.key);
    }
  }

  /**
   * Updates the LRU time for an item.
   * @param key Item identifier
   * @param now New LRU time.
   */
  private synchronized void markUsed(K key, long now) {
    Tracker tracker = trackers.get(key);
    if (tracker == null) {
      return;
    }
    // Update the tracker's use time.  |evictionQueue| relies on the use time for ordering, so to
    // avoid corrupting its internal state, we need to remove the tracker, update the time, and then
    // re-insert it.
    evictionQueue.remove(tracker);
    tracker.useTime = Math.max(tracker.useTime, now);
    evictionQueue.add(tracker);
  }

  // From Caffeine's AsyncLoadingCache
  public synchronized ListenableFuture<V> getIfPresent(K key) {
    long now = time.read();
    expire(now);  // Avoid returning expired results.
    markUsed(key, now);
    checkInvariants();
    return values.get(key);
  }

  /**
   * This function is called when a load completes with an uncacheable result.
   */
  private synchronized void abortLoad(K key) {
    values.remove(key);
    checkInvariants();

  }

  /**
   * A load completed with a cacheable result.  Now the result must be tracked.
   * @param key Identifies the item
   * @param expirationTime The item's expiration time
   * @param now The current time, which is the item's initial use time.
   */
  private synchronized void track(K key, long expirationTime, long now) {
    Tracker tracker = new Tracker(key, expirationTime, now);
    evictionQueue.add(tracker);
    expirationQueue.add(tracker);
    trackers.put(key, tracker);
    // Expire before eviction to minimize unnecessary eviction.
    expire(now);
    evict();
    checkInvariants();
  }

  // From Caffeine's AsyncLoadingCache API
  public synchronized ListenableFuture<V> get(final K key) {
    long now = time.read();
    expire(now);  // Avoid returning expired results.
    markUsed(key, now);
    ListenableFuture<V> value = values.get(key);
    if (value != null) {
      return value;
    }
    value = loader.asyncLoad(key);
    values.put(key, value);

    // This is essentially a retrofit for CompletableFuture, since older Android doesn't support it.
    Futures.addCallback(value, new FutureCallback<V>() {
      @Override
      public void onSuccess(V v) {
        long ttl = expiry.expireAfterCreate(key, v);
        if (ttl <= 0) {
          abortLoad(key);
        } else {
          long now = time.read();
          track(key, now + ttl, now);
        }
      }

      @Override
      public void onFailure(Throwable throwable) {
        abortLoad(key);
      }
    }, MoreExecutors.directExecutor());

    checkInvariants();
    return value;
  }

  // Based on Caffeine's Cache API.  This implementation only invalidates loaded cache entries, not
  // pending entries.
  public synchronized void invalidateAll() {
    for (K key : trackers.keySet()) {
      values.remove(key);
    }
    trackers.clear();
    evictionQueue.clear();
    expirationQueue.clear();
    checkInvariants();
  }

}
