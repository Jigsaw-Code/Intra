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
import android.content.Context;
import android.content.SharedPreferences;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;

public class WelcomePopup {

  private static final String PREFS_NAME = "IntroState";
  private static final String APPROVED_KEY = "approved";

  public static boolean shouldShow(Activity activity) {
    SharedPreferences settings = activity.getSharedPreferences(PREFS_NAME, 0);
    return !settings.getBoolean(APPROVED_KEY, false);
  }

  public WelcomePopup(final MainActivity mainActivity) {
    LayoutInflater layoutInflater = (LayoutInflater) mainActivity.getSystemService(
        Context.LAYOUT_INFLATER_SERVICE);
    View welcomeView = layoutInflater.inflate(R.layout.welcome, null);

    View mainView = mainActivity.findViewById(R.id.activity_main);
    final PopupWindow popupWindow = new PopupWindow(welcomeView,
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

    Button acceptButton = welcomeView.findViewById(R.id.accept);
    acceptButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        SharedPreferences settings = mainActivity.getSharedPreferences(PREFS_NAME,
            0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean(APPROVED_KEY, true);
        editor.apply();
        popupWindow.dismiss();
      }
    });

    // Enable hyperlinks on the welcome screen.
    int[] idsToLink = {
        R.id.welcome_privacy,
        R.id.welcome_credits
    };
    for (int id : idsToLink) {
      TextView textView = (TextView) welcomeView.findViewById(id);
      textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    popupWindow.showAtLocation(mainView, Gravity.NO_GRAVITY, 0, 0);
  }
}
