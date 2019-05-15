/*
Copyright 2018 Jigsaw Operations LLC

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

import android.util.Log;
import app.intra.BuildConfig;
import com.crashlytics.android.Crashlytics;

/**
 * Wrapper for Crashlytics.  This allows unit testing of classes that contain logging and allows
 * us to cleanly disable Crashlytics in debug builds.
 * See https://stackoverflow.com/questions/16986753/how-to-disable-crashlytics-during-development
 */
public class LogWrapper {
  public static void logException(Throwable t) {
    if (BuildConfig.DEBUG) {
      Log.e("LogWrapper", "Error", t);
      return;
    }
    try {
      Crashlytics.logException(t);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }

  public static void log(int i, String s, String s1) {
    if (BuildConfig.DEBUG) {
      Log.println(i, s, s1);
      return;
    }
    try {
      Crashlytics.log(i, s, s1);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }
}
