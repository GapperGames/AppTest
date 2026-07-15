plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.poolsight.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poolsight.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.9-visual"
    }

    signingConfigs {
        // Committed debug keystore: keeps the signature stable across CI
        // builds so the phone updates in place instead of demanding an
        // uninstall. Debug-only; contains nothing secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":geometry"))
    implementation("com.google.ar:core:1.45.0")
}
