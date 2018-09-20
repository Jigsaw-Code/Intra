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
import android.widget.TextView;

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
  private static boolean shown = false;

  public static boolean shouldShow(Activity activity) {
    return !shown && !PersistentState.getWelcomeApproved(activity);
  }

  @Override
  public int getTheme() {
    return R.style.FullScreenDialog;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle
      savedInstanceState) {
    View welcomeView = inflater.inflate(R.layout.intro_pager, container);

    Adapter adapter = new Adapter(getChildFragmentManager());
    ViewPager pager = welcomeView.findViewById(R.id.welcome_pager);
    pager.setAdapter(adapter);

    // Right-to-left locale support.
    boolean rtl = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      rtl = welcomeView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    Button backButton = welcomeView.findViewById(R.id.intro_back);
    // Workaround for lack of vector drawable support pre-Lollipop with drawableEnd.
    // Using a bitmap drawable might be a simpler solution, but bitmaps don't have auto-mirroring.
    // ic_chevron_start is auto-mirroring, so we just have to put it on the outside end.
    Drawable startChevron = getResources().getDrawable(R.drawable.ic_chevron_start);
    if (rtl) {
      backButton.setCompoundDrawablesWithIntrinsicBounds(null, null, startChevron, null);
    } else {
      backButton.setCompoundDrawablesWithIntrinsicBounds(startChevron, null, null, null);
    }
    backButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        int currentItem = pager.getCurrentItem();
        if (currentItem > 0) {
          pager.setCurrentItem(currentItem - 1);
        }
      }
    });

    Button nextButton = welcomeView.findViewById(R.id.intro_next);
    Drawable endChevron = getResources().getDrawable(R.drawable.ic_chevron_end);
    if (rtl) {
      nextButton.setCompoundDrawablesWithIntrinsicBounds(endChevron, null, null, null);
    } else {
      nextButton.setCompoundDrawablesWithIntrinsicBounds(null, null, endChevron, null);
    }
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

    shown = true;
    return welcomeView;
  }

  public static class Page0 extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      return inflater.inflate(R.layout.intro0, container, false);

    }
  }

  public static class Page1 extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      return inflater.inflate(R.layout.intro1, container, false);
    }
  }

  public static class Page2 extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      View view = inflater.inflate(R.layout.intro2, container, false);
      TextView details = view.findViewById(R.id.intro_details_body);
      details.setMovementMethod(LinkMovementMethod.getInstance());
      return view;
    }
  }

  private class Adapter extends FragmentPagerAdapter {
    private final Fragment[] pages;
    Adapter(FragmentManager fm) {
      super(fm);
      pages = new Fragment[NUM_PAGES];
      pages[0] = new Page0();
      pages[1] = new Page1();
      pages[2] = new Page2();
    }

    @Override
    public int getCount() {
      return pages.length;
    }

    @Override
    public Fragment getItem(int position) {
      return pages[position];
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
