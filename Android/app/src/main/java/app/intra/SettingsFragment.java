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

import com.google.firebase.crash.FirebaseCrash;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * UI Fragment for displaying user preferences.
 * See https://developer.android.com/guide/topics/ui/settings#Fragment
 *
 * This class contains the special logic for the app exclusion setting, which does not require a
 * custom UI.  The server selection preference requires a custom UI, so it has its own class.
 */

public class SettingsFragment extends PreferenceFragmentCompat {
  private static final String LABELS_KEY = "labels";
  private static final String PACKAGENAMES_KEY = "packageNames";

  private AppEnumerationTask enumerator = null;
  private ArrayList<String> labels = null;
  private ArrayList<String> packageNames = null;
  private MultiSelectListPreference apps = null;

  /**
   * Custom pair class for labels (human-readable names) and package names (Java-style package
   * identifiers).  This class's primary purpose is to expose a custom ordering, where entries
   * are sorted by label (if non-null) before packageName, and Entries with null labels are placed
   * after those with non-null labels.
   */
  private class Entry implements Comparable<Entry> {
    final String label;  // Human-readable app name (may be null)
    final String packageName;  // Java-style package name (non-null)

    Entry(@Nullable String label, @NonNull String packageName) {
      this.label = label;
      this.packageName = packageName;
    }

    @Override
    public int compareTo(@NonNull Entry other) {
      if (label == null) {
        if (other.label == null) {
          return packageName.compareTo(other.packageName);
        }
        return -1;
      } else if (other.label == null) {
        return 1;
      } else if (!label.equals(other.label)) {
        return label.compareTo(other.label);
      }
      return packageName.compareTo(other.packageName);
    }
  }

  /**
   * Task representing enumeration of the installed apps.  This task populates |labels| and
   * |packageNames| before completing.  Enumeration typically takes several seconds, so this task
   * ensures that enumeration starts when the user opens Settings, but only blocks the UI when the
   * user opens the Excluded Apps dialog.
   */
  private class AppEnumerationTask extends AsyncTask<PackageManager, Void, List<Entry>> {
    @Override
    protected List<Entry> doInBackground(PackageManager... pms) {
      PackageManager pm = pms[0];
      List<ApplicationInfo> infos = pm.getInstalledApplications(0);
      ArrayList<Entry> entries = new ArrayList<>(infos.size());
      for (ApplicationInfo info : infos) {
        if ("app.intra".equals(info.packageName)) {
          // Showing ourselves in the list of apps would be confusing and useless.
          continue;
        }
        if (pm.getLaunchIntentForPackage(info.packageName) == null) {
          // This is not an app that can actually be launched, so it's confusing to show it.
          continue;
        }
        // Calling loadLabel() can be very slow, because it needs to read into the APK.
        entries.add(new Entry(info.loadLabel(pm).toString(), info.packageName));
      }
      Collections.sort(entries);
      return entries;
    }

    @Override
    protected void onPostExecute(List<Entry> entries) {
      labels = new ArrayList<>(entries.size());
      packageNames = new ArrayList<>(entries.size());
      for (Entry e : entries) {
        labels.add(e.label);
        packageNames.add(e.packageName);
      }
    }
  }

  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    if (waitForAppInfo()) {
      savedInstanceState.putStringArrayList(LABELS_KEY, labels);
      savedInstanceState.putStringArrayList(PACKAGENAMES_KEY, packageNames);
    }
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    if (savedInstanceState != null) {
      // Restore the app list state.
      labels = savedInstanceState.getStringArrayList(LABELS_KEY);
      packageNames = savedInstanceState.getStringArrayList(PACKAGENAMES_KEY);
    }

    // Load the preferences from an XML resource
    addPreferencesFromResource(R.xml.preferences);
    apps = (MultiSelectListPreference)findPreference(PersistentState.APPS_KEY);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      // App exclusion relies on VpnService.Builder.addDisallowedApplication, which was added in L.
      apps.setEnabled(false);
      apps.setSummary(R.string.old_android);
    }
  }

  @Override
  public void onDisplayPreferenceDialog(Preference preference) {
    if (preference instanceof ServerChooser) {
      DialogFragment dialogFragment = ServerChooserFragment.newInstance(preference.getKey());
      dialogFragment.setTargetFragment(this, 0);
      dialogFragment.show(getFragmentManager(), null);
    } else {
      // This is the app exclusion dialog.
      if (waitForAppInfo()) {
        apps.setEntries(labels.toArray(new String[0]));
        apps.setEntryValues(packageNames.toArray(new String[0]));
      }

      super.onDisplayPreferenceDialog(preference);
    }
  }

  /**
   * Update this SettingsFragment's list of installed apps for the "Excluded apps" setting.  This
   * method must be called before the fragment is displayed.
   * @param pm The global PackageManager.
   */
  @MainThread
  void collectInstalledApps(PackageManager pm) {
    enumerator = new AppEnumerationTask();
    enumerator.execute(pm);
  }

  /**
   * Blocks until |labels| and |packageNames| have been populated by the async task.
   * @return True if this was successful, or false if labels and packageNames are not available.
   */
  @MainThread
  private boolean waitForAppInfo() {
    if (labels != null && packageNames != null) {
      return true;
    }
    if (enumerator != null) {
      try {
        enumerator.get();
        return true;
      } catch (InterruptedException e) {
        // Thread was interrupted.  This is probably fine.
      } catch (ExecutionException e) {
        // Something bad happened during the async task.
        FirebaseCrash.report(e);
      }
    }
    return false;
  }
}
