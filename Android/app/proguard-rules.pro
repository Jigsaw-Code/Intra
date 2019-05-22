# Obfuscation makes stack traces harder to understand in release builds.
# The minor size savings isn't worth making debugging harder.
-dontobfuscate

# Our tun2socks library calls back into this class when printing log
# messages.  Proguard can't see that external reference to the log
# method, so we need to mark it for preservation explicitly.
-keep public class org.outline.tun2socks.Tun2SocksJni { *; }
