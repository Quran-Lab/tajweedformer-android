package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecitedOutcome
import ai.quranlab.tajweedformer.RecitedReport
import ai.quranlab.tajweedformer.TajweedError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 4.0's analytics (SPEC 17.1, 17.6, 17.8, 17.9; scratch SDK_32_CONTRACT_DRAFT.md section D): a take counted from
 * exactly one source (the Core's detector, else the app's report, else nothing), once, on the day it started, also
 * across a kill; the new fields and the `reported` list; the learner's days; the app's judgement; the switches.
 * The detector itself is the Core's (its own tests run the 906 recited vectors); here the Core's answer is given.
 */
class Analytics40Test : ai.quranlab.tajweedformer.OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-a40-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val ctx = TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel8", "tensor_g3", 14, "14"))
    private val day0 = 20_000L // a UTC epoch day
    private var now = day0 * 86_400_000L + 10 * 3_600_000L

    @Suppress("UNCHECKED_CAST")
    private fun body(t: Telemetry): Map<String, Any?> = t.peek(ctx) ?: emptyMap()

    @Test
    fun aTakeCountsFromOneSourceOnce() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        // measured: the detector's range; the app's report for the same take is not counted
        assertTrue(t.recordTake(TakeRecord(1, day0, RecitedReport(1, 1, 7), found = true, surah = 2, first = 255, last = 257)))
        // the same take again (a restart that finalises it once more): nothing more
        assertTrue(t.recordTake(TakeRecord(1, day0, RecitedReport(1, 1, 7), found = true, surah = 2, first = 255, last = 257)))
        // not measured, the app reported: the app's fallback count
        assertTrue(t.recordTake(TakeRecord(2, day0, RecitedReport(112, 1, 4), found = false)))
        // neither: nothing but the take's source count
        assertTrue(t.recordTake(TakeRecord(3, day0, null, found = false)))
        val b = body(t)
        assertEquals(3.0, b["ayahs"])
        assertEquals(mapOf("2" to 3.0), b["surah_ayahs"])
        assertEquals(4.0, b["app_ayahs"])
        assertEquals(mapOf("112" to 4.0), b["app_surah_ayahs"])
        assertFalse("coverage is not sent from 4.0", b.containsKey("coverage"))
        // the measured fields are never listed; the app's are, first
        assertEquals(listOf("app_ayahs", "app_surah_ayahs"), b["reported"])
        assertEquals(mapOf("measured" to 1.0, "none" to 1.0, "app" to 1.0), b["recited_takes"])
        // 2:255 to 2:257 lie in juz 3 (it starts at 2:253)
        assertEquals(mapOf("3" to 3.0), b["juz_ayahs"])
    }

    @Test
    fun theJuzTable() {
        assertEquals(1, Juz.of(1, 1))
        assertEquals(1, Juz.of(2, 141))
        assertEquals(2, Juz.of(2, 142))
        assertEquals(15, Juz.of(17, 1))
        assertEquals(29, Juz.of(77, 50))
        assertEquals(30, Juz.of(78, 1))
        assertEquals(30, Juz.of(114, 6))
    }

    @Test
    fun aKillLeavesNoTakeLostAndNoneCountedTwice() {
        val root = temp()
        val core = File(root, "core")
        var t = Telemetry(File(root, "telemetry")) { now }
        var rc = RecitedCount(core, t, { now }, core = { null })
        val s1 = Any()
        rc.takeStarted(s1)
        rc.report(s1, RecitedReport(1, 1, 7))
        rc.takeEnded(s1, true)
        // the process is killed before the take was counted: a new process finds it in the journal
        t = Telemetry(File(root, "telemetry")) { now }
        rc = RecitedCount(core, t, { now }, core = { null })
        rc.flush()
        assertEquals(7.0, body(t)["app_ayahs"])
        // and once more: counted once
        t = Telemetry(File(root, "telemetry")) { now }
        rc = RecitedCount(core, t, { now }, core = { null })
        rc.flush()
        rc.recover()
        assertEquals(7.0, body(t)["app_ayahs"])
        // a take open at the kill, with the app's report journaled: finalised at the next start as recovered
        val s2 = Any()
        rc.takeStarted(s2)
        rc.report(s2, RecitedReport(2, 1, 5))
        t = Telemetry(File(root, "telemetry")) { now }
        rc = RecitedCount(core, t, { now }, core = { null })
        rc.recover()
        val b = body(t)
        assertEquals(12.0, b["app_ayahs"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(1.0, (b["recited_takes"] as Map<String, Any?>)["recovered"])
    }

    @Test
    fun aCancelledTakeCountsNothingAndAnalyticsOffKeepsNoJournal() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        var on = true
        val rc = RecitedCount(File(root, "core"), t, { now }, analytics = { on }, core = { null })
        val s = Any()
        rc.takeStarted(s)
        rc.report(s, RecitedReport(1, 1, 7))
        rc.takeEnded(s, false)
        rc.flush()
        assertNull(body(t)["app_ayahs"])
        on = false
        rc.takeStarted(Any())
        assertFalse(File(root, "core/recited.json").exists())
    }

    @Test
    fun aTakeOverMidnightCountsOnTheDayItStarted() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        val rc = RecitedCount(File(root, "core"), t, { now }, core = { null })
        val s = Any()
        rc.takeStarted(s)
        rc.report(s, RecitedReport(1, 1, 3))
        now += 15 * 3_600_000L // the next UTC day
        rc.takeEnded(s, true)
        rc.flush()
        assertNull("today has nothing", body(t)["app_ayahs"])
        val yesterday = Json.parse(File(root, "telemetry/${Day.of((day0) * 86_400_000L)}.json").readText()) as Map<*, *>
        assertEquals(3.0, (yesterday["m40"] as Map<*, *>)["app_ayahs"])
    }

    @Test
    fun theAppsJudgementPairedWithTheMeasuredAyahs() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        val r = RecitedReport(2, 255, 257, RecitedOutcome.incorrect(2, listOf("madd", "qalqalah")), mapOf(255 to RecitedOutcome.correct(), 300 to RecitedOutcome.correct()))
        t.recordTake(TakeRecord(9, day0, r, found = true, surah = 2, first = 255, last = 257))
        val b = body(t)
        @Suppress("UNCHECKED_CAST")
        val j = b["judged"] as Map<String, Any?>
        assertEquals(3.0, j["ayahs"])
        assertEquals(1.0, j["correct"])
        assertEquals(2.0, j["incorrect"])
        assertEquals(4.0, j["mistakes"])
        assertEquals(3.0, j["first"])
        assertEquals(3.0, j["measured"])
        assertEquals("the perAyah entry outside the measured range", 1.0, j["unpaired"])
        assertEquals(mapOf("2" to 3.0), b["judged_surah_ayahs"])
        assertEquals(mapOf("2" to 1.0), b["correct_surah_ayahs"])
        // each rule of an incorrect ayah: one check and one flag
        assertEquals(mapOf("madd" to 2.0, "qalqalah" to 2.0), b["rule_checks"])
        assertEquals(mapOf("madd" to 2.0, "qalqalah" to 2.0), b["rule_flags"])
        @Suppress("UNCHECKED_CAST")
        val reported = b["reported"] as List<String>
        assertTrue(reported.containsAll(listOf("judged", "judged_surah_ayahs", "correct_surah_ayahs", "rule_checks", "rule_flags")))
        assertFalse(reported.contains("ayahs"))
        // the same ayahs judged again in the session: repeats
        t.recordTake(TakeRecord(10, day0, RecitedReport(2, 255, 255, RecitedOutcome.correct()), found = true, surah = 2, first = 255, last = 255))
        @Suppress("UNCHECKED_CAST")
        val j2 = body(t)["judged"] as Map<String, Any?>
        assertEquals(1.0, j2["repeat"])
        assertEquals(1.0, j2["repeat_correct"])
        assertEquals(1.0, body(t)["repeat_ayahs"])
    }

    @Test
    fun anOutcomeIsCheckedBeforeAnythingIsKept() {
        for (bad in listOf({ RecitedOutcome.incorrect(0) }, { RecitedOutcome.incorrect(1001) }, { RecitedOutcome.incorrect(rules = listOf("Madd")) },
            { RecitedOutcome.incorrect(rules = (1..9).map { "r$it" }) })) {
            try {
                bad()
                fail("accepted")
            } catch (e: TajweedError) {
                assertEquals("invalid_argument", e.code)
            }
        }
    }

    @Test
    fun theLearnersDaysAreSentOnceADay() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        t.zone = java.time.ZoneOffset.UTC
        fun take() {
            t.sessionStart(TajweedObserver.SessionStart("4.1.0", "c16_l64", "cpu"))
            t.sessionEnd(TajweedObserver.SessionEnd(5.0, completed = true, audioMs = 5_000, speechFrames = 100))
        }
        take()
        @Suppress("UNCHECKED_CAST")
        var b = body(t)
        assertEquals(mapOf("active" to 1.0, "new" to 1.0, "d1" to 0.0, "d7" to 0.0, "d30" to 0.0), b["learner"])
        assertEquals(mapOf("new" to 1.0), b["learner_gap"])
        assertEquals(mapOf("1" to 1.0), b["streak"])
        assertEquals(mapOf("le_5" to 1.0), b["take_s"])
        assertEquals(mapOf("10" to 4.0), b["hour_speech_s"])
        // sealed and sent: a later delta of the same day carries no learner facts
        assertEquals(1, t.seal(ctx))
        take()
        assertNull(body(t)["learner"])
        // the next day, again: d1, a gap of 1, a streak of 2
        now += 86_400_000L
        take()
        b = body(t)
        assertEquals(mapOf("active" to 1.0, "new" to 0.0, "d1" to 1.0, "d7" to 0.0, "d30" to 0.0), b["learner"])
        assertEquals(mapOf("1" to 1.0), b["learner_gap"])
        assertEquals(mapOf("2_3" to 1.0), b["streak"])
    }

    @Test
    fun theSwitchesTurnGroupsOff() {
        val root = temp()
        val t = Telemetry(File(root, "telemetry")) { now }
        t.metric = { g -> g != "juz" && g != "health" }
        t.recordTake(TakeRecord(1, day0, null, found = true, surah = 2, first = 255, last = 257))
        t.lease("offline")
        val b = body(t)
        assertEquals(3.0, b["ayahs"])
        assertNull(b["juz_ayahs"])
        assertNull(b["recited_takes"])
        assertNull(b["lease"])
        t.metric = { true }
        t.lease(null)
        t.lease("refused")
        assertEquals(mapOf("ok" to 1.0, "failed" to mapOf("refused" to 1.0)), body(t)["lease"])
    }
}
