package ai.quranlab.tajweedformer

/**
 * Every output of the V4 network (README_heads.txt of the ONNX export; zf_tajweed.h `ZfTajweedHeads`).
 * Two frame rates: 40 ms (the CTC heads) and 20 ms (the rest), aligned: 20 ms frames 2k and 2k + 1
 * lie in 40 ms frame k. [width] values per frame.
 */
enum class TajweedHead(
    val key: String,
    /** 40 (the CTC heads) or 20 ms per frame. */
    val frameMs: Int,
    /** Values per frame. */
    val width: Int,
    /** What a value is. */
    val kind: HeadKind,
    /** Class names for the attribute heads (README_heads.txt of the export); empty for the token
     *  heads, whose classes are the model's tokens (see [classNames]). */
    val fixedClasses: List<String> = emptyList(),
) {
    /** Final CTC over the 38 tj3 tokens, log-softmax. The main evidence and the transcription. */
    LOG_PROBS("log_probs", 40, 38, HeadKind.LOG_PROBS),
    /** Stack-1 InterCTC, log-softmax. Listens more to the sound; fused with [LOG_PROBS] for consonants. */
    INTER1_LOG_PROBS("inter1_log_probs", 40, 38, HeadKind.LOG_PROBS),
    /** Stack-4 InterCTC, log-softmax. Diagnostic. */
    INTER4_LOG_PROBS("inter4_log_probs", 40, 38, HeadKind.LOG_PROBS),
    /** Identity CTC over the same tokens at 20 ms, log-softmax. */
    ID_LOG_PROBS("id_log_probs", 20, 38, HeadKind.LOG_PROBS),
    /** The H-time chain, log-softmax: when each sound is held (madd counts); 0 = pause state. */
    TIMING_LOG_PROBS("timing_log_probs", 20, 38, HeadKind.LOG_PROBS),
    /** Gemination softmax: single, held. */
    GEM("gem", 20, 2, HeadKind.PROBS, listOf("single", "held")),
    /** Softmax: thin, heavy, low heavy. */
    ATTR_TAFKHEEM("attr_tafkheem", 20, 3, HeadKind.PROBS, listOf("thin", "heavy", "low_heavy")),
    /** Softmax: none, plain, geminate, ikhfa, idgham. */
    ATTR_GHUNNA("attr_ghunna", 20, 5, HeadKind.PROBS, listOf("none", "plain", "geminate", "ikhfa", "idgham")),
    /** Softmax: none, sughra, kubra. */
    ATTR_QALQALAH("attr_qalqalah", 20, 3, HeadKind.PROBS, listOf("none", "sughra", "kubra")),
    /** Softmax: none, imala, tasheel, sakt. */
    ATTR_FACE("attr_face", 20, 4, HeadKind.PROBS, listOf("none", "imala", "tasheel", "sakt")),
    /** Logit: a unit starts here (apply a sigmoid). */
    BOUNDARY("boundary", 20, 1, HeadKind.LOGIT, listOf("boundary")),
    /** Logit: the frame's label is truthful (apply a sigmoid). */
    CONF("conf", 20, 1, HeadKind.LOGIT, listOf("truthful"));

    /** The class name of every value of a frame: the model's tokens for the token heads. */
    fun classNames(vocabulary: Vocabulary): List<String> =
        if (fixedClasses.isNotEmpty()) fixedClasses else (0 until width).map { vocabulary.symbol(it) }

    companion object {
        @JvmStatic fun fromKey(key: String): TajweedHead? = entries.firstOrNull { it.key == key }

        /** What the scorer needs; checks whose head is missing are skipped. */
        @JvmField
        val SCORER_HEADS: Set<TajweedHead> = setOf(LOG_PROBS, INTER1_LOG_PROBS, TIMING_LOG_PROBS, GEM, ATTR_QALQALAH)
    }
}

/** What a head's values are. */
enum class HeadKind {
    /** Log-softmax over the classes (natural log). */
    LOG_PROBS,
    /** Softmax probabilities over the classes. */
    PROBS,
    /** One logit per frame: apply a sigmoid for a probability. */
    LOGIT,
}

/**
 * Heads of a stretch of audio (one engine chunk, or a whole take), row major, frames first:
 * `[frames(head), head.width]`. A head is absent when its source does not give it (an NPU build
 * may expose only some).
 */
class TajweedHeads @JvmOverloads constructor(
    /** 40 ms frames; the 20 ms heads have twice as many. */
    val n40: Int,
    values: Map<TajweedHead, FloatArray>,
    /** The engine's top-2 of log_probs per 40 ms frame ([token, runner_up, log_prob, runner_up_log_prob,
     *  margin], zf_tajweed_stream_top2), when the source gave it; [Decoder] computes it otherwise. */
    val top2: FloatArray? = null,
) {
    private val values: Map<TajweedHead, FloatArray> = HashMap(values)

    val n20: Int get() = 2 * n40

    init {
        require(n40 >= 0) { "n40 $n40" }
        for ((h, v) in values) {
            require(v.size == frames(h) * h.width) { "${h.key} has ${v.size} values, [${frames(h)}, ${h.width}] expected" }
        }
    }

    fun frames(head: TajweedHead): Int = if (head.frameMs == 40) n40 else n20

    /** The head's values, or null when absent. The array is the heads' own: do not change it. */
    operator fun get(head: TajweedHead): FloatArray? = values[head]

    fun has(head: TajweedHead): Boolean = head in values

    val available: Set<TajweedHead> get() = values.keys

    /** One frame of a head. */
    fun row(head: TajweedHead, frame: Int): FloatArray {
        val v = values[head] ?: throw NoSuchElementException("${head.key} is absent")
        require(frame in 0 until frames(head)) { "frame $frame of ${frames(head)}" }
        return v.copyOfRange(frame * head.width, (frame + 1) * head.width)
    }

    /** The same heads restricted to [keep] (the raw layer's per-head opt-in). */
    fun only(keep: Set<TajweedHead>): TajweedHeads =
        if (keep.containsAll(values.keys)) this else TajweedHeads(n40, values.filterKeys { it in keep }, top2)

    /** Greedy CTC on [TajweedHead.LOG_PROBS], blanks and repeats removed: what the model heard.
     *  Display only; decisions come from the scorer. */
    fun greedy(blank: Int = TajweedModel.BLANK): IntArray {
        val lp = values[TajweedHead.LOG_PROBS] ?: return IntArray(0)
        val w = TajweedHead.LOG_PROBS.width
        val out = ArrayList<Int>()
        var prev = -1
        for (t in 0 until n40) {
            var best = 0
            for (k in 1 until w) if (lp[t * w + k] > lp[t * w + best]) best = k
            if (best != blank && best != prev) out.add(best)
            prev = best
        }
        return out.toIntArray()
    }

    companion object {
        /** Chunks in order, joined into the heads of the whole take. A head is kept only when
         *  every chunk has it. */
        @JvmStatic
        fun concat(chunks: List<TajweedHeads>): TajweedHeads {
            val n40 = chunks.sumOf { it.n40 }
            val heads = TajweedHead.entries.filter { h -> chunks.isNotEmpty() && chunks.all { it.has(h) } }
            val out = HashMap<TajweedHead, FloatArray>()
            for (h in heads) {
                val a = FloatArray((if (h.frameMs == 40) n40 else 2 * n40) * h.width)
                var at = 0
                for (c in chunks) {
                    val v = c[h]!!
                    v.copyInto(a, at)
                    at += v.size
                }
                out[h] = a
            }
            val top2 = if (chunks.isNotEmpty() && chunks.all { it.top2 != null }) {
                FloatArray(n40 * 5).also { t -> var at = 0; for (c in chunks) { c.top2!!.copyInto(t, at); at += c.top2.size } }
            } else null
            return TajweedHeads(n40, out, top2)
        }
    }
}

/** One engine chunk's heads (8 + 16 frames at c16, 12 + 24 at c24), as the session streams them. */
class TajweedChunk(
    /** Chunks since the take started, from 0. */
    val index: Int,
    val heads: TajweedHeads,
    /** Wall-clock time the engine spent on it, in seconds. */
    val seconds: Double,
    /** The backend that computed it. */
    val backend: Backend = Backend.CPU,
    /** Its first 40 ms frame within the take (its audio starts at `firstFrame * 40` ms). */
    val firstFrame: Int = 0,
) {
    /** The chunk's decoded layer (top-1, runner-up, margin per CTC frame, and [topK] ranked entries per
     *  head), timed within the take. */
    @JvmOverloads
    fun decode(vocabulary: Vocabulary, topK: Map<TajweedHead, Int> = Decoder.DEFAULT_TOP_K): Decoded =
        Decoder.decode(heads, vocabulary, firstFrame, topK)
}
