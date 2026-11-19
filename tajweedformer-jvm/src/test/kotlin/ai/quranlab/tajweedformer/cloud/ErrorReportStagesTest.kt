package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract section 24 from the failure itself: a stand-in for Quran Lab's server answers the launch and the Client's
 * calls with a 503, a 429, no answer, a config that does not verify, a refused telemetry report; each failure becomes
 * one error report with the stage of its cause, the code the app got (24.4), its count, and is sent at a send point
 * (24.6) as the body of 24.2. Also the answers to a report (202, 400, 503), `flags.issues: false`, and the 30 s send
 * point while the app is in the foreground.
 */
@Suppress("UNCHECKED_CAST")
class ErrorReportStagesTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var open: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-errors-").toFile()
    }

    @After
    fun down() {
        open?.close()
        stub.close()
        dir.deleteRecursively()
    }

    private fun client(): TajweedClient = TajweedClient(stub.key, TajweedClientOptions(),
        ClientPlatform(modelRetry = ai.quranlab.tajweedformer.RetryPlan.NONE, cloud = stub.environment(dir), apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
        autoLaunch = false).also { open = it }

    private fun failure(body: suspend () -> Unit): TajweedError = try {
        runBlocking { body() }
        throw AssertionError("the call did not fail")
    } catch (e: TajweedError) {
        e
    }

    private fun error(b: Map<String, Any?>) = b["error"] as Map<String, Any?>

    /** The section 7 top level of 24.2, and nothing of 24.2's "never". */
    private fun checkBody(b: Map<String, Any?>) {
        assertEquals("error", b["kind"])
        assertEquals("android", b["platform"])
        assertEquals(SdkInfo.VERSION, b["sdk_version"])
        assertEquals("15", b["os_version"])
        assertEquals("Pixel8", b["device_class"])
        for (k in listOf("message", "frames", "detail")) assertTrue(k, k !in b)
        val text = Json.write(b)
        assertTrue(text, !text.contains("://") && !text.contains("127.0.0.1") && !text.contains(stub.appId) && !text.contains(stub.key))
    }

    @Test
    fun aConfigThatAnswers503IsDownloadFailedAndTheLaunchsOwnReportIsApart() {
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(503, "server_error") }
        val c = client()
        // the launch goes on without its config: a silent report, code none
        c.cloud.launch(foreground = false)
        assertEquals(listOf("none"), c.cloud.errors.queued().map { it.code })
        // 24.4: an answer of the server is never needs_connection
        val e = failure { c.model() }
        assertEquals("download_failed", e.code)
        assertTrue(e.message, e.message.contains("503"))
        // the call's report has its own signature, since the code is part of it (24.1)
        val q = c.cloud.errors.queued()
        assertEquals(listOf(listOf("config", "http", 503, "none", 1), listOf("config", "http", 503, "download_failed", 1)),
            q.map { listOf(it.stage, it.reason, it.httpStatus, it.code, it.count) })
        c.cloud.deliver()
        val sent = stub.errorReports()
        assertEquals(listOf("none", "download_failed"), sent.map { it["code"] })
        val b = sent.last()
        checkBody(b)
        assertEquals(mapOf("stage" to "config", "reason" to "http", "http_status" to 503.0, "count" to 1.0, "first_launch" to false,
            "first_report" to true, "ram_tier" to "8_16", "storage_free" to error(b)["storage_free"]), error(b))
        assertTrue(c.cloud.errors.queued().isEmpty())
        assertEquals(2, c.cloud.errorsSent)
    }

    /**
     * release-3.1.6 54235d8, in 4.0 since e66c7b1, shown here from the failure itself: a 401 `invalid_token` that names
     * no stop reason is the token's own answer (24.3; model_gate.json: no error of its own). The token is renewed once
     * and the request repeated once; when the repeat fails too it is reason `http` with status 401 and error_code
     * `invalid_token`, and the call is `download_failed`, never a refusal of the key. One that names a stop reason is.
     */
    @Test
    fun aTokenAnswerThatNamesNoStopIsNotARefusal() {
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(401, "invalid_token") }
        val c = client()
        val e = failure { c.model() }
        assertEquals("download_failed", e.code)
        assertTrue("the token renewed and the config asked again", stub.seen("/v1/config").size >= 2)
        val q = c.cloud.errors.queued().last()
        assertEquals(listOf("config", "http", 401, "download_failed"), listOf(q.stage, q.reason, q.httpStatus, q.code))
        assertEquals("invalid_token", q.detail["error_code"])
        assertEquals("http", ErrorRules.reasonOf(401, "invalid_token", null))
        assertEquals("refused", ErrorRules.reasonOf(401, "invalid_token", "key_revoked"))
    }

    /** 24.4 rule 3a: the token gates the config; with no build on the device its failure decides the call's code. */
    @Test
    fun aTokenRequestAnswered503IsDownloadFailed() {
        stub.overrides["/v1/attest"] = { _, _ -> StubQlab.Reply.error(503, "server_error") }
        val c = client()
        assertEquals("download_failed", failure { c.model() }.code)
        val q = c.cloud.errors.queued().single()
        assertEquals(listOf("attest", "http", 503, "download_failed"), listOf(q.stage, q.reason, q.httpStatus, q.code))
    }

    @Test
    fun aConfigThatIsRateLimitedNamesItsStatusAndTheServersError() {
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(429, "rate_limited") }
        val c = client()
        assertEquals("download_failed", failure { c.model() }.code)
        val q = c.cloud.errors.queued().single()
        assertEquals(listOf("config", "rate_limited", 429, "download_failed", 1), listOf(q.stage, q.reason, q.httpStatus, q.code, q.count))
        assertEquals("rate_limited", q.detail["error_code"])
    }

    @Test
    fun noAnswerAtAllIsNeedsConnectionAndTheLaunchAloneIsNotReported() {
        stub.down = true
        val c = client()
        c.cloud.launch(foreground = false)
        // a plain network failure of a background call is never reported on its own (24.1)
        assertTrue(c.cloud.errors.queued().isEmpty())
        val e = failure { c.model() }
        assertEquals("needs_connection", e.code)
        // the first request of the call that got no answer: the token's attest
        val q = c.cloud.errors.queued().single()
        assertEquals(listOf("attest", "network", null, "needs_connection"), listOf(q.stage, q.reason, q.httpStatus, q.code))
        assertEquals("java.io.IOException", q.detail["error_name"])
        stub.down = false
        c.cloud.deliver()
        val b = stub.errorReports().single()
        checkBody(b)
        assertEquals("java.io.IOException", error(b)["error_name"])
    }

    @Test
    fun aConfigThatDoesNotVerifyIsDownloadFailedNamingIt() {
        stub.tamper = true
        val c = client()
        val e = failure { c.model() }
        assertEquals("download_failed", e.code)
        assertTrue(e.message, e.message.contains("does not verify"))
        val q = c.cloud.errors.queued().single()
        assertEquals(listOf("config", "signature", "download_failed"), listOf(q.stage, q.reason, q.code))
    }

    @Test
    fun aTelemetryReportTheServerRefusedIsReportedOnce() {
        val c = stub.cloud(dir)
        c.refreshConfig()
        c.telemetry.launch()
        c.sendPoint()
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.json(400, mapOf("error" to "invalid_body", "message" to "cohorts")) }
        c.deliver()
        val b = stub.errorReports().single()
        checkBody(b)
        assertEquals("none", b["code"])
        assertEquals(mapOf("stage" to "events", "reason" to "http", "http_status" to 400.0, "error_code" to "invalid_body"),
            error(b).filterKeys { it in setOf("stage", "reason", "http_status", "error_code") })
    }

    @Test
    fun theAnswersKeepDropOrSendAReport() {
        val c = stub.cloud(dir)
        c.refreshConfig()
        c.silent(ErrorCause("storage", "unavailable", errorName = "java.io.FileNotFoundException"))
        // a 5xx keeps it, and the count goes on
        stub.issueAnswer = { StubQlab.Reply.error(503, "unavailable") }
        c.deliver()
        c.silent(ErrorCause("storage", "unavailable"))
        assertEquals(2, c.errors.queued().single().count)
        // a 400 drops it for good, and the signature makes no report again that day
        stub.issueAnswer = { StubQlab.Reply.json(400, mapOf("error" to "invalid_body")) }
        c.deliver()
        assertTrue(c.errors.queued().isEmpty())
        c.silent(ErrorCause("storage", "unavailable"))
        assertTrue(c.errors.queued().isEmpty())
        // a 202 sends the next signature
        stub.issueAnswer = null
        c.silent(ErrorCause("storage", "evicted"))
        c.deliver()
        assertEquals(1, c.errorsSent)
        assertEquals("evicted", error(stub.errorReports().last())["reason"])
    }

    @Test
    fun flagsIssuesFalseDropsTheQueueAndRecordsNothing() {
        val c = stub.cloud(dir)
        c.refreshConfig()
        c.silent(ErrorCause("storage", "unavailable"))
        assertEquals(1, c.errors.queued().size)
        stub.flags = stub.flags + ("issues" to false)
        c.refreshConfig()
        assertTrue(c.errors.queued().isEmpty())
        c.silent(ErrorCause("storage", "evicted"))
        c.deliver()
        assertTrue(c.errors.queued().isEmpty())
        assertTrue(stub.errorReports().isEmpty())
        // a new process with that config keeps them off
        assertTrue(!stub.cloud(dir).errors.enabled)
    }

    @Test
    fun aNewReportIsSentThirtySecondsLaterWhileInTheForeground() {
        val c = stub.cloud(dir, scheduler = CloudScheduler.Inline)
        c.refreshConfig()
        c.errorSendDelayMs = 200
        // in the background: no timer
        c.inForeground = false
        c.silent(ErrorCause("storage", "unavailable"))
        Thread.sleep(600)
        assertTrue(stub.errorReports().isEmpty())
        // in the foreground: one timer, the queue sent at it
        c.inForeground = true
        c.silent(ErrorCause("storage", "evicted"))
        val until = System.currentTimeMillis() + 10_000
        // the stub takes a report before its answer reaches the reporter, which then empties its queue: wait for both
        while ((stub.errorReports().size < 2 || c.errors.queued().isNotEmpty()) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(listOf("unavailable", "evicted"), stub.errorReports().map { error(it)["reason"] })
        assertTrue(c.errors.queued().isEmpty())
    }

    @Test
    fun aFailedDownloadCountsInTelemetryWithItsReason() {
        val c = stub.cloud(dir)
        c.refreshConfig()
        c.onDownload(TajweedObserver.Download("4.0.0", "android-cpu", 0, false, false, failureReason = "http"))
        c.onDownload(TajweedObserver.Download("4.0.0", "android-cpu", 0, false, false, failureReason = "http"))
        c.onDownload(TajweedObserver.Download("4.0.0", "android-cpu", 1000, false, false, failureReason = "hash_mismatch"))
        c.onDownload(TajweedObserver.Download("4.0.0", "android-cpu", 5000, true, false))
        c.sendPoint()
        c.deliver()
        val body = stub.seen("/v1/events").last().json!!
        val d = (body["downloads"] as List<Map<String, Any?>>).single()
        assertEquals(3.0, d["failed"])
        assertEquals(1.0, d["success"])
        assertEquals(mapOf("http" to 2.0, "hash_mismatch" to 1.0), d["failure_reasons"])
    }

    @Test
    fun aStoreThatCannotBeWrittenKeepsTheReportsInMemory() {
        // the cloud folder's place is a file: nothing can be written under it (24.5: they live in memory)
        val blocked = File(dir, "blocked").also { it.writeText("") }
        val r = ErrorReporter(blocked)
        assertTrue(r.occur("2026-11-18", "storage", "write", "storage_failed", null, true))
        assertTrue(!r.occur("2026-11-18", "storage", "write", "storage_failed", null, false))
        assertEquals(2, r.queued().single().count)
        assertNull(blocked.listFiles())
    }
}
