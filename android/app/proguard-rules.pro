# ML Kit Text Recognition and Barcode Scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# CameraX
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.core.**
-dontwarn androidx.camera.camera2.**

# Hilt & Dagger
-keep class dagger.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.EntryPoint { *; }
-keep @dagger.hilt.EntryPoint class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Jetpack Compose
-keep class androidx.compose.runtime.ParcelableSnapshotMutableState { *; }
-dontwarn androidx.compose.**

# DataStore Preferences
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-dontwarn androidx.datastore.**

# Coroutines & Serialization
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
