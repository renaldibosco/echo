plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reno.echo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reno.echo"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "2.2"
    }

    // One fixed key so every new version installs over the old one
    signingConfigs {
        getByName("debug") {
            storeFile = file("echo.keystore")
            storePassword = "echomobile123"
            keyAlias = "echo"
            keyPassword = "echomobile123"
        }
    }

    // Two assistants from one codebase. Each installs as its own app.
    flavorDimensions += "persona"
    productFlavors {
        create("echo") {
            dimension = "persona"
            applicationId = "com.reno.echo"
        }
        create("jane") {
            dimension = "persona"
            applicationId = "com.reno.jane"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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

// Built-in Android APIs only: SpeechRecognizer, TextToSpeech, WebView.
