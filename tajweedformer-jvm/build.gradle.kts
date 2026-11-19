import java.time.Duration

// The library's Kotlin sources (tajweedformer/src/main/kotlin) on the desktop JVM, so the wrapper is tested without a
// device. SDK 4.0: the tests that need the Quran Lab Core run against a host build of the Core and of the JNI bridge
// (scripts/build_host_core.sh), named by TAJWEEDFORMER_LIBRARY and TAJWEEDFORMER_CORE_LIBRARY; without them they skip.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    sourceSets["main"].kotlin.srcDir("../tajweedformer/src/main/kotlin")
    // the desktop JVM's own part: TajweedClient(key, storage)
    sourceSets["main"].kotlin.srcDir("../tajweedformer/src/jvm/kotlin")
    // the 4.0 test kit and the contract's vectors, shared with the device tests
    sourceSets["test"].kotlin.srcDir("../tajweedformer/src/sharedTest/kotlin")
    // only the types of NAMING.md are public: every public declaration says so
    explicitApi()
}
// the SDK's Java resources (the self-test clip, the sharing copy deck) on the desktop JVM too
sourceSets["main"].resources.srcDir("../tajweedformer/src/main/resources")
sourceSets["test"].resources.srcDir("../tajweedformer/src/sharedTest/resources")
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

// every test task of this module is set up the same way
tasks.withType<Test>().configureEach {
    systemProperty("tajweedformer.version", rootProject.property("tajweedformer.version") as String)
    // the host's JNI bridge and Core (scripts/build_host_core.sh); the build folder's pair when present
    val host = rootProject.layout.buildDirectory.dir("host-core").get().asFile
    (System.getenv("TAJWEEDFORMER_LIBRARY") ?: host.listFiles()?.firstOrNull { it.name.matches(Regex("(lib)?tajweedformer_jni\\.(dll|so|dylib)")) }?.path)
        ?.let { systemProperty("tajweedformer.library", it) }
    (System.getenv("TAJWEEDFORMER_CORE_LIBRARY") ?: host.listFiles()?.firstOrNull { it.name.matches(Regex("(lib)?qlcore\\.(dll|so|dylib)")) }?.path)
        ?.let { systemProperty("tajweedformer.core.library", it) }
    // the shared 3.0 vectors (TJ_VECTORS_DIR: sdk_3.0.0/vectors of the contract; the tests that read them skip without
    // it, except the copies kept in src/test/resources), the 4.0 ones (TJ_VECTORS40_DIR: sdk_4.0.0/vectors, checked
    // byte for byte against the copies in src/sharedTest/resources), the error catalogue, and how many times
    // SharingOffTest runs each of its cases (the shared file says 20; a race passes most single runs) and which; a
    // qlab-api worktree (QLAB_API_DIR, branch sdk4-server) whose own events validator TelemetryEvents4Test runs, read-only;
    // a protected test build of the Core's tools/make_test_build.py (TJ_CORE_TEST_BUILD) and a clip (TJ_CORE_CLIP, 16 kHz
    // float32) for CoreFlowTest's whole desktop flow
    for (k in listOf("TJ_VECTORS_DIR", "TJ_VECTORS40_DIR", "TJ_ERRORS_JSON", "TJ_REPEAT", "TJ_CASES", "QLAB_API_DIR", "TJ_CORE_TEST_BUILD", "TJ_CORE_CLIP")) {
        System.getenv(k)?.let { systemProperty(k, it) }
    }
    // bounded before it starts: one test JVM of at most 2 GB (maxHeapSize below), at most 4 workers on a shared
    // machine (the engine tests run one stream per process anyway), and a wall-clock limit for the whole task
    maxParallelForks = 1
    timeout.set(Duration.ofMinutes(30))
    // recitation sharing's codec tests: libtajweedformer_opus for this machine
    // (scripts/opus/build_opus_host.sh; TAJWEEDFORMER_OPUS_LIBRARY names another build)
    val opusLib = System.getenv("TAJWEEDFORMER_OPUS_LIBRARY")
        ?: rootProject.layout.buildDirectory.dir("host-opus").get().asFile.listFiles()
            ?.firstOrNull { it.name.matches(Regex("(lib)?tajweedformer_opus\\.(dll|so|dylib)")) }?.path
    opusLib?.let { systemProperty("tajweedformer.opus.library", it) }
    System.getenv("TJ_REQUIRE_OPUS")?.let { systemProperty("TJ_REQUIRE_OPUS", it) }
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
