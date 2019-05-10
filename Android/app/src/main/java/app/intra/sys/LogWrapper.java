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

import com.crashlytics.android.Crashlytics;

/**
 * Wrapper for Firebase Crash Reporting.  This allows unit testing of classes that contain logging.
 */
public class LogWrapper {
  public static void report(Throwable t) {
    try {
      Crashlytics.logException(t);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }

  public static void logcat(int i, String s, String s1) {
    try {
      Crashlytics.log(i, s, s1);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }
}
