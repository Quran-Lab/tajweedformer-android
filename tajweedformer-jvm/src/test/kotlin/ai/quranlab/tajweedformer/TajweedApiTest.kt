package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The V4 API surface without the engine: the default engine throws EngineNotAvailable, and a fake
 * engine checks the plumbing (chunks streamed, heads kept for the take, settings handed to the
 * scorer, one session at a time).
 */
class TajweedApiTest {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun model(): TajweedModel {
        val dir = Files.createTempDirectory("tj-api-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        return TajweedModel.open(dir, TestKit.verifier)
    }

    /** One chunk's heads: n40 frames with every value = [tag]; log_probs peaks on token [token]. */
    private fun chunk(n40: Int, tag: Float, token: Int): TajweedHeads {
        val m = HashMap<TajweedHead, FloatArray>()
        for (h in TajweedHead.entries) m[h] = FloatArray((if (h.frameMs == 40) n40 else 2 * n40) * h.width) { tag }
        val lp = m.getValue(TajweedHead.LOG_PROBS)
        for (t in 0 until n40) for (k in 0 until 38) lp[t * 38 + k] = if (k == token) 0f else -10f
        return TajweedHeads(n40, m)
    }

    private class FakeEngine(val chunkSamples: Int, val n40: Int) : TajweedEngine {
        var lastConfig: TajweedConfig? = null
        var lastSettings: ResolvedTajweedSettings? = null
        var streams = 0
        override val chunkMs: Int get() = chunkSamples / 16
        override fun createStream(): TajweedEngineStream {
            streams++
            return object : TajweedEngineStream {
                var pending = 0
                var made = 0
                override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
                    pending += length
                    val out = ArrayList<TajweedHeads>()
                    while (pending >= chunkSamples) {
                        pending -= chunkSamples
                        out.add(heads(made++))
                    }
                    return out
                }

                override fun finish(): List<TajweedHeads> = listOf(heads(made++))
                override fun reset() { pending = 0; made = 0 }
                override fun close() {}
            }
        }

        fun heads(i: Int): TajweedHeads {
            val m = HashMap<TajweedHead, FloatArray>()
            for (h in TajweedHead.entries) m[h] = FloatArray((if (h.frameMs == 40) n40 else 2 * n40) * h.width) { i.toFloat() }
            return TajweedHeads(n40, m)
        }

        override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult {
            lastConfig = config
            lastSettings = settings
            val s = settings.sites.getValue(SiteClass.VOWEL_SUB)
            val site = SiteVerdict(SiteClass.VOWEL_SUB, s.status, 3, 0, 32, 33, 2.0, 1.0, 1.8, s.threshold!!, s.enabled && 1.8 > s.threshold!!, 4, 6)
            return TajweedResult(surah, ayah, settings, listOf(site), emptyList(), emptyList(), emptyList(),
                TajweedSummary(1, if (site.flagged) 1 else 0, 0, 0, 0, 0, 0, 0, 0.0, false))
        }

        override fun expectedTokens(surah: Int, ayah: Int) = intArrayOf(1, 31)
        override fun close() {}
    }

    @Test
    fun defaultEngineIsNotAvailableYet() {
        val model = model()
        try {
            TajweedRecognizer.open(model)
            fail("the stub engine opened")
        } catch (e: RecognizerException.EngineNotAvailable) {
            assertTrue(e.message!!.contains("not available"))
        } catch (e: RecognizerException.ModelLoadFailed) {
            // a library with zf_tajweed loaded (the scorer vectors run): this test's folder holds
            // placeholder bytes for the expected table and engine files, which the engine refuses
            assertTrue(ai.quranlab.tajweedformer.engine.TajweedJni.available)
        }
    }

    @Test
    fun invalidAppOptionsThrowAtOpen() {
        val model = model()
        val engine = TajweedEngineFactory { _, _, _ -> FakeEngine(5120, 8) }
        for (o in listOf(
            TajweedRecognizer.Options(context = "c16_l256", engine = engine),
            TajweedRecognizer.Options(tajweed = TajweedOptions(context = "c16_l256"), engine = engine),
        )) {
            try {
                TajweedRecognizer.open(model, o)
                fail()
            } catch (e: RecognizerException.InvalidAppOption) {
            }
        }
        try {
            TajweedRecognizer.open(model, TajweedRecognizer.Options(sampleRate = 96_000, engine = engine))
            fail()
        } catch (e: RecognizerException.UnsupportedAudioFormat) {
        }
    }

    @Test
    fun sessionStreamsChunksAndScores() = runBlocking {
        val model = model()
        val fake = FakeEngine(5120, 8)
        var openedContext = ""
        val tj = TajweedRecognizer.open(model, TajweedRecognizer.Options(
            tajweed = TajweedOptions(checks = mapOf(TajweedCheck.LETTERS to true)),
            engine = { _, ctx, _ -> openedContext = ctx; fake },
        ))
        assertEquals("c16_l64", openedContext)
        assertEquals(320, tj.chunkMs)
        tj.remoteConfig = "{\"v\":1,\"preset\":\"very_sensitive\",\"context\":\"c24_l72\",\"checks\":{\"insertion\":true}}"
        assertEquals("c24_l72", tj.requestedContext)
        val s = tj.startSession()
        try {
            tj.startSession()
            fail("a second session started")
        } catch (e: RecognizerException.SessionActive) {
        }
        val collected = async { s.chunks.toList() }
        s.append(FloatArray(5120 * 2 + 100))
        s.append(FloatArray(5000))
        val result = s.finishAndScore(1, 2)
        val chunks = collected.await()
        // 2 chunks from the first push, none from the second (5100 samples pending), 1 from finish
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals(listOf(0f, 1f, 2f), chunks.map { it.heads[TajweedHead.CONF]!![0] })
        assertEquals(1, fake.streams)
        assertEquals(result.settings, fake.lastSettings)
        assertEquals(1, result.surah)
        assertEquals(2, result.ayah)
        // settings: the recognizer's context counts as the app's; the remote preset applies; the
        // remote's unknown check key is dropped
        val settings = result.settings
        assertEquals("c16_l64", settings.context)
        assertEquals(TajweedPreset.VERY_SENSITIVE, settings.preset)
        assertEquals(0.0, settings.sites.getValue(SiteClass.VOWEL_SUB).threshold!!, 1e-12)
        assertEquals(listOf("remote.checks.insertion"), settings.dropped)
        assertTrue(result.sites.single().flagged)
        assertEquals(mapOf(TajweedHead.LOG_PROBS to 2.0, TajweedHead.INTER1_LOG_PROBS to 1.0), result.sites.single().headLlrs)
        // the next take
        val s2 = tj.startSession()
        s2.cancel()
        try {
            s2.append(FloatArray(10))
            fail()
        } catch (e: RecognizerException.SessionClosed) {
        }
        tj.close()
    }

    @Test
    fun finishReturnsEveryHeadOfTheTake() = runBlocking {
        val fake = FakeEngine(5120, 8)
        val tj = TajweedRecognizer.open(model(), TajweedRecognizer.Options(engine = { _, _, _ -> fake }))
        val s = tj.startSession()
        s.append(FloatArray(5120 * 3))
        val heads = s.finish()
        assertEquals(32, heads.n40)
        assertEquals(64, heads.n20)
        val gem = heads[TajweedHead.GEM]!!
        assertEquals(64 * 2, gem.size)
        assertEquals(0f, gem[0])
        assertEquals(3f, gem.last())
        tj.close()
    }

    @Test
    fun headsShapesConcatAndGreedy() {
        try {
            TajweedHeads(2, mapOf(TajweedHead.GEM to FloatArray(3)))
            fail()
        } catch (e: IllegalArgumentException) {
        }
        val a = chunk(2, 0f, 31)
        val b = chunk(3, 1f, 31)
        val c = TajweedHeads.concat(listOf(a, b, chunk(1, 2f, 0), chunk(1, 2f, 34)))
        assertEquals(7, c.n40)
        assertArrayEquals(intArrayOf(31, 34), c.greedy())
        assertEquals(1f, c.row(TajweedHead.ATTR_GHUNNA, 4)[0])
        assertEquals(5, c.row(TajweedHead.ATTR_GHUNNA, 4).size)
        // a head missing from one chunk is missing from the take
        val partial = TajweedHeads(1, mapOf(TajweedHead.LOG_PROBS to FloatArray(38)))
        val d = TajweedHeads.concat(listOf(a, partial))
        assertTrue(d.has(TajweedHead.LOG_PROBS))
        assertFalse(d.has(TajweedHead.GEM))
        assertNull(d[TajweedHead.CONF])
        assertEquals(TajweedHead.SCORER_HEADS.map { it.key }.toSet(),
            setOf("log_probs", "inter1_log_probs", "timing_log_probs", "gem", "attr_qalqalah"))
    }

    @Test
    fun releaseBuildsRefuseOffChecks() {
        val cal = model().calibration
        val app = TajweedOptions(checks = mapOf(TajweedCheck.LAZIM to true, TajweedCheck.LETTERS to true))
        val release = TajweedConfig(app = app, debugBuild = false).resolve(cal)
        assertFalse(release.sites.getValue(SiteClass.INSERTION).enabled)
        assertFalse(release.lazim.enabled)
        assertEquals(listOf("app:insertion", "app:lazim"), release.refused)
        val debug = TajweedConfig(app = app, debugBuild = true).resolve(cal)
        assertTrue(debug.sites.getValue(SiteClass.INSERTION).enabled)
        assertTrue(debug.lazim.enabled)
        assertEquals(emptyList<String>(), debug.refused)
        // the portal never can, debug or not
        val remote = TajweedConfig("{\"v\":1,\"checks\":{\"letters\":true}}", debugBuild = true).resolve(cal)
        assertFalse(remote.sites.getValue(SiteClass.INSERTION).enabled)
        assertEquals(listOf("remote:insertion"), remote.refused)
    }
}
