# ML Kit
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }

# Hilt / Dagger
-keep class dagger.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
