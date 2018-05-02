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

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * A callback object to listen for a DNS response.  The caller should create one such object for
 * each DNS request.  Responses will run on a reader thread owned by OkHttp.
 */
class TestDnsCallback implements Callback {
    public Response response = null;
    public Semaphore semaphore = new Semaphore(1);

    public TestDnsCallback() {
        try {
            semaphore.acquire();
        } catch(Exception e) {
            fail();
        }
    }

    @Override
    public void onFailure(Call call, IOException e) {
        semaphore.release();
    }

    @Override
    public void onResponse(Call call, Response r) {
        response = r;
        semaphore.release();
    }
}
