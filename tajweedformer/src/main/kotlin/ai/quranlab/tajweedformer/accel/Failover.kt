package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.HeadKind
import ai.quranlab.tajweedformer.SelfTest
import ai.quranlab.tajweedformer.SelfTestSet
import ai.quranlab.tajweedformer.TajweedBackend
import ai.quranlab.tajweedformer.TajweedConfig
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TajweedHead
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedResult
import ai.quranlab.tajweedformer.ResolvedTajweedSettings
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * In-session failover (the constants in one place).
 *
 * A chunk of an accelerator session FAILS when the call throws, returns NaN or inf, returns values outside the
 * build's range ([HeadRange]), or misses its limit. The session then switches to the exact CPU engine within the
 * call: the last [REPLAY_WINDOW_MS] of the take's audio (the ring buffer) are replayed through a fresh CPU stream to
 * rebuild its caches, the failed chunk is redone on the CPU, and the take continues there. The chunks the app already
 * got stay; the take's heads for scoring are the accelerator's before the failed chunk and the CPU's from it.
 *
 * Since 2.0.2 two rules no longer fail a chunk by themselves:
 *
 * - Blank output over audio with energy for [BLANK_RUN_MS] is a SUSPICION: CTC writes blanks through a held vowel or a
 *   long madd. The same window is replayed through the CPU engine on a thread of its own while the take goes on on
 *   the accelerator (no stall). The CPU blank too (within the build's `golden_tolerance` token count): nothing
 *   happens (`blank_confirmed_ok`), and the CPU engine is released again. The CPU writing tokens there: a confirmed
 *   disagreement, the take continues on that CPU stream (already rebuilt by the check) and the chunks from the first
 *   suspicious one are delivered again. At most one check in flight, at most one per [BLANK_CHECK_EVERY_MS] of a
 *   take's audio.
 * - The limit never punishes a cold start: the accelerator is warmed up (one prediction of silence, off the audio
 *   path) when a take starts and when the app becomes active; the take's first chunk and the first chunk after
 *   activation get [firstChunkMs]; later chunks [deadlineMs] of WARM chunks only ([ChunkLimits]). A miss is served by
 *   the CPU for the rest of that take and demotes only when it happens in three sessions within a day (the
 *   controller counts them).
 */
object Failover {
    /** The warm limit's cap and its value before any warm measurement exists. */
    const val DEADLINE_CAP_MS = 300.0

    /** The warm limit's floor: a fast NPU's 4 x p90 can be 10 to 20 ms, inside the scheduling jitter of a background
     *  thread. */
    const val DEADLINE_FLOOR_MS = 50.0

    /** The first chunk of a take, and the first after the app became active: max([FIRST_CHUNK_MIN_MS],
     *  [FIRST_CHUNK_P90S] x the warm p90). The accelerator may have powered down while the app sat idle. */
    const val FIRST_CHUNK_MIN_MS = 1000.0
    const val FIRST_CHUNK_P90S = 10

    /** The end of a take may complete up to this many chunks in one call (the flush). */
    const val FINISH_DEADLINES = 3

    /**
     * Audio replayed through the CPU before the failed chunk: 16 chunks at c16_l64. Measured (the exact engine,
     * a fresh stream started N chunks before chunk f, against the uninterrupted run; 35 parity ayahs and the
     * self-test clips, failures at the first, a middle and the last chunk): see the README's "CPU first" section.
     * The encoder's caches reach back further than any bounded window (16 layers, 1.28 s of attention each), so
     * the rebuilt state is close, not bit-exact, unless the take so far fits in the window (then the replay
     * starts at the take's first sample and is exact).
     */
    const val REPLAY_WINDOW_MS = 5120

    /** A run of all-blank chunks with audio energy this long is checked on the CPU (a long madd is about 3 s). */
    const val BLANK_RUN_MS = 4000

    /** Audio energy for the blank rule: RMS of the chunk's input at or above -40 dBFS. */
    const val BLANK_RMS = 0.01

    /** At most one blank check per this much audio of a take (a long silent stretch must not loop the CPU). */
    const val BLANK_CHECK_EVERY_MS = 10_000

    /** The ring buffer keeps this much audio (the window, a blank run and a margin). */
    const val RING_MS = REPLAY_WINDOW_MS + BLANK_RUN_MS + 2000

    /** Warm chunk times kept for the limits (the latest), and how many make a measurement of their own (fewer: the
     *  golden run's warm chunks). */
    const val WARM_SAMPLES = 256
    const val MIN_WARM_SAMPLES = 16

    /** `failover_causes` keys. */
    const val EXCEPTION = "exception"
    const val NON_FINITE = "non_finite"
    const val OUT_OF_RANGE = "out_of_range"
    const val BLANK = "blank"
    const val DEADLINE = "deadline"

    @JvmField val CAUSES = listOf(EXCEPTION, NON_FINITE, OUT_OF_RANGE, BLANK, DEADLINE)

    /** min(300 ms, max(4 x p90, 2 x median, 50 ms)) of WARM accelerator chunk times on this device; 300 ms before a
     *  measurement exists. */
    @JvmStatic
    fun deadlineMs(medianMs: Double, p90Ms: Double): Double {
        if (!(medianMs > 0) || !(p90Ms > 0)) return DEADLINE_CAP_MS
        return minOf(DEADLINE_CAP_MS, maxOf(4 * p90Ms, 2 * medianMs, DEADLINE_FLOOR_MS))
    }

    /** max(1000 ms, 10 x the warm p90): the first chunk of a take and the first after activation. */
    @JvmStatic
    fun firstChunkMs(p90Ms: Double): Double = if (!(p90Ms > 0)) FIRST_CHUNK_MIN_MS else maxOf(FIRST_CHUNK_MIN_MS, FIRST_CHUNK_P90S * p90Ms)

    /** `non_finite`, `out_of_range` or null for a chunk's heads. */
    @JvmStatic
    fun check(h: TajweedHeads, range: HeadRange): String? {
        for (head in h.available) for (x in h[head]!!) if (!x.isFinite()) return NON_FINITE
        for (head in h.available) {
            val v = h[head]!!
            when (head.kind) {
                HeadKind.LOG_PROBS -> for (x in v) if (x > range.logProbMax || x < range.logProbFloor(head.width)) return OUT_OF_RANGE
                HeadKind.PROBS -> for (x in v) if (x < -range.probEps || x > 1 + range.probEps) return OUT_OF_RANGE
                HeadKind.LOGIT -> for (x in v) if (x > range.logitMax || x < -range.logitMax) return OUT_OF_RANGE
            }
        }
        return null
    }

    /** Every 40 ms frame's best token is the blank (no token at all in the chunk). */
    @JvmStatic
    fun allBlank(h: TajweedHeads): Boolean = h.has(TajweedHead.LOG_PROBS) && h.n40 > 0 && h.greedy().isEmpty()

    @JvmStatic
    fun rms(v: FloatArray, offset: Int, length: Int): Double {
        if (length <= 0) return 0.0
        var s = 0.0
        for (i in offset until offset + length) s += v[i].toDouble() * v[i]
        return sqrt(s / length)
    }

    /** The greedy tokens of consecutive chunks (repeats across chunk boundaries collapsed). */
    @JvmStatic
    fun tokens(chunks: List<TajweedHeads>): IntArray = if (chunks.isEmpty()) IntArray(0) else TajweedHeads.concat(chunks).greedy()
}

/**
 * The accelerator's chunk limits on this device, from WARM chunk times only (never the first chunk of a take, a
 * warm-up, the first chunk after activation or the flush): the golden run's warm chunks ([baseMedianMs],
 * [baseP90Ms]) until [Failover.MIN_WARM_SAMPLES] warm session chunks exist, then the latest
 * [Failover.WARM_SAMPLES] of those. Shared by the session engines of one controller; thread safe.
 */
class ChunkLimits(private val baseMedianMs: Double = Double.NaN, private val baseP90Ms: Double = Double.NaN) {
    private val live = ArrayDeque<Double>()

    /** A warm chunk's compute time, ms. */
    @Synchronized
    fun add(ms: Double) {
        if (!ms.isFinite() || ms < 0) return
        live.addLast(ms)
        while (live.size > Failover.WARM_SAMPLES) live.removeFirst()
    }

    /** Warm session chunks measured so far (at most [Failover.WARM_SAMPLES]). */
    val samples: Int @Synchronized get() = live.size

    /** (median, p90) of the warm chunk times in force, NaN when none. */
    @Synchronized
    fun stats(): Pair<Double, Double> =
        if (live.size >= Failover.MIN_WARM_SAMPLES) SelfTestSet.percentile(live.toList(), 0.5) to SelfTestSet.percentile(live.toList(), 0.9)
        else baseMedianMs to baseP90Ms

    /** The limit of a warm chunk ([Failover.deadlineMs]). */
    val warmMs: Double get() = stats().let { Failover.deadlineMs(it.first, it.second) }

    /** The limit of a take's first chunk and of the first after activation ([Failover.firstChunkMs]). */
    val firstChunkMs: Double get() = Failover.firstChunkMs(stats().second)
}

/** The values an accelerator's heads can take: log-probs at most [logProbMax] and at least [logProbFloor], probabilities
 * within [probEps] of 0..1, logits within +-[logitMax]. For an int16 NPU build: every logit is at most 32768 x its
 * output scale ([fromLogitBound]), so a log-prob over `w` classes is at least -(2 x bound + ln w).
 */
data class HeadRange(val logitMax: Double = 64.0, val logProbMax: Double = 1e-3, val probEps: Double = 1e-3) {
    fun logProbFloor(width: Int): Double = -(2 * logitMax + ln(width.toDouble())) - 1.0

    companion object {
        /** From the widest output scale of a build (bound = 32768 x scale), with 5 % room. */
        @JvmStatic
        fun fromLogitBound(bound: Double): HeadRange = HeadRange(logitMax = bound * 1.05)
    }
}

/** The CPU engine kept as the standby: its weights may be released while the accelerator runs ([warm] false), and
 *  [createStream] then loads them again (the cold switch). */
interface CpuStandby : TajweedEngine {
    /** The weights are loaded (a stream starts without a load). */
    val warm: Boolean

    /** Releases the weights again (after a blank check that agreed, when they were released before it). Returns
     *  whether it did; a standby that cannot release them returns false. */
    fun release(): Boolean = false
}

/** What a failure did to the key ([FailoverEvent.demotion]). */
object Demotion {
    /** Nothing: the next session may use the accelerator again (a single missed limit). */
    const val NONE = "none"
    /** A short pause ([PromotionTimeouts.transientBackoffMs]), then the same verified build serves again. */
    const val PAUSED = "paused"
    /** Retried by the pipeline later, golden test included (an exception: the 2.0.1 rule). */
    const val RETRY = "retry"
    /** Never again for this key. */
    const val OFF = "off"
}

/** One in-session failover, for telemetry and the incident report. */
data class FailoverEvent(
    val cause: String,
    /** The failed chunk (the first redone on the CPU), 0-based in the take. */
    val chunk: Int,
    /** From the failure detected to the failed chunk's CPU result computed, ms (the replay included). */
    val stallMs: Double,
    /** How long the failed accelerator call ran, and its limit, ms (a blank disagreement: the CPU check's time, and 0). */
    val elapsedMs: Double,
    val deadlineMs: Double,
    val error: Throwable?,
    /** The CPU weights were loaded when it happened (else they were loaded during the stall). */
    val warm: Boolean,
    /** Chunks replayed before the failed one; [exact] when the replay started at the take's first sample. */
    val replayedChunks: Int,
    val exact: Boolean,
    /** What it did to the key ([Demotion]); `none` for a single missed limit. */
    val demotion: String = Demotion.NONE,
    /** A blank disagreement: the CPU's tokens over the window against the accelerator's. */
    val divergence: Divergence? = null,
) {
    /** The key was taken back to the CPU for later sessions (the `promotions` telemetry's `demoted`). */
    val demoted: Boolean get() = demotion != Demotion.NONE
}

/** A blank suspicion the CPU checked ([FailoverListener.blankChecked]). */
data class BlankCheck(
    /** The first and last suspicious chunk of the take. */
    val fromChunk: Int,
    val toChunk: Int,
    /** The CPU agreed: blank there too (within the build's token tolerance). */
    val agreed: Boolean,
    /** Token edit distance between the CPU and the accelerator over the window, and the CPU's token count. */
    val tokenDiffs: Int,
    val cpuTokens: Int,
    /** The check's time off the audio path (CPU open + replay), ms, and whether the CPU weights were loaded before. */
    val ms: Double,
    val warm: Boolean,
    /** The CPU could not check (it failed): the suspicion is dropped, nothing counted. */
    val failed: Boolean = false,
)

/** What the session engine tells the controller. */
interface FailoverListener {
    /** Before the first accelerator call of a stream ([done] false) and after it returned ([done] true): the crash
     *  sentinel around a first inference. */
    fun firstCall(done: Boolean) {}

    /** The accelerator failed in take [take] with [cause]: what it does to the key ([Demotion]; demote now, so later
     *  sessions start on the CPU). Called before the switch. */
    fun failingOver(cause: String, take: Long): String = Demotion.NONE

    /** The switch is done (the failed chunk's CPU result is about to be delivered). */
    fun failedOver(event: FailoverEvent) {}

    /** A blank check finished (on the check's own thread). */
    fun blankChecked(check: BlankCheck) {}
}

/**
 * A session engine on the accelerator with the CPU as its standby ([CpuStandby]). Every accelerator call runs on this
 * engine's own thread, raced with its limit ([ChunkLimits]): a late call is abandoned (never awaited again, its result
 * dropped, the accelerator closed only once the call returns), and the engine serves no new take until it has
 * returned ([busy]). Audio goes to the accelerator in pieces of at most one chunk's advance, so a call completes at
 * most one chunk. The take's audio is kept in a ring buffer of [Failover.RING_MS]. Blank checks run on a thread of
 * their own.
 */
class FailoverEngine(
    private val handle: AcceleratorHandle,
    private val cpu: CpuStandby,
    private val sampleRate: Int,
    /** The limits (shared with the controller's other engines; warm chunks are added to it). */
    val limits: ChunkLimits,
    private val range: HeadRange,
    private val listener: FailoverListener,
    /** Chunks replayed before the failed one ([Failover.REPLAY_WINDOW_MS] / chunk). */
    private val windowChunks: Int = Failover.REPLAY_WINDOW_MS / handle.accelerator.chunkMs,
    private val blankRunMs: Int = Failover.BLANK_RUN_MS,
    /** Token edits the CPU may differ by over a blank window and still agree (the build's `golden_tolerance`). */
    private val blankTokenTolerance: Int = handle.accelerator.tolerance.tokenDiffs,
    private val blankCheckEveryMs: Int = Failover.BLANK_CHECK_EVERY_MS,
) : TajweedEngine {
    private val prepared: PreparedAccelerator get() = handle.accelerator
    override val chunkMs: Int get() = prepared.chunkMs
    override val backend: Backend get() = if (handle.retired) Backend.CPU else Backend.NPU

    /** The thresholds an accelerator take is scored with. */
    val headsBackend: TajweedBackend get() = prepared.headsBackend

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tajweedformer-accelerator").apply { isDaemon = true }
    }
    private val checker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "tajweedformer-blank-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
    /** Abandoned calls still running on the accelerator thread. */
    private val lateCalls = AtomicInteger(0)
    private val firstCallDone = AtomicBoolean(false)
    /** The app became active: the next chunk gets the first-chunk limit. */
    private val coldNext = AtomicBoolean(false)
    /** A warm-up was queued for the next take (at its session start). */
    private val warmQueued = AtomicBoolean(false)

    /** Warm-ups run, and the last one's time on the accelerator thread, ms (measurements). */
    val warmUps = AtomicInteger(0)
    @Volatile var lastWarmUpMs = Double.NaN
        private set

    /** Input samples per chunk at the app's rate (the CPU stream restarts on this grid). */
    private val advance: Long = prepared.chunkMs.toLong() * sampleRate / 1000

    /** A late call has not returned yet: a new take goes to the CPU (the accelerator thread is still taken). */
    val busy: Boolean get() = lateCalls.get() > 0

    /** Whether this engine runs [p] over [standby] at [rate] (the caller may keep using it). */
    fun belongsTo(p: PreparedAccelerator, standby: CpuStandby, rate: Int): Boolean =
        handle.accelerator === p && cpu === standby && rate == sampleRate && !handle.retired

    override fun createStream(): TajweedEngineStream = createStream(TajweedHead.SCORER_HEADS)

    override fun createStream(heads: Set<TajweedHead>): TajweedEngineStream =
        if (handle.retired) cpu.createStream(heads) else Stream(heads)

    override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult =
        cpu.score(heads, config, settings, surah, ayah)

    override fun expectedTokens(surah: Int, ayah: Int): IntArray = cpu.expectedTokens(surah, ayah)

    override fun expectedWords(surah: Int, ayah: Int): IntArray = cpu.expectedWords(surah, ayah)

    /** The accelerator is the controller's ([AcceleratorHandle.retire]); the CPU standby the recognizer's. */
    override fun close() {}

    /** No more calls: the thread ends once its queue is done (a late call keeps it). */
    fun shutdown() {
        executor.shutdown()
        if (checkerMade) checker.shutdown()
    }

    @Volatile private var checkerMade = false

    /** [body] on the accelerator thread, not awaited (closing a stream after the switch; a warm-up). */
    private fun later(body: () -> Unit) {
        handle.enter()
        try {
            executor.execute { try { body() } catch (_: Throwable) {} finally { handle.exit() } }
        } catch (_: Exception) {
            handle.exit()
        }
    }

    /**
     * The warm-up: one prediction of silence through a scratch stream on the accelerator thread, not awaited (the
     * take's first chunk needs a chunk of audio first; if the warm-up still runs then, that chunk waits behind it
     * under the first-chunk limit). [activation]: the app became active, so the next chunk also gets the first-chunk
     * limit. Its result is not looked at (a fault shows in the take's own chunks), and its time never counts as warm.
     */
    fun warmUp(activation: Boolean = false) {
        if (handle.retired) return
        if (activation) coldNext.set(true) else warmQueued.set(true)
        runWarmUp()
    }

    private fun runWarmUp() {
        val guarded = firstCallDone.compareAndSet(false, true)
        if (guarded) listener.firstCall(false)
        later {
            val t0 = System.nanoTime()
            try {
                prepared.stream(TajweedHead.SCORER_HEADS, sampleRate).use { s ->
                    val z = FloatArray(advance.toInt())
                    // pieces of silence until the network ran once (the front end's lookahead comes first)
                    var n = 0
                    while (n++ < 8 && s.pushAudio(z, 0, z.size).isEmpty()) Unit
                }
            } finally {
                lastWarmUpMs = (System.nanoTime() - t0) / 1e6
                warmUps.incrementAndGet()
                if (guarded) listener.firstCall(true)
            }
        }
    }

    private sealed class Outcome<out T> {
        class Ok<T>(val value: T, val ms: Double) : Outcome<T>()
        class Failed(val error: Throwable, val ms: Double) : Outcome<Nothing>()
        class Late(val ms: Double) : Outcome<Nothing>()
    }

    private class CallState {
        var late = false
        var finished = false
    }

    /** [call] on the accelerator thread within [budgetMs]; a late call is abandoned (counted in [busy] until it returns). */
    private fun <T> race(budgetMs: Double, call: () -> T): Outcome<T> {
        val t0 = System.nanoTime()
        val st = CallState()
        handle.enter()
        val f = try {
            executor.submit(Callable {
                try {
                    call()
                } finally {
                    synchronized(st) {
                        st.finished = true
                        if (st.late) lateCalls.decrementAndGet()
                    }
                    handle.exit()
                }
            })
        } catch (e: Exception) {
            handle.exit()
            return Outcome.Failed(e, 0.0)
        }
        return try {
            val v = f.get((budgetMs * 1e6).toLong(), TimeUnit.NANOSECONDS)
            Outcome.Ok(v, (System.nanoTime() - t0) / 1e6)
        } catch (e: TimeoutException) {
            synchronized(st) {
                if (!st.finished) {
                    st.late = true
                    lateCalls.incrementAndGet()
                }
            }
            Outcome.Late((System.nanoTime() - t0) / 1e6)
        } catch (e: ExecutionException) {
            Outcome.Failed(e.cause ?: e, (System.nanoTime() - t0) / 1e6)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.Failed(e, (System.nanoTime() - t0) / 1e6)
        }
    }

    /** A blank check's outcome; [stream] is the CPU stream it rebuilt (kept open only on a disagreement). */
    private class CheckResult(
        val check: BlankCheck,
        val replayFrom: Int,
        /** The CPU chunks from [replayFrom], in order. */
        val chunks: List<TajweedHeads>,
        /** The input sample (absolute in the take) the replay ended at. */
        val endSample: Long,
        val stream: TajweedEngineStream?,
        val exact: Boolean,
    )

    private val takes = AtomicLong(0)

    private inner class Stream(private val heads: Set<TajweedHead>) : TajweedEngineStream {
        private val take = takes.incrementAndGet()
        @Volatile private var acc: TajweedEngineStream? = null
        private val ring = AudioRing(maxOf(advance * 4, Failover.RING_MS.toLong() * sampleRate / 1000).toInt())
        private val accHeads = ArrayList<TajweedHeads>()
        private val cpuHeads = ArrayList<TajweedHeads>()
        private var cpuStream: TajweedEngineStream? = null
        /** The first chunk taken from the CPU, and the CPU stream's next chunk index. */
        private var cpuFrom = 0
        private var cpuNext = 0
        private var delivered = 0
        private var blankStart = -1
        private var blankMs = 0
        /** No chunk of the take yet (the first-chunk limit applies), or the app became active since the last one. */
        private var cold = true
        private var coldActive = false
        /** The limits of this take (from warm chunks before it). */
        private var warmLimit = limits.warmMs
        private var firstLimit = limits.firstChunkMs
        /** The ring position the last blank check started at, and the check in flight. */
        private var lastCheckAt = Long.MIN_VALUE
        private var check: Future<CheckResult>? = null
        /** The chunk index the last call's chunks start at when they redo delivered chunks, else -1. */
        private var redo = -1

        init {
            // a take started without the recognizer's session start (which queues one): its own warm-up now
            if (!warmQueued.getAndSet(false) && !handle.retired) runWarmUp()
        }

        override val backend: Backend get() = if (cpuStream == null) Backend.NPU else Backend.CPU

        /** Chunks of the take from the accelerator (before any failover). */
        val acceleratorChunks: Int get() = if (cpuStream == null) delivered else cpuFrom

        /** The take left the accelerator: a late call that runs after it must not open a stream (it would never be closed). */
        @Volatile private var dropped = false

        private fun accStream(): TajweedEngineStream {
            if (dropped) throw IllegalStateException("the take left the accelerator")
            return acc ?: prepared.stream(heads, sampleRate).also { acc = it }
        }

        private fun budget(): Double {
            if (coldNext.getAndSet(false)) coldActive = true
            return if (cold || coldActive) firstLimit else warmLimit
        }

        private fun <T> call(budgetMs: Double, body: () -> T): Outcome<T> {
            val isFirst = firstCallDone.compareAndSet(false, true)
            if (isFirst) listener.firstCall(false)
            val r = race(budgetMs, body)
            if (isFirst && r !is Outcome.Late) listener.firstCall(true)
            return r
        }

        override fun takeRedo(): Int = redo.also { redo = -1 }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            redo = -1
            ring.add(samples, offset, length)
            cpuStream?.let { return fromCpu(it.pushAudio(samples, offset, length)) }
            // a check that disagreed: the take continues on its CPU stream (this call's audio is in the ring)
            check?.takeIf { it.isDone }?.let { f ->
                check = null
                val res = f.get()
                if (!res.check.agreed) return adopt(res, finishing = false)
            }
            val out = ArrayList<TajweedHeads>()
            var at = offset
            val end = offset + length
            while (at < end) {
                val k = minOf(advance.toInt(), end - at)
                val wasCold = cold || coldActive || coldNext.get()
                val budget = budget()
                val p = at
                when (val r = call(budget) { accStream().pushAudio(samples, p, k) }) {
                    is Outcome.Failed -> return out + failover(delivered, Failover.EXCEPTION, r.error, r.ms, budget, finishing = false)
                    is Outcome.Late -> return out + failover(delivered, Failover.DEADLINE, null, r.ms, budget, finishing = false)
                    is Outcome.Ok -> {
                        if (r.value.isNotEmpty()) {
                            // only a warm call's time is a measurement
                            if (!wasCold) for (h in r.value) limits.add(r.ms / r.value.size)
                            cold = false
                            coldActive = false
                        }
                        val energy = Failover.rms(samples, p, k)
                        for (h in r.value) {
                            Failover.check(h, range)?.let { bad -> return out + failover(delivered, bad, null, r.ms, budget, finishing = false) }
                            accHeads.add(h)
                            out.add(h)
                            delivered++
                            blankRule(h, energy)
                        }
                    }
                }
                at += k
            }
            return out
        }

        /** Counts a run of energetic all-blank chunks; at [blankRunMs] it starts a CPU check (when allowed) and the run
         *  starts again. Never a failure by itself. */
        private fun blankRule(h: TajweedHeads, energy: Double) {
            if (energy >= Failover.BLANK_RMS && Failover.allBlank(h)) {
                if (blankStart < 0) blankStart = delivered - 1
                blankMs += h.n40 * 40
                if (blankMs >= blankRunMs) {
                    val from = blankStart
                    blankStart = -1
                    blankMs = 0
                    val every = blankCheckEveryMs.toLong() * sampleRate / 1000
                    if (check == null && (lastCheckAt == Long.MIN_VALUE || ring.end - lastCheckAt >= every)) startCheck(from, delivered - 1)
                }
            } else {
                blankStart = -1
                blankMs = 0
            }
        }

        /** Replays the window before chunk [from] and the suspicious chunks [from]..[to] through the CPU, off the audio
         *  path (the audio is copied out of the ring now). */
        private fun startCheck(from: Int, to: Int) {
            var r = maxOf(0, from - windowChunks)
            val firstInRing = ceil(ring.start.toDouble() / advance).toInt()
            if (r < firstInRing) r = firstInRing
            if (r > from) return
            lastCheckAt = ring.end
            val endSample = ring.end
            val audio = ring.copy(r * advance, endSample)
            val accTokens = Failover.tokens(accHeads.subList(from, to + 1))
            val exact = r == 0 && ring.start == 0L
            checkerMade = true
            check = try {
                checker.submit(Callable { runCheck(from, to, r, audio, endSample, accTokens, exact) })
            } catch (_: Exception) {
                null
            }
        }

        private fun runCheck(from: Int, to: Int, r: Int, audio: FloatArray, endSample: Long, accTokens: IntArray, exact: Boolean): CheckResult {
            val t0 = System.nanoTime()
            val warm = cpu.warm
            var s: TajweedEngineStream? = null
            try {
                s = cpu.createStream(heads)
                val got = s.pushAudio(audio, 0, audio.size)
                val window = got.drop(from - r).take(to - from + 1)
                val cpuTokens = Failover.tokens(window)
                val d = SelfTest.editDistance(cpuTokens, accTokens)
                val agreed = d <= blankTokenTolerance
                val ms = (System.nanoTime() - t0) / 1e6
                val c = BlankCheck(from, to, agreed, d, cpuTokens.size, ms, warm)
                if (agreed) {
                    s.close()
                    s = null
                    // no resident cost: the CPU engine closed again, as it was before the check
                    if (!warm) try { cpu.release() } catch (_: Exception) {}
                }
                try { listener.blankChecked(c) } catch (_: Exception) {}
                return CheckResult(c, r, got, endSample, s, exact)
            } catch (e: Throwable) {
                try { s?.close() } catch (_: Exception) {}
                if (!warm) try { cpu.release() } catch (_: Exception) {}
                val c = BlankCheck(from, to, agreed = true, tokenDiffs = 0, cpuTokens = 0, ms = (System.nanoTime() - t0) / 1e6, warm = warm, failed = true)
                try { listener.blankChecked(c) } catch (_: Exception) {}
                return CheckResult(c, r, emptyList(), endSample, null, exact)
            }
        }

        /** Waits for the check in flight (it holds the CPU engine's one stream); null when none. */
        private fun awaitCheck(): CheckResult? {
            val f = check ?: return null
            check = null
            return try { f.get() } catch (_: Exception) { null }
        }

        override fun finish(): List<TajweedHeads> {
            redo = -1
            cpuStream?.let { return fromCpu(it.finish()) }
            awaitCheck()?.let { if (!it.check.agreed) return adopt(it, finishing = true) }
            if (coldNext.getAndSet(false)) coldActive = true
            val budget = if (cold || coldActive) firstLimit else minOf(firstLimit, warmLimit * Failover.FINISH_DEADLINES)
            return when (val r = call(budget) { accStream().finish() }) {
                is Outcome.Failed -> failover(delivered, Failover.EXCEPTION, r.error, r.ms, budget, finishing = true)
                is Outcome.Late -> failover(delivered, Failover.DEADLINE, null, r.ms, budget, finishing = true)
                is Outcome.Ok -> {
                    val out = ArrayList<TajweedHeads>()
                    for (h in r.value) {
                        // the flush has no input of its own: the blank rule does not apply to it
                        Failover.check(h, range)?.let { bad -> return out + failover(delivered, bad, null, r.ms, budget, finishing = true) }
                        accHeads.add(h)
                        out.add(h)
                        delivered++
                    }
                    out
                }
            }
        }

        /** CPU chunks after the switch: those from [cpuFrom] are the take's, those not yet delivered go out. */
        private fun fromCpu(got: List<TajweedHeads>): List<TajweedHeads> {
            val fresh = ArrayList<TajweedHeads>()
            for (h in got) {
                val i = cpuNext++
                if (i >= cpuFrom) cpuHeads.add(h)
                if (i >= delivered) {
                    fresh.add(h)
                    delivered = i + 1
                }
            }
            return fresh
        }

        /** The accelerator's stream goes (closed on its thread, after a late call if one runs); no more calls on it. */
        private fun dropAccelerator() {
            dropped = true
            // closed on the accelerator thread after the calls queued there (a stream a late call opened included)
            later {
                acc?.close()
                acc = null
            }
        }

        /**
         * A confirmed blank disagreement: the take continues on the check's CPU stream (its state rebuilt by the check's
         * replay), the audio since the check is pushed, and the CPU's chunks from the first suspicious one are delivered
         * again ([takeRedo]): the learner gets what the accelerator left out.
         */
        private fun adopt(res: CheckResult, finishing: Boolean): List<TajweedHeads> {
            val t0 = System.nanoTime()
            val s = res.stream ?: return emptyList()
            if (res.endSample < ring.start) {
                // the ring no longer holds the audio since the check (a very slow check): an ordinary switch at the
                // next chunk instead, with the check's verdict as its cause
                try { s.close() } catch (_: Exception) {}
                return failover(delivered, Failover.BLANK, null, res.check.ms, 0.0, finishing, res.check)
            }
            dropAccelerator()
            val demotion = listener.failingOver(Failover.BLANK, take)
            cpuStream = s
            cpuFrom = res.check.fromChunk
            cpuNext = res.replayFrom
            delivered = res.check.fromChunk
            redo = res.check.fromChunk
            val got = ArrayList(res.chunks)
            val more = ring.copy(res.endSample, ring.end)
            if (more.isNotEmpty()) got += s.pushAudio(more, 0, more.size)
            if (finishing) got += s.finish()
            val fresh = fromCpu(got)
            val stall = (System.nanoTime() - t0) / 1e6
            listener.failedOver(FailoverEvent(Failover.BLANK, res.check.fromChunk, stall, res.check.ms, 0.0, null, res.check.warm,
                res.check.fromChunk - res.replayFrom, res.exact, demotion, res.check.divergence()))
            return fresh
        }

        /**
         * The switch, on the session's thread: demote (by cause), open a CPU stream (loading the weights when they were
         * released), replay the ring from [Failover.REPLAY_WINDOW_MS] before chunk [f] (aligned to the chunk grid; from
         * the take's start when it fits), and return the CPU chunks not yet delivered. A blank check in flight is waited
         * for first (it holds the CPU engine's one stream); when it disagreed, its stream continues the take.
         */
        private fun failover(f: Int, cause: String, error: Throwable?, elapsedMs: Double, budgetMs: Double, finishing: Boolean,
                             blank: BlankCheck? = null): List<TajweedHeads> {
            awaitCheck()?.let { if (!it.check.agreed) return adopt(it, finishing) }
            val t0 = System.nanoTime()
            dropAccelerator()
            if (cold || !firstCallDone.get()) listener.firstCall(true)
            val demotion = listener.failingOver(cause, take)
            val warm = cpu.warm
            val s = cpu.createStream(heads)
            cpuStream = s
            var r = maxOf(0, f - windowChunks)
            val firstInRing = ceil(ring.start.toDouble() / advance).toInt()
            if (r < firstInRing) r = firstInRing
            cpuFrom = f
            cpuNext = r
            val audio = ring.copy(r * advance, ring.end)
            val got = ArrayList(s.pushAudio(audio, 0, audio.size))
            if (finishing) got += s.finish()
            val fresh = fromCpu(got)
            val stall = (System.nanoTime() - t0) / 1e6
            listener.failedOver(FailoverEvent(cause, f, stall, elapsedMs, budgetMs, error, warm, f - r, r == 0 && ring.start == 0L,
                demotion, blank?.divergence()))
            return fresh
        }

        override fun takeHeads(): List<TajweedHeads>? =
            if (cpuStream == null) null else accHeads.subList(0, minOf(cpuFrom, accHeads.size)) + cpuHeads

        override fun reset() {
            awaitCheck()?.stream?.close()
            cpuStream?.close()
            cpuStream = null
            ring.clear()
            accHeads.clear()
            cpuHeads.clear()
            delivered = 0
            cpuFrom = 0
            cpuNext = 0
            blankStart = -1
            blankMs = 0
            lastCheckAt = Long.MIN_VALUE
            cold = true
            coldActive = false
            warmLimit = limits.warmMs
            firstLimit = limits.firstChunkMs
            // a fresh accelerator stream for the new take (the old one closed on its thread)
            dropAccelerator()
            later { dropped = false }
        }

        override fun close() {
            // a check in flight holds the CPU engine's one stream: wait for it, then close what it kept
            awaitCheck()?.stream?.close()
            cpuStream?.close()
            cpuStream = null
            dropAccelerator()
        }
    }

    /** Whether [s] (a stream of this engine) ran mostly on the accelerator: its take is scored with [headsBackend]. */
    fun mostlyAccelerator(s: TajweedEngineStream, chunks: Int): Boolean =
        (s as? Stream)?.let { 2 * it.acceleratorChunks > chunks } ?: false
}

/** The divergence of a blank check (incident `detail.divergence`: tokens only, the session is not scored mid-take). */
internal fun BlankCheck.divergence(): Divergence = Divergence(cpuTokens, tokenDiffs, 0, 0)

/**
 * The prepared accelerator shared by the session engines over it: closed once, when it is retired (demoted, or the
 * controller closed) and no call is running on it. A late call keeps it open until the call returns (closing a
 * runtime under a running call could crash the process).
 */
class AcceleratorHandle(val accelerator: PreparedAccelerator) {
    private val inflight = AtomicInteger(0)
    private val retiredFlag = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val retired: Boolean get() = retiredFlag.get()

    fun enter() { inflight.incrementAndGet() }

    fun exit() { if (inflight.decrementAndGet() == 0 && retiredFlag.get()) close() }

    /** Closes now when idle, else when the last call returns. */
    fun retire() {
        retiredFlag.set(true)
        if (inflight.get() == 0) close()
    }

    private fun close() {
        if (closed.compareAndSet(false, true)) try { accelerator.close() } catch (_: Exception) {}
    }
}

/** The take's recent input at the app's rate: [capacity] samples, with absolute positions ([start] until [end]). */
internal class AudioRing(private val capacity: Int) {
    private val a = FloatArray(capacity)
    var end = 0L
        private set
    val start: Long get() = maxOf(0L, end - capacity)

    fun add(v: FloatArray, offset: Int, length: Int) {
        var o = offset
        var n = length
        if (n > capacity) {
            o += n - capacity
            end += (n - capacity).toLong()
            n = capacity
        }
        var at = (end % capacity).toInt()
        while (n > 0) {
            val k = minOf(n, capacity - at)
            System.arraycopy(v, o, a, at, k)
            o += k
            n -= k
            end += k
            at = (at + k) % capacity
        }
    }

    /** Samples [from] until [to] (absolute; [from] at least [start]). */
    fun copy(from: Long, to: Long): FloatArray {
        require(from >= start && to <= end && from <= to) { "$from..$to outside $start..$end" }
        val out = FloatArray((to - from).toInt())
        var p = from
        var o = 0
        while (p < to) {
            val at = (p % capacity).toInt()
            val k = minOf((to - p).toInt(), capacity - at)
            System.arraycopy(a, at, out, o, k)
            o += k
            p += k
        }
        return out
    }

    fun clear() { end = 0 }
}
