package ai.quranlab.tajweedformer

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One 40 ms CTC frame of [TajweedHead.LOG_PROBS]: the best and second-best token and their
 * log-probabilities. Ties go to the lower id.
 */
data class CtcFrame(
    /** Frame within the take. */
    val index: Int,
    val top: Int,
    val topLogProb: Float,
    val runnerUp: Int,
    val runnerUpLogProb: Float,
) {
    /** `topLogProb - runnerUpLogProb`, 0 or more. */
    val margin: Float get() = topLogProb - runnerUpLogProb
    val startMs: Long get() = index * FRAME_MS
    val endMs: Long get() = (index + 1) * FRAME_MS

    companion object {
        const val FRAME_MS = 40L
    }
}

/**
 * A token of the greedy transcript: a run of frames whose top-1 is the same non-blank token.
 *
 * - [logProb] and [margin]: at the run's peak frame (the frame with the highest top-1 log-prob);
 *   [margin] is top-1 minus runner-up there.
 * - [conf]: the mean of sigmoid(conf) over the run's 20 ms frames, when the conf head is present.
 * - [confidence]: a display value in 0..1, the geometric mean of [conf] and sigmoid([margin]) (or
 *   sigmoid([margin]) alone without the conf head). It is not calibrated: verdicts come from the
 *   tajweed layer.
 */
data class DecodedToken(
    val id: Int,
    val symbol: String,
    val firstFrame: Int,
    val lastFrame: Int,
    val logProb: Float,
    val margin: Float,
    val runnerUp: Int,
    val conf: Float?,
    val confidence: Float,
) {
    val startMs: Long get() = firstFrame * CtcFrame.FRAME_MS
    val endMs: Long get() = (lastFrame + 1) * CtcFrame.FRAME_MS
}

/** Tokens between two word boundaries ("|", [TajweedModel.WORD_BOUNDARY]) of the transcript. */
data class DecodedWord(val index: Int, val tokens: List<DecodedToken>) {
    val startMs: Long get() = tokens.first().startMs
    val endMs: Long get() = tokens.last().endMs
    /** The lowest token confidence of the word. */
    val confidence: Float get() = tokens.minOf { it.confidence }
}

/**
 * The decoded layer of a stretch of audio: every CTC frame's top two tokens, the greedy transcript
 * with per-token confidence, words, and times (frame k covers `[40 k, 40 (k + 1))` ms of the take,
 * the engine's frame grid; no model delay is subtracted).
 *
 * Words: the transcript split at the word boundary token when the model emits it. For a known ayah
 * the tajweed layer gives words from the expected text instead ([TajweedResult.words]).
 */
class Decoded @JvmOverloads constructor(
    val frames: List<CtcFrame>,
    val tokens: List<DecodedToken>,
    val words: List<DecodedWord>,
    /** The ranked entries asked for per head ([TajweedRecognizer.Options.topK]), from the engine's top-K. */
    val ranked: Map<TajweedHead, Ranked> = emptyMap(),
    /** boundary and conf as probabilities per 20 ms frame (sigmoid of their logits), when present. */
    val probabilities: Map<TajweedHead, FloatArray> = emptyMap(),
) {
    /** Token ids of the transcript (word boundaries included). */
    val ids: IntArray get() = tokens.map { it.id }.toIntArray()

    /** The transcript as symbols separated by spaces. */
    val text: String get() = tokens.joinToString(" ") { it.symbol }
}

/**
 * Greedy CTC with top-2 and confidences (see [Decoded]). The per-frame top-2 is the engine's
 * (zf_tajweed_stream_top2 for the exact engine's own heads, zf_tajweed_top2 for heads from elsewhere):
 * one rule, ties to the lower id, the same as the engine's greedy decode.
 */
object Decoder {
    /** log_probs ranked 2 deep, the default of [TajweedRecognizer.Options.topK]. */
    @JvmField val DEFAULT_TOP_K: Map<TajweedHead, Int> = mapOf(TajweedHead.LOG_PROBS to 2)

    @JvmStatic
    @JvmOverloads
    fun decode(heads: TajweedHeads, vocabulary: Vocabulary, frameOffset: Int = 0, topK: Map<TajweedHead, Int> = DEFAULT_TOP_K): Decoded {
        val ranked = topK.filterKeys { heads.has(it) }.mapValues { (h, k) -> Ranked.of(heads, h, k) }
        val probabilities = listOf(TajweedHead.BOUNDARY, TajweedHead.CONF).mapNotNull { h ->
            heads[h]?.let { v -> h to FloatArray(v.size) { sigmoid(v[it].toDouble()).toFloat() } }
        }.toMap()
        val lp = heads[TajweedHead.LOG_PROBS] ?: return Decoded(emptyList(), emptyList(), emptyList(), ranked, probabilities)
        val w = TajweedHead.LOG_PROBS.width
        val conf = heads[TajweedHead.CONF]
        val t2 = heads.top2 ?: run {
            if (!ai.quranlab.tajweedformer.engine.TajweedJni.available) {
                throw RecognizerException.EngineNotAvailable("the decoded layer's top-2 is the engine's (zf_tajweed_top2)")
            }
            ai.quranlab.tajweedformer.engine.TajweedJni.top2(lp, heads.n40, w)
        }
        val frames = ArrayList<CtcFrame>(heads.n40)
        for (t in 0 until heads.n40) {
            frames.add(CtcFrame(frameOffset + t, t2[5 * t].toInt(), t2[5 * t + 2], t2[5 * t + 1].toInt(), t2[5 * t + 3]))
        }
        val tokens = ArrayList<DecodedToken>()
        var t = 0
        while (t < frames.size) {
            val id = frames[t].top
            var end = t
            while (end + 1 < frames.size && frames[end + 1].top == id) end++
            if (id != vocabulary.blank) {
                var peak = t
                for (k in t..end) if (frames[k].topLogProb > frames[peak].topLogProb) peak = k
                val c = conf?.let {
                    var sum = 0.0
                    var n = 0
                    for (k in 2 * t..2 * end + 1) if (k < it.size) { sum += sigmoid(it[k].toDouble()); n++ }
                    if (n > 0) (sum / n).toFloat() else null
                }
                val m = frames[peak].margin
                val pm = sigmoid(m.toDouble())
                val confidence = if (c != null) sqrt(c * pm) else pm
                tokens.add(DecodedToken(id, vocabulary.symbol(id), frameOffset + t, frameOffset + end, frames[peak].topLogProb, m,
                    frames[peak].runnerUp, c, confidence.toFloat()))
            }
            t = end + 1
        }
        val words = ArrayList<DecodedWord>()
        var cur = ArrayList<DecodedToken>()
        for (tok in tokens) {
            if (tok.id == TajweedModel.WORD_BOUNDARY) {
                if (cur.isNotEmpty()) words.add(DecodedWord(words.size, cur))
                cur = ArrayList()
            } else {
                cur.add(tok)
            }
        }
        if (cur.isNotEmpty()) words.add(DecodedWord(words.size, cur))
        return Decoded(frames, tokens, words, ranked, probabilities)
    }

    internal fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

/**
 * One head ranked per frame by the engine (zf_tajweed_topk): the [k] best entries of every frame, highest
 * value first, equal values by the lower id (a stable argsort), with each entry's margin to the next rank
 * (0 after the last class). Token heads (log_probs, inter1, inter4, id, timing) rank the 38 tokens, k up
 * to 38 (the full ranked list); class heads (gem, attr_*) rank their classes. Names:
 * [TajweedHead.classNames].
 */
class Ranked(val head: TajweedHead, val frames: Int, val k: Int, val ids: IntArray, val values: FloatArray, val margins: FloatArray) {
    fun id(frame: Int, rank: Int): Int = ids[frame * k + rank]
    fun value(frame: Int, rank: Int): Float = values[frame * k + rank]
    fun margin(frame: Int, rank: Int): Float = margins[frame * k + rank]

    /** The ids of one frame, best first. */
    fun row(frame: Int): IntArray = ids.copyOfRange(frame * k, frame * k + k)

    companion object {
        /** [head] of [heads] ranked [k] deep by the engine. Throws IllegalArgumentException for boundary
         *  or conf (single logits) and for k outside 1..the head's width. */
        @JvmStatic
        fun of(heads: TajweedHeads, head: TajweedHead, k: Int): Ranked {
            require(head.kind != HeadKind.LOGIT) { "${head.key} is a single logit: it has no ranking" }
            require(k in 1..head.width) { "k $k is not 1..${head.width} for ${head.key}" }
            val v = heads[head] ?: throw NoSuchElementException("${head.key} is absent")
            if (!ai.quranlab.tajweedformer.engine.TajweedJni.available) {
                throw RecognizerException.EngineNotAvailable("the decoded layer's ranking is the engine's (zf_tajweed_topk)")
            }
            val frames = heads.frames(head)
            val r = ai.quranlab.tajweedformer.engine.TajweedJni.topk(v, frames, head.width, k)
            val n = frames * k
            return Ranked(head, frames, k, IntArray(n) { r[3 * it].toInt() }, FloatArray(n) { r[3 * it + 1] }, FloatArray(n) { r[3 * it + 2] })
        }
    }
}
