package app.intra.util;

import com.google.firebase.crash.FirebaseCrash;

/**
 * Wrapper for Firebase Crash Reporting.  This allows unit testing of classes that contain logging.
 */
public class LogWrapper {
  public static void report(Throwable t) {
    try {
      FirebaseCrash.report(t);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }

  public static void logcat(int i, String s, String s1) {
    try {
      FirebaseCrash.logcat(i, s, s1);
    } catch (IllegalStateException e) {
      // This only occurs during unit tests.
    }
  }
}
