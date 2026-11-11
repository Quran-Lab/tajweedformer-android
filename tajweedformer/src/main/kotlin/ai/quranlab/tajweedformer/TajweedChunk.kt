package ai.quranlab.tajweedformer

/**
 * One chunk of a take as the engine finished it (320 ms of audio at the default context): where it is in the take,
 * what ran it, whether it holds speech, its raw Heads, and the decoded layer of the take so far, ready to show.
 *
 * ```
 * session.chunks.collect { chunk -> heard.text = chunk.decoded.text }
 * ```
 */
public class TajweedChunk internal constructor(
    /** Chunks since the take started, from 0. */
    public val index: Int,
    /** Where the chunk's audio starts and ends in the take, in ms of the model's frame grid. */
    public val startMs: Long,
    public val endMs: Long,
    /** Its first 40 ms frame within the take (`startMs / 40`). */
    public val firstFrame: Int,
    /** The Backend that ran it. */
    public val backend: TajweedBackend,
    /** True when the chunk holds speech frames: the model wrote a token within half a second of one of its frames. */
    public val speech: Boolean,
    /** The Heads of this chunk only. */
    public val heads: TajweedHeads,
    /** The decoded layer of the take SO FAR. */
    public val decoded: TajweedDecoded,
)

/**
 * The decoded layer: what the model heard, ready to show. Built from `log_probs` by greedy CTC decoding (no language
 * model, no expected text): the tokens with their times and a display confidence, the words (tokens split at the
 * word bar) and every 40 ms frame's best token, runner-up and margin.
 *
 * On a chunk it covers the take so far. [settled] says how many leading [tokens] will not change any more in this
 * take; the tokens after them are tentative (the model may still be inside the sound). [isFinal] is true on the last
 * one of the take, where every token is settled.
 */
public class TajweedDecoded internal constructor(
    /** The tokens heard so far, in order (word bars included). */
    public val tokens: List<TajweedToken>,
    /** Every 40 ms frame so far. */
    public val frames: List<TajweedFrame>,
    /**
     * The count of leading [tokens] that will not change in this take. It never decreases, except once after a
     * `BackendChanged` event that replaced chunks.
     */
    public val settled: Int,
    /** True on the last decoded layer of the take. */
    public val isFinal: Boolean,
) {
    /** The token names separated by spaces, word bars included: `b i s m i | l l aa h i`. */
    public val text: String by lazy { tokens.joinToString(" ") { it.name } }

    /** The tokens between word bars. */
    public val words: List<TajweedWord> by lazy {
        val out = ArrayList<TajweedWord>()
        var cur = ArrayList<TajweedToken>()
        for (t in tokens) {
            if (t.id == ModelFolder.WORD_BOUNDARY) {
                if (cur.isNotEmpty()) out.add(TajweedWord(out.size, cur))
                cur = ArrayList()
            } else {
                cur.add(t)
            }
        }
        if (cur.isNotEmpty()) out.add(TajweedWord(out.size, cur))
        out
    }
}

/** One token the model heard: a run of frames whose best token is the same, not blank. */
public class TajweedToken internal constructor(
    /** The token's id in the model's tokens. */
    public val id: Int,
    /** Its name (`model.tokens.name(id)`). */
    public val name: String,
    /** The start of its first frame, of its peak frame (the frame where it is most probable) and of its last frame,
     *  in ms within the take. */
    public val firstMs: Long,
    public val peakMs: Long,
    public val lastMs: Long,
    /** A display value in 0..1 (the model's own confidence head and the margin over the runner-up). Not calibrated. */
    public val confidence: Float,
) {
    override fun equals(other: Any?): Boolean = other is TajweedToken && other.id == id && other.firstMs == firstMs &&
        other.peakMs == peakMs && other.lastMs == lastMs && other.confidence == confidence

    override fun hashCode(): Int = listOf(id, firstMs, peakMs, lastMs, confidence).hashCode()

    override fun toString(): String = "$name@$firstMs"
}

/** The tokens between two word bars. */
public class TajweedWord internal constructor(
    /** Words since the take started, from 0. */
    public val index: Int,
    public val tokens: List<TajweedToken>,
) {
    /** The token names of the word, separated by spaces. */
    public val text: String get() = tokens.joinToString(" ") { it.name }
    public val firstMs: Long get() = tokens.first().firstMs
    public val lastMs: Long get() = tokens.last().lastMs
    /** The lowest token confidence of the word. */
    public val confidence: Float get() = tokens.minOf { it.confidence }
}

/** One 40 ms frame of `log_probs`: the best token, the runner-up and the margin between them. */
public class TajweedFrame internal constructor(
    /** The frame's index within the take; it covers `[40 * index, 40 * (index + 1))` ms. */
    public val index: Int,
    /** The best token's id (0 is blank) and its log-probability. */
    public val top: Int,
    public val topLogProb: Float,
    /** The second best token's id. Ties go to the lower id. */
    public val runnerUp: Int,
    /** `log P(top) - log P(runnerUp)`, 0 or more. */
    public val margin: Float,
    /** With the Session option `topK`: the K best token ids of the frame, best first; empty otherwise. */
    public val topK: List<Int>,
    /** The log-probabilities of [topK]. */
    public val topKLogProbs: List<Float>,
) {
    public val startMs: Long get() = index * 40L
}
