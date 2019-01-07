package app.intra.net.doh.cache;

import androidx.annotation.NonNull;

/**
 * Minimal expiration time calculator interface based on Caffeine's Expiry interface.
 */
public interface Expiry<K, V> {
  long expireAfterCreate(@NonNull K key, @NonNull V value, long currentTime);
}
