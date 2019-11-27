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
package app.intra.net.doh;

import android.content.Context;
import app.intra.net.doh.Probe.Status;
import app.intra.net.go.GoProbe;
import app.intra.sys.firebase.RemoteConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * This class performs parallel probes to all of the specified servers and calls the listener when
 * the fastest probe succeeds or all probes have failed.  Each instance can only be used once.
 */
public class Race {
  public interface Listener {
    /**
     * This method is called once, when the race has concluded.
     * @param index The index in urls of the fastest server, or -1 if all probes failed.
     */
    void onResult(int index);
  }

  /**
   * Starts a race between different servers.
   * @param context Used to read the IP addresses of the servers from storage.
   * @param urls The URLs for all the DOH servers to compare.
   * @param listener Called once on an arbitrary thread with the result of the race.
   */
  public static void start(Context context, String[] urls, Listener listener) {
    Probe.Factory factory = RemoteConfig.getUseGoDoh() ? GoProbe.factory : JavaProbe.factory;
    start(factory, context, urls, listener);
  }

  // Exposed for unit testing only.
  static void start(Probe.Factory factory, Context context, String[] urls, Listener listener) {
    List<Probe> probes = new ArrayList<>(urls.length);
    for (int i = 0; i < urls.length; ++i) {
      probes.add(factory.get(context, urls[i], new Callback(i, probes, listener)));
    }
    synchronized (probes) {
      for (Probe task : probes) {
        task.start();
      }
    }
  }

  private static class Callback implements JavaProbe.Callback {
    private final int index;
    private final List<Probe> probes;
    private final Listener listener;


    private Callback(int index, List<Probe> probes, Listener listener) {
      this.index = index;
      this.probes = probes;
      this.listener = listener;
    }

    @Override
    public void onFailure() {
      synchronized(probes) {
        if (probes.isEmpty()) {
          return;
        }
        for (Probe task : probes) {
          if (task.getStatus() != Status.FAILED) {
            return;
          }
        }
        // All probes have failed.
        listener.onResult(-1);
        probes.clear();
      }
    }

    @Override
    public void onSuccess() {
      synchronized (probes) {
        if (probes.isEmpty()) {
          return;
        }
        listener.onResult(index);
        // Minor optimization: cancel any probes that haven't issued a probe query yet.
        for (Probe probe : probes) {
          probe.interrupt();
        }
        // Mark the race as completed to prevent additional Listener callbacks.
        probes.clear();
      }
    }
  }
}
