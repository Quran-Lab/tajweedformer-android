package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.CheckStatus
import ai.quranlab.tajweedformer.MaddClass
import ai.quranlab.tajweedformer.TajweedResult

/**
 * What the recognition SDK tells the cloud side (telemetry, issues, recitation sharing). The core
 * (`ai.quranlab.tajweedformer`: downloader, backends, recognizer) calls these from its own threads;
 * every method is cheap (a lock and a few additions; audio is copied and encoded on the caller's
 * thread only while sharing is on) and never throws. Every method has a no-op default.
 *
 * Nothing passed here leaves the device except as the anonymous sums of the telemetry, and the
 * audio, only while the learner agreed to share.
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

    /** A promotion attempt of the accelerator ended (promoted or failed at a stage): `promotions` telemetry, and an
     *  incident report (`promotion_failed`) when it failed. */
    fun onPromotion(e: ai.quranlab.tajweedformer.PromotionEvent) {}

    /** A session switched from the accelerator to the CPU: `promotions` telemetry, and an incident report
     *  (`accelerator_failover`). */
    fun onFailover(e: ai.quranlab.tajweedformer.AcceleratorFailoverEvent) {}

    /** The CPU confirmed a blank stretch of an accelerator take: `promotions[].blank_confirmed_ok`, nothing else. */
    fun onBlankConfirmed(e: ai.quranlab.tajweedformer.BlankConfirmedEvent) {}

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
        /**
         * Per rule or class id (`vowel_sub` ... `insertion`, `madd`, `qalqalah`, `gemination`, `word`): the
         * verdicts whose check ran (status not off, enabled in the settings in force), and of those the flagged.
         */
        val ruleChecks: Map<String, Int> = emptyMap(),
        val ruleFlags: Map<String, Int> = emptyMap(),
    ) {
        companion object {
            /** The counts of one scored ayah. */
            @JvmStatic
            fun of(r: TajweedResult): Scored {
                val checks = LinkedHashMap<String, Int>()
                val flags = LinkedHashMap<String, Int>()
                fun add(key: String, flagged: Boolean) {
                    checks[key] = (checks[key] ?: 0) + 1
                    if (flagged) flags[key] = (flags[key] ?: 0) + 1
                }
                val st = r.settings
                for (v in r.sites) if (v.status != CheckStatus.OFF && st.sites[v.siteClass]?.enabled == true) add(v.siteClass.key, v.flagged)
                for (v in r.madd) {
                    val on = st.madd.enabled && (v.maddClass != MaddClass.LAZIM || st.lazim.enabled)
                    if (v.status != CheckStatus.OFF && on) add("madd", v.flagged)
                }
                for (v in r.qalqalah) if (v.status != CheckStatus.OFF && st.qalqalah.enabled) add("qalqalah", v.flagged)
                for (v in r.gemination) if (v.status != CheckStatus.OFF && st.gemination.enabled) add("gemination", v.flagged)
                for (w in r.words) add("word", w.flagged)
                return Scored(
                    surah = r.surah, ayah = r.ayah,
                    classes = r.sites.groupBy { it.siteClass.key }.mapValues { (_, v) -> v.size to v.count { it.flagged } },
                    maddC2 = r.madd.count { it.count == 2 }, maddC4 = r.madd.count { it.count == 4 },
                    maddC6 = r.madd.count { it.count == 6 }, maddFlagged = r.madd.count { it.flagged },
                    wordsScored = r.words.size, wordsFlagged = r.words.count { it.flagged },
                    ruleChecks = checks, ruleFlags = flags,
                )
            }
        }
    }

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
