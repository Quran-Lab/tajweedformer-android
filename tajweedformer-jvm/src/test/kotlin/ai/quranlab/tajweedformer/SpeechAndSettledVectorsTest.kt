package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The shared vectors `vectors30/speech.json` (the one definition of speech: a 40 ms frame is active when `log_probs`
 * or the timing head has a token on it; the two speech events, `endOnSilenceMs`, the 6.2 counters) and
 * `vectors30/settled.json` (settled and tentative tokens, also after a backend change). The speech cases run through
 * a real Session over an engine that gives the case's frames, so the chunks, the events and the take's Heads are what
 * an app sees; the settled cases run through the take's decoder.
 *
 * Three results of the fixture's take 20 have a test of their own, by name: its longest held vowel does not end the
 * take, real silence ends it 1500 ms after the last active frame, and a clip that ends on a held vowel ends once.
 */
class SpeechAndSettledVectorsTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun vector(name: String): Map<String, Any?> =
        Json.parse(javaClass.getResourceAsStream("/vectors30/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

    private fun ints(v: Any?): IntArray = (v as List<*>).map { (it as Double).toInt() }.toIntArray()

    private val model: ModelFolder by lazy {
        val dir = Files.createTempDirectory("tj-vectors-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        ModelFolder.open(dir, TestKit.verifier)
    }

    /** The heads of some frames: each CTC head peaks on its frame's id; a head without ids is all zeros (blank). */
    private fun heads(n40: Int, ids: Map<HeadId, IntArray>, all: Boolean = true): HeadValues {
        val m = HashMap<HeadId, FloatArray>()
        for (h in if (all) HeadId.entries else ids.keys.toList()) {
            val frames = if (h.frameMs == 40) n40 else 2 * n40
            val v = FloatArray(frames * h.width)
            ids[h]?.let { top -> for (t in 0 until frames) for (k in 0 until h.width) v[t * h.width + k] = if (k == top[t]) 0f else -10f }
            m[h] = v
        }
        return HeadValues(n40, m)
    }

    /** An engine that gives the case's frames: a chunk per [chunkFrames] frames of audio, the rest at the end. */
    private inner class Scripted(val frames: Map<HeadId, IntArray>, val chunkFrames: Int) : TajweedEngine {
        private val total = frames.getValue(HeadId.LOG_PROBS).size
        override val chunkMs: Int get() = 40 * chunkFrames

        private fun slice(from: Int, to: Int): HeadValues =
            heads(to - from, frames.mapValues { (h, ids) -> if (h.frameMs == 40) ids.copyOfRange(from, to) else ids.copyOfRange(2 * from, 2 * to) })

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0
            var at = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= 640 * chunkFrames && at + chunkFrames <= total) {
                    pending -= 640 * chunkFrames
                    out.add(slice(at, at + chunkFrames))
                    at += chunkFrames
                }
                return out
            }

            override fun finish(): List<HeadValues> = if (at < total) listOf(slice(at, total)).also { at = total } else emptyList()
            override fun reset() { pending = 0; at = 0 }
            override fun close() {}
        }

        override fun close() {}
    }

    private fun asMap(e: TajweedEvent): Map<String, Any?> = when (e) {
        is TajweedEvent.SpeechStarted -> mapOf("event" to "speechStarted", "atMs" to e.atMs.toDouble())
        is TajweedEvent.SpeechEnded -> mapOf("event" to "speechEnded", "atMs" to e.atMs.toDouble(), "silenceMs" to e.silenceMs.toDouble())
        is TajweedEvent.Ended -> mapOf("event" to "ended", "reason" to e.reason.name.lowercase())
        else -> mapOf("event" to e.javaClass.simpleName)
    }

    @Suppress("UNCHECKED_CAST")
    private val speechCases: List<Map<String, Any?>> by lazy { vector("speech.json")["cases"] as List<Map<String, Any?>> }

    /** The one case whose name starts with [name]. */
    private fun case(name: String): Map<String, Any?> = speechCases.single { (it["name"] as String).startsWith(name) }

    /**
     * One case of speech.json, three ways: the frames as a whole take (which are speech, the totals), the events with
     * the chunk that carries their frame, and the same through a Session (the chunks, the events and the Heads an
     * app gets, the 6.2 counters). Returns the events the Session raised and the frames of the take.
     */
    @Suppress("UNCHECKED_CAST")
    private fun replay(c: Map<String, Any?>): Pair<List<Map<String, Any?>>, Int> = runBlocking {
        withTimeout(60_000) {
            val name = c["name"] as String
            val want = c["expect"] as Map<String, Any?>
            val ids = LinkedHashMap<HeadId, IntArray>()
            ids[HeadId.LOG_PROBS] = ints(c["frames"])
            ids[HeadId.TIMING_LOG_PROBS] = ints(c["timing"])
            (c["other_heads"] as? Map<String, Any?>)?.forEach { (k, x) -> ids[HeadId.fromKey(k)!!] = ints(x) }
            val chunkFrames = (c["chunk_frames"] as Double).toInt()
            val used = (want["frames_used"] as Double).toInt()
            val endOnSilence = (c["endOnSilenceMs"] as? Double)?.toInt()
            val n = ids.getValue(HeadId.LOG_PROBS).size
            assertEquals("$name: two timing frames a frame", 2 * n, ids.getValue(HeadId.TIMING_LOG_PROBS).size)

            // the frames as a whole take: which are active, the totals
            val whole = heads(used, ids.mapValues { (h, x) -> x.copyOf(if (h.frameMs == 40) used else 2 * used) }, all = false)
            val flags = SpeechFrames.active(whole, ModelFolder.BLANK)
            assertEquals("$name speech", ints(want["speech"]).toList(), flags.map { if (it) 1 else 0 })
            assertEquals("$name speech_frames", (want["speech_frames"] as Double).toInt(), flags.count { it })
            assertEquals("$name silence_frames", (want["silence_frames"] as Double).toInt(), flags.count { !it })
            assertEquals("$name speech_s", want["speech_s"] as Double, flags.count { it } * 40 / 1000.0, 1e-9)
            assertEquals("$name silence_s", want["silence_s"] as Double, flags.count { !it } * 40 / 1000.0, 1e-9)

            // the events with the chunk that carries their frame
            val tracker = SpeechTracker(endOnSilence?.takeIf { it > 0 } ?: TajweedSession.DEFAULT_SILENCE_MS)
            val raised = ArrayList<Map<String, Any?>>()
            var chunk = 0
            var at = 0
            while (at < used) {
                val to = minOf(at + chunkFrames, used)
                val events = ArrayList<TajweedEvent>()
                tracker.add(flags.copyOfRange(at, to), events)
                for (e in events) raised.add(asMap(e) + ("chunk" to chunk.toDouble()))
                at = to
                chunk++
            }
            val wanted = want["events"] as List<Map<String, Any?>>
            assertEquals("$name events by chunk", wanted.filter { it["event"] != "ended" }, raised)

            // the same through a Session: the chunks, the events and the take's Heads an app gets. The app selects
            // only what the case counts tokens for: the timing head is computed for the speech rule all the same.
            val counted = (want["tokens"] as Map<String, Any?>).keys.toList()
            val tj = TajweedModel.of(model, ModelHost(verifier = TestKit.verifier, engine = { _, _, _ -> Scripted(ids, chunkFrames) }))
            val session = tj.session(TajweedSessionOptions(endOnSilenceMs = endOnSilence, heads = counted))
            val chunks = async { session.chunks.toList() }
            val events = async { session.events.toList() }
            val piece = FloatArray(640 * chunkFrames)
            try {
                repeat(n / chunkFrames) { session.push(piece) }
            } catch (e: TajweedError) {
                // the take ended on silence under the pushes: what comes after is not part of it
                assertEquals("$name: a push failed", "session_closed", e.code)
                assertTrue("$name ended by itself", wanted.any { it["event"] == "ended" })
            }
            val take = session.finish()
            val delivered = chunks.await().filter { it.endMs > it.startMs }
            assertEquals("$name frames_used", used, take.decoded().frames.size)
            assertEquals("$name chunk_speech", want["chunk_speech"], delivered.map { it.speech })
            val got = events.await().map { asMap(it) }
            assertEquals("$name events", wanted.map { it - "chunk" }, got)
            assertTrue("$name: the last chunk is final", chunks.await().last().decoded.isFinal)
            assertEquals("$name: the last chunk has the whole take", used, chunks.await().last().decoded.frames.size)
            // the app gets the heads it selected, in the take's Heads and in every chunk, and no other (a take without
            // a frame has no head at all)
            if (used > 0) assertEquals("$name: the heads handed to the app", counted.toSet(), take.names.toSet())
            for (d in delivered) assertEquals("$name: the heads of a chunk", counted.toSet(), d.heads.names.toSet())
            // the 6.2 counters of the take, from everything the engine computed for it
            val counts = TakeCounts.of(whole, counted.map { HeadId.fromKey(it)!! }.toSet())
            assertEquals("$name counted speech frames", (want["speech_frames"] as Double).toInt(), counts.speechFrames)
            assertEquals("$name counted silence frames", (want["silence_frames"] as Double).toInt(), counts.silenceFrames)
            assertEquals("$name tokens", (want["tokens"] as Map<String, Any?>).mapValues { (it.value as Double).toInt() }, counts.tokens)
            tj.close()
            got to used
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun speechAndSilence() {
        val v = vector("speech.json")
        assertEquals(40, (v["frame_ms"] as Double).toInt())
        assertEquals(20, (v["timing_frame_ms"] as Double).toInt())
        assertEquals((v["default_window_ms"] as Double).toInt(), TajweedSession.DEFAULT_SILENCE_MS)
        assertEquals(32, speechCases.size)
        for (c in speechCases) replay(c)

        // the option: off, the valid range, and what is refused
        val option = v["endOnSilenceMs"] as Map<String, Any?>
        for (ok in (option["off"] as List<Any?>) + (option["valid"] as List<Any?>)) TajweedSessionOptions(endOnSilenceMs = (ok as? Double)?.toInt()).validate()
        for (bad in (option["invalid_argument"] as List<Any?>).filterIsInstance<Double>().filter { it == Math.rint(it) }) {
            try {
                TajweedSessionOptions(endOnSilenceMs = bad.toInt()).validate()
                fail("endOnSilenceMs $bad was accepted")
            } catch (e: TajweedError) {
                assertEquals("invalid_argument", e.code)
            }
        }
    }

    /** Fixture take 20: `log_probs` is blank for 39 frames (1.56 s) from frame 57 while a vowel is held; the timing
     *  head stays on it, so with the default 1500 ms the take goes on: one `speechStarted`, no `speechEnded`. */
    @Test
    fun theLongestHeldVowelOfTheFixtureDoesNotEndTheTake() {
        val c = case("fixture take 20: the longest held vowel of the fixture does not end the take")
        val frames = ints(c["frames"])
        assertEquals(128, frames.size)
        assertTrue("log_probs is blank for 39 frames from frame 57", (57 until 96).all { frames[it] == 0 } && frames[56] != 0 && frames[96] != 0)
        val (events, used) = replay(c)
        assertEquals(128, used)
        assertEquals(listOf(mapOf("event" to "speechStarted", "atMs" to 280.0)), events)
    }

    /** The same take followed by silence, `endOnSilenceMs` 1500: `speechEnded` at 4000 ms with 1520 ms of silence,
     *  raised with chunk 17 of 8-frame chunks (the window after the last active frame, within one chunk), then
     *  `ended(silence)`; the take is 144 frames. */
    @Test
    fun realSilenceEndsTheTake1500MsAfterItsLastActiveFrame() {
        val c = case("fixture take 20 followed by silence")
        val (events, used) = replay(c)
        assertEquals(144, used)
        assertEquals(listOf(
            mapOf("event" to "speechStarted", "atMs" to 280.0),
            mapOf("event" to "speechEnded", "atMs" to 4000.0, "silenceMs" to 1520.0),
            mapOf("event" to "ended", "reason" to "silence"),
        ), events)
        @Suppress("UNCHECKED_CAST")
        val ended = ((c["expect"] as Map<String, Any?>)["events"] as List<Map<String, Any?>>).single { it["event"] == "speechEnded" }
        assertEquals("with chunk 17", 17.0, ended["chunk"])
        // frame L + W: the last active frame is frame 99 (4000 ms is its end), W is 38 frames, frame 137 is in chunk 17
        assertEquals(17, (4000 / 40 - 1 + 38) / 8)
    }

    /** The take cut at the end of its held vowel, then silence: it ends once (one start, one end at 3560 ms, one
     *  `ended`), not inside the vowel and not a second time. */
    @Test
    fun aClipThatEndsOnAHeldVowelFollowedBySilenceEndsOnce() {
        val (events, _) = replay(case("fixture take 20 cut at the end of its held vowel, then silence"))
        assertEquals(listOf(
            mapOf("event" to "speechStarted", "atMs" to 280.0),
            mapOf("event" to "speechEnded", "atMs" to 3560.0, "silenceMs" to 1520.0),
            mapOf("event" to "ended", "reason" to "silence"),
        ), events)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun settledTokens() {
        val v = vector("settled.json")
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(11, cases.size)
        val vocabulary = model.vocabulary
        fun chunk(ids: IntArray) = heads(ids.size, mapOf(HeadId.LOG_PROBS to ids), all = false)
        for (c in cases) {
            val decoder = TakeDecoder(vocabulary, 0)
            /** The first frame of each chunk in force. */
            val starts = ArrayList<Int>()
            var frames = 0
            var settledBefore = 0
            for ((i, step) in (c["steps"] as List<Map<String, Any?>>).withIndex()) {
                val name = "${c["name"]}, step $i"
                val replaced = step["backendChanged"] as? Map<String, Any?>
                if (replaced == null) {
                    val ids = ints(step["chunk"])
                    assertTrue(!decoder.add(chunk(ids), starts.size, frames))
                    starts.add(frames)
                    frames += ids.size
                } else {
                    val from = (replaced["fromChunk"] as Double).toInt()
                    frames = starts[from]
                    while (starts.size > from) starts.removeAt(starts.size - 1)
                    for ((k, x) in (replaced["chunks"] as List<Any?>).withIndex()) {
                        val ids = ints(x)
                        assertEquals("$name: the first replaced chunk rewinds the take", k == 0, decoder.add(chunk(ids), starts.size, frames))
                        starts.add(frames)
                        frames += ids.size
                    }
                }
                val got = decoder.snapshot(final = step["final"] == true)
                val want = step["expect"] as Map<String, Any?>
                assertEquals("$name frames", (want["frames"] as Double).toInt(), got.frames.size)
                assertEquals("$name tokens", (want["tokens"] as List<Map<String, Any?>>).map { listOf(it["id"], it["firstMs"], it["lastMs"]) },
                    got.tokens.map { listOf(it.id.toDouble(), it.firstMs.toDouble(), it.lastMs.toDouble()) })
                assertEquals("$name settled", (want["settled"] as Double).toInt(), got.settled)
                assertEquals("$name isFinal", want["isFinal"], got.isFinal)
                // settled only goes down when a backend change replaced frames
                if (replaced == null) assertTrue("$name: settled went down", got.settled >= settledBefore)
                settledBefore = got.settled
            }
        }
    }
}
