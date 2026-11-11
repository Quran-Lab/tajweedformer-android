package ai.quranlab.tajweedformer.cloud

/**
 * What the inference side tells the cloud side (telemetry, issues, recitation sharing). The core
 * (`ai.quranlab.tajweedformer`: downloader, backends, recognizer) calls these from its own threads;
 * every method is cheap (a lock and a few additions; audio is copied and encoded on the caller's
 * thread only while sharing is on) and never throws. Every method has a no-op default.
 *
 * Nothing passed here leaves the device except as the anonymous sums of the telemetry, and the
 * audio, only while the learner agreed to share.
 *
 * A model is named by its version (`revision`, as the 2.x wire calls the field) and its build id (`build`).
 */
internal interface TajweedObserver {
    /** A model was loaded (each load; [ModelLoad.cold] for the first of this process). */
    fun onModelLoad(e: ModelLoad) {}

    /** The CPU path's self-test after a load or an update. */
    fun onSelfTest(revision: String, context: String, backend: String, passed: Boolean, build: String = "") {}

    /** A backend gave way to another (the NPU to the CPU, say). */
    fun onFallback(revision: String, context: String, from: String, reason: FallbackReason, build: String = "") {}

    /** One model update attempt of a download profile, summed per day. */
    fun onDownload(e: Download) {}

    /** A recitation session (one take) started. */
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

    /** A thermal state change; [throttled] when the device now throttles. */
    fun onThermal(throttled: Boolean) {}

    /** The session ended. */
    fun onSessionEnd(e: SessionEnd) {}

    /** An error worth an issue report (no free text: a kind, a code, SDK frames). */
    fun onIssue(kind: String, code: String, error: Throwable? = null) {}

    /** A promotion attempt of the accelerator ended (promoted or failed at a stage): `promotions` telemetry, and an
     *  incident report (`promotion_failed`) when it failed. */
    fun onPromotion(e: ai.quranlab.tajweedformer.PromotionEvent) {}

    /** A session switched from the accelerator to the CPU: `promotions` telemetry, and an incident report
     *  (`accelerator_failover`). */
    fun onFailover(e: ai.quranlab.tajweedformer.AcceleratorFailoverEvent) {}

    /** The CPU confirmed a blank stretch of an accelerator take: `promotions[].blank_confirmed_ok`, nothing else. */
    fun onBlankConfirmed(e: ai.quranlab.tajweedformer.BlankConfirmedEvent) {}

    data class ModelLoad(val revision: String, val context: String, val backend: String, val ms: Double, val cold: Boolean, val build: String = "")

    data class Download(val revision: String, val profile: String, val bytes: Long, val success: Boolean, val resumed: Boolean, val build: String = "")

    data class SessionStart(
        /** The model's version. */
        val revision: String,
        val context: String,
        /** What runs: `cpu`, `gpu`, `npu:<soc>`. */
        val backend: String,
        /** The capture rate of [onAudio]. */
        val sampleRate: Int = 16_000,
        /** `built_in|wired|usb|bluetooth|car|other|unknown`. */
        val inputRoute: String = "unknown",
        /** The backend name the shared-session JSON uses: `exact` (CPU, NPU) or `mock` in tests. */
        val decodeBackend: String = "exact",
        /** SHA-256 of the int8 weight file, when known (shared-session JSON). */
        val weightsSha256: String? = null,
        /** The model's build id. */
        val build: String = "",
    )

    data class SessionEnd(
        /** Seconds of audio in the session. */
        val audioSeconds: Double,
        /** False when it failed or was cancelled. */
        val completed: Boolean,
        /** The take finished and its output was delivered to the app (the SDK may then ask to share). */
        val feedbackShown: Boolean = false,
        /** floor(1000 * input samples / input rate). */
        val audioMs: Long = 0,
        /** The compute time of the take's chunks, each in whole microseconds, summed. */
        val computeUs: Long = 0,
        /** The take's 40 ms frames of speech and of silence, from the model's own output (SPEC 6.2). */
        val speechFrames: Int = 0,
        val silenceFrames: Int = 0,
        /** Greedy tokens emitted per selected CTC head. */
        val tokens: Map<String, Int> = emptyMap(),
    )

    /** The fallback reasons of telemetry v2. */
    enum class FallbackReason(val key: String) {
        NON_FINITE("non_finite"), DEADLINE("deadline"), ANE_UNAVAILABLE("ane_unavailable"), NPU_UNAVAILABLE("npu_unavailable"),
        MODEL_LOAD("model_load"), THERMAL("thermal"), MEMORY("memory"), OTHER("other"),
    }
}
