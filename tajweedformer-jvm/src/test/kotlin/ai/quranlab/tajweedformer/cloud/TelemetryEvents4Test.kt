package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecitedOutcome
import ai.quranlab.tajweedformer.RecitedReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The SDK's 4.0 telemetry bodies against qlab-api's OWN validator (branch sdk4-server, `src/events2.js`): the closed
 * schema the server picks for the body's sdk_version (EVENTS4), then ruleProblem, reportedProblem and sdk4Problem
 * (17.8 and 17.9's bounds), run by node from the API's folder, read-only (`scripts/check_events4.mjs`). Set
 * QLAB_API_DIR to that worktree; skipped without it or without node. A body broken on purpose must fail, so a
 * validator that passes everything cannot pass this test.
 */
class TelemetryEvents4Test : ai.quranlab.tajweedformer.OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-e4-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val ctx = TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel8", "tensor_g3", 14, "14"))
    private val day0 = 20_000L
    private var now = day0 * 86_400_000L + 10 * 3_600_000L

    private val api: File? = (System.getProperty("QLAB_API_DIR") ?: System.getenv("QLAB_API_DIR"))?.let(::File)?.takeIf { File(it, "src/events2.js").isFile }

    private fun node(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0 }
    } catch (_: Exception) {
        false
    }

    /** The server's verdict on each body: null when it takes it, else its error text. */
    @Suppress("UNCHECKED_CAST")
    private fun check(bodies: List<Map<String, Any?>>): List<Pair<String, String?>> {
        val dir = temp()
        val files = bodies.mapIndexed { i, b -> File(dir, "body$i.json").also { it.writeText(Json.write(b)) } }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "scripts/check_events4.mjs") }.first { it.isFile }
        val p = ProcessBuilder(listOf("node", script.path, api!!.path) + files.map { it.path }).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node ended", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        return out.lines().filter { it.startsWith("{") }.map { l ->
            val o = Json.parse(l) as Map<String, Any?>
            (o["schema"] as String) to (o["error"] as? String)
        }
    }

    /** A day with every 4.0 field: the measured and the app's counts, 17.8's and the app's judgement (17.9). */
    private fun fullDay(): Map<String, Any?> {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        // three weeks earlier: the install's first active day, and judged takes, so a trend is due today
        now -= 21 * 86_400_000L
        t.sessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
        t.sessionEnd(TajweedObserver.SessionEnd(30.0, completed = true, audioMs = 30_000, speechFrames = 400))
        now += 21 * 86_400_000L
        // today: two sessions (one cancelled with audio), measured and reported takes, judgements, the lease, checks
        t.sessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
        t.sessionEnd(TajweedObserver.SessionEnd(42.0, completed = true, audioMs = 42_000, speechFrames = 600))
        t.sessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
        t.sessionEnd(TajweedObserver.SessionEnd(8.0, completed = false, audioMs = 0))
        assertTrue(t.recordTake(TakeRecord(1, day0, RecitedReport(2, 255, 257, RecitedOutcome.correct(),
            mapOf(256 to RecitedOutcome.incorrect(2, listOf("madd")))), found = true, surah = 2, first = 255, last = 257)))
        assertTrue(t.recordTake(TakeRecord(2, day0, RecitedReport(112, 1, 4, RecitedOutcome.incorrect(1)), found = false)))
        assertTrue(t.recordTake(TakeRecord(3, day0, null, found = false, recovered = true)))
        assertTrue(t.recordTake(TakeRecord(4, day0, RecitedReport(2, 255, 255, RecitedOutcome.correct()), found = true, surah = 2, first = 255, last = 255)))
        t.recitedDropped(1)
        t.lease(null)
        t.lease("offline")
        assertTrue(t.checks("madd", 4, 2))
        assertTrue(t.checks("word", 6, 1))
        return t.peek(ctx)!!
    }

    @Test
    fun theServersOwnValidatorTakesTheSdksBodies() {
        assumeTrue("QLAB_API_DIR (a qlab-api worktree on sdk4-server) is not set", api != null)
        assumeTrue("node is not on PATH", node())
        val full = fullDay()
        for (k in listOf("ayahs", "surah_ayahs", "app_ayahs", "app_surah_ayahs", "juz_ayahs", "hour_speech_s", "take_s", "session_s", "learner",
            "learner_gap", "streak", "repeat_ayahs", "recited_takes", "lease", "judged", "judged_surah_ayahs", "correct_surah_ayahs",
            "judged_tenure", "trend", "words", "rule_checks", "rule_flags")) assertNotNull("the full day has $k", full[k])
        // only the app's report; and a day with every switch off but the app's counts
        val appOnly = Telemetry(File(temp(), "telemetry")) { now }.let { t ->
            t.recited(1, 1, 7)
            t.peek(ctx)!!
        }
        val off = Telemetry(File(temp(), "telemetry")) { now }.let { t ->
            t.metric = { false }
            t.sessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
            t.sessionEnd(TajweedObserver.SessionEnd(12.0, completed = true, audioMs = 12_000, speechFrames = 100))
            t.recordTake(TakeRecord(9, day0, RecitedReport(36, 1, 12, RecitedOutcome.correct()), found = false))
            t.peek(ctx)!!
        }
        // a body broken on purpose (17.9: correct + incorrect must equal ayahs): the server refuses it
        @Suppress("UNCHECKED_CAST")
        val broken = full.toMutableMap().also { b -> b["judged"] = (b["judged"] as Map<String, Any?>) + ("correct" to 999.0) }
        val r = check(listOf(full, appOnly, off, broken))
        assertEquals(4, r.size)
        for ((i, x) in r.take(3).withIndex()) {
            assertEquals("body $i is a 4.0 body", "EVENTS4", x.first)
            assertEquals("body $i: ${Json.write(listOf(full, appOnly, off)[i])}", null, x.second)
        }
        assertTrue("the broken body is refused: ${r[3].second}", r[3].second?.contains("judged") == true)
    }
}
