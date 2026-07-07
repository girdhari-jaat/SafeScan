# SafeScan Optimized Proguard/R8 Rules

# Preserve local data models and serialization classes
-keep class com.safescan.data.** { *; }
-keepclassmembers class com.safescan.data.** { *; }

# OpenCV JNI and classes (accessed from native C++ code)
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# Let R8 utilize the bundled consumer rules for Hilt, CameraX, ML Kit, and Jetpack Compose.
# Removing the over-broad wildcard "-keep" rules allows deep tree-shaking and significant APK size reduction.

# Keep any annotations required at runtime
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Suppress warnings from platform and third-party libraries where they are safe to ignore
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe

# LiteRT Proguard Rules
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**


