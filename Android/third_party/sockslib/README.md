# SocksLib [![Build Status](https://travis-ci.org/fengyouchao/sockslib.svg?branch=master)](https://travis-ci.org/fengyouchao/sockslib) [![Coverage Status](https://coveralls.io/repos/fengyouchao/sockslib/badge.svg?branch=master&service=github)](https://coveralls.io/github/fengyouchao/sockslib?branch=master) [![Coverity Scan Build Status](https://scan.coverity.com/projects/6898/badge.svg)](https://scan.coverity.com/projects/fengyouchao-sockslib)

**SocksLib** is a Java library for **SOCKS5** protocol.

See [Java API Documentation](http://fengyouchao.github.io/projects/fucksocks/apidocs/index.html)

See [Wiki Page](http://git.oschina.net/fyc/fucksocks/wikis/home) (Chinese)

If you are looking for a SOCKS5 server instead of a SOKCS5 library, I hope [Esocks](https://github.com/fengyouchao/esocks) can help you.




#### References
 
* [SOCKS Protocol Version 5](http://www.ietf.org/rfc/rfc1928.txt)
* [Username/Password Authentication for SOCKS V5](http://www.ietf.org/rfc/rfc1929.txt)

## Features

### Client

* TCP proxy
* UDP proxy
* Bind
* Anonymous authentication
* USERNAME/PASSWORD authentication
* Proxy chain

### Server

* TCP proxy
* UDP proxy
* Bind
* Anonymous authentication
* USERNAME/PASSWORD authentication
* Proxy chain
* Black or white IP lists for clients

## Quick start

#### Environment

* JDK 8+

#### Dependencies

You should put following libraries in your project's *CLASSPATH*:

* [log4j-slf4j-impl-2.4.jar](https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-slf4j-impl/2.4/log4j-slf4j-impl-2.4.jar)
* [slf4j-api-1.7.12.jar](https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.12/slf4j-api-1.7.12.jar)
* [log4j-api-2.4.jar](https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.4/log4j-api-2.4.jar)
* [log4j-core-2.4.jar](https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.4/log4j-core-2.4.jar)
* [guava-18.0.jar](https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar)

### SOCKS5 Client

#### CONNECT

```java
    SocksProxy proxy = new Socks5(new InetSocketAddress("localhost",1080));
    Socket socket = new SocksSocket(proxy, new InetSocketAddress("whois.internic.net",43));
```

Connect SOCKS5 server using SSL connection

```java
    SSLConfigurationBuilder builder = SSLConfigurationBuilder.newBuilder();
    builder.setTrustKeyStorePath("client-trust-keystore.jks");
    builder.setTrustKeyStorePassword("123456");
    SocksProxy proxy = new SSLSocks5(new InetSocketAddress("localhost", 1081), builder.build());
    Socket socket = new SocksSocket(proxy, new InetSocketAddress("whois.internic.net",43));
```

#### BIND

```java
    SocksServerSocket serverSocket = new SocksServerSocket(proxy, inetAddress,8080);
    InetAddress bindAddress = serverSocket.getBindAddress();
    int bindPort  = serverSocket.getBindPort();
    Socket socket = serverSocket.accept();
```

#### UDP ASSOCIATE

```java
     DatagramSocket socket = new Socks5DatagramSocket(proxy);
```

### SOCKS5 Server

```java
     SocksProxyServer proxyServer = SocksServerBuilder.buildAnonymousSocks5Server(); 
     proxyServer.start();// Creat a SOCKS5 server bind at port 1080
```

SSL socks server

```java
    SSLConfigurationBuilder builder = SSLConfigurationBuilder.newBuilder();
    builder.setKeyStorePath("server-keystore.jks");
    builder.setKeyStorePassword("123456");
    builder.setClientAuth(false);
    socksProxyServer = SocksServerBuilder.buildAnonymousSSLSocks5Server(1081, builder.build());
    socksProxyServer.start();
```
