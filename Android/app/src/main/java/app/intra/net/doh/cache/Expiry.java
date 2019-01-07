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

import androidx.annotation.NonNull;

/**
 * Minimal expiration time calculator interface based on Caffeine's Expiry interface.
 */
public interface Expiry<K, V> {
  // Unlike Caffeine, this function measures time in milliseconds, not nanoseconds.
  long expireAfterCreate(@NonNull K key, @NonNull V value);
}
