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
 * The recognizer (the internals under the public Model and Session) without the engine: the default engine throws
 * EngineNotAvailable, and a fake engine checks the plumbing (chunks streamed, heads kept for the take, the context
 * resolved from the app, the config and the manifest, one session at a time).
 */
class TajweedApiTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun model(): ModelFolder {
        val dir = Files.createTempDirectory("tj-api-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        return ModelFolder.open(dir, TestKit.verifier)
    }

    /** One chunk's heads: n40 frames with every value = [tag]; log_probs peaks on token [token]. */
    private fun chunk(n40: Int, tag: Float, token: Int): HeadValues {
        val m = HashMap<HeadId, FloatArray>()
        for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) n40 else 2 * n40) * h.width) { tag }
        val lp = m.getValue(HeadId.LOG_PROBS)
        for (t in 0 until n40) for (k in 0 until 38) lp[t * 38 + k] = if (k == token) 0f else -10f
        return HeadValues(n40, m)
    }

    private class FakeEngine(val chunkSamples: Int, val n40: Int) : TajweedEngine {
        var streams = 0
        override val chunkMs: Int get() = chunkSamples / 16
        override fun createStream(): TajweedEngineStream {
            streams++
            return object : TajweedEngineStream {
                var pending = 0
                var made = 0
                override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                    pending += length
                    val out = ArrayList<HeadValues>()
                    while (pending >= chunkSamples) {
                        pending -= chunkSamples
                        out.add(heads(made++))
                    }
                    return out
                }

                override fun finish(): List<HeadValues> = listOf(heads(made++))
                override fun reset() { pending = 0; made = 0 }
                override fun close() {}
            }
        }

        fun heads(i: Int): HeadValues {
            val m = HashMap<HeadId, FloatArray>()
            for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) n40 else 2 * n40) * h.width) { i.toFloat() }
            return HeadValues(n40, m)
        }

        override fun close() {}
    }

    @Test
    fun defaultEngineIsNotAvailableYet() {
        val model = model()
        try {
            Recognizer.open(model)
            fail("the stub engine opened")
        } catch (e: RecognizerException.EngineNotAvailable) {
            assertTrue(e.message!!.contains("not available"))
        } catch (e: RecognizerException.RuntimeUnsupported) {
            // no native library for this machine: the runtime is the problem, and the fix says where the SDK runs
            assertEquals("runtime_unsupported", e.code)
            assertTrue(e.message!!, e.message!!.contains("tajweedformer_jni"))
            assertTrue(e.fix, e.fix.contains(SdkInfo.MINIMUM))
        } catch (e: RecognizerException.ModelLoadFailed) {
            // a library with the engine loaded: this test's folder holds placeholder bytes for the engine files,
            // which the engine refuses
            assertTrue(ai.quranlab.tajweedformer.engine.TajweedJni.available)
        }
    }

    @Test
    fun invalidAppOptionsThrowAtOpen() {
        val model = model()
        val engine = TajweedEngineFactory { _, _, _ -> FakeEngine(5120, 8) }
        // the app's context must be one the model ships; the same name from the signed config is ignored
        try {
            Recognizer.open(model, Recognizer.Options(context = "c16_l256", engine = engine))
            fail()
        } catch (e: RecognizerException.InvalidAppOption) {
            assertEquals("invalid_argument", e.code)
        }
        Recognizer.open(model, Recognizer.Options(configContext = "c16_l256", engine = engine)).use { assertEquals("c16_l64", it.context) }
        Recognizer.open(model, Recognizer.Options(configContext = "c24_l72", engine = engine)).use { assertEquals("c24_l72", it.context) }
        Recognizer.open(model, Recognizer.Options(context = "c16_l64", configContext = "c24_l72", engine = engine)).use { assertEquals("c16_l64", it.context) }
        try {
            Recognizer.open(model, Recognizer.Options(sampleRate = 96_000, engine = engine))
            fail()
        } catch (e: RecognizerException.UnsupportedAudioFormat) {
        }
    }

    @Test
    fun sessionStreamsChunks() = runBlocking {
        val model = model()
        val fake = FakeEngine(5120, 8)
        var openedContext = ""
        val tj = Recognizer.open(model, Recognizer.Options(engine = { _, ctx, _ -> openedContext = ctx; fake }))
        assertEquals("c16_l64", openedContext)
        assertEquals(320, tj.chunkMs)
        val s = tj.startSession()
        try {
            tj.startSession()
            fail("a second session started")
        } catch (e: RecognizerException.SessionActive) {
        }
        val collected = async { s.chunks.toList() }
        s.append(FloatArray(5120 * 2 + 100))
        s.append(FloatArray(5000))
        val take = s.finish()
        val chunks = collected.await()
        // 2 chunks from the first push, none from the second (5100 samples pending), 1 from finish
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals(listOf(0f, 1f, 2f), chunks.map { it.heads[HeadId.CONF]!![0] })
        assertEquals(listOf(false, false, true), chunks.map { it.last })
        assertEquals(1, fake.streams)
        assertEquals(24, take.n40)
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
        val tj = Recognizer.open(model(), Recognizer.Options(engine = { _, _, _ -> fake }))
        val s = tj.startSession()
        s.append(FloatArray(5120 * 3))
        val heads = s.finish()
        assertEquals(32, heads.n40)
        assertEquals(64, heads.n20)
        val gem = heads[HeadId.GEM]!!
        assertEquals(64 * 2, gem.size)
        assertEquals(0f, gem[0])
        assertEquals(3f, gem.last())
        tj.close()
    }

    @Test
    fun headsShapesConcatAndGreedy() {
        try {
            HeadValues(2, mapOf(HeadId.GEM to FloatArray(3)))
            fail()
        } catch (e: IllegalArgumentException) {
        }
        val a = chunk(2, 0f, 31)
        val b = chunk(3, 1f, 31)
        val c = HeadValues.concat(listOf(a, b, chunk(1, 2f, 0), chunk(1, 2f, 34)))
        assertEquals(7, c.n40)
        assertArrayEquals(intArrayOf(31, 34), c.greedy())
        assertEquals(1f, c.row(HeadId.ATTR_GHUNNA, 4)[0])
        assertEquals(5, c.row(HeadId.ATTR_GHUNNA, 4).size)
        // a head missing from one chunk is missing from the take
        val partial = HeadValues(1, mapOf(HeadId.LOG_PROBS to FloatArray(38)))
        val d = HeadValues.concat(listOf(a, partial))
        assertTrue(d.has(HeadId.LOG_PROBS))
        assertFalse(d.has(HeadId.GEM))
        assertNull(d[HeadId.CONF])
        assertEquals(HeadId.BASE_HEADS.map { it.key }.toSet(),
            setOf("log_probs", "inter1_log_probs", "timing_log_probs", "gem", "attr_qalqalah"))
    }

}
