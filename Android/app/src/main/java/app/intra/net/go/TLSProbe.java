/*
Copyright 2019 Jigsaw Operations LLC

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
package app.intra.net.go;

import android.content.Context;
import android.os.Bundle;
import app.intra.sys.Names;
import app.intra.sys.RemoteConfig;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.io.IOException;
import java.net.URL;
import javax.net.ssl.SSLHandshakeException;

// Static utility to check whether the user's connection supports standard TLS sockets.
class TLSProbe {
  enum Result {SUCCESS, TLS_FAILED, OTHER_FAILED}
  private static Result probe(String target) {
    try {
      URL url = new URL("https://" + target);
      url.openConnection().connect();
    } catch (SSLHandshakeException e) {
      if (e.getMessage().toLowerCase().contains("cert")) {
        return Result.TLS_FAILED;
      } else {
        return Result.OTHER_FAILED;
      }
    } catch (IOException e) {
      return Result.OTHER_FAILED;
    }
    return Result.SUCCESS;
  }

  static Result run(Context context, String[] targets) {
    Result worstResult = Result.SUCCESS;
    for (String target : targets) {
      Result r = probe(target);
      if (context != null) {
        Bundle b = new Bundle();
        b.putString(Names.RESULT.name(), r.name());
        b.putString(Names.SERVER.name(), target);
        FirebaseAnalytics.getInstance(context).logEvent(Names.TLS_PROBE.name(), b);
      }
      if (r == Result.TLS_FAILED ||
          (r == Result.OTHER_FAILED && worstResult == Result.SUCCESS)) {
        worstResult = r;
      }
    }
    return worstResult;
  }

  static Result run(Context context) {
    return run(context, RemoteConfig.getTlsProbeServers());
  }
}
