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
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;
import app.intra.R;
import app.intra.sys.PersistentState;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * User interface for a the server URL selection.
 */

public class ServerChooserFragment extends PreferenceDialogFragmentCompat
    implements RadioGroup.OnCheckedChangeListener, TextWatcher, EditText.OnEditorActionListener,
    OnItemSelectedListener {
    private RadioGroup buttons = null;

    // Builtin servers
    private Spinner spinner = null;
    private TextView description = null;
    private TextView serverWebsite = null;

    // Custom server
    private EditText customServerUrl = null;
    private TextView warning = null;

    // Builtin server resources, initialized in onBindDialogView.
    private String[] urls = null;
    private String[] descriptions = null;
    private String[] websiteLinks = null;

    static ServerChooserFragment newInstance(String key) {
        final ServerChooserFragment fragment = new ServerChooserFragment();
        final Bundle bundle = new Bundle(1);
        bundle.putString(ARG_KEY, key);
        fragment.setArguments(bundle);
        return fragment;
    }

    private String getUrl() {
        int checkedId = buttons.getCheckedRadioButtonId();
        if (checkedId == R.id.pref_server_custom) {
            return customServerUrl.getText().toString();
        }

        return urls[spinner.getSelectedItemPosition()];
    }

    private void updateUI() {
        int checkedId = buttons.getCheckedRadioButtonId();
        boolean custom = checkedId == R.id.pref_server_custom;
        customServerUrl.setEnabled(custom);
        spinner.setEnabled(!custom);
        description.setEnabled(!custom);
        serverWebsite.setEnabled(!custom);
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
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        onBuiltinServerSelected(i);
    }

    private void onBuiltinServerSelected(int i) {
        description.setText(descriptions[i]);

        // Update website text with a link pointing to the correct website.
        // The string resource contains a dummy hyperlink that must be replaced with a new link to
        // the correct destination.
        CharSequence template = getResources().getText(R.string.server_choice_website_notice);
        SpannableStringBuilder websiteMessage = new SpannableStringBuilder(template);

        // Trim leading whitespace introduced by indentation in strings.xml.
        while (Character.isWhitespace(websiteMessage.charAt(0))) {
            websiteMessage.delete(0, 1);
        }

        // Replace hyperlink with a new link for the current URL.
        URLSpan templateLink =
            websiteMessage.getSpans(0, websiteMessage.length(), URLSpan.class)[0];
        int linkStart = websiteMessage.getSpanStart(templateLink);
        int linkEnd = websiteMessage.getSpanEnd(templateLink);
        websiteMessage.removeSpan(templateLink);
        websiteMessage.setSpan(new URLSpan(websiteLinks[i]), linkStart, linkEnd, 0);

        serverWebsite.setText(websiteMessage);
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        updateUI();
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        ServerChooser preference = (ServerChooser) getPreference();
        String url = preference.getUrl();

        urls = getResources().getStringArray(R.array.urls);
        descriptions = getResources().getStringArray(R.array.descriptions);
        websiteLinks = getResources().getStringArray(R.array.server_websites);

        buttons = view.findViewById(R.id.pref_server_radio_group);
        spinner = view.findViewById(R.id.builtin_server_spinner);
        description = view.findViewById(R.id.server_description);
        serverWebsite = view.findViewById(R.id.server_website);
        customServerUrl = view.findViewById(R.id.custom_server_url);
        warning = view.findViewById(R.id.url_warning);

        // Make website link clickable.
        serverWebsite.setMovementMethod(LinkMovementMethod.getInstance());

        // Check if we are using one of the built-in servers.
        int index = -1;
        String expanded = PersistentState.expandUrl(getContext(), url);
        for (int i = 0; i < urls.length; ++i) {
            if (urls[i].equals(expanded)) {
                index = i;
                break;
            }
        }

        if (index >= 0) {
            buttons.check(R.id.pref_server_builtin);
            spinner.setSelection(index);
            onBuiltinServerSelected(index);
        } else {
            buttons.check(R.id.pref_server_custom);
            customServerUrl.setText(url);
        }
        buttons.setOnCheckedChangeListener(this);
        spinner.setOnItemSelectedListener(this);
        customServerUrl.addTextChangedListener(this);
        customServerUrl.setOnEditorActionListener(this);
        updateUI();
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            ServerChooser preference = (ServerChooser) getPreference();
            preference.setUrl(getUrl());
        }
        customServerUrl.removeTextChangedListener(this);
        customServerUrl.setOnEditorActionListener(null);
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
