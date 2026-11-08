// The library's Kotlin sources (tajweedformer/src/main/kotlin) on the desktop JVM, against a host build
// of the JNI library, so the whole wrapper is conformance-tested without a device.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    sourceSets["main"].kotlin.srcDir("../tajweedformer/src/main/kotlin")
}
// the SDK's Java resources (the self-test clip, the sharing copy deck) on the desktop JVM too
sourceSets["main"].resources.srcDir("../tajweedformer/src/main/resources")
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

val hostLib = rootProject.layout.buildDirectory.dir("host")
// the engine: the submodule, or the checkout ZF_ENGINE_DIR names (scripts/build_host.sh reads it too)
val engineDir: File = System.getenv("ZF_ENGINE_DIR")?.let(::file) ?: rootDir.resolve("engine")

// -PnoNative (the default on Windows, where the host build needs Git Bash, MinGW GCC and Ninja on
// PATH): no host build of the JNI library; the engine tests then skip themselves unless
// TAJWEEDFORMER_LIBRARY names a library built beforehand (scripts/build_host.sh). -Pnative builds it.
val noNative = !project.hasProperty("native") &&
    (project.hasProperty("noNative") || System.getProperty("os.name").lowercase().startsWith("windows"))

val buildHostNative by tasks.registering(Exec::class) {
    description = "Builds libtajweedformer_jni for this machine (scripts/build_host.sh)."
    workingDir = rootDir
    // on Windows Git for Windows' bash (the one on PATH may be WSL's, which is not this build's host)
    val bash = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
        listOf(System.getenv("ProgramFiles") ?: "C:/Program Files", System.getenv("ProgramW6432") ?: "C:/Program Files")
            .map { File(it, "Git/bin/bash.exe") }.firstOrNull { it.isFile }?.path ?: "bash"
    } else "bash"
    commandLine(bash, "scripts/build_host.sh", hostLib.get().asFile.invariantSeparatorsPath)
    inputs.dir(rootDir.resolve("native")).withPropertyName("native")
    inputs.dir(engineDir.resolve("engine")).withPropertyName("engine")
    inputs.dir(engineDir.resolve("accel")).withPropertyName("accel")
    inputs.dir(engineDir.resolve("tajweed")).withPropertyName("tajweed")
    outputs.dir(hostLib)
    onlyIf { !noNative }
}

tasks.test {
    dependsOn(buildHostNative)
    systemProperty("tajweedformer.version", rootProject.property("tajweedformer.version") as String)
    // the V4 scorer against the reference vectors (ScorerVectorsTest; skipped without these):
    // TAJWEEDFORMER_LIBRARY = a JNI library with zf_tajweed (scripts/build_scorer_host.sh),
    // TJ_SCORER_TABLE = scripts/make_scorer_table.py's folder
    System.getenv("TAJWEEDFORMER_LIBRARY")?.let { systemProperty("tajweedformer.library", it) }
    System.getenv("TJ_SCORER_TABLE")?.let { systemProperty("TJ_SCORER_TABLE", it) }
    // the layout-3 model folder (signed with a test key) and that key, for the engine and download tests
    for (k in listOf("TJ_V4_DIR", "TJ_V4_TRANSPORT_DIR", "TJ_TEST_PUBLIC_KEY", "TJ_NPU_DIR", "TJ_NPU_GOLDEN", "TJ_NPU_WORK", "TJ_NPU_2500_GOLDEN", "TJ_SELFTEST_TOKENS_OUT")) {
        System.getenv(k)?.let { systemProperty(k, it) }
    }
    // recitation sharing's codec tests: libtajweedformer_opus for this machine
    // (scripts/opus/build_opus_host.sh; TAJWEEDFORMER_OPUS_LIBRARY names another build)
    val opusLib = System.getenv("TAJWEEDFORMER_OPUS_LIBRARY")
        ?: rootProject.layout.buildDirectory.dir("host-opus").get().asFile.listFiles()
            ?.firstOrNull { it.name.matches(Regex("(lib)?tajweedformer_opus\\.(dll|so|dylib)")) }?.path
    opusLib?.let { systemProperty("tajweedformer.opus.library", it) }
    System.getenv("TJ_REQUIRE_OPUS")?.let { systemProperty("TJ_REQUIRE_OPUS", it) }
    systemProperty("java.library.path", hostLib.get().asFile.path)
    val conformance = System.getenv("ZF_V4_CONFORMANCE_DIR") ?: engineDir.resolve("conformance").path
    systemProperty("ZF_V4_CONFORMANCE_DIR", conformance)
    System.getenv("ZF_V4_AUDIO_DIR")?.let { systemProperty("ZF_V4_AUDIO_DIR", it) }
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
