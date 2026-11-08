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
