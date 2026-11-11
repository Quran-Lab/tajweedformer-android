package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.cloud.TajweedObserver
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One take boundary of a recording: where the take's first sample is in the stream, and the wall gap before it. */
data class Take(
    /** floor(1000 x input samples of the recording before the take / input rate). */
    val startMs: Long,
    /** The wall time from the previous take's end to this take's start, rounded to the nearest 100 ms (0 for the first). */
    val gapMs: Int,
)

/**
 * Back-to-back takes share one sharing recording (SDK 2.0.3), the same in every SDK and replayed against the shared
 * vector `takes_join.json`:
 *
 * - When a take ends while sharing is on, its recording stays open for the JOIN WINDOW ([window]: the signed
 *   config's `flags.share_join_ms`, a whole number 0 to 10000; absent or anything else 3000; 0 never joins).
 * - The next take joins it when it starts within the window (its start minus the end of the previous take that had
 *   audio, wall ms, at most the window) with the same input sample rate, while the app is in the foreground, sharing
 *   is still on, and the contributor, the consent version and the model revision are the recording's: its audio
 *   continues the same Ogg stream (no new headers; the encoder and the resampler keep their state, so a partial Opus
 *   frame left by one take is completed by the next take's first samples; the segment plan and the bitrate ladder
 *   run on). The gap gets no audio.
 * - Otherwise the recording closes as a 2.0.2 session ends, and the take starts a new one. The window expiring, the
 *   app going to the background, sharing going off, [CAP_MS] of audio or [MAX_TAKES] takes also close it at once.
 * - A recording keeps its takes `[{start_ms, gap_ms}]` (the first `{0, 0}`, strictly increasing `start_ms`); one take
 *   sends none. A take without audio adds no entry and does not move the previous take's end.
 *
 * Tried and rejected: inserting silence for the gap (made-up audio in training data); holding the upload back until
 * the recording closes so the session JSON could list the takes (a long recording would wait to upload, and its
 * backlog would push the bitrate ladder down).
 */
object TakeJoin {
    const val DEFAULT_WINDOW_MS = 3000
    const val MAX_WINDOW_MS = 10_000
    /** A recording holds at most 20 minutes of audio: a take that would take it further starts a new one. */
    const val CAP_MS = 20 * 60 * 1000L
    /** The take list call takes at most 256 entries: the 257th take starts a new recording. */
    const val MAX_TAKES = 256
    /**
     * Whether a take fits under [CAP_MS] is known only once it ended. Within the last [HOLD_MS] of room, a joining
     * take's audio is held in memory (at most [HOLD_MS] of it) until it ends (it joins) or outgrows the room (the
     * recording closes without it and the take starts a new one with the held audio), so the cap is exact there. A
     * take that joins with more room and runs past [CAP_MS] stays whole and the recording closes when it ends.
     * Tried and rejected: holding every joining take (a crash would lose it, and its segments would wait); cutting
     * the recording at the cap inside a take (it would split speech, what 2.0.3 removes).
     */
    const val HOLD_MS = 30_000L

    /** `flags.share_join_ms`: a whole number 0 to 10000, else [DEFAULT_WINDOW_MS] (a JSON number reads as a Double). */
    @JvmStatic
    fun window(v: Any?): Int {
        if (v is Boolean || v !is Number) return DEFAULT_WINDOW_MS
        val d = v.toDouble()
        return if (d == Math.rint(d) && d >= 0 && d <= MAX_WINDOW_MS) d.toInt() else DEFAULT_WINDOW_MS
    }

    /** The wall gap rounded to the nearest 100 ms (half up), within 0 to [MAX_WINDOW_MS] (a clock set back gives 0). */
    @JvmStatic
    fun gapMs(gap: Long): Int = ((gap.coerceIn(0, MAX_WINDOW_MS.toLong()) + 50) / 100 * 100).toInt()

    /** Milliseconds of [samples] at [rate], floored. */
    @JvmStatic
    fun ms(samples: Long, rate: Int): Long = samples * 1000 / rate

    /** A take of [take] samples fits a recording of [recording] samples (both at [rate]). */
    @JvmStatic
    fun fits(recording: Long, take: Long, rate: Int): Boolean = ms(recording, rate) + ms(take, rate) <= CAP_MS
}

/** What the recorder drives: one Ogg stream in one outbox folder ([ShareCapture]; tests pass their own). */
internal interface Recording {
    /** Audio of the take that is running, at its input rate. */
    fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int)
    /** The recording's takes so far (2 or more), written where recovery and the outbox read them. */
    fun takes(list: List<Take>)
    /** Ends the stream: [completed] is the last take's. */
    fun finish(completed: Boolean)
    /** Sharing went off: ends the recording and keeps nothing of it that is not uploaded yet. */
    fun abandon()
    /** Waits until the audio given so far is encoded and on disk. */
    fun sync() {}
    /** False once the recording failed or ended by itself (a storage error, sharing off): no take joins it. */
    val alive: Boolean get() = true
}

/** Runs a task after a delay (wall ms); the returned function cancels it. The window's timer; tests pass their own. */
internal fun interface JoinTimer {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit

    companion object {
        /** A daemon thread of the process, started with the first window. */
        val DEFAULT = JoinTimer { ms, task ->
            val f = joinTimerPool.schedule({ try { task() } catch (_: Exception) {} }, ms, TimeUnit.MILLISECONDS)
            return@JoinTimer { f.cancel(false); Unit }
        }
    }
}

private val joinTimerPool: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tj-share-join").apply { isDaemon = true } }
}

/**
 * Keeps the sharing recording across takes (SDK 2.0.3): the model session (the take) stays as the app drives it, and the
 * recording, its Ogg stream, outbox item and session id, lives across back-to-back takes by the rules of [TakeJoin].
 * The recognizer's hooks call [takeStarted], [audio] and [takeEnded]; the app's life calls [background] and
 * [foreground]; sharing going off calls [stop]. Every decision reads [clock] (the window is wall time); the timer only
 * makes the window's end prompt, and the clock decides whether it passed. Thread safe: the hooks, the timer, the
 * app's life and the settings switch arrive on different threads.
 *
 * A take's input rate is known with its first audio, so that is where a take that may join (decided when it started)
 * joins the open recording or, at another rate, closes it and starts its own.
 */
internal class ShareRecorder(
    private val clock: () -> Long,
    /** The join window in force, ms (0: never join). */
    private val window: () -> Int,
    /** Sharing is on now (the learner's consent, the signed config). */
    private val active: () -> Boolean,
    /** What a recording made for this take is bound to (the contributor, the consent version, the model revision). */
    private val identity: (TajweedObserver.SessionStart) -> Any?,
    /** A new recording for a take, or null when none can be made (sharing off, no contributor key). */
    private val open: (TajweedObserver.SessionStart) -> Recording?,
) {
    @Volatile var timer: JoinTimer = JoinTimer.DEFAULT

    private class Open(val rec: Recording, val identity: Any?) {
        /** The input rate (the first audio's); audio at another rate inside a take is ignored (one rate per recording). */
        var rate = 0
        var samples = 0L
        val takes = arrayListOf(Take(0, 0))
        /** The last take with audio completed. */
        var completed = false
        val ms: Long get() = if (rate == 0) 0 else TakeJoin.ms(samples, rate)
    }

    /** A joining take's audio held near the cap ([TakeJoin.HOLD_MS]). */
    private class Hold(val gapMs: Int) {
        val chunks = ArrayList<FloatArray>()
        var samples = 0L
    }

    private var rec: Open? = null
    private var take: TajweedObserver.SessionStart? = null
    private var takeSamples = 0L
    /** The gap of a take that may join the open recording, until its first audio (a take without audio joins nothing). */
    private var joinGap: Int? = null
    private var hold: Hold? = null
    /** The wall time the last take with audio ended. */
    private var lastEnd = 0L
    private var backgrounded = false
    private var cancelTimer: (() -> Unit)? = null
    private var armed = 0

    /** The last recording closed (tests wait on its encoder). */
    @Volatile var lastClosed: Recording? = null
        private set

    /** The open recording (between takes too), or null. */
    val current: Recording? @Synchronized get() = rec?.rec

    /** The open recording's takes so far. */
    val takes: List<Take> @Synchronized get() = rec?.takes?.toList() ?: emptyList()

    /** A take starts: it may join the open recording (decided here, done at its first audio) or starts a new one. */
    @Synchronized
    fun takeStarted(e: TajweedObserver.SessionStart) {
        // a take that never ended: as in 2.0.2 its recording ends there, not completed. When that take really ended is
        // not known, so this one does not join it.
        if (take != null) {
            takeEnded(false)
            rec?.let { close(it) }
        }
        take = e
        takeSamples = 0
        joinGap = null
        hold = null
        val o = rec
        if (o != null) {
            disarm()
            val w = window()
            val gap = clock() - lastEnd
            if (w > 0 && gap <= w && !backgrounded && active() && o.rec.alive && o.ms < TakeJoin.CAP_MS &&
                o.takes.size < TakeJoin.MAX_TAKES && o.identity == identity(e)) {
                joinGap = TakeJoin.gapMs(gap)
                return
            }
            close(o)
        }
        start(e)
    }

    /** A new recording for the running take, or none. */
    private fun start(e: TajweedObserver.SessionStart): Open? {
        rec = null
        if (!active()) return null
        val r = open(e) ?: return null
        return Open(r, identity(e)).also { rec = it }
    }

    /** Audio of the running take; nothing outside a take. */
    @Synchronized
    fun audio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        val e = take ?: return
        if (length <= 0) return
        var o = rec ?: return
        val gap = joinGap
        if (gap != null) {
            // the first audio of a take that may join: its rate is known now
            joinGap = null
            if (sampleRate != o.rate) {
                // another input rate: the open recording closes, and the take starts its own
                close(o)
                o = start(e) ?: return
            } else if (TakeJoin.CAP_MS - o.ms <= TakeJoin.HOLD_MS) {
                hold = Hold(gap)
            } else {
                boundary(o, gap)
            }
        }
        if (o.rate == 0) o.rate = sampleRate else if (sampleRate != o.rate) return
        val h = hold
        if (h != null) {
            h.chunks.add(samples.copyOfRange(offset, offset + length))
            h.samples += length
            takeSamples += length
            if (!TakeJoin.fits(o.samples, h.samples, o.rate)) overflow(o, h)
            return
        }
        o.rec.append(samples, offset, length, sampleRate)
        o.samples += length
        takeSamples += length
    }

    /** A take joins at the recording's current position: its boundary, when it lies after the last one. */
    private fun boundary(o: Open, gapMs: Int) {
        val at = o.ms
        if (at <= o.takes.last().startMs) return
        o.takes.add(Take(at, gapMs))
        o.rec.takes(o.takes.toList())
    }

    /** The held take outgrew the room: the recording closes without it, and the take starts a new one. */
    private fun overflow(o: Open, h: Hold) {
        hold = null
        close(o)
        val n = take?.let(::start) ?: return
        n.rate = o.rate
        for (c in h.chunks) { n.rec.append(c, 0, c.size, n.rate); n.samples += c.size }
    }

    /** The take ended: its recording waits for the next take within the window, or closes now. */
    @Synchronized
    fun takeEnded(completed: Boolean) {
        if (take == null) return
        take = null
        joinGap = null
        val o = rec ?: run { hold = null; return }
        hold?.let { h ->
            // the held take ended within the room: it joins
            hold = null
            boundary(o, h.gapMs)
            for (c in h.chunks) { o.rec.append(c, 0, c.size, o.rate); o.samples += c.size }
        }
        val now = clock()
        if (takeSamples > 0) { o.completed = completed; lastEnd = now }
        val w = window()
        if (o.samples == 0L || w <= 0 || backgrounded || !active() || !o.rec.alive || o.ms >= TakeJoin.CAP_MS ||
            o.takes.size >= TakeJoin.MAX_TAKES || now - lastEnd > w) {
            close(o)
            return
        }
        arm(w - (now - lastEnd))
    }

    /** The window's end: closes the waiting recording once [clock] says the window passed (tests call it too). */
    @Synchronized
    fun checkWindow() = expire(armed)

    private fun arm(remainingMs: Long) {
        disarm()
        val gen = ++armed
        cancelTimer = timer.schedule(maxOf(0L, remainingMs) + 1) { synchronized(this) { expire(gen) } }
    }

    private fun disarm() {
        cancelTimer?.invoke()
        cancelTimer = null
        armed++
    }

    private fun expire(gen: Int) {
        if (gen != armed || take != null) return
        val o = rec ?: return
        val w = window()
        val waited = clock() - lastEnd
        // a clock set back while the recording waited: the timer ran its time, so the recording closes rather than
        // waiting for a moment that may never come
        if (w <= 0 || waited > w || waited < 0) close(o) else arm(w - waited)
    }

    /** The app went to the background: a waiting recording closes now, a running take's when it ends. */
    @Synchronized
    fun background() {
        backgrounded = true
        val o = rec ?: return
        if (take == null) close(o)
    }

    @Synchronized
    fun foreground() { backgrounded = false }

    /** Sharing went off (withdrawal, deletion, the signed config): the recording ends at once, whatever the take does. */
    @Synchronized
    fun stop() {
        hold = null
        joinGap = null
        rec?.let { close(it, keep = false) }
    }

    @Synchronized
    fun sync() { rec?.rec?.sync() }

    private fun close(o: Open, keep: Boolean = true) {
        disarm()
        if (rec === o) rec = null
        if (keep) o.rec.finish(o.completed) else o.rec.abandon()
        lastClosed = o.rec
    }
}
