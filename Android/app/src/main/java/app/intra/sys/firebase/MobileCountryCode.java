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
import android.telephony.TelephonyManager;
import app.intra.sys.firebase.LogWrapper;
import java.lang.reflect.Method;

/**
 * Static utility function for getting the Mobile Country Code.
 */
class MobileCountryCode {
  // Mobile Country Code, or 0 if it could not be determined.  This is used to memoize getMCC()
  // across all the analytics events.
  private static Integer mcc = null;

  // Extracts the MCC from an MCC+MNC string.
  private static Integer extractMCC(String operator) {
    if (operator != null && operator.length() >= 3) {
      return Integer.valueOf(operator.substring(0, 3));
    }
    return null;
  }

  /**
   * Gets the system's mobile country code, or 0 if it could not be determined.
   * After the first call to this function, all subsequent calls will return immediately.
   */
  static int get(Context context) {
    if (mcc != null) {
      return mcc;
    }
    TelephonyManager telephonyManager =
        (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
    Integer simMCC = extractMCC(telephonyManager.getSimOperator());
    if (simMCC != null) {
      // Require the SIM and network country codes to match before reporting a country, to avoid
      // confusion when they disagree (e.g. international roaming).
      Integer networkMCC = extractMCC(telephonyManager.getNetworkOperator());
      if (simMCC.equals(networkMCC)) {
        mcc = simMCC;
      }
      return mcc;
    }
    // User appears to be non-GSM.  Try CDMA.
    try {
      // Get the system properties by reflection.
      Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
      Method get = systemPropertiesClass.getMethod("get", String.class);
      String cdmaOperator = (String)get.invoke(systemPropertiesClass,
          "ro.cdma.home.operator.numeric");
      Integer cdmaMCC = extractMCC(cdmaOperator);
      if (cdmaMCC != null) {
        mcc = cdmaMCC;
      }
    } catch (Exception e) {
      LogWrapper.logException(e);
    }
    return mcc;
  }

}
