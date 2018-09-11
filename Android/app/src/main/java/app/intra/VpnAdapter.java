package app.intra;

/**
 * Abstract class representing a VPN Adapter, for use by DnsVpnService.  For our purposes, a
 * VpnAdapter is just a thread that can safely be stopped at any time.
 */
public abstract class VpnAdapter extends Thread {
  protected static final int VPN_INTERFACE_MTU = 32767;
  protected static final int DNS_DEFAULT_PORT = 53;

  public VpnAdapter(String name) {
    super(name);
  }

  /**
   * Perform a safe shutdown.
   */
  public abstract void close();
}
