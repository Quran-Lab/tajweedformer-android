package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.FailoverEngine
import ai.quranlab.tajweedformer.accel.FailoverEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The engine under a [Recognizer]: the exact C engine through JNI, the NPU backend, or a test's own. */
internal interface TajweedEngine : AutoCloseable {
    /** Audio per chunk in ms (320 at c16, 480 at c24). */
    val chunkMs: Int

    /** The backend that computes the heads now. */
    val backend: Backend get() = Backend.CPU

    /** A stream for one take at a time; the exact engine allows one live stream per process. */
    fun createStream(): TajweedEngineStream

    /** A stream that computes at least [heads] (the exact engine computes only those and the base set;
     *  other engines may compute more). */
    fun createStream(heads: Set<HeadId>): TajweedEngineStream = createStream()
}

internal interface TajweedEngineStream : AutoCloseable {
    /** The backend computing this take now. */
    val backend: Backend get() = Backend.CPU

    /** Mono float samples in -1..1 at the recognizer's sample rate; the chunks they complete. */
    fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues>

    /** The end of the take (with the flush the model is decoded with); the chunks it completes. */
    fun finish(): List<HeadValues>

    /** A new take: every cache back to the start. */
    fun reset()

    /** Every chunk of the take when the stream replaced earlier chunks (a fallback replay), else null. */
    fun takeHeads(): List<HeadValues>? = null

    /** After a [pushAudio] or [finish]: the take's chunk index its chunks start at when they REDO chunks already
     *  delivered (a confirmed accelerator disagreement: the CPU's chunks from the first suspicious one), else -1. */
    fun takeRedo(): Int = -1
}

/** Makes the CPU engine for a model and context. */
internal fun interface TajweedEngineFactory {
    fun open(model: ModelFolder, context: String, options: Recognizer.Options): TajweedEngine

    companion object {
        /**
         * The exact engine through JNI (native/tj_jni.c over the engine's zf_tajweed.h).
         * Throws [RecognizerException.RuntimeUnsupported] when the native library does not load on this device (an
         * ABI it is not built for), and [RecognizerException.EngineNotAvailable] when it lacks the engine.
         */
        @JvmField
        val EXACT: TajweedEngineFactory = TajweedEngineFactory { model, context, options ->
            val jni = ai.quranlab.tajweedformer.engine.TajweedJni
            if (!jni.available) throw jni.unavailable("libtajweedformer_jni was built without the engine's zf_tajweed")
            model.contextFiles(context) // a context that is not downloaded fails here, clearly
            ai.quranlab.tajweedformer.engine.ExactTajweedEngine(model, context, options)
        }
    }
}

/**
 * The streaming engine of one model and context (the internals of the public `TajweedModel`): audio in; per
 * chunk every requested head; the decoded layer ([decode]: top-1 and runner-up per frame, transcript with
 * confidences, words, times). Nothing here judges a recitation.
 *
 * Backends: the exact CPU engine serves from the first session. With an [Options.accelerator] the NPU
 * is promoted in the background (download, compile, warm-up, the golden test against the CPU engine) and a
 * NEW session runs on it once it is ready (never a session in progress). While it serves, the CPU engine's
 * weights are released ([CpuSlot]); a chunk the NPU fails (an exception, NaN or inf, values out of range, a
 * missed limit) switches the take to the CPU within that call ([FailoverEngine]: the recent audio replayed, the
 * failed chunk redone). Blank output over audio is checked on the CPU off the audio path and switches the take
 * only when the CPU writes tokens there (the chunks from that point are delivered again, [EngineChunk.replaces]).
 * What a failure does to later sessions depends on its cause ([ai.quranlab.tajweedformer.accel.AcceleratorController]).
 * [backendChoice] says which backend the last session got and why; every choice and switch goes to
 * [SdkObserver.onBackendSelected].
 *
 * One session at a time (the exact engine runs one live stream per process). Every engine call runs
 * on the recognizer's own thread, in order; the calls are safe from any thread.
 */
internal class Recognizer private constructor(
    val model: ModelFolder,
    /** The context the engine runs ("c16_l64"). */
    val context: String,
    val options: Options,
    /** The exact CPU engine at open, the accelerator's standby (its weights released while the accelerator serves). */
    firstEngine: CpuSlot,
    choice: BackendChoice,
    /** The CPU self-test, when it ran ([Options.selfTest]). */
    val cpuSelfTest: SelfTestResult?,
) : AutoCloseable {
    data class Options(
        /** The app's context; null: [configContext], else the model's default. */
        val context: String? = null,
        /** The signed config's `inference.context` (the portal's Response time); used when the app chose none and
         *  the model build ships it, ignored otherwise. */
        val configContext: String? = null,
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
        val topK: Map<HeadId, Int> = Decoder.DEFAULT_TOP_K,
        /** The heads each chunk carries (the raw layer is opt-in per head). */
        val heads: Set<HeadId> = DEFAULT_HEADS,
        /** Every head, whatever [heads] says. */
        val allHeads: Boolean = false,
        /** AUTO uses the [accelerator] when it is ready; CPU never does. */
        val backend: BackendPreference = BackendPreference.AUTO,
        /** The NPU, prepared in the background (ModelClient makes it); null: CPU only. */
        val accelerator: AcceleratorController? = null,
        /** The device's SoC id, for the observer. */
        val soc: String? = null,
        /** Run the CPU self-test at open (ModelClient always does). */
        val selfTest: Boolean = false,
        val engine: TajweedEngineFactory = TajweedEngineFactory.EXACT,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tajweedformer-v4-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val lock = Any()
    private var session: RecognizerSession? = null

    init {
        OpenObjects.opened(this, "engine of $context")
    }

    /** The CPU engine in use: one at a time, opened for [rate] (a take at another rate opens it again for that rate). */
    @Volatile private var engine: CpuSlot = firstEngine

    /** The input rate the CPU engine is open for. */
    @Volatile private var rate: Int = options.sampleRate
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

    /** The accelerator controller in use (null: CPU only, or the app asked for the CPU). */
    internal fun accelerator(): AcceleratorController? = options.accelerator.takeIf { options.backend != BackendPreference.CPU }

    /** The engine for a new session of [sampleRate]: the accelerator's when it is ready and nothing prefers the CPU. */
    private fun engineForSession(sampleRate: Int, cpuOnly: Boolean): TajweedEngine {
        val cpu = cpuFor(sampleRate)
        val acc = accelerator() ?: return cpu
        if (cpuOnly) {
            choose(BackendChoice(Backend.CPU, "cpu_requested", "", acc.key.soc, context, model.manifest.build))
            return cpu
        }
        val e = acc.engineForNextSession(cpu, sampleRate, accEngine) { ev -> switched(ev) }
        if (e != null) {
            accEngine = e
            // the warm-up at take start: one prediction of silence on the accelerator's thread while the first
            // chunk's audio arrives (a powered-down accelerator wakes up before the first real chunk)
            e.warmUp()
            choose(BackendChoice(Backend.NPU, "npu", "", acc.key.soc, context, model.manifest.build))
            // no second resident copy of the weights while the accelerator serves: a failover loads them again
            if (releaseCpuForAccelerator) executor.execute { try { cpu.release() } catch (_: Exception) {} }
            return e
        }
        choose(BackendChoice(Backend.CPU, acc.lastCpuReason ?: "cpu", "", acc.key.soc, context, model.manifest.build))
        return cpu
    }

    /**
     * The CPU engine for a take pushed at [sampleRate]. The engine resamples at its input, and its rate is fixed when it
     * is opened, so a take at another rate than the last one closes the engine and opens it again for that rate (one
     * model load; a `listen` take runs at the microphone's rate, so this happens once, not per take). Never two loaded
     * engines: the weights would be resident twice.
     */
    private fun cpuFor(sampleRate: Int): CpuSlot {
        if (sampleRate == rate) return engine
        val old = engine
        try { executor.submit { old.close() }.get() } catch (_: Exception) {}
        val o = options.copy(sampleRate = sampleRate)
        val next = CpuSlot(options.engine.open(model, context, o)) { options.engine.open(model, context, o) }
        engine = next
        rate = sampleRate
        return next
    }

    /** A take on the accelerator switched to the CPU: the app sees only the backend change. */
    private fun switched(e: FailoverEvent) {
        choose(BackendChoice(Backend.CPU, "failover_${e.cause}", e.error?.javaClass?.simpleName ?: "", options.soc, context, model.manifest.build))
    }

    val chunkMs: Int get() = engine.chunkMs

    /** The heads delivered per chunk. */
    val heads: Set<HeadId> get() = if (options.allHeads) HeadId.entries.toSet() else options.heads

    /** The decoded layer of [heads] (a chunk's, or a whole take's). */
    fun decode(heads: HeadValues, frameOffset: Int = 0): Decoded = Decoder.decode(heads, model.vocabulary, frameOffset, options.topK)

    /** Starts a take at the recognizer's own rate and head selection. Throws [RecognizerException.SessionActive]
     *  while another is running. */
    fun startSession(): RecognizerSession = startSession(options.sampleRate, heads)

    /**
     * Starts a take pushed at [sampleRate] (8000 to 48000) carrying [select] per chunk; with [cpuOnly] on the CPU
     * engine whatever the accelerator's state. A rate other than the last take's opens the CPU engine again for it.
     */
    fun startSession(sampleRate: Int, select: Set<HeadId> = heads, cpuOnly: Boolean = false): RecognizerSession {
        if (sampleRate !in 8_000..48_000) {
            throw RecognizerException.UnsupportedAudioFormat("$sampleRate Hz (the resampler takes 8000 to 48000)")
        }
        synchronized(lock) {
            if (closed) throw RecognizerException.SessionClosed()
            if (session != null) throw RecognizerException.SessionActive()
            val sessionEngine = engineForSession(sampleRate, cpuOnly)
            val info = sessionInfo(sampleRate)
            val s = RecognizerSession(this, info, sessionEngine, select)
            session = s
            // the pipeline's golden and warm-up stages yield to sessions (and a first install starts it after one)
            accelerator()?.sessionStarted()
            SdkEvents.emit { it.onSessionStart(info) }
            return s
        }
    }

    private fun sessionInfo(sampleRate: Int): SessionInfo =
        SessionInfo(sessionIds.incrementAndGet(), model.modelId, context, backend, sampleRate, System.currentTimeMillis(),
            revision = model.manifest.build, soc = options.soc,
            weightsSha256 = model.manifest.file("layers.zfw")?.localSha256)

    internal fun release(s: RecognizerSession) {
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

    /** Whether a take is open. */
    internal val sessionOpen: Boolean get() = synchronized(lock) { session != null }

    /** Batched appends (RecognizerSession.append): off only in the tests that compare with the unbatched path. */
    @Volatile internal var batchAppends = true

    /** The audio a take may hold, ms ([TakeLimits.MAX_TAKE_MS]; the tests of the limit set a shorter one). */
    @Volatile internal var maxTakeMs = TakeLimits.MAX_TAKE_MS

    /** Per engine: a lower bound of the input samples a take needs before its first chunk runs, learned
     *  from the first take (stream reset makes it the same every take; the rate and context are fixed). */
    internal val firstChunkNeed = java.util.concurrent.ConcurrentHashMap<TajweedEngine, Long>()

    internal suspend fun <T> onEngine(body: (TajweedEngine) -> T): T = withContext(dispatcher) { body(engine) }

    /**
     * [body] on the engine's thread, after what is queued there, not awaited. A plain task of the thread, never a
     * coroutine: a coroutine cancelled before it started does not run, while the thread runs every task queued
     * before [close] ends it. Returns false when the thread has already ended.
     */
    internal fun onEngineLater(body: () -> Unit): Boolean = try {
        executor.execute { try { body() } catch (_: Throwable) {} }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    override fun close() {
        val s: RecognizerSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            s = session
        }
        // the open take's engine stream is closed on the engine's thread first, then the engine itself
        s?.cancel()
        executor.execute { engine.close() }
        executor.shutdown()
        OpenObjects.closed(this)
    }

    companion object {
        private val counter = AtomicInteger()
        private val sessionIds = AtomicLong()
        private val loads = AtomicInteger()

        /** The heads delivered by default: the base set plus conf for the decoded layer's confidences. */
        @JvmField
        val DEFAULT_HEADS: Set<HeadId> = HeadId.BASE_HEADS + HeadId.CONF

        /**
         * The context a recognizer runs: the app's choice, then the signed config's `inference.context` when the model
         * build ships it, then the manifest's default. An app context the build does not ship is an error; a config
         * context it does not ship is ignored.
         */
        @JvmStatic
        fun resolveContext(model: ModelFolder, app: String?, config: String?): String {
            if (app != null) {
                if (app !in model.contexts) throw RecognizerException.InvalidAppOption("context", "$app is not shipped (${model.contexts})")
                return app
            }
            return config?.takeIf { it in model.contexts } ?: model.defaultContext
        }

        /** Opens the engine (and the NPU when set up) for [model] on [Dispatchers.IO]. */
        suspend fun create(model: ModelFolder, options: Options = Options()): Recognizer =
            withContext(Dispatchers.IO) { open(model, options) }

        /** The same as [create], blocking. */
        @JvmStatic
        @JvmOverloads
        fun open(model: ModelFolder, options: Options = Options()): Recognizer {
            val context = resolveContext(model, options.context, options.configContext)
            if (options.sampleRate !in 8_000..48_000) {
                throw RecognizerException.UnsupportedAudioFormat("${options.sampleRate} Hz (the resampler takes 8000 to 48000)")
            }
            val t0 = System.nanoTime()
            val cpu = options.engine.open(model, context, options)
            val rec = try {
                choose(model, context, options, cpu)
            } catch (e: Throwable) {
                cpu.close()
                throw e
            }
            val load = ModelLoadInfo(model.manifest.build, context, rec.backend, (System.nanoTime() - t0) / 1e6,
                loads.getAndIncrement() == 0, options.soc)
            SdkEvents.emit { it.onModelLoad(load) }
            return rec
        }

        private fun choose(model: ModelFolder, context: String, options: Options, cpu: TajweedEngine): Recognizer {
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
            val c = BackendChoice(Backend.CPU, reason, "", options.soc, context, model.manifest.build)
            SdkEvents.emit { it.onBackendSelected(c) }
            val slot = CpuSlot(cpu) { options.engine.open(model, context, options) }
            return Recognizer(model, context, options, slot, c, cpuTest?.result)
        }
    }
}

/**
 * One take: audio in, each chunk's heads out of [chunks], and every head of the take at the end.
 */
internal class RecognizerSession internal constructor(
    private val recognizer: Recognizer,
    val info: SessionInfo,
    /** The engine this take runs on, chosen at its start (the CPU, or the accelerator when ready). */
    private val engine: TajweedEngine,
    /** The heads each chunk carries. */
    private val select: Set<HeadId> = recognizer.heads,
) {
    // Both hold at most the chunks of one take, and a take holds at most maxSamples of audio (TakeLimits): the
    // channel has no capacity of its own because the engine thread must never wait for, or drop, a chunk the
    // Session's decoder needs.
    private val events = Channel<EngineChunk>(Channel.UNLIMITED)
    private val kept = ArrayList<HeadValues>()
    /** The input samples this take may hold; audio given beyond them is not part of the take. */
    private val maxSamples = recognizer.maxTakeMs * info.sampleRate / 1000
    private var stream: TajweedEngineStream? = null
    private val lock = Any()
    private var done = false
    private var index = 0
    private var frame = 0
    private var samples = 0L
    private var compute = 0.0
    /** The compute time of the take's chunks, each in whole microseconds, summed. */
    private var computeUs = 0L
    /** The take ends after this many chunks (it ended on silence there): later chunks are not part of it. -1: no limit. */
    @Volatile private var stopAt = -1

    /** The take is over after its first [chunks] chunks: what the engine gives after them (the audio already pushed,
     *  the flush) is neither delivered nor counted, and the take's heads are those chunks'. */
    fun stopAfter(chunks: Int) { stopAt = chunks }
    private var lastBackend = info.backend
    // Batched appends: audio waits here (the caller's side) until the engine can run a chunk with it.
    private val appendLock = Mutex()
    private val pending = FloatArray(maxOf(16_000, (engine.chunkMs.toLong() * info.sampleRate / 1000).toInt() + info.sampleRate / 10))
    private var pendingN = 0
    @Volatile private var taken = 0L

    /** The take holds all the audio it may ([TakeLimits.MAX_TAKE_MS]): the Session ends it with `Ended(LIMIT)`. */
    val atLimit: Boolean get() = taken >= maxSamples
    /** Hops onto the engine thread (for the tests). */
    internal var hops = 0

    init {
        OpenObjects.opened(this, "take ${info.id} of the engine")
    }


    /** Each chunk's heads (those the session asks for) as the engine runs it. Completes
     *  after [finish]; fails with [RecognizerException.Cancelled] after [cancel]. Collect it once. */
    val chunks: Flow<EngineChunk> = events.consumeAsFlow()

    val isClosed: Boolean get() = synchronized(lock) { done }

    /** The backend that computed the last chunk (the session's first backend before any chunk). */
    val backend: Backend get() = lastBackend

    /**
     * Mono float samples in -1..1 at the session's sample rate. Returns once the engine has run every
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
            // the take's limit: the audio up to it is taken, the rest is dropped (the Session ends the take there)
            val n = minOf(length.toLong(), maxSamples - taken).toInt()
            if (n <= 0) return
            taken += n
            val need = nextNeed()
            if (need >= 0 && taken < need && n <= pending.size - pendingN) {
                System.arraycopy(samples, offset, pending, pendingN, n)
                pendingN += n
                return
            }
            hop(samples, offset, n, finish = false)
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
    private suspend fun hop(samples: FloatArray?, offset: Int, length: Int, finish: Boolean): Pair<HeadValues, Backend>? {
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
            // the take ended (cancel) while this call waited for the engine's thread: no stream is opened for it,
            // nothing would close one
            if (isClosed) throw RecognizerException.SessionClosed()
            val b0 = if (prof) System.nanoTime() else 0L
            // the two heads speech is read from are always computed, whatever the app selected; they are handed to
            // the app, and counted, only when selected
            val s = stream ?: engine.createStream(select + SpeechFrames.HEADS).also { stream = it }
            if (prof) StageClock.lap(StageClock.STREAM_OPEN_CLOSE, b0)
            val t0 = System.nanoTime()
            // each call's chunks with where they start when they redo delivered ones (takeRedo)
            val parts = ArrayList<Pair<List<HeadValues>, Int>>(3)
            if (buffered > 0) parts += s.pushAudio(pending, 0, buffered) to s.takeRedo()
            if (length > 0) parts += s.pushAudio(samples!!, offset, length) to s.takeRedo()
            this.samples += buffered + length
            var take: Pair<HeadValues, Backend>? = null
            if (finish) {
                parts += s.finish() to s.takeRedo()
                deliver(parts, (System.nanoTime() - t0) / 1e9, s.backend, final = true)
                val all = s.takeHeads() ?: kept
                val heads = if (stopAt in 0 until all.size) all.subList(0, stopAt) else all
                val b = s.backend
                val c0 = if (prof) System.nanoTime() else 0L
                s.close()
                stream = null
                if (prof) StageClock.lap(StageClock.STREAM_OPEN_CLOSE, c0)
                take = HeadValues.concat(heads) to b
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

    /** The end of the take: every head of it (those the options ask for, plus the base set).
     *  [chunks] completes. With [failed] the take ends as failed (its heads are returned, nothing of it is counted). */
    suspend fun finish(failed: Boolean = false): HeadValues {
        val all = appendLock.withLock {
            if (isClosed) throw RecognizerException.SessionClosed()
            try {
                hop(null, 0, 0, finish = true)!!
            } catch (e: Throwable) {
                end(null, "failed")
                throw e
            }
        }
        takeHeads = all.first
        end(null, if (failed) "failed" else "finished")
        return all.first
    }

    /** The take's heads once it finished (the 6.2 counters are read from them). */
    @Volatile private var takeHeads: HeadValues? = null

    /** Stops the take; [chunks] fails with Cancelled. */
    fun cancel() {
        end(RecognizerException.Cancelled(), "cancelled")
        // The stream is closed on the engine's thread, after the call that may be running there, as a plain task of
        // that thread. It was a coroutine of a scope the recognizer's close cancelled: closed with a take open, the
        // coroutine did not run and the process's one engine stream stayed taken (`session_open` for every later take).
        recognizer.onEngineLater {
            runCatching { stream?.close() }
            stream = null
        }
    }

    /** The chunks of one hop's calls; a call whose chunks redo delivered ones (from index `redo`) rewinds to it.
     *  With [final] (the take's last engine call) the last chunk delivered is marked as the take's last. */
    private fun deliver(parts: List<Pair<List<HeadValues>, Int>>, seconds: Double, backend: Backend, final: Boolean = false) {
        val n = parts.sumOf { it.first.size }
        compute += seconds
        lastBackend = backend
        var left = n
        for ((heads, redo) in parts) {
            if (redo in 0 until index) {
                while (kept.size > redo) kept.removeAt(kept.size - 1)
                index = redo
                frame = kept.sumOf { it.n40 }
            }
            left -= heads.size
            deliver(heads, if (n > 0) seconds / n else 0.0, lastOfTake = final && left == 0)
        }
    }

    /** The highest chunk index delivered so far plus one (a redone chunk below it replaces one). */
    private var highWater = 0

    /** How many chunks were put on [chunks] so far (the Session waits until it has taken them all). */
    @Volatile var emitted = 0
        private set

    private fun deliver(heads: List<HeadValues>, perChunk: Double, lastOfTake: Boolean = false) {
        val backend = lastBackend
        val observe = SdkEvents.active
        val prof = StageClock.on
        for ((k, h) in heads.withIndex()) {
            if (stopAt in 0..index) continue
            computeUs += (perChunk * 1e6).toLong()
            var tp = if (prof) System.nanoTime() else 0L
            kept.add(h)
            val i = index++
            val chunk = EngineChunk(i, h.only(select), perChunk, backend, frame, replaces = i < highWater, full = h,
                last = lastOfTake && k == heads.size - 1)
            highWater = maxOf(highWater, index)
            events.trySend(chunk)
            emitted++
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

    private fun end(error: RecognizerException?, outcome: String) {
        synchronized(lock) {
            if (done) return
            done = true
        }
        this.outcome = outcome
        events.close(error)
        recognizer.release(this)
        OpenObjects.closed(this)
        emitEnd()
    }

    private fun emitEnd() {
        // the 6.2 counters come from the take's own outputs: a finished take's heads, else the chunks so far
        val counts = try {
            TakeCounts.of(takeHeads ?: HeadValues.concat(ArrayList(kept)), select)
        } catch (_: Exception) {
            TakeCounts.NONE
        }
        val stats = SessionStats(samples.toDouble() / info.sampleRate, compute, index, outcome, lastBackend,
            speechFrames = counts.speechFrames, silenceFrames = counts.silenceFrames, tokens = counts.tokens,
            audioMs = samples * 1000 / info.sampleRate, computeUs = computeUs)
        SdkEvents.emit { it.onSessionEnd(info, stats) }
    }
}
