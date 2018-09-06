/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sockslib.server.manager;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The class <code>HashPasswordProtector</code> implements {@link PasswordProtector}. This class
 * will use a specified hash algorithm to encrypt password.
 *
 * @author Youchao Feng
 * @version 1.0
 * @date Sep 06, 2015
 */
public class HashPasswordProtector implements PasswordProtector {

  private static final Logger logger = LoggerFactory.getLogger(HashPasswordProtector.class);

  private HashAlgorithm algorithm = HashAlgorithm.MD5;
  private int hashTime = 1;
  private LoadingCache<String, String> cache;

  public HashPasswordProtector() {
    this(HashAlgorithm.MD5, 1);
  }

  public HashPasswordProtector(HashAlgorithm algorithm) {
    this(algorithm, 1);
  }

  public HashPasswordProtector(HashAlgorithm algorithm, int hashTime) {
    this.algorithm = algorithm;
    this.hashTime = hashTime;
    cache =
        CacheBuilder.newBuilder().maximumSize(1000).expireAfterAccess(5, TimeUnit.MINUTES).build
            (new CacheLoader<String, String>() {
          @Override
          public String load(String key) throws Exception {
            return encryptNow(key);
          }
        });
  }

  @Override
  public String encrypt(final User user) {
    if (user == null) {
      throw new IllegalArgumentException("User can't be null");
    }
    if (Strings.isNullOrEmpty(user.getPassword())) {
      throw new IllegalArgumentException("User's password can't be null or empty");
    }
    return cache.getUnchecked(getPlainText(user));
  }

  public String getPlainText(User user) {
    return user.getPassword();
  }

  private String encryptNow(String plaintext) {
    logger.debug("Encrypt:{}", plaintext);
    byte[] bytes = plaintext.getBytes(Charsets.UTF_8);
    for (int i = 0; i < hashTime - 1; i++) {
      Hasher hasher = chooseHasher(algorithm);
      bytes = hasher.putBytes(bytes).hash().asBytes();
    }
    Hasher hasher = chooseHasher(algorithm);
    return hasher.putBytes(bytes).hash().toString();
  }

  private Hasher chooseHasher(HashAlgorithm algorithm) {
    Hasher hasher = null;
    switch (algorithm) {

      case MD5:
        hasher = Hashing.md5().newHasher();
        break;
      case SHA1:
        hasher = Hashing.sha1().newHasher();
        break;
      case SHA256:
        hasher = Hashing.sha256().newHasher();
        break;
      case SHA512:
        hasher = Hashing.sha512().newHasher();
        break;

    }
    return hasher;
  }

  public int getHashTime() {
    return hashTime;
  }

  public void setHashTime(int hashTime) {
    this.hashTime = hashTime;
  }

  public HashAlgorithm getAlgorithm() {
    return algorithm;
  }

  public void setAlgorithm(HashAlgorithm algorithm) {
    this.algorithm = algorithm;
  }

  public LoadingCache<String, String> getCache() {
    return cache;
  }

  public void setCache(LoadingCache<String, String> cache) {
    this.cache = cache;
  }

  public enum HashAlgorithm {
    MD5, SHA1, SHA256, SHA512
  }
}
