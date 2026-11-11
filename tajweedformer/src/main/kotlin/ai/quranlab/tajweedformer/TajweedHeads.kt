package ai.quranlab.tajweedformer

/**
 * One raw head of the model for a span of audio: [frames] rows of [classes] values, row major in [values].
 *
 * The twelve heads: `log_probs`, `inter1_log_probs`, `inter4_log_probs` (CTC over the model's tokens, 40 ms frames),
 * `id_log_probs`, `timing_log_probs` (CTC, 20 ms frames), `gem`, `attr_tafkheem`, `attr_ghunna`, `attr_qalqalah`,
 * `attr_face`, `boundary`, `conf` (attribute heads, 20 ms frames). CTC heads hold log-probabilities (natural log);
 * the attribute heads hold probabilities, except `boundary` and `conf`, which hold one logit per frame (apply a
 * sigmoid). 20 ms frames 2k and 2k + 1 lie in 40 ms frame k.
 */
public class TajweedHead internal constructor(
    /** The head's name, for example `log_probs`. */
    public val name: String,
    /** `CTC` or `ATTRIBUTE`. */
    public val kind: Kind,
    /** Milliseconds per frame: 40 or 20. */
    public val frameMs: Int,
    /** Rows. */
    public val frames: Int,
    /** Values per row: 38 for the CTC heads (the model's tokens), the class count for an attribute head. */
    public val classes: Int,
    /** `frames * classes` values, row major. The array is the SDK's own: read it, do not change it. */
    public val values: FloatArray,
) {
    public enum class Kind { CTC, ATTRIBUTE }

    /** The names of the classes of an attribute head (`single`, `held` for `gem`); the token names for a CTC head. */
    public val classNames: List<String> get() = names

    internal var names: List<String> = emptyList()

    /** One value: row [frame], column [cls]. */
    public operator fun get(frame: Int, cls: Int): Float {
        if (frame !in 0 until frames || cls !in 0 until classes) {
            throw RecognizerException.InvalidAppOption("frame, class", "($frame, $cls) is outside [$frames, $classes]")
        }
        return values[frame * classes + cls]
    }
}

/**
 * Raw model output for a span of audio (one chunk, or the whole take): every selected head, and greedy tokens.
 * Plain data.
 *
 * ```
 * val heads = session.finish()
 * val logProbs = heads["log_probs"]!!            // [frames, 38] log-probabilities, 40 ms per frame
 * val heard = heads.greedy().map { it.name }     // what the model heard
 * ```
 */
public class TajweedHeads internal constructor(
    internal val raw: HeadValues,
    private val vocabulary: Vocabulary,
    /** The first 40 ms frame of the span within the take. */
    private val firstFrame: Int,
    private val topK: Int,
    /** The decoded layer when the Session already built it (the take's final one). */
    private val prebuilt: TajweedDecoded? = null,
) {
    /** The names of the heads it holds, in the model's order. */
    public val names: List<String> get() = HeadId.entries.filter { raw.has(it) }.map { it.key }

    /** One raw head, or null when these Heads do not hold it (it was not selected). An unknown name is `invalid_argument`. */
    public operator fun get(name: String): TajweedHead? {
        val id = HeadId.fromKey(name) ?: throw RecognizerException.InvalidAppOption("head", "$name is not a head (${HeadId.entries.joinToString { it.key }})")
        val v = raw[id] ?: return null
        return TajweedHead(id.key, if (id.kind == HeadKind.LOG_PROBS) TajweedHead.Kind.CTC else TajweedHead.Kind.ATTRIBUTE,
            id.frameMs, raw.frames(id), id.width, v).also { it.names = id.classNames(vocabulary) }
    }

    /**
     * Greedy tokens of a CTC head (repeats collapsed, blanks removed) with their times within the take. The
     * confidence of a token is the sigmoid of its margin over the runner-up at its peak frame (for `log_probs` see
     * [decoded], which also uses the `conf` head). A head that is absent gives an empty list; a head that is not a
     * CTC head is `invalid_argument`.
     */
    @JvmOverloads
    public fun greedy(name: String = "log_probs"): List<TajweedToken> {
        val id = HeadId.fromKey(name)?.takeIf { it.kind == HeadKind.LOG_PROBS }
            ?: throw RecognizerException.InvalidAppOption("head", "$name is not a CTC head (${HeadId.CTC_HEADS.joinToString { it.key }})")
        val v = raw[id] ?: return emptyList()
        val w = id.width
        val ms = id.frameMs.toLong()
        val offset = firstFrame * (40 / id.frameMs)
        val out = ArrayList<TajweedToken>()
        var t = 0
        val n = raw.frames(id)
        fun best(f: Int): Int { var b = 0; for (k in 1 until w) if (v[f * w + k] > v[f * w + b]) b = k; return b }
        while (t < n) {
            val top = best(t)
            var end = t
            while (end + 1 < n && best(end + 1) == top) end++
            if (top != vocabulary.blank) {
                var peak = t
                for (f in t..end) if (v[f * w + top] > v[peak * w + top]) peak = f
                var second = Float.NEGATIVE_INFINITY
                for (k in 0 until w) if (k != top && v[peak * w + k] > second) second = v[peak * w + k]
                val margin = v[peak * w + top] - second
                out.add(TajweedToken(top, vocabulary.symbol(top), (offset + t) * ms, (offset + peak) * ms, (offset + end) * ms,
                    Decoder.sigmoid(margin.toDouble()).toFloat()))
            }
            t = end + 1
        }
        return out
    }

    /** The decoded layer of exactly these Heads (for the take's Heads from `finish`: the final one). */
    public fun decoded(): TajweedDecoded = prebuilt ?: TakeDecoder(vocabulary, topK).also { it.add(raw, 0, firstFrame) }.snapshot(final = true)
}
