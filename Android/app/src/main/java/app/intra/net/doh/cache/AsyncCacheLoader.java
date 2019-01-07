package app.intra.net.doh.cache;

import java.util.concurrent.Future;

/**
 * Minimal cache loader interface based on Caffeine's AsyncCacheLoader.
 */
public interface AsyncCacheLoader<K, V> {
  Future<V> asyncLoad(K key);
}
