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
package app.intra.sys.firebase;

import android.os.Bundle;

/**
 * Builder-pattern wrapper around android.os.Bundle, with type enforcement for parameters used
 * by Intra.
 */
class BundleBuilder {

  // Parameters sent in Analytics events
  enum Params {
    BYTES,
    CHUNKS,
    COUNTRY,
    DOWNLOAD,
    DURATION,
    LATENCY,
    MODE,
    NETWORK,
    PORT,
    RESULT,
    RETRY,
    SERVER,
    SPLIT,
    TCP_HANDSHAKE_MS,
    TIMEOUT,
    UPLOAD,
  }

  final private Bundle bundle = new Bundle();

  BundleBuilder put(Params key, int val) {
    bundle.putInt(key.name(), val);
    return this;
  }

  BundleBuilder put(Params key, long val) {
    bundle.putLong(key.name(), val);
    return this;
  }

  BundleBuilder put(Params key, String val) {
    bundle.putString(key.name(), val);
    return this;
  }

  Bundle build() {
    return bundle;
  }
}
