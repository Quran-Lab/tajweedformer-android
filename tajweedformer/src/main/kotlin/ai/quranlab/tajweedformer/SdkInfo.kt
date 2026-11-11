package ai.quranlab.tajweedformer

/**
 * The SDK's identity. [VERSION] is what a model build's `min_sdk_version` is compared with: a 3.0 build
 * (a manifest with `version`) needs 3.0.0, and older SDKs are never offered it.
 */
internal object SdkInfo {
    const val VERSION = "3.0.0"

    /** The oldest Android the SDK runs on (the library's minSdk). */
    const val MIN_ANDROID = "Android 8.0 (API 26)"

    /** The ABIs the AAR has a native library for (the library's abiFilters; a test compares the two). x86_64 is
     *  for the Android emulator on an Intel or AMD PC and for x86 Chromebooks, on the CPU engine only
     *  ([SocIds.onArch]); the 35 conformance clips are bit-exact on it through the library. */
    @JvmField val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /** Where the SDK runs, in the words of the README's "Before you start": the `{minimum}` of `runtime_unsupported`. */
    @JvmField val MINIMUM = "$MIN_ANDROID or later on ${ABIS.dropLast(1).joinToString(", ")} or ${ABIS.last()}"

    /** The manifest format this SDK reads (layout v2: tagged files, gzip transport). */
    const val MANIFEST_FORMAT = 3

    /** The engine format this SDK reads: 3 = the V4 TajweedFormer folder of layout v2 (one shared
     *  `layers.zfw` and `frontend.zfw`, per context the graph, its data and `frames.zfw`). */
    const val ENGINE_FORMAT = 3

    /** CoreML specification versions a manifest may name (the field is checked, the CoreML model
     *  itself is used only on Apple platforms). */
    @JvmField val SUPPORTED_COREML_FORMATS = 1..9

    /** The platform tag this SDK downloads for (manifest `platforms`). */
    const val PLATFORM = "android"

    /**
     * Listeners for what the SDK does (sessions, backend choice and fallbacks, downloads, self-tests,
     * timing, and the audio it receives). See [SdkObserver]. The client's cloud side (telemetry, recording
     * sharing) registers itself here.
     */
    @JvmStatic
    val events: SdkEvents get() = SdkEvents
}
