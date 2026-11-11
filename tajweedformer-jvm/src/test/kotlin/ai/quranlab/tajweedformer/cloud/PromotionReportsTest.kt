package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.AcceleratorFailoverEvent
import ai.quranlab.tajweedformer.AcceleratorIncident
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.PromotionEvent
import ai.quranlab.tajweedformer.BlankConfirmedEvent
import ai.quranlab.tajweedformer.accel.BlankCheck
import ai.quranlab.tajweedformer.accel.Demotion
import ai.quranlab.tajweedformer.accel.Divergence
import ai.quranlab.tajweedformer.accel.FailoverEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The `promotions` telemetry entry and the accelerator incident reports (platform client doc, sections 6 and 7.1):
 * the entry for a promoted attempt, a failure at each stage and failovers; the issue bodies of both kinds validated
 * against the closed 7.1 schema, the path-stripped message, the first-report-per-revision rule across the dedupe
 * window, nothing identifying, and a 2.0.0 server's 400 dropping the report.
 */
class PromotionReportsTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var now = 1_762_700_000_000L
    private val clock = { now }

    @Before fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-promo").toFile()
    }

    @After fun down() {
        stub.close()
        dir.deleteRecursively()
    }

    private fun cloud() = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
        stub.environment(dir, clock = clock))

    private val ctx = TelemetryContext(AppIdentity("org.example.recite", "3.2"), DeviceProfile("Pixel10", "tensor_g5", 16, "16", "8_16"))

    @Suppress("UNCHECKED_CAST")
    private fun promotions(t: Telemetry): List<Map<String, Any?>> {
        t.seal(ctx)
        val f = t.pendingReports().lastOrNull() ?: return emptyList()
        val body = Json.parse(f.readText()) as Map<String, Any?>
        t.dropReport(f)
        return body["promotions"] as? List<Map<String, Any?>> ?: emptyList()
    }

    private fun incident(kind: String, stage: String, cause: String, rev: String = "v4.0-2026-11-09", error: Throwable? = null, attempt: Int? = 1,
                         chunk: Int? = null, stall: Double? = null, clip: String? = null, divergence: Divergence? = null) =
        AcceleratorIncident(kind, stage, cause, rev, "c16_l64", "npu:tensor_g5", error = error, message = error?.message, chunk = chunk,
            elapsedMs = if (chunk != null) 301.4 else null, deadlineMs = if (chunk != null) 120.0 else null, clip = clip, divergence = divergence,
            deviceModel = "Pixel 10", osBuild = "16/BD1A.250101.001/12345678", chip = "tensor_g5", computeUnits = "npu",
            delegate = "LiteRtDispatch_GoogleTensor litert 2.2.0", thermal = "light", lowPower = false, stallMs = stall, attempt = attempt)

    private fun promotion(promoted: Boolean, stage: String, reason: String?, compileMs: Long? = null, speedup: Double? = null) =
        PromotionEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", promoted, stage, reason, 1, compileMs, speedup,
            incident = if (promoted) null else incident(AcceleratorIncident.PROMOTION_FAILED, stage, AcceleratorIncident.cause(reason!!)))

    @Test fun theEntryOfAPromotedAttempt() {
        val t = Telemetry(File(dir, "t"), clock)
        t.promotion(promotion(true, "promote", null, compileMs = 3200, speedup = 0.8))
        val p = promotions(t).single()
        assertEquals(mapOf("backend" to "npu:tensor_g5", "revision" to "v4.0-2026-11-09", "context" to "c16_l64", "attempts" to 1.0, "promoted" to 1.0,
            "compile_ms" to mapOf("le_5000" to 1.0), "speedup" to mapOf("lt_1" to 1.0)), p)
    }

    @Test fun theEntryOfAFailureAtEachStage() {
        for ((stage, reason) in listOf("download" to "network", "download" to "verify", "compile" to "compile_timeout", "warmup" to "non_finite",
            "golden" to "golden_mismatch", "golden" to "cpu_mismatch")) {
            val t = Telemetry(File(dir, "t-$stage-$reason"), clock)
            t.promotion(promotion(false, stage, reason, compileMs = if (stage == "golden") 900 else null))
            val p = promotions(t).single()
            val want = linkedMapOf<String, Any?>("backend" to "npu:tensor_g5", "revision" to "v4.0-2026-11-09", "context" to "c16_l64",
                "attempts" to 1.0, "failed_at" to mapOf(stage to 1.0), "reasons" to mapOf(reason to 1.0))
            if (stage == "golden") want["compile_ms"] = mapOf("le_1000" to 1.0)
            assertEquals("$stage $reason", want, p)
        }
    }

    /** Every switch counts in `failovers` and its cause (a single missed limit too), `demoted` and `reasons.failover`
     *  only the switches that demoted the key. */
    @Test fun failoversCountTheirCausesAndStalls() {
        val t = Telemetry(File(dir, "t"), clock)
        for ((cause, stall, demotion) in listOf(Triple("deadline", 40.0, Demotion.NONE), Triple("deadline", 180.0, Demotion.PAUSED),
            Triple("non_finite", 260.0, Demotion.OFF), Triple("blank", 1400.0, Demotion.PAUSED))) {
            val ev = FailoverEvent(cause, 3, stall, 10.0, 120.0, null, warm = false, replayedChunks = 3, exact = true, demotion = demotion)
            t.failover(AcceleratorFailoverEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", ev,
                incident(AcceleratorIncident.FAILOVER, "session", AcceleratorIncident.cause(cause), chunk = 3, stall = stall)))
        }
        val p = promotions(t).single()
        assertEquals(4.0, p["failovers"])
        assertEquals(mapOf("deadline" to 2.0, "non_finite" to 1.0, "blank" to 1.0), p["failover_causes"])
        assertEquals(mapOf("le_50" to 1.0, "le_200" to 1.0, "le_300" to 1.0, "gt_1000" to 1.0), p["failover_stall_ms"])
        assertEquals(mapOf("failover" to 3.0), p["reasons"])
        assertEquals(3.0, p["demoted"])
        assertFalse("no attempt ended", p.containsKey("attempts"))
    }

    /** A blank the CPU confirmed: `blank_confirmed_ok`, and nothing else in the entry. */
    @Test fun blanksTheCpuConfirmedCountInBlankConfirmedOk() {
        val t = Telemetry(File(dir, "t"), clock)
        repeat(3) { t.blankConfirmed(BlankConfirmedEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", BlankCheck(4, 16, true, 0, 0, 120.0, false))) }
        assertEquals(mapOf("backend" to "npu:tensor_g5", "revision" to "v4.0-2026-11-09", "context" to "c16_l64", "blank_confirmed_ok" to 3.0),
            promotions(t).single())
    }

    /**
     * The developer who ran offline and never called configure got no report: before 2.0.2 the observer was a no-op
     * until configure. Now incidents are kept on the device (7 days, at most 32) and sent once the app configures with
     * its key and the network is there.
     */
    @Suppress("UNCHECKED_CAST")
    @Test fun incidentsMadeBeforeConfigureAreKeptAndSentAfterItWhenOnline() {
        stub.incidentsAccepted = true
        val spool = IncidentSpool(File(dir, "cloud"), clock)
        val offline = OfflineIncidents(spool)
        val ev = FailoverEvent("blank", 4, 90.0, 140.0, 0.0, null, warm = false, replayedChunks = 4, exact = true, demotion = Demotion.PAUSED,
            divergence = Divergence(5, tokensDiffer = 5))
        offline.onFailover(AcceleratorFailoverEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", ev,
            incident(AcceleratorIncident.FAILOVER, "session", "blank", chunk = 4, stall = 90.0, divergence = Divergence(5, tokensDiffer = 5), error = thrower())))
        offline.onPromotion(promotion(false, "golden", "golden_mismatch"))
        offline.onPromotion(promotion(true, "promote", null))
        assertEquals(2, spool.pending().size)
        // kept on the device as the report may carry it: the message already path-stripped
        assertTrue(File(dir, "cloud/incidents-offline.json").readText().let { !it.contains("/data/user") && !it.contains("https://") })
        // a day later the app configures Quran Lab, still offline: queued, not sent
        now += 86_400_000L
        stub.down = true
        val c = cloud()
        assertTrue(spool.pending().isEmpty())
        assertEquals(listOf("session:blank", "golden:golden_mismatch"), c.issues.pending().map { it["code"] })
        assertEquals(0, c.deliver().second)
        assertEquals(2, c.issues.pending().size)
        // online: sent, each with the day it happened
        stub.down = false
        assertEquals(2, c.deliver().second)
        val sent = stub.seen("/v1/issues").map { it.json!! }
        sent.forEach { validate(it) }
        assertEquals(listOf("2025-11-09", "2025-11-09"), sent.map { it["day"] })
        // the 3.0 wire: a blank check has no raw measures, and the 2.x tokens_differ, sites_differ, words_differ are refused
        assertEquals(mapOf("tokens_expected" to 5.0), (sent[0]["detail"] as Map<String, Any?>)["divergence"])
        assertTrue(c.issues.pending().isEmpty())
    }

    @Test fun incidentsAreKeptSevenDaysAtMost() {
        val spool = IncidentSpool(File(dir, "cloud"), clock)
        repeat(40) { spool.add(incident(AcceleratorIncident.FAILOVER, "session", "timeout", chunk = it), emptyList()) }
        assertEquals("at most 32 kept, the newest", 32, spool.pending().size)
        assertEquals(39.0, (spool.pending().last()["detail"] as Map<*, *>)["chunk"])
        now += 8 * 86_400_000L
        assertTrue("a week old: dropped", spool.pending().isEmpty())
        // in the issue queue too: an incident report waiting more than 7 days is dropped
        val c = cloud()
        c.reportIncident(incident(AcceleratorIncident.FAILOVER, "session", "nan", chunk = 3))
        assertEquals(1, c.issues.pending().size)
        now += 8 * 86_400_000L
        assertTrue(c.issues.pending().isEmpty())
        assertEquals(null, c.issues.firstToSend())
    }

    @Test fun anAppWithIssueReportsOffDropsTheKeptIncidents() {
        val spool = IncidentSpool(File(dir, "cloud"), clock)
        spool.add(incident(AcceleratorIncident.FAILOVER, "session", "timeout", chunk = 1), emptyList())
        val c = QuranLabCloud(CloudOptions(stub.key, issueReports = false, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(dir, clock = clock))
        assertTrue(c.issues.pending().isEmpty())
        assertTrue(spool.pending().isEmpty())
    }

    @Test fun atMostEightEntriesAndNothingWhenNothingHappened() {
        val t = Telemetry(File(dir, "t"), clock)
        assertTrue(promotions(t).isEmpty())
        for (i in 0 until 11) t.promotion(PromotionEvent("npu:soc$i", "r", "c16_l64", true, "promote", null, 1))
        assertEquals(8, promotions(t).size)
        // what is counted survives the day file (a new process seals it)
        t.promotion(promotion(true, "promote", null))
        t.flushToDisk()
        assertEquals(1, promotions(Telemetry(File(dir, "t"), clock)).size)
    }

    // ------------------------------------------------------------------------------------------- incident reports

    /** The closed 7.1 schema: the section 7 body with `message` and `detail`, `backend` left out, every detail field typed. */
    @Suppress("UNCHECKED_CAST")
    private fun validate(b: Map<String, Any?>) {
        val top = setOf("day", "platform", "sdk_version", "app_version", "os_version", "device_class", "revision", "kind", "code", "frames", "message", "detail")
        assertTrue("unknown fields ${b.keys - top}", (b.keys - top).isEmpty())
        assertFalse(b.containsKey("backend"))
        assertTrue(b["kind"] in setOf("accelerator_failover", "promotion_failed"))
        assertTrue(Regex("^[A-Za-z0-9_.:-]{1,64}$").matches(b["code"] as String))
        assertTrue(b["revision"] is String)
        (b["message"] as String?)?.let { m ->
            assertTrue(m.length <= 200)
            assertFalse("a path in the message: $m", Regex("[\\\\/]").containsMatchIn(m))
            assertFalse("a URL in the message: $m", m.contains("://"))
            assertFalse("a long digit run in the message: $m", Regex("\\d{5,}").containsMatchIn(m))
        }
        val d = b["detail"] as Map<String, Any?>
        val types = mapOf<String, (Any?) -> Boolean>(
            "stage" to { v -> v in setOf("download", "compile", "warmup", "golden", "session") },
            "cause" to { v -> v in setOf("exception", "nan", "out_of_range", "blank", "timeout", "crash_on_last_run", "golden_mismatch", "cpu_mismatch",
                "compile_error", "compile_timeout", "load_error", "verify", "network", "storage", "other") },
            "error_domain" to { v -> v is String && Regex("^[A-Za-z0-9_.:-]{1,64}$").matches(v) },
            "error_code" to { v -> v is String && Regex("^[A-Za-z0-9_.:-]{1,32}$").matches(v) },
            "chunk" to { v -> v is Double && v == Math.floor(v) && v in 0.0..1_000_000.0 },
            "elapsed_ms" to { v -> v is Double && v in 0.0..600_000.0 },
            "deadline_ms" to { v -> v is Double && v in 0.0..600_000.0 },
            "clip" to { v -> v is String && v.length <= 32 },
            "divergence" to { v -> v is Map<*, *> && v.keys.all { it in setOf("tokens_expected", "heads", "tolerance", "failed") } &&
                (v["tokens_expected"] as? Double)?.let { it == Math.floor(it) && it in 0.0..100_000.0 } != false &&
                (v["heads"] as? Map<*, *>)?.all { (k, h) -> Regex("^[a-z][a-z0-9_]{0,31}$").matches(k as String) && (h == null || h is Map<*, *>) } != false &&
                (v["failed"] as? List<*>)?.all { it is String && Regex("^[a-z][a-z0-9_]{0,31}\\.[a-z0-9_]+$").matches(it) } != false },
            "device_model" to { v -> v is String && Regex("^[A-Za-z0-9_.,-]{1,32}$").matches(v) },
            "os_build" to { v -> v is String && Regex("^[A-Za-z0-9_.:/-]{1,64}$").matches(v) },
            "chip" to { v -> v is String && Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,23}$").matches(v) },
            "accelerator" to { v -> v is String && Regex("^(ane|gpu|npu:[A-Za-z0-9][A-Za-z0-9_.-]{0,23})$").matches(v) },
            "compute_units" to { v -> v in setOf("cpuAndNeuralEngine", "cpuAndGPU", "all", "cpuOnly", "npu", "gpu") },
            "delegate" to { v -> v is String && Regex("^[A-Za-z0-9_.:/ -]{1,48}$").matches(v) },
            "context" to { v -> v is String && Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,23}$").matches(v) },
            "thermal" to { v -> v in setOf("nominal", "fair", "serious", "critical", "none", "light", "moderate", "severe", "emergency", "shutdown") },
            "low_power" to { v -> v is Boolean },
            "stall_ms" to { v -> v is Double && v == Math.floor(v) && v in 0.0..600_000.0 },
            "attempt" to { v -> v is Double && v == Math.floor(v) && v in 1.0..100.0 },
        )
        assertTrue("detail.stage and detail.cause are required", d.containsKey("stage") && d.containsKey("cause"))
        for ((k, v) in d) {
            val ok = types[k] ?: fail("detail.$k is not in the schema")
            assertTrue("detail.$k = $v", (ok as (Any?) -> Boolean)(v))
        }
        assertEquals(b["code"], "${d["stage"]}:${d["cause"]}")
        // nothing identifying: no ids of a user, install or contributor, no audio, no text of the learner
        val text = Json.write(b)
        for (bad in listOf("install", "user", "contributor", "audio", "transcript", "token_text")) assertFalse("$bad in $text", text.contains("\"$bad"))
    }

    private fun thrower(): Throwable = try {
        throw IllegalStateException("LiteRtRunCompiledModel failed (status 13) for /data/user/0/org.example.recite/no_backup/tajweedformer/npu/" +
            "tensor_g5/c16_l64/model.tflite at 1762700000123, see https://example.org/x?id=99999")
    } catch (e: IllegalStateException) {
        e
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun bothKindsValidateAgainstTheClosedSchema() {
        stub.incidentsAccepted = true
        val c = cloud()
        val ev = FailoverEvent("deadline", 7, 182.0, 301.4, 120.0, null, warm = false, replayedChunks = 7, exact = true)
        c.onFailover(AcceleratorFailoverEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", ev,
            incident(AcceleratorIncident.FAILOVER, "session", "timeout", error = thrower(), chunk = 7, stall = 182.4)))
        for ((stage, reason) in listOf("download" to "network", "compile" to "compile_error", "warmup" to "non_finite", "golden" to "golden_mismatch")) {
            c.onPromotion(PromotionEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", false, stage, reason, 2, incident =
                incident(AcceleratorIncident.PROMOTION_FAILED, stage, AcceleratorIncident.cause(reason), error = thrower(), attempt = 2,
                    clip = if (stage == "golden") "Husary_112001_vsub2" else null,
                    divergence = if (stage == "golden") Divergence(16,
                        mapOf("log_probs" to ai.quranlab.tajweedformer.SelfTestSet.HeadMeasure(1, 0.07, 2.4, 310)), 0.03, 3100,
                        ai.quranlab.tajweedformer.ModelManifest.RawTolerance(mapOf("log_probs" to ai.quranlab.tajweedformer.ModelManifest.HeadTolerance(0, 0.05, 1.5)), 0.02),
                        listOf("log_probs.token_edits", "log_probs.lp_p99", "attr.p_mean"), tokensDiffer = 1) else null)))
        }
        // a promoted attempt is no incident
        c.onPromotion(PromotionEvent("npu:tensor_g5", "v4.0-2026-11-09", "c16_l64", true, "promote", null, 1))
        assertEquals(5, c.issues.pending().size)
        assertEquals(5, c.deliver().second)
        val sent = stub.seen("/v1/issues").map { it.json!! }
        assertEquals(listOf("session:timeout", "download:network", "compile:compile_error", "warmup:nan", "golden:golden_mismatch"), sent.map { it["code"] })
        sent.forEach { validate(it) }
        val f = sent[0]
        assertEquals("accelerator_failover", f["kind"])
        val d = f["detail"] as Map<String, Any?>
        assertEquals(7.0, d["chunk"])
        assertEquals(182.0, d["stall_ms"])
        assertEquals(301.4, d["elapsed_ms"])
        assertEquals(120.0, d["deadline_ms"])
        assertEquals("Pixel_10", d["device_model"])
        assertEquals("IllegalStateException", d["error_domain"])
        assertEquals("LiteRtRunCompiledModel failed (status 13) for _ at _, see _", f["message"])
        val g = sent[4]["detail"] as Map<String, Any?>
        assertEquals("Husary_112001_vsub2", g["clip"])
        // the server's shape for SDK 3.0: the raw measures per head, the tolerance they were judged against, what failed
        assertEquals(mapOf("tokens_expected" to 16.0,
            "heads" to mapOf("log_probs" to mapOf("token_edits" to 1.0, "lp_mean" to 0.07, "lp_p99" to 2.4, "frames" to 310.0),
                "attr" to mapOf("p_mean" to 0.03, "values" to 3100.0)),
            "tolerance" to mapOf("log_probs" to mapOf("token_edits" to 0.0, "lp_mean" to 0.05, "lp_p99" to 1.5), "attr" to mapOf("p_mean" to 0.02)),
            "failed" to listOf("log_probs.token_edits", "log_probs.lp_p99", "attr.p_mean")), g["divergence"])
        assertEquals(2.0, g["attempt"])
    }

    @Test fun theMessageIsPathStripped() {
        assertEquals("open _ failed: _", IssueQueue.stripMessage("open /data/app/~~x==/lib/arm64/libLiteRt.so failed: dlopen 2"
            // a Windows path, its drive letter joined at run time
            .replace("dlopen 2", "Q" + ":\\models\\x.dll")))
        assertEquals("status _ at _", IssueQueue.stripMessage("status 1234567 at content://media/external/1"))
        assertEquals("plain message", IssueQueue.stripMessage("plain message"))
        assertEquals("_", IssueQueue.stripMessage("/only/a/path"))
        assertNull(IssueQueue.stripMessage(1.toChar().toString()))
        assertEquals(200, IssueQueue.stripMessage("x".repeat(500))!!.length)
    }

    @Test fun theFirstReportOfEachKindPerRevisionGoesThroughTheDedupe() {
        val c = cloud()
        fun failover(rev: String) = c.reportIncident(incident(AcceleratorIncident.FAILOVER, "session", "nan", rev = rev, chunk = 3))
        failover("rev-a")
        failover("rev-a")
        assertEquals("the same signature twice a day: once", 1, c.issues.pending().size)
        // another revision, the same signature, the same day: its first report goes out anyway
        failover("rev-b")
        assertEquals(2, c.issues.pending().size)
        failover("rev-b")
        assertEquals(2, c.issues.pending().size)
        // the other kind on rev-a is its own first
        c.reportIncident(incident(AcceleratorIncident.PROMOTION_FAILED, "golden", "golden_mismatch", rev = "rev-a"))
        assertEquals(3, c.issues.pending().size)
        // a first report is never trimmed from the queue while it waits
        for (i in 0 until 40) c.onIssue("engine_failed", "code$i")
        val kinds = c.issues.pending().map { it["kind"] }
        assertEquals(32, kinds.size)
        assertEquals(3, kinds.count { it == "accelerator_failover" || it == "promotion_failed" })
        // the queue's own marker never reaches the server
        assertTrue(c.issues.pending().any { it.containsKey(IssueQueue.PIN) })
        assertFalse(c.issues.firstToSend()!!.containsKey(IssueQueue.PIN))
        // the next day the usual dedupe lets one through again
        now += 86_400_000L
        fun aFailovers() = c.issues.pending().count { it["revision"] == "rev-a" && it["kind"] == "accelerator_failover" }
        val before = aFailovers()
        failover("rev-a")
        assertEquals(before + 1, aFailovers())
    }

    @Test fun aServerWithoutTheIncidentSchemaDropsTheReportWithoutALoop() {
        stub.incidentsAccepted = false
        val c = cloud()
        c.reportIncident(incident(AcceleratorIncident.FAILOVER, "session", "exception", chunk = 0, error = thrower()))
        assertEquals(0, c.deliver().second)
        assertEquals("refused (400): dropped", 0, c.issues.pending().size)
        assertEquals(1, stub.seen("/v1/issues").size)
        c.deliver()
        assertEquals("not sent again", 1, stub.seen("/v1/issues").size)
    }

    @Test fun issuesOffSendsNoIncident() {
        stub.flags = stub.flags + ("issues" to false)
        val c = cloud()
        c.refreshConfig()
        c.reportIncident(incident(AcceleratorIncident.FAILOVER, "session", "exception", chunk = 0))
        assertTrue(c.issues.pending().isEmpty())
    }
}
