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
package app.intra.sys.firebase;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

/**
 * Utility class for initializing Firebase Remote Config.  Remote Configuration allows us to conduct
 * A/B tests of experimental functionality, and to enable or disable features without having to
 * release a new version of the app.
 */
public class RemoteConfig {
  public static Task<Boolean> update() {
    try {
      FirebaseRemoteConfig config = FirebaseRemoteConfig.getInstance();
      return config.fetchAndActivate();
    } catch (IllegalStateException e) {
      LogWrapper.logException(e);
      return Tasks.forResult(false);
    }
  }

}
