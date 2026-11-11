plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// ai.quranlab:tajweedformer-compose: the recitation-sharing sheet and settings in Jetpack Compose.
// Optional: the core AAR has the same views for the Views toolkit and needs no Compose.
group = "ai.quranlab"
version = providers.gradleProperty("tajweedformer.version").get()

android {
    namespace = "ai.quranlab.tajweedformer.compose"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
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
        }
    }
}

dependencies {
    api(project(":tajweedformer"))
    val bom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
