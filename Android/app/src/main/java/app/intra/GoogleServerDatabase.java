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
package app.intra;

import android.content.Context;
import android.content.res.AssetManager;

import android.util.Log;

import androidx.annotation.WorkerThread;
import app.intra.util.DiversitySampler;
import app.intra.util.DualStackResult;

import com.google.firebase.crash.FirebaseCrash;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import okhttp3.Dns;

/**
 * This is an "almost-fake" implementation of DNS that ignores the name and returns a fixed pool
 * of addresses,  Since the corresponding client is only used to access a single service
 * (dns.google.com), this allows us to direct its connections to an IP address that we know to be
 * hosting that service.
 */
public class GoogleServerDatabase implements Dns {

  private static final String LOG_TAG = "GoogleServerDatabase";

  // Max number of these IP addresses, selected at random, to attempt before declaring failure.
  private static final int MAX_ATTEMPTS = 10;

  /**
   * "Good" names to try first, in this order.  Better to use a name than a hardcoded IP, and
   * better to use the right name than the wrong name.
   */
  private static final String[] NAMES = {"dns.google.com", "www.google.com"};

  private LinkedList<InetAddress> tryOrder;
  private Context context;

  public GoogleServerDatabase(Context context, AssetManager assets) {
    this.context = context;
    // Try the preferred servers, then dns.google.com, then www.google.com.
    DualStackResult preferredServers = getPreferred();
    tryOrder = preferredServers.getInterleaved();
    for (String name : NAMES) {
      addAddress(name);
    }

    // If none of the good choices work, try some random hardcoded IP addresses.  We set
    // recurse=true so that if a connection works, we query it for an IP address of dns.google.com,
    // and try to switch.  This should help with geo-locality.
    List<InetAddress> secondTier6 = readIPs(assets, "ipv6.txt");
    List<InetAddress> secondTier4 = readIPs(assets, "ipv4.txt");
    DiversitySampler sampler6 = new DiversitySampler(secondTier6);
    DiversitySampler sampler4 = new DiversitySampler(secondTier4);
    for (int i = 0; i < MAX_ATTEMPTS; ++i) {
      // Alternately try IPv6 and IPv4 addresses
      if (i < secondTier6.size()) {
        tryOrder.add(sampler6.choose());
      }
      if (i < secondTier4.size()) {
        tryOrder.add(sampler4.choose());
      }
    }
  }

  private List<InetAddress> readIPs(AssetManager assets, String filename) {
    List<InetAddress> addresses = new ArrayList<InetAddress>();
    try {
      InputStream input = assets.open(filename);
      BufferedReader reader = new BufferedReader(new InputStreamReader(input));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        addresses.add(InetAddress.getByName(line));
      }
      reader.close();
      input.close();
    } finally {
      return addresses;
    }
  }

  @WorkerThread
  private void addAddress(String address) {
    try {
      tryOrder.addAll(Arrays.asList(InetAddress.getAllByName(address)));
    } catch (UnknownHostException e) {
      // It's expected that some addresses might fail to resolve.
    }
  }

  @Override
  public List<InetAddress> lookup(String hostname) {
    return tryOrder;
  }

  /**
   * @return The last known working server, or "" if there is none.
   */
  private DualStackResult getPreferred() {
    Set<String> v4set = PersistentState.getExtraGoogleV4Servers(context);
    Set<String> v6set = PersistentState.getExtraGoogleV6Servers(context);
    String[] dummy = new String[0];
    return new DualStackResult(v4set.toArray(dummy), v6set.toArray(dummy));
  }

  /**
   * Record servers that we will prefer if they are accessible.  In the future, try them first.
   *
   * @param servers IP addresses to try first.
   */
  public void setPreferred(DualStackResult servers) {
    // Prepend these servers to the list, preserving the interleaved order.
    tryOrder.addAll(0, servers.getInterleaved());

    // Record them to disk so they can be prepended at the next startup.
    PersistentState.setExtraGoogleV4Servers(context, servers.getV4());
    PersistentState.setExtraGoogleV6Servers(context, servers.getV6());
  }
}
