import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

// Maven coordinates ai.quranlab:tajweedformer (version in gradle.properties). Nothing here uploads anywhere:
// the release writes a signed Maven layout to build/central-bundle and zips it (centralBundle), and the
// publish workflow (.github/workflows/publish.yml) hands that zip to the Central Portal. The zip holds this
// library AND ai.quranlab:tajweedformer-compose (the Compose module publishes into the same folder): one bundle, one
// deployment, so both artifacts of a version are on Maven Central or neither is.
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
            // x86_64 is for the Android emulator on an Intel or AMD PC and for x86 Chromebooks, on the CPU engine only:
            // the 35 conformance clips are bit-exact on it through this library (DeviceEndToEndTest on an x86_64
            // emulator, scripts/emulator_proof.py). SdkInfo.ABIS says the same.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
        // internal members carry the module's name in their JVM names: one name for every variant, so the Compose
        // module (a friend of this one) links against debug and release alike
        moduleName = "tajweedformer"
    }
}

// Only the types of NAMING.md are public: every public declaration says so, everything else is internal.
kotlin { explicitApi() }

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
                    "TajweedFormer V4 on Android: an on-device inference engine for Quran recitation. Audio in; the model's " +
                        "raw heads, greedy tokens and the decoded layer out, chunk by chunk while the learner recites."
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
    description = "Zips the signed release publications of the core and the Compose module for the Central Portal (needs SIGNING_KEY)."
    dependsOn("publishReleasePublicationToBundleRepository", ":tajweedformer-compose:publishReleasePublicationToBundleRepository")
    from(bundleDir)
    exclude("**/maven-metadata*")
    archiveFileName.set("central-bundle-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doFirst {
        check(signingKey != null) { "centralBundle needs SIGNING_KEY: Maven Central refuses unsigned files" }
        // both components, whole: the AAR, the POM, the module file, sources and javadoc, each with its signature
        val names = bundleDir.get().asFile.walkTopDown().filter { it.isFile }.map { it.relativeTo(bundleDir.get().asFile).invariantSeparatorsPath }.toSet()
        for (artifact in listOf("tajweedformer", "tajweedformer-compose")) {
            val base = "ai/quranlab/$artifact/${project.version}/$artifact-${project.version}"
            for (file in listOf(".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")) {
                check("$base$file" in names) { "the bundle has no $base$file" }
                check("$base$file.asc" in names) { "the bundle has no signature of $base$file" }
            }
        }
        // the native engine must be in the AAR for both Android ABIs
        val aar = bundleDir.get().asFile.walkTopDown().single { it.name == "tajweedformer-${project.version}.aar" }
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
    // Play Integrity (SDK 3.1.0, SPEC 15.4): a real dependency of the AAR. The SDK asks Play for an integrity token only
    // when Quran Lab's signed config gives the Cloud project number (flags.play_integrity_project_number), for model
    // downloads and attested sharing; without it, or where Play services are missing, the token is unattested
    implementation("com.google.android.play:integrity:1.4.0")

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}
