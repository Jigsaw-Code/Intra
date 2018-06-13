package app.intra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;
import com.google.firebase.crash.FirebaseCrash;

/**
 * Broadcast receiver that runs on boot.
 */

public class AutoStarter extends BroadcastReceiver {

  private static String LOG_TAG = "AutoStarter";

  @Override
  public void onReceive(Context context, Intent intent) {
    FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Boot event");
    if (DnsVpnServiceState.getInstance().isDnsVpnServiceStarting()) {
      // The service is already started or starting, so there's no work to do.
      FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Already running");
      return;
    }
    if (Preferences.getVpnEnabled(context)) {
      FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Autostart enabled");
      if (VpnService.prepare(context) != null) {
        // prepare() returns a non-null intent if VPN permission has not been granted.
        FirebaseCrash.logcat(Log.WARN, LOG_TAG, "VPN permission not granted");
        return;
      }
      Intent startServiceIntent = new Intent(context, DnsVpnService.class);
      context.startService(startServiceIntent);
      DnsVpnServiceState.getInstance().setDnsVpnServiceStarting();
    }
  }
}
