package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.Divergence
import ai.quranlab.tajweedformer.accel.FailoverEvent
import ai.quranlab.tajweedformer.accel.Reasons

/**
 * One promotion attempt that ended ([SdkObserver.onPromotion]): promoted, or failed at [stage] with [reason]. The
 * `promotions` telemetry counts it; a failure is also an incident report ([incident]).
 */
internal data class PromotionEvent(
    /** `npu:<soc>`. */
    val backend: String,
    val revision: String,
    val context: String,
    val promoted: Boolean,
    /** The stage it ended at (`promote` when promoted; `download`, `compile`, `warmup`, `golden`; `session` for a crash
     *  at a session's first accelerator call). */
    val stage: String,
    /** A [Reasons] code when it failed. */
    val reason: String?,
    /** Which attempt for this key (1 for the first). */
    val attempt: Int,
    /** The compile stage's time, when this attempt finished it. */
    val compileMs: Long? = null,
    /** CPU median chunk time over the accelerator's, when the attempt reached the measure stage. */
    val speedup: Double? = null,
    /** False for a crash found at launch that did not end a pipeline attempt (a session's first call). */
    val countsAttempt: Boolean = true,
    val incident: AcceleratorIncident? = null,
    /** A stop of the process in the background that counted nothing (SDK 3.1.5): reported, not counted in the statistics. */
    val background: Boolean = false,
)

/**
 * The CPU checked a blank stretch of an accelerator take and wrote nothing there either ([SdkObserver.onBlankConfirmed]):
 * a held vowel or a long madd, not a fault. No switch, no demotion, no incident: the `promotions` telemetry's
 * `blank_confirmed_ok` counts it.
 */
internal data class BlankConfirmedEvent(
    /** `npu:<soc>`. */
    val backend: String,
    val revision: String,
    val context: String,
    val check: ai.quranlab.tajweedformer.accel.BlankCheck,
)

/** A session switched from the accelerator to the CPU ([SdkObserver.onFailover]). */
internal data class AcceleratorFailoverEvent(
    val backend: String,
    val revision: String,
    val context: String,
    val failover: FailoverEvent,
    val incident: AcceleratorIncident,
)

/**
 * An accelerator incident, for the issue report (platform client doc, section 7.1): the stage and cause, the error's
 * class and message (the message is path-stripped before it leaves the device), where it failed, and the device,
 * accelerator and conditions. Never audio, transcript or token text, or any id.
 */
internal data class AcceleratorIncident(
    /** [FAILOVER] or [PROMOTION_FAILED]. */
    val kind: String,
    /** `download`, `compile`, `warmup`, `golden` or `session`. */
    val stage: String,
    /** The report's cause class ([cause]). */
    val cause: String,
    val revision: String,
    val context: String,
    /** `npu:<soc>`. */
    val accelerator: String,
    val error: Throwable? = null,
    val message: String? = null,
    /** A runtime status or errno, when the error carries one. */
    val errorCode: String? = null,
    val chunk: Int? = null,
    val elapsedMs: Double? = null,
    val deadlineMs: Double? = null,
    val clip: String? = null,
    val divergence: Divergence? = null,
    val deviceModel: String? = null,
    val osBuild: String? = null,
    val chip: String? = null,
    val computeUnits: String? = null,
    val delegate: String? = null,
    val thermal: String? = null,
    val lowPower: Boolean? = null,
    val stallMs: Double? = null,
    val attempt: Int? = null,
    /** A promotion's `crash_on_last_run`: whether the app was in the foreground when the stage last recorded it; null
     *  when not known. */
    val foreground: Boolean? = null,
) {
    /** `<stage>:<cause>`. */
    val code: String get() = "$stage:$cause"

    companion object {
        const val FAILOVER = "accelerator_failover"
        const val PROMOTION_FAILED = "promotion_failed"

        /** The report's cause for a pipeline reason or a failover cause. */
        @JvmStatic
        fun cause(reason: String): String = when (reason) {
            "exception" -> "exception"
            Reasons.NON_FINITE -> "nan"
            "out_of_range" -> "out_of_range"
            "blank" -> "blank"
            "deadline", Reasons.HANG -> "timeout"
            Reasons.CRASH -> "crash_on_last_run"
            Reasons.GOLDEN_MISMATCH, Reasons.CPU_MISMATCH, Reasons.COMPILE_ERROR, Reasons.COMPILE_TIMEOUT, Reasons.LOAD_ERROR,
            Reasons.VERIFY, Reasons.NETWORK, Reasons.STORAGE -> reason
            else -> "other"
        }
    }
}
