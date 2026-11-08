plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// Maven coordinates ai.quranlab:tajweedformer (version in gradle.properties). Nothing here publishes
// anywhere: publishToMavenLocal is the only repository until a release is made.
group = "ai.quranlab"
version = providers.gradleProperty("tajweedformer.version").get()

android {
    namespace = "ai.quranlab.tajweedformer"
    compileSdk = 34
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // the engine is built in its exact release configuration by native/CMakeLists.txt
                arguments += listOf("-DANDROID_STL=none")
                // another engine checkout than the submodule (the JVM build's variable of the same name)
                System.getenv("ZF_ENGINE_DIR")?.let { arguments += "-DZF_ENGINE_DIR=$it" }
                // -PnativeRelease: optimised native code in every variant (the device test's timing)
                if (project.hasProperty("nativeRelease")) arguments += "-DCMAKE_BUILD_TYPE=Release"
                targets += listOf("tajweedformer_jni", "tajweedformer_opus")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // src/main/kotlin is plain JVM Kotlin, shared with :tajweedformer-jvm; src/android/kotlin is the
    // Android-only part (microphone, SoC, network, the one-call client).
    sourceSets {
        getByName("main").kotlin.srcDir("src/android/kotlin")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
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

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.quranlab"
            artifactId = "tajweedformer"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // the portal link: telemetry, issues and the sharing outbox run as WorkManager jobs (survive process death and reboots)
    implementation("androidx.work:work-runtime:2.9.1")
    // Play Integrity for attested sharing uploads: compiled against, not shipped (an app that wants it adds the dependency)
    compileOnly("com.google.android.play:integrity:1.4.0")

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}
