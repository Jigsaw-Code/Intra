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
package app.intra.ui.settings;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import app.intra.R;
import app.intra.sys.Names;
import app.intra.sys.PersistentState;
import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * This dialog shows the user the fastest server we detected and allows them to accept or decline.
 * It also gives them the option to view the server's website before approving.  The website is
 * displayed inside the body of the dialog.  This unusual arrangement is necessary because an
 * ordinary URL intent would not work.  The intent would open in a web browser, but this dialog is
 * used when the VPN is active and failing.  The web browser would therefore fail to open the
 * server's page.  Opening the page in a WebView here works, because Intra itself is whitelisted
 * to bypass the VPN.
 */
public class ServerApprovalDialogFragment extends DialogFragment {
  private static final String INDEX_KEY = "index";
  private static final String SHOW_WEBSITE_KEY = "showWebsite";

  /**
   * Default constructor.  This is present because it's required by the framework, to handle
   * re-creation of the dialog (e.g. on rotation).  Arguments are persisted in the arguments bundle.
   */
  public ServerApprovalDialogFragment() {
    super();
  }

  /**
   * This is the constructor used by MainActivity.  It creates a version of the dialog with three
   * buttons: Accept, Cancel, and Visit Website.
   * @param index The index of the recommended server in the built-in server database.
   */
  public ServerApprovalDialogFragment(int index) {
    super();
    setIndex(index);
  }

  /**
   * Private constructor, used to show a version of the dialog that contains the website, with only
   * two buttons (Accept and Cancel).
   * @param index The index of the recommended server in the built-in server database.
   * @param showWebsite If true, show the server's website in the body of the dialog.
   */
  private ServerApprovalDialogFragment(int index, boolean showWebsite) {
    super();
    setIndex(index);
    setShowWebsite(showWebsite);
  }

  private Bundle getArgs() {
    Bundle args = getArguments();
    if (args != null) {
      return args;
    }
    return new Bundle();
  }

  private void setIndex(int index) {
    Bundle args = getArgs();
    args.putInt(INDEX_KEY, index);
    setArguments(args);
  }

  private int getIndex() {
    return getArgs().getInt(INDEX_KEY);
  }

  private void setShowWebsite(boolean showWebsite) {
    Bundle args = getArgs();
    args.putBoolean(SHOW_WEBSITE_KEY, showWebsite);
    setArguments(args);
  }

  private boolean getShowWebsite() {
    return getArgs().getBoolean(SHOW_WEBSITE_KEY);
  }

  private View makeWebView(int index) {
    final String url = getResources().getStringArray(R.array.server_websites)[index];

    final WebView webView = new WebView(getContext());
    webView.getSettings().setJavaScriptEnabled(true);
    webView.setWebViewClient(new WebViewClient());  // Makes navigation stay in this webview.
    webView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    webView.loadUrl(url);

    return webView;
  }

  private View makeNoticeText() {
    final LayoutInflater inflater = requireActivity().getLayoutInflater();
    final View dialogContent = inflater.inflate(R.layout.server_notice_dialog, null);
    TextView serverWebsite = dialogContent.findViewById(R.id.server_website);
    serverWebsite.setText(R.string.server_choice_website_notice);

    // Remove hyperlink.  Its functionality is provided by the dialog's neutral button instead.
    Spannable s = new SpannableString(serverWebsite.getText());
    for (URLSpan span: s.getSpans(0, s.length(), URLSpan.class)) {
      s.removeSpan(span);
    }
    serverWebsite.setText(s);
    return dialogContent;
  }

  private Bundle getAnalyticsBundle() {
    final int index = getIndex();
    final String url = getResources().getStringArray(R.array.urls)[index];
    final Bundle bundle = new Bundle();
    bundle.putString(Names.SERVER.name(),
        PersistentState.extractHostForAnalytics(getContext(), url));
    return bundle;
  }

  @Override
  public @NonNull Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

    final int index = getIndex();
    final String name = getResources().getStringArray(R.array.names)[index];
    final String template = getResources().getString(R.string.found_fastest);
    final String message = String.format(template, name);

    final boolean showWebsite = getShowWebsite();
    final View body = showWebsite ? makeWebView(index) : makeNoticeText();

    final FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(getContext());
    analytics.logEvent(Names.TRY_ALL_DIALOG.name(), getAnalyticsBundle());
    builder.setView(body)
        .setTitle(message)
        .setPositiveButton(R.string.intro_accept, (DialogInterface d, int id) -> {
          final String url = getResources().getStringArray(R.array.urls)[index];
          PersistentState.setServerUrl(getContext(), url);
          analytics.logEvent(Names.TRY_ALL_ACCEPTED.name(), getAnalyticsBundle());
        })
        .setNegativeButton(android.R.string.cancel, (d, id) -> onCancel(d));

    if (!showWebsite) {
      builder.setNeutralButton(R.string.server_website_button, (DialogInterface d, int id) -> {
        // Reopen the dialog in webview mode.
        new ServerApprovalDialogFragment(index, true).show(getFragmentManager(), "dialog");
      });
    }
    return builder.create();
  }

  @Override
  public void onCancel(DialogInterface dialog) {
    final FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(getContext());
    analytics.logEvent(Names.TRY_ALL_CANCELLED.name(), getAnalyticsBundle());
  }
}

