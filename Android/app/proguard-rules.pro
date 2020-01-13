# Obfuscation makes stack traces harder to understand in release builds.
# The minor size savings isn't worth making debugging harder.
-dontobfuscate

# See https://github.com/google/guava/wiki/UsingProGuardWithGuava
-dontwarn java.lang.ClassValue
