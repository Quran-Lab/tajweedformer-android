// The README's quick start as an app: the microphone in, what the model heard out, on one screen.
// CI compiles it (:samples:quickstart:compileDebugKotlin), so the code in the README always builds, and
// QuickStartDocTest keeps the README's text equal to QuickStartActivity.kt.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.quranlab.tajweedformer.quickstart"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.quranlab.tajweedformer.quickstart"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // the SDK's native code is built for these two; an x86_64 emulator image has no library to load
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        // The release build shrinks, as an app that ships does: R8 with the default rules and no rule of this app's
        // own. It builds only because the SDK's AAR brings every rule it needs (tajweedformer/consumer-rules.pro).
        // CI assembles it (:samples:quickstart:assembleRelease), so a missing rule fails the build there. Signed
        // with the debug key: it is never published.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    // in your own app: implementation("ai.quranlab:tajweedformer:<version>") with mavenCentral() in the repositories
    implementation(project(":tajweedformer"))
}
