package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bounds of a take (NAMING.md "Bounds", [TakeLimits]): 20 minutes of audio, 256 waiting chunks, 64 waiting
 * events. Each test fails when its bound is removed. None can run away: the audio is a fixed number of pushes of a
 * small buffer into a fake engine, and every test has a time limit.
 */
class TakeLimitsTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** One chunk of 8 frames (320 ms) per 5120 input samples, and one more at the end; chunk i's best token is token(i). */
    private class FakeEngine(val token: (Int) -> Int = { 0 }) : TajweedEngine {
        /** Every sample the engine was given. */
        @Volatile var samples = 0L
        override val chunkMs: Int get() = 320

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0
            var made = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                this@FakeEngine.samples += length
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= CHUNK) {
                    pending -= CHUNK
                    out.add(heads(made++))
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(heads(made++))
            override fun reset() { pending = 0; made = 0 }
            override fun close() {}
        }

        fun heads(i: Int): HeadValues {
            val m = HashMap<HeadId, FloatArray>()
            for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) FRAMES else 2 * FRAMES) * h.width)
            val lp = m.getValue(HeadId.LOG_PROBS)
            val w = HeadId.LOG_PROBS.width
            val best = token(i)
            for (t in 0 until FRAMES) for (k in 0 until w) lp[t * w + k] = if (k == best) 0f else -10f
            return HeadValues(FRAMES, m)
        }

        override fun close() {}
    }

    private fun model(engine: TajweedEngine, maxTakeMs: Long = TakeLimits.MAX_TAKE_MS, dropped: AtomicInteger = AtomicInteger()): TajweedModel {
        val dir = Files.createTempDirectory("tj-limits-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        return TajweedModel.of(ModelFolder.open(dir, TestKit.verifier), ModelHost(verifier = TestKit.verifier,
            engine = { _, _, _ -> engine }, chunkDropped = { dropped.incrementAndGet() }, maxTakeMs = maxTakeMs))
    }

    @Test
    fun theNumbersAreTheSharedOnes() {
        assertEquals(20 * 60 * 1000L, TakeLimits.MAX_TAKE_MS)
        assertEquals(256, TakeLimits.CHUNK_BUFFER)
        assertEquals(64, TakeLimits.EVENT_BUFFER)
    }

    @Test
    fun aPushedTakeEndsAtTwentyMinutesOfAudio() = runBlocking {
        withTimeout(LIMIT_MS) {
            val engine = FakeEngine()
            val m = model(engine)
            val s = m.session(TajweedSessionOptions(sampleRate = 8000))
            val before = engine.samples   // the self test's clip went through the engine when it opened
            val events = async { s.events.toList() }
            val second = FloatArray(8000)
            var pushes = 0
            try {
                repeat(1205) { s.push(second); pushes++ }
                fail("a push after the limit was taken")
            } catch (e: TajweedError) {
                assertEquals("session_closed", e.code)
            }
            // 1200 pushes of one second each: the last one reaches the limit, the next is refused
            assertEquals(1200, pushes)
            assertEquals(1200L * 8000, engine.samples - before)
            val ended = events.await().filterIsInstance<TajweedEvent.Ended>()
            assertEquals(listOf(TajweedEvent.Ended.Reason.LIMIT), ended.map { it.reason })
            // the take's Heads are whole: 1875 chunks of audio and the engine's last one
            val heads = s.finish()
            assertEquals((1200 * 8000 / CHUNK + 1) * FRAMES, heads.decoded().frames.size)
            assertTrue(heads.decoded().isFinal)
            // the next take starts as usual
            m.session().cancel()
            m.close()
        }
    }

    @Test
    fun audioBeyondTheLimitIsNotTaken() = runBlocking {
        withTimeout(LIMIT_MS) {
            val engine = FakeEngine()
            val m = model(engine, maxTakeMs = 3200)
            val s = m.session()
            val before = engine.samples
            // one push that is longer than the take may be: the audio up to the limit is decoded, the rest dropped
            s.push(FloatArray(16_000 * 10))
            assertEquals(3200L * 16, engine.samples - before)
            val chunks = s.chunks.toList()
            assertEquals(11, chunks.size)
            assertTrue(chunks.last().decoded.isFinal)
            assertEquals(listOf(TajweedEvent.Ended.Reason.LIMIT), s.events.toList().filterIsInstance<TajweedEvent.Ended>().map { it.reason })
            assertEquals(11 * FRAMES, s.finish().decoded().frames.size)
            m.close()
        }
    }

    @Test
    fun aListenTakeEndsAtTheLimitNotAtTheEndOfItsSource() = runBlocking {
        withTimeout(LIMIT_MS) {
            val engine = FakeEngine()
            val m = model(engine, maxTakeMs = 3200)
            val before = engine.samples
            val s = m.listen(FloatArray(16_000 * 10), 16_000, TajweedSessionOptions(pace = TajweedSessionOptions.Pace.FAST))
            val chunks = s.chunks.toList()
            assertTrue(chunks.last().decoded.isFinal)
            assertEquals(3200L * 16, engine.samples - before)
            assertEquals(listOf(TajweedEvent.Ended.Reason.LIMIT), s.events.toList().filterIsInstance<TajweedEvent.Ended>().map { it.reason })
            assertEquals(11 * FRAMES, s.finish().decoded().frames.size)
            m.close()
        }
    }

    @Test
    fun chunksNobodyCollectsAreBoundedAndTheOldestGo() = runBlocking {
        withTimeout(LIMIT_MS) {
            val dropped = AtomicInteger()
            val m = model(FakeEngine(), dropped = dropped)
            val s = m.session()
            val piece = FloatArray(CHUNK)
            repeat(299) { s.push(piece) }
            // 299 chunks and the engine's last one, and nobody collected: only the newest 256 wait
            val heads = s.finish()
            val got = s.chunks.toList()
            assertEquals(TakeLimits.CHUNK_BUFFER, got.size)
            assertEquals((44..299).toList(), got.map { it.index })
            assertEquals(44, dropped.get())
            // nothing an app needs is lost: the last chunk is there with the whole take decoded, and so are the Heads
            assertTrue(got.last().decoded.isFinal)
            assertEquals(300 * FRAMES, got.last().decoded.frames.size)
            assertEquals(300 * FRAMES, heads.decoded().frames.size)
            m.close()
        }
    }

    @Test
    fun chunksAnAppCollectsAreNeverDropped() = runBlocking {
        withTimeout(LIMIT_MS) {
            val dropped = AtomicInteger()
            val m = model(FakeEngine(), dropped = dropped)
            val s = m.session()
            val got = async { s.chunks.toList() }
            val piece = FloatArray(CHUNK)
            repeat(299) { s.push(piece) }
            s.finish()
            assertEquals((0..299).toList(), got.await().map { it.index })
            assertEquals(0, dropped.get())
            // a collector that stops early leaves chunks behind: those are not counted as dropped
            val s2 = m.session()
            repeat(10) { s2.push(piece) }
            assertEquals(0, s2.chunks.first().index)
            s2.finish()
            assertEquals(0, dropped.get())
            m.close()
        }
    }

    @Test
    fun eventsNobodyCollectsAreBoundedAndEndedIsKept() = runBlocking {
        withTimeout(LIMIT_MS) {
            var blank = 0
            // a sound every 11 chunks (3.52 s): each one raises speechStarted and, in the silence after it, speechEnded
            val engine = FakeEngine { i -> if (i % 11 == 0) blank + 5 else blank }
            val m = model(engine, maxTakeMs = 440 * 320L)
            blank = m.tokens.blank
            val s = m.session()
            s.push(FloatArray(440 * CHUNK))
            val events = s.events.toList()
            // 40 sounds raised about 80 events; the newest 64 wait, and the take's end is the last of them
            assertEquals(TakeLimits.EVENT_BUFFER, events.size)
            assertEquals(TajweedEvent.Ended.Reason.LIMIT, (events.last() as TajweedEvent.Ended).reason)
            val first = events.first()
            assertNotEquals("the first event of the take is still waiting", 0L,
                (first as? TajweedEvent.SpeechStarted)?.atMs ?: (first as TajweedEvent.SpeechEnded).atMs)
            s.finish()
            m.close()
        }
    }

    private companion object {
        const val CHUNK = 5120
        const val FRAMES = 8
        /** No test here may take longer. */
        const val LIMIT_MS = 120_000L
    }
}
