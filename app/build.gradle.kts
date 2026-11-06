plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
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
    // Reconhecimento de voz
    implementation("ai.picovoice:porcupine-android:2.1.0")

    // Gemma local
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")

    // Firebase e Gemini
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // Coroutines para chamadas do Gemini
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
