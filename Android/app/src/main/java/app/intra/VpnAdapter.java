package app.intra;

/**
 * Abstract class representing a VPN Adapter, for use by DnsVpnService.  For our purposes, a
 * VpnAdapter is just a thread that can safely be stopped at any time.
 */
public abstract class VpnAdapter extends Thread {

  public VpnAdapter(String name) {
    super(name);
  }

  /**
   * Perform a safe shutdown.
   */
  public abstract void close();
}
