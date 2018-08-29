package app.intra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;
import com.google.firebase.crash.FirebaseCrash;

/**
 * Broadcast receiver that runs on boot, and also when the app is restarted due to an update.
 */
public class AutoStarter extends BroadcastReceiver {

  private static String LOG_TAG = "AutoStarter";

  @Override
  public void onReceive(Context context, Intent intent) {
    FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Boot event");
    DnsVpnController controller = DnsVpnController.getInstance();
    DnsVpnState state = controller.getState(context);
    if (state.requested && !state.on) {
      FirebaseCrash.logcat(Log.DEBUG, LOG_TAG, "Autostart enabled");
      if (VpnService.prepare(context) != null) {
        // prepare() returns a non-null intent if VPN permission has not been granted.
        FirebaseCrash.logcat(Log.WARN, LOG_TAG, "VPN permission not granted");
        return;
      }
      controller.start(context);
    }
  }
}
