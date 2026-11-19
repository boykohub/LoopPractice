# JNI: native method names must match the C++ functions.
-keep class com.boykohub.looppractice.NativeStretch { *; }
-keepclasseswithmembernames class * { native <methods>; }
