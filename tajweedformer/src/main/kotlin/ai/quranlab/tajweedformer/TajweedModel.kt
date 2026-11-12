package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a [TajweedModel] gets from where it was made: a [TajweedClient] (which can download more of the build, has
 * the signed config and may have an accelerator), or in the tests a verified folder (which has none of that).
 */
internal class ModelHost(
    /** Fetches what is not in the folder yet (another context, the reference data, the sample clips); null: nothing can be fetched. */
    val store: ModelStore? = null,
    val verifier: ManifestVerifier = ManifestVerifier(),
    val engine: TajweedEngineFactory = TajweedEngineFactory.EXACT,
    val soc: String? = null,
    /** The rate the engine is opened for first (the microphone's, so the first `listen` take needs no reload). */
    val warmRate: Int = 16_000,
    val microphone: () -> AudioInput? = { null },
    /** The signed config's `inference.context`, read when a Session starts. */
    val configContext: () -> String? = { null },
    /** The accelerator of a context (made and started once per key); null: the CPU only. */
    val accelerator: (ModelFolder, String) -> AcceleratorController? = { _, _ -> null },
    /** Whether the signed config forces the CPU for this build. */
    val forcedCpu: (ModelFolder) -> Boolean = { false },
    /** A take that counts reported where the learner recited (surah, first ayah, last ayah): its one report. */
    val recited: (Triple<Int, Int, Int>) -> Unit = { },
    /** A waiting chunk was dropped because the app did not collect `chunks` (counted for `diagnose()`). */
    val chunkDropped: () -> Unit = { },
    /** The audio a take may hold ([TakeLimits.MAX_TAKE_MS]; the tests of the limit set a shorter one). */
    val maxTakeMs: Long = TakeLimits.MAX_TAKE_MS,
    val onClose: (TajweedModel) -> Unit = {},
)

/**
 * One verified model build on this device: its version, contexts, tokens, calibration and reference data, sample
 * clips, and the engine that runs it (the CPU engine, and an accelerator once it passed its test). Made only by
 * `client.model()` (download, verify, open, warm): a model comes from Quran Lab's server, through the Client.
 *
 * ```
 * val model = client.model()
 * val session = model.listen()                     // the microphone
 * val session = model.session()                    // or: the app pushes audio
 * ```
 *
 * A Model has one open Session at a time (`session_open` otherwise). A new take is a new Session: the engine stays
 * loaded between takes.
 */
public class TajweedModel internal constructor(
    folder: ModelFolder,
    internal val host: ModelHost,
    first: Recognizer,
) : AutoCloseable {
    @Volatile internal var folder: ModelFolder = folder
        private set
    @Volatile private var recognizer: Recognizer? = first
    private val switching = Mutex()
    private val lock = Any()
    private var open: TajweedSession? = null
    private var starting = false
    @Volatile private var closed = false

    init {
        OpenObjects.opened(this, "Model ${folder.manifest.version ?: folder.manifest.build}")
    }

    /** The model's id, `v4.0`. */
    public val id: String get() = folder.modelId

    /** The model's version, `4.0.0`: the weights and what they recognise. */
    public val version: String get() = folder.manifest.version ?: ""

    /** The build id (a build is one uploaded folder of a version). For diagnostics only: never select by it. */
    public val build: String get() = folder.manifest.build

    /** The contexts the model ships: `c8_l64` (160 ms chunks), `c16_l64` (320 ms), `c24_l72` and `c24_l256` (480 ms). */
    public val contexts: List<String> get() = folder.contexts

    /** The context a Session gets when neither the app nor sdk.quranlab.ai chose one. */
    public val defaultContext: String get() = folder.defaultContext

    /** Audio per chunk of a context, in ms. A context the model does not list is `invalid_argument`. */
    @JvmOverloads
    public fun chunkMs(context: String = defaultContext): Int {
        if (context !in folder.contexts) throw RecognizerException.InvalidAppOption("context", "$context is not shipped (${folder.contexts})")
        return folder.chunkMs(context)
    }

    /** The vocabulary. */
    public val tokens: TajweedTokens = TajweedTokens(folder.vocabulary)

    /** `calibration.json` as shipped, read only and never applied by the SDK; null when the folder has none. */
    public val calibration: TajweedCalibration? get() = folder.calibration

    /** The expected units of each ayah, read-only data fetched on request; null when this build has none. */
    public val reference: TajweedReference? = ModelProfile.reference(folder.manifest).takeIf { it.isNotEmpty() }
        ?.let { TajweedReference(it, folder.dir, host.store, folder.manifest.tokensSha256) }

    /** The sample clips of the folder (its self-test clips); empty when they are not on this device. */
    public val sampleClips: List<TajweedClip>
        get() = try {
            val dir = File(folder.dir, SelfTestSet.DIR)
            SelfTestSet.read(dir).clips.mapNotNull { c ->
                val f = File(dir, c.file)
                if (f.isFile && c.sampleRate > 0) TajweedClip(c.id, c.kind, c.samples * 1000L / c.sampleRate, f, c.sampleRate) else null
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** The Backend the next Session will use. */
    public val backend: TajweedBackend
        get() {
            val acc = recognizer?.accelerator() ?: return TajweedBackend.CPU
            return TajweedBackend.of(acc.state.value, acc.key.soc)
        }

    internal fun backendFor(b: Backend): TajweedBackend {
        if (b == Backend.NPU) return TajweedBackend.ofChunk(b, host.soc)
        val acc = recognizer?.accelerator() ?: return TajweedBackend.CPU
        return TajweedBackend.of(acc.state.value, acc.key.soc, acc.lastCpuReason ?: "cpu")
    }

    /**
     * A Session fed by the SDK's microphone. Asks for the microphone permission when it has to (the error is
     * `microphone_denied` when it is refused), captures at the device's own rate and carries that rate to the
     * Session. A call or another app taking the audio pauses the take (`Interrupted`, then `Resumed`); headphones
     * going in or out are followed; the app leaving the foreground ends the take (`Ended(BACKGROUND)`). No audio is
     * captured before this call or after the take ends.
     */
    @JvmOverloads
    public suspend fun listen(options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession = guard {
        options.validate()
        val input = host.microphone() ?: throw RecognizerException.MicrophoneUnavailable("this platform has no microphone the SDK can open")
        reserve()
        var capture: AudioCapture? = null
        try {
            if (input.permission() != MicPermission.GRANTED && !input.requestPermission()) throw RecognizerException.MicrophoneDenied()
            val listener = TajweedSession.MicListener()
            capture = withContext(Dispatchers.IO) { input.open(listener) }
            val session = start(options, capture.sampleRate, listening = true)
            session.feedFrom(input, capture, listener)
            session
        } catch (e: Throwable) {
            try { capture?.close() } catch (_: Exception) {}
            release()
            throw e
        }
    }

    /**
     * The same path with no microphone: a Session fed by one of [sampleClips], at the speed it was recorded
     * (`pace = REAL_TIME`, the default) or as fast as the engine takes it (`pace = FAST`). The take ends when the
     * clip does (`Ended(SOURCE_END)`).
     */
    @JvmOverloads
    public suspend fun listen(from: TajweedClip, options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession = guard {
        val samples = withContext(Dispatchers.IO) { SelfTestSet.samples(from.file) }
        listen(samples, from.sampleRate, options)
    }

    /** The same with samples the app already has: mono float in -1..1 at [sampleRate] (8000 to 48000). */
    @JvmOverloads
    public suspend fun listen(from: FloatArray, sampleRate: Int, options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession = guard {
        options.validate()
        if (sampleRate !in 8_000..48_000) throw RecognizerException.UnsupportedAudioFormat("$sampleRate Hz (the SDK resamples 8000 to 48000)")
        reserve()
        try {
            val session = start(options, sampleRate, listening = true)
            session.feedFrom(from, sampleRate)
            session
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /** A Session the app pushes audio into (`session.push(samples)` at `options.sampleRate`). */
    @JvmOverloads
    public suspend fun session(options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession = guard {
        options.validate()
        reserve()
        try {
            start(options, options.sampleRate, listening = false)
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /** One open Session per Model: taken before anything else is done for a new take. */
    private fun reserve() = synchronized(lock) {
        if (closed) throw RecognizerException.SessionClosed()
        if (open != null || starting) throw RecognizerException.SessionActive()
        starting = true
    }

    private fun release() = synchronized(lock) { starting = false }

    private suspend fun start(options: TajweedSessionOptions, sampleRate: Int, listening: Boolean): TajweedSession {
        val context = Recognizer.resolveContext(folder, options.context, host.configContext())
        val rec = recognizerFor(context)
        // a first take at another rate than the engine was warmed for reopens it: off the caller's thread
        val inner = withContext(Dispatchers.IO) {
            rec.startSession(sampleRate, options.headSet(), cpuOnly = options.backend == TajweedSessionOptions.Backend.CPU)
        }
        val session = TajweedSession(this, rec, inner, options, listening)
        synchronized(lock) {
            starting = false
            open = session
        }
        return session
    }

    /**
     * The engine of [context]: the one that is loaded, or (another context, or the signed config now forces the CPU
     * or no longer does) a new one in its place. Another context's files are downloaded first when they are not on
     * the device.
     */
    private suspend fun recognizerFor(context: String): Recognizer = switching.withLock {
        val now = recognizer
        val wantCpu = host.forcedCpu(folder)
        if (now != null && now.context == context && (now.options.backend == BackendPreference.CPU) == wantCpu) return now
        withContext(Dispatchers.IO) {
            if (context !in folder.availableContexts) {
                val store = host.store ?: throw RecognizerException.ModelMissing("context $context is not in this folder")
                folder = runInterruptible { store.ensureCpu(context) }
            }
            now?.close()
            recognizer = null
            openRecognizer(folder, host, context, wantCpu).also { recognizer = it }
        }
    }

    internal fun sessionEnded(s: TajweedSession) = synchronized(lock) { if (open === s) open = null }

    /** The context of the engine that is loaded (the last Session's, or the one the Model was opened with). */
    internal fun contextInForce(): String? = recognizer?.context

    /** The app left the foreground: an open `listen` take ends. */
    internal fun background() {
        synchronized(lock) { open }?.background()
    }

    /** Whether a take is open (the signed config is refreshed more often while one is). */
    internal val sessionOpen: Boolean get() = synchronized(lock) { open != null }

    /** Ends the open Session (as `cancel()`), releases the engine and any accelerator. The files stay on disk. */
    override fun close() {
        val s = synchronized(lock) {
            if (closed) return
            closed = true
            open
        }
        s?.cancel()
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        reference?.close()
        host.onClose(this)
        OpenObjects.closed(this)
    }

    internal companion object {
        /**
         * A verified model folder opened with no Client, for the tests and the local proof only (SPEC 15.1: no public
         * call hands the SDK a model; an app gets one from `client.model()`). Verified as a downloaded one is: the
         * manifest's signature with [ModelHost.verifier], then every file's size and SHA-256.
         */
        suspend fun openVerifiedFolder(dir: File, host: ModelHost, context: String? = null): TajweedModel = guard {
            withContext(Dispatchers.IO) {
                val folder = ModelFolder.open(dir, host.verifier)
                of(folder, host, context)
            }
        }

        /** The Model of a verified folder: the engine of [context] (null: the config's, else the default) opened and warmed. */
        internal fun of(folder: ModelFolder, host: ModelHost, context: String? = null): TajweedModel {
            val ctx = Recognizer.resolveContext(folder, context, host.configContext()).let { c ->
                // a context whose files are not here yet falls back to one that is (the Session fetches the other on use)
                if (c in folder.availableContexts) c else folder.availableContexts.first()
            }
            return TajweedModel(folder, host, openRecognizer(folder, host, ctx, host.forcedCpu(folder)))
        }

        private fun openRecognizer(folder: ModelFolder, host: ModelHost, context: String, cpuOnly: Boolean): Recognizer {
            val acc = if (cpuOnly) null else host.accelerator(folder, context)
            return Recognizer.open(folder, Recognizer.Options(
                context = context, sampleRate = host.warmRate, backend = if (cpuOnly) BackendPreference.CPU else BackendPreference.AUTO,
                accelerator = acc, soc = host.soc, selfTest = true, engine = host.engine,
            )).also { it.maxTakeMs = host.maxTakeMs }
        }
    }
}
