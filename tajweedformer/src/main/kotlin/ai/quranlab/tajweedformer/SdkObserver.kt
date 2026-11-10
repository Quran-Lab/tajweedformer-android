package ai.quranlab.tajweedformer

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the SDK does, for code that watches it: the SDK's cloud module (anonymous telemetry, opt-in
 * recording sharing) and apps that want their own logs. Every method has an empty default, so an
 * observer overrides only what it needs.
 *
 * Register with `TajweedFormer.events.add(observer)`. Calls come on the SDK's own threads (the
 * recognizer's engine thread for audio and chunks, a download thread for downloads), in order per
 * source, and must return quickly: copy what you need and hand heavy work (encoding, uploading) to
 * your own thread. An observer that throws is ignored for that call; the SDK never fails because of
 * an observer.
 *
 * Nothing here carries recognised text or identifiers of the user or the device beyond the SoC id;
 * [onAudio] carries the audio itself and is meant only for recording sharing behind the user's
 * consent.
 */
interface SdkObserver {
    /** A take started on a recognizer. */
    fun onSessionStart(session: SessionInfo) {}

    /** A take ended (finished, cancelled or failed), with its timing. */
    fun onSessionEnd(session: SessionInfo, stats: SessionStats) {}

    /** One engine chunk of a take: its compute and audio time, and its decoded layer (for token
     *  margins and confidences). [decoded] is computed only while an observer is registered. */
    fun onChunk(session: SessionInfo, chunk: ChunkStats, decoded: Decoded) {}

    /** A take was scored ([TajweedSession.finishAndScore] or [TajweedRecognizer.score] during it). */
    fun onScored(session: SessionInfo, result: TajweedResult) {}

    /** A recognizer opened its engines (the CPU engine, and the NPU when chosen). */
    fun onModelLoad(load: ModelLoadInfo) {}

    /** The accelerator's state changed: preparing (with its stage), ready (with its time to ready), disabled (with
     *  the reason), or cpu (none for this device). */
    fun onAcceleratorState(state: ai.quranlab.tajweedformer.accel.AcceleratorState, key: ai.quranlab.tajweedformer.accel.AcceleratorKey) {}

    /** A promotion attempt ended: promoted, or failed at a stage (the `promotions` telemetry; a failure is an incident). */
    fun onPromotion(event: PromotionEvent) {}

    /** A session switched from the accelerator to the CPU (the `promotions` telemetry's failovers, and an incident). */
    fun onFailover(event: AcceleratorFailoverEvent) {}

    /**
     * The audio of a take exactly as the app pushed it, before any resampling: mono float in -1..1 at
     * [sampleRate] (16000 when the app pushes 16 kHz, as recommended; 8000 to 48000 otherwise). The
     * array is the SDK's own copy for this call: the observer may keep it.
     */
    fun onAudio(session: SessionInfo, samples: FloatArray, sampleRate: Int) {}

    /** The backend a recognizer runs, and why (see [BackendChoice.reason]). Called when a recognizer
     *  is made and again whenever it falls back to the CPU during its life. */
    fun onBackendSelected(choice: BackendChoice) {}

    /** Bytes of a model file arrived. Called at most every 256 KB per file and at the end. */
    fun onDownloadProgress(progress: DownloadProgress) {}

    /** A model file finished downloading: verified and in place, or failed (and deleted). */
    fun onDownloadFinished(result: DownloadResult) {}

    /** A backend's self-test ran (the CPU engine's first, then the NPU's against it). */
    fun onSelfTest(result: SelfTestResult) {}

    /** An error the SDK handled itself (a fallback, a failed optional download). */
    fun onError(where: String, error: Throwable) {}
}

/** Identifies one take for observers: a process-unique number, the model and context, the backend,
 *  and the settings in force when it started. */
data class SessionInfo(
    val id: Long,
    val modelId: String,
    val context: String,
    val backend: Backend,
    /** The rate the app pushes audio at. */
    val sampleRate: Int,
    /** Wall-clock start, ms since the epoch. */
    val startedAtMs: Long,
    /** The model folder's manifest revision. */
    val revision: String = "",
    /** The device's SoC id, when known. */
    val soc: String? = null,
    /** The global site preset in force (`strict`, `balanced`, ...), or `custom` when a class has a raw threshold. */
    val preset: String = "",
    /** The per-word level (`strict`, `general`, `beginner`) or `custom`. */
    val wordLevel: String = "",
    val wordThreshold: Double = 0.0,
    /** Classes with an app or portal override. */
    val siteOverrides: Int = 0,
    /** Checks on (`letters`, `vowels`, `length`, `madd`, `gemination`, `qalqalah`, `lazim`). */
    val checksOn: Set<String> = emptySet(),
    /** SHA-256 of the decoded int8 weight file (layers.zfw). */
    val weightsSha256: String? = null,
)

/** One engine chunk's timing. */
data class ChunkStats(val index: Int, val computeMs: Double, val audioMs: Double, val backend: Backend)

/** A recognizer's engines opened in [ms]; [cold] for the first open in this process. */
data class ModelLoadInfo(val revision: String, val context: String, val backend: Backend, val ms: Double, val cold: Boolean, val soc: String? = null)

/** A take's timing. RTF = compute time / audio time (below 1 is faster than real time). */
data class SessionStats(
    val audioSeconds: Double,
    val computeSeconds: Double,
    val chunks: Int,
    /** finished, cancelled or failed */
    val outcome: String,
    /** The backend that produced the last chunk (an NPU take can end on the CPU after a fallback). */
    val backend: Backend,
    /** The take was scored (its feedback was produced). */
    val scored: Boolean = false,
    /** The score flagged at least one site or word. */
    val flagged: Boolean = false,
) {
    val rtf: Double get() = if (audioSeconds > 0) computeSeconds / audioSeconds else 0.0
    val meanChunkMs: Double get() = if (chunks > 0) 1000.0 * computeSeconds / chunks else 0.0
}

/** The compute backends of the Android SDK. */
enum class Backend(val key: String) {
    /** The exact C engine on the CPU: bit-exact on every device, always available, the fallback. */
    CPU("cpu"),
    /** The SoC's NPU through LiteRT with the vendor dispatch (int16 I/O), checked against the CPU. */
    NPU("npu"),
}

/**
 * A recognizer's backend and the reason for it. [reason] is a stable short code for telemetry:
 * `npu` (the NPU was promoted), `cpu_requested` (the app asked for the CPU), `no_npu_build` (no NPU file for
 * this SoC and context), `soc_unknown`, `runtime_missing` (no LiteRT or vendor dispatch library in the app),
 * `preparing` (the promotion pipeline has not finished), a pipeline failure reason (`compile_error`,
 * `golden_mismatch`, ...), `failover` (the key was demoted by a failover), `failover_<cause>` (this session
 * switched to the CPU: `exception`, `non_finite`, `out_of_range`, `blank`, `deadline`), `thermal`, `memory`,
 * `denylist`, `forced_cpu`; [detail] is free text for logs.
 */
data class BackendChoice(
    val backend: Backend,
    val reason: String,
    val detail: String = "",
    /** The device's SoC id (`tensor_g4`, `exynos2600`), or null when unknown. */
    val soc: String? = null,
    val context: String? = null,
    /** The model revision. */
    val revision: String? = null,
) {
    /** A move away from a backend that was there (not a plain CPU choice): the NPU failed to load,
     *  its self-test failed, its runtime is missing, or it failed mid-take. */
    val isFallback: Boolean get() = backend == Backend.CPU && (reason in FALLBACK_REASONS || reason.startsWith("failover_"))

    companion object {
        @JvmField val FALLBACK_REASONS = setOf("compile_error", "compile_timeout", "load_error", "crash", "hang", "non_finite",
            "golden_mismatch", "cpu_mismatch", "failover", "runtime_missing", "thermal", "memory", "denylist")
    }
}

data class DownloadProgress(
    /** The manifest path (stored bytes, `.gz` for compressed files). */
    val path: String,
    val role: String,
    /** Stored bytes on disk so far, including a resumed part. */
    val bytes: Long,
    val total: Long,
)

data class DownloadResult(
    val path: String,
    val role: String,
    /** Bytes moved over the network by this download (0 when the file was already in place). */
    val networkBytes: Long,
    /** The stored size. */
    val totalBytes: Long,
    /** Bytes that were already in `<path>.part` from an earlier, cut download. */
    val resumedFrom: Long,
    val ok: Boolean,
    val error: String? = null,
    val millis: Long = 0,
    /** The manifest revision the file belongs to. */
    val revision: String = "",
)

data class SelfTestResult(
    val backend: Backend,
    val passed: Boolean,
    /** What was compared and how it came out, for logs. */
    val detail: String,
    val millis: Long,
    val soc: String? = null,
    val context: String? = null,
    val revision: String? = null,
)

/** The registry behind [TajweedFormer.events]. */
object SdkEvents {
    private val observers = CopyOnWriteArrayList<SdkObserver>()

    @JvmStatic fun add(observer: SdkObserver) {
        observers.addIfAbsent(observer)
    }

    @JvmStatic fun remove(observer: SdkObserver) {
        observers.remove(observer)
    }

    @JvmStatic fun clear() = observers.clear()

    internal val active: Boolean get() = observers.isNotEmpty()

    internal fun emit(call: (SdkObserver) -> Unit) {
        for (o in observers) {
            try {
                call(o)
            } catch (_: Throwable) {
                // an observer never breaks the SDK
            }
        }
    }
}
