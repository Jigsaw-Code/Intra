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

import android.content.Context;
import android.os.Bundle;
import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Wrapper for Firebase Analytics event reporting.  In addition to improving the reporting API,
 * this class also attaches the Mobile Country Code (MCC) to each event, which indicates the client
 * country, to compensate for country classification errors in Firebase.
 */
public class AnalyticsEvent {
  // Analytics events
  public enum Events {
    BOOTSTRAP,
    BOOTSTRAP_FAILED,
    BYTES,
    EARLY_RESET,
    STARTVPN,
    TLS_PROBE,
    TRY_ALL_ACCEPTED,
    TRY_ALL_CANCELLED,
    TRY_ALL_DIALOG,
    TRY_ALL_FAILED,
    TRY_ALL_REQUESTED,
    UDP,
  }

  // Parameters sent in Analytics events
  public enum Params {
    BYTES,
    CHUNKS,
    DOWNLOAD,
    DURATION,
    LATENCY,
    MCC,
    MODE,
    PORT,
    RESULT,
    RETRY,
    SERVER,
    SPLIT,
    TCP_HANDSHAKE_MS,
    TIMEOUT,
    UPLOAD,
  }

  final private Context context;
  final private Bundle bundle = new Bundle();
  public AnalyticsEvent(Context context) {
    this.context = context;
  }

  public AnalyticsEvent put(Params key, int val) {
    bundle.putInt(key.name(), val);
    return this;
  }

  public AnalyticsEvent put(Params key, long val) {
    bundle.putLong(key.name(), val);
    return this;
  }

  public AnalyticsEvent put(Params key, String val) {
    bundle.putString(key.name(), val);
    return this;
  }

  public void send(Events event) {
    put(Params.MCC, MobileCountryCode.get(context));
    FirebaseAnalytics.getInstance(context).logEvent(event.name(), bundle);
  }
}
