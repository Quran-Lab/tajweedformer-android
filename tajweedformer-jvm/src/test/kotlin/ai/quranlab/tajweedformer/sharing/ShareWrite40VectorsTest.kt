package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract section 29 (SHARE_WRITE_40.md, SDK 4.0.2): a write of a shared recording that fails (a full disk) ends the
 * recording as a crash would, and the 2.0.1 recovery keeps the closed segments and the open segment's whole pages when
 * a segment had closed and sharing is on. Every case of `vectors40/share_write_40.json` through the whole sharing path
 * ([QuranLabCloud], the real capture and outbox, [StubQlab] for the API and the upload storage), with
 * [ShareFiles.failWrites] as the full disk (`Contributions.abandonNow` is where the capture meets it).
 *
 * Android has no per-take result of the milliseconds shared, so `take_ms` is not replayed. An Opus case needs the host
 * Opus library and is skipped without it (as SharingTest); the FLAC case runs anywhere.
 */
@Suppress("UNCHECKED_CAST")
class ShareWrite40VectorsTest : OpenChecked() {
    private val v: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors40/share_write_40.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }
    private val folders = ArrayList<File>()
    private val stubs = ArrayList<StubQlab>()
    private var now = 0L
    private var seed = 900L

    @After
    fun down() {
        ShareFiles.failWrites = false
        stubs.forEach { it.close() }
        folders.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-sharewrite").toFile().also(folders::add)

    private fun cloud(stub: StubQlab, storage: File): QuranLabCloud {
        val c = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)), stub.environment(storage, clock = { now }))
        // the join window's timer never runs: the replay moves the clock and asks for the check
        c.sharing.recorder.timer = JoinTimer { _, _ -> {} }
        c.refreshConfig()
        if (!c.sharing.isOn) c.sharing.accept()
        return c
    }

    @Test
    fun theSegmentPlanIsTheVectors() {
        assertEquals((v["plan_s"] as List<Double>).map { it.toInt() }, SegmentPlan.SECONDS)
    }

    @Test
    fun everyCase() {
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(6, cases.size)
        val opus = OpusNative.load()
        if (!opus && System.getProperty("TJ_REQUIRE_OPUS") == "1") throw AssertionError("libtajweedformer_opus: ${OpusNative.loadError}")
        var ran = 0
        for (case in cases) {
            if (case["form"] == "opus" && !opus) continue
            replay(case)
            ran++
        }
        assertTrue("no case ran", ran > 0)
    }

    private fun replay(case: Map<String, Any?>) {
        val name = case["name"] as String
        val rate = (case["rate"] as Double).toInt()
        val flags = case["flags"] as Map<String, Any?>
        val expect = case["expect"] as Map<String, Any?>
        val stub = StubQlab().also { stubs += it }
        stub.flags = stub.flags + flags.mapValues { (_, x) -> if (x is Double) x.toInt() else x }
        val storage = temp()
        now = System.currentTimeMillis()
        var c = cloud(stub, storage)
        var capture: ShareCapture? = null
        try {
            for (e in case["events"] as List<Map<String, Any?>>) {
                when (e["event"]) {
                    "take_start" -> {
                        c.onSessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu", sampleRate = rate, inputRoute = "built_in",
                            decodeBackend = "mock"))
                    }
                    "audio" -> {
                        // the capture's own buffers: 20 ms
                        val x = OggTestKit.noise(Math.round((e["s"] as Double) * rate).toInt(), seed = seed++)
                        var at = 0
                        while (at < x.size) {
                            val k = minOf(rate / 50, x.size - at)
                            c.onAudio(x, at, k, rate)
                            at += k
                        }
                        now += x.size * 1000L / rate
                        // the recording this audio went to (made at a take's first audio, or the one it joined)
                        capture = c.sharing.recorder.current as? ShareCapture ?: capture
                    }
                    // the audio so far is on the disk; the next audio is where the capture meets the full disk
                    "write_fails" -> {
                        capture?.sync()
                        ShareFiles.failWrites = true
                    }
                    "disk_ok" -> {
                        capture?.sync()
                        ShareFiles.failWrites = false
                    }
                    "take_end" -> c.onSessionEnd(TajweedObserver.SessionEnd(0.0, e["completed"] == true, feedbackShown = true,
                        speechFrames = if (e["completed"] == true) 250 else 0))
                    "sharing_off" -> c.sharing.setOn(false)
                    "relaunch" -> {
                        // the process ends with the files as they are: its captures stop, and nothing of them is in
                        // the new process's memory (ShareCapture.ACTIVE is per process)
                        capture?.await(5_000)
                        ShareCapture.ACTIVE.clear()
                        ShareFiles.failWrites = false
                        c = cloud(stub, storage)
                    }
                    else -> throw AssertionError("$name: event ${e["event"]}")
                }
            }
            // the join window passes: an open recording closes
            now += 60_001
            c.sharing.checkJoinWindow()
            capture?.await(10_000)
            // the uploader finishes
            repeat(40) {
                c.sharing.outbox.drain(ignoringBackoff = true)
                if (c.sharing.items.all { it.status == Contribution.Status.UPLOADED }) return@repeat
                Thread.sleep(25)
            }
            check(name, c, stub, storage, rate, case["form"] as String, expect)
        } finally {
            ShareFiles.failWrites = false
        }
    }

    private fun check(name: String, c: QuranLabCloud, stub: StubQlab, storage: File, rate: Int, form: String, expect: Map<String, Any?>) {
        val starts = stub.starts()
        if (expect["kept"] != true) {
            assertEquals("$name: a recording reached the server", 0, starts.size)
            assertTrue("$name: the outbox is not empty: ${c.sharing.items}", c.sharing.items.isEmpty())
            val left = File(storage, "outbox").listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
            assertEquals("$name: folders left in the outbox", emptyList<String>(), left)
            return
        }
        assertEquals("$name: one recording reaches the server", 1, starts.size)
        val item = c.sharing.items.single()
        assertEquals("$name: ${item.lastError}", Contribution.Status.UPLOADED, item.status)
        assertEquals("$name: the session is not completed", false, item.completed)
        val segs = stub.segments(item.id)
        val n = segs.size
        assertTrue("$name: $n segments", n in (expect["segments_min"] as Double).toInt()..(expect["segments_max"] as Double).toInt())
        val pages = OggTestKit.pages(segs.fold(ByteArray(0)) { a, b -> a + b })
        assertFalse("$name: an EOS page", pages.any { it.flags and 0x04 != 0 })
        assertEquals("$name: pages in sequence", pages.indices.toList(), pages.map { it.sequence })
        val ms = if (form == "opus") stub.receivedMs(item.id) else pages.last().granule * 1000 / rate
        assertTrue("$name: $ms ms", ms in (expect["audio_ms_min"] as Double).toLong()..(expect["audio_ms_max"] as Double).toLong())
        val takes = (expect["takes"] as Double?)?.toInt()
        val calls = stub.takeCalls(item.id)
        if (takes == null) {
            assertTrue("$name: a take list was sent", calls.isEmpty())
        } else {
            val body = calls.last().json!!
            assertEquals("$name: the take list", takes, (body["takes"] as List<*>).size)
        }
    }
}
