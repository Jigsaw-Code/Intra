package app.intra;

import com.google.android.material.navigation.NavigationView;

import android.content.Context;
import android.util.AttributeSet;

import androidx.core.view.WindowInsetsCompat;

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
