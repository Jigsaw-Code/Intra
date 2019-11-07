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
import androidx.annotation.NonNull;
import java.lang.reflect.Method;

/**
 * Static class for getting the system's country code.
 */
class CountryCode {
  // The SIM or CDMA country.
  private final @NonNull String deviceCountry;

  // The country claimed by the attached cell network.
  private final @NonNull String networkCountry;

  CountryCode(Context context) {
    TelephonyManager telephonyManager =
        (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);

    deviceCountry = countryFromSim(telephonyManager);
    networkCountry = telephonyManager.getNetworkCountryIso();
  }

  @NonNull String getDeviceCountry() {
    return deviceCountry;
  }

  @NonNull String getNetworkCountry() {
    return networkCountry;
  }

  private static @NonNull String countryFromSim(TelephonyManager telephonyManager) {
    String simCountry = telephonyManager.getSimCountryIso();
    if (!simCountry.isEmpty()) {
      return simCountry;
    }
    // User appears to be non-GSM.  Try CDMA.
    try {
      // Get the system properties by reflection.
      Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
      Method get = systemPropertiesClass.getMethod("get", String.class);
      String cdmaOperator = (String)get.invoke(systemPropertiesClass,
          "ro.cdma.home.operator.numeric");
      if (cdmaOperator == null || cdmaOperator.isEmpty()) {
        // System is neither GSM nor CDMA.
        return "";
      }
      String mcc = cdmaOperator.substring(0, 3);
      Class<?> mccTableClass = Class.forName("com.android.internal.telephony.MccTable");
      Method countryCodeForMcc = mccTableClass.getMethod("countryCodeForMcc", String.class);
      return (String)countryCodeForMcc.invoke(mccTableClass, mcc);
    } catch (Exception e) {
      LogWrapper.logException(e);
      return "";
    }
  }
}
