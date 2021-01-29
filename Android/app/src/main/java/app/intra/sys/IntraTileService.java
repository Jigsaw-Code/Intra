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
package app.intra.sys;

import android.content.ComponentName;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

import app.intra.ui.MainActivity;

@RequiresApi(api = Build.VERSION_CODES.N)
public class IntraTileService extends TileService {

    @Override
    public void onStartListening() {
        VpnState vpnState = VpnController.getInstance().getState(this);

        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        if (vpnState.activationRequested) {
            tile.setState(Tile.STATE_ACTIVE);
        } else {
            tile.setState(Tile.STATE_INACTIVE);
        }

        tile.updateTile();
    }

    @Override
    public void onClick() {
        VpnState vpnState = VpnController.getInstance().getState(this);

        if (vpnState.activationRequested) {
            VpnController.getInstance().stop(this);
        } else {
            if (VpnService.prepare(this) == null) {
                // Start VPN service when VPN permission has been granted.
                VpnController.getInstance().start(this);
            } else {
                // Open Main activity when VPN permission has not been granted.
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(intent);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Update tile state on boot.
        TileService.requestListeningState(this,
                new ComponentName(this, IntraTileService.class));
        return super.onBind(intent);
    }
}
