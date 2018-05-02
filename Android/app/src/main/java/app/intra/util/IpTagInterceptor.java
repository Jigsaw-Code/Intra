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

import java.io.IOException;
import java.net.InetAddress;

import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * This is an OkHttp3 Network Interceptor that marks each response with the IP address of the
 * server that sent it.
 */
public class IpTagInterceptor implements Interceptor {

  public static final String HEADER_NAME = "X-OkHttp3-Server-IP";

  @Override
  public Response intercept(Interceptor.Chain chain) throws IOException {
    Connection connection = chain.connection();
    InetAddress server = null;
    if (connection != null) {
      server = connection.route().socketAddress().getAddress();
    }
    Response response = chain.proceed(chain.request());

    if (server != null) {
      return response.newBuilder()
          .header(HEADER_NAME, server.getHostAddress())
          .build();
    }
    return response;
  }
}
