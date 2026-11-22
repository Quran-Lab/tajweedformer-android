package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.sharing.OggTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 4.0.2 under load (the Ramadan capacity model): contract 28.10, optional traffic steps back after a 429 (events,
 * issues and error reports, shared-recording uploads), and 18.4.7.3's steps of the lease backoff with a `Retry-After`
 * as a floor. Through [QuranLabCloud] and [StubQlab] on a clock the test moves; the lease's run on the real Core is
 * LeaseBackoffTest. That a 429 asks no other host is EndpointsHttpTest.a429OnTheFirstHostIsTheAnswerAndNoOtherHostIsAsked
 * and the slow_down cases of EndpointsVectorsTest (28.3).
 */
class SlowDownTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()
    private var now = 1_800_000_000_000L

    @Before
    fun up() {
        stub = StubQlab()
    }

    @After
    fun down() {
        stub.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-slowdown-").toFile().also { dirs += it }

    private fun reply429(retryAfter: Int?) = StubQlab.Reply(429, """{"error":"rate_limited","message":"slow down"}""".toByteArray(),
        mapOf("content-type" to "application/json") + (retryAfter?.let { mapOf("retry-after" to it.toString()) } ?: emptyMap()))

    @Test
    fun theLeaseBackoff() {
        // 18.4.7.3 items 1 and 2: after every failed attempt a fresh draw from 600 to 1800 s; a Retry-After is the floor
        assertEquals(600_000L, LeaseClient.backoffMs(1, 0) { 0.0 })
        assertTrue(LeaseClient.backoffMs(1, 0) { 0.999999 } in 600_000L..1_800_000L)
        val rnd = java.util.Random(7)
        // a run of 503 lease_unavailable with Retry-After: 30: every wait in [600, 1800] s, never 30, not twice the same
        val run = (1..12).map { n -> LeaseClient.backoffMs(n, 30) { rnd.nextDouble() } }
        assertTrue("$run", run.all { it in 600_000L..1_800_000L })
        assertTrue(run.none { it == 30_000L })
        assertEquals("drawn afresh: $run", run.size, run.toSet().size)
        assertEquals(3_000_000L, LeaseClient.backoffMs(1, 3000) { 0.5 })
        assertEquals(3_000_000L, LeaseClient.backoffMs(7, 3000) { 0.5 })
        // a Retry-After outside 1 to 21600 is none
        assertTrue(LeaseClient.backoffMs(1, 90_000) { 0.5 } in 600_000L..1_800_000L)
    }

    @Test
    fun aRouteWaitsTheRetryAfterThenDoublesInARowUpTo3600s() {
        val s = SlowDown({ now })
        // three 429s in a row without Retry-After: 300, 600, 1200 s
        assertEquals(listOf(300_000L, 600_000L, 1_200_000L), (1..3).map { s.slowDown(null) })
        assertEquals(listOf(2_400_000L, 3_600_000L, 3_600_000L), (1..3).map { s.slowDown(null) })
        now = s.notBeforeMs
        s.ok()
        assertEquals("an accepted answer ends the row", 120_000L, s.slowDown(120))
        assertEquals(now + 120_000L, s.notBeforeMs)
        // a Retry-After above the cap is still honoured
        val t = SlowDown({ now })
        assertEquals(5_000_000L, t.slowDown(5000))
        // a 503 with a Retry-After holds the route without growing the row
        val u = SlowDown({ now })
        u.answered(503, 90)
        assertEquals(0, u.row)
        assertTrue(!u.allowed())
        now += 90_000
        assertTrue(u.allowed())
    }

    /** 28.11 item 3: a route's row and its wait are kept across restarts: a new process waits as the old one would have. */
    @Test
    fun theRowIsKeptAcrossARestart() {
        val f = File(temp(), "holds.json")
        val s = SlowDown({ now }, RouteHolds(f, { now }), "sharing_uploads")
        assertEquals(300_000L, s.slowDown(null))
        now += 10_000
        val again = SlowDown({ now }, RouteHolds(f, { now }), "sharing_uploads")
        assertEquals(1, again.row)
        assertTrue("allowed 10 s after the 429 in a new process", !again.allowed())
        now += 291_000
        assertTrue(again.allowed())
        assertEquals("the row goes on: 600 s", 600_000L, again.slowDown(null))
    }

    /** 28.10 item 4: a 429 with Retry-After: 120 on events; the send point 30 s later sends nothing, the one after 120 s sends. */
    @Test
    fun eventsWaitTheRetryAfterOfA429() {
        val c = stub.cloud(temp(), clock = { now })
        c.refreshConfig()
        c.foreground()
        assertTrue("a report to send", c.telemetry.seal(TelemetryContext(c.env.app, c.env.device)) > 0 || c.telemetry.pendingReports().isNotEmpty())
        stub.overrides["/v1/events"] = { n, _ -> if (n == 1) reply429(120) else null }
        c.deliver()
        assertEquals(1, stub.seen("/v1/events").size)
        assertTrue(c.telemetry.pendingReports().isNotEmpty())
        now += 30_000
        c.deliver()
        assertEquals("a request 30 s after the 429", 1, stub.seen("/v1/events").size)
        // a return to the foreground resets telemetry's own backoff, never a server's Retry-After
        c.foreground()
        c.deliver()
        assertEquals("a request after a return to the foreground", 1, stub.seen("/v1/events").size)
        now += 91_000
        c.deliver()
        assertEquals("the send point after 120 s", 2, stub.seen("/v1/events").size)
        assertTrue("the report kept and sent", c.telemetry.pendingReports().isEmpty())
    }

    /** 28.10 item 2: issue and error reports wait a 429 the same way (300 s without a Retry-After). */
    @Test
    fun issuesWaitA429() {
        val c = stub.cloud(temp(), clock = { now })
        c.refreshConfig()
        stub.overrides["/v1/issues"] = { n, _ -> if (n == 1) reply429(null) else null }
        c.report("other", "test_code")
        c.deliver()
        val first = stub.seen("/v1/issues").size
        assertEquals(1, first)
        now += 299_000
        c.deliver()
        assertEquals("a request inside the 300 s", 1, stub.seen("/v1/issues").size)
        now += 2_000
        c.deliver()
        assertTrue("the send point after 300 s", stub.seen("/v1/issues").size > 1)
    }

    /** 28.10 item 4: three 429s in a row without Retry-After on uploads give waits of 300, 600 and 1200 s. */
    @Test
    fun threeUpload429sWait300Then600Then1200() {
        stub.flags = stub.flags + mapOf("share_flac" to true, "share_join_ms" to 0)
        // the stand-in's signed config is issued on the real clock: sharing needs it in force
        now = System.currentTimeMillis()
        val c = stub.cloud(temp(), clock = { now })
        c.refreshConfig()
        if (!c.sharing.isOn) c.sharing.accept()
        // a finished shared recording: 12 s of Ogg FLAC at 16 kHz (no Opus library needed)
        c.onSessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu", sampleRate = 16_000, inputRoute = "built_in", decodeBackend = "mock"))
        val x = OggTestKit.noise(16_000 * 12, seed = 11)
        var at = 0
        while (at < x.size) {
            val k = minOf(320, x.size - at)
            c.onAudio(x, at, k, 16_000)
            at += k
        }
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true, speechFrames = 250))
        now += 61_000
        c.sharing.checkJoinWindow()
        c.sharing.lastCapture?.await(10_000)
        stub.overrides["/v1/uploads"] = { _, q -> if (q.method == "POST") reply429(null) else null }
        val out = c.sharing.outbox
        val waits = ArrayList<Long>()
        repeat(3) { i ->
            out.drain(ignoringBackoff = true)
            assertEquals("one start asked per pass", i + 1, stub.starts().size)
            waits += out.slowDown.notBeforeMs - now
            // a pass inside the wait asks nothing, also an explicit retry
            out.drain(ignoringBackoff = true)
            assertEquals(i + 1, stub.starts().size)
            now += waits.last() + 1_000
        }
        assertEquals(listOf(300_000L, 600_000L, 1_200_000L), waits)
        // the recording is kept for later (nothing dropped)
        assertEquals(1, c.sharing.items.size)
    }
}
