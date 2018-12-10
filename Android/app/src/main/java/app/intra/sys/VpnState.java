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
package app.intra.sys;

import app.intra.net.doh.ServerConnection;

public final class VpnState {
  // Whether the user has requested that the VPN be active.  This is persistent state, sync'd to
  // disk.
  public final boolean activationRequested;

  // Whether the VPN is running.  When this is true a key icon is showing in the status bar.
  public final boolean on;

  // Whether we have a connection to a DOH server, and if so, whether the connection is ready or
  // has recently been failing.
  public final ServerConnection.State connectionState;

  VpnState(boolean activationRequested, boolean on, ServerConnection.State connectionState) {
    this.activationRequested = activationRequested;
    this.on = on;
    this.connectionState = connectionState;
  }
}
