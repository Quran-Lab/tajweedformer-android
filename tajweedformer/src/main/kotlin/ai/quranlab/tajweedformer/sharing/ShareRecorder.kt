package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.cloud.TajweedObserver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One take boundary of a recording: where the take's first sample is in the stream, and the wall gap before it. */
internal data class Take(
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
 * - A recording keeps its takes `[{start_ms, gap_ms}]` (the first with gap 0 and, without a pre-roll, start 0;
 *   strictly increasing `start_ms`); one take sends none. A take without audio adds no entry and does not move the
 *   previous take's end.
 *
 * SDK 3.0 adds the feed (audio the app captures outside takes: the pre-roll before a take and the tail after it),
 * `take_seq` and `gap_ms_prev`, and the cut at the cap. The rules are those of the shared vector `sharing_30.json`
 * (its README, section 10), replayed case by case in the tests.
 *
 * Tried and rejected: inserting silence for the gap (made-up audio in training data); holding the upload back until
 * the recording closes so the session JSON could list the takes (a long recording would wait to upload, and its
 * backlog would push the bitrate ladder down).
 */
internal object TakeJoin {
    const val DEFAULT_WINDOW_MS = 3000
    const val MAX_WINDOW_MS = 10_000
    /** A recording holds at most 20 minutes of audio: a take that would take it further starts a new one. */
    const val CAP_MS = 20 * 60 * 1000L
    /** The take list call takes at most 256 entries: the 257th take starts a new recording. */
    const val MAX_TAKES = 256
    /**
     * Whether a take fits under [CAP_MS] is known only once it ended. A take that can join a recording with
     * [HOLD_MS] or less of room is held in memory, its pre-roll first (at most [HOLD_MS] and a second of audio),
     * until it ends (it joins) or outgrows the room (the recording closes without it and the take starts a new one
     * with the held audio). The room is counted in samples: a held take joins when the recording, the pre-roll and
     * the take together are at most [capSamples], so a held take that joins is never cut. Every other take is written as it comes, and the recording closes on the sample that
     * fills it ([capSamples]): the take's next sample starts a new recording (SDK 3.0; 2.0.3 let such a take run
     * past the cap). Tried and rejected: holding every joining take (a crash would lose it, and its segments would
     * wait).
     */
    const val HOLD_MS = 30_000L

    /** The fed audio continues the audio before it when it comes no later than its own length plus this, ms. */
    const val STALE_MS = 500L

    /**
     * The tail of a take can take fed audio until this long after the take ended (the shared rule F8, the same number
     * in the four SDKs): a later buffer goes to the ring. It is also how long a recording waits for its tail when
     * the join window is shorter.
     */
    const val TAIL_WAIT_MS = 1_500L

    /** `gap_ms_prev` is sent up to an hour. */
    const val GAP_PREV_MAX_MS = 3_600_000L

    /** The samples a recording at [rate] may hold: 20 minutes. It never holds more. */
    @JvmStatic
    fun capSamples(rate: Int): Long = CAP_MS / 1000 * rate

    /** `gap_ms_prev`: the wall gap rounded to the nearest 100 ms (half up); null (absent) when negative or over an hour. */
    @JvmStatic
    fun gapPrevMs(gap: Long): Int? = if (gap < 0 || gap > GAP_PREV_MAX_MS) null else minOf((gap + 50) / 100 * 100, GAP_PREV_MAX_MS).toInt()

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

/** A new recording's place among the install's shared recordings (SPEC 11.3), for its session JSON. */
internal class RecordingOrder(
    /** `take_seq`: this install's count of shared recordings, from 1. */
    val takeSeq: Int,
    /** `gap_ms_prev`: wall ms from the end of the last take of the install's previous recording to this one's
     *  start, a multiple of 100 up to an hour; null: absent. 0 for the rest of a take that was cut at the cap. */
    val gapMsPrev: Int?,
)

/**
 * This install's count of shared recordings and when its last recorded take ended: what `take_seq` and `gap_ms_prev`
 * are made from. Kept in [file] (when there is one) so both go on across launches. "Delete what I shared" forgets
 * them. Neither is an identifier: a small counter and a time of day the server never sees.
 */
internal class TakeSequence(private val file: File? = null) {
    /** Recordings started so far. */
    var count = 0
        private set
    /** The wall time the last recorded take ended, or null. */
    var lastTakeEnd: Long? = null
        private set

    init {
        val o = file?.let { ShareFiles.readJson(it) }
        if (o != null) {
            count = (o["count"] as? Double)?.takeIf { it >= 0 && it == Math.rint(it) }?.toInt() ?: 0
            lastTakeEnd = (o["last_take_end"] as? Double)?.toLong()
        }
    }

    /** A recording starts: its `take_seq`. */
    @Synchronized
    fun started(): Int {
        count++
        save()
        return count
    }

    @Synchronized
    fun takeEnded(at: Long) {
        lastTakeEnd = at
        save()
    }

    @Synchronized
    fun forget() {
        count = 0
        lastTakeEnd = null
        try { file?.delete() } catch (_: Exception) {}
    }

    private fun save() {
        val f = file ?: return
        // a counter that cannot be written goes on in memory for this launch
        try {
            f.parentFile?.mkdirs()
            ShareFiles.writeJson(f, linkedMapOf("count" to count, "last_take_end" to lastTakeEnd))
        } catch (_: Exception) {
        }
    }
}

/**
 * Keeps the sharing recording across takes: the model session (the take) stays as the app drives it, and the
 * recording, its Ogg stream, outbox item and session id, lives across back-to-back takes by the rules of [TakeJoin].
 * The recognizer's hooks call [takeStarted], [audio] and [takeEnded]; the app's own capture outside takes calls
 * [feed]; the app's life calls [background] and [foreground]; sharing going off calls [stop]. Every decision reads
 * [clock] (the window is wall time); the timer only makes the window's end prompt, and the clock decides whether it
 * passed. Thread safe: the hooks, the feed, the timer, the app's life and the settings switch arrive on different
 * threads.
 *
 * A take's input rate is known with its first audio, so that is where a take joins the open recording (decided when
 * it started) or starts its own, and where its recording is made: a take without audio makes none.
 *
 * What is held in memory, at most: the feed's last second (the ring), and a take that starts with 30 s or less of
 * room under the cap (its pre-roll and up to 30 s). Everything else goes to the recording as it comes, and a recording
 * never holds more than 20 minutes.
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
    private val open: (TajweedObserver.SessionStart, RecordingOrder) -> Recording?,
    private val sequence: TakeSequence = TakeSequence(),
) {
    @Volatile var timer: JoinTimer = JoinTimer.DEFAULT

    companion object {
        /** Fed audio kept before a take, ms. */
        const val PRE_ROLL_MS = 1000
        /** Fed audio recorded after a take, ms. */
        const val TAIL_MS = 500
    }

    /** The samples of one take in a recording: its pre-roll, its own audio, its tail. */
    private class Part {
        var preRoll = 0L
        var take = 0L
        var tail = 0L
    }

    private class Open(val rec: Recording, val identity: Any?, val rate: Int, val order: RecordingOrder) {
        var samples = 0L
        val takes = ArrayList<Take>()
        val parts = ArrayList<Part>()
        /** The last take with audio completed. */
        var completed = false
        /** Samples of fed audio the recording still takes as the tail of the take that just ended. */
        var tailLeft = 0L
        var atCap = false
        val ms: Long get() = TakeJoin.ms(samples, rate)
        /** Samples until the cap. */
        val room: Long get() = TakeJoin.capSamples(rate) - samples
    }

    /** A joining take held near the cap ([TakeJoin.HOLD_MS]): its pre-roll first, then its audio as it came. */
    private class Hold(val gapMs: Int) {
        val chunks = ArrayList<FloatArray>()
        var samples = 0L
        var preRoll = 0
    }

    /** What the running take's audio does. */
    private enum class Mode {
        /** No audio yet: the take's rate, and with it where it goes, is not known. */
        WAITING,
        /** Into the open recording as it comes. */
        WRITING,
        /** Held in memory until the take ends or outgrows the room. */
        HOLDING,
        /** Not recorded (sharing is off, no recording could be made, or it was ended). */
        OFF,
    }

    /** What became of a take (the tests compare it with the shared vector). */
    internal class TakeNote {
        var preRoll = 0
        var recorded = false
        var joined = false
        var held = false
        /** The index, among the take's own samples, of the first sample of the recording that continues it; -1: not cut. */
        var cutAt = -1L
    }

    /** What a recording held when it closed (the tests compare it with the shared vector). Counts only, no audio. */
    internal class Closed(val recording: Recording, val rate: Int, val samples: Long, val order: RecordingOrder, val takes: List<Take>,
                          /** Per take: pre-roll, own audio and tail samples. */
                          val parts: List<LongArray>, val atCap: Boolean, val deleted: Boolean)

    private var rec: Open? = null
    private var take: TajweedObserver.SessionStart? = null
    private var mode = Mode.OFF
    private var takeStart = 0L
    private var takeRate = 0
    /** The take's own samples so far (recorded or held), and those of them written to a recording. */
    private var takeSamples = 0L
    private var takeWritten = 0L
    /** The gap of a take that may join the open recording, until its first audio (a take without audio joins nothing). */
    private var joinGap: Int? = null
    private var hold: Hold? = null
    /** The recording filled inside the running take: the take's next sample opens a new one. */
    private var afterCap = false
    /** The wall time the last take with audio ended (the join window counts from it). */
    private var lastEnd: Long? = null
    /** The wall time of the last audio: a take's end or a fed buffer (the feed continues from it or has paused). */
    private var lastAudio: Long? = null
    private var backgrounded = false
    private var cancelTimer: (() -> Unit)? = null
    private var armed = 0

    /** The last recording closed (tests wait on its encoder). */
    @Volatile var lastClosed: Recording? = null
        private set

    /** Told every recording that closes (the tests). */
    @Volatile var onClosed: ((Closed) -> Unit)? = null

    /** The running or the last take. */
    var lastTake = TakeNote()
        @Synchronized get
        private set

    /** Where the last fed buffer went: samples to the tail, to the ring, dropped. */
    val lastFeed = LongArray(3)

    /** The open recording (between takes too), or null. */
    val current: Recording? @Synchronized get() = rec?.rec

    /** The open recording's takes so far. */
    val takes: List<Take> @Synchronized get() = rec?.takes?.toList() ?: emptyList()

    // ---- the feed: audio the app captures outside takes (SPEC 11.2; the rules F1 to F7 of the shared vector) ----

    /** The last [PRE_ROLL_MS] of fed audio while no take runs: memory only, at the feed's rate. */
    private var ring = FloatArray(0)
    private var ringRate = 0
    /** Samples in the ring (at most its length) and where the next one goes. */
    private var ringFill = 0
    private var ringAt = 0
    /** The wall time of the last buffer that went into the ring. */
    private var ringWall = 0L

    /** Samples kept in memory for the next take's pre-roll (0 when nothing is kept). Never more than one second. */
    val keptSamples: Int @Synchronized get() = ringFill

    /** Samples of a held take in memory (0 when none is held). Never more than its pre-roll and [TakeJoin.HOLD_MS]
     *  of audio, plus the buffer that outgrew the room. */
    val heldSamples: Long @Synchronized get() = hold?.samples ?: 0

    /**
     * Audio the app captures while no take runs, at the wall time of the call (taken as the end of the audio). With
     * sharing on and the learner's yes: what continues a take that just ended goes into its recording, up to
     * [TAIL_MS] (the tail); the rest goes to the ring of the last [PRE_ROLL_MS] for the next take (the pre-roll).
     * Otherwise it is dropped at once and nothing is kept. During a take it is dropped (the take's own audio is the
     * recording's). A buffer that comes later than its own length plus 500 ms after the audio before it does not
     * continue it: the ring is emptied first and an open tail ends.
     */
    @Synchronized
    fun feed(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        lastFeed.fill(0)
        if (length <= 0 || sampleRate <= 0) return
        if (!active()) {
            dropFeed()
            lastFeed[2] = length.toLong()
            return
        }
        if (take != null) {
            lastFeed[2] = length.toLong()
            return
        }
        val now = clock()
        val o = rec
        val last = lastAudio
        val continues = last != null && now - last <= 1000L * length / sampleRate + TakeJoin.STALE_MS
        if (!continues) {
            emptyRing()
            o?.tailLeft = 0
        }
        // the tail takes fed audio until TAIL_WAIT_MS after the take: a later buffer goes to the ring, however it continues
        val end = lastEnd
        if (o != null && (end == null || now - end > TakeJoin.TAIL_WAIT_MS)) o.tailLeft = 0
        var at = offset
        var left = length
        if (o != null && o.tailLeft > 0) {
            if (sampleRate == o.rate && o.rec.alive) {
                // the tail is cut at the cap: it never opens a recording
                val n = minOf(left.toLong(), o.tailLeft, o.room).toInt()
                o.rec.append(samples, at, n, sampleRate)
                o.samples += n
                o.parts.last().tail += n
                o.tailLeft -= n
                at += n
                left -= n
                lastFeed[0] = n.toLong()
                if (o.room == 0L) closeAtCap(o)
            } else {
                o.tailLeft = 0
            }
        }
        if (left > 0) {
            intoRing(samples, at, left, sampleRate, now)
            lastFeed[1] = left.toLong()
        }
        lastAudio = now
        // a recording whose window is over waited only for its tail
        val c = rec
        if (c != null && c.tailLeft == 0L && windowOver(c, now)) close(c)
    }

    private fun windowOver(o: Open, now: Long): Boolean {
        val end = lastEnd ?: return true
        val w = if (o.takes.size >= TakeJoin.MAX_TAKES) 0 else window()
        return w <= 0 || now - end > w
    }

    private fun intoRing(samples: FloatArray, at: Int, n: Int, sampleRate: Int, now: Long) {
        val cap = (sampleRate.toLong() * PRE_ROLL_MS / 1000).toInt()
        if (ringRate != sampleRate || ring.size != cap) {
            ring = FloatArray(cap)
            ringRate = sampleRate
            ringFill = 0
            ringAt = 0
        }
        ringWall = now
        if (n >= cap) {
            System.arraycopy(samples, at + n - cap, ring, 0, cap)
            ringFill = cap
            ringAt = 0
            return
        }
        val first = minOf(n, cap - ringAt)
        System.arraycopy(samples, at, ring, ringAt, first)
        if (n > first) System.arraycopy(samples, at + first, ring, 0, n - first)
        ringAt = (ringAt + n) % cap
        ringFill = minOf(cap, ringFill + n)
    }

    /** The feed paused: what the ring holds does not lead into what comes next. */
    private fun emptyRing() {
        ringFill = 0
        ringAt = 0
    }

    /** Nothing of the feed stays in memory. */
    private fun dropFeed() {
        ring = FloatArray(0)
        ringRate = 0
        emptyRing()
        rec?.tailLeft = 0
    }

    /**
     * The pre-roll of the take that started at [takeStart], at [sampleRate]: the ring's audio in order when it has the
     * take's rate and its last buffer came at most 500 ms before the take; else null. The ring is empty afterwards.
     */
    private fun takePreRoll(sampleRate: Int): FloatArray? {
        val n = ringFill
        val out = if (n > 0 && ringRate == sampleRate && takeStart - ringWall <= TakeJoin.STALE_MS) {
            val start = (ringAt - n + ring.size) % ring.size
            FloatArray(n) { ring[(start + it) % ring.size] }
        } else null
        emptyRing()
        return out
    }

    // ---- the takes ----

    /** A take starts: it may join the open recording (decided here, done at its first audio) or starts a new one. */
    @Synchronized
    fun takeStarted(e: TajweedObserver.SessionStart) {
        // a take that never ended: as in 2.0.2 its recording ends there, not completed. When that take really ended is
        // not known, so this one does not join it.
        if (take != null) {
            takeEnded(false)
            rec?.let { close(it) }
        }
        val now = clock()
        take = e
        takeStart = now
        takeRate = 0
        takeSamples = 0
        takeWritten = 0
        joinGap = null
        hold = null
        afterCap = false
        lastTake = TakeNote()
        mode = if (active()) Mode.WAITING else Mode.OFF
        if (mode == Mode.OFF) dropFeed()
        val o = rec ?: return
        disarm()
        // the tail ends where the next take starts
        o.tailLeft = 0
        val w = window()
        val end = lastEnd
        if (mode == Mode.WAITING && w > 0 && end != null && now - end <= w && !backgrounded && o.rec.alive && o.room > 0 &&
            o.takes.size < TakeJoin.MAX_TAKES && o.identity == identity(e)) {
            joinGap = TakeJoin.gapMs(now - end)
            return
        }
        close(o)
    }

    /** A new recording at [rate] for the running take, or none. */
    private fun start(e: TajweedObserver.SessionStart, rate: Int, gapMsPrev: Int?): Open? {
        rec = null
        if (!active()) return null
        val order = RecordingOrder(sequence.count + 1, gapMsPrev)
        val r = open(e, order) ?: return null
        sequence.started()
        return Open(r, identity(e), rate, order).also { rec = it }
    }

    /** `gap_ms_prev` of a recording whose first take is the running one. */
    private fun gapPrev(): Int? = sequence.lastTakeEnd?.let { TakeJoin.gapPrevMs(takeStart - it) }

    /** Audio of the running take; nothing outside a take. */
    @Synchronized
    fun audio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        val e = take ?: return
        if (length <= 0 || sampleRate <= 0) return
        if (mode == Mode.WAITING) first(e, sampleRate)
        // one rate per take (and per recording): audio at another rate inside a take is ignored
        if (mode == Mode.OFF || sampleRate != takeRate) return
        takeSamples += length
        if (mode == Mode.WRITING) {
            write(e, samples, offset, length, own = true)
            return
        }
        val h = hold ?: return
        val o = rec ?: return
        h.chunks.add(samples.copyOfRange(offset, offset + length))
        h.samples += length
        // the fit is counted in samples: the pre-roll and the take so far against the recording's room
        if (h.samples > o.room) overflow(e, o, h)
    }

    /** The take's first audio: its rate is known now, so it joins the open recording, is held, or starts its own. */
    private fun first(e: TajweedObserver.SessionStart, sampleRate: Int) {
        takeRate = sampleRate
        // what the app fed just before the take goes in first; the take's boundary is its own first sample
        val pre = takePreRoll(sampleRate)
        lastTake.preRoll = pre?.size ?: 0
        val o = rec
        val gap = joinGap
        joinGap = null
        if (o != null && gap != null && sampleRate == o.rate) {
            lastTake.recorded = true
            // 30 s or less of room (in samples): the take is held until it is known whether it fits
            if (o.room <= TakeJoin.HOLD_MS * o.rate / 1000) {
                val h = Hold(gap)
                if (pre != null) {
                    h.chunks.add(pre)
                    h.samples = pre.size.toLong()
                    h.preRoll = pre.size
                }
                hold = h
                mode = Mode.HOLDING
                lastTake.held = true
                return
            }
            join(o, gap, pre?.size ?: 0)
            mode = Mode.WRITING
            if (pre != null) write(e, pre, 0, pre.size, own = false)
            return
        }
        // no recording to join, or one at another input rate (it closes): the take starts its own
        if (o != null) close(o)
        fresh(e, pre)
    }

    /** A new recording whose first take is the running one, its pre-roll first. */
    private fun fresh(e: TajweedObserver.SessionStart, pre: FloatArray?) {
        val o = start(e, takeRate, gapPrev())
        if (o == null) {
            mode = Mode.OFF
            return
        }
        o.takes.add(Take(TakeJoin.ms((pre?.size ?: 0).toLong(), o.rate), 0))
        o.parts.add(Part())
        mode = Mode.WRITING
        lastTake.recorded = true
        if (pre != null) write(e, pre, 0, pre.size, own = false)
    }

    /** A take joins [o] after [preRoll] samples of pre-roll: its boundary, when it lies after the last one. */
    private fun join(o: Open, gapMs: Int, preRoll: Int) {
        lastTake.joined = true
        o.parts.add(Part())
        val at = TakeJoin.ms(o.samples + preRoll, o.rate)
        if (at <= o.takes.last().startMs) return
        o.takes.add(Take(at, gapMs))
        o.rec.takes(o.takes.toList())
    }

    /**
     * Take audio into the recording as it comes ([own]: the take's own samples, else its pre-roll). The recording
     * closes on the sample that fills it, as a whole recording; the next sample opens a new one that continues the
     * take: the next `take_seq`, `gap_ms_prev` 0, no pre-roll, a take list that starts at 0.
     */
    private fun write(e: TajweedObserver.SessionStart, samples: FloatArray, offset: Int, length: Int, own: Boolean) {
        var at = offset
        var left = length
        while (left > 0) {
            var o = rec
            if (o == null) {
                // closed under the take for another reason than the cap (sharing went off): the rest is not recorded
                if (!afterCap) {
                    mode = Mode.OFF
                    return
                }
                afterCap = false
                o = start(e, takeRate, 0)
                if (o == null) {
                    mode = Mode.OFF
                    return
                }
                o.takes.add(Take(0, 0))
                o.parts.add(Part())
                lastTake.cutAt = takeWritten
            }
            val n = minOf(left.toLong(), o.room).toInt()
            o.rec.append(samples, at, n, o.rate)
            o.samples += n
            if (own) {
                o.parts.last().take += n
                takeWritten += n
            } else {
                o.parts.last().preRoll += n
            }
            at += n
            left -= n
            if (o.room == 0L) {
                closeAtCap(o)
                afterCap = true
            }
        }
    }

    /** The recording holds 20 minutes: it closes at once, a whole recording whatever becomes of the take. */
    private fun closeAtCap(o: Open) {
        o.completed = true
        o.atCap = true
        close(o)
    }

    /** The held audio goes into the recording the take now has: its pre-roll, then its own samples. */
    private fun flush(e: TajweedObserver.SessionStart, h: Hold) {
        for ((i, c) in h.chunks.withIndex()) {
            if (mode != Mode.WRITING) return
            write(e, c, 0, c.size, own = !(i == 0 && h.preRoll > 0))
        }
    }

    /** The held take outgrew the room: the recording closes without it, and the take starts a new one with all its audio. */
    private fun overflow(e: TajweedObserver.SessionStart, o: Open, h: Hold) {
        hold = null
        close(o)
        val n = start(e, takeRate, gapPrev())
        if (n == null) {
            mode = Mode.OFF
            return
        }
        n.takes.add(Take(TakeJoin.ms(h.preRoll.toLong(), n.rate), 0))
        n.parts.add(Part())
        mode = Mode.WRITING
        flush(e, h)
    }

    /** The take ended: its recording waits for the next take within the window and for its tail, or closes now. */
    @Synchronized
    fun takeEnded(completed: Boolean) {
        val e = take ?: return
        take = null
        joinGap = null
        val h = hold
        hold = null
        if (h != null) {
            // the held take ended within the room: it joins
            val o = rec
            if (o != null) {
                join(o, h.gapMs, h.preRoll)
                mode = Mode.WRITING
                flush(e, h)
            }
        }
        val had = takeSamples > 0 && lastTake.recorded
        mode = Mode.OFF
        afterCap = false
        val now = clock()
        if (had) {
            lastEnd = now
            lastAudio = now
            sequence.takeEnded(now)
        }
        val o = rec ?: return
        if (had) {
            o.completed = completed
            // the feed may add the tail: half a second after the take
            o.tailLeft = (o.rate.toLong() * TAIL_MS / 1000)
        }
        if (o.samples == 0L || backgrounded || !active() || !o.rec.alive || o.room == 0L) {
            close(o)
            return
        }
        settle(o, now, fromTimer = false)
    }

    /**
     * The waiting recording closes when its window is over and it waits for no tail; else the timer is set for the
     * moment both are. A recording waits for its tail at most [TakeJoin.TAIL_WAIT_MS] after the take.
     */
    private fun settle(o: Open, now: Long, fromTimer: Boolean) {
        val end = lastEnd
        if (end == null) {
            close(o)
            return
        }
        val since = now - end
        // a clock set back while the recording waited: the timer ran its time, so the recording closes rather than
        // waiting for a moment that may never come
        if (fromTimer && since < 0) {
            close(o)
            return
        }
        val w = if (o.takes.size >= TakeJoin.MAX_TAKES) 0 else window()
        val windowLeft = if (w > 0) w - since else -1L
        val tailLeft = if (o.tailLeft > 0) TakeJoin.TAIL_WAIT_MS - since else -1L
        val left = maxOf(windowLeft, tailLeft)
        if (left < 0) close(o) else arm(left)
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
        settle(o, clock(), fromTimer = true)
    }

    /** The app went to the background: a waiting recording closes now, a running take's when it ends; the ring is emptied. */
    @Synchronized
    fun background() {
        backgrounded = true
        dropFeed()
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
        afterCap = false
        if (take != null) mode = Mode.OFF
        dropFeed()
        lastEnd = null
        rec?.let { close(it, keep = false) }
    }

    /** "Delete what I shared": the count of recordings starts again and the previous take's end is forgotten. */
    @Synchronized
    fun forget() = sequence.forget()

    @Synchronized
    fun sync() { rec?.rec?.sync() }

    private fun close(o: Open, keep: Boolean = true) {
        disarm()
        o.tailLeft = 0
        if (rec === o) rec = null
        if (keep) o.rec.finish(o.completed) else o.rec.abandon()
        lastClosed = o.rec
        onClosed?.invoke(Closed(o.rec, o.rate, o.samples, o.order, o.takes.toList(),
            o.parts.map { longArrayOf(it.preRoll, it.take, it.tail) }, o.atCap, deleted = !keep))
    }
}
