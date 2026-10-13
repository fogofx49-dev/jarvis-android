
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("androidx.core:core-ktx:1.15.0")
}




