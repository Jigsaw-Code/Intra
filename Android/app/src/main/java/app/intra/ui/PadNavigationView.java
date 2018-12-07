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
package app.intra.ui;

import android.content.Context;
import android.util.AttributeSet;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.navigation.NavigationView;

/**
 * This is a NavigationView that always respects the system insets, even if it has no menu of its
 * own.
 */

public class PadNavigationView extends NavigationView {
  public PadNavigationView(Context context) {
    super(context);
  }

  public PadNavigationView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public PadNavigationView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onInsetsChanged(WindowInsetsCompat insets) {
    // Normally, NavigationView applies the system inset padding to the first menu item, not to the
    // NavigationView itself.  This doesn't work if the view has no MenuItems.  By overriding the
    // inset listener and applying the padding directly to the NavigationView, we can get correct
    // padding behavior even when there are no menu items.
    setPadding(getPaddingLeft(), insets.getSystemWindowInsetTop(), getPaddingRight(),
        getPaddingBottom());
  }
}
