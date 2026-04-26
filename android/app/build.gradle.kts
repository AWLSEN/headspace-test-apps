plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mercurylabs.headspace"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mercurylabs.headspace"
        minSdk = 24       // Android 7.0 — covers ~99% of devices and has UsbManager
        // targetSdk 33 NOT 34: the AndroidUSBCamera library calls
        // registerReceiver without RECEIVER_EXPORTED/_NOT_EXPORTED, which
        // throws on targetSdk 34+ on Android 14 devices. Targeting 33
        // grandfathers us into the lenient behavior.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"  // matches Kotlin 1.9.24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No keystore yet — debug-signed APK, fine for sideloading.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // AndroidUSBCamera: bundles libusb + libuvc, bypasses Android's USB API
    // (the broken Mediatek class-control transfers). Fragment-based, gives
    // raw frame callbacks via IFrameCallback.
    // 3.2.7 — last version where all sub-artifacts (libuvc, libnative,
    // libutils, libuvccommon) were actually published to JitPack. Newer
    // tags forgot to publish libuvc.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7")
    // Sub-modules needed for direct API use (USBMonitor, frame callbacks).
    // libausbc declares these as `api` in its POM but Kotlin still needs
    // them on the compile classpath explicitly.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvccommon:3.2.7")
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libnative:3.2.7")
}
