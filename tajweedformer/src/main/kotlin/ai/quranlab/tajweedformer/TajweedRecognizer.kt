package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.FailoverEngine
import ai.quranlab.tajweedformer.accel.FailoverEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The tajweed settings layers a score is made with: the portal's block (as JSON text), the app's
 * options, and whether the host app is a debug build. [resolve] gives what is in force.
 */
class TajweedConfig @JvmOverloads constructor(
    /** The `tajweed` block of the signed /v1/config, as JSON text; null for none. */
    val remoteJson: String? = null,
    val app: TajweedOptions = TajweedOptions(),
    /** Only a debug build's app code can turn on a check calibration.json marks `off`. */
    val debugBuild: Boolean = false,
    /** Where the heads come from: INT8 for the exact engine's, FP32 for an NPU's, CoreML's or a
     *  server's. Null: the scorer's default for its source. */
    val backend: TajweedBackend? = null,
    /** The app's per-word level or threshold (null: the portal's, else calibration's). */
    val word: WordOption? = null,
) {
    val remote: RemoteTajweed by lazy { RemoteTajweed.parseJson(remoteJson) }

    @JvmOverloads
    fun resolve(calibration: Calibration, defaultBackend: TajweedBackend = TajweedBackend.INT8): ResolvedTajweedSettings =
        TajweedSettings.resolve(calibration, remote, app, debugBuild, backend ?: defaultBackend)
}

/** The engine under a [TajweedRecognizer]: the exact C engine through JNI, the NPU backend, or a
 *  test's own. */
interface TajweedEngine : AutoCloseable {
    /** Audio per chunk in ms (320 at c16, 480 at c24). */
    val chunkMs: Int

    /** The backend that computes the heads now. */
    val backend: Backend get() = Backend.CPU

    /** A stream for one take at a time; the exact engine allows one live stream per process. */
    fun createStream(): TajweedEngineStream

    /** A stream that computes at least [heads] (the exact engine computes only those and the scorer's;
     *  other engines may compute more). */
    fun createStream(heads: Set<TajweedHead>): TajweedEngineStream = createStream()

    /** Scores the heads of a take of surah:ayah (the whole ayah) under [config]; [settings] is its
     *  resolution, whose backend the engine is given explicitly. */
    fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult

    /** The ayah's expected tj3 tokens (the continuing form), from the model's expected table. */
    fun expectedTokens(surah: Int, ayah: Int): IntArray

    /** The word (0-based) of each expected token; word boundaries belong to the word before. */
    fun expectedWords(surah: Int, ayah: Int): IntArray = throw UnsupportedOperationException("expectedWords")
}

interface TajweedEngineStream : AutoCloseable {
    /** The backend computing this take now. */
    val backend: Backend get() = Backend.CPU

    /** Mono float samples in -1..1 at the recognizer's sample rate; the chunks they complete. */
    fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads>

    /** The end of the take (with the flush the model is decoded with); the chunks it completes. */
    fun finish(): List<TajweedHeads>

    /** A new take: every cache back to the start. */
    fun reset()

    /** Every chunk of the take when the stream replaced earlier chunks (a fallback replay), else null. */
    fun takeHeads(): List<TajweedHeads>? = null

    /** After a [pushAudio] or [finish]: the take's chunk index its chunks start at when they REDO chunks already
     *  delivered (a confirmed accelerator disagreement: the CPU's chunks from the first suspicious one), else -1. */
    fun takeRedo(): Int = -1
}

/** Makes the CPU engine for a model and context. */
fun interface TajweedEngineFactory {
    fun open(model: TajweedModel, context: String, options: TajweedRecognizer.Options): TajweedEngine

    companion object {
        /**
         * The exact engine through JNI (native/tj_jni.c over the engine's zf_tajweed.h, API 2).
         * Throws [RecognizerException.EngineNotAvailable] when the native library lacks it.
         */
        @JvmField
        val EXACT: TajweedEngineFactory = TajweedEngineFactory { model, context, options ->
            if (!ai.quranlab.tajweedformer.engine.TajweedJni.available) {
                throw RecognizerException.EngineNotAvailable("libtajweedformer_jni was built without the engine's zf_tajweed")
            }
            model.contextFiles(context) // a context that is not downloaded fails here, clearly
            ai.quranlab.tajweedformer.engine.ExactTajweedEngine(model, context, options)
        }
    }
}

/**
 * Streaming TajweedFormer V4: audio in; per chunk every requested head (the raw layer); the decoded
 * layer ([decode]: top-1 and runner-up per frame, transcript with confidences, words, times); and the
 * tajweed layer for a take of a known ayah ([TajweedSession.finishAndScore]: site verdicts, madd,
 * qalqalah and gemination evidence, per-word scores).
 *
 * ```
 * val tj = TajweedRecognizer.create(model, TajweedRecognizer.Options(debugBuild = TajweedAndroid.isDebuggable(context)))
 * tj.remoteConfig = portalTajweedJson                        // optional; app options still win
 * val session = tj.startSession()
 * launch { session.chunks.collect { chunk -> draw(chunk.heads, chunk.decode(tj.model.vocabulary)) } }
 * session.append(samples)                                    // 16 kHz mono float, as it arrives
 * val result = session.finishAndScore(surah = 1, ayah = 2)
 * result.flaggedWords.forEach { show(it.word, it.score, it.best) }
 * ```
 *
 * Backends: the exact CPU engine serves from the first session. With an [Options.accelerator] the NPU
 * is promoted in the background (download, compile, warm-up, the golden test against the CPU engine) and a
 * NEW session runs on it once it is ready (never a session in progress). While it serves, the CPU engine's
 * weights are released ([CpuSlot]); a chunk the NPU fails (an exception, NaN or inf, values out of range, a
 * missed limit) switches the take to the CPU within that call ([FailoverEngine]: the recent audio replayed, the
 * failed chunk redone). Blank output over audio is checked on the CPU off the audio path and switches the take
 * only when the CPU writes tokens there (the chunks from that point are delivered again, [TajweedChunk.replaces]).
 * What a failure does to later sessions depends on its cause ([ai.quranlab.tajweedformer.accel.AcceleratorController]).
 * [backendChoice] says which backend the last session got and why; every choice and switch goes to
 * [SdkObserver.onBackendSelected].
 *
 * One session at a time (the exact engine runs one live stream per process). Every engine call runs
 * on the recognizer's own thread, in order; the public calls are safe from any thread.
 */
class TajweedRecognizer private constructor(
    val model: TajweedModel,
    /** The context the engine runs ("c16_l64"). */
    val context: String,
    val options: Options,
    /** The exact CPU engine, the accelerator's standby (its weights released while the accelerator serves). */
    private val engine: CpuSlot,
    choice: BackendChoice,
    /** The CPU self-test, when it ran ([Options.selfTest]). */
    val cpuSelfTest: SelfTestResult?,
) : AutoCloseable {
    data class Options(
        /** A calibration.json context; null for [TajweedOptions.context], else the model's default. */
        val context: String? = null,
        /** The app's tajweed options (presets, per-class overrides, checks). */
        val tajweed: TajweedOptions = TajweedOptions(),
        /** The app's per-word level or threshold; null for the portal's, else calibration's. */
        val word: WordOption? = null,
        /** Whether the host app is a debug build (on Android: `TajweedAndroid.isDebuggable(context)`).
         *  False, the default, refuses every `off` check. */
        val debugBuild: Boolean = false,
        /** The rate audio is pushed at: 16000, or 8000 .. 48000 through the engine's resampler. */
        val sampleRate: Int = 16_000,
        /** Engine threads; 0 = the default: on Android the big + mid cores capped at 4 ([CoreTopology]),
         *  elsewhere the engine's own choice. */
        val threads: Int = 0,
        /** Engine chunks run together, for stored audio; 1 for live audio. */
        val batch: Int = 1,
        /** Leave the engine's threads unpinned (true) or pin them (false); null: the engine's platform
         *  default (pinned on Android, unpinned on desktops). Timing only, never a bit. */
        val noPin: Boolean? = null,
        /** The decoded layer's depth per head ([Decoder]): how many ranked entries per frame (2 for log_probs
         *  by default; up to 38 for the token heads = the full ranked list; the class heads up to their
         *  class count). boundary and conf have no ranking (they are single logits). */
        val topK: Map<TajweedHead, Int> = Decoder.DEFAULT_TOP_K,
        /** The heads each chunk carries (the raw layer is opt-in per head). The scorer always gets
         *  what it needs. */
        val heads: Set<TajweedHead> = DEFAULT_HEADS,
        /** Every head, whatever [heads] says. */
        val allHeads: Boolean = false,
        /** AUTO uses the [accelerator] when it is ready; CPU never does. */
        val backend: BackendPreference = BackendPreference.AUTO,
        /** The NPU, prepared in the background (TajweedFormerClient makes it); null: CPU only. */
        val accelerator: AcceleratorController? = null,
        /** The device's SoC id, for the observer. */
        val soc: String? = null,
        /** Run the CPU self-test at open (TajweedFormerClient always does). */
        val selfTest: Boolean = false,
        val engine: TajweedEngineFactory = TajweedEngineFactory.EXACT,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tajweedformer-v4-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var session: TajweedSession? = null
    private var closed = false

    /** The backend the last session got and why (at open: the CPU and the accelerator's state). */
    @Volatile
    var backendChoice: BackendChoice = choice
        private set

    /** The backend of the last session (the CPU until a session runs on the accelerator). */
    val backend: Backend get() = backendChoice.backend

    private fun choose(c: BackendChoice) {
        val prev = backendChoice
        backendChoice = c
        if (prev.backend != c.backend || prev.reason != c.reason) SdkEvents.emit { it.onBackendSelected(c) }
    }

    /** The accelerator's session engine, kept while the same accelerator stays promoted. */
    @Volatile private var accEngine: FailoverEngine? = null

    /** The accelerator's session engine of the last accelerator session (measurements). */
    internal val acceleratorEngine: FailoverEngine? get() = accEngine

    /** The CPU standby (tests look at its weights being loaded or released). */
    internal val cpuStandby: CpuSlot get() = engine

    /** Release the CPU weights while the accelerator serves (tests keep them to measure the warm switch). */
    @Volatile internal var releaseCpuForAccelerator = true

    private fun accelerator(): AcceleratorController? = options.accelerator.takeIf { options.backend != BackendPreference.CPU }

    /** The engine for a new session: the accelerator's when it is ready and nothing prefers the CPU. */
    private fun engineForSession(): TajweedEngine {
        val acc = accelerator() ?: return engine
        val e = acc.engineForNextSession(engine, options.sampleRate, accEngine) { ev -> switched(ev) }
        if (e != null) {
            accEngine = e
            // the warm-up at take start: one prediction of silence on the accelerator's thread while the first
            // chunk's audio arrives (a powered-down accelerator wakes up before the first real chunk)
            e.warmUp()
            choose(BackendChoice(Backend.NPU, "npu", "", acc.key.soc, context, model.manifest.revision))
            // no second resident copy of the weights while the accelerator serves: a failover loads them again
            if (releaseCpuForAccelerator) executor.execute { try { engine.release() } catch (_: Exception) {} }
            return e
        }
        choose(BackendChoice(Backend.CPU, acc.lastCpuReason ?: "cpu", "", acc.key.soc, context, model.manifest.revision))
        return engine
    }

    /** A take on the accelerator switched to the CPU: the app sees only the backend change. */
    private fun switched(e: FailoverEvent) {
        choose(BackendChoice(Backend.CPU, "failover_${e.cause}", e.error?.javaClass?.simpleName ?: "", options.soc, context, model.manifest.revision))
    }

    /** How a take's heads are scored: the accelerator's thresholds for a take that ran mostly there, else the CPU's. */
    internal fun scoringOf(session: TajweedEngine, stream: TajweedEngineStream, chunks: Int): TajweedBackend {
        val f = session as? FailoverEngine ?: return TajweedBackend.INT8
        return if (stream.backend == Backend.NPU || f.mostlyAccelerator(stream, chunks)) f.headsBackend else TajweedBackend.INT8
    }

    /** The portal's `tajweed` block (JSON text), or null. Never throws: an invalid field is dropped
     *  (see [settings]'s `dropped`). App options still win over it. */
    @Volatile
    var remoteConfig: String? = null

    /** Called at every session start to refresh [remoteConfig] (the cloud module's current
     *  `tajweed` block: portal changes apply at the next session). Null: [remoteConfig] stays. */
    @Volatile
    var remoteConfigProvider: (() -> String?)? = null

    /** The layers in force; [context] counts as the app's. */
    val config: TajweedConfig
        get() = TajweedConfig(remoteConfig, options.tajweed.copy(context = context), options.debugBuild)

    /** The site settings in force for this recognizer's context (CPU heads). */
    val settings: ResolvedTajweedSettings get() = config.resolve(model.calibration)

    /** The per-word setting in force (app > portal > calibration). */
    val wordSettings: WordSettings
        get() = WordSettings.resolve(WordCalibration.of(model.calibration, context), remoteConfig, options.word)

    /** The context the portal and app would choose now (it may differ from [context] when the
     *  portal moved it after this recognizer was made: make a new recognizer to follow it). */
    val requestedContext: String
        get() = TajweedConfig(remoteConfig, options.tajweed.copy(context = options.context ?: options.tajweed.context), options.debugBuild)
            .resolve(model.calibration).context

    val chunkMs: Int get() = engine.chunkMs

    /** The heads delivered per chunk. */
    val heads: Set<TajweedHead> get() = if (options.allHeads) TajweedHead.entries.toSet() else options.heads

    /** The decoded layer of [heads] (a chunk's, or a whole take's). */
    fun decode(heads: TajweedHeads, frameOffset: Int = 0): Decoded = Decoder.decode(heads, model.vocabulary, frameOffset, options.topK)

    /** Starts a take. Throws [RecognizerException.SessionActive] while another is running. */
    fun startSession(): TajweedSession {
        synchronized(lock) {
            check(!closed) { "the recognizer is closed" }
            if (session != null) throw RecognizerException.SessionActive()
            remoteConfigProvider?.let { p -> try { remoteConfig = p() } catch (_: Exception) {} }
            val sessionEngine = engineForSession()
            val info = sessionInfo()
            val s = TajweedSession(this, info, sessionEngine)
            session = s
            // the pipeline's golden and warm-up stages yield to sessions (and a first install starts it after one)
            accelerator()?.sessionStarted()
            SdkEvents.emit { it.onSessionStart(info) }
            return s
        }
    }

    private fun sessionInfo(): SessionInfo {
        val st = settings
        val w = wordSettings
        val remote = config.remote.overrides.orEmpty()
        val classesOverridden = options.tajweed.overrides.keys + remote.keys
        val rawThreshold = (options.tajweed.overrides.values + remote.values).any { it is ClassOverride.Threshold }
        val checks = HashSet<String>()
        for ((cls, site) in st.sites) if (site.enabled) checks.add(cls.check.key)
        if (st.madd.enabled) checks.add(TajweedCheck.MADD.key)
        if (st.lazim.enabled) checks.add(TajweedCheck.LAZIM.key)
        if (st.gemination.enabled) checks.add(TajweedCheck.GEMINATION.key)
        if (st.qalqalah.enabled) checks.add(TajweedCheck.QALQALAH.key)
        val overrides = classesOverridden.size
        return SessionInfo(sessionIds.incrementAndGet(), model.modelId, context, backend, options.sampleRate, System.currentTimeMillis(),
            revision = model.manifest.revision, soc = options.soc,
            preset = if (rawThreshold) "custom" else st.preset.key,
            wordLevel = w.level?.key ?: "custom", wordThreshold = w.threshold,
            siteOverrides = minOf(overrides, 8), checksOn = checks,
            weightsSha256 = model.manifest.file("layers.zfw")?.localSha256)
    }

    internal fun release(s: TajweedSession) {
        val ended = synchronized(lock) {
            if (session === s) {
                session = null
                true
            } else {
                false
            }
        }
        if (ended) accelerator()?.sessionEnded()
    }

    /** Batched appends (TajweedSession.append): off only in the tests that compare with the unbatched path. */
    @Volatile internal var batchAppends = true

    /** Per engine: a lower bound of the input samples a take needs before its first chunk runs, learned
     *  from the first take (stream reset makes it the same every take; the rate and context are fixed). */
    internal val firstChunkNeed = java.util.concurrent.ConcurrentHashMap<TajweedEngine, Long>()

    internal suspend fun <T> onEngine(body: (TajweedEngine) -> T): T = withContext(dispatcher) { body(engine) }

    /** Scores heads that came from anywhere with the same layout as a take of surah:ayah, under the
     *  settings in force. [backend]: INT8 for the exact engine's heads, FP32 for heads from an NPU,
     *  CoreML or a server. Adds the per-word verdicts. */
    suspend fun score(heads: TajweedHeads, surah: Int, ayah: Int, backend: TajweedBackend = TajweedBackend.INT8): TajweedResult =
        scoreAs(heads, surah, ayah, backend, if (backend == TajweedBackend.INT8) Backend.CPU else Backend.NPU)

    internal suspend fun scoreAs(heads: TajweedHeads, surah: Int, ayah: Int, backend: TajweedBackend, source: Backend): TajweedResult {
        val base = config
        val cfg = TajweedConfig(base.remoteJson, base.app, base.debugBuild, backend, options.word)
        val settings = cfg.resolve(model.calibration)
        val words = wordSettings
        return onEngine { e ->
            val r = e.score(heads, cfg, settings, surah, ayah)
            val n = try { (e.expectedWords(surah, ayah).maxOrNull() ?: -1) + 1 } catch (_: UnsupportedOperationException) { 0 }
            r.withWords(n, words, source)
        }
    }

    /** The ayah's expected tj3 tokens (names: [TajweedModel.vocabulary]). */
    suspend fun expectedTokens(surah: Int, ayah: Int): IntArray = onEngine { it.expectedTokens(surah, ayah) }

    /** The word of each expected token of the ayah. */
    suspend fun expectedWords(surah: Int, ayah: Int): IntArray = onEngine { it.expectedWords(surah, ayah) }

    override fun close() {
        val s: TajweedSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            s = session
        }
        s?.cancel()
        executor.execute { engine.close() }
        executor.shutdown()
        scope.cancel()
    }

    companion object {
        private val counter = AtomicInteger()
        private val sessionIds = AtomicLong()
        private val loads = AtomicInteger()

        /** The heads delivered by default: what the scorer reads, plus conf for the decoded layer's
         *  confidences. */
        @JvmField
        val DEFAULT_HEADS: Set<TajweedHead> = TajweedHead.SCORER_HEADS + TajweedHead.CONF

        /** Opens the engine (and the NPU when set up) for [model] on [Dispatchers.IO]. */
        suspend fun create(model: TajweedModel, options: Options = Options()): TajweedRecognizer =
            withContext(Dispatchers.IO) { open(model, options) }

        /** The same as [create], blocking. */
        @JvmStatic
        @JvmOverloads
        fun open(model: TajweedModel, options: Options = Options()): TajweedRecognizer {
            val appContext = options.context ?: options.tajweed.context
            if (appContext != null && appContext !in model.calibration.contexts) {
                throw RecognizerException.InvalidAppOption("context", "$appContext is not shipped (${model.contexts})")
            }
            if (options.sampleRate !in 8_000..48_000) {
                throw RecognizerException.UnsupportedAudioFormat("${options.sampleRate} Hz (the resampler takes 8000 to 48000)")
            }
            // validate the app's options against the model now, not at the first score
            TajweedSettings.resolve(model.calibration, RemoteTajweed.ABSENT, options.tajweed, options.debugBuild)
            WordSettings.resolve(WordCalibration.of(model.calibration), null, options.word)
            val context = appContext ?: model.defaultContext
            val t0 = System.nanoTime()
            val cpu = options.engine.open(model, context, options)
            val rec = try {
                choose(model, context, options, cpu)
            } catch (e: Throwable) {
                cpu.close()
                throw e
            }
            val load = ModelLoadInfo(model.manifest.revision, context, rec.backend, (System.nanoTime() - t0) / 1e6,
                loads.getAndIncrement() == 0, options.soc)
            SdkEvents.emit { it.onModelLoad(load) }
            return rec
        }

        private fun choose(model: TajweedModel, context: String, options: Options, cpu: TajweedEngine): TajweedRecognizer {
            // the CPU self-test runs the built-in 16 kHz clip: at another app rate, on a 16 kHz engine of its own
            val cpuTest = if (options.selfTest) {
                val testCpu = if (options.sampleRate == 16_000) cpu else options.engine.open(model, context, options.copy(sampleRate = 16_000))
                try {
                    SelfTest.cpu(testCpu, model, context)
                } finally {
                    if (testCpu !== cpu) testCpu.close()
                }
            } else null
            val acc = options.accelerator
            val reason = when {
                options.backend == BackendPreference.CPU -> "cpu_requested"
                acc == null -> "no_accelerator"
                else -> acc.state.value.let { s -> (s as? ai.quranlab.tajweedformer.accel.AcceleratorState.Disabled)?.reason
                    ?: (s as? ai.quranlab.tajweedformer.accel.AcceleratorState.Cpu)?.reason ?: s.key }
            }
            val c = BackendChoice(Backend.CPU, reason, "", options.soc, context, model.manifest.revision)
            SdkEvents.emit { it.onBackendSelected(c) }
            // a scoring handle without weights exists for the exact engine only: other engines stay loaded
            val scorer: (() -> TajweedEngine)? = if (options.engine === TajweedEngineFactory.EXACT) {
                { ai.quranlab.tajweedformer.engine.ExactTajweedEngine(model.dir, context, loadEngine = false) }
            } else null
            val slot = CpuSlot(cpu, { options.engine.open(model, context, options) }, scorer)
            return TajweedRecognizer(model, context, options, slot, c, cpuTest?.result)
        }
    }
}

/**
 * One take (one ayah, or part of one): audio in, each chunk's heads out of [chunks], and every head
 * of the take at the end.
 */
class TajweedSession internal constructor(
    private val recognizer: TajweedRecognizer,
    val info: SessionInfo,
    /** The engine this take runs on, chosen at its start (the CPU, or the accelerator when ready). */
    private val engine: TajweedEngine,
) {
    private val events = Channel<TajweedChunk>(Channel.UNLIMITED)
    private val kept = ArrayList<TajweedHeads>()
    private var stream: TajweedEngineStream? = null
    private val lock = Any()
    private var done = false
    private var index = 0
    private var frame = 0
    private var samples = 0L
    private var compute = 0.0
    private var lastBackend = info.backend
    private val select = recognizer.heads
    // Batched appends: audio waits here (the caller's side) until the engine can run a chunk with it.
    private val appendLock = Mutex()
    private val pending = FloatArray(maxOf(16_000, (engine.chunkMs.toLong() * info.sampleRate / 1000).toInt() + info.sampleRate / 10))
    private var pendingN = 0
    private var taken = 0L
    /** Hops onto the engine thread (for the tests). */
    internal var hops = 0
    @Volatile private var scored = false
    /** The thresholds the finished take is scored with. */
    @Volatile private var scoring = TajweedBackend.INT8
    @Volatile private var flagged = false

    /** Each chunk's heads (those the recognizer's options ask for) as the engine runs it. Completes
     *  after [finish]; fails with [RecognizerException.Cancelled] after [cancel]. Collect it once. */
    val chunks: Flow<TajweedChunk> = events.consumeAsFlow()

    val isClosed: Boolean get() = synchronized(lock) { done }

    /**
     * Mono float samples in -1..1 at the recognizer's sample rate. Returns once the engine has run every
     * chunk they complete (chunks and observer callbacks happen before it returns, as they always did).
     *
     * Audio that cannot complete a chunk yet waits in the session's buffer (copied; no allocation per
     * call) and goes to the engine with the audio that does, so a 20 ms piece costs a copy instead of a
     * hop onto the engine thread (about 0.5 ms on the Alcatel 1, 14 times a chunk at 20 ms pieces). The
     * hop is taken as soon as the audio could complete the next chunk: the bound is the samples the first
     * chunk needed (learned from the first take, unbatched until then) plus one chunk's advance per chunk
     * since, less a margin, so a chunk never runs later than without the buffer. The chunks, their heads
     * and the tokens are the same bits as without it.
     */
    suspend fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (offset < 0 || length < 0 || offset + length > samples.size) throw IndexOutOfBoundsException("offset $offset length $length in ${samples.size}")
        appendLock.withLock {
            if (isClosed) throw RecognizerException.SessionClosed()
            taken += length
            val need = nextNeed()
            if (need >= 0 && taken < need && length <= pending.size - pendingN) {
                System.arraycopy(samples, offset, pending, pendingN, length)
                pendingN += length
                return
            }
            hop(samples, offset, length, finish = false)
        }
    }

    /** Input samples after which the next chunk may run (a lower bound); -1: hop at every append. */
    private fun nextNeed(): Long {
        if (!recognizer.batchAppends || engine.chunkMs <= 0) return -1
        val first = recognizer.firstChunkNeed[engine] ?: return -1
        // at 16 kHz the fbank makes it exact (one chunk's advance is exactly that many samples more); the
        // resampler can move it by a sample or two, so 1 ms of margin there (AppendBatchTest checks it)
        val margin = if (info.sampleRate == 16_000) 0L else info.sampleRate / 1000L
        return first + Math.floor(index * advanceSamples()).toLong() - margin
    }

    /** One hop onto the engine thread: the buffered audio, then [length] samples of [samples], then
     *  (with [finish]) the end of the take, whose heads it returns. */
    private suspend fun hop(samples: FloatArray?, offset: Int, length: Int, finish: Boolean): Pair<TajweedHeads, Backend>? {
        val prof = StageClock.on
        val tIn = if (prof) System.nanoTime() else 0L
        if (SdkEvents.active && pendingN + length > 0) {
            // the observers get the audio as it goes to the engine: one copy a hop
            val copy = FloatArray(pendingN + length)
            System.arraycopy(pending, 0, copy, 0, pendingN)
            if (length > 0) System.arraycopy(samples!!, offset, copy, pendingN, length)
            SdkEvents.emit { it.onAudio(info, copy, info.sampleRate) }
        }
        val tHop = if (prof) StageClock.lap(StageClock.AUDIO_OBSERVERS, tIn) else 0L
        val chunksBefore = index
        val takenBefore = taken - length
        val buffered = pendingN
        var body = 0L
        hops++
        val result = recognizer.onEngine { _ ->
            val b0 = if (prof) System.nanoTime() else 0L
            val s = stream ?: engine.createStream(select).also { stream = it }
            if (prof) StageClock.lap(StageClock.STREAM_OPEN_CLOSE, b0)
            val t0 = System.nanoTime()
            // each call's chunks with where they start when they redo delivered ones (takeRedo)
            val parts = ArrayList<Pair<List<TajweedHeads>, Int>>(3)
            if (buffered > 0) parts += s.pushAudio(pending, 0, buffered) to s.takeRedo()
            if (length > 0) parts += s.pushAudio(samples!!, offset, length) to s.takeRedo()
            this.samples += buffered + length
            var take: Pair<TajweedHeads, Backend>? = null
            if (finish) {
                parts += s.finish() to s.takeRedo()
                deliver(parts, (System.nanoTime() - t0) / 1e9, s.backend)
                val heads = s.takeHeads() ?: kept
                val b = s.backend
                scoring = recognizer.scoringOf(engine, s, heads.size)
                val c0 = if (prof) System.nanoTime() else 0L
                s.close()
                stream = null
                if (prof) StageClock.lap(StageClock.STREAM_OPEN_CLOSE, c0)
                take = TajweedHeads.concat(heads) to b
            } else {
                deliver(parts, (System.nanoTime() - t0) / 1e9, s.backend)
            }
            if (prof) body = System.nanoTime() - b0
            take
        }
        pendingN = 0
        if (prof) StageClock.lap(StageClock.DISPATCH, tHop + body)
        // chunk number chunksBefore ran in this hop, so it needed more than the engine had before it; one
        // chunk's advance per chunk back, that bounds what the first chunk needs (the best bound is kept)
        if (!finish && index > chunksBefore) {
            val bound = takenBefore - buffered + 1 - Math.floor(chunksBefore * advanceSamples()).toLong()
            recognizer.firstChunkNeed.merge(engine, bound) { a, b -> maxOf(a, b) }
        }
        return result
    }

    private fun advanceSamples(): Double = engine.chunkMs.toDouble() * info.sampleRate / 1000.0

    /** The end of the take: every head of it (those the options ask for, plus the scorer's).
     *  [chunks] completes. */
    suspend fun finish(): TajweedHeads = finishTake(scoring = false).first

    private suspend fun finishTake(scoring: Boolean): Pair<TajweedHeads, Backend> {
        val all = appendLock.withLock {
            if (isClosed) throw RecognizerException.SessionClosed()
            try {
                hop(null, 0, 0, finish = true)!!
            } catch (e: Throwable) {
                end(null, "failed")
                throw e
            }
        }
        // a take being scored reports its end after the score (feedback shown, mistakes flagged)
        end(null, "finished", report = !scoring)
        return all
    }

    /** [finish], then the tajweed layer of the take as surah:ayah (CPU heads scored with the int8
     *  thresholds, NPU heads with the fp32 ones; a take that switched to the CPU by the source of most of
     *  its chunks). */
    suspend fun finishAndScore(surah: Int, ayah: Int): TajweedResult {
        val (heads, b) = finishTake(scoring = true)
        val r = try {
            recognizer.scoreAs(heads, surah, ayah, scoring, b)
        } catch (e: Throwable) {
            emitEnd()
            throw e
        }
        scored = true
        flagged = r.sites.any { it.flagged } || r.words.any { it.flagged } || r.madd.any { it.flagged }
        SdkEvents.emit { it.onScored(info, r) }
        emitEnd()
        return r
    }

    /** Stops the take; [chunks] fails with Cancelled. */
    fun cancel() {
        end(RecognizerException.Cancelled(), "cancelled")
        recognizer.scope.launch {
            runCatching { stream?.close() }
            stream = null
        }
    }

    /** The chunks of one hop's calls; a call whose chunks redo delivered ones (from index `redo`) rewinds to it. */
    private fun deliver(parts: List<Pair<List<TajweedHeads>, Int>>, seconds: Double, backend: Backend) {
        val n = parts.sumOf { it.first.size }
        compute += seconds
        lastBackend = backend
        for ((heads, redo) in parts) {
            if (redo in 0 until index) {
                while (kept.size > redo) kept.removeAt(kept.size - 1)
                index = redo
                frame = kept.sumOf { it.n40 }
            }
            deliver(heads, if (n > 0) seconds / n else 0.0)
        }
    }

    /** The highest chunk index delivered so far plus one (a redone chunk below it replaces one). */
    private var highWater = 0

    private fun deliver(heads: List<TajweedHeads>, perChunk: Double) {
        val backend = lastBackend
        val observe = SdkEvents.active
        val prof = StageClock.on
        for (h in heads) {
            var tp = if (prof) System.nanoTime() else 0L
            kept.add(h)
            val i = index++
            val chunk = TajweedChunk(i, h.only(select), perChunk, backend, frame, replaces = i < highWater)
            highWater = maxOf(highWater, index)
            events.trySend(chunk)
            if (prof) tp = StageClock.lap(StageClock.DELIVER_OTHER, tp)
            if (observe) {
                val stats = ChunkStats(chunk.index, 1000.0 * chunk.seconds, h.n40 * 40.0, backend)
                // the observers' decoded layer never fails a session
                val decoded = try { Decoder.decode(h, recognizer.model.vocabulary, frame) } catch (_: Exception) { Decoded(emptyList(), emptyList(), emptyList()) }
                if (prof) tp = StageClock.lap(StageClock.DECODE, tp)
                SdkEvents.emit { it.onChunk(info, stats, decoded) }
                if (prof) StageClock.lap(StageClock.CHUNK_OBSERVERS, tp)
            }
            frame += h.n40
        }
    }

    @Volatile private var outcome = ""

    private fun end(error: RecognizerException?, outcome: String, report: Boolean = true) {
        synchronized(lock) {
            if (done) return
            done = true
        }
        this.outcome = outcome
        events.close(error)
        recognizer.release(this)
        if (report) emitEnd()
    }

    private fun emitEnd() {
        val stats = SessionStats(samples.toDouble() / info.sampleRate, compute, index, outcome, lastBackend, scored, flagged)
        SdkEvents.emit { it.onSessionEnd(info, stats) }
    }
}
