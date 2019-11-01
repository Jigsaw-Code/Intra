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
package app.intra.sys;

import android.content.Context;
import android.os.Bundle;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.lang.reflect.Method;

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

  // Mobile Country Code, or 0 if it could not be determined.  This is used to memoize getMCC()
  // across all the analytics events.
  private static Integer mcc = null;

  // Get the system's mobile country code, or 0 if it could not be determined.
  // After the first call to this function, all subsequent calls will return immediately.
  private int getMCC() {
    if (mcc != null) {
      return mcc;
    }
    int gsmMCC = context.getResources().getConfiguration().mcc;
    if (gsmMCC != 0) {
      mcc = gsmMCC;
      return mcc;
    }
    // User appears to be non-GSM.  Try CDMA.
    try {
      // Get the system properties by reflection.
      Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
      Method get = systemPropertiesClass.getMethod("get", String.class);
      String mcc_mnc = (String)get.invoke(systemPropertiesClass, "ro.cdma.home.operator.numeric");
      if (mcc_mnc.length() >= 3) {
        mcc = Integer.valueOf(mcc_mnc.substring(0, 3));
      }
    } catch (Exception e) {
      LogWrapper.logException(e);
      mcc = 0;
    }
    return mcc;
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
    put(Params.MCC, getMCC());
    FirebaseAnalytics.getInstance(context).logEvent(event.name(), bundle);
  }
}
