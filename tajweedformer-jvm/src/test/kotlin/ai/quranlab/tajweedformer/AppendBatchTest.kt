package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Batched appends (RecognizerSession.append keeps audio on the caller's side until it can complete a
 * chunk): against the unbatched path on the real engine, for odd piece sizes, the same chunks with the
 * same heads and tokens, delivered during the same append (no added latency), with far fewer hops; a
 * take ending in a partial chunk; a cancel with audio still buffered.
 */
class AppendBatchTest : OpenChecked() {
    private val conf = V4ConformanceTest()

    /** What a take delivered: per chunk its index, first frame, every head, and the append it came in. */
    private class Take(val chunks: List<EngineChunk>, val during: List<Int>, val all: HeadValues, val hops: Int)

    private fun run(tj: Recognizer, samples: FloatArray, piece: Int, batched: Boolean): Take = runBlocking {
        tj.batchAppends = batched
        var appends = 0
        val during = java.util.Collections.synchronizedList(ArrayList<Int>())
        val watch = object : SdkObserver {
            override fun onChunk(session: SessionInfo, chunk: ChunkStats, decoded: Decoded) { during.add(appends) }
        }
        SdkEvents.add(watch)
        try {
            val s = tj.startSession()
            val chunks = async { s.chunks.toList() }
            var at = 0
            while (at < samples.size) {
                val k = minOf(piece, samples.size - at)
                appends++
                s.append(samples, at, k)
                at += k
            }
            appends++ // the finish
            val all = s.finish()
            Take(chunks.await(), ArrayList(during), all, s.hops)
        } finally {
            SdkEvents.remove(watch)
        }
    }

    private fun assertSame(what: String, a: Take, b: Take) {
        assertEquals("$what: chunks", a.chunks.size, b.chunks.size)
        for ((x, y) in a.chunks.zip(b.chunks)) {
            assertEquals("$what: index", x.index, y.index)
            assertEquals("$what: frame", x.firstFrame, y.firstFrame)
            assertEquals("$what: n40", x.heads.n40, y.heads.n40)
            for (h in HeadId.entries) {
                val u = x.heads[h]
                val v = y.heads[h]
                assertEquals("$what: head $h present", u != null, v != null)
                if (u != null) assertArrayEquals("$what: chunk ${x.index} head $h", u, v!!, 0f)
            }
        }
        assertArrayEquals("$what: tokens", a.all.greedy(), b.all.greedy())
        assertArrayEquals("$what: the take's log_probs", a.all[HeadId.LOG_PROBS]!!, b.all[HeadId.LOG_PROBS]!!, 0f)
        // no added latency: every chunk is delivered during the same append (or the finish) as unbatched
        assertEquals("$what: the append each chunk came in", a.during, b.during)
    }

    private fun recognizer(): Pair<Recognizer, FloatArray>? {
        val audio = conf.env("ZF_V4_AUDIO_DIR")?.let(::File)
        if (audio == null || !File(audio, "Saood_001001.pcm").isFile) return null
        val model = conf.model() ?: return null
        val tj = Recognizer.open(model, Recognizer.Options(context = "c16_l64", threads = 4, allHeads = true))
        return tj to conf.readPcm(File(audio, "Saood_001001.pcm"))
    }

    @Test
    fun batchedAppendsGiveTheSameChunksAtTheSameAppends() {
        val r = recognizer()
        Assume.assumeTrue("needs the JNI library, the staged folder and ZF_V4_AUDIO_DIR", r != null)
        val (tj, clip) = r!!
        tj.use {
            // the first take learns where the first chunk runs (unbatched until then)
            run(tj, clip, 320, batched = true)
            assertTrue("the first chunk's need was learned", tj.firstChunkNeed.isNotEmpty())
            // the takes reuse the first take's engine stream (reset, not created again)
            val created = ai.quranlab.tajweedformer.engine.ExactTajweedEngine.streamsCreated.get()
            val advance = tj.chunkMs * 16
            for (piece in intArrayOf(1, 7, 320, 1234, 16_000)) {
                val plain = run(tj, clip, piece, batched = false)
                val batched = run(tj, clip, piece, batched = true)
                assertSame("piece $piece", plain, batched)
                println("piece $piece: ${plain.chunks.size} chunks; hops unbatched ${plain.hops}, batched ${batched.hops}")
                if (piece < advance) assertTrue("piece $piece: ${batched.hops} hops for ${plain.chunks.size} chunks",
                    batched.hops <= 2 * plain.chunks.size + 2)
            }
            assertEquals("engine streams created by 10 more takes", created, ai.quranlab.tajweedformer.engine.ExactTajweedEngine.streamsCreated.get())
            // a take that ends in a partial chunk: 2.5 chunks' advance after the first chunk's need
            val need = tj.firstChunkNeed.values.first()
            val partial = clip.copyOf(minOf(clip.size, (need + 2.5 * advance).toInt() + 1001))
            for (piece in intArrayOf(7, 320)) {
                assertSame("partial, piece $piece", run(tj, partial, piece, batched = false), run(tj, partial, piece, batched = true))
            }
        }
    }

    @Test
    fun batchedAppendsAt48kHzThroughTheResampler() {
        val r = recognizer()
        Assume.assumeTrue("needs the JNI library, the staged folder and ZF_V4_AUDIO_DIR", r != null)
        val (tj16, clip16) = r!!
        tj16.close()
        // any 48 kHz signal will do for the comparison: the clip, linearly upsampled
        val clip = FloatArray(clip16.size * 3) { i ->
            val x = i / 3.0
            val a = x.toInt()
            val b = minOf(a + 1, clip16.size - 1)
            (clip16[a] + (clip16[b] - clip16[a]) * (x - a)).toFloat()
        }
        Recognizer.open(conf.model()!!, Recognizer.Options(context = "c16_l64", threads = 4, allHeads = true, sampleRate = 48_000)).use { tj ->
            run(tj, clip, 960, batched = true)
            for (piece in intArrayOf(1, 7, 960, 4410)) {
                val plain = run(tj, clip, piece, batched = false)
                val batched = run(tj, clip, piece, batched = true)
                assertSame("48 kHz, piece $piece", plain, batched)
                println("48 kHz piece $piece: ${plain.chunks.size} chunks; hops unbatched ${plain.hops}, batched ${batched.hops}")
            }
        }
    }

    @Test
    fun cancelWithAudioBufferedLeavesTheNextTakeExact() {
        val r = recognizer()
        Assume.assumeTrue("needs the JNI library, the staged folder and ZF_V4_AUDIO_DIR", r != null)
        val (tj, clip) = r!!
        tj.use {
            val reference = run(tj, clip, 320, batched = false)
            runBlocking {
                tj.batchAppends = true
                val s = tj.startSession()
                val chunks = async { runCatching { s.chunks.toList() } }
                // one chunk and three pieces more: those pieces wait in the buffer
                val need = tj.firstChunkNeed.values.first().toInt()
                var at = 0
                while (at < need + 3 * 320) {
                    s.append(clip, at, 320)
                    at += 320
                }
                val hops = s.hops
                s.cancel()
                assertTrue("the last pieces were buffered, not run (hops $hops)", hops < at / 320)
                val got = chunks.await()
                assertTrue("chunks fails with Cancelled: $got", got.exceptionOrNull() is RecognizerException.Cancelled)
                try {
                    s.append(clip, 0, 320)
                    fail("append after cancel")
                } catch (_: RecognizerException.SessionClosed) {
                }
            }
            // the kept engine stream is reset for the next take: the same bits as before
            assertSame("after a cancel", reference, run(tj, clip, 320, batched = true))
        }
    }
}
