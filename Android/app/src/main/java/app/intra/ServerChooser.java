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

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.support.v7.preference.DialogPreference;
import android.util.AttributeSet;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * A preference that opens a dialog, allowing the user to choose their preferred server from a list
 * or by entering a URL.
 */

public class ServerChooser extends DialogPreference {
  private String url;
  private String summaryTemplate;
  private String defaultDomain;

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public ServerChooser(Context context) {
    super(context);
    initialize(context);
  }

  public ServerChooser(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(context);
  }

  public ServerChooser(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize(context);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public ServerChooser(Context context, AttributeSet attrs, int defStyle, int defStyleRes) {
    super(context, attrs, defStyle, defStyleRes);
    initialize(context);
  }

  private void initialize(Context context) {
    setPersistent(true);
    setDialogLayoutResource(R.layout.servers);
    summaryTemplate =
        context.getResources().getString(R.string.server_choice_summary);
    defaultDomain =
        context.getResources().getString(R.string.domain0);
  }

  @Override
  protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
    setUrl(restorePersistedValue ? getPersistedString(url) : (String) defaultValue);
  }

  @Override
  protected Object onGetDefaultValue(TypedArray a, int index) {
    return a.getString(index);
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
    persistString(url);
    updateSummary(url);
  }

  // Updates the "Currently <servername>" summary under the title.
  private void updateSummary(String url) {
    String domain = null;
    if (url == null || url.isEmpty()) {
      domain = defaultDomain;
    } else {
      try {
        URL parsed = new URL(url);
        domain = parsed.getHost();
      } catch (MalformedURLException e) {
        // Leave domain null.
      }
    }
    if (domain != null) {
      setSummary(String.format(Locale.ROOT, summaryTemplate, domain));
    } else {
      setSummary(null);
    }
  }

}
