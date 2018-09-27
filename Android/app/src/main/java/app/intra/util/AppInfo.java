package app.intra.util;

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
public class AppInfo implements Comparable<AppInfo>, Parcelable {
  public final String label;  // Human-readable app name (may be null)
  public final String packageName;  // Java-style package name (non-null)

  public AppInfo(@Nullable String label, @NonNull String packageName) {
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
