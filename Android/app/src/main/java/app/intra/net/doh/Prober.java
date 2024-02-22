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

/**
 * A prober can perform asynchronous checks to determine whether a DOH server is working.
 */
public abstract class Prober {
  public interface Callback {
    void onCompleted(boolean succeeded);
  }

  /**
   * Called to execute the probe on a new thread.
   * @param url The DOH server URL to probe.
   * @param callback How to report the probe results
   */
  public abstract void probe(String url, Callback callback);
}
