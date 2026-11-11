package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
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

/** The platform client (docs/platform-client.md and the API it talks to) against [StubQlab] on 127.0.0.1. */
class CloudTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var now = System.currentTimeMillis()
    private val clock = { now }

    @Before fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-cloud").toFile()
    }

    @After fun down() {
        // no request of any test carried an Origin header, a key with a token, or a production host
        assertTrue(stub.seen.none { it.headers.containsKey("origin") })
        assertTrue(stub.seen.none { it.headers.containsKey("x-qlab-key") && it.headers.containsKey("authorization") })
        stub.close()
        dir.deleteRecursively()
    }

    private fun cloud(options: CloudOptions = CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
                      integrity: IntegrityProvider? = IntegrityProvider { stub.integrityToken(it) }) =
        QuranLabCloud(options, stub.environment(dir, integrity = integrity, clock = clock))

    @Test fun keyFormat() {
        assertNotNull(QuranLabKey.parse(stub.key))
        assertEquals("testkey01", QuranLabKey.parse(stub.key)!!.keyId)
        assertNull(QuranLabKey.parse("qlk_v1_x_short"))
        assertNull(QuranLabKey.parse("qlt_v1.abc.def"))
        assertFalse(QuranLabKey.parse(stub.key).toString().contains("AAAA"))
    }

    @Test fun registerOncePerUtcDayWithTheNewInstallFlagOnlyFirst() {
        val c = cloud()
        assertTrue(c.register("v4.0+abc"))
        assertTrue(c.register("v4.0+abc"))
        val regs = stub.seen("/v1/register")
        assertEquals("at most once a day", 1, regs.size)
        val b = regs[0].json!!
        assertEquals(setOf("platform", "bundle_id", "app_name", "app_version", "sdk_version", "model_revision", "new_install"), b.keys)
        assertEquals("android", b["platform"])
        assertEquals(SdkInfo.VERSION, b["sdk_version"])
        assertEquals(true, b["new_install"])
        assertTrue(regs[0].headers["authorization"]!!.startsWith("Bearer qlt_v1."))
        // the token came from POST /v1/attest with the key, unattested (sharing is off)
        val att = stub.seen("/v1/attest").single()
        assertEquals(stub.key, att.headers["x-qlab-key"])
        assertEquals(setOf("platform", "bundle_id"), att.json!!.keys)
        // another launch and returns to the foreground the same UTC day: no call
        val c2 = cloud()
        assertTrue(c2.register(null))
        c2.foreground()
        c2.foreground()
        assertEquals(1, stub.seen("/v1/register").size)
        assertEquals(Day.of(now), c2.registeredOn)
        // the next UTC day: one more at the first foreground, not new
        now += 86_400_000
        c2.foreground()
        c2.foreground()
        assertEquals(2, stub.seen("/v1/register").size)
        assertEquals(false, stub.seen("/v1/register")[1].json!!["new_install"])
    }

    @Test fun aFailedFirstRegistrationKeepsTheNewInstallFlag() {
        stub.down = true
        assertFalse(cloud().register(null))
        assertNull("no day stamp without a 200", cloud().registeredOn)
        stub.down = false
        assertTrue(cloud().register(null))
        assertEquals(true, stub.seen("/v1/register").last().json!!["new_install"])
    }

    @Test fun signedConfigIsVerifiedStoredAndConditional() {
        val c = cloud()
        val cfg = c.refreshConfig()
        assertNotNull(cfg)
        assertEquals(SdkInfo.VERSION, stub.seen("/v1/config")[0].q("sdk"))
        assertNotNull(stub.seen("/v1/config")[0].q("bucket"))
        assertEquals("c24_l72", c.inferenceContext)
        assertEquals("v4.0+abc", cfg!!.recommendedRevision)
        assertEquals("4.0.0", cfg.recommendedVersion)
        // the next fetch is conditional: 304 keeps the stored body
        assertNotNull(c.refreshConfig())
        val second = stub.seen("/v1/config")[1]
        assertTrue(second.headers["if-none-match"]!!.startsWith("\""))
        assertEquals("c24_l72", c.inferenceContext)
        // a change on the portal (the Response time): 200 with the new field
        stub.inference = mapOf("context" to "c8_l64")
        c.refreshConfig()
        assertEquals("c8_l64", c.inferenceContext)
        // survives a relaunch, re-verified from disk
        val c2 = cloud()
        assertEquals("c8_l64", c2.inferenceContext)
        // no field: nothing chosen on the portal
        stub.inference = null
        c2.refreshConfig()
        assertNull(c2.inferenceContext)
    }

    @Test fun theModelFolderUrlComesFromTheSignedBaseElseTheCdnModelsPath() {
        val rec = mapOf("model" to "Quran-Lab/tajweedformer-v4", "model_id" to "v4.0", "folder" to "sdk/v4.0", "revision" to "abc")
        fun url(base: Any?) = RemoteConfig(mapOf("recommended" to rec) + (if (base != null) mapOf("base" to base) else emptyMap()), ByteArray(0)).modelBaseUrl()
        assertEquals("https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/abc/sdk/v4.0/", url(null))
        assertEquals("https://mirror.example/Quran-Lab/tajweedformer-v4/abc/sdk/v4.0/", url("https://mirror.example/"))
        assertEquals("a bare cdn base keeps the old /hf/ path", "https://cdn.quranlab.ai/hf/Quran-Lab/tajweedformer-v4/abc/sdk/v4.0/", url("https://cdn.quranlab.ai"))
        assertEquals("not https: ignored", "https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/abc/sdk/v4.0/", url("http://evil.example"))
        assertEquals("the models path is not the bare host: no /hf", "https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/abc/sdk/v4.0/", url("https://cdn.quranlab.ai/models/"))
        assertNull(RemoteConfig(emptyMap(), ByteArray(0)).modelBaseUrl())
        assertEquals("https://eeur.cdn.quranlab.ai/hf/Quran-Lab/m/r/sdk/v4.0/", ai.quranlab.tajweedformer.ModelStore.folderUrl("https://eeur.cdn.quranlab.ai/", "Quran-Lab/m", "r", "sdk/v4.0"))
        assertTrue(ai.quranlab.tajweedformer.ModelStore.DEFAULT_BASE_URL.startsWith("https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/"))
    }

    @Test fun aTamperedOrForeignConfigIsRejectedAndTheLastGoodOneKept() {
        val c = cloud()
        c.refreshConfig()
        stub.inference = mapOf("context" to "c8_l64")
        stub.tamper = true
        c.refreshConfig()
        assertEquals("the last good config stays", "c24_l72", c.inferenceContext)
        assertTrue(c.issues.pending().any { it["kind"] == "signature_invalid" })
        // a config signed by a key the SDK does not pin
        stub.tamper = false
        val other = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(ByteArray(32) { 7 })),
            stub.environment(Files.createTempDirectory("tj-cloud2").toFile(), clock = clock))
        assertNull(other.refreshConfig())
        // verification order and failures, directly
        val body = "{\"v\":1}".toByteArray()
        try { RemoteConfig.verify(body, "ed25519=AAAA", "0000000000000000", listOf(stub.publicKey), 0); throw AssertionError() } catch (e: ConfigRejected) {
            assertTrue(e.message!!.contains("unknown key id"))
        }
    }

    @Test fun anExpiringConfigIsFetchedFreshAndAnExpiredOneIsNotUsed() {
        stub.expiresIn = 1_800
        val c = cloud()
        c.refreshConfig()
        c.refreshConfig()
        assertNull("within an hour of expiry: no If-None-Match", stub.seen("/v1/config")[1].headers["if-none-match"])
        now += 3_600_000
        assertNull("past expires_at the stored config is not used", cloud().currentConfig)
    }

    @Test fun theDenyListForcesABackend() {
        val c = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(dir, clock = clock, device = DeviceProfile("SM-A145", null, 13, "13", "2_4")))
        c.refreshConfig()
        assertEquals("exact", c.forcedBackend("v4.0+abc"))
        assertNull(cloud().forcedBackend("v4.0+abc"))
    }

    @Test fun deviceClassesAreFamiliesNotUnits() {
        assertEquals("Pixel8", DeviceProfile.deviceClass("Pixel 8"))
        assertEquals("SM-S921", DeviceProfile.deviceClass("SM-S921B"))
        assertEquals("5033F", DeviceProfile.deviceClass("5033F"))
        assertEquals("unknown", DeviceProfile.deviceClass(""))
        assertEquals("4_8", DeviceProfile.ramTier(6_000_000_000))
        assertEquals("lt2", DeviceProfile.ramTier(1_000_000_000))
    }

    private fun session(c: QuranLabCloud, seconds: Double = 30.0, backend: String = "cpu", context: String = "c16_l64") {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", context, backend))
        repeat(10) { c.onChunk(12.0 + it, 320.0) }
        c.onTokens(floatArrayOf(0.3f, 1.5f, 9f), floatArrayOf(0.99f, 0.6f))
        c.onSessionEnd(TajweedObserver.SessionEnd(seconds, completed = true, feedbackShown = true, audioMs = (seconds * 1000).toLong(),
            computeUs = 150_400, speechFrames = 100, silenceFrames = 25, tokens = mapOf("log_probs" to 12, "inter1_log_probs" to 0)))
        // what the app reported for the take (SPEC 6.3)
        c.telemetry.recited(1, 2, 2)
        c.telemetry.recited(112, 1, 1)
    }

    private fun body(f: File) = stub.parse(f.readBytes())

    @Suppress("UNCHECKED_CAST")
    @Test fun aDeltaIsSealedAtASendPointWithTheDaysSumsAndThe201Fields() {
        val c = cloud()
        c.refreshConfig()
        // a sheet the learner leaves unanswered (an ask nobody can show is not counted)
        c.sharing.prompts = {}
        c.onModelLoad(TajweedObserver.ModelLoad("v4.0+abc", "c16_l64", "cpu", 800.0, cold = true))
        c.onDownload(TajweedObserver.Download("v4.0+abc", "android-cpu", 72_000_000, success = true, resumed = true))
        session(c)
        session(c, 15.0)
        c.onSelfTest("v4.0+abc", "c16_l64", "cpu", true)
        // nothing is sent before a send point
        assertEquals(0 to 0, c.deliver())
        assertTrue(stub.seen("/v1/events").isEmpty())
        assertTrue(c.telemetry.pendingReports().isEmpty())
        // the app goes to the background: one sealed report, on disk before it is sent
        c.background()
        val sealed = c.telemetry.pendingReports().single()
        val sealedBytes = sealed.readBytes()
        assertTrue(c.telemetry.openDays().isEmpty())
        val (n, _) = c.deliver()
        assertEquals(1, n)
        val ev = stub.seen("/v1/events").single()
        assertArrayEquals("the sealed bytes are the body", sealedBytes, ev.body)
        assertTrue(ev.headers["authorization"]!!.startsWith("Bearer qlt_v1."))
        val b = ev.json!!
        assertEquals(2.0, b["v"])
        assertEquals(Day.of(now), b["day"])
        assertEquals(SdkInfo.VERSION, b["sdk_version"])
        assertTrue(Regex("^[0-9a-f]{32}$").matches(b["report_id"] as String))
        assertTrue(sealed.name.contains(b["report_id"] as String))
        assertEquals(mapOf("class" to "Pixel8", "soc" to "tensor_g3", "os_major" to 15.0, "ram_tier" to "8_16"), b["device"])
        val co = (b["cohorts"] as List<Map<String, Any?>>)
        val main = co.first { (it["sessions"] as Double) > 0 }
        for (gone in listOf("preset", "word_level", "word_bucket", "site_overrides", "checks_on", "classes", "session_flag_pct", "madd", "words")) {
            assertFalse("a 3.0 cohort has no $gone", main.containsKey(gone))
        }
        assertEquals(2.0, main["sessions"])
        assertEquals(0.75, main["minutes"])
        assertEquals(20.0, main["chunks"])
        assertEquals(mapOf("le_20" to 18.0, "le_40" to 2.0), main["latency_ms"])
        assertEquals(mapOf("le_0_1" to 20.0), main["rtf"])
        assertEquals(mapOf("le_250" to 1.0), main["cold_start_ms"])
        assertEquals(mapOf("le_0_5" to 2.0, "le_2" to 2.0, "gt_8" to 2.0), main["margin"])
        assertEquals(mapOf("le_0_7" to 2.0, "gt_0_95" to 2.0), main["confidence"])
        assertEquals(mapOf("pass" to 1.0, "fail" to 0.0), main["self_test"])
        // SPEC 6.2: the SDK-measured counters of the two takes
        assertEquals(2.0, main["takes"])
        assertEquals(45.0, main["audio_s"])
        assertEquals(8.0, main["speech_s"])
        assertEquals(2.0, main["silence_s"])
        assertEquals(0.301, main["compute_s"])
        assertEquals(mapOf("log_probs" to 24.0), main["tokens"])
        assertEquals(mapOf("1" to 2.0, "112" to 2.0), b["coverage"])
        assertEquals(listOf(mapOf("revision" to "v4.0+abc", "profile" to "android-cpu", "bytes" to 7.2E7, "success" to 1.0, "failed" to 0.0, "resumed" to 1.0)), b["downloads"])
        // the delta's counters (zero ones left out); the app-reported ones are listed
        assertEquals(4.0, b["ayahs"])
        assertEquals(mapOf("1" to 2.0, "112" to 2.0), b["surah_ayahs"])
        assertEquals(listOf("ayahs", "surah_ayahs", "coverage"), b["reported"])
        assertFalse(b.containsKey("words"))
        assertEquals("each finished session with feedback asks while the learner has not answered (the config offers sharing, a sheet is registered; 3 at most)", 2.0, b["share_asked"])
        for (k in listOf("launches", "rule_checks", "rule_flags", "share_yes", "share_no")) assertFalse(k, b.containsKey(k))
        // nothing that could identify anyone: only the schema's fields (the stub refuses any other)
        val text = String(ev.body)
        assertFalse(text.contains("org.example.recite"))
        assertFalse(text.contains("Recite"))
        // 202 deleted it; counting starts again from zero: the next send point has nothing to seal
        assertEquals(0 to 0, c.deliver())
        c.background()
        assertTrue(c.telemetry.pendingReports().isEmpty())
        assertEquals(1, stub.seen("/v1/events").size)
    }

    @Test fun aRetrySendsTheSameReportIdAndARefusalDropsIt() {
        val c = cloud()
        session(c)
        c.background()
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(503, "storage_unavailable") }
        assertEquals(0, c.deliver().first)
        assertEquals("503 keeps it", 1, c.telemetry.pendingReports().size)
        // backoff: a minute
        assertEquals(0, c.deliver().first)
        assertEquals(1, stub.seen("/v1/events").size)
        // counts after the seal go into the next delta, not into the waiting one
        session(c, 6.0)
        now += 61_000
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(500, "internal") }
        assertEquals(0, c.deliver().first)
        now += 600_001
        stub.overrides.remove("/v1/events")
        assertEquals(1, c.deliver().first)
        val sent = stub.seen("/v1/events")
        assertEquals(3, sent.size)
        assertEquals("a retry sends the same bytes", sent[0].body.toList(), sent[2].body.toList())
        assertEquals(sent[0].json!!["report_id"], sent[2].json!!["report_id"])
        // the server counts a report once: the same bytes again are a 202 duplicate, and it is deleted all the same
        val again = c.api.events(c.tokens!!.token().token, sent[2].body)
        assertEquals(202, again.status)
        assertEquals(true, again.json()!!["duplicate"])
        // the next delta has its own id
        c.background()
        assertEquals(1, c.deliver().first)
        assertNotEquals(sent[0].json!!["report_id"], stub.seen("/v1/events").last().json!!["report_id"])
        // 400 drops it (and reports the SDK bug), 413 drops it
        session(c)
        c.background()
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(400, "invalid_body") }
        assertEquals(0, c.deliver().first)
        assertTrue("a refused body is never retried", c.telemetry.pendingReports().isEmpty())
        assertTrue("reported, and delivered in the same pass", stub.seen("/v1/issues").any { it.json!!["code"] == "bad_request:events" })
        session(c)
        c.background()
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(413, "too_large") }
        assertEquals(0, c.deliver().first)
        assertTrue(c.telemetry.pendingReports().isEmpty())
    }

    @Test fun anInvalidTokenOnEventsIsRenewedAndTheSameReportSent() {
        val c = cloud()
        session(c)
        c.background()
        stub.overrides["/v1/events"] = { n, _ -> if (n == 1) StubQlab.Reply.error(401, "invalid_token") else null }
        assertEquals(1, c.deliver().first)
        val sent = stub.seen("/v1/events")
        assertEquals(2, sent.size)
        assertEquals(sent[0].body.toList(), sent[1].body.toList())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun noDeltaSpansMidnight() {
        now = (now / 86_400_000L) * 86_400_000L + 23 * 3_600_000L + 50 * 60_000L
        val c = cloud()
        session(c)
        val first = Day.of(now)
        now += 20 * 60_000L
        val second = Day.of(now)
        assertNotEquals(first, second)
        session(c, 15.0)
        c.background()
        val reports = c.telemetry.pendingReports()
        assertEquals(2, reports.size)
        assertEquals(2, c.deliver().first)
        val sent = stub.seen("/v1/events").map { it.json!! }
        assertEquals("oldest first, each under its own day", listOf(first, second), sent.map { it["day"] })
        assertEquals(listOf(1.0, 1.0), sent.map { ((it["cohorts"] as List<Map<String, Any?>>).single())["sessions"] })
        assertEquals(listOf(0.5, 0.25), sent.map { ((it["cohorts"] as List<Map<String, Any?>>).single())["minutes"] })
        assertNotEquals(sent[0]["report_id"], sent[1]["report_id"])
    }

    @Test fun sendPointsAtLaunchEveryFifteenMinutesInUseAndInTheBackground() {
        now = (now / 86_400_000L) * 86_400_000L + 12 * 3_600_000L
        val c = cloud()
        // a cold start: a launch counted, the day's registration, a send point
        c.launch()
        assertEquals(1, stub.seen("/v1/register").size)
        assertEquals(1, c.telemetry.pendingReports().size)
        assertEquals(1, c.deliver().first)
        assertEquals(1.0, stub.seen("/v1/events").last().json!!["launches"])
        session(c)
        now += 14 * 60_000L
        c.tick()
        assertTrue("not before 15 minutes", c.telemetry.pendingReports().isEmpty())
        now += 60_000L
        c.tick()
        assertEquals(1, c.telemetry.pendingReports().size)
        assertEquals(1, c.deliver().first)
        assertFalse(stub.seen("/v1/events").last().json!!.containsKey("launches"))
        c.tick()
        assertTrue("the timer restarted", c.telemetry.pendingReports().isEmpty())
        // the background
        session(c)
        c.background()
        assertEquals(1, c.deliver().first)
        // back in the foreground: a launch, no second registration the same day, the 15 minutes from now
        now += 10 * 60_000L
        c.foreground()
        now += 10 * 60_000L
        c.tick()
        assertTrue(c.telemetry.pendingReports().isEmpty())
        c.background()
        assertEquals(1, c.deliver().first)
        assertEquals(1.0, stub.seen("/v1/events").last().json!!["launches"])
        assertEquals(1, stub.seen("/v1/register").size)
        // leftovers of an earlier run are sealed and sent at the next launch
        session(c)
        val next = cloud()
        next.launch(foreground = false)
        assertEquals(1, next.deliver().first)
        assertFalse(stub.seen("/v1/events").last().json!!.containsKey("launches"))
        assertEquals(1, (stub.seen("/v1/events").last().json!!["cohorts"] as List<*>).size)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun theAppsOwnReportsGiveTheAppReportedFieldsAndTheReportedList() {
        // SPEC 6.3: nothing the SDK measures; counted only because the app said so, and listed in `reported`
        val c = cloud()
        c.onSessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
        c.onSessionEnd(TajweedObserver.SessionEnd(12.0, completed = true))
        c.telemetry.recited(2, 255, 256)
        assertTrue(c.telemetry.checks("vowel_sub", 3, 1))
        assertTrue(c.telemetry.checks("vowel_sub", 3, 1))
        assertTrue(c.telemetry.checks("madd", 4, 2))
        assertTrue(c.telemetry.checks("qalqalah", 4, 2))
        assertTrue(c.telemetry.checks("word", 6, 2))
        assertFalse("nothing checked: nothing counted, no place among the day's rules", c.telemetry.checks("gemination", 0, 0))
        c.background()
        assertEquals(1, c.deliver().first)
        val b = stub.seen("/v1/events").single().json!!
        assertEquals(2.0, b["ayahs"])
        assertEquals(6.0, b["words"])
        assertEquals(mapOf("2" to 2.0), b["surah_ayahs"])
        assertEquals(mapOf("2" to 2.0), b["coverage"])
        val checks = b["rule_checks"] as Map<String, Double>
        val flags = b["rule_flags"] as Map<String, Double>
        assertEquals(mapOf("vowel_sub" to 6.0, "madd" to 4.0, "qalqalah" to 4.0, "word" to 6.0), checks)
        assertEquals(mapOf("vowel_sub" to 2.0, "madd" to 2.0, "qalqalah" to 2.0, "word" to 2.0), flags)
        for ((k, f) in flags) assertTrue("$k: flags <= checks", f <= checks.getValue(k))
        assertEquals(listOf("ayahs", "surah_ayahs", "coverage", "words", "rule_checks", "rule_flags"), b["reported"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun reportedListsExactlyTheAppReportedFieldsPresentInEveryCombination() {
        // the server refuses a body whose `reported` and fields disagree (the stub does the same): every combination
        // of what an app can report gives a list that names exactly the fields the body carries
        for (mask in 0 until 16) {
            stub.close()
            stub = StubQlab()
            dir.deleteRecursively()
            dir = Files.createTempDirectory("tj-cloud").toFile()
            val c = cloud()
            c.onSessionStart(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu"))
            c.onSessionEnd(TajweedObserver.SessionEnd(3.0, completed = true))
            if (mask and 1 != 0) c.telemetry.recited(1, 1, 7)
            if (mask and 2 != 0) c.telemetry.checks("word", 5, 0)
            if (mask and 4 != 0) c.telemetry.checks("madd", 5, 0)
            if (mask and 8 != 0) c.telemetry.checks("qalqalah", 5, 2)
            c.background()
            assertEquals("mask $mask: the server accepted the delta", 1, c.deliver().first)
            val b = stub.seen("/v1/events").single().json!!
            val six = listOf("ayahs", "surah_ayahs", "coverage", "words", "rule_checks", "rule_flags")
            val present = six.filter { b.containsKey(it) }
            if (present.isEmpty()) assertFalse("mask $mask: nothing reported, no field", b.containsKey("reported"))
            else assertEquals("mask $mask", present, b["reported"])
            assertEquals("mask $mask ayahs", mask and 1 != 0, b.containsKey("ayahs"))
            assertEquals("mask $mask words", mask and 2 != 0, b.containsKey("words"))
            assertEquals("mask $mask rule_checks", mask and 14 != 0, b.containsKey("rule_checks"))
            assertEquals("mask $mask rule_flags", mask and 8 != 0, b.containsKey("rule_flags"))
        }
    }
    @Test fun theKillSwitchAndTheDeveloperOptOutDropEverything() {
        val c = cloud()
        session(c)
        c.background()
        stub.flags = stub.flags + ("analytics" to false)
        c.refreshConfig()
        assertFalse(c.telemetry.enabled)
        assertEquals(0, c.deliver().first)
        assertTrue(stub.seen("/v1/events").isEmpty())
        assertTrue(c.telemetry.pendingReports().isEmpty())
        assertTrue(c.telemetry.openDays().isEmpty())

        val off = QuranLabCloud(CloudOptions(stub.key, analytics = CloudOptions.Analytics.OFF, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(Files.createTempDirectory("tj-off").toFile(), clock = clock))
        session(off)
        off.background()
        assertEquals(0, off.deliver().first)
        assertTrue(off.telemetry.pendingReports().isEmpty())
        assertTrue(off.telemetry.openDays().isEmpty())
    }

    @Test fun daysAndReportsOlderThanAWeekAreDropped() {
        val c = cloud()
        session(c)
        c.background()
        session(c)
        now += 9 * 86_400_000L
        assertTrue(c.telemetry.openDays().isEmpty())
        assertTrue(c.telemetry.pendingReports().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun cohortsBeyondSixteenAreMergedIntoTheLargest() {
        val c = cloud()
        for (i in 0 until 20) session(c, backend = if (i < 17) "npu:soc$i" else "cpu")
        c.background()
        assertEquals(1, c.deliver().first)
        val co = stub.seen("/v1/events").single().json!!["cohorts"] as List<Map<String, Any?>>
        assertEquals(16, co.size)
        assertEquals(20.0, co.sumOf { it["sessions"] as Double }, 0.0)
    }

    @Test fun issuesCarryNoTextAndGoOncePerSignatureADay() {
        val c = cloud()
        val e = IllegalStateException("secret user text /data/user/0/org.example")
        c.onIssue("engine_failed", "zf_stream:-3 bad", e)
        c.onIssue("engine_failed", "zf_stream:-3 bad", e)
        c.onIssue("unknown_kind", "x")
        assertEquals(1, c.issues.pending().size)
        val (_, sent) = c.deliver()
        assertEquals(1, sent)
        val b = stub.seen("/v1/issues").single().json!!
        assertEquals("zf_stream:-3bad", b["code"])
        assertFalse(String(stub.seen("/v1/issues").single().body).contains("secret"))
        assertFalse(b.containsKey("message"))
        @Suppress("UNCHECKED_CAST")
        val frames = b["frames"] as List<String>
        assertTrue(frames.isNotEmpty() && frames.all { Regex("^[A-Za-z0-9_]+\\.kt:\\d+$").matches(it) })
        // the next day it may be reported again
        now += 86_400_000
        c.onIssue("engine_failed", "zf_stream:-3 bad", e)
        assertEquals(1, c.issues.pending().size)
    }

    @Test fun anInvalidTokenIsRenewedOnceAndARevokedKeyStopsTheLaunch() {
        val c = cloud()
        c.register(null)
        stub.overrides["/v1/config"] = { n, _ -> if (n == 1) StubQlab.Reply.error(401, "invalid_token") else null }
        assertNotNull(c.refreshConfig())
        assertEquals("a new token after the 401", 2, stub.seen("/v1/attest").size)
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(401, "invalid_key") }
        c.refreshConfig()
        assertTrue(c.networkStopped)
        val before = stub.seen.size
        c.refreshConfig()
        c.deliver()
        assertEquals("nothing more this launch", before, stub.seen.size)
    }

    @Test fun playIntegrityGivesAnAttestedTokenAndARefusalPausesIt() {
        val c = cloud()
        val t = c.tokens!!.token(attested = true)
        assertTrue(t.attested)
        assertEquals("pi", t.via)
        val att = stub.seen("/v1/attest").single().json!!
        assertEquals(setOf("platform", "bundle_id", "challenge", "integrity_token"), att.keys)
        // requestHash = base64url(SHA-256(utf8(challenge))), 43 characters, no padding
        val hash = (att["integrity_token"] as String).removePrefix("pi.")
        assertEquals(43, hash.length)
        assertEquals(b64url(sha256((att["challenge"] as String).toByteArray())), hash)

        stub.integrityRefuses = true
        val d2 = Files.createTempDirectory("tj-pi").toFile()
        val c2 = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(d2, integrity = { stub.integrityToken(it) }, clock = clock))
        try { c2.tokens!!.token(attested = true); throw AssertionError("attested after a refusal") } catch (e: CloudException) {
            assertEquals("attestation_required", e.api!!.code)
        }
        assertTrue(c2.tokens!!.attestationPaused)
        assertFalse(c2.tokens!!.canAttest())
        assertFalse("unattested still works", c2.tokens!!.token(attested = false).attested)
        // no provider at all (the app has no Play Integrity): never attested
        val c3 = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(Files.createTempDirectory("tj-np").toFile(), integrity = null, clock = clock))
        assertFalse(c3.tokens!!.canAttest())
        d2.deleteRecursively()
    }

    @Test fun withoutAKeyNothingIsSent() {
        val c = QuranLabCloud(CloudOptions(null, apiUrl = stub.base), stub.environment(dir, clock = clock))
        c.launch()
        session(c)
        c.background()
        now += 86_400_000
        c.foreground()
        c.deliver()
        assertTrue(stub.seen.isEmpty())
    }

    @Test fun errorTableActions() {
        assertEquals(ErrorAction.STOP_UNTIL_NEXT_LAUNCH, ApiError(401, "invalid_key").action)
        assertEquals(ErrorAction.RENEW_TOKEN, ApiError(401, "invalid_token").action)
        assertEquals(ErrorAction.NEW_CHALLENGE, ApiError(401, "challenge_invalid").action)
        assertEquals(ErrorAction.CONTINUE_UNATTESTED, ApiError(401, "attestation_failed").action)
        assertEquals(ErrorAction.STOP_THIS_LAUNCH, ApiError(403, "sdk_too_old").action)
        assertEquals(ErrorAction.NEW_TOKEN, ApiError(403, "app_mismatch").action)
        assertEquals(ErrorAction.NEED_ATTESTATION, ApiError(403, "attestation_required").action)
        assertEquals(ErrorAction.PAUSE_SHARING, ApiError(403, "contributions_disabled").action)
        assertEquals(ErrorAction.SEGMENT_CAP, ApiError(403, "segment_cap").action)
        assertEquals(ErrorAction.DROP_LOCAL, ApiError(410, "upload_deleted").action)
        assertEquals(ErrorAction.DROP, ApiError(413, "too_large").action)
        assertEquals(ErrorAction.BACKOFF, ApiError(429, "rate_limited").action)
        assertEquals(ErrorAction.RETRY_NEXT_LAUNCH, ApiError(503, "integrity_unavailable").action)
        assertEquals(ErrorAction.DROP_AND_REPORT, ApiError(400, "invalid_body").action)
        assertTrue(Json.write(mapOf("a" to 1)).isNotEmpty())
    }
}
