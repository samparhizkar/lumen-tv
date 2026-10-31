import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.samparhizkar.lumen"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.samparhizkar.lumen"
        minSdk = 28          // Sony dongle is API 34; keep headroom for other boxes
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"
        ndk {
            // Homatics dongle userspace is 32-bit only.
            abiFilters += listOf("armeabi-v7a")
        }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    // Reused protocol/crypto core (RTSP, pairing, FairPlay, RTP decrypt).
    externalNativeBuild {
        cmake {
            path = file("../native-core/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release key from keystore.properties at the repo root (git-ignored; see
    // README). Without it, release builds fall back to the debug key so anyone
    // can still build and sideload.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking; Compose is several times slower without it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.0.0")
    // Installs the Compose libraries' baseline profiles so their hot paths are
    // AOT-compiled on sideloaded installs too, not just Play installs.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
