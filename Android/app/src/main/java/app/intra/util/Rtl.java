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
package app.intra.util;

import android.os.Build;
import android.view.View;

import androidx.fragment.app.Fragment;

/**
 * Utility class for dealing with Right-to-Left locales.
 */
public class Rtl {
  /**
   * @param fragment The UI element to analyze
   * @return True if the UI is configured to use a Right-to-Left locale.
   */
  public static boolean isRtl(Fragment fragment) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
      return false;
    }
    int layoutDirection = fragment.getResources().getConfiguration().getLayoutDirection();
    return layoutDirection == View.LAYOUT_DIRECTION_RTL;
  }
}
