package app.intra.net.doh.cache;

import android.os.AsyncTask;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Future;

/**
 * Simple, low-performance cache based on a small subset of Caffeine's AsyncLoadingCache type.
 * Caffeine does not support most Android versions, and is not intended to work well on Android.
 *
 * In addition to expiration with custom TTLs, this class implements a size limit with a simple
 * eviction policy: items closest to expiration are evicted first.  This is likely a poor heuristic,
 * but it should be acceptable so long as eviction is rare.
 */
public class AsyncLoadingCache<K, V> {
  private class Tracker implements Comparable<Tracker> {
    final K key;
    final long expirationTime;

    Tracker(K key, long expirationTime) {
      this.key = key;
      this.expirationTime = expirationTime;
    }

    @Override
    public int compareTo(Tracker other) {
      return Long.compare(expirationTime, other.expirationTime);
    }
  }
  private int maxItems;
  private PriorityQueue<Tracker> queue;
  private Map<K, Future<V>> map = new HashMap<>();

  private Expiry<K, V> expiry;
  private AsyncCacheLoader<K, V> loader;

  public AsyncLoadingCache(AsyncCacheLoader<K, V> loader, int maxItems, Expiry<K, V> expiry) {
    this.loader = loader;
    this.maxItems = maxItems;
    this.expiry = expiry;
  }

  private synchronized void compact(long now) {
    while (queue.size() > maxItems) {
      K key = queue.poll().key;
      map.remove(key);
    }
    while (queue.size() > 0) {
      Tracker tracker = queue.peek();
      if (tracker.expirationTime <= now) {
        map.remove(tracker.key);
        queue.poll();
      } else {
        break;
      }
    }
  }

  public synchronized Future<V> getIfPresent(K key) {
    compact(SystemClock.elapsedRealtime());
    return map.get(key);
  }

  private synchronized void remove(K key) {
    map.remove(key);
  }

  private synchronized void track(K key, long expirationTime) {
    queue.add(new Tracker(key, expirationTime));
  }

  private class Pair {
    final K key;
    final Future<V> value;
    Pair(K key, Future<V> value) {
      this.key = key;
      this.value = value;
    }
  }

  private class StoreTask extends AsyncTask<Pair, Void, Void> {
    @Override
    protected Void doInBackground(Pair... pairs) {
      for (Pair pair : pairs) {
        try {
          V v = pair.value.get();  // Blocks until the lookup completes.
          processValue(pair.key, v);
        } catch (Exception e) {
          continue;
        }
      }
      return null;
    }

    private void processValue(K key, V value) {
      long now = SystemClock.elapsedRealtime();
      long ttl = expiry.expireAfterCreate(key, value, now);
      if (ttl <= 0) {
        remove(key);
      } else {
        track(key, now + ttl);
      }
    }
  }

  public synchronized Future<V> get(K key) {
    long now = SystemClock.elapsedRealtime();
    compact(now);
    Future<V> value = map.get(key);
    if (value != null) {
      return value;
    }
    value = loader.asyncLoad(key);
    if (value == null) {
      return null;
    }
    map.put(key, value);
    StoreTask followup = new StoreTask();
    followup.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new Pair(key, value));
    return value;
  }
}
