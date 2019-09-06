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
package app.intra.sys;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.intra.R;
import app.intra.ui.settings.Untemplate;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Static class representing on-disk storage of mutable state.  Collecting this all in one class
 * helps to reduce duplication of code using SharedPreferences, allows settings to be read and
 * written by separate components, and also helps to improve preference naming consistency.
 */
public class PersistentState {
  private static final String LOG_TAG = "PersistentState";

  public static final String APPS_KEY = "pref_apps";
  public static final String URL_KEY = "pref_server_url";

  private static final String APPROVED_KEY = "approved";
  private static final String ENABLED_KEY = "enabled";
  private static final String SERVER_KEY = "server";

  private static final String INTERNAL_STATE_NAME = "MainActivity";

  // The approval state is currently stored in a separate preferences file.
  // TODO: Unify preferences into a single file.
  private static final String APPROVAL_PREFS_NAME = "IntroState";

  private static SharedPreferences getInternalState(Context context) {
    return context.getSharedPreferences(INTERNAL_STATE_NAME, Context.MODE_PRIVATE);
  }

  private static SharedPreferences getUserPreferences(Context context) {
    return PreferenceManager.getDefaultSharedPreferences(context);
  }

  static boolean getVpnEnabled(Context context) {
    return getInternalState(context).getBoolean(ENABLED_KEY, false);
  }

  static void setVpnEnabled(Context context, boolean enabled) {
    SharedPreferences.Editor editor = getInternalState(context).edit();
    editor.putBoolean(ENABLED_KEY, enabled);
    editor.apply();
  }

  public static void syncLegacyState(Context context) {
    // Copy the domain choice into the new URL setting, if necessary.
    if (getServerUrl(context) != null) {
      // New server URL is already populated
      return;
    }

    // There is no URL setting, so read the legacy server name.
    SharedPreferences settings = getInternalState(context);
    String domain = settings.getString(SERVER_KEY, null);
    if (domain == null) {
      // Legacy setting is in the default state, so we can leave the new URL setting in the default
      // state as well.
      return;
    }

    String[] urls = context.getResources().getStringArray(R.array.urls);
    String defaultDomain = context.getResources().getString(R.string.legacy_domain0);
    String url = null;
    if (domain.equals(defaultDomain)) {
      // Common case: the domain is dns.google.com, which now corresponds to dns.google (url 0).
      url = urls[0];
    } else {
      // In practice, we expect that domain will always be cloudflare-dns.com at this point, because
      // that was the only other option in the relevant legacy versions of Intra.
      // Look for the corresponding URL among the builtin servers.
      for (String u : urls) {
        Uri parsed = Uri.parse(u);
        if (domain.equals(parsed.getHost())) {
          url = u;
          break;
        }
      }
    }

    if (url == null) {
      LogWrapper.log(Log.WARN, LOG_TAG, "Legacy domain is unrecognized");
      return;
    }
    setServerUrl(context, url);
  }

  public static void setServerUrl(Context context, String url) {
    SharedPreferences.Editor editor = getUserPreferences(context).edit();
    editor.putString(URL_KEY, url);
    editor.apply();
  }

  public static String getServerUrl(Context context) {
    String urlTemplate = getUserPreferences(context).getString(URL_KEY, null);
    if (urlTemplate == null) {
      return null;
    }
    return Untemplate.strip(urlTemplate);
  }

  private static String extractHost(String url) {
    try {
      URL parsed = new URL(url);
      return parsed.getHost();
    } catch (MalformedURLException e) {
      LogWrapper.log(Log.WARN, LOG_TAG, "URL is corrupted");
      return null;
    }
  }

  // Converts a null url into the actual default url.  Otherwise, returns url unmodified.
  public static @NonNull String expandUrl(Context context, @Nullable String url) {
    if (url == null || url.isEmpty()) {
      return context.getResources().getString(R.string.url0);
    }
    return url;
  }

  // Returns a domain representing the current configured server URL, for use as a display name.
  public static String getServerName(Context context) {
    return extractHost(expandUrl(context, getServerUrl(context)));
  }

  /**
   * Extract the hostname from the URL, while avoiding disclosure of custom servers.
   * @param url A DoH server configuration url (or url template).
   * @return Returns the domain name in the URL, or "CUSTOM_SERVER" if the url is not one of the
   * built-in servers.
   */
  public static String extractHostForAnalytics(Context context, String url) {
    String expanded = expandUrl(context, url);
    String[] urls = context.getResources().getStringArray(R.array.urls);
    if (Arrays.asList(urls).contains(expanded)) {
      return extractHost(expanded);
    }
    return Names.CUSTOM_SERVER.name();
  }

  private static SharedPreferences getApprovalSettings(Context context) {
    return context.getSharedPreferences(APPROVAL_PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static boolean getWelcomeApproved(Context context) {
    return getApprovalSettings(context).getBoolean(APPROVED_KEY, false);
  }

  public static void setWelcomeApproved(Context context, boolean approved) {
    SharedPreferences.Editor editor = getApprovalSettings(context).edit();
    editor.putBoolean(APPROVED_KEY, approved);
    editor.apply();
  }

  static Set<String> getExcludedPackages(Context context) {
    return getUserPreferences(context).getStringSet(APPS_KEY, new HashSet<String>());
  }
}
