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
package app.intra.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
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
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.intra.R;
import app.intra.net.doh.Race;
import app.intra.net.doh.ServerConnection;
import app.intra.net.doh.ServerConnectionFactory;
import app.intra.net.doh.Transaction;
import app.intra.sys.LogWrapper;
import app.intra.sys.Names;
import app.intra.sys.PersistentState;
import app.intra.sys.QueryTracker;
import app.intra.sys.VpnController;
import app.intra.sys.VpnState;
import app.intra.ui.settings.ServerApprovalDialogFragment;
import app.intra.ui.settings.SettingsFragment;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

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

  private SettingsFragment settingsFragment = null;

  private BroadcastReceiver messageReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Names.RESULT.name().equals(intent.getAction())) {
            updateStatsDisplay(getNumRequests(),
                (Transaction) intent.getSerializableExtra(Names.TRANSACTION.name()));
          } else if (Names.DNS_STATUS.name().equals(intent.getAction())) {
            syncDnsStatus();
          }
        }
      };

  private void updateStatsDisplay(long numRequests, Transaction transaction) {
    showNumRequests(numRequests);
    showTransaction(transaction);
  }

  private void showTransaction(Transaction transaction) {
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
          case R.id.support:
            openUrl("https://support.getintra.org/");
            return true;
          case R.id.privacy:
            openUrl("https://getintra.org/privacy");
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

    prepareHyperlinks(this, findViewById(R.id.activity_main));

    // Autostart if necessary
    maybeAutostart();
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
    final Switch switchButton = controlView.findViewById(R.id.dns_switch);
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
    showHistory.setChecked(isHistoryEnabled());

    final QueryTracker tracker = VpnController.getInstance().getTracker(this);
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

    // The try-all-servers button is normally hidden, and only becomes visible in the failing state.
    final Button tryAllButton = controlView.findViewById(R.id.try_all_servers_button);
    // Clicking tryAllButton starts a connection race in a new thread.  This status is
    // not recorded in persistent state, so if the activity is destroyed and recreated while
    // the race is running, the race will be lost and the user will have to click the
    // button again.
    tryAllButton.setOnClickListener((View view) -> {
      tryAllButton.setEnabled(false);
      tryAllButton.setText(R.string.checking_servers);
      String[] urls = getResources().getStringArray(R.array.urls);
      FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(this);
      analytics.logEvent(Names.TRY_ALL_REQUESTED.name(), null);
      // The result needs to be posted to the UI thread before we can make UI changes.
      new Race(new ServerConnectionFactory(this), urls, (int index) -> view.post(() -> {
        if (index >= 0) {
          // By the time this callback runs, MainActivity may have been stopped.  In this situation
          // showing a DialogFragment directly causes an IllegalStateException.  Using
          // commitAllowingStateLoss() avoids this problem.
          getSupportFragmentManager().beginTransaction()
              .add(new ServerApprovalDialogFragment(), "dialog")
              .commitAllowingStateLoss();
        } else {
          Toast.makeText(this, R.string.all_servers_failed, Toast.LENGTH_LONG).show();
          analytics.logEvent(Names.TRY_ALL_FAILED.name(), null);
        }
        tryAllButton.setText(R.string.try_all_servers);
        tryAllButton.setEnabled(true);
      })).start();
    });

    // Set up click listeners for the info boxes.
    setInfoClicker(R.id.lifetime_queries_box, InfoPage.LIFETIME_QUERIES);
    setInfoClicker(R.id.qpm_box, InfoPage.RECENT_QUERIES);
    setInfoClicker(R.id.protocol_box, InfoPage.SECURE_PROTOCOL);
    setInfoClicker(R.id.server_box, InfoPage.SECURE_SERVER);
    setInfoClicker(R.id.default_protocol_box, InfoPage.DEFAULT_PROTOCOL);
    setInfoClicker(R.id.default_server_box, InfoPage.DEFAULT_SERVER);

    // Restore number of requests.
    long numRequests = tracker.getNumRequests();
    showNumRequests(numRequests);

    updateServerName();
    syncDnsStatus();

    // Start updating the live Queries Per Minute value.
    startAnimation();

    return controlView;
  }

  private void setInfoClicker(final @IdRes int id, final InfoPage page) {
    controlView.findViewById(id).setOnClickListener(view -> showInfo(page));
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
    return VpnController.getInstance().getTracker(this).isHistoryEnabled();
  }

  private void startAnimation() {
    if (controlView == null) {
      return;
    }

    // Start updating the live Queries Per Minute value.
    if (activityTimer == null) {
      activityTimer = new Timer();
    }
    final QueryTracker tracker = VpnController.getInstance().getTracker(this);
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
    syncDnsStatus();
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

  private void maybeAutostart() {
    VpnController controller = VpnController.getInstance();
    VpnState state = controller.getState(this);
    if (state.activationRequested && !state.on) {
      LogWrapper.log(Log.INFO, LOG_TAG, "Autostart enabled");
      prepareAndStartDnsVpn();
    }
  }

  private void startDnsVpnService() {
    VpnController.getInstance().start(this);
  }

  private void stopDnsVpnService() {
    VpnController.getInstance().stop(this);
  }

  private Queue<Transaction> getHistory() {
    VpnController controller = VpnController.getInstance();
    return controller.getTracker(this).getRecentTransactions();
  }

  private long getNumRequests() {
    VpnController controller = VpnController.getInstance();
    return controller.getTracker(this).getNumRequests();
  }

  private void prepareAndStartDnsVpn() {
    if (hasVpnService()) {
      if (prepareVpnService()) {
        startDnsVpnService();
      }
    } else {
      LogWrapper.log(Log.ERROR, LOG_TAG, "Device does not support system-wide VPN mode.");
    }
  }

  // Returns whether the device supports the tunnel VPN service.
  private boolean hasVpnService() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
  }

  private boolean prepareVpnService() throws ActivityNotFoundException {
    Intent prepareVpnIntent = null;
    try {
       prepareVpnIntent = VpnService.prepare(this);
    } catch (NullPointerException e) {
      // This exception is not mentioned in the documentation, but it has been encountered by Intra
      // users and also by other developers, e.g. https://stackoverflow.com/questions/45470113.
      LogWrapper.logException(e);
      return false;
    }
    if (prepareVpnIntent != null) {
      Log.i(LOG_TAG, "Prepare VPN with activity");
      startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
      syncDnsStatus();  // Set DNS status to off in case the user does not grant VPN permissions
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
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      String privateDnsServerName = linkProperties.getPrivateDnsServerName();
      if (privateDnsServerName != null) {
        return privateDnsServerName;
      }
    }
    for (InetAddress address : linkProperties.getDnsServers()) {
      // Show the first DNS server on the list.
      return address.getHostAddress();
    }
    return null;
  }

  private enum PrivateDnsMode {
    NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
    UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
    STRICT  // The setting is "Strict".
  };

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
      return PrivateDnsMode.UPGRADED;
    }

    return PrivateDnsMode.NONE;
  }

  private boolean isAnotherVpnActive() {
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      ConnectivityManager connectivityManager =
          (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      Network activeNetwork = connectivityManager.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }
      NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
      if (capabilities == null) {
        // It's not clear when this can happen, but it has occurred for at least one user.
        return false;
      }
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
      LogWrapper.logException(e);
    }
    return false;
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == REQUEST_CODE_PREPARE_VPN) {
      if (result == RESULT_OK) {
        startDnsVpnService();
      } else {
        stopDnsVpnService();
      }
    }
  }

  // Sets the UI DNS status on/off.
  private void syncDnsStatus() {
    if (controlView == null) {
      return;
    }

    VpnState status = VpnController.getInstance().getState(this);

    // Change switch-button state
    final Switch switchButton = controlView.findViewById(R.id.dns_switch);
    switchButton.setChecked(status.activationRequested);

    // Change indicator text
    final TextView indicatorText = controlView.findViewById(R.id.indicator);
    indicatorText.setText(status.activationRequested ? R.string.indicator_on : R.string.indicator_off);

    // Hide server change button by default
    final Button changeServerButton = controlView.findViewById(R.id.try_all_servers_button);
    changeServerButton.setVisibility(View.INVISIBLE);

    // Change status and explanation text
    final int statusId;
    final int explanationId;
    PrivateDnsMode privateDnsMode = PrivateDnsMode.NONE;
    if (status.activationRequested) {
      if (status.connectionState == null) {
        statusId = R.string.status_waiting;
        explanationId = R.string.explanation_offline;
      } else if (status.connectionState == ServerConnection.State.NEW) {
        statusId = R.string.status_starting;
        explanationId = R.string.explanation_starting;
      } else if (status.connectionState == ServerConnection.State.WORKING) {
        statusId = R.string.status_protected;
        explanationId = R.string.explanation_protected;
      } else {
        // status.connectionState == ServerConnection.State.FAILING
        statusId = R.string.status_failing;
        explanationId = R.string.explanation_failing;
        changeServerButton.setVisibility(View.VISIBLE);
      }
    } else if (isAnotherVpnActive()) {
      statusId = R.string.status_exposed;
      explanationId = R.string.explanation_vpn;
    } else {
      privateDnsMode = getPrivateDnsMode();
      if (privateDnsMode == PrivateDnsMode.STRICT) {
        statusId = R.string.status_strict;
        explanationId = R.string.explanation_strict;
      } else if (privateDnsMode == PrivateDnsMode.UPGRADED) {
        statusId = R.string.status_upgraded;
        explanationId = R.string.explanation_upgraded;
      } else {
        statusId = R.string.status_exposed;
        explanationId = R.string.explanation_exposed;
      }
    }

    final int colorId;
    if (status.on) {
      colorId = status.connectionState != ServerConnection.State.FAILING ? R.color.accent_good :
          R.color.accent_bad;
    } else if (privateDnsMode == PrivateDnsMode.STRICT) {
      // If the VPN is off but we're in strict mode, show the status in white.  This isn't a bad
      // state, but Intra isn't helping.
      colorId = R.color.indicator;
    } else {
      colorId = R.color.accent_bad;
    }

    final TextView statusText = controlView.findViewById(R.id.status);
    final TextView explanationText = controlView.findViewById(R.id.explanation);
    final int color = ContextCompat.getColor(this, colorId);
    statusText.setTextColor(color);
    statusText.setText(statusId);
    explanationText.setText(explanationId);

    // Change graph foreground and background tint.
    HistoryGraph graph = controlView.findViewById(R.id.graph);
    graph.setColor(color);
    ImageView backdrop = controlView.findViewById(R.id.graph_backdrop);
    backdrop.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);

    // Show/hide secure/insecure details
    View systemDetails = controlView.findViewById(R.id.system_details);
    systemDetails.setVisibility(status.on ? View.VISIBLE : View.GONE);
    View insecureSystemDetails = controlView.findViewById(R.id.insecure_system_details);
    insecureSystemDetails.setVisibility(status.on ? View.GONE : View.VISIBLE);
    if (!status.on) {
      TextView defaultProtocol = controlView.findViewById(R.id.default_protocol);
      boolean tls = privateDnsMode != PrivateDnsMode.NONE;
      defaultProtocol.setText(tls ? R.string.tls_transport : R.string.insecure_transport);

      ImageView defaultProtocolIcon = controlView.findViewById(R.id.default_protocol_icon);
      defaultProtocolIcon.setImageResource(
          tls ? R.drawable.ic_lock_black_24dp : R.drawable.ic_lock_open_black_24dp);
      defaultProtocolIcon.setColorFilter(color, Mode.SRC_ATOP);

      InfoPage protocolTarget = InfoPage.DEFAULT_PROTOCOL;
      if (privateDnsMode == PrivateDnsMode.STRICT) {
        protocolTarget = InfoPage.STRICT_MODE_PROTOCOL;
      } else if (tls) {
        protocolTarget = InfoPage.UPGRADED_PROTOCOL;
      }
      setInfoClicker(R.id.default_protocol_box, protocolTarget);

      String systemDnsServer = getSystemDnsServer();
      if (systemDnsServer == null) {
        systemDnsServer = getResources().getText(R.string.unknown_server).toString();
      }
      TextView serverLabel = controlView.findViewById(R.id.default_server_value);
      serverLabel.setText(systemDnsServer);

      ImageView defaultServerIcon = controlView.findViewById(R.id.default_server_icon);
      defaultServerIcon.setColorFilter(color, Mode.SRC_ATOP);

      setInfoClicker(R.id.default_server_box, privateDnsMode == PrivateDnsMode.STRICT ?
          InfoPage.STRICT_MODE_SERVER : InfoPage.DEFAULT_SERVER);
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
    settingsFragment = new SettingsFragment();
    settingsFragment.collectInstalledApps(getApplicationContext().getPackageManager());

    // Display the fragment in its designated location.
    getSupportFragmentManager().beginTransaction()
        .replace(R.id.settings, settingsFragment)
        .commitNow();
  }

  private View chooseView(int id) {
    View home = findViewById(R.id.frame_main);
    View settings = findViewById(R.id.settings);
    View info = findViewById(R.id.info_page);
    home.setVisibility(View.GONE);
    settings.setVisibility(View.GONE);
    info.setVisibility(View.GONE);

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
    }

    // Close the drawer
    DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.activity_main);
    drawerLayout.closeDrawer(GravityCompat.START);

    // Show an arrow on non-home screens.  See https://stackoverflow.com/questions/27742074
    if (id == R.id.frame_main) {
      drawerToggle.setDrawerIndicatorEnabled(true);
    } else {
      actionBar.setDisplayHomeAsUpEnabled(false);
      drawerToggle.setDrawerIndicatorEnabled(false);
      actionBar.setDisplayHomeAsUpEnabled(true);
    }
    return selected;
  }

  private enum InfoPage {
    LIFETIME_QUERIES(true,
        R.string.num_requests,
        R.drawable.ic_dns,
        R.string.num_requests_headline,
        R.string.num_requests_body),
    RECENT_QUERIES(true,
        R.string.queries_per_minute,
        R.drawable.ic_trending_up_black_24dp,
        R.string.queries_per_minute_headline,
        R.string.queries_per_minute_body),
    SECURE_PROTOCOL(true,
        R.string.transport_label,
        R.drawable.ic_lock_black_24dp,
        R.string.transport_headline,
        R.string.transport_body),
    SECURE_SERVER(true,
        R.string.server_label,
        R.drawable.ic_server,
        R.string.server_headline,
        R.string.server_body),
    DEFAULT_PROTOCOL(false,
        R.string.default_transport_label,
        R.drawable.ic_lock_open_black_24dp,
        R.string.insecure_transport_headline,
        R.string.insecure_transport_body),
    // There are two DNS-over-TLS cases: upgraded (i.e. opportunistic) and strict mode.
    // These are only shown on Android P, to users who are using DNS-over-TLS rare.  Rather than
    // writing custom explanations for these rare cases, we just reuse the status indicator text.
    UPGRADED_PROTOCOL(false,
        R.string.default_transport_label,
        R.drawable.ic_lock_black_24dp,
        R.string.status_upgraded,
        R.string.explanation_upgraded),
    STRICT_MODE_PROTOCOL(true,
        R.string.default_transport_label,
        R.drawable.ic_lock_black_24dp,
        R.string.status_strict,
        R.string.explanation_strict),
    DEFAULT_SERVER(false,
        R.string.default_server_label,
        R.drawable.ic_server,
        R.string.insecure_server_headline,
        R.string.insecure_server_body),
    STRICT_MODE_SERVER(true,
        R.string.default_server_label,
        R.drawable.ic_server,
        R.string.strict_mode_server_headline,
        R.string.strict_mode_server_body);

    final boolean good;
    final @StringRes int title;
    final @DrawableRes int drawable;
    final @StringRes int headline;
    final @StringRes int body;
    InfoPage(boolean good, int title, int drawable, int headline, int body) {
      this.title = title;
      this.good = good;
      this.drawable = drawable;
      this.headline = headline;
      this.body = body;
    }
  }

  private void showInfo(InfoPage page) {
    View view = chooseView(R.id.info_page);

    ActionBar actionBar = getSupportActionBar();
    actionBar.setTitle(page.title);

    ImageView image = view.findViewById(R.id.info_image);
    image.setImageResource(page.drawable);
    int color = ContextCompat.getColor(this, page.good ? R.color.accent_good : R.color.accent_bad);
    ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(color));

    TextView headline = view.findViewById(R.id.info_headline);
    headline.setText(page.headline);

    TextView body = view.findViewById(R.id.info_body);
    body.setText(page.body);
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
    if (IntroDialog.shouldShow(this)) {
      new IntroDialog().show(getSupportFragmentManager(), "intro");
    }
  }
}
