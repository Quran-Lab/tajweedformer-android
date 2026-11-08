pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "tajweedformer-sdk-android"
// The Android library (AAR) and the same Kotlin sources built for the desktop JVM, where the
// conformance tests run against a host build of the JNI library.
include(":tajweedformer", ":tajweedformer-jvm")
// the recitation-sharing sheet and settings in Jetpack Compose (optional; the core has the Views ones)
include(":tajweedformer-compose")
