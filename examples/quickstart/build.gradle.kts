// The example app: one screen (ExampleActivity.kt: the model loading with its progress, "Play sample", "Recite", the
// decoded tokens, "Diagnose") and the README's quick start (QuickStart.kt). CI builds it
// (:examples:quickstart:assembleDebug), so the code in the README always builds, and QuickStartDocTest keeps the
// README's text equal to QuickStart.kt.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The key the example runs with: a development apps key from sdk.quranlab.ai, given to the build in the environment
// variable QURANLAB_KEY. It goes into a generated resource under build/, never into a file git tracks. Without it
// the app is built with a placeholder and says so when it starts (README.md here).
val quranlabKey: String = providers.environmentVariable("QURANLAB_KEY").orNull?.takeIf { it.isNotBlank() } ?: "qlk_v1_..."

// -PsdkAar=<a built tajweedformer AAR>: the app against that file, as an app outside this repository has the SDK,
// instead of against the library's sources (the release gate, scripts/phone_gate.py).
val sdkAar: String? = providers.gradleProperty("sdkAar").orNull

android {
    namespace = "ai.quranlab.tajweedformer.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.quranlab.tajweedformer.example"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resValue("string", "quranlab_key", quranlabKey)
        // the ABIs the SDK has native code for: the two of phones, and x86_64 for the emulator on a PC
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        // The release build shrinks, as an app that ships does: R8 with the default rules and no rule of this app's
        // own. It builds only because the SDK's AAR brings every rule it needs (tajweedformer/consumer-rules.pro).
        // CI assembles it (:examples:quickstart:assembleRelease), so a missing rule fails the build there. Signed
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
    if (sdkAar == null) {
        implementation(project(":tajweedformer"))
    } else {
        // the AAR as a file has no POM: these are the dependencies its POM names
        implementation(files(sdkAar))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        implementation("androidx.work:work-runtime:2.9.1")
    }
}
