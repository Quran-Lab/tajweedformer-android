package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.HeadKind
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * In-session failover (the constants in one place).
 *
 * A chunk of an accelerator session failed when the call throws, returns NaN or inf, returns values outside the
 * build's range ([HeadRange]), returns all-blank chunks for [BLANK_RUN_MS] while the audio has energy, or misses
 * its deadline ([deadlineMs]). The session then switches to the exact CPU engine within the call: the last
 * [REPLAY_WINDOW_MS] of the take's audio (the ring buffer) are replayed through a fresh CPU stream to rebuild its
 * caches, the failed chunk is redone on the CPU, and the take continues there. The chunks the app already got
 * stay; the take's heads for scoring are the accelerator's before the failed chunk and the CPU's from it.
 */
object Failover {
    /** The deadline's cap and its value before any measurement exists. */
    const val DEADLINE_CAP_MS = 300.0

    /**
     * The deadline's floor: a fast NPU's 4 x p90 can be 10 to 20 ms, inside the scheduling jitter of a phone's
     * background thread, and a missed deadline demotes the accelerator for a day. Real hangs are caught at 50 ms.
     */
    const val DEADLINE_FLOOR_MS = 50.0

    /**
     * A stream's first call (it also builds the stream: the front end, the NPU streamer) gets this many deadlines,
     * at most [FIRST_CALL_CAP_MS]: a cold first chunk is not a hang.
     */
    const val FIRST_CALL_DEADLINES = 4
    const val FIRST_CALL_CAP_MS = 1200.0

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

    /** A run of all-blank chunks with audio energy this long fails the accelerator (a long madd is about 3 s). */
    const val BLANK_RUN_MS = 4000

    /** Audio energy for the blank rule: RMS of the chunk's input at or above -40 dBFS. */
    const val BLANK_RMS = 0.01

    /** The ring buffer keeps this much audio (the window, a blank run and a margin). */
    const val RING_MS = REPLAY_WINDOW_MS + BLANK_RUN_MS + 2000

    /** `failover_causes` keys. */
    const val EXCEPTION = "exception"
    const val NON_FINITE = "non_finite"
    const val OUT_OF_RANGE = "out_of_range"
    const val BLANK = "blank"
    const val DEADLINE = "deadline"

    @JvmField val CAUSES = listOf(EXCEPTION, NON_FINITE, OUT_OF_RANGE, BLANK, DEADLINE)

    /** min(300 ms, max(4 x p90, 2 x median)) of the accelerator's chunk times on this device (the measure stage),
     *  never below [DEADLINE_FLOOR_MS]; 300 ms before a measurement exists. */
    @JvmStatic
    fun deadlineMs(medianMs: Double, p90Ms: Double): Double {
        if (!(medianMs > 0) || !(p90Ms > 0)) return DEADLINE_CAP_MS
        return minOf(DEADLINE_CAP_MS, maxOf(4 * p90Ms, 2 * medianMs, DEADLINE_FLOOR_MS))
    }

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
}

/**
 * The values an accelerator's heads can take: log-probs at most [logProbMax] and at least [logProbFloor], probabilities
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
}

/** One in-session failover, for telemetry and the incident report. */
data class FailoverEvent(
    val cause: String,
    /** The failed chunk (the first redone on the CPU), 0-based in the take. */
    val chunk: Int,
    /** From the failure detected to the failed chunk's CPU result computed, ms (the replay included). */
    val stallMs: Double,
    /** How long the failed accelerator call ran, and its deadline, ms. */
    val elapsedMs: Double,
    val deadlineMs: Double,
    val error: Throwable?,
    /** The CPU weights were loaded when it happened (else they were loaded during the stall). */
    val warm: Boolean,
    /** Chunks replayed before the failed one; [exact] when the replay started at the take's first sample. */
    val replayedChunks: Int,
    val exact: Boolean,
)

/** What the session engine tells the controller. */
interface FailoverListener {
    /** Before the first accelerator call of a stream ([done] false) and after it returned ([done] true): the crash
     *  sentinel around a first inference. */
    fun firstCall(done: Boolean) {}

    /** The accelerator failed: demote it now (later sessions start on the CPU). Called before the switch. */
    fun failingOver(cause: String) {}

    /** The switch is done (the failed chunk's CPU result is about to be delivered). */
    fun failedOver(event: FailoverEvent) {}
}

/**
 * A session engine on the accelerator with the CPU as its standby ([CpuStandby]). Every accelerator call runs on this
 * engine's own thread, raced with the deadline: a hung call is abandoned (never awaited again, its result dropped,
 * the accelerator closed only once the call returns). Audio goes to the accelerator in pieces of at most one chunk's
 * advance, so a call completes at most one chunk. The take's audio is kept in a ring buffer of [Failover.RING_MS].
 */
class FailoverEngine(
    private val handle: AcceleratorHandle,
    private val cpu: CpuStandby,
    private val sampleRate: Int,
    /** Per call ([Failover.deadlineMs] of the measure stage). */
    val deadlineMs: Double,
    private val range: HeadRange,
    private val listener: FailoverListener,
    /** Chunks replayed before the failed one ([Failover.REPLAY_WINDOW_MS] / chunk). */
    private val windowChunks: Int = Failover.REPLAY_WINDOW_MS / handle.accelerator.chunkMs,
    private val blankRunMs: Int = Failover.BLANK_RUN_MS,
) : TajweedEngine {
    private val prepared: PreparedAccelerator get() = handle.accelerator
    override val chunkMs: Int get() = prepared.chunkMs
    override val backend: Backend get() = if (failed.get()) Backend.CPU else Backend.NPU

    /** The thresholds an accelerator take is scored with. */
    val headsBackend: TajweedBackend get() = prepared.headsBackend

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tajweedformer-accelerator").apply { isDaemon = true }
    }
    private val failed = AtomicBoolean(false)
    private val hung = AtomicBoolean(false)
    private val firstCallDone = AtomicBoolean(false)

    /** Input samples per chunk at the app's rate (the CPU stream restarts on this grid). */
    private val advance: Long = prepared.chunkMs.toLong() * sampleRate / 1000

    /** Whether this engine runs [p] over [standby] at [rate] (the caller may keep using it). */
    fun belongsTo(p: PreparedAccelerator, standby: CpuStandby, rate: Int): Boolean =
        handle.accelerator === p && cpu === standby && rate == sampleRate && !handle.retired

    override fun createStream(): TajweedEngineStream = createStream(TajweedHead.SCORER_HEADS)

    override fun createStream(heads: Set<TajweedHead>): TajweedEngineStream =
        if (failed.get()) cpu.createStream(heads) else Stream(heads)

    override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult =
        cpu.score(heads, config, settings, surah, ayah)

    override fun expectedTokens(surah: Int, ayah: Int): IntArray = cpu.expectedTokens(surah, ayah)

    override fun expectedWords(surah: Int, ayah: Int): IntArray = cpu.expectedWords(surah, ayah)

    /** The accelerator is the controller's ([AcceleratorHandle.retire]); the CPU standby the recognizer's. */
    override fun close() {}

    /** No more calls: the thread ends once its queue is done (a hung call keeps it). */
    fun shutdown() = executor.shutdown()

    /** [body] on the accelerator thread, not awaited (closing a stream after the switch). */
    private fun later(body: () -> Unit) {
        handle.enter()
        try {
            executor.execute { try { body() } catch (_: Exception) {} finally { handle.exit() } }
        } catch (_: Exception) {
            handle.exit()
        }
    }

    private sealed class Outcome<out T> {
        class Ok<T>(val value: T, val ms: Double) : Outcome<T>()
        class Failed(val error: Throwable, val ms: Double) : Outcome<Nothing>()
        class Late(val ms: Double) : Outcome<Nothing>()
    }

    /** [call] on the accelerator thread within [budgetMs]; a late call is abandoned. */
    private fun <T> race(budgetMs: Double, call: () -> T): Outcome<T> {
        val t0 = System.nanoTime()
        handle.enter()
        val f = try {
            executor.submit(Callable { try { call() } finally { handle.exit() } })
        } catch (e: Exception) {
            handle.exit()
            return Outcome.Failed(e, 0.0)
        }
        return try {
            val v = f.get((budgetMs * 1e6).toLong(), TimeUnit.NANOSECONDS)
            Outcome.Ok(v, (System.nanoTime() - t0) / 1e6)
        } catch (e: TimeoutException) {
            hung.set(true)
            Outcome.Late((System.nanoTime() - t0) / 1e6)
        } catch (e: ExecutionException) {
            Outcome.Failed(e.cause ?: e, (System.nanoTime() - t0) / 1e6)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.Failed(e, (System.nanoTime() - t0) / 1e6)
        }
    }

    private inner class Stream(private val heads: Set<TajweedHead>) : TajweedEngineStream {
        private var acc: TajweedEngineStream? = null
        private var first = true
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

        override val backend: Backend get() = if (cpuStream == null) Backend.NPU else Backend.CPU

        /** Chunks of the take from the accelerator (before any failover). */
        val acceleratorChunks: Int get() = if (cpuStream == null) delivered else cpuFrom

        private fun accStream(): TajweedEngineStream = acc ?: prepared.stream(heads, sampleRate).also { acc = it }

        private fun budget(): Double =
            if (first) minOf(Failover.FIRST_CALL_CAP_MS, maxOf(deadlineMs, Failover.FIRST_CALL_DEADLINES * deadlineMs)) else deadlineMs

        private fun <T> call(budgetMs: Double, body: () -> T): Outcome<T> {
            val isFirst = first && firstCallDone.compareAndSet(false, true)
            if (isFirst) listener.firstCall(false)
            val r = race(budgetMs, body)
            first = false
            if (isFirst && r !is Outcome.Late) listener.firstCall(true)
            return r
        }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            ring.add(samples, offset, length)
            cpuStream?.let { return fromCpu(it.pushAudio(samples, offset, length)) }
            val out = ArrayList<TajweedHeads>()
            var at = offset
            val end = offset + length
            while (at < end) {
                val k = minOf(advance.toInt(), end - at)
                val budget = budget()
                val p = at
                when (val r = call(budget) { accStream().pushAudio(samples, p, k) }) {
                    is Outcome.Failed -> return out + failover(delivered, Failover.EXCEPTION, r.error, r.ms, budget, finishing = false)
                    is Outcome.Late -> return out + failover(delivered, Failover.DEADLINE, null, r.ms, budget, finishing = false)
                    is Outcome.Ok -> {
                        val energy = Failover.rms(samples, p, k)
                        for (h in r.value) {
                            val bad = judge(h, energy)
                            if (bad != null) return out + failover(if (bad == Failover.BLANK) blankStart else delivered, bad, null, r.ms, budget, finishing = false)
                            accHeads.add(h)
                            out.add(h)
                            delivered++
                        }
                    }
                }
                at += k
            }
            return out
        }

        /** Null when the chunk is good; the blank rule counts a run of energetic all-blank chunks. */
        private fun judge(h: TajweedHeads, energy: Double): String? {
            Failover.check(h, range)?.let { return it }
            if (energy >= Failover.BLANK_RMS && Failover.allBlank(h)) {
                if (blankStart < 0) blankStart = delivered
                blankMs += h.n40 * 40
                if (blankMs >= blankRunMs) return Failover.BLANK
            } else {
                blankStart = -1
                blankMs = 0
            }
            return null
        }

        override fun finish(): List<TajweedHeads> {
            cpuStream?.let { return fromCpu(it.finish()) }
            val budget = minOf(Failover.FIRST_CALL_CAP_MS, budget() * Failover.FINISH_DEADLINES)
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

        /**
         * The switch, on the session's thread: demote, open a CPU stream (loading the weights when they were released),
         * replay the ring from [Failover.REPLAY_WINDOW_MS] before chunk [f] (aligned to the chunk grid; from the take's
         * start when it fits), and return the CPU chunks not yet delivered.
         */
        private fun failover(f: Int, cause: String, error: Throwable?, elapsedMs: Double, budgetMs: Double, finishing: Boolean): List<TajweedHeads> {
            val t0 = System.nanoTime()
            failed.set(true)
            acc?.let { a -> if (!hung.get()) later { a.close() } }
            if (first || !firstCallDone.get()) listener.firstCall(true)
            listener.failingOver(cause)
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
            listener.failedOver(FailoverEvent(cause, f, stall, elapsedMs, budgetMs, error, warm, f - r, r == 0 && ring.start == 0L))
            return fresh
        }

        override fun takeHeads(): List<TajweedHeads>? =
            if (cpuStream == null) null else accHeads.subList(0, minOf(cpuFrom, accHeads.size)) + cpuHeads

        override fun reset() {
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
            acc?.let { a -> if (!hung.get()) race(deadlineMs * Failover.FIRST_CALL_DEADLINES) { a.reset() } }
        }

        override fun close() {
            cpuStream?.close()
            cpuStream = null
            val a = acc ?: return
            acc = null
            if (!hung.get()) later { a.close() }
        }
    }

    /** Whether [s] (a stream of this engine) ran mostly on the accelerator: its take is scored with [headsBackend]. */
    fun mostlyAccelerator(s: TajweedEngineStream, chunks: Int): Boolean =
        (s as? Stream)?.let { 2 * it.acceleratorChunks > chunks } ?: false
}

/**
 * The prepared accelerator shared by the session engines over it: closed once, when it is retired (demoted, or the
 * controller closed) and no call is running on it. A hung call keeps it open until the call returns (closing a
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
