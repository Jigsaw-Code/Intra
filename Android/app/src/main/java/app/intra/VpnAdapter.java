package app.intra;

import androidx.annotation.Nullable;

public abstract class VpnAdapter extends Thread {

  VpnAdapter(String name) {
    super(name);
  }

  /**
   * Initiate an asynchronous shutdown.
   */
  abstract void close();
}
