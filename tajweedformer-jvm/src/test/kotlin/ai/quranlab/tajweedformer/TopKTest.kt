package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decoded layer's ranking in Kotlin (SDK 4.0: the engine is inside the Core, the ranking is a pure function of
 * the outputs): a stable argsort of the negated row, ties to the lower id, NaN last; margins as float32 differences to
 * the next rank of the whole row; top-2 the same ranking. Against a plain sort of the same rule, on random rows with
 * ties, and against the 3.0 vectors (`topk.json`) when TJ_VECTORS_DIR names the contract's sdk_3.0.0/vectors.
 */
class TopKTest : ai.quranlab.tajweedformer.OpenChecked() {
    private fun reference(row: FloatArray): List<Int> = row.indices.sortedWith { a, b ->
        val x = row[a]
        val y = row[b]
        when {
            x.isNaN() && y.isNaN() -> a - b
            x.isNaN() -> 1
            y.isNaN() -> -1
            x != y -> y.compareTo(x)
            else -> a - b
        }
    }

    @Test
    fun aStableArgsortWithItsMargins() {
        val rnd = java.util.Random(7)
        for (trial in 0 until 200) {
            val cols = 1 + rnd.nextInt(38)
            val row = FloatArray(cols) { (rnd.nextInt(7) - 6).toFloat() * 0.5f }
            if (trial % 17 == 0 && cols > 2) row[1] = Float.NaN
            val order = reference(row)
            for (k in 1..cols) {
                val r = TopK.topk(row, 1, cols, k)
                for (j in 0 until k) {
                    assertEquals("trial $trial k $k rank $j", order[j], r[3 * j].toInt())
                    val want = if (j + 1 < cols) row[order[j]] - row[order[j + 1]] else 0f
                    assertEquals(java.lang.Float.floatToRawIntBits(want), java.lang.Float.floatToRawIntBits(r[3 * j + 2]))
                }
            }
        }
    }

    @Test
    fun topTwoIsTheSameRanking() {
        val lp = floatArrayOf(-1f, -0.5f, -0.5f, -3f, /* frame 2 */ -9f, -9f, -0.1f, -2f)
        val t = TopK.top2(lp, 2, 4)
        assertEquals(listOf(1f, 2f, -0.5f, -0.5f, 0f, 2f, 3f, -0.1f, -2f, (-0.1f) - (-2f)), t.toList())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theTopKVectorsOf30() {
        val dir = System.getenv("TJ_VECTORS_DIR") ?: System.getProperty("TJ_VECTORS_DIR") ?: return
        val f = java.io.File(dir, "topk.json").takeIf { it.isFile } ?: return
        val v = Json.parse(f.readText()) as Map<String, Any?>
        for (c in (v["cases"] ?: v["rows"]) as List<Map<String, Any?>>) {
            val row = (c["row"] ?: c["values"]) as? List<Double> ?: continue
            val k = (c["k"] as Double).toInt()
            val r = TopK.topk(FloatArray(row.size) { row[it].toFloat() }, 1, row.size, k)
            val ids = (c["ids"] as? List<Double>)?.map { it.toInt() } ?: continue
            assertEquals(ids, (0 until k).map { r[3 * it].toInt() })
        }
    }
}
