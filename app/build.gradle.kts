plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.remote"
        minSdk = 26   // Android 8.0; enables adaptive-icon XML without binary PNGs
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // BuildConfig.VERSION_NAME is read by the update checker.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // QR scanning for first-run pairing (ZXing wrapper, offline-capable).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // ReactHost is not required; the WebView loads the remote /m/ page directly.
}
