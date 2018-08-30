package app.intra;

public final class DnsVpnState {
  // Whether the user has requested that the VPN be active.  This is persistent state, sync'd to
  // disk.
  public final boolean activationRequested;

  // Whether the VPN is running.  When this is true a key icon is showing in the status bar.
  public final boolean on;

  // Whether we have a connection to a DOH server, and if so, whether the connection is ready or
  // has recently been failing.
  public final ServerConnection.State connectionState;

  public DnsVpnState(boolean activationRequested, boolean on, ServerConnection.State connectionState) {
    this.activationRequested = activationRequested;
    this.on = on;
    this.connectionState = connectionState;
  }
}
