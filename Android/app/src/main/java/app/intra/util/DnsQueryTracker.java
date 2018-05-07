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
package app.intra.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedList;
import java.util.Queue;

import static android.content.Context.MODE_PRIVATE;

/**
 * A class for tracking DNS transactions.  This class counts the number of successful transactions,
 * records the last minute of query timestamps, and optionally maintains a history of recent
 * transactions.
 * Thread-safe.
 */
public class DnsQueryTracker {

  private static final String NUM_REQUESTS = "numRequests";

  private static final int HISTORY_SIZE = 100;
  private static final int ACTIVITY_MEMORY_MS = 60 * 1000;  // One minute

  private long numRequests = 0;
  private Queue<DnsTransaction> recentTransactions = new LinkedList<>();
  private Queue<Long> recentActivity = new LinkedList<>();
  private boolean historyEnabled = false;

  private Context context;

  public DnsQueryTracker(Context context) {
    this.context = context;
    sync();
  }

  public long getNumRequests() {
    synchronized (this) {
      return numRequests;
    }
  }

  public Queue<DnsTransaction> getRecentTransactions() {
    synchronized (this) {
      return new LinkedList<>(recentTransactions);
    }
  }

  public Queue<Long> getRecentActivity() {
    synchronized (this) {
      return new LinkedList<>(recentActivity);
    }
  }

  public int countQueriesSince(long startTime) {
    synchronized (this) {
      // Linearly scan recent activity.  Due to the small scale (N ~ 300), a more efficient algorithm
      // does not appear to be necessary.
      int queries = recentActivity.size();
      for (long time : recentActivity) {
        if (time < startTime) {
          --queries;
        } else {
          break;
        }
      }
      return queries;
    }
  }

  public void setHistoryEnabled(boolean enabled) {
    synchronized (this) {
      historyEnabled = enabled;
      if (!enabled) {
        recentTransactions.clear();
      }
    }
  }

  public boolean isHistoryEnabled() {
    synchronized (this) {
      return historyEnabled;
    }
  }

  public void recordTransaction(DnsTransaction transaction) {
    synchronized (this) {
      // Increment request counter on each successful resolution
      if (transaction.status == DnsTransaction.Status.COMPLETE) {
        ++numRequests;

        if (numRequests % HISTORY_SIZE == 0) {
          // Avoid losing too many requests in case of an unclean shutdown, but also avoid
          // excessive disk I/O from syncing the counter to disk after every request.
          sync();
        }
      }

      recentActivity.add(transaction.queryTime);
      while (recentActivity.peek() + ACTIVITY_MEMORY_MS < transaction.queryTime) {
        recentActivity.remove();
      }

      if (historyEnabled) {
        recentTransactions.add(transaction);
        if (recentTransactions.size() > HISTORY_SIZE) {
          recentTransactions.remove();
        }
      }
    }
  }

  public void sync() {
    synchronized (this) {
      // Restore number of requests from storage, or 0 if it isn't defined yet.
      SharedPreferences settings =
          context.getSharedPreferences(DnsQueryTracker.class.getSimpleName(), MODE_PRIVATE);
      long storedNumRequests = settings.getLong(NUM_REQUESTS, 0);
      if (storedNumRequests >= numRequests) {
        numRequests = storedNumRequests;
      } else {
        // Save the request counter.
        SharedPreferences.Editor editor = settings.edit();
        editor.putLong(NUM_REQUESTS, numRequests);
        editor.apply();
      }
    }
  }

}
