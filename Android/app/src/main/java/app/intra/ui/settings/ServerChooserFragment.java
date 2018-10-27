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

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;
import app.intra.R;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * User interface for a the server URL selection.
 */

public class ServerChooserFragment extends PreferenceDialogFragmentCompat
    implements RadioGroup.OnCheckedChangeListener, TextWatcher, EditText.OnEditorActionListener {
    private RadioGroup buttons = null;
    private EditText text = null;
    private TextView warning = null;

    static ServerChooserFragment newInstance(String key) {
        final ServerChooserFragment fragment = new ServerChooserFragment();
        final Bundle bundle = new Bundle(1);
        bundle.putString(ARG_KEY, key);
        fragment.setArguments(bundle);
        return fragment;
    }

    private String getUrl() {
        int checkedId = buttons.getCheckedRadioButtonId();
        if (checkedId == R.id.pref_server_google) {
            return getResources().getString(R.string.url0);
        } else if (checkedId == R.id.pref_server_cloudflare) {
            return getResources().getString(R.string.url1);
        } else if (checkedId == R.id.pref_server_dns_quad9) {
            return getResources().getString(R.string.url2);
        } else if (checkedId == R.id.pref_server_dns10_quad9) {
            return getResources().getString(R.string.url3);
        } else {
            return text.getText().toString();
        }
    }

    private void updateUI() {
        int checkedId = buttons.getCheckedRadioButtonId();
        boolean custom = checkedId == R.id.pref_server_custom;
        text.setEnabled(custom);
        if (custom) {
            setValid(checkUrl(Untemplate.strip(getUrl())));
        } else {
            setValid(true);
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        updateUI();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        updateUI();
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private boolean checkUrl(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol().equals("https") && !parsed.getHost().isEmpty() &&
                !parsed.getPath().isEmpty() && parsed.getQuery() == null && parsed.getRef() == null;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Usability optimization:
        // If the user is typing in the free text field and presses "enter" or "go" on the keyboard
        // while the URL is valid, treat that the same as closing the keyboard and pressing "OK".
        if (checkUrl(Untemplate.strip(v.getText().toString()))) {
            Dialog dialog = getDialog();
            if (dialog instanceof AlertDialog) {
                Button ok = ((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE);
                ok.setEnabled(true);
                ok.performClick();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        ServerChooser preference = (ServerChooser) getPreference();
        String url = preference.getUrl();
        buttons = view.findViewById(R.id.pref_server_radio_group);
        text = view.findViewById(R.id.custom_server_url);
        warning = view.findViewById(R.id.url_warning);
        if (url == null || url.equals(getResources().getString(R.string.url0))) {
            buttons.check(R.id.pref_server_google);
        } else if (url.equals(getResources().getString(R.string.url1))) {
            buttons.check(R.id.pref_server_cloudflare);
        } else if (url.equals(getResources().getString(R.string.url2))) {
            buttons.check(R.id.pref_server_dns_quad9);
        } else if (url.equals(getResources().getString(R.string.url3))) {
            buttons.check(R.id.pref_server_dns10_quad9);
        } else {
            buttons.check(R.id.pref_server_custom);
            text.setText(url);
        }
        buttons.setOnCheckedChangeListener(this);
        text.addTextChangedListener(this);
        text.setOnEditorActionListener(this);
        updateUI();
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            ServerChooser preference = (ServerChooser) getPreference();
            preference.setUrl(getUrl());
        }
        text.removeTextChangedListener(this);
        text.setOnEditorActionListener(null);
        buttons.setOnCheckedChangeListener(null);
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        updateUI();
    }

    private void setValid(boolean valid) {
        warning.setVisibility(valid ? View.INVISIBLE : View.VISIBLE);
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            ((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(valid);
        }
    }
}
