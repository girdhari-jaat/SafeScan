import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.safescan"
    compileSdk = 36
    buildToolsVersion = "36.0.0"



    defaultConfig {
        applicationId = "com.safescan"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    androidResources {
        // Keep only English and Urdu resources to significantly reduce app size
        localeFilters += listOf("en", "ur")
        noCompress += listOf("tflite", "pdf", "db")
    }

    signingConfigs {
        create("release") {
            val signingPropertiesFile = rootProject.file("signing.properties")
            if (signingPropertiesFile.exists()) {
                val properties = Properties().apply {
                    signingPropertiesFile.inputStream().use { load(it) }
                }
                val keystoreFileName = properties.getProperty("key.store.file") ?: "release-key.jks"
                storeFile = file(keystoreFileName)
                storePassword = properties.getProperty("key.store.password") ?: "password"
                keyAlias = properties.getProperty("key.alias") ?: "key0"
                keyPassword = properties.getProperty("key.alias.password") ?: "password"
            } else {
                // Fallback to environment variables (ideal for CI/CD like GitHub Actions) or local defaults
                val envStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
                storeFile = if (!envStoreFile.isNullOrEmpty()) file(envStoreFile) else file("release-key.jks")
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "password"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "password"
            }
        }
    }

    buildTypes {
        getByName("release") {
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile?.exists() == true) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // Enable Jetpack Compose permanently
        compose = true
    }

    packaging {
        jniLibs {
            excludes.add("lib/armeabi-v7a/**")
            excludes.add("lib/x86/**")
            excludes.add("lib/x86_64/**")
        }
        resources {
            excludes.add("META-INF/*.kotlin_module")
            excludes.add("META-INF/LICENSE*")
            excludes.add("META-INF/NOTICE*")
            excludes.add("META-INF/ASL2.0")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/NOTICE")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // CameraX
    val camerax_version = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // OpenCV dependency
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    // TFLite via Google Play Services (Reduces APK size)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // Lifecycle/ViewModel/Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Navigation Component
    val nav_version = "2.8.5"
    implementation("androidx.navigation:navigation-fragment-ktx:$nav_version")
    implementation("androidx.navigation:navigation-ui-ktx:$nav_version")

    // Compose BOM and Libraries
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-livedata")

    // Hilt
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-android-compiler:2.52")

    // Coil for asynchronous image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

apply(from = "download-tflite.gradle.kts")
