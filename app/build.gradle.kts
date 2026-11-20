import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.sameerasw.airsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sameerasw.airsync"
        minSdk = 30
        targetSdk = 37
        versionCode = 39
        versionName = "4.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MIN_MAC_APP_VERSION", "\"4.0.0\"")

        val abiProp = (project.findProperty("airsync.abi") as String?)
            ?: System.getenv("AIRSYNC_ABI")
            ?: "arm64-v8a"
        if (abiProp.isNotBlank() && abiProp != "all") {
            ndk {
                abiFilters += abiProp.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }

    // 签名配置：必须在 buildTypes 之前声明
    val signingProperties = Properties().apply {
        rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("AIRSYNC_KEYSTORE")
                ?: "${rootDir}/airsync-self.keystore"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("AIRSYNC_STORE_PASSWORD") ?: signingProperties.getProperty("storePassword")
                keyAlias = System.getenv("AIRSYNC_KEY_ALIAS") ?: signingProperties.getProperty("keyAlias")
                keyPassword = System.getenv("AIRSYNC_KEY_PASSWORD") ?: signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileSdkMinor = 0
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)

    // Material Components (XML themes: Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // Android 12+ SplashScreen API with backward compatibility attributes
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.compose.material3:material3:1.5.0-alpha10")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // DataStore for state persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-core:1.1.1")

    // ViewModel and state handling
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // WebSocket support
    implementation(libs.okhttp)

    // Media session support for Mac media player
    implementation("androidx.media:media:1.7.0")

    // CameraX for QR scanning
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("androidx.camera:camera-mlkit-vision:1.4.0")

    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.android)

    // ML Kit barcode scanner (QR code only)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Coil for app icon loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.wire.runtime)
    implementation(libs.bouncycastle)

    // Ktor Server for WebDAV
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
}

wire {
    kotlin {
        // Wire defaults to current project's proto directory
    }
}
