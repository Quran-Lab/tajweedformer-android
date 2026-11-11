package ai.quranlab.tajweedformer

import kotlin.math.sqrt

/**
 * Speech and silence from the model's own output (SPEC 6.2), one definition for the events, `chunk.speech` and the
 * telemetry sums: a 40 ms frame is ACTIVE when the greedy token of `log_probs` is non-blank on it, or the greedy
 * token of `timing_log_probs` is non-blank on one of its two 20 ms frames. A speech frame is an active frame, a
 * silence frame an inactive one. The timing head is what keeps a held sound speech: `log_probs` writes a token where
 * a sound starts and is blank while it is held, and a madd of six counts is held for over two seconds. No energy
 * threshold and no rule about the recitation: only where the model wrote.
 */
internal object SpeechFrames {
    /** The heads the definition reads. A Session always computes both, whatever the app selected. */
    @JvmField val HEADS: Set<HeadId> = setOf(HeadId.LOG_PROBS, HeadId.TIMING_LOG_PROBS)

    /** Whether each 40 ms frame of [h] is active. A head [h] does not have counts as blank. */
    fun active(h: HeadValues, blank: Int): BooleanArray {
        val top = top1(h)
        val timing = best(h, HeadId.TIMING_LOG_PROBS)
        return BooleanArray(h.n40) { f ->
            (f < top.size && top[f] != blank) || (2 * f < timing.size && timing[2 * f] != blank) ||
                (2 * f + 1 < timing.size && timing[2 * f + 1] != blank)
        }
    }

    /** The best token of every 40 ms frame of [h]'s `log_probs` (ties to the lower id); empty without the head. */
    fun top1(h: HeadValues): IntArray {
        val t2 = h.top2
        if (t2 != null) return IntArray(h.n40) { t2[5 * it].toInt() }
        return best(h, HeadId.LOG_PROBS)
    }

    /** The best class of every frame of [head] in [h] (ties to the lower id); empty without the head. */
    private fun best(h: HeadValues, head: HeadId): IntArray {
        val v = h[head] ?: return IntArray(0)
        val w = head.width
        return IntArray(v.size / w) { t ->
            var b = 0
            for (k in 1 until w) if (v[t * w + k] > v[t * w + b]) b = k
            b
        }
    }
}

/** The SDK-measured counters of one take (SPEC 6.2), from its heads. */
internal class TakeCounts(val speechFrames: Int, val silenceFrames: Int, val tokens: Map<String, Int>) {
    companion object {
        val NONE = TakeCounts(0, 0, emptyMap())

        /** The active and the inactive frames of the take ([SpeechFrames]), and the greedy tokens of every CTC head in [select]. */
        fun of(heads: HeadValues, select: Set<HeadId>): TakeCounts {
            val flags = SpeechFrames.active(heads, ModelFolder.BLANK)
            val speech = flags.count { it }
            val tokens = LinkedHashMap<String, Int>()
            for (h in HeadId.entries) {
                // a selected head of a take without a frame counts 0 tokens (it is not left out)
                if (h in select && h.kind == HeadKind.LOG_PROBS) tokens[h.key] = if (heads.has(h)) heads.greedy(h).size else 0
            }
            return TakeCounts(speech, flags.size - speech, tokens)
        }
    }
}

/**
 * The speech events of a take, decided frame by frame as the frames arrive, never at the end (SPEC 13.2). The state
 * is silence at the take's start. A chunk holds speech when one of its frames is active. The timing of the two
 * events is in [started] and [ended], and nowhere else.
 */
internal class SpeechTracker(windowMs: Int) {
    /** Whether each frame so far was active (a failover replays them from a frame on; a take is at most 30000 frames). */
    private val flags = ArrayList<Boolean>()
    private var lastActive = -1
    private var inSpeech = false

    /** The silence window in frames: W = ceil(window / 40). */
    private val window: Int = (windowMs + 39) / 40

    /** `speechStarted`: at an active frame [f] while the state is silence; its time is the start of that frame. */
    private fun started(f: Int): TajweedEvent = TajweedEvent.SpeechStarted(40L * f)

    /**
     * `speechEnded`: while the state is speech, when W inactive frames in a row have arrived after the last active
     * frame L, that is with frame L + W. Its time is the end of the last active frame, 40 * (L + 1); the silence that
     * ended it is 40 * W. Null while frame [f] is not that frame.
     */
    private fun ended(f: Int): TajweedEvent? =
        if (f == lastActive + window) TajweedEvent.SpeechEnded(40L * (lastActive + 1), 40L * window) else null

    /** Feeds the new frames ([active]: one flag a frame). Returns whether they hold speech, and the events they raise. */
    fun add(active: BooleanArray, events: MutableList<TajweedEvent>?): Boolean {
        var speech = false
        for (a in active) {
            val f = flags.size
            flags.add(a)
            if (a) {
                if (!inSpeech) {
                    inSpeech = true
                    events?.add(started(f))
                }
                lastActive = f
                speech = true
            } else if (inSpeech) {
                ended(f)?.let {
                    inSpeech = false
                    events?.add(it)
                }
            }
        }
        return speech
    }

    /** Back to the state after the first [frames] frames (a failover replaced the chunks from there); no events. */
    fun rewind(frames: Int) {
        val keep = BooleanArray(minOf(frames, flags.size)) { flags[it] }
        flags.clear()
        lastActive = -1
        inSpeech = false
        add(keep, null)
    }
}

/**
 * The decoded layer of a take as its chunks arrive: the frames (best token, runner-up, margin, top K when asked), the
 * tokens with their times and confidences, and how many of them are settled (SPEC 13.3). A token is settled once the
 * frame after its run has another best token: nothing that comes later in the take changes it. The token whose run
 * reaches the last frame so far is tentative. Greedy CTC, ties to the lower id, the engine's rule.
 *
 * Each [snapshot] shares the settled tokens and the frames with the ones before it (they are only appended to), so a
 * long take does not copy its whole history at every chunk.
 */
internal class TakeDecoder(private val vocabulary: Vocabulary, private val topK: Int) {
    private var frames = ArrayList<TajweedFrame>()
    private var settled = ArrayList<TajweedToken>()
    private var conf = FloatArray(0)
    private var confN = 0
    private var hasConf = true
    /** The first frame of each chunk added. */
    private val chunkStart = ArrayList<Int>()
    /** The first frame not yet covered by a settled token or a closed blank run. */
    private var scanFrom = 0
    private var frameOffset = 0

    val chunks: Int get() = chunkStart.size
    val frameCount: Int get() = frames.size

    /**
     * Adds chunk [index] of the take, whose first frame in the take is [firstFrame]. An index below the chunks
     * already added replaces them from there (a failover redid them); returns whether it did.
     */
    fun add(h: HeadValues, index: Int, firstFrame: Int): Boolean {
        var rewound = false
        if (chunkStart.isEmpty()) frameOffset = firstFrame
        if (index < chunkStart.size) {
            val n = chunkStart[index]
            frames = ArrayList(frames.subList(0, n))
            confN = minOf(confN, 2 * n)
            while (chunkStart.size > index) chunkStart.removeAt(chunkStart.size - 1)
            settled = ArrayList()
            scanFrom = 0
            rewound = true
        }
        chunkStart.add(frames.size)
        val lp = h[HeadId.LOG_PROBS] ?: return rewound
        val w = HeadId.LOG_PROBS.width
        val t2 = h.top2
        for (t in 0 until h.n40) {
            var top: Int
            var second: Int
            if (t2 != null) {
                top = t2[5 * t].toInt()
                second = t2[5 * t + 1].toInt()
            } else {
                top = 0
                for (k in 1 until w) if (lp[t * w + k] > lp[t * w + top]) top = k
                second = if (top == 0) 1 else 0
                for (k in 0 until w) if (k != top && lp[t * w + k] > lp[t * w + second]) second = k
            }
            var ids: List<Int> = emptyList()
            var values: List<Float> = emptyList()
            if (topK > 0) {
                val order = (0 until w).sortedWith(compareBy<Int>({ -lp[t * w + it] }, { it })).take(minOf(topK, w))
                ids = order
                values = order.map { lp[t * w + it] }
            }
            frames.add(TajweedFrame(frameOffset + frames.size, top, lp[t * w + top], second, lp[t * w + top] - lp[t * w + second], ids, values))
        }
        val c = h[HeadId.CONF]
        if (c == null) hasConf = false else {
            if (confN + c.size > conf.size) conf = conf.copyOf(maxOf(2 * conf.size, confN + c.size))
            c.copyInto(conf, confN)
            confN += c.size
        }
        return rewound
    }

    private fun token(first: Int, last: Int): TajweedToken {
        val id = frames[first].top
        var peak = first
        for (k in first..last) if (frames[k].topLogProb > frames[peak].topLogProb) peak = k
        val pm = Decoder.sigmoid(frames[peak].margin.toDouble())
        var confidence = pm
        if (hasConf) {
            var sum = 0.0
            var n = 0
            for (k in 2 * first..2 * last + 1) if (k < confN) { sum += Decoder.sigmoid(conf[k].toDouble()); n++ }
            if (n > 0) confidence = sqrt((sum / n) * pm)
        }
        val o = frameOffset
        return TajweedToken(id, vocabulary.symbol(id), (o + first) * 40L, (o + peak) * 40L, (o + last) * 40L, confidence.toFloat())
    }

    /** The decoded layer of the frames so far; with [final] every token is settled. */
    fun snapshot(final: Boolean): TajweedDecoded {
        var tail: TajweedToken? = null
        var t = scanFrom
        val n = frames.size
        while (t < n) {
            val id = frames[t].top
            var end = t
            while (end + 1 < n && frames[end + 1].top == id) end++
            val closed = end + 1 < n || final
            if (id != vocabulary.blank) {
                val tok = token(t, end)
                if (closed) settled.add(tok) else tail = tok
            }
            if (!closed) break
            t = end + 1
            scanFrom = t
        }
        return TajweedDecoded(Prefix(settled, settled.size, tail), Prefix(frames, n, null), settled.size, final)
    }

    /** The first [n] items of an append-only list, then one more. */
    private class Prefix<T>(private val base: List<T>, private val n: Int, private val tail: T?) : AbstractList<T>() {
        override val size: Int get() = n + (if (tail != null) 1 else 0)

        override fun get(index: Int): T = when {
            index in 0 until n -> base[index]
            index == n && tail != null -> tail
            else -> throw IndexOutOfBoundsException("$index of $size")
        }
    }
}
