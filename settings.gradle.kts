pluginManagement {
    repositories {
        // Plugin portal first: the geometry module can then be built in
        // environments where Google's maven is unreachable.
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "PoolSight"

include(":geometry")

// The Android app module needs the Android SDK. The geometry module (the pure
// aiming maths) does not — so when no SDK is present we still configure and
// test :geometry. CI and Android Studio always have the SDK and get both.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found: configuring :geometry only (aiming maths + tests).")
}
