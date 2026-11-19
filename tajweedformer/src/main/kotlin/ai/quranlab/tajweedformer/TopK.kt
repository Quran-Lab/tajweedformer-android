package ai.quranlab.tajweedformer

/**
 * The decoded layer's ranking: zf_tajweed_topk and zf_tajweed_top2 of the engine (tajweed/src/decoded.c), in Kotlin.
 * SDK 4.0 keeps the engine inside the closed Core, and these two are pure functions of the outputs, so they live in
 * the wrapper. The same arithmetic in single precision as the C (a comparison and one float subtraction per entry), so
 * the same bits (TopKTest checks the vectors of SPEC 6, `topk.json`).
 *
 * Per row, k passes of a selection over the entries not ranked yet: the next is the largest value, the lower id on a
 * tie (a strictly greater value replaces the candidate, scanning ids upwards), which is a stable argsort of the
 * negated row. The margin of the last entry asked for needs the next rank, so one more pass runs when k < cols. A NaN
 * entry ranks after every number.
 */
internal object TopK {
    const val MAX_COLS = 64

    /** [frames, k] ids, values and margins packed as [id, value, margin] per entry (the old bridge's layout). */
    @JvmStatic
    fun topk(values: FloatArray, frames: Int, cols: Int, k: Int): FloatArray {
        require(frames >= 0 && cols in 1..MAX_COLS && k in 1..cols && values.size >= frames * cols) { "not [frames, cols] with 1 <= k <= cols" }
        val out = FloatArray(frames * k * 3)
        val passes = if (k < cols) k + 1 else k
        val taken = BooleanArray(cols)
        val rank = IntArray(cols + 1)
        for (f in 0 until frames) {
            val base = f * cols
            java.util.Arrays.fill(taken, false)
            for (j in 0 until passes) {
                var best = -1
                for (v in 0 until cols) {
                    if (taken[v]) continue
                    val x = values[base + v]
                    if (best < 0) {
                        best = v
                        continue
                    }
                    val b = values[base + best]
                    if (x > b || (b != b && x == x)) best = v
                }
                taken[best] = true
                rank[j] = best
            }
            for (j in 0 until k) {
                val o = (f * k + j) * 3
                val v = values[base + rank[j]]
                out[o] = rank[j].toFloat()
                out[o + 1] = v
                out[o + 2] = if (j + 1 < cols) v - values[base + rank[j + 1]] else 0.0f
            }
        }
        return out
    }

    /** Per frame of log-probs: [token, runner_up, log_prob, runner_up_log_prob, margin] (zf_tajweed_top2). */
    @JvmStatic
    fun top2(logProbs: FloatArray, frames: Int, vocab: Int): FloatArray {
        require(frames >= 0 && vocab >= 2 && logProbs.size >= frames * vocab) { "log_probs is not [frames, vocab]" }
        val out = FloatArray(frames * 5)
        for (f in 0 until frames) {
            val r = topk(logProbs.copyOfRange(f * vocab, (f + 1) * vocab), 1, vocab, 2)
            val o = f * 5
            out[o] = r[0]
            out[o + 1] = r[3]
            out[o + 2] = r[1]
            out[o + 3] = r[4]
            out[o + 4] = r[1] - r[4]
        }
        return out
    }
}
