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
package app.intra;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

// Fragment representing the full-screen first-run experience.  This intro is launched by the main
// activity.
public class IntroDialog extends DialogFragment {
  private static final int NUM_PAGES = 3;

  // Avoid duplicate instances of this dialog.  This can occur when rotating the app, which would
  // otherwise cause the main activity to launch an additional instance of this dialog.
  private static boolean isShown = false;

  public static boolean shouldShow(Activity activity) {
    return !isShown && !PersistentState.getWelcomeApproved(activity);
  }

  @Override
  public int getTheme() {
    return R.style.FullScreenDialog;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle
      savedInstanceState) {
    View welcomeView = inflater.inflate(R.layout.intro_pager, container);

    // Right-to-left locale support.
    boolean rtl = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      int layoutDirection = getResources().getConfiguration().getLayoutDirection();
      rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL;
    }

    Adapter adapter = new Adapter(getChildFragmentManager());
    ViewPager pager = welcomeView.findViewById(R.id.welcome_pager);
    pager.setAdapter(adapter);

    if (rtl) {
      pager.setRotationY(180);
    }

    Drawable leftChevron = getResources().getDrawable(R.drawable.ic_chevron_left);
    Drawable rightChevron = getResources().getDrawable(R.drawable.ic_chevron_right);

    final Button backButton = welcomeView.findViewById(R.id.intro_back);
    final Button nextButton = welcomeView.findViewById(R.id.intro_next);

    final Button leftButton, rightButton;
    if (rtl) {
      leftButton = nextButton;
      rightButton = backButton;
    } else {
      leftButton = backButton;
      rightButton = nextButton;
    }
    leftButton.setCompoundDrawablesWithIntrinsicBounds(leftChevron, null, null, null);
    rightButton.setCompoundDrawablesWithIntrinsicBounds(null, null, rightChevron, null);

    backButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        int currentItem = pager.getCurrentItem();
        if (currentItem > 0) {
          pager.setCurrentItem(currentItem - 1);
        }
      }
    });

    nextButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        int currentItem = pager.getCurrentItem();
        if (currentItem < NUM_PAGES - 1) {
          pager.setCurrentItem(currentItem + 1);
        }
      }
    });

    Button acceptButton = welcomeView.findViewById(R.id.intro_accept);
    acceptButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        PersistentState.setWelcomeApproved(getContext(), true);
        DnsVpnController.getInstance().start(getContext());
        dismiss();
      }
    });

    pager.addOnPageChangeListener(new TabListener(backButton, nextButton, acceptButton));

    isShown = true;
    return welcomeView;
  }

  public static class Page extends Fragment {
    private static final String IMAGE = "image";
    private static final String HEADLINE = "headline";
    private static final String BODY = "body";

    static Page newInstance(@DrawableRes int image, @StringRes int headline, @StringRes int body) {
      Page page = new Page();
      Bundle arguments = new Bundle(3);
      arguments.putInt(IMAGE, image);
      arguments.putInt(HEADLINE, headline);
      arguments.putInt(BODY, body);
      page.setArguments(arguments);
      return page;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      View view = inflater.inflate(R.layout.intro_page, container, false);
      boolean rtl = false;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        int layoutDirection = getResources().getConfiguration().getLayoutDirection();
        rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL;
      }
      if (rtl) {
        view.setRotationY(180);
      }

      ImageView imageView = view.findViewById(R.id.intro_image);
      imageView.setImageResource(getArguments().getInt(IMAGE));

      TextView headlineView = view.findViewById(R.id.intro_headline);
      headlineView.setText(getArguments().getInt(HEADLINE));

      TextView bodyView = view.findViewById(R.id.intro_body);
      bodyView.setMovementMethod(LinkMovementMethod.getInstance());
      bodyView.setText(getArguments().getInt(BODY));
      return view;
    }
  }

  private class Adapter extends FragmentPagerAdapter {
    private final int[] images = {
        R.drawable.intro0,
        R.drawable.intro1,
        R.drawable.intro2
    };
    private final int[] headlines = {
        R.string.intro_benefit_headline,
        R.string.intro_manipulation_headline,
        R.string.intro_details_headline
    };
    private final int[] text = {
        R.string.intro_benefit_body,
        R.string.intro_manipulation_body,
        R.string.intro_details_body
    };

    private final Fragment[] pages;
    Adapter(FragmentManager fm) {
      super(fm);
      pages = new Fragment[NUM_PAGES];
      for (int i = 0; i < pages.length; ++i) {
        pages[i] = Page.newInstance(images[i], headlines[i], text[i]);
      }
    }

    @Override
    public int getCount() {
      return pages.length;
    }

    @Override
    public Fragment getItem(int position) {
      if (position >= 0 && position < pages.length) {
        return pages[position];
      }
      return null;
    }
  }

  private class TabListener extends ViewPager.SimpleOnPageChangeListener {
    private final Button backButton, nextButton, acceptButton;

    TabListener(Button backButton, Button nextButton, Button acceptButton) {
      this.backButton = backButton;
      this.nextButton = nextButton;
      this.acceptButton = acceptButton;
    }

    @Override
    public void onPageSelected(int position) {
      super.onPageSelected(position);
      backButton.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
      nextButton.setVisibility(position == NUM_PAGES - 1 ? View.GONE : View.VISIBLE);
      acceptButton.setVisibility(position == NUM_PAGES - 1 ? View.VISIBLE : View.GONE);
    }
  }
}
