
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fogofx.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fogofx.jarvis"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(
        "com.google.ai.edge.litertlm:litertlm-android:latest.release"
    )
}
