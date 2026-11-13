plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
    // the sharing sheet's screenshot and layout tests (src/test): Paparazzi renders the Compose sheet and the core's Views
    // dialog with Android's own renderer (layoutlib) on the JVM. It adds to the unit tests' configurations only: the
    // published AAR and POM of either module carry nothing of it.
    id("app.cash.paparazzi")
}

// ai.quranlab:tajweedformer-compose: the recitation-sharing sheet and settings in Jetpack Compose.
// Optional: the core AAR has the same views for the Views toolkit and needs no Compose.
//
// Published to Maven Central with the core, in ONE bundle and one deployment (the core's centralBundle task zips both
// publications): either both artifacts of a version are on Maven Central or neither is. It works only with
// ai.quranlab:tajweedformer of EXACTLY the same version: it is compiled as a friend of the core and calls the core's
// internals, whose names and shapes belong to one version. Its POM names that one version of the core.
group = "ai.quranlab"
version = providers.gradleProperty("tajweedformer.version").get()

android {
    namespace = "ai.quranlab.tajweedformer.compose"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    // src/androidTest is the sheet's device check: an activity a host starts with adb to show both drawings on a phone
    // and end them with the system's own input. Its APK targets a current Android (a phone's Play Protect blocks an app
    // that targets an old one); the library itself has no target.
    testOptions { targetSdk = 34 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Only the types of the public API are public, here as in the core.
kotlin { explicitApi() }

// The sheets are part of the SDK: they read the copy deck and record the learner's answer through the core's
// internals, which no app can reach. So this module compiles as a friend of the core module (the same mechanism a
// module's tests use), against the core's classes of the same variant.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val core = project(":tajweedformer").layout.buildDirectory.get().asFile.invariantSeparatorsPath
    friendPaths.from(libraries.filter { it.invariantSeparatorsPath.startsWith(core) })
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.quranlab"
            artifactId = "tajweedformer-compose"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            // the POM names the versions the build resolved (the Compose libraries come through a BOM here; a POM
            // reader without the BOM needs each version said)
            versionMapping { allVariants { fromResolutionOf("releaseRuntimeClasspath") } }
            pom {
                name.set("TajweedFormer for Android: the sharing sheet in Jetpack Compose")
                description.set(
                    "The recitation-sharing sheet and settings of TajweedFormer for Android in Jetpack Compose. Optional: the " +
                        "core library ai.quranlab:tajweedformer has the same sheet for the Views toolkit. Works only with " +
                        "ai.quranlab:tajweedformer of exactly the same version."
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
        // the core's bundle folder: both publications go into the one Maven layout that is zipped for the Central Portal
        maven {
            name = "bundle"
            url = uri(project(":tajweedformer").layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// In-memory GPG signing with the same key as the core, only when SIGNING_KEY is set (the publish workflow sets it).
val signingKey: String? = providers.environmentVariable("SIGNING_KEY").orNull?.takeIf { it.isNotBlank() }
signing {
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, "")
        sign(publishing.publications["release"])
    }
}

// The core's publication empties the bundle folder before it writes: this one goes in after it, never before.
tasks.matching { it.name == "publishReleasePublicationToBundleRepository" }.configureEach {
    mustRunAfter(":tajweedformer:publishReleasePublicationToBundleRepository")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // -PsheetPictures=<folder>: the sheet tests write their pictures (PNG) there; without it they write nothing
    providers.gradleProperty("sheetPictures").orNull?.let { systemProperty("sheet.pictures", it) }
}

dependencies {
    api(project(":tajweedformer"))
    val bom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // the consent sheet's window hands the app's view-tree owners to the sheet's ComposeView: both libraries come with
    // Compose UI at run time, in these versions; declared for compiling only, so neither the AAR nor the POM changes
    compileOnly("androidx.lifecycle:lifecycle-runtime:2.6.1")
    compileOnly("androidx.savedstate:savedstate:1.2.1")
    // the device check only: the ComponentActivity that hosts both sheets (the version Compose UI already brings)
    androidTestImplementation("androidx.activity:activity:1.7.0")
    constraints {
        // the screenshot tests only: layoutlib and Paparazzi's Android tools need Guava's standard JVM build, and an
        // Android unit test's classpath would otherwise take its Android build (Paparazzi's issue 906)
        testImplementation("com.google.guava:guava") {
            attributes {
                attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
            }
        }
    }
}
