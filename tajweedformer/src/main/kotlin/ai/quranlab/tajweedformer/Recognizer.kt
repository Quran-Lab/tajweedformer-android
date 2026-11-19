package ai.quranlab.tajweedformer

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

/** The engine under a [Recognizer]: the Core's exact engine (SDK 4.0), or a test's own. */
internal interface TajweedEngine : AutoCloseable {
    /** Audio per chunk in ms (320 at c16, 480 at c24). */
    val chunkMs: Int

    /** The backend that computes the heads now. */
    val backend: Backend get() = Backend.CPU

    /** A stream for one take at a time; the Core allows one live stream per process. */
    fun createStream(): TajweedEngineStream

    /** A stream that computes at least [heads] (the Core computes only those and the base set). */
    fun createStream(heads: Set<HeadId>): TajweedEngineStream = createStream()

    /** The same, on the accelerator when [accelerator] and the engine has one promoted (else the CPU). */
    fun createStream(heads: Set<HeadId>, accelerator: Boolean): TajweedEngineStream = createStream(heads)
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

    /** Every chunk of the take when the stream replaced earlier chunks, else null. */
    fun takeHeads(): List<HeadValues>? = null

    /** After a [pushAudio] or [finish]: the take's chunk index its chunks start at when they REDO chunks already
     *  delivered, else -1. */
    fun takeRedo(): Int = -1
}

/** Makes the CPU engine for a model and context. */
internal fun interface TajweedEngineFactory {
    fun open(model: ModelFolder, context: String, options: Recognizer.Options): TajweedEngine

    companion object {
        /**
         * The Core's exact engine (SDK 4.0): the build's device forms decrypted into the Core's memory under the lease.
         * Throws `runtime_unsupported` when the Core does not load on this device (an ABI it is not built for).
         */
        @JvmField
        val CORE: TajweedEngineFactory = TajweedEngineFactory { model, context, options ->
            val build = model.protected ?: throw RecognizerException.ModelIncompatible("not an encrypted build: SDK 4.0 opens a model only through the Core")
            if (context !in model.availableContexts) throw RecognizerException.ModelMissing("context $context is not installed")
            ai.quranlab.tajweedformer.core.CoreTajweedEngine(build, context, options)
        }

        /** The 3.x name of the default factory. */
        @JvmField
        val EXACT: TajweedEngineFactory = CORE
    }
}

/**
 * The streaming engine of one model and context (the internals of the public `TajweedModel`): audio in; per
 * chunk every requested head; the decoded layer ([decode]: top-1 and runner-up per frame, transcript with
 * confidences, words, times). Nothing here judges a recitation.
 *
 * SDK 4.0: the exact engine inside the Quran Lab Core, on the CPU, and the accelerator program the Core runs from its
 * own memory (R2) once it is prepared and passed its golden test ([Options.accelerator]). One session at a time. Every
 * engine call runs on the recognizer's own thread, in order (an accelerator take is fed from it, never from the
 * real-time audio callback); the calls are safe from any thread.
 */
internal class Recognizer private constructor(
    val model: ModelFolder,
    /** The context the engine runs ("c16_l64"). */
    val context: String,
    val options: Options,
    first: TajweedEngine,
    choice: BackendChoice,
) : AutoCloseable {
    data class Options(
        /** The app's context; null: [configContext], else the model's default. */
        val context: String? = null,
        /** The signed config's `inference.context`; used when the app chose none and the build ships it. */
        val configContext: String? = null,
        /** The rate audio is pushed at: 16000, or 8000 .. 48000 through the engine's resampler. */
        val sampleRate: Int = 16_000,
        /** Engine threads; 0 = the default: on Android the big + mid cores capped at 4 ([CoreTopology]). */
        val threads: Int = 0,
        /** Engine chunks run together, for stored audio; 1 for live audio. */
        val batch: Int = 1,
        /** Leave the engine's threads unpinned (true) or pin them (false); null: the engine's platform default. */
        val noPin: Boolean? = null,
        /** The decoded layer's depth per head ([Decoder]). */
        val topK: Map<HeadId, Int> = Decoder.DEFAULT_TOP_K,
        /** The heads each chunk carries. */
        val heads: Set<HeadId> = DEFAULT_HEADS,
        /** Every head, whatever [heads] says. */
        val allHeads: Boolean = false,
        /** The app's backend choice: AUTO runs takes on the accelerator once it is promoted, CPU never. */
        val backend: BackendPreference = BackendPreference.AUTO,
        /** The accelerator to promote once the CPU engine is open (R2); null: the CPU only. */
        val accelerator: ai.quranlab.tajweedformer.core.AcceleratorPlan? = null,
        /** The device's SoC id, for the observer. */
        val soc: String? = null,
        val engine: TajweedEngineFactory = TajweedEngineFactory.CORE,
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
    @Volatile private var engine: TajweedEngine = first

    /** The input rate the CPU engine is open for. */
    @Volatile private var rate: Int = options.sampleRate
    private var closed = false

    /** The backend the last session got and why. */
    @Volatile
    var backendChoice: BackendChoice = choice
        private set

    /** The backend the next session asked for on the accelerator runs on. */
    val backend: Backend get() = backendChoice.backend

    /**
     * The CPU engine for a take pushed at [sampleRate]. The engine resamples at its input, and its rate is fixed when it
     * is opened, so a take at another rate closes the engine and opens it again for that rate (one model load: the
     * Core decrypts the device forms again). Never two loaded engines (the Core opens one model at a time).
     */
    private fun cpuFor(sampleRate: Int): TajweedEngine {
        if (sampleRate == rate) return engine
        val old = engine
        try { executor.submit { old.close() }.get() } catch (_: Exception) {}
        val next = options.engine.open(model, context, options.copy(sampleRate = sampleRate))
        engine = next
        rate = sampleRate
        backendChoice = BackendChoice(Backend.CPU, "no_accelerator", "", options.soc, context, model.manifest.build)
        promoteLater(next)
        return next
    }

    val chunkMs: Int get() = engine.chunkMs

    /** The heads delivered per chunk. */
    val heads: Set<HeadId> get() = if (options.allHeads) HeadId.entries.toSet() else options.heads

    /** The decoded layer of [heads] (a chunk's, or a whole take's). */
    fun decode(heads: HeadValues, frameOffset: Int = 0): Decoded = Decoder.decode(heads, model.vocabulary, frameOffset, options.topK)

    /** Starts a take at the recognizer's own rate and head selection. */
    fun startSession(): RecognizerSession = startSession(options.sampleRate, heads)

    /** Starts a take pushed at [sampleRate] (8000 to 48000) carrying [select] per chunk. */
    fun startSession(sampleRate: Int, select: Set<HeadId> = heads, cpuOnly: Boolean = false): RecognizerSession {
        if (sampleRate !in 8_000..48_000) {
            throw RecognizerException.UnsupportedAudioFormat("$sampleRate Hz (the resampler takes 8000 to 48000)")
        }
        synchronized(lock) {
            if (closed) throw RecognizerException.SessionClosed()
            if (session != null) throw RecognizerException.SessionActive()
            val sessionEngine = cpuFor(sampleRate)
            val info = sessionInfo(sampleRate, if (cpuOnly) Backend.CPU else sessionEngine.backend)
            val s = RecognizerSession(this, info, sessionEngine, select, cpuOnly)
            session = s
            SdkEvents.emit { it.onSessionStart(info) }
            return s
        }
    }

    private fun sessionInfo(sampleRate: Int, backend: Backend): SessionInfo =
        SessionInfo(sessionIds.incrementAndGet(), model.modelId, context, backend, sampleRate, System.currentTimeMillis(),
            revision = model.manifest.build, soc = options.soc,
            weightsSha256 = model.manifest.file("layers.zfw")?.plainSha256)

    internal fun release(s: RecognizerSession) {
        synchronized(lock) { if (session === s) session = null }
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

    /** [body] on the engine's thread, waited for (never call it from that thread). */
    internal fun <T> onEngineBlocking(body: (TajweedEngine) -> T): T = try {
        executor.submit(java.util.concurrent.Callable { body(engine) }).get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    // ---------------------------------------------------------------- the accelerator's promotion (R2)

    /**
     * The accelerator for [e], in the background (quranlab-core hardening.md section 10): the Core prepares the program
     * on a thread of its own (seconds: the decryption, then the runtime's compile) while CPU takes go on; then, on the
     * engine thread with no take open, the golden test (once per build on this device: the verdict is kept); then the
     * takes asked for on the accelerator run on it. A failure leaves the CPU engine; a build whose program the Core or
     * the runtime refused, or that failed its golden test, is not tried again on this device.
     */
    private fun promoteLater(e: TajweedEngine) {
        val plan = options.accelerator ?: return
        val ce = e as? ai.quranlab.tajweedformer.core.AcceleratorHost ?: return
        if (options.backend == BackendPreference.CPU) return
        val key = "${model.manifest.build}:${plan.kind}:$context"
        // off for good, or held by a transient verdict (slower than the CPU engine, the runtime refused it on this OS)
        if (plan.records.blocked(key) || plan.held(key)) return
        ce.onFailover = { why ->
            val c = BackendChoice(Backend.CPU, "failover", why, options.soc, context, model.manifest.build)
            backendChoice = c
            SdkEvents.emit { it.onBackendSelected(c) }
            plan.report(ai.quranlab.tajweedformer.core.PromotionInfo(plan.soc, model.manifest.build, context, promoted = false,
                reason = "failover", detail = why, failover = true, version = model.manifest.version ?: ""))
        }
        Thread({ promote(ce, plan, key) }, "tajweedformer-accel").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun promote(e: ai.quranlab.tajweedformer.core.AcceleratorHost, plan: ai.quranlab.tajweedformer.core.AcceleratorPlan, key: String) {
        val build = model.manifest.build
        val records = plan.records
        fun report(at: String?, reason: String?, detail: String?, ms: Double?, accMs: Double? = null, cpuMs: Double? = null) = try {
            plan.report(ai.quranlab.tajweedformer.core.PromotionInfo(plan.soc, build, context, at == null, at, reason, detail, ms,
                version = model.manifest.version ?: "", acceleratorMs = accMs, cpuMs = cpuMs))
        } catch (_: Exception) {
        }
        // every transient verdict names the dispatch library it was found with: another one is tried again at once
        fun hold(state: String, why: String, extra: Map<String, Any?> = emptyMap()) =
            records.hold(key, state, why, plan.sdk, plan.os, plan.clock(), extra + ("dispatch" to plan.dispatchId))
        val passedBefore = records.passedWith(key, plan.dispatchId) || records.passedBefore(key)
        val clips = if (passedBefore) emptyList() else try { plan.clips() } catch (_: Exception) { emptyList() }
        if (!passedBefore && clips.isEmpty()) return
        if (!records.begin(key, plan.dispatchId)) {
            // the process ended twice during the preparation (the system, or the vendor's runtime, ended it): the CPU
            // engine, held until an SDK, OS or dispatch library update or 30 days, never off for good
            hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, "crash: the process ended during the preparation twice")
            report("compile", "crash", "the process ended during the preparation twice", null)
            return
        }
        val t0 = System.nanoTime()
        // the Core's prepare on a thread of its own, waited for at most plan.prepareDeadlineMs: a vendor dispatch that
        // does not answer (the Pixel 10's launch firmware waits on its service) never keeps the promotion waiting; the
        // takes run on the CPU engine meanwhile, and a late answer is released, never promoted
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun releaseLate() {
            if (released.compareAndSet(false, true)) onEngineLater { try { e.releaseAccelerator() } catch (_: Exception) {} }
        }
        val prepare = java.util.concurrent.FutureTask {
            val n = e.prepareAccelerator(plan.kind, plan.dispatchDir)
            if (abandoned.get()) releaseLate()
            n
        }
        Thread(prepare, "tajweedformer-accel-prepare").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
        val name = try {
            prepare.get(plan.prepareDeadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            abandoned.set(true)
            if (prepare.isDone) try { prepare.get(); releaseLate() } catch (_: Exception) {}
            hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, "compile: no answer in ${plan.prepareDeadlineMs} ms")
            report("compile", "compile_timeout", "no answer in ${plan.prepareDeadlineMs} ms", null)
            return
        } catch (w: java.util.concurrent.ExecutionException) {
            val x = w.cause as? ai.quranlab.tajweedformer.core.CoreException
            if (x == null) {
                records.end(key, "waiting")
                return
            }
            val qc = ai.quranlab.tajweedformer.core.Core
            val reason = when (x.detail) {
                qc.D_ACCEL_PROGRAM -> "compile_error"
                qc.D_ACCEL_RUNTIME, qc.D_ACCEL_CONTRACT -> "load_error"
                qc.D_ACCEL_FIRMWARE -> "firmware"
                qc.D_ACCEL_DISPATCH -> "dispatch"
                else -> "other"
            }
            when {
                x.status != qc.E_ENGINE -> records.end(key, "waiting", x.message)
                // Core 69276aa: firmware known not to set a program up (the Pixel 10's launch firmware), refused in
                // milliseconds: the SDK stops asking (no download, no prepare) until the OS build changes
                x.detail == qc.D_ACCEL_FIRMWARE ->
                    hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.FIRMWARE, x.message ?: reason)
                // the vendor's service did not answer: the CPU engine; the Core refuses every later prepare of this
                // process at once, and the next start asks again
                x.detail == qc.D_ACCEL_DISPATCH -> records.end(key, "waiting", x.message)
                // the runtime or the vendor's dispatch refused the program on this OS build (the Pixel 10's launch
                // firmware: its dispatch times out): tried again after an SDK or OS update or 30 days, not for good
                x.detail == qc.D_ACCEL_PROGRAM || x.detail == qc.D_ACCEL_RUNTIME ->
                    hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, x.message ?: reason)
                // the device's side too (Stage B): LiteRT's entry points split over images (another LiteRT build, or a
                // hook: risk signal 8 is the Core's), or an OS below the program's floor: tried again after an update
                x.detail == qc.D_ACCEL_HOOKED || x.detail == qc.D_ACCEL_PLATFORM ->
                    hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, x.message ?: reason)
                // the build's own fault (a program that breaks the host contract, no complete program): off for good
                else -> records.end(key, ai.quranlab.tajweedformer.core.AcceleratorRecords.FAILED, x.message)
            }
            report("compile", reason, qc.detailName(x.status, x.detail) ?: qc.statusName(x.status), null)
            return
        } catch (_: Exception) {
            records.end(key, "waiting")
            return
        }
        val ms = (System.nanoTime() - t0) / 1e6
        var accMs: Double? = null
        var cpuMs: Double? = null
        if (!passedBefore) {
            // the golden test on the engine thread, when no take is open (one CPU stream at a time in the Core)
            var runs: ai.quranlab.tajweedformer.core.GoldenRuns? = null
            val until = System.currentTimeMillis() + GOLDEN_WAIT_MS
            while (runs == null) {
                if (closed || engine !== e) return records.end(key, "waiting")
                runs = try {
                    executor.submit(java.util.concurrent.Callable { if (engine === e) e.goldenRuns(clips) else null }).get()
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    return records.end(key, "waiting")
                } catch (x: Exception) {
                    // the golden runs did not complete: the accelerator's stream failed while running (the runtime or
                    // the vendor's dispatch: a program prepared without its dispatch library fails at its first push,
                    // "qlc_stream_push: engine", a device lab's Pixel 10, 11-18), or the CPU's side failed. That says
                    // nothing about the build: the CPU engine now, held until an SDK, OS or dispatch library update or
                    // 30 days, never off for good. Only a mismatch below is a verdict on the build.
                    val cause = (x as? java.util.concurrent.ExecutionException)?.cause ?: x
                    val qc = ai.quranlab.tajweedformer.core.Core
                    val core = cause as? ai.quranlab.tajweedformer.core.CoreException
                    val what = core?.let { qc.detailName(it.status, it.detail) ?: qc.statusName(it.status) } ?: cause.javaClass.simpleName
                    hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, "golden run: ${cause.message ?: what}")
                    try { executor.submit { e.releaseAccelerator() }.get() } catch (_: Exception) {}
                    report("golden", "load_error", "golden run: $what", ms)
                    return
                }
                if (runs == null) {
                    if (System.currentTimeMillis() > until) return records.end(key, "waiting")
                    try { Thread.sleep(GOLDEN_RETRY_MS) } catch (_: InterruptedException) { return records.end(key, "waiting") }
                }
            }
            val v = ai.quranlab.tajweedformer.core.Golden.judge(runs.runs, plan.tolerance)
            if (!v.passed) {
                records.end(key, ai.quranlab.tajweedformer.core.AcceleratorRecords.FAILED, "golden: ${v.detail}")
                try { executor.submit { e.releaseAccelerator() }.get() } catch (_: Exception) {}
                report("golden", "golden_mismatch", v.detail.take(120), ms)
                return
            }
            // the measure stage (SDK 4.0): not promoted when slower than the CPU engine on this device, by the
            // golden test's warm chunks
            val speed = ai.quranlab.tajweedformer.core.AcceleratorSpeed
            val a = speed.median(runs.acceleratorMs)
            val c = speed.median(runs.cpuMs)
            accMs = a.takeIf { it > 0 }
            cpuMs = c.takeIf { it > 0 }
            val need = speed.minSpeedup(try { plan.minSpeedup() } catch (_: Exception) { null })
            if (speed.slower(a, c, need)) {
                val why = String.format(java.util.Locale.ROOT,
                    "measure: the accelerator's median chunk takes %.1f ms, the CPU engine's %.1f ms (speedup %.2f, at least %.2f)", a, c, c / a, need)
                hold(ai.quranlab.tajweedformer.core.AcceleratorRecords.SLOWER, why, mapOf("accelerator_ms" to a, "cpu_ms" to c))
                // the program let go: its memory goes back (about 200 MB on a Pixel 10)
                try { executor.submit { e.releaseAccelerator() }.get() } catch (_: Exception) {}
                backendChoice = BackendChoice(Backend.CPU, "slower_than_cpu", why, options.soc, context, build)
                report("measure", "slower_than_cpu", why, ms, a, c)
                return
            }
        }
        records.end(key, ai.quranlab.tajweedformer.core.AcceleratorRecords.PASSED, extra = mapOf("dispatch" to plan.dispatchId))
        if (closed || engine !== e) return
        e.promote()
        val c = BackendChoice(Backend.NPU, "promoted", name, options.soc, context, build)
        backendChoice = c
        SdkEvents.emit { it.onBackendSelected(c) }
        report(null, null, name, ms, accMs, cpuMs)
    }

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
        // the open take's engine stream is closed on the engine's thread first, then the engine itself; the Core's
        // model is closed before close() returns, so another one can be opened right after
        s?.cancel()
        try { executor.submit { engine.close() }.get() } catch (_: Exception) {}
        executor.shutdown()
        OpenObjects.closed(this)
    }

    companion object {
        private val counter = AtomicInteger()
        private val sessionIds = AtomicLong()
        private val loads = AtomicInteger()

        /** How long the golden test waits for a moment with no take open, and how often it looks. */
        const val GOLDEN_WAIT_MS = 10 * 60_000L
        const val GOLDEN_RETRY_MS = 5_000L

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

        /** Opens the engine for [model] on [Dispatchers.IO]. */
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
            // the vocabulary the open model gives (R4: its build's tokens.txt, from the Core), where the folder's differs
            val folder = (cpu as? ai.quranlab.tajweedformer.core.CoreTajweedEngine)?.coreModel?.let { m ->
                try { model.withVocabulary(Vocabulary(m.tokenNames().toTypedArray(), ModelFolder.BLANK)) } catch (_: Exception) { null }
            } ?: model
            val reason = if (options.backend == BackendPreference.CPU) "cpu_requested" else "no_accelerator"
            val c = BackendChoice(Backend.CPU, reason, "", options.soc, context, folder.manifest.build)
            SdkEvents.emit { it.onBackendSelected(c) }
            val rec = Recognizer(folder, context, options, cpu, c)
            val load = ModelLoadInfo(model.manifest.build, context, rec.backend, (System.nanoTime() - t0) / 1e6,
                loads.getAndIncrement() == 0, options.soc)
            SdkEvents.emit { it.onModelLoad(load) }
            rec.promoteLater(cpu)
            return rec
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
    /** The app asked for the CPU (`Backend.CPU`): never the accelerator. */
    private val cpuOnly: Boolean = false,
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

    /** The take was given audio (a take without any is not a session, SPEC 6.1). */
    val hadAudio: Boolean get() = taken > 0

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
            val s = stream ?: engine.createStream(select + SpeechFrames.HEADS, accelerator = !cpuOnly).also { stream = it }
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

    /** The `Ended` reason of a take that ended by itself, set by its [TajweedSession] before [finish]; null for the app's finish. */
    @Volatile internal var endedBy: String? = null

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
            audioMs = samples * 1000 / info.sampleRate, computeUs = computeUs, endedBy = endedBy)
        SdkEvents.emit { it.onSessionEnd(info, stats) }
    }
}
