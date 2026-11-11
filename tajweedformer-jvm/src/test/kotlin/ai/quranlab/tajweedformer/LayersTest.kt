package ai.quranlab.tajweedformer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.exp

/** P2.5's decoded layer (top-1 / runner-up, transcript, confidences, words, times) and the per-word
 *  tajweed readout with its settings precedence; head metadata. */
class LayersTest : OpenChecked() {
    private val vocab = Vocabulary(Array(38) { if (it == 0) "<blk>" else if (it == 1) "|" else "t$it" }, 0)

    /** n40 frames; frame t has its top token tops[t] at log-prob -0.1, the runner-up seconds[t] at
     *  -2.5, everything else -9; conf logits per 20 ms frame. */
    private fun heads(tops: IntArray, seconds: IntArray, conf: FloatArray? = null): HeadValues {
        val n = tops.size
        val lp = FloatArray(n * 38) { -9f }
        for (t in 0 until n) {
            lp[t * 38 + tops[t]] = -0.1f
            lp[t * 38 + seconds[t]] = -2.5f
        }
        val m = hashMapOf(HeadId.LOG_PROBS to lp)
        if (conf != null) m[HeadId.CONF] = conf
        return HeadValues(n, m)
    }

    @Test
    fun decodedLayer() {
        // the default top-k ranking is the engine's (zf_tajweed_topk), so this runs with the JNI library
        org.junit.Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        //            frame: 0  1  2  3  4  5  6  7
        val tops = intArrayOf(0, 5, 5, 0, 5, 1, 7, 7)
        val secs = intArrayOf(5, 0, 0, 5, 0, 0, 0, 3)
        val conf = FloatArray(16) { if (it < 8) 4f else -4f }
        val d = Decoder.decode(heads(tops, secs, conf), vocab, frameOffset = 10)
        assertEquals(8, d.frames.size)
        val f1 = d.frames[1]
        assertEquals(11, f1.index)
        assertEquals(5, f1.top)
        assertEquals(0, f1.runnerUp)
        assertEquals(2.4f, f1.margin, 1e-6f)
        assertEquals(440L, f1.startMs)
        assertEquals(480L, f1.endMs)
        // greedy: 5 (frames 1-2), 5 again after a blank (frame 4), the boundary, 7
        assertArrayEquals(intArrayOf(5, 5, 1, 7), d.ids)
        val t0 = d.tokens[0]
        assertEquals(11, t0.firstFrame)
        assertEquals(12, t0.lastFrame)
        assertEquals(440L, t0.startMs)
        assertEquals(520L, t0.endMs)
        assertEquals(1f / (1f + exp(-4f)), t0.conf!!, 1e-6f)
        val pm = 1.0 / (1.0 + exp(-2.4))
        assertEquals(Math.sqrt(t0.conf!! * pm), t0.confidence.toDouble(), 1e-6)
        assertTrue(d.tokens[3].confidence < t0.confidence)   // conf logits -4 there
        // words split at "|"
        assertEquals(2, d.words.size)
        assertEquals(listOf(5, 5), d.words[0].tokens.map { it.id })
        assertEquals(listOf(7), d.words[1].tokens.map { it.id })
        assertEquals("t5 t5 | t7", d.text)
        // the same greedy as HeadValues.greedy, without the conf head too
        val plain = Decoder.decode(heads(tops, secs), vocab)
        assertArrayEquals(heads(tops, secs).greedy(), plain.ids)
        assertNull(plain.tokens[0].conf)
        assertEquals(pm, plain.tokens[0].confidence.toDouble(), 1e-6)
    }

    fun headsAreTypedWithRatesAndClassNames() {
        assertEquals(12, HeadId.entries.size)
        for (h in HeadId.entries) {
            assertEquals(h.key, h.width, h.classNames(vocab).size)
            assertTrue(h.frameMs == 20 || h.frameMs == 40)
        }
        assertEquals(listOf("none", "plain", "geminate", "ikhfa", "idgham"), HeadId.ATTR_GHUNNA.classNames(vocab))
        assertEquals(listOf("single", "held"), HeadId.GEM.fixedClasses)
        assertEquals("|", HeadId.LOG_PROBS.classNames(vocab)[1])
        assertEquals(HeadKind.LOGIT, HeadId.BOUNDARY.kind)
        assertEquals(40, HeadId.INTER4_LOG_PROBS.frameMs)
        assertEquals(20, HeadId.ID_LOG_PROBS.frameMs)
        val h = HeadValues(1, HeadId.entries.associateWith { FloatArray((if (it.frameMs == 40) 1 else 2) * it.width) })
        assertEquals(setOf(HeadId.GEM, HeadId.CONF), h.only(setOf(HeadId.GEM, HeadId.CONF)).available)
    }

    @Test
    fun topKComesFromTheEngineForTokenAndClassHeads() {
        org.junit.Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val rnd = java.util.Random(3)
        val n40 = 6
        val m = HashMap<HeadId, FloatArray>()
        for (h in HeadId.entries) m[h] = FloatArray(n40 * (if (h.frameMs == 40) 1 else 2) * h.width) { (rnd.nextInt(20) - 30).toFloat() / 3f }
        // a tie on frame 0 of log_probs: the lower id ranks first
        val lp = m.getValue(HeadId.LOG_PROBS)
        lp[7] = 5f; lp[3] = 5f
        val heads = HeadValues(n40, m)
        val d = Decoder.decode(heads, vocab, 0, mapOf(HeadId.LOG_PROBS to 38, HeadId.INTER1_LOG_PROBS to 2, HeadId.GEM to 2,
            HeadId.ATTR_GHUNNA to 5))
        val full = d.ranked.getValue(HeadId.LOG_PROBS)
        assertEquals(38, full.k)
        for (t in 0 until n40) {
            // the full list is a stable argsort of the row (value down, ties by the lower id)
            val want = (0 until 38).sortedWith(compareBy<Int>({ -lp[t * 38 + it] }, { it }))
            assertEquals(want, full.row(t).toList())
            // k = 2 equals the frame's top-1 / runner-up (the engine's top-2)
            assertEquals(d.frames[t].top, full.id(t, 0))
            assertEquals(d.frames[t].runnerUp, full.id(t, 1))
            assertEquals(lp[t * 38 + full.id(t, 0)] - lp[t * 38 + full.id(t, 1)], full.margin(t, 0), 1e-6f)
            assertEquals(0f, full.margin(t, 37), 0f)
        }
        assertEquals(listOf(3, 7), full.row(0).take(2))
        // class heads: ranked classes at 20 ms
        val gem = d.ranked.getValue(HeadId.GEM)
        assertEquals(2 * n40, gem.frames)
        val g = m.getValue(HeadId.GEM)
        for (f in 0 until gem.frames) assertEquals(if (g[2 * f + 1] > g[2 * f]) 1 else 0, gem.id(f, 0))
        assertEquals(5, d.ranked.getValue(HeadId.ATTR_GHUNNA).k)
        // boundary and conf: probabilities, no ranking
        assertEquals(2 * n40, d.probabilities.getValue(HeadId.CONF).size)
        assertEquals(1.0 / (1.0 + Math.exp(-m.getValue(HeadId.BOUNDARY)[0].toDouble())), d.probabilities.getValue(HeadId.BOUNDARY)[0].toDouble(), 1e-6)
        for (bad in listOf<() -> Unit>({ Ranked.of(heads, HeadId.CONF, 1) }, { Ranked.of(heads, HeadId.LOG_PROBS, 39) },
            { Ranked.of(heads, HeadId.GEM, 3) })) {
            try { bad(); fail() } catch (e: IllegalArgumentException) {}
        }
    }
}
