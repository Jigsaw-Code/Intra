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
package app.intra.net.doh;

import static org.junit.Assert.*;
import java.util.concurrent.Semaphore;
import org.junit.Test;

public class RaceTest {

  private class SuccessProbe extends Probe {
    SuccessProbe(Callback c) {
      callback = c;
    }

    @Override
    protected void execute() {
      succeed();
    }
  }

  private class FailProbe extends Probe {
    FailProbe(Callback c) {
      callback = c;
    }

    @Override
    protected void execute() {
      fail();
    }
  }

  @Test
  public void Success() throws Exception {
    Probe.Factory successFactory = (context, url, callback) -> new SuccessProbe(callback);
    final int N = 7;
    String[] urls = new String[N];
    Semaphore done = new Semaphore(0);
    Race.start(successFactory, null, urls, (int index) -> {
      assertTrue(index >= 0);
      assertTrue(index < N);
      done.release();
      if (done.availablePermits() > 1) {
        // Multiple success callbacks.
        fail();
      }
    });
    // Wait for listener to run.
    done.acquire();
  }

  @Test
  public void AllFail() throws Exception {
    Probe.Factory failFactory = (context, url, callback) -> new FailProbe(callback);
    final int N = 7;
    String[] urls = new String[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("server%d", i);
    }
    Semaphore done = new Semaphore(0);
    Race.start(failFactory, null, urls, (int index) -> {
      assertEquals(-1, index);
      done.release();
    });
    done.acquire();
  }

  @Test
  public void HalfFail() throws Exception {
    Probe.Factory halfFactory = (context, url, callback) -> {
      int i = Integer.parseInt(url);
      if (i % 2 == 0) {
        // Even-number servers succeed.
        return new SuccessProbe(callback);
      } else {
        return new FailProbe(callback);
      }
    };
    final int N = 7;
    String[] urls = new String[N];
    ServerConnection[] connections = new ServerConnection[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("%d", i);
    }
    Semaphore done = new Semaphore(0);
    Race.start(halfFactory, null, urls, (int index) -> {
      assertTrue(index >= 0);
      assertTrue(index < N);
      // Only the even-numbered servers succeeded.
      assertEquals(0, index % 2);
      done.release();
    });
    done.acquire();
  }

}

