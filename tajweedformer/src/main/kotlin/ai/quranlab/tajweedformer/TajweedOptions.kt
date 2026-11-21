package ai.quranlab.tajweedformer

import java.io.File

/**
 * The options of a [TajweedClient]. Every one is optional: `TajweedClient(context, key)` is enough.
 * On Android the app id and version are read from the app.
 *
 * SDK 4.0: anonymous analytics (daily counts and durations only, never audio, text, names or device identifiers) are
 * always on; no option turns them off. Quran Lab's signed config is the only switch.
 *
 * SDK 4.0: every app shows the same sharing sheet under the same ask rules; no option changes the sheet.
 */
public class TajweedClientOptions @JvmOverloads constructor(
    /** The app's id as registered at sdk.quranlab.ai. Null: the app's own (Android: the package name). */
    public val appId: String? = null,
    /** The app's version. Null: the app's own (Android: `versionName`). */
    public val appVersion: String? = null,
    /** Where the SDK keeps its files (the model, the signed config, the sharing queue). Null: the platform's
     *  private folder (Android: `noBackupFilesDir/quranlab`). */
    public val storage: File? = null,
    /**
     * SDK 4.0 (SPEC 17.3): the SDK's own downloads (the recited-ayah index, an update of a build) wait for Wi-Fi or
     * Ethernet. Off (the default): they go on any network unless the system says to save data (Data Saver on for the
     * app, or roaming). `client.model()` and `prefetch()` are not affected: they keep their own argument.
     */
    public val unmeteredOnly: Boolean = false,
    /**
     * SDK 4.0.1: the app's build number, sent with the app's version on every config request so that the developer's
     * model version rules at sdk.quranlab.ai can match it. Null: the app's own (Android: `longVersionCode`, `versionCode`
     * below API 28).
     */
    public val appBuild: String? = null,
)

/**
 * The options of one Session (`model.listen(options)`, `model.session(options)`). Every one is optional.
 *
 * ```
 * val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 1500))
 * ```
 */
public class TajweedSessionOptions @JvmOverloads constructor(
    /**
     * The model context (how much audio the model sees per chunk: `c8_l64` 160 ms, `c16_l64` 320 ms, `c24_l72` and
     * `c24_l256` 480 ms). Null: the context chosen at sdk.quranlab.ai for this app (Response time), else the model's
     * default. A context the model does not list is `invalid_argument`.
     */
    public val context: String? = null,
    /** The rate of the samples the app pushes (`session.push`), 8000 to 48000. `listen` sets it from the microphone
     *  or the clip and ignores this. */
    public val sampleRate: Int = 16_000,
    /**
     * The heads each chunk and the take carry: head names, or `listOf("all")`. Null: the default six (`log_probs`,
     * `inter1_log_probs`, `timing_log_probs`, `gem`, `attr_qalqalah`, `conf`).
     */
    public val heads: List<String>? = null,
    /**
     * [Backend.AUTO]: the best engine the Quran Lab Core has on this phone; in 4.0 that is its CPU engine (the
     * accelerator comes with a later Core, without a change here). [Backend.CPU]: the CPU engine only.
     */
    public val backend: Backend = Backend.AUTO,
    /** How many ranked tokens each decoded frame carries (0: only the best and the runner-up; up to 38). */
    public val topK: Int = 0,
    /**
     * Null or 0: off (a `speechEnded` event still comes after 1500 ms of silence). Set, 500 to 60000: `speechEnded`
     * comes after that many ms of silence following speech and the take finishes there (`Ended(SILENCE)`). A held
     * sound is speech, however long (the model's timing output stays on it), so a take does not end inside one.
     */
    public val endOnSilenceMs: Int? = null,
    /** `listen(from = ...)` only: [Pace.REAL_TIME] feeds the audio at the speed it was recorded, [Pace.FAST] as fast
     *  as the engine takes it. */
    public val pace: Pace = Pace.REAL_TIME,
) {
    public enum class Backend { AUTO, CPU }

    public enum class Pace { REAL_TIME, FAST }

    internal fun headSet(): Set<HeadId> {
        val h = heads ?: return Recognizer.DEFAULT_HEADS
        if (h.size == 1 && h[0] == "all") return HeadId.entries.toSet()
        return h.map { n -> HeadId.fromKey(n) ?: throw RecognizerException.InvalidAppOption("heads", "$n is not a head (${HeadId.entries.joinToString { it.key }})") }.toSet()
    }

    internal fun validate() {
        if (sampleRate !in 8_000..48_000) throw RecognizerException.UnsupportedAudioFormat("$sampleRate Hz (the SDK resamples 8000 to 48000)")
        if (topK !in 0..HeadId.LOG_PROBS.width) throw RecognizerException.InvalidAppOption("topK", "$topK is not 0..${HeadId.LOG_PROBS.width}")
        if (endOnSilenceMs != null && endOnSilenceMs != 0 && endOnSilenceMs !in 500..60_000) {
            throw RecognizerException.InvalidAppOption("endOnSilenceMs", "$endOnSilenceMs is not 0 (off) or 500..60000")
        }
        headSet()
    }

    /** The silence window that ends the take, or null when the option is off. */
    internal val silenceEndMs: Int? get() = endOnSilenceMs?.takeIf { it != 0 }
}
