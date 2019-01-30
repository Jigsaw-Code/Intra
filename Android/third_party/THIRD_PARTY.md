sockslib/ contains a [point-in-time-snapshot](https://github.com/fengyouchao/sockslib/tree/9ad0d94914468cde403932dbd6a58b6449faac56)
of https://github.com/fengyouchao/sockslib, with the following changes:
 * Tests and examples have been removed, as these are not needed to build Intra.
 * Any files related to MongoDB or JDBC have been removed, as these are not relevant to Intra.
 * Small fixes for https://github.com/fengyouchao/sockslib/issues/14 and related issues in
   StreamPipe.java.
 * Workaround for a NullPointerException in Android's DatagramSocket.receive().
