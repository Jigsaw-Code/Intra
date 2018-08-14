package app.intra.util;

import java.util.Collection;

/**
 * Visitor interface for DnsQueryTracker's activity data.  This allows a class to get access
 * to the query activity without making a copy, while maintaining DnsQueryTracker's
 * synchronization.
 */
public interface DnsActivityReader {
  /**
   * @param activity The SystemClock.elapsedRealtime() timestamps of each event in the recent
   *                 activity history.
   */
  void read(Collection<Long> activity);
}
