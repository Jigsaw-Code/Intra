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

  private class SuccessProber extends Prober {
    @Override
    public void probe(String url, Callback callback) {
      new Thread(() -> callback.onCompleted(true)).start();

    }
  }

  @Test
  public void Success() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    Semaphore done = new Semaphore(0);
    Race.start(new SuccessProber(), urls, (int index) -> {
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

  private class FailProber extends Prober {
    @Override
    public void probe(String url, Callback callback) {
      new Thread(() -> callback.onCompleted(false)).start();
    }
  }

  @Test
  public void AllFail() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("server%d", i);
    }
    Semaphore done = new Semaphore(0);
    Race.start(new FailProber(), urls, (int index) -> {
      assertEquals(-1, index);
      done.release();
    });
    done.acquire();
  }

  private class HalfProber extends Prober {
    @Override
    public void probe(String url, Callback callback) {
      int i = Integer.parseInt(url);
      // Even-number servers succeed.
      boolean succeed = (i % 2 == 0);
      new Thread(() -> callback.onCompleted(succeed)).start();
    }
  }

  @Test
  public void HalfFail() throws Exception {
    final int N = 7;
    String[] urls = new String[N];
    for (int i = 0; i < N; ++i) {
      urls[i] = String.format("%d", i);
    }
    Semaphore done = new Semaphore(0);
    Race.start(new HalfProber(), urls, (int index) -> {
      assertTrue(index >= 0);
      assertTrue(index < N);
      // Only the even-numbered servers succeeded.
      assertEquals(0, index % 2);
      done.release();
    });
    done.acquire();
  }

}

