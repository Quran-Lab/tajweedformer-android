import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

// Maven coordinates ai.quranlab:tajweedformer (version in gradle.properties). Nothing here uploads anywhere:
// the release writes a signed Maven layout to build/central-bundle and zips it (centralBundle), and the
// publish workflow (.github/workflows/publish.yml) hands that zip to the Central Portal.
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
            withJavadocJar()
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
            pom {
                name.set("TajweedFormer for Android")
                description.set(
                    "TajweedFormer V4 on Android: on-device tajweed feedback from the exact engine, word by word " +
                        "while the learner recites, with the decoded layer, per-site verdicts and the Quran Lab platform client."
                )
                url.set("https://github.com/Quran-Lab/tajweedformer-android")
                licenses {
                    license {
                        name.set("Quran-Lab No-Profit License 1.2 (NPL-1.2)")
                        url.set("https://github.com/Quran-Lab/tajweedformer-android/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("quran-lab")
                        name.set("Quran Lab")
                        organization.set("Quran Lab")
                        organizationUrl.set("https://quranlab.ai")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Quran-Lab/tajweedformer-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Quran-Lab/tajweedformer-android.git")
                    url.set("https://github.com/Quran-Lab/tajweedformer-android")
                }
            }
        }
    }
    repositories {
        // a folder, not a server: the signed files in the Maven layout the Central Portal's bundle needs
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// In-memory GPG signing of the release publication, only when SIGNING_KEY (an ASCII-armored private key with no
// passphrase) is set: the publish workflow sets it, every other build leaves the artifacts unsigned.
val signingKey: String? = providers.environmentVariable("SIGNING_KEY").orNull?.takeIf { it.isNotBlank() }
signing {
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, "")
        sign(publishing.publications["release"])
    }
}

val bundleDir = layout.buildDirectory.dir("central-bundle")
tasks.matching { it.name == "publishReleasePublicationToBundleRepository" }.configureEach {
    // only this release's files: nothing left over from an earlier build goes into the bundle
    doFirst { delete(bundleDir) }
}

// The bundle for the Central Portal's upload API: the group's Maven layout with the .asc signatures and the
// checksums the file repository wrote; maven-metadata is the repository's own bookkeeping, not part of a release.
val centralBundle by tasks.registering(Zip::class) {
    description = "Zips the signed release publication for the Central Portal (needs SIGNING_KEY)."
    dependsOn("publishReleasePublicationToBundleRepository")
    from(bundleDir)
    exclude("**/maven-metadata*")
    archiveFileName.set("central-bundle-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doFirst {
        check(signingKey != null) { "centralBundle needs SIGNING_KEY: Maven Central refuses unsigned files" }
        // the native engine must be in the AAR for both Android ABIs
        val aar = bundleDir.get().asFile.walkTopDown().single { it.name.endsWith("${project.version}.aar") }
        val entries = ZipFile(aar).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
            for (lib in listOf("libtajweedformer_jni.so", "libtajweedformer_opus.so")) {
                check("jni/$abi/$lib" in entries) { "the AAR has no jni/$abi/$lib" }
            }
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
