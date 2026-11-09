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
class LayersTest {
    private val vocab = Vocabulary(Array(38) { if (it == 0) "<blk>" else if (it == 1) "|" else "t$it" }, 0)

    /** n40 frames; frame t has its top token tops[t] at log-prob -0.1, the runner-up seconds[t] at
     *  -2.5, everything else -9; conf logits per 20 ms frame. */
    private fun heads(tops: IntArray, seconds: IntArray, conf: FloatArray? = null): TajweedHeads {
        val n = tops.size
        val lp = FloatArray(n * 38) { -9f }
        for (t in 0 until n) {
            lp[t * 38 + tops[t]] = -0.1f
            lp[t * 38 + seconds[t]] = -2.5f
        }
        val m = hashMapOf(TajweedHead.LOG_PROBS to lp)
        if (conf != null) m[TajweedHead.CONF] = conf
        return TajweedHeads(n, m)
    }

    @Test
    fun decodedLayer() {
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
        // the same greedy as TajweedHeads.greedy, without the conf head too
        val plain = Decoder.decode(heads(tops, secs), vocab)
        assertArrayEquals(heads(tops, secs).greedy(), plain.ids)
        assertNull(plain.tokens[0].conf)
        assertEquals(pm, plain.tokens[0].confidence.toDouble(), 1e-6)
    }

    private fun site(word: Int, llr: Double, cls: SiteClass = SiteClass.VOWEL_SUB, lo: Int = 2 * word, hi: Int = 2 * word + 1) =
        SiteVerdict(cls, CheckStatus.VALIDATED, word * 3, word, 31, 32, llr, llr, llr, 1.0, llr > 1.0, lo, hi)

    @Test
    fun siteFallbackWordScoresAreTheMaxClippedFusedLlr() {
        val sites = listOf(site(0, -3.0), site(0, 5.2, SiteClass.CONS_SUB), site(1, 4.0), site(1, 99.0, SiteClass.INSERTION), site(3, Double.NaN))
        val w = WordScores.fromSites(sites, 5, 4.93)
        assertEquals(5, w.size)
        assertEquals(5.2, w[0].score, 0.0)
        assertTrue(w[0].flagged)
        assertEquals(SiteClass.CONS_SUB, w[0].best!!.siteClass)
        assertEquals(30.0, w[1].score, 0.0)          // clipped to 30
        assertEquals(-30.0, w[2].score, 0.0)         // no site: -30, never flagged
        assertFalse(w[2].flagged)
        assertFalse(w[2].hasScore)
        assertEquals(-30.0, w[3].score, 0.0)         // NaN counts as -30
        assertEquals(0, w[0].frameLo)
        assertEquals(1, w[0].frameHi)
        assertEquals(80L, w[0].endMs)
        // at the threshold exactly: flagged (score >= tau, calibration's flag_rule)
        assertTrue(WordScores.fromSites(listOf(site(0, 4.93)), 1, 4.93)[0].flagged)
    }

    private fun calibration(word: String?): Calibration {
        val base = String(TestKit.resource("calibration_fixture.json"), Charsets.UTF_8)
        val text = if (word == null) base else base.trimEnd().removeSuffix("}") + ",\"word\":$word}"
        return Calibration.parse(text)
    }

    @Test
    fun wordSettingsPrecedenceAndFallback() {
        // no word block: the documented P2.5 table
        val d = WordCalibration.of(calibration(null))
        assertFalse(d.fromCalibration)
        assertEquals(4.93, d.threshold(WordLevel.GENERAL), 0.0)
        assertEquals(4.0, d.threshold(WordLevel.BEGINNER), 0.0)
        assertEquals(6.5, d.threshold(WordLevel.STRICT), 0.0)
        assertEquals(WordSettings(WordLevel.GENERAL, 4.93, SettingSource.CALIBRATION, false), WordSettings.resolve(d, null, null))
        // a block (top level here; the shipping file has one per context): presets as {tau}, default, floor, range
        val c = WordCalibration.of(calibration("{\"presets\":{\"general\":{\"tau\":5.5},\"beginner\":{\"tau\":1.5},\"strict\":{\"tau\":7}}," +
            "\"floor\":2.5,\"custom_range\":[2.5,12],\"default_preset\":\"beginner\"}"))
        assertTrue(c.fromCalibration)
        // beginner's 1.5 is raised to the floor
        assertEquals(WordSettings(WordLevel.BEGINNER, 2.5, SettingSource.CALIBRATION, true), WordSettings.resolve(c, null, null))
        // portal over calibration ("preset" or "threshold", exactly one); app over portal
        val remote = "{\"v\":1,\"preset\":\"balanced\",\"word\":{\"preset\":\"strict\"}}"
        assertEquals(WordSettings(WordLevel.STRICT, 7.0, SettingSource.REMOTE, true), WordSettings.resolve(c, remote, null))
        assertEquals(WordSettings(null, 3.0, SettingSource.REMOTE, true), WordSettings.resolve(c, "{\"word\":{\"threshold\":3.0}}", null))
        assertEquals(WordSettings(WordLevel.GENERAL, 5.5, SettingSource.APP, true), WordSettings.resolve(c, remote, WordOption.level(WordLevel.GENERAL)))
        // custom thresholds are clamped to the range and the floor, as the engine does
        assertEquals(WordSettings(null, 12.0, SettingSource.APP, true), WordSettings.resolve(c, remote, WordOption.threshold(20.0)))
        assertEquals(WordSettings(null, 2.5, SettingSource.APP, true), WordSettings.resolve(c, remote, WordOption.threshold(1.0)))
        assertEquals(WordSettings(null, 2.5, SettingSource.REMOTE, true), WordSettings.resolve(c, "{\"word\":{\"threshold\":1.0}}", null))
        // an invalid portal value is ignored (both fields, an unknown preset, not JSON)
        for (bad in listOf("{\"word\":{\"preset\":\"general\",\"threshold\":5}}", "{\"word\":{\"preset\":\"lenient\"}}", "not json")) {
            assertEquals(bad, SettingSource.CALIBRATION, WordSettings.resolve(c, bad, null).source)
        }
        try {
            WordSettings.resolve(d, null, WordOption.threshold(Double.NaN))
            fail("NaN")
        } catch (e: RecognizerException.InvalidAppOption) {
            assertEquals("invalid_app_option", e.code)
        }
    }

    /** The shipping calibration.json (747b7b4c): the levels come from its per-context
     *  word blocks, not from the fallback table. */
    @Test
    fun wordThresholdsComeFromTheShippingCalibration() {
        val f = java.io.File(TestKit.env("TJ_CALIBRATION") ?: "")
        org.junit.Assume.assumeTrue("the shipping calibration.json is not here", f.isFile)
        val cal = Calibration.read(f)
        for (ctx in cal.contexts.keys) {
            val w = WordCalibration.of(cal, ctx)
            val block = cal.contexts.getValue(ctx).raw["word"] as Map<*, *>
            val presets = block["presets"] as Map<*, *>
            assertTrue(ctx, w.fromCalibration)
            for (l in WordLevel.entries) {
                assertEquals("$ctx ${l.key}", (presets[l.key] as Map<*, *>)["tau"] as Double, w.threshold(l), 0.0)
            }
            assertEquals(WordLevel.fromKey(block["default_preset"] as String), w.defaultLevel)
            assertEquals(block["floor"] as Double, w.floor, 0.0)
            assertEquals((block["custom_range"] as List<*>)[1] as Double, w.customHi, 0.0)
            assertTrue((block["readout"] as String).contains("clipped to [-30, 30]"))
            println("word levels $ctx: strict ${w.threshold(WordLevel.STRICT)}, general ${w.threshold(WordLevel.GENERAL)}, " +
                "beginner ${w.threshold(WordLevel.BEGINNER)}, default ${w.defaultLevel.key}, range ${w.customLo}..${w.customHi}")
        }
        val c16 = WordCalibration.of(cal, "c16_l64")
        assertEquals(5.5368, c16.threshold(WordLevel.GENERAL), 0.0)
        assertEquals(WordSettings(WordLevel.GENERAL, 5.5368, SettingSource.CALIBRATION, true), WordSettings.resolve(c16, null, null))
    }

    @Test
    fun headsAreTypedWithRatesAndClassNames() {
        assertEquals(12, TajweedHead.entries.size)
        for (h in TajweedHead.entries) {
            assertEquals(h.key, h.width, h.classNames(vocab).size)
            assertTrue(h.frameMs == 20 || h.frameMs == 40)
        }
        assertEquals(listOf("none", "plain", "geminate", "ikhfa", "idgham"), TajweedHead.ATTR_GHUNNA.classNames(vocab))
        assertEquals(listOf("single", "held"), TajweedHead.GEM.fixedClasses)
        assertEquals("|", TajweedHead.LOG_PROBS.classNames(vocab)[1])
        assertEquals(HeadKind.LOGIT, TajweedHead.BOUNDARY.kind)
        assertEquals(40, TajweedHead.INTER4_LOG_PROBS.frameMs)
        assertEquals(20, TajweedHead.ID_LOG_PROBS.frameMs)
        val h = TajweedHeads(1, TajweedHead.entries.associateWith { FloatArray((if (it.frameMs == 40) 1 else 2) * it.width) })
        assertEquals(setOf(TajweedHead.GEM, TajweedHead.CONF), h.only(setOf(TajweedHead.GEM, TajweedHead.CONF)).available)
    }

    @Test
    fun topKComesFromTheEngineForTokenAndClassHeads() {
        org.junit.Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val rnd = java.util.Random(3)
        val n40 = 6
        val m = HashMap<TajweedHead, FloatArray>()
        for (h in TajweedHead.entries) m[h] = FloatArray(n40 * (if (h.frameMs == 40) 1 else 2) * h.width) { (rnd.nextInt(20) - 30).toFloat() / 3f }
        // a tie on frame 0 of log_probs: the lower id ranks first
        val lp = m.getValue(TajweedHead.LOG_PROBS)
        lp[7] = 5f; lp[3] = 5f
        val heads = TajweedHeads(n40, m)
        val d = Decoder.decode(heads, vocab, 0, mapOf(TajweedHead.LOG_PROBS to 38, TajweedHead.INTER1_LOG_PROBS to 2, TajweedHead.GEM to 2,
            TajweedHead.ATTR_GHUNNA to 5))
        val full = d.ranked.getValue(TajweedHead.LOG_PROBS)
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
        val gem = d.ranked.getValue(TajweedHead.GEM)
        assertEquals(2 * n40, gem.frames)
        val g = m.getValue(TajweedHead.GEM)
        for (f in 0 until gem.frames) assertEquals(if (g[2 * f + 1] > g[2 * f]) 1 else 0, gem.id(f, 0))
        assertEquals(5, d.ranked.getValue(TajweedHead.ATTR_GHUNNA).k)
        // boundary and conf: probabilities, no ranking
        assertEquals(2 * n40, d.probabilities.getValue(TajweedHead.CONF).size)
        assertEquals(1.0 / (1.0 + Math.exp(-m.getValue(TajweedHead.BOUNDARY)[0].toDouble())), d.probabilities.getValue(TajweedHead.BOUNDARY)[0].toDouble(), 1e-6)
        for (bad in listOf<() -> Unit>({ Ranked.of(heads, TajweedHead.CONF, 1) }, { Ranked.of(heads, TajweedHead.LOG_PROBS, 39) },
            { Ranked.of(heads, TajweedHead.GEM, 3) })) {
            try { bad(); fail() } catch (e: IllegalArgumentException) {}
        }
    }
}
