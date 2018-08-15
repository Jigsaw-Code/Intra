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

import com.google.android.material.navigation.NavigationView;
import com.google.firebase.crash.FirebaseCrash;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.intra.util.DnsQueryTracker;
import app.intra.util.DnsTransaction;
import app.intra.util.Feedback;
import app.intra.util.Names;

public class MainActivity extends AppCompatActivity
    implements SharedPreferences.OnSharedPreferenceChangeListener {

  private static final String LOG_TAG = "MainActivity";

  private static final int REQUEST_CODE_PREPARE_VPN = 100;
  public static final int RESULT_OK = -1;

  private ActionBarDrawerToggle drawerToggle;
  private RecyclerView recyclerView;
  private RecyclerAdapter adapter;
  private RecyclerView.LayoutManager layoutManager;
  private View controlView = null;
  private Timer activityTimer;

  private BroadcastReceiver messageReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Names.RESULT.name().equals(intent.getAction())) {
            DnsVpnService dnsVpnService = DnsVpnServiceState.getInstance().getDnsVpnService();
            if (dnsVpnService == null) {
              FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Found unexpected null DnsVpnService");
              return;
            }
            updateStatsDisplay(getNumRequests(),
                (DnsTransaction) intent.getSerializableExtra(Names.TRANSACTION.name()));

          } else if (Names.DNS_STATUS.name().equals(intent.getAction())) {
            setDnsStatus(intent.getBooleanExtra(Names.DNS_STATUS.name(), false));
          }
        }
      };

  private void updateStatsDisplay(long numRequests, DnsTransaction transaction) {
    showNumRequests(numRequests);
    showTransaction(transaction);
  }

  private void showTransaction(DnsTransaction transaction) {
    if (isHistoryEnabled()) {
      adapter.add(transaction);
    }
  }

  private void showNumRequests(long numRequests) {
    if (controlView == null) {
      return;
    }
    TextView numRequestsTextView = (TextView) controlView.findViewById(R.id.numRequests);
    numRequestsTextView.setText(String.format(Locale.getDefault(), "%,d", numRequests));
  }

  private void openUrl(String url) {
    Intent i = new Intent(Intent.ACTION_VIEW);
    i.setData(Uri.parse(url));
    startActivity(i);
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
    if (PersistentState.URL_KEY.equals(key)) {
      updateServerName();
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Sync old settings into new preferences if necessary.
    PersistentState.syncLegacyState(this);

    // Export defaults into preferences.  See https://developer.android.com/guide/topics/ui/settings#Defaults
    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

    // Registers this class as a listener for user preference changes.
    PreferenceManager.getDefaultSharedPreferences(this).
        registerOnSharedPreferenceChangeListener(this);

    // Enable SVG support on very old versions of Android.
    AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

    setContentView(R.layout.activity_main);

    // Set up the toolbar
    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    // Set up the drawer toggle
    DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.activity_main);
    drawerToggle = new ActionBarDrawerToggle(
        this,                  /* host Activity */
        drawerLayout,         /* DrawerLayout object */
        toolbar,
        R.string.drawer_open,  /* "open drawer" description */
        R.string.drawer_close  /* "close drawer" description */
    );
    drawerLayout.addDrawerListener(drawerToggle);

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setHomeButtonEnabled(true);

    drawerToggle.setToolbarNavigationClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        chooseView(R.id.frame_main);
      }
    });

    // Set up the drawer
    NavigationView drawer = (NavigationView) findViewById(R.id.drawer);
    drawer.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
      @Override
      public boolean onNavigationItemSelected(MenuItem item) {
        switch (item.getItemId()) {
          case R.id.up:
          case R.id.home:
            chooseView(R.id.frame_main);
            return true;
          case R.id.settings:
            chooseView(R.id.settings);
            return true;
          case R.id.report_error:
            chooseView(R.id.frame_report);
            return true;
          case R.id.privacy:
            openUrl("https://developers.google.com/speed/public-dns/privacy");
            return true;
          case R.id.tos:
            openUrl("https://jigsaw.google.com/jigsaw-tos.html");
            return true;
          case R.id.source_code:
            openUrl("https://github.com/Jigsaw-Code/intra");
            return true;
          default:
            return false;
        }
      }
    });

    // Set up the recycler
    recyclerView = (RecyclerView) findViewById(R.id.recycler);
    recyclerView.setHasFixedSize(true);
    layoutManager = new LinearLayoutManager(this);
    recyclerView.setLayoutManager(layoutManager);
    adapter = new RecyclerAdapter(this);
    adapter.reset(getHistory());
    recyclerView.setAdapter(adapter);

    // Register broadcast receiver
    IntentFilter intentFilter = new IntentFilter(Names.RESULT.name());
    intentFilter.addAction(Names.DNS_STATUS.name());
    LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, intentFilter);

    // Connect error report button
    Button errorButton = (Button) findViewById(R.id.feedbackButton);
    errorButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // Send user feedback to Crashlytics
        EditText userErrorReport = (EditText) findViewById(R.id.feedbackContent);
        String userMessage = userErrorReport.getText().toString();
        FirebaseCrash.logcat(Log.INFO, LOG_TAG, userMessage);
        FirebaseCrash.report(new Feedback("User feedback"));

        // Go back to the home screen
        chooseView(R.id.frame_main);
      }
    });

    prepareHyperlinks(this, findViewById(R.id.activity_main));
  }

  @Override
  public void onAttachedToWindow() {
    // Show the intro dialog.
    getIntroApproval();
  }

  public View getControlView(ViewGroup parent) {
    if (controlView != null) {
      return controlView;
    }
    controlView = LayoutInflater.from(parent.getContext()).inflate(R.layout.main_content, parent,
        false);

    // Set up the main UI
    final ToggleButton switchButton = (ToggleButton) controlView.findViewById(R.id.dns_switch);
    setDnsStatus(isServiceRunning());
    switchButton.setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
              prepareAndStartDnsVpn();
            } else {
              stopDnsVpnService();
            }
          }
        });

    final CheckBox showHistory = (CheckBox) controlView.findViewById(R.id.show_history);
    if (isServiceRunning()) {
      showHistory.setChecked(isHistoryEnabled());
    }
    final DnsQueryTracker tracker = DnsVpnServiceState.getInstance().getTracker(this);
    showHistory.setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            tracker.setHistoryEnabled(isChecked);

            if (!isChecked) {
              // Clear the visual state immediately
              adapter.reset(null);
            }
          }
        });

    // Restore number of requests.
    long numRequests = tracker.getNumRequests();
    showNumRequests(numRequests);

    updateServerName();

    // Start updating the live Queries Per Minute value.
    startAnimation();

    return controlView;
  }

  private void updateServerName() {
    if (controlView == null) {
      return;
    }
    // Restore the chosen server name
    final TextView serverName = controlView.findViewById(R.id.server);
    serverName.setText(PersistentState.getServerName(this));
  }

  private boolean isHistoryEnabled() {
    return DnsVpnServiceState.getInstance().getTracker(this).isHistoryEnabled();
  }

  private void startAnimation() {
    if (controlView == null) {
      return;
    }

    // Start updating the live Queries Per Minute value.
    if (activityTimer == null) {
      activityTimer = new Timer();
    }
    final DnsQueryTracker tracker = DnsVpnServiceState.getInstance().getTracker(this);
    final Handler controlViewUpdateHandler = new Handler();
    final TextView qpmView = (TextView) controlView.findViewById(R.id.qpm);
    final Runnable doUpdate = new Runnable() {
      @Override
      public void run() {
        long oneMinuteAgo = SystemClock.elapsedRealtime() - 60 * 1000;
        qpmView.setText(String.format(Locale.getDefault(), "%d",
            tracker.countQueriesSince(oneMinuteAgo)));
      }
    };
    int intervalMs = 500;  // Update the value twice a second
    activityTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        controlViewUpdateHandler.post(doUpdate);
      }
    }, 0, intervalMs);
  }

  private void stopAnimation() {
    if (activityTimer != null) {
      activityTimer.cancel();
      activityTimer = null;
    }
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Sync the toggle state after onRestoreInstanceState has occurred.
    drawerToggle.syncState();
  }

  @Override
  protected void onResume() {
    super.onResume();
    startAnimation();

    // Refresh DNS status.  This is mostly to update the VPN warning message state.
    setDnsStatus(isServiceRunning());
  }

  @Override
  protected void onPause() {
    super.onPause();
    stopAnimation();
  }

  @Override
  protected void onDestroy() {
    LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
    PreferenceManager.getDefaultSharedPreferences(this).
        unregisterOnSharedPreferenceChangeListener(this);

    super.onDestroy();
  }

  private void startDnsVpnService() {
    PersistentState.setVpnEnabled(this, true);
    Intent startServiceIntent = new Intent(this, DnsVpnService.class);
    startService(startServiceIntent);
    DnsVpnServiceState.getInstance().setDnsVpnServiceStarting();
  }

  private boolean isServiceRunning() {
    DnsVpnServiceState serviceState = DnsVpnServiceState.getInstance();
    return serviceState.isDnsVpnServiceStarting() || serviceState.getDnsVpnService() != null;
  }

  private void stopDnsVpnService() {
    DnsVpnService dnsVpnService = DnsVpnServiceState.getInstance().getDnsVpnService();
    if (dnsVpnService != null) {
      dnsVpnService.signalStopService(true /* user initiated */);
    }
    PersistentState.setVpnEnabled(this, false);
  }

  private Queue<DnsTransaction> getHistory() {
    DnsVpnServiceState serviceState = DnsVpnServiceState.getInstance();
    return serviceState.getTracker(this).getRecentTransactions();
  }

  private long getNumRequests() {
    DnsVpnServiceState serviceState = DnsVpnServiceState.getInstance();
    return serviceState.getTracker(this).getNumRequests();
  }

  private void prepareAndStartDnsVpn() {
    if (hasVpnService()) {
      if (prepareVpnService()) {
        startDnsVpnService();
      }
    } else {
      FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Device does not support system-wide VPN mode.");
    }
  }

  // Returns whether the device supports the tunnel VPN service.
  private boolean hasVpnService() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  private boolean prepareVpnService() throws ActivityNotFoundException {
    Intent prepareVpnIntent = VpnService.prepare(this);
    if (prepareVpnIntent != null) {
      Log.i(LOG_TAG, "Prepare VPN with activity");
      startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
      setDnsStatus(false); // Set DNS status to off in case the user does not grant VPN permissions
      return false;
    }
    return true;
  }

  private LinkProperties getLinkProperties() {
    ConnectivityManager connectivityManager =
        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      // getActiveNetwork() requires M or later.
      return null;
    }
    Network activeNetwork = connectivityManager.getActiveNetwork();
    if (activeNetwork == null) {
      return null;
    }
    return connectivityManager.getLinkProperties(activeNetwork);
  }

  private String getSystemDnsServer() {
    if (Build.VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
      // getDnsServers requires Lollipop or later.
      return null;
    }
    LinkProperties linkProperties = getLinkProperties();
    if (linkProperties == null) {
      return null;
    }
    for (InetAddress address : linkProperties.getDnsServers()) {
      // Show the first DNS server on the list.
      return address.getHostAddress();
    }
    return null;
  }

  private enum PrivateDnsMode { NONE, OPPORTUNISTIC, STRICT };

  private PrivateDnsMode getPrivateDnsMode() {
    if (Build.VERSION.SDK_INT < VERSION_CODES.P) {
      // Private DNS was introduced in P.
      return PrivateDnsMode.NONE;
    }

    LinkProperties linkProperties = getLinkProperties();
    if (linkProperties == null) {
      return PrivateDnsMode.NONE;
    }

    if (linkProperties.getPrivateDnsServerName() != null) {
      return PrivateDnsMode.STRICT;
    }

    if (linkProperties.isPrivateDnsActive()) {
      return PrivateDnsMode.OPPORTUNISTIC;
    }

    return PrivateDnsMode.NONE;
  }

  private boolean getVpnActive() {
    ConnectivityManager connectivityManager =
        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      Network activeNetwork = connectivityManager.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }
      NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
      return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    // For pre-M versions, return true if there's any network whose name looks like a VPN.
    try {
      Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
      while (networkInterfaces.hasMoreElements()) {
        NetworkInterface networkInterface = networkInterfaces.nextElement();
        String name = networkInterface.getName();
        if (networkInterface.isUp() && name != null &&
            (name.startsWith("tun") || name.startsWith("pptp") || name.startsWith("l2tp"))) {
          return true;
        }
      }
    } catch (SocketException e) {
      FirebaseCrash.report(e);
    }
    return false;
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    if (request == REQUEST_CODE_PREPARE_VPN && result == RESULT_OK) {
      startDnsVpnService();
    }
  }

  // Sets the UI DNS status on/off.
  private void setDnsStatus(boolean on) {
    if (controlView == null) {
      return;
    }

    // Change toggle button text
    final ToggleButton switchButton = (ToggleButton) controlView.findViewById(R.id.dns_switch);
    switchButton.setChecked(on);

    // Show/hide graph
    View graph = controlView.findViewById(R.id.graph);
    graph.setVisibility(on ? View.VISIBLE : View.INVISIBLE);

    // Change indicator text
    boolean vpnActive = !on && getVpnActive();
    final TextView indicatorText = controlView.findViewById(R.id.indicator);
    int colorId = on ? R.color.accent_good : R.color.accent_bad;
    int color = ContextCompat.getColor(this, colorId);

    PrivateDnsMode privateDnsMode = PrivateDnsMode.NONE;
    int templateId;
    if (on) {
      templateId = R.string.dns_on;
    } else {
      privateDnsMode = getPrivateDnsMode();
      if (privateDnsMode == PrivateDnsMode.STRICT) {
        templateId = R.string.dns_strict;
      } else if (privateDnsMode == PrivateDnsMode.OPPORTUNISTIC) {
        templateId = R.string.dns_opportunistic;
      } else {
        templateId = R.string.dns_off;
      }
    }

    final TextView osStatusText = controlView.findViewById(R.id.os_status);
    if (vpnActive) {
      osStatusText.setText(R.string.vpn_explanation);
    } else if (privateDnsMode == PrivateDnsMode.STRICT) {
      osStatusText.setText(R.string.dns_strict_explanation);
    } else {
      osStatusText.setText("");
    }
    String template = getResources().getText(templateId).toString();
    // Html.fromHtml only supports RGB, not ARGB, so mask off the alpha byte.
    String html = String.format(template, color & 0xFFFFFF);
    Spanned text;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      text = Html.fromHtml(html, 0);
    } else {
      text = Html.fromHtml(html);
    }
    indicatorText.setText(text);

    // Change graph backdrop tint.
    ImageView backdrop = controlView.findViewById(R.id.graph_backdrop);
    backdrop.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);

    // Show/hide secure/insecure details
    View systemDetails = controlView.findViewById(R.id.system_details);
    systemDetails.setVisibility(on ? View.VISIBLE : View.GONE);
    View insecureSystemDetails = controlView.findViewById(R.id.insecure_system_details);
    insecureSystemDetails.setVisibility(on ? View.GONE : View.VISIBLE);
    if (!on) {
      String insecureServerAddress = getSystemDnsServer();
      if (insecureServerAddress == null) {
        insecureServerAddress = getResources().getText(R.string.unknown_server).toString();
      }
      TextView serverLabel = controlView.findViewById(R.id.insecure_server_value);
      serverLabel.setText(insecureServerAddress);
    }
  }

  @Override
  public void onBackPressed() {
    if (isShowingHomeView()) {
      // Back button should leave the app if we are currently looking at the home view.
      super.onBackPressed();
      return;
    }
    chooseView(R.id.frame_main);
  }

  private boolean isShowingHomeView() {
    View home = findViewById(R.id.frame_main);
    return home.getVisibility() == View.VISIBLE;
  }

  private void showSettings() {
    SettingsFragment fragment = new SettingsFragment();
    fragment.updateInstalledApps(getApplicationContext().getPackageManager());

    // Display the fragment in its designated location.
    getSupportFragmentManager().beginTransaction()
        .replace(R.id.settings, fragment)
        .commit();
  }

  private void chooseView(int id) {
    View home = findViewById(R.id.frame_main);
    View report = findViewById(R.id.frame_report);
    View settings = findViewById(R.id.settings);
    home.setVisibility(View.GONE);
    report.setVisibility(View.GONE);
    settings.setVisibility(View.GONE);

    View selected = findViewById(id);
    selected.setVisibility(View.VISIBLE);

    ActionBar actionBar = getSupportActionBar();
    switch (id) {
      case R.id.frame_main:
        actionBar.setTitle(R.string.app_name);
        break;
      case R.id.settings:
        actionBar.setTitle(R.string.settings);
        showSettings();
        break;
      case R.id.frame_report:
        actionBar.setTitle(R.string.feedback);
        break;
    }

    // Close the drawer
    DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.activity_main);
    drawerLayout.closeDrawer(Gravity.START);

    // Show an arrow on non-home screens.  See https://stackoverflow.com/questions/27742074
    if (id == R.id.frame_main) {
      drawerToggle.setDrawerIndicatorEnabled(true);
    } else {
      actionBar.setDisplayHomeAsUpEnabled(false);
      drawerToggle.setDrawerIndicatorEnabled(false);
      actionBar.setDisplayHomeAsUpEnabled(true);
    }
  }

  // Hyperlinks need to be filled in whenever the layout is instantiated.
  private static void prepareHyperlinks(Activity activity, View view) {
    // Enable hyperlinks
    int[] idsToLink = {
        R.id.credit_text_view
    };
    for (int id : idsToLink) {
      TextView textView = (TextView) view.findViewById(id);
      textView.setMovementMethod(LinkMovementMethod.getInstance());
    }
  }

  private void getIntroApproval() {
    if (WelcomePopup.shouldShow(this)) {
      WelcomePopup popup = new WelcomePopup(this);
    }
  }
}
