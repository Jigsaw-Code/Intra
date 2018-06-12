package app.intra;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static class representing on-disk storage of mutable state.  Collecting this all in one class
 * helps to reduce duplication of code using SharedPreferences, allows settings to be read and
 * written by separate components, and also helps to improve preference naming consistency.
 */
public class Preferences {

  private static final String APPROVED_KEY = "approved";
  private static final String EXTRA_SERVERS_V4_KEY = "extraServersV4";
  private static final String EXTRA_SERVERS_V6_KEY = "extraServersV6";
  private static final String SERVER_KEY = "server";

  private static final String MAIN_PREFS_NAME = "MainActivity";

  // The approval state is currently stored in a separate preferences file.
  // TODO: Unify preferences into a single file.
  private static final String APPROVAL_PREFS_NAME = "IntroState";

  private static SharedPreferences getSettings(Context context) {
    return context.getSharedPreferences(MAIN_PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static String getServerName(Context context) {
    SharedPreferences settings = getSettings(context);

    String defaultDomain = context.getResources().getStringArray(R.array.domains)[0];
    return settings.getString(SERVER_KEY, defaultDomain);
  }

  public static boolean setServerName(Context context, String name) {
    String oldName = getServerName(context);
    if (name == null && oldName == null) {
      return true;
    }
    if (name != null && name.equals(oldName)) {
      return true;
    }
    final List<String> knownNames =
        Arrays.asList(context.getResources().getStringArray(R.array.domains));
    if (!knownNames.contains(name)) {
      return false;
    }
    SharedPreferences settings = getSettings(context);
    SharedPreferences.Editor editor = settings.edit();

    editor.putString(SERVER_KEY, name);
    editor.apply();

    return true;
  }

  public static String getServerUrl(Context context) {
    String[] domains = context.getResources().getStringArray(R.array.domains);
    String[] urls = context.getResources().getStringArray(R.array.urls);
    String domain = getServerName(context);
    for (int i = 0; i < domains.length; ++i) {
      if (domains[i].equals(domain)) {
        return urls[i];
      }
    }
    return null;
  }

  public static Set<String> getExtraGoogleV4Servers(Context context) {
    return getSettings(context).getStringSet(EXTRA_SERVERS_V4_KEY, new HashSet<String>());
  }

  public static void setExtraGoogleV4Servers(Context context, String[] servers) {
    SharedPreferences.Editor editor = getSettings(context).edit();
    editor.putStringSet(EXTRA_SERVERS_V4_KEY,
        new HashSet<String>(Arrays.asList(servers)));
    editor.apply();
  }

  public static Set<String> getExtraGoogleV6Servers(Context context) {
    return getSettings(context).getStringSet(EXTRA_SERVERS_V6_KEY, new HashSet<String>());
  }

  public static void setExtraGoogleV6Servers(Context context, String[] servers) {
    SharedPreferences.Editor editor = getSettings(context).edit();
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
}
