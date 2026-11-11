package ai.quranlab.tajweedformer

/**
 * What happened to a take, on `session.events`. One stream per Session; it ends when the take ends.
 *
 * ```
 * session.events.collect { event ->
 *     when (event) {
 *         is TajweedEvent.SpeechEnded -> showResult()
 *         is TajweedEvent.Ended -> if (event.reason == TajweedEvent.Ended.Reason.BACKGROUND) offerToStartAgain()
 *         else -> {}
 *     }
 * }
 * ```
 */
public sealed class TajweedEvent {
    /** The model heard a sound after silence: the learner started reciting. The take goes on. */
    public class SpeechStarted internal constructor(
        /** Where in the take, in ms. */
        public val atMs: Long,
    ) : TajweedEvent()

    /**
     * Silence after speech: 1500 ms, or `endOnSilenceMs` when that option is set (the take then finishes here).
     * From the model's own output, not from the loudness of the audio.
     */
    public class SpeechEnded internal constructor(
        /** Where in the take the speech ended and the silence began, in ms. */
        public val atMs: Long,
        /** The silence that ended it, in ms. */
        public val silenceMs: Long,
    ) : TajweedEvent()

    /**
     * A `listen` take only. [Cause.INTERRUPTION] (a call, or another app took the audio): the take is paused and no
     * audio is read until [Resumed]. [Cause.ROUTE_CHANGE] (headphones in or out, another input): the microphone was
     * opened again and the take goes on.
     */
    public class Interrupted internal constructor(public val cause: Cause) : TajweedEvent() {
        public enum class Cause { INTERRUPTION, ROUTE_CHANGE }
    }

    /** An interruption ended and the take goes on, in the same Session. */
    public class Resumed internal constructor() : TajweedEvent()

    /**
     * The Backend of this take changed: the accelerator failed and the CPU engine took over from chunk [fromChunk]
     * on. The chunks from [fromChunk] may be delivered again; `decoded.settled` may drop back once.
     */
    public class BackendChanged internal constructor(
        public val backend: TajweedBackend,
        public val fromChunk: Int,
    ) : TajweedEvent()

    /**
     * The take ended without the app's `finish`. The last chunk has `decoded.isFinal`, `chunks` ends, and `finish()`
     * returns the take's Heads.
     */
    public class Ended internal constructor(
        public val reason: Reason,
        /** With [Reason.ERROR]: what failed. */
        public val error: TajweedError? = null,
    ) : TajweedEvent() {
        public enum class Reason {
            /** `endOnSilenceMs` of silence followed speech. */
            SILENCE,
            /** The app left the foreground (a `listen` take never holds the microphone in the background). */
            BACKGROUND,
            /** The page lost visibility. Raised by the web SDK only; here for the same names on every platform. */
            HIDDEN,
            /** An interruption that could not be resumed. */
            INTERRUPTED,
            /** The clip or the samples of `listen(from = ...)` ran out. */
            SOURCE_END,
            /** The take holds 20 minutes of audio, the most a take may: start a new one to go on. */
            LIMIT,
            /** A failure: see [error]. */
            ERROR,
        }
    }
}
