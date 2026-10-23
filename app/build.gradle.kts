plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mergetactics.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mergetactics.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Распознавание текста на устройстве (модель вшита в приложение, интернет не нужен)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
