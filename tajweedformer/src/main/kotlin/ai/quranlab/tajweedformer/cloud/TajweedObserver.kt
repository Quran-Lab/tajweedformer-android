package ai.quranlab.tajweedformer.cloud

/**
 * What the recognition SDK tells the cloud side (telemetry, issues, recitation sharing). The core
 * (`ai.quranlab.tajweedformer`: downloader, backends, recognizer) calls these from its own threads;
 * every method is cheap (a lock and a few additions; audio is copied and encoded on the caller's
 * thread only while sharing is on) and never throws. Every method has a no-op default.
 *
 * Nothing passed here leaves the device except as the anonymous daily sums of section 6, and the
 * audio, only while the learner agreed to share (section 9).
 */
interface TajweedObserver {
    /** A model was loaded (each load; [cold] for the first of this process). */
    fun onModelLoad(e: ModelLoad) {}

    /** The CPU path's self-test after a load or an update. */
    fun onSelfTest(revision: String, context: String, backend: String, passed: Boolean) {}

    /** A backend gave way to another (the NPU to the CPU, say). */
    fun onFallback(revision: String, context: String, from: String, reason: FallbackReason) {}

    /** One model update attempt of a download profile, summed per day. */
    fun onDownload(e: Download) {}

    /** A recitation session started: the settings in force for it. */
    fun onSessionStart(e: SessionStart) {}

    /**
     * The audio the SDK received for the open session, mono float PCM at [sampleRate] as the app
     * gave it (before the engine's own resampling). Used only for sharing; the array is not kept.
     */
    fun onAudio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {}

    /** One engine chunk: its compute time and the audio it covered. */
    fun onChunk(computeMs: Double, audioMs: Double) {}

    /** Emitted tokens: log-prob margin top-1 minus runner-up, and the confidence head (0..1). Either may be null. */
    fun onTokens(margins: FloatArray?, confidences: FloatArray?) {}

    /** One ayah scored. */
    fun onScored(e: Scored) {}

    /** A thermal state change; [throttled] when the device now throttles. */
    fun onThermal(throttled: Boolean) {}

    /** The session ended. */
    fun onSessionEnd(e: SessionEnd) {}

    /** An error worth an issue report (no free text: a kind, a code, SDK frames). */
    fun onIssue(kind: String, code: String, error: Throwable? = null) {}

    data class ModelLoad(val revision: String, val context: String, val backend: String, val ms: Double, val cold: Boolean)

    data class Download(val revision: String, val profile: String, val bytes: Long, val success: Boolean, val resumed: Boolean)

    data class SessionStart(
        val revision: String,
        val context: String,
        /** What runs: `cpu`, `gpu`, `npu:<soc>`. */
        val backend: String,
        /** The site preset in force, or `custom` when any raw site threshold is set. */
        val preset: String,
        /** `strict|general|beginner|custom|off`. */
        val wordLevel: String,
        /** The custom word threshold (only when [wordLevel] is `custom`). */
        val wordThreshold: Double? = null,
        /** Classes overridden (0 to 8). */
        val siteOverrides: Int = 0,
        /** Checks on: `letters, vowels, length, madd, gemination, qalqalah, lazim`. */
        val checksOn: Set<String> = emptySet(),
        /** The capture rate of [onAudio]. */
        val sampleRate: Int = 16_000,
        /** `built_in|wired|usb|bluetooth|car|other|unknown`. */
        val inputRoute: String = "unknown",
        /** The backend name the shared-session JSON uses: `exact` (CPU, NPU) or `mock` in tests. */
        val decodeBackend: String = "exact",
        /** SHA-256 of the int8 weight file, when known (shared-session JSON). */
        val weightsSha256: String? = null,
    )

    data class Scored(
        val surah: Int,
        val ayah: Int,
        /** Per calibration class: sites scored and flagged. */
        val classes: Map<String, Pair<Int, Int>> = emptyMap(),
        /** Madd verdicts: counts of 2, 4 and 6 and how many were flagged. */
        val maddC2: Int = 0,
        val maddC4: Int = 0,
        val maddC6: Int = 0,
        val maddFlagged: Int = 0,
        val wordsScored: Int = 0,
        val wordsFlagged: Int = 0,
    )

    data class SessionEnd(
        /** Seconds of audio in the session. */
        val audioSeconds: Double,
        /** False when it failed or was cancelled. */
        val completed: Boolean,
        /** The learner was shown the model's feedback for this session (it may ask to share). */
        val feedbackShown: Boolean = false,
        /** At least one mistake was flagged and shown. */
        val mistakeFlagged: Boolean = false,
    )

    /** The fallback reasons of telemetry v2. */
    enum class FallbackReason(val key: String) {
        NON_FINITE("non_finite"), DEADLINE("deadline"), ANE_UNAVAILABLE("ane_unavailable"), NPU_UNAVAILABLE("npu_unavailable"),
        MODEL_LOAD("model_load"), THERMAL("thermal"), MEMORY("memory"), OTHER("other"),
    }
}
