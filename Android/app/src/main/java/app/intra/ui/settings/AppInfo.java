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
package app.intra.ui.settings;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom pair class for labels (human-readable names) and package names (Java-style package
 * identifiers).  This class's primary purpose is to expose a custom ordering, where entries
 * are sorted by label (if non-null) before packageName, and Entries with null labels are placed
 * after those with non-null labels.
 */
class AppInfo implements Comparable<AppInfo>, Parcelable {
  final String label;  // Human-readable app name (may be null)
  final String packageName;  // Java-style package name (non-null)

  AppInfo(@Nullable String label, @NonNull String packageName) {
    this.label = label;
    this.packageName = packageName;
  }

  @Override
  public int compareTo(@NonNull AppInfo other) {
    if (label == null) {
      if (other.label == null) {
        return packageName.compareTo(other.packageName);
      }
      return 1;
    } else if (other.label == null) {
      return -1;
    } else if (!label.equals(other.label)) {
      return label.compareTo(other.label);
    }
    return packageName.compareTo(other.packageName);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeStringArray(new String[]{label, packageName});
  }

  public static final Creator<AppInfo> CREATOR = new Creator<AppInfo>() {
    @Override
    public AppInfo createFromParcel(Parcel in) {
      String[] array = in.createStringArray();
      if (array == null || array.length < 2) {
        return null;
      }
      return new AppInfo(array[0], array[1]);
    }

    @Override
    public AppInfo[] newArray(int size) {
      return new AppInfo[size];
    }
  };
}
