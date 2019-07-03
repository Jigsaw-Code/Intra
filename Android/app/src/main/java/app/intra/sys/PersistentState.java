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
  private static final String EXTRA_SERVERS_V4_KEY = "extraServersV4";
  private static final String EXTRA_SERVERS_V6_KEY = "extraServersV6";
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
    String defaultDomain = context.getResources().getString(R.string.domain0);
    String domain = settings.getString(SERVER_KEY, defaultDomain);

    if (domain == null) {
      // Legacy setting is in the default state, so we can leave the new URL setting in the default
      // state as well.
      return;
    }

    // Special case: url 0 is the nonstandard DoH service on dns.google.com.
    // TODO: Remove this special case once dns.google.com transitions to standard DoH.
    String[] urls = context.getResources().getStringArray(R.array.urls);
    String url = null;
    if (domain.equals(defaultDomain)) {
      url = urls[0];
    }

    // Look for the corresponding URL among the other builtin servers.
    for (int i = 1; i < urls.length; ++i) {
      Uri parsed = Uri.parse(urls[i]);

      if (domain.equals(parsed.getHost())) {
        url = urls[i];
        break;
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

  static String getServerUrl(Context context) {
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

  public static String getServerName(Context context) {
    String url = getServerUrl(context);
    if (url == null || url.isEmpty()) {
      return context.getResources().getString(R.string.domain0);
    }
    return extractHost(url);
  }

  /**
   * Extract the hostname from the URL, while avoiding disclosure of custom servers.
   * @param url A DoH server configuration url (or url template).
   * @return Returns the domain name in the URL, or "CUSTOM_SERVER" if the url is not one of the
   * built-in servers.
   */
  public static String extractHostForAnalytics(Context context, String url) {
    if (url == null || url.isEmpty()) {
      return context.getResources().getString(R.string.domain0);
    }
    String[] urls = context.getResources().getStringArray(R.array.urls);
    if (Arrays.asList(urls).contains(url)) {
      return extractHost(url);
    }
    return Names.CUSTOM_SERVER.name();
  }

  public static Set<String> getExtraGoogleV4Servers(Context context) {
    return getInternalState(context).getStringSet(EXTRA_SERVERS_V4_KEY, new HashSet<String>());
  }

  public static void setExtraGoogleV4Servers(Context context, String[] servers) {
    SharedPreferences.Editor editor = getInternalState(context).edit();
    editor.putStringSet(EXTRA_SERVERS_V4_KEY,
        new HashSet<String>(Arrays.asList(servers)));
    editor.apply();
  }

  public static Set<String> getExtraGoogleV6Servers(Context context) {
    return getInternalState(context).getStringSet(EXTRA_SERVERS_V6_KEY, new HashSet<String>());
  }

  public static void setExtraGoogleV6Servers(Context context, String[] servers) {
    SharedPreferences.Editor editor = getInternalState(context).edit();
    editor.putStringSet(EXTRA_SERVERS_V6_KEY,
        new HashSet<String>(Arrays.asList(servers)));
    editor.apply();
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
