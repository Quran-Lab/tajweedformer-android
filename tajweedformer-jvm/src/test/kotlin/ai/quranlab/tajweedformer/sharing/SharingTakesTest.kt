package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 2.0.3, takes in a row share one recording, through the whole client: the observer hooks the recognizer calls,
 * the real capture (libopus), the outbox and [StubQlab] (qlab-api and R2 on 127.0.0.1, with the take list route as
 * the server checks it). The clients' clock moves only when a test moves it, and the join window's timer never runs
 * (a test asks for the window's check after moving the clock), so nothing here waits for wall time.
 */
class SharingTakesTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var storage: File
    private val folders = ArrayList<File>()
    private var now = 0L
    private var seed = 500L

    /** As SharingTest: without the host Opus library every test here is SKIPPED before anything is set up. */
    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        storage = temp()
        now = System.currentTimeMillis()
    }

    @After fun down() {
        folders.forEach { it.deleteRecursively() }
        if (!::stub.isInitialized) return
        try {
            assertTrue(stub.seen.none { it.headers.containsKey("origin") })
        } finally {
            stub.close()
        }
    }

    private fun temp(): File = Files.createTempDirectory("tj-takes").toFile().also(folders::add)

    /** A configured client with the signed config fetched; [consent]: the learner said yes. */
    private fun cloud(consent: Boolean = true, at: File = storage, debug: Boolean = false, log: (String) -> Unit = {}): QuranLabCloud {
        val c = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(at, clock = { now }, debugBuild = debug, log = log))
        // the window's timer never runs: the test moves the clock and asks for the check
        c.sharing.recorder.timer = JoinTimer { _, _ -> {} }
        c.refreshConfig()
        if (consent && !c.sharing.isOn) c.sharing.accept()
        return c
    }

    private fun start(c: QuranLabCloud, rate: Int = 16_000, revision: String = "v4.0+abc") =
        c.onSessionStart(TajweedObserver.SessionStart(revision, "c16_l64", "cpu", sampleRate = rate, inputRoute = "built_in",
            decodeBackend = "mock"))

    /** Audio into the open take in 100 ms pieces; the clock moves on by its length. */
    private fun push(c: QuranLabCloud, x: FloatArray, rate: Int = 16_000) {
        var at = 0
        while (at < x.size) {
            val k = minOf(rate / 10, x.size - at)
            c.onAudio(x, at, k, rate)
            at += k
        }
        now += x.size * 1000L / rate
    }

    private fun end(c: QuranLabCloud, completed: Boolean = true) =
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, completed, feedbackShown = true))

    /** One take: started now, its audio, ended when the audio is through. */
    private fun take(c: QuranLabCloud, x: FloatArray, rate: Int = 16_000, completed: Boolean = true) {
        start(c, rate)
        push(c, x, rate)
        end(c, completed)
    }

    private fun noise(seconds: Double, rate: Int = 16_000) = OggTestKit.noise(Math.round(seconds * rate).toInt(), seed = seed++)

    /** No take follows: the window passes, its check closes the recording, the encoder ends. */
    private fun windowPasses(c: QuranLabCloud, window: Int = TakeJoin.DEFAULT_WINDOW_MS) {
        now += window + 1
        c.sharing.checkJoinWindow()
        c.sharing.lastCapture?.await()
    }

    /** The id of the recording that is open. */
    private fun open(c: QuranLabCloud): String = (c.sharing.recorder.current as ShareCapture).id

    /** Uploads until the recording is up and its take list, if any, is sent or dropped. */
    private fun settle(c: QuranLabCloud, id: String): Contribution {
        // a recording closed by the next take's start: its encoder thread may still be writing the last pages
        val until = System.nanoTime() + 60_000_000_000L
        while (id in ShareCapture.ACTIVE && System.nanoTime() < until) Thread.sleep(5)
        assertFalse("the recording $id is still open", id in ShareCapture.ACTIVE)
        repeat(20) {
            c.sharing.outbox.drain(ignoringBackoff = true)
            val item = c.sharing.outbox.item(id)
            if (item?.status == Contribution.Status.UPLOADED && c.sharing.outbox.takesState(id) != ContributionOutbox.TAKES_PENDING) return item
            Thread.sleep(20)
        }
        throw AssertionError("the outbox did not settle: ${c.sharing.outbox.item(id)}")
    }

    private fun folder(id: String, at: File = storage) = File(File(at, "outbox"), id)

    /** The self-test clip (16 kHz, 1.72 s of recitation). */
    private fun clip(): FloatArray {
        val raw = SharingTakesTest::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/selftest_16k.f32")!!.use { it.readBytes() }
        val fb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** [n] samples at [rate] in 48 kHz samples, as the writer's resampler counts them. */
    private fun at48(n: Long, rate: Int): Long = if (rate == 48_000) n else (n * 48_000 + rate - 1) / rate

    private fun has(p: OggTestKit.Page, tag: String) = p.packets.any { it.size >= 8 && String(it, 0, 8, Charsets.US_ASCII) == tag }

    /**
     * Every check of the bitrate stream test on one whole stream: pages in sequence from 0 (their CRCs are checked as
     * they are read), one serial, BOS only on page 0, OpusHead only there and OpusTags only on page 1, granules
     * 960 x packets, EOS only on the last page ([ended]) with the encoder's final position, and a libopus decode of
     * exactly [input48] samples.
     */
    private fun checkStream(whole: ByteArray, rate: Int, input48: Long, ended: Boolean = true): List<OggTestKit.Page> {
        val ps = OggTestKit.pages(whole)
        assertEquals("page sequence from 0", ps.indices.toList(), ps.map { it.sequence })
        assertEquals("one serial", 1, ps.map { it.serial }.toSet().size)
        assertEquals("BOS only on page 0", listOf(0), ps.indices.filter { ps[it].flags and 0x02 != 0 })
        assertEquals("OpusHead only on page 0", listOf(0), ps.indices.filter { has(ps[it], "OpusHead") })
        assertEquals("OpusTags only on page 1", listOf(1), ps.indices.filter { has(ps[it], "OpusTags") })
        val head = ps[0].packets[0]
        assertEquals("OpusHead: the input rate", rate, java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("EOS only on the last page", if (ended) listOf(ps.size - 1) else emptyList(), ps.indices.filter { ps[it].flags and 0x04 != 0 })
        var packets = 0L
        for (p in ps.drop(2)) {
            packets += p.packets.size
            if (p.flags and 0x04 == 0) assertEquals("granule = 960 x packets", packets * 960, p.granule)
        }
        if (ended) {
            assertEquals("the last granule is the encoder's final position", 312 + input48, ps.last().granule)
            assertEquals("libopus decodes exactly the input", input48, OggTestKit.decode(whole).size.toLong())
        }
        return ps
    }

    /** ffprobe and ffmpeg (its native and libopus decoders) on the stream, where they are on PATH. */
    private fun checkWithFfmpeg(label: String, whole: ByteArray, input48: Long) {
        val f = OggTestKit.temp()
        try {
            f.writeBytes(whole)
            val probe = OggTestKit.run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path)
            if (probe == null) {
                println("$label: ffprobe/ffmpeg not on PATH, their checks skipped")
                return
            }
            assertEquals("ffprobe exit", 0, probe.first)
            val duration = String(probe.second, Charsets.US_ASCII).trim().toDouble()
            assertEquals("ffprobe duration within 20 ms", input48 / 48_000.0, duration, 0.020)
            val counts = ArrayList<Int>()
            for (dec in listOf("opus", "libopus")) {
                val r = OggTestKit.run("ffmpeg", "-v", "error", "-c:a", dec, "-i", f.path, "-f", "f32le", "-ac", "1", "-")!!
                assertEquals("ffmpeg $dec exit", 0, r.first)
                assertEquals("ffmpeg $dec decodes exactly the input", input48, r.second.size / 4L)
                counts.add(r.second.size / 4)
            }
            println("%s: ffprobe duration %.6f s, ffmpeg opus %d samples, libopus %d samples".format(java.util.Locale.ROOT, label, duration, counts[0], counts[1]))
        } finally {
            f.delete()
        }
    }

    /** ONE continuous encode of [x] in one piece: the same serial, segment plan and per-segment bitrates, no takes. */
    private fun continuous(x: FloatArray, rate: Int, serial: Int, bitrates: List<Int>): List<ByteArray> {
        val dir = OggTestKit.tempDir()
        try {
            val closed = ArrayList<OggSegment>()
            val w = OggOpusWriter({ File(dir, ShareFiles.name(it) + ".opus") }, SegmentPlan.SECONDS, rate, serial = serial,
                bitrateFor = { n -> bitrates[n - 1] }) { closed.add(it) }
            w.append(x)
            w.finish()
            return closed.map { it.file.readBytes() }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun join(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun takeMaps(vararg t: Pair<Int, Int>) = t.map { mapOf("start_ms" to it.first.toDouble(), "gap_ms" to it.second.toDouble()) }

    /**
     * The self-test clip cut into three takes, 1.2 s and 0.8 s apart, at 16, 48 and 44.1 kHz input: ONE recording
     * (one session JSON, one upload id), a valid stream by every check of the bitrate stream test, a decode of exactly
     * the takes' samples at 48 kHz, and bytes and decoded PCM identical to one continuous encode of the same audio.
     * The cuts fall inside a 20 ms Opus frame and inside the resampler's reach, so the frame one take leaves is
     * completed by the next take's first samples. The take list follows the last segment with the exact body.
     */
    @Test fun threeTakesInARowAreOneRecordingByteForByteTheContinuousEncode() {
        val clip = clip()
        val cuts = listOf(0, 9_001, 18_517, clip.size)
        val c = cloud()
        for (rate in listOf(16_000, 48_000, 44_100)) {
            val starts = stub.starts().size
            for (i in 0 until 3) {
                if (i == 1) now += 1_200
                if (i == 2) now += 800
                take(c, clip.copyOfRange(cuts[i], cuts[i + 1]), rate)
                assertNotNull("the recording waits for the next take", c.sharing.recorder.current)
            }
            val id = open(c)
            assertTrue("nothing is closed yet: no item, nothing sent", c.sharing.items.none { it.id == id } && stub.starts().size == starts)
            windowPasses(c)
            assertNull(c.sharing.recorder.current)
            val item = settle(c, id)
            assertEquals(3, item.takes)
            assertEquals(1, item.segments)
            assertEquals(true, item.completed)
            assertEquals(Math.round(clip.size * 1000.0 / rate).toInt(), item.durationMs)
            val session = stub.starts().drop(starts).single()
            assertEquals("the session JSON once, with the first take", id, session.headers["x-qlab-id"])
            @Suppress("UNCHECKED_CAST")
            assertEquals(rate.toDouble(), (session.json!!["audio"] as Map<String, Any?>)["input_sample_rate"])

            val segs = stub.segments(id)
            val whole = join(segs)
            // the takes' samples at 48 kHz (at 44.1 kHz the takes are one stream to the resampler: one rounding, at its end)
            val input48 = at48(clip.size.toLong(), rate)
            if (rate != 44_100) assertEquals(input48, (0 until 3).sumOf { at48((cuts[it + 1] - cuts[it]).toLong(), rate) })
            val ps = checkStream(whole, rate, input48)
            val ref = continuous(clip, rate, ps[0].serial, item.segmentBitrates)
            assertEquals(segs.size, ref.size)
            for (i in segs.indices) assertArrayEquals("segment ${i + 1}: byte for byte the continuous encode", ref[i], segs[i])
            assertArrayEquals("the decoded PCM is the continuous encode's", OggTestKit.decode(join(ref)), OggTestKit.decode(whole), 0f)

            val want = listOf(Take(0, 0), Take(9_001 * 1000L / rate, 1_200), Take(18_517 * 1000L / rate, 800))
            assertEquals(want, ShareFiles.takes(folder(id)))
            val call = stub.takeCalls(id).single()
            assertEquals(ShareFiles.takesJson(want), String(call.body, Charsets.UTF_8))
            if (rate == 16_000) {
                assertEquals("{\"takes\":[{\"start_ms\":0,\"gap_ms\":0},{\"start_ms\":562,\"gap_ms\":1200},{\"start_ms\":1157,\"gap_ms\":800}]}", String(call.body, Charsets.UTF_8))
            }
            assertEquals("application/json", call.headers["content-type"])
            assertTrue(call.headers["authorization"]!!.startsWith("Bearer qlt_v1."))
            assertEquals(c.sharing.contributor.id, call.headers["x-qlab-contributor"])
            assertTrue("after the last segment", stub.seen.indexOf(call) > stub.seen.indexOf(stub.puts.last { it.path.contains(id) }))
            assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(id))
            println("three takes at %d Hz: %d pages, %d bytes, %d samples decoded, takes %s, identical to the continuous encode".format(java.util.Locale.ROOT,
                rate, ps.size, whole.size, input48, String(call.body, Charsets.UTF_8)))
            checkWithFfmpeg("three takes at $rate Hz", whole, input48)
        }
        assertEquals("three recordings in all, each sent once", 3, stub.starts().size)
    }

    /**
     * A recording of three takes over 75 s (the clip repeated, 16 kHz): segments are cut by the same plan counted across
     * the takes (10, 20, 40 s, then the rest), one boundary exactly at a take boundary and the others inside a take,
     * and the bitrate ladder steps between them (192, 96, 32, 64 kbit/s). The stream is byte for byte one continuous
     * encode with those bitrates; every segment's marker has its bitrate.
     */
    @Test fun aRecordingOfTakesIsCutBySegmentsAndBitratesLikeOneStream() {
        val clip = clip()
        val rate = 16_000
        val input = FloatArray(rate * 75) { clip[it % clip.size] }
        val cuts = listOf(0, 10 * rate, 41 * rate + 11_200, input.size) // 10 s, 31.7 s, 33.3 s
        val c = cloud()
        val ladder = c.sharing.outbox.ladder
        fun part(from: Int, to: Int) = input.copyOfRange(from, to)

        take(c, part(cuts[0], cuts[1]))
        val id = open(c)
        c.sharing.syncCapture()
        assertTrue("10 s of takes: the first segment closes with the next take's first packet", ShareFiles.markers(folder(id)).isEmpty())
        ladder.uploadFailed() // segment 2 opens at 96 kbit/s, at the take boundary
        now += 1_200
        start(c)
        push(c, part(cuts[1], cuts[1] + 15 * rate))
        c.sharing.syncCapture()
        assertEquals(1, ShareFiles.markers(folder(id)).size)
        repeat(2) { ladder.uploadFailed() } // segment 3 opens at 32 kbit/s, 20 s into the second take
        push(c, part(cuts[1] + 15 * rate, cuts[2]))
        end(c)
        now += 800
        start(c)
        push(c, part(cuts[2], cuts[2] + 20 * rate))
        c.sharing.syncCapture()
        repeat(3) { ladder.uploadOk(0.0) } // segment 4 opens at 64 kbit/s, 28.3 s into the third take
        push(c, part(cuts[2] + 20 * rate, cuts[3]))
        end(c)
        windowPasses(c)
        val item = settle(c, id)

        val want = listOf(192_000, 96_000, 32_000, 64_000)
        assertEquals(want, item.segmentBitrates)
        assertEquals(want, ShareFiles.markers(folder(id)).map { it.bitrate })
        assertEquals(4, item.segments)
        assertEquals(3, item.takes)
        assertEquals(75_000, item.durationMs)
        assertEquals("one session start for the three takes", 1, stub.starts().size)
        assertEquals((1..4).map { "/r2/qlab-contributions/v1/$id/seg-0000$it.opus" }, stub.puts.map { it.path })
        assertEquals(takeMaps(0 to 0, 10_000 to 1_200, 41_700 to 800), stub.takes[id])
        assertEquals(1, stub.takeCalls(id).size)

        val segs = stub.segments(id)
        val whole = join(segs)
        val input48 = 75L * 48_000
        val ps = checkStream(whole, rate, input48)
        val audio = segs.map { s -> OggTestKit.pages(s).filter { !has(it, "OpusHead") && !has(it, "OpusTags") } }
        assertEquals("10, 20 and 40 s of packets, then the rest", listOf(500, 1_000, 2_000, 251), audio.map { s -> s.sumOf { it.packets.size } })
        val ref = continuous(input, rate, ps[0].serial, want)
        assertEquals(4, ref.size)
        for (i in segs.indices) assertArrayEquals("segment ${i + 1}: byte for byte the continuous encode", ref[i], segs[i])
        assertArrayEquals("the decoded PCM is the continuous encode's", OggTestKit.decode(join(ref)), OggTestKit.decode(whole), 0f)
        val kbps = audio.map { s -> s.sumOf { pg -> pg.packets.sumOf { it.size } } * 8.0 / (s.sumOf { it.packets.size } * 0.020) / 1000 }
        assertTrue("32k < 64k < 96k < 192k: $kbps", kbps[2] < kbps[3] && kbps[3] < kbps[1] && kbps[1] < kbps[0])
        println("takes over segments: %d pages, %d bytes, segments %s kbit/s measured %s, takes %s, identical to the continuous encode".format(java.util.Locale.ROOT,
            ps.size, whole.size, want.map { it / 1000 }, kbps.map { "%.1f".format(java.util.Locale.ROOT, it) }, stub.takes[id]))
        checkWithFfmpeg("takes over segments", whole, input48)
    }

    /** A gap over the window: two recordings, each a valid stream of its own with its own session JSON; no take list. */
    @Test fun aGapOverTheWindowGivesTwoRecordings() {
        val c = cloud()
        val a = noise(3.0)
        val b = noise(2.5)
        take(c, a)
        val first = open(c)
        now += 3_001
        take(c, b)
        val second = open(c)
        assertNotEquals(first, second)
        windowPasses(c)
        for ((id, x) in listOf(first to a, second to b)) {
            val item = settle(c, id)
            assertEquals(1, item.takes)
            assertEquals(true, item.completed)
            val ps = checkStream(join(stub.segments(id)), 16_000, x.size * 3L)
            assertArrayEquals(join(continuous(x, 16_000, ps[0].serial, item.segmentBitrates)), join(stub.segments(id)))
            assertFalse(File(folder(id), ShareFiles.TAKES).exists())
            assertNull(c.sharing.outbox.takesState(id))
        }
        assertEquals(2, stub.starts().size)
        assertEquals("a session JSON each", setOf(first, second), stub.starts().map { it.headers["x-qlab-id"] }.toSet())
        assertTrue("one take each: no take list", stub.seen.none { it.path.endsWith("/takes") })

        // exactly the window apart joins
        take(c, a)
        now += 3_000
        take(c, b)
        val id = open(c)
        windowPasses(c)
        assertEquals(2, settle(c, id).takes)
        assertEquals(takeMaps(0 to 0, 3_000 to 3_000), stub.takes[id])
        checkStream(join(stub.segments(id)), 16_000, (a.size + b.size) * 3L)
    }

    /**
     * The process killed between takes and inside the second take: the next launch closes the recording at its last
     * whole page (as 2.0.1), keeps the takes that start inside what is left, uploads it and sends the take list.
     */
    @Test fun aKilledRecordingOfTakesIsRecoveredWithItsTakes() {
        /** The disk of [from] as a kill would leave it now (the encoder caught up first). */
        fun killedCopy(c: QuranLabCloud, from: File): File {
            c.sharing.syncCapture()
            return temp().also { from.copyRecursively(it, overwrite = true) }
        }

        fun relaunch(killed: File, id: String): Pair<QuranLabCloud, Contribution> {
            ShareCapture.ACTIVE.remove(id)
            val r = cloud(consent = false, at = killed)
            assertTrue("the grant is on disk", r.sharing.isOn)
            return r to settle(r, id)
        }

        // killed in the window after the second take
        val c = cloud()
        take(c, noise(12.0))
        now += 1_500
        take(c, noise(4.0))
        val id = open(c)
        val killed = killedCopy(c, storage)
        windowPasses(c)
        assertEquals(listOf(Take(0, 0), Take(12_000, 1_500)), ShareFiles.takes(folder(id, killed)))
        assertFalse(File(folder(id, killed), ShareFiles.END).exists())
        val torn = File(folder(id, killed), "seg-00002.opus")
        val wholePages = torn.readBytes()
        torn.appendBytes("OggS".toByteArray() + ByteArray(90) { 5 })
        val (r, item) = relaunch(killed, id)
        assertEquals(false, item.completed)
        assertEquals(2, item.segments)
        assertEquals(2, item.takes)
        val segs = stub.segments(id)
        assertArrayEquals("the torn page is cut off", wholePages, segs[1])
        val ps = checkStream(join(segs), 16_000, 0, ended = false)
        assertEquals(((ps.last().granule - 312) / 48).toInt(), item.durationMs)
        assertTrue("16 s were recited; what is on whole pages is kept: ${item.durationMs}", item.durationMs in 14_000..15_999)
        assertEquals(takeMaps(0 to 0, 12_000 to 1_500), stub.takes[id])
        assertEquals("{\"takes\":[{\"start_ms\":0,\"gap_ms\":0},{\"start_ms\":12000,\"gap_ms\":1500}]}", String(stub.takeCalls(id).single().body, Charsets.UTF_8))
        assertEquals(ContributionOutbox.TAKES_SENT, r.sharing.outbox.takesState(id))
        assertEquals(1, stub.starts().size)

        // killed inside the second take, 5.3 s in
        val at2 = temp()
        val d = cloud(at = at2)
        take(d, noise(12.0))
        now += 700
        start(d)
        push(d, noise(5.3))
        val id2 = open(d)
        val killed2 = killedCopy(d, at2)
        end(d)
        windowPasses(d)
        val (_, item2) = relaunch(killed2, id2)
        assertEquals(false, item2.completed)
        assertEquals(2, item2.takes)
        assertTrue("17.3 s were recited: ${item2.durationMs}", item2.durationMs in 16_000..17_299)
        assertEquals(takeMaps(0 to 0, 12_000 to 700), stub.takes[id2])
        checkStream(join(stub.segments(id2)), 16_000, 0, ended = false)

        // killed 0.4 s into the second take: none of it is on a whole page, so the recording has one take and no list
        val at3 = temp()
        val e = cloud(at = at3)
        take(e, noise(12.0))
        now += 700
        start(e)
        push(e, noise(0.4))
        val id3 = open(e)
        val killed3 = killedCopy(e, at3)
        end(e)
        windowPasses(e)
        assertEquals("the list was on disk when it was killed", listOf(Take(0, 0), Take(12_000, 700)), ShareFiles.takes(folder(id3, killed3)))
        val (r3, item3) = relaunch(killed3, id3)
        assertEquals(1, item3.takes)
        assertTrue("${item3.durationMs}", item3.durationMs in 11_000..11_999)
        assertFalse(File(folder(id3, killed3), ShareFiles.TAKES).exists())
        assertTrue(stub.takeCalls(id3).isEmpty())
        assertNull(r3.sharing.outbox.takesState(id3))
    }

    /** Two takes in a row (1.5 s each, 500 ms apart) and no third: the closed recording's id. */
    private fun joined(c: QuranLabCloud): String {
        take(c, noise(1.5))
        now += 500
        take(c, noise(1.5))
        val id = open(c)
        windowPasses(c)
        return id
    }

    private val twoTakes = "{\"takes\":[{\"start_ms\":0,\"gap_ms\":0},{\"start_ms\":1500,\"gap_ms\":500}]}"

    private fun bodies(id: String) = stub.takeCalls(id).map { String(it.body, Charsets.UTF_8) }

    /** The take list: sent once, after the last segment, with the exact body; a recording of one take sends none. */
    @Test fun theTakeListIsSentOnceAfterTheLastSegment() {
        val c = cloud()
        val id = joined(c)
        assertTrue("nothing before the outbox runs", stub.seen.none { it.path.startsWith("/v1/uploads") })
        assertTrue(c.sharing.outbox.hasWork())
        c.sharing.outbox.drain(ignoringBackoff = true)
        val item = c.sharing.outbox.item(id)!!
        assertEquals(Contribution.Status.UPLOADED, item.status)
        assertEquals(2, item.takes)
        assertEquals(3_000, item.durationMs)
        assertEquals(listOf(twoTakes), bodies(id))
        val order = stub.seen.filter { it.path.startsWith("/v1/uploads") || it.method == "PUT" }.map { it.method + " " + it.path.substringAfterLast('/') }
        assertEquals("the session, the segment, then the take list", listOf("POST uploads", "PUT seg-00001.opus", "POST takes"), order)
        assertEquals(takeMaps(0 to 0, 1_500 to 500), stub.takes[id])
        assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(id))
        assertFalse(c.sharing.outbox.hasWork())
        assertNull(c.sharing.outbox.nextDue())
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("never again", 1, stub.takeCalls(id).size)
        // a new process reads the item: sent, and the count stays
        val again = cloud(consent = false)
        again.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(1, stub.takeCalls(id).size)
        assertEquals(2, again.sharing.outbox.item(id)!!.takes)
        assertEquals(ContributionOutbox.TAKES_SENT, again.sharing.outbox.takesState(id))

        // one take: no list, no call, no field in the item
        take(c, noise(1.5))
        val one = open(c)
        windowPasses(c)
        assertEquals(1, settle(c, one).takes)
        assertTrue(stub.takeCalls(one).isEmpty())
        assertNull(c.sharing.outbox.takesState(one))
        assertFalse(File(folder(one), ContributionOutbox.ITEM).readText().contains("takes"))
    }

    /** A server without the route answers 404: the list is dropped, the session stays uploaded, one line in the log. */
    @Test fun a404DropsTheTakeListWithoutFailingTheSession() {
        stub.takesRoute = false
        val lines = ArrayList<String>()
        val c = cloud(debug = true, log = { synchronized(lines) { lines.add(it) } })
        val a = joined(c)
        val b = joined(c)
        c.sharing.outbox.drain(ignoringBackoff = true)
        for (id in listOf(a, b)) {
            val item = c.sharing.outbox.item(id)!!
            assertEquals(Contribution.Status.UPLOADED, item.status)
            assertNull(item.lastError)
            assertEquals("the count is the recording's, sent or not", 2, item.takes)
            assertEquals(ContributionOutbox.TAKES_DROPPED, c.sharing.outbox.takesState(id))
            assertEquals(listOf(twoTakes), bodies(id))
        }
        assertFalse(c.sharing.outbox.hasWork())
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("a dropped list is not sent again", 2, stub.seen.count { it.path.endsWith("/takes") })
        assertEquals("logged once: $lines", 1, lines.count { it.contains("/takes") })
        assertTrue(c.issues.pending().isEmpty())
        assertEquals(2, c.sharing.items.count { it.status == Contribution.Status.UPLOADED })
        // a release build logs nothing
        val quiet = ArrayList<String>()
        val d = cloud(at = temp(), log = { synchronized(quiet) { quiet.add(it) } })
        val id = joined(d)
        d.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_DROPPED, d.sharing.outbox.takesState(id))
        assertTrue(quiet.isEmpty())
    }

    /**
     * No answer to the take list call: the session is uploaded all the same, the list waits with the outbox's backoff
     * and the retry sends the same body, in this process or the next. The bitrate ladder does not hear of it.
     */
    @Test fun aNetworkFailureKeepsTheTakeListAndTheRetrySendsTheSameBody() {
        val c = cloud()
        val id = joined(c)
        val tried = ArrayList<String>()
        var offline = true
        stub.offlineFor = { r -> if (r.url.endsWith("/takes")) { tried.add(String(r.body!!, Charsets.UTF_8)); offline } else false }
        assertFalse("offline: the pass stops", c.sharing.outbox.drain(ignoringBackoff = true))
        val item = c.sharing.outbox.item(id)!!
        assertEquals("uploaded whatever becomes of the list", Contribution.Status.UPLOADED, item.status)
        assertNull(item.lastError)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(id))
        assertTrue(c.sharing.outbox.hasWork())
        assertEquals(listOf(twoTakes), tried)
        assertEquals("not audio: the ladder stays", 192_000, c.sharing.outbox.ladder.bitrate)
        // the backoff: nothing before it is due (30 s, with jitter)
        c.sharing.outbox.drain()
        assertEquals(1, tried.size)
        val due = c.sharing.outbox.nextDue()!!
        assertTrue("due in ${due - now} ms", due - now in 24_000..36_000)
        now = due
        assertFalse(c.sharing.outbox.drain())
        assertEquals(listOf(twoTakes, twoTakes), tried)
        assertTrue("the second wait is longer", c.sharing.outbox.nextDue()!! - now in 48_000..72_000)

        // the next launch finds it in the item and sends it
        val again = cloud(consent = false)
        assertTrue(again.sharing.outbox.hasWork())
        assertEquals(ContributionOutbox.TAKES_PENDING, again.sharing.outbox.takesState(id))
        offline = false
        assertTrue(again.sharing.outbox.drain(ignoringBackoff = true))
        assertEquals(listOf(twoTakes, twoTakes, twoTakes), tried)
        assertEquals(listOf(twoTakes), bodies(id))
        assertEquals(ContributionOutbox.TAKES_SENT, again.sharing.outbox.takesState(id))
        assertFalse(again.sharing.outbox.hasWork())
        assertEquals("one PUT in all: the segment was not sent again", 1, stub.puts.size)
        assertEquals(1, stub.starts().size)
    }

    /** 503, 429 and (SDK 3.0) 400 keep the list for a retry with the same body, 400 reporting once; 401 renews the token; 410 drops it. */
    @Test fun theTakeListCallFollowsItsAnswers() {
        val c = cloud()
        // 503, then 429, then accepted
        val a = joined(c)
        stub.overrides["/v1/uploads/$a/takes"] = { n, _ ->
            when (n) {
                1 -> StubQlab.Reply.error(503, "unavailable")
                2 -> StubQlab.Reply.error(429, "rate_limited")
                else -> null
            }
        }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(Contribution.Status.UPLOADED, c.sharing.outbox.item(a)!!.status)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(a))
        c.sharing.outbox.drain()
        assertEquals("a refusal to retry waits for its backoff", 1, stub.takeCalls(a).size)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(a))
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(a))
        assertEquals(listOf(twoTakes, twoTakes, twoTakes), bodies(a))
        assertEquals("the ladder does not hear of the take list", 192_000, c.sharing.outbox.ladder.bitrate)

        // 400 (SDK 3.0; 2.0.3 dropped the list): it stays pending and is tried again on the backoff until the server
        // takes it, and the SDK bug is reported once for the list
        val b = joined(c)
        stub.overrides["/v1/uploads/$b/takes"] = { n, _ ->
            if (n <= 2) StubQlab.Reply.json(400, mapOf("error" to "invalid_body", "message" to "json.takes[1].gap_ms")) else null
        }
        fun reports() = c.issues.pending().filter { it["code"] == "bad_request:uploads_takes" }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(Contribution.Status.UPLOADED, c.sharing.outbox.item(b)!!.status)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(b))
        val reported = reports()
        assertEquals(1, reported.size)
        c.sharing.outbox.drain()
        assertEquals("a refused list waits for its backoff", 1, stub.takeCalls(b).size)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(b))
        assertEquals(2, stub.takeCalls(b).size)
        assertEquals("reported once for the list, not at every refusal", reported.toString(), reports().toString())
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(b))
        assertEquals(listOf(twoTakes, twoTakes, twoTakes), bodies(b))

        // 401: a new token, and again at once
        val d = joined(c)
        stub.overrides["/v1/uploads/$d/takes"] = { n, _ -> if (n == 1) StubQlab.Reply.error(401, "invalid_token") else null }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(d))
        c.sharing.outbox.drain()
        assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(d))
        val calls = stub.takeCalls(d)
        assertEquals(2, calls.size)
        assertNotEquals("a renewed token", calls[0].headers["authorization"], calls[1].headers["authorization"])
        assertEquals(listOf(twoTakes, twoTakes), bodies(d))

        // 410: the upload was deleted on the server: dropped
        val e = joined(c)
        stub.overrides["/v1/uploads/$e/takes"] = { _, _ -> StubQlab.Reply.error(410, "upload_deleted") }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(Contribution.Status.UPLOADED, c.sharing.outbox.item(e)!!.status)
        assertEquals(ContributionOutbox.TAKES_DROPPED, c.sharing.outbox.takesState(e))
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(1, stub.takeCalls(e).size)
        assertEquals("four recordings, all uploaded", 4, c.sharing.items.count { it.status == Contribution.Status.UPLOADED })
        assertFalse(c.sharing.outbox.hasWork())
    }

    /** A take list that waits is dropped when sharing goes off, and after 7 days; its recording stays uploaded. */
    @Test fun aWaitingTakeListEndsWithSharingOrAfterSevenDays() {
        val c = cloud()
        val a = joined(c)
        stub.offlineFor = { r -> r.url.endsWith("/takes") }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(a))
        c.sharing.setOn(false)
        assertEquals(ContributionOutbox.TAKES_DROPPED, c.sharing.outbox.takesState(a))
        assertEquals(listOf(Contribution.Status.UPLOADED), c.sharing.items.map { it.status })
        stub.offlineFor = null
        c.sharing.setOn(true)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue(stub.takeCalls(a).isEmpty())

        val b = joined(c)
        stub.offlineFor = { r -> r.url.endsWith("/takes") }
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(b))
        stub.offlineFor = null
        now += 8 * 86_400_000L
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(ContributionOutbox.TAKES_DROPPED, c.sharing.outbox.takesState(b))
        assertTrue(stub.takeCalls(b).isEmpty())
        assertEquals(Contribution.Status.UPLOADED, c.sharing.outbox.item(b)!!.status)
        assertFalse(c.sharing.outbox.hasWork())
    }

    /**
     * The capped tier's segment cap ends an upload while its recording still runs: the take list, sent once the
     * recording has ended, keeps the takes that start inside the audio that went up (none when fewer than two are
     * left), and no further take joins that recording.
     */
    @Test fun theSegmentCapKeepsTheTakesInsideWhatWentUp() {
        stub.batch = 1
        val c = cloud()
        take(c, noise(4.0))
        val id = open(c)
        stub.overrides["/v1/uploads/$id/urls"] = { _, _ -> StubQlab.Reply.error(403, "segment_cap") }
        now += 1_000
        start(c)
        push(c, noise(28.0)) // 32 s: the second segment closed, with no URL for it
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        val capped = c.sharing.outbox.item(id)!!
        assertEquals("what is up is the session", Contribution.Status.UPLOADED, capped.status)
        assertEquals("segment_cap", capped.lastError)
        assertEquals(1, stub.puts.size)
        assertEquals("the list waits for the recording's end", ContributionOutbox.TAKES_PENDING, c.sharing.outbox.takesState(id))
        assertTrue(stub.takeCalls(id).isEmpty())
        push(c, noise(3.0))
        end(c)
        assertNull("the upload ended at the cap: the recording closes with the take", c.sharing.recorder.current)
        c.sharing.lastCapture!!.await()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("the second take starts 4 s in, inside the 10 s that went up",
            listOf("{\"takes\":[{\"start_ms\":0,\"gap_ms\":0},{\"start_ms\":4000,\"gap_ms\":1000}]}"), bodies(id))
        assertEquals(ContributionOutbox.TAKES_SENT, c.sharing.outbox.takesState(id))
        assertTrue(c.issues.pending().isEmpty())

        // a take inside what would have been the window starts a recording of its own
        now += 500
        take(c, noise(12.0))
        val next = open(c)
        assertNotEquals(id, next)
        stub.overrides["/v1/uploads/$next/urls"] = { _, _ -> StubQlab.Reply.error(403, "segment_cap") }
        now += 1_000
        start(c)
        push(c, noise(20.0)) // 32 s again; this take starts at 12 s, past the 10 s that will go up
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("segment_cap", c.sharing.outbox.item(next)!!.lastError)
        end(c)
        c.sharing.lastCapture!!.await()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue("one take is inside what went up: no list", stub.takeCalls(next).isEmpty())
        assertNull(c.sharing.outbox.takesState(next))
        assertEquals(1, c.sharing.outbox.item(next)!!.takes)
        assertFalse(c.sharing.outbox.hasWork())
    }

    /**
     * Sharing turned off during the join window: the recording ends at once and the unsent queue is deleted (the 2.0.1
     * rule); a take that follows is not recorded. With segments already up, nothing more is sent.
     */
    @Test fun withdrawalDuringTheJoinWindowEndsTheRecordingAndDeletesTheQueue() {
        val c = cloud()
        stub.down = true
        take(c, noise(12.0))
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        val id = c.sharing.items.single().id
        assertEquals(id, open(c))
        assertTrue(folder(id).isDirectory)
        now += 1_000
        c.sharing.setOn(false)
        assertNull("ended at once", c.sharing.recorder.current)
        c.sharing.lastCapture!!.await()
        assertTrue(c.sharing.items.isEmpty())
        assertFalse("the queue is deleted", folder(id).exists())
        assertEquals(0, File(storage, "outbox").listFiles()!!.size)
        assertFalse(id in ShareCapture.ACTIVE)
        stub.down = false
        now += 500
        take(c, noise(3.0))
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue("a take after sharing went off is not recorded", c.sharing.items.isEmpty() && stub.starts().isEmpty() && stub.puts.isEmpty())
        assertNull(c.sharing.recorder.current)

        // on again from the settings: the next take starts a new recording
        c.sharing.setOn(true)
        take(c, noise(12.0))
        val second = open(c)
        now += 500
        start(c)
        push(c, noise(1.0))
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("the first segment is up while the second take runs", 1, stub.puts.size)
        assertEquals(listOf(Take(0, 0), Take(12_000, 500)), ShareFiles.takes(folder(second)))
        // off inside the take: the recording ends there, nothing more is sent, the rest of the take is not recorded
        c.sharing.setOn(false)
        assertNull(c.sharing.recorder.current)
        push(c, noise(1.0))
        end(c)
        c.sharing.lastCapture!!.await()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(1, stub.puts.size)
        assertTrue(stub.seen.none { it.path.endsWith("/takes") })
        assertTrue(c.sharing.items.isEmpty())
        assertFalse(folder(second).exists())
        assertEquals(ConsentState.Consent.FINAL, c.sharing.state.consent)

        // "Delete what I shared" during the window ends the recording too
        c.sharing.setOn(true)
        take(c, noise(3.0))
        assertNotNull(c.sharing.recorder.current)
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertNull(c.sharing.recorder.current)
        c.sharing.lastCapture!!.await()
        assertTrue(c.sharing.items.isEmpty())
        assertEquals(0, File(storage, "outbox").listFiles()!!.size)
    }

    /** The app going to the background closes a recording that waits; one in a take closes when the take ends. */
    @Test fun theBackgroundDuringTheWindowClosesTheRecording() {
        val c = cloud()
        val a = noise(3.0)
        take(c, a)
        val first = open(c)
        now += 1_000
        c.background()
        assertNull("closed in the window", c.sharing.recorder.current)
        c.sharing.lastCapture!!.await()
        val item = settle(c, first)
        assertEquals(true, item.completed)
        assertEquals(1, item.takes)
        checkStream(join(stub.segments(first)), 16_000, a.size * 3L)

        // in the foreground again, inside what was the window: a new recording
        c.foreground()
        now += 500
        take(c, noise(3.0))
        val second = open(c)
        assertNotEquals(first, second)
        // the background during the next take: the take keeps its recording, which closes when it ends
        now += 600
        start(c)
        push(c, noise(1.0))
        c.background()
        assertEquals(second, open(c))
        push(c, noise(1.0))
        end(c)
        assertNull("closed with the take", c.sharing.recorder.current)
        c.sharing.lastCapture!!.await()
        val joined = settle(c, second)
        assertEquals(2, joined.takes)
        assertEquals(5_000, joined.durationMs)
        assertEquals(takeMaps(0 to 0, 3_000 to 600), stub.takes[second])
        checkStream(join(stub.segments(second)), 16_000, 5L * 48_000)
        assertEquals(2, stub.starts().size)
    }

    /**
     * The audio that waits in memory for the encoder is bounded: at most ten seconds (ShareCapture.MAX_WAITING_MS).
     * An encoder that does not catch up ends the capture; nothing more is kept. The encoder is stopped with a latch,
     * the audio is a fixed number of one-second buffers.
     */
    @Test fun audioWaitingForTheEncoderIsBounded() {
        val c = cloud()
        val gate = java.util.concurrent.CountDownLatch(1)
        val capture = ShareCapture(c.sharing.outbox, { _, _ -> ByteArray(0) }, "built_in", { gate.await(); true }, catchUpMs = 50)
        val second = FloatArray(16_000)
        try {
            var most = 0L
            repeat(40) {
                capture.append(second, 0, second.size, 16_000)
                most = maxOf(most, capture.waitingSamples)
            }
            assertEquals("ten seconds wait, the eleventh did not get in", 10L * 16_000, most)
            assertFalse("the encoder stalled: the capture ended", capture.alive)
            assertEquals(10L * 16_000, capture.waitingSamples)
        } finally {
            gate.countDown()
        }
        capture.finish(false)
        capture.await()
        assertEquals("the encoder took what waited", 0L, capture.waitingSamples)
        assertFalse("nothing of it is kept", capture.folder.exists())

        // an encoder that keeps up is never the limit: three minutes of audio as fast as it is given, all of it encoded
        val live = ShareCapture(c.sharing.outbox, { rate, route ->
            ShareCapture.sessionJson("4.0.0", "mock", null, "share-2026-11-09", "c".repeat(32), null, null, "2026-11-11", "phone", 14, rate, route)
        }, "built_in", { true })
        repeat(180) { live.append(second, 0, second.size, 16_000) }
        assertTrue(live.alive)
        live.finish(true)
        live.await()
        assertEquals(180_000, ShareFiles.end(live.folder)!!.durationMs)
        live.folder.deleteRecursively()
    }

    /** The signed config's `flags.share_join_ms`: 0 never joins, 8000 joins up to 8 s apart, anything invalid is 3000. */
    @Test fun theConfigSetsTheJoinWindow() {
        stub.flags = stub.flags + ("share_join_ms" to 0)
        val c = cloud()
        assertEquals(0, c.currentConfig!!.flags.shareJoinMs)
        take(c, noise(2.5))
        // SDK 3.0: with window 0 the recording stays only for its tail (fed audio that continues the take)
        assertNotNull("window 0: open for the tail", c.sharing.recorder.current)
        now += TakeJoin.TAIL_WAIT_MS + 1
        c.sharing.checkJoinWindow()
        assertNull("window 0: no tail came, closed; no take joins it", c.sharing.recorder.current)
        val a = c.sharing.lastCapture!!.also { it.await() }.id
        take(c, noise(2.5))
        // a take that starts while a recording waits for its tail does not join it either
        take(c, noise(2.5))
        val b = c.sharing.lastCapture!!.also { it.await() }.id
        assertNotEquals(a, b)
        now += TakeJoin.TAIL_WAIT_MS + 1
        c.sharing.checkJoinWindow()
        val afterIt = c.sharing.lastCapture!!.also { it.await() }.id
        assertNotEquals(b, afterIt)
        assertEquals(1, settle(c, afterIt).takes)
        assertEquals(listOf(1, 1), listOf(settle(c, a), settle(c, b)).map { it.takes })
        assertEquals("three takes, three recordings", 3, stub.starts().size)

        stub.flags = stub.flags + ("share_join_ms" to 8_000)
        c.refreshConfig()
        assertEquals(8_000, c.currentConfig!!.flags.shareJoinMs)
        take(c, noise(2.5))
        now += 7_000
        take(c, noise(2.5))
        val joined = open(c)
        now += 8_001
        take(c, noise(2.5))
        val apart = open(c)
        assertNotEquals(joined, apart)
        windowPasses(c, 8_000)
        assertEquals(2, settle(c, joined).takes)
        assertEquals(takeMaps(0 to 0, 2_500 to 7_000), stub.takes[joined])
        assertEquals(1, settle(c, apart).takes)

        // not a whole number from 0 to 10000: the default, 3000
        for (bad in listOf<Any>("5", 20_000, 2.5, true)) {
            stub.flags = stub.flags + ("share_join_ms" to bad)
            c.refreshConfig()
            assertEquals("share_join_ms $bad", 3_000, c.currentConfig!!.flags.shareJoinMs)
        }
        take(c, noise(2.5))
        now += 3_000
        take(c, noise(2.5))
        val third = open(c)
        now += 3_001
        take(c, noise(2.5))
        assertNotEquals(third, open(c))
        windowPasses(c)
        assertEquals(2, settle(c, third).takes)
        assertEquals(takeMaps(0 to 0, 2_500 to 3_000), stub.takes[third])
    }

    /**
     * What a recording is bound to, through the client: another input rate, model revision, consent version or
     * contributor starts a new recording even inside the window; the backend and the input route may change.
     */
    @Test fun aTakeJoinsOnlyTheSameRateRevisionConsentAndContributor() {
        val c = cloud()
        take(c, noise(2.5))
        val first = open(c)
        now += 500
        // another backend and route: the same recording (the session JSON keeps the first take's)
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "npu:sm8650", sampleRate = 16_000, inputRoute = "bluetooth",
            decodeBackend = "exact"))
        push(c, noise(2.5))
        end(c)
        assertEquals(first, open(c))
        now += 500
        take(c, noise(2.5, 48_000), 48_000)
        val rate = open(c)
        assertNotEquals("another input rate", first, rate)
        now += 500
        start(c, 48_000, revision = "v4.0+def")
        push(c, noise(2.5, 48_000), 48_000)
        end(c)
        val revision = open(c)
        assertNotEquals("another model revision", rate, revision)
        now += 500
        c.sharing.state.grant("share-2026-11-01", byGuardian = false)
        take(c, noise(2.5, 48_000), 48_000)
        val consent = open(c)
        assertNotEquals("another consent version", revision, consent)
        now += 500
        c.sharing.contributor.reset()
        take(c, noise(2.5, 48_000), 48_000)
        val contributor = open(c)
        assertNotEquals("another contributor", consent, contributor)
        windowPasses(c)
        val ids = listOf(first, rate, revision, consent, contributor)
        assertEquals(listOf(2, 1, 1, 1, 1), ids.map { settle(c, it).takes })
        val sessions = stub.starts().associate { it.headers["x-qlab-id"] to it.json!! }
        assertEquals(5, sessions.size)
        @Suppress("UNCHECKED_CAST")
        val audio = sessions[first]!!["audio"] as Map<String, Any?>
        assertEquals("the first take's route", "built_in", audio["input_route"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("the first take's backend", "mock", (sessions[first]!!["decode"] as Map<String, Any?>)["backend"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("v4.0+def", (sessions[revision]!!["decode"] as Map<String, Any?>)["model_revision"])
        assertEquals("share-2026-11-01", sessions[consent]!!["consent_version"])
        assertNotEquals(sessions[consent]!!["contributor"], sessions[contributor]!!["contributor"])
        assertEquals(takeMaps(0 to 0, 2_500 to 500), stub.takes[first])
        assertEquals(1, stub.seen.count { it.path.endsWith("/takes") })
    }

    /**
     * The rest keeps working for a recording that spans takes: its segments upload while it waits for the next take
     * (the next batch of URLs as for a running session), an offline one goes in order after a relaunch, telemetry
     * counts a session per take, a finished take's ask moment leaves the recording open, the thank-you comes once.
     */
    @Suppress("UNCHECKED_CAST")
    @Test fun uploadsTelemetryAndTheAskMomentAcrossTakes() {
        stub.batch = 6
        val c = cloud()
        val shown = ArrayList<Sharing.SheetRequest>()
        c.sharing.prompts = { synchronized(shown) { shown.add(it) } }
        val sessions = c.sharing.state.sessions
        take(c, noise(12.0))
        val id = open(c)
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        val waiting = c.sharing.outbox.item(id)!!
        assertEquals("the first segment is up while the recording waits for a take", listOf("/r2/qlab-contributions/v1/$id/seg-00001.opus"), stub.puts.map { it.path })
        assertEquals(Contribution.Status.QUEUED, waiting.status)
        assertNull(waiting.completed)
        assertEquals("segment 1 used, 5 left: the next batch, as for a session that runs", listOf(7), stub.urlCalls(id))
        assertTrue(stub.takeCalls(id).isEmpty())
        now += 2_000
        take(c, noise(13.0))
        now += 2_900
        take(c, noise(10.0))
        assertEquals("the ask moment of each finished take left the recording open", id, open(c))
        assertEquals(sessions + 3, c.sharing.state.sessions)
        assertTrue("a learner who said yes is not asked: $shown", shown.none { it is Sharing.SheetRequest.Consent })
        windowPasses(c)
        val item = settle(c, id)
        assertEquals(3, item.takes)
        assertEquals(3, item.segments)
        assertEquals(35_000, item.durationMs)
        assertEquals((1..3).map { "/r2/qlab-contributions/v1/$id/seg-0000$it.opus" }, stub.puts.map { it.path })
        assertEquals(1, stub.starts().size)
        assertEquals(takeMaps(0 to 0, 12_000 to 2_000, 25_000 to 2_900), stub.takes[id])
        checkStream(join(stub.segments(id)), 16_000, 35L * 48_000)
        assertEquals("the thank-you once", 1, shown.count { it == Sharing.SheetRequest.Thanks })
        c.background()
        val report = stub.parse(c.telemetry.pendingReports().single().readBytes())
        assertEquals("three takes are three sessions", 3.0, (report["cohorts"] as List<Map<String, Any?>>).sumOf { it["sessions"] as Double }, 0.0)

        // offline through three takes, then a new process: everything is on disk, sent in order, then the take list
        val at = temp()
        val d = cloud(at = at)
        stub.down = true
        take(d, noise(12.0))
        now += 1_000
        take(d, noise(13.0))
        now += 1_000
        take(d, noise(10.0))
        val off = open(d)
        windowPasses(d)
        d.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(Contribution.Status.QUEUED, d.sharing.outbox.item(off)!!.status)
        stub.down = false
        val before = stub.seen.size
        val next = cloud(consent = false, at = at)
        assertEquals(3, settle(next, off).takes)
        val sent = stub.seen.drop(before).filter { it.path.contains(off) || it.headers["x-qlab-id"] == off }.map { it.method + " " + it.path.substringAfterLast('/') }
        assertEquals(listOf("POST uploads", "PUT seg-00001.opus", "PUT seg-00002.opus", "PUT seg-00003.opus", "POST takes"), sent)
        assertEquals(takeMaps(0 to 0, 12_000 to 1_000, 25_000 to 1_000), stub.takes[off])
    }
}
