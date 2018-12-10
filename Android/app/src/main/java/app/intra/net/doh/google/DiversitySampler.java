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
package app.intra.net.doh.google;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Given a collection of IP addresses (all the same family), produce a random sample that is
 * diverse, i.e. samples are spread widely across the input as measured by the Hamming distance.
 * Low Hamming distance is taken as an indication of correlated failure.  In the most significant
 * bits, this would happen if an entire subnet become unusable.  In the least significant bits, this
 * would indicate a change to a configuration that is replicated across many subnets.
 */
final class DiversitySampler {

  private class Pair implements Comparable<Pair> {

    public InetAddress address;
    // The hamming distance of this value to the nearest value in the sample.
    public int distance = Integer.MAX_VALUE;

    Pair(InetAddress address) {
      this.address = address;
    }

    @Override
    public int compareTo(Pair other) {
      return distance - other.distance;
    }
  }

  // A list of addresses, maintained in sorted order by distance.
  private List<Pair> population;
  private Random random = new Random();

  private static int hammingDistance(InetAddress a, InetAddress b) {
    byte[] aBytes = a.getAddress();
    byte[] bBytes = b.getAddress();
    int d = 0;
    for (int i = 0; i < aBytes.length; ++i) {
      d += Integer.bitCount((aBytes[i] ^ bBytes[i]) & 0xff);
    }
    return d;
  }

  DiversitySampler(List<InetAddress> values) {
    population = new ArrayList<>(values.size());
    for (int i = 0; i < values.size(); ++i) {
      population.add(new Pair(values.get(i)));
    }
    Collections.shuffle(population);
  }

  InetAddress choose() {
    // Choose at random from the upper half of the population
    int start = population.size() / 2;
    int index = start + random.nextInt(population.size() - start);
    InetAddress choice = population.get(index).address;

    // Recompute minimum distances
    for (Pair p : population) {
      p.distance = Math.min(p.distance, hammingDistance(choice, p.address));
    }

    // Put the population in order by minimum distance again.
    Collections.sort(population);

    return choice;
  }
}
