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

import app.intra.net.doh.Probe.Status;
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

  private final List<Probe> probes;
  private final Listener listener;

  /**
   * Creates a race between different servers.  To run the race, call start().
   * @param factory The factory will be called once for each server.
   * @param urls The URLs for all the DOH servers to compare.
   * @param listener Called once on an arbitrary thread with the result of the race.
   */
  public Race(ServerConnectionFactory factory, String[] urls, Listener listener) {
    probes = new ArrayList<>(urls.length);
    for (int i = 0; i < urls.length; ++i) {
      probes.add(new Probe(factory, urls[i], new Callback(i)));
    }
    this.listener = listener;
  }

  public synchronized void start() {
    for (Probe task : probes) {
      task.start();
    }
  }

  private class Callback implements Probe.Callback {
    private final int index;

    private Callback(int index) {
      this.index = index;
    }

    @Override
    public void onFailure() {
      synchronized(Race.this) {
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
      synchronized (Race.this) {
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
