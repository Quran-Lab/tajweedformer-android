package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The platform client (PLATFORM_CLIENT.md sections 2 to 8) against [StubQlab] on 127.0.0.1. */
class CloudTest {
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

    @Test fun registerOncePerLaunchWithTheNewInstallFlagOnlyFirst() {
        val c = cloud()
        assertTrue(c.register("v4.0+abc"))
        assertTrue(c.register("v4.0+abc"))
        val regs = stub.seen("/v1/register")
        assertEquals("at most once per launch", 1, regs.size)
        val b = regs[0].json!!
        assertEquals(setOf("platform", "bundle_id", "app_name", "app_version", "sdk_version", "model_revision", "new_install"), b.keys)
        assertEquals("android", b["platform"])
        assertEquals("2.0.0", b["sdk_version"])
        assertEquals(true, b["new_install"])
        assertTrue(regs[0].headers["authorization"]!!.startsWith("Bearer qlt_v1."))
        // the token came from POST /v1/attest with the key, unattested (sharing is off)
        val att = stub.seen("/v1/attest").single()
        assertEquals(stub.key, att.headers["x-qlab-key"])
        assertEquals(setOf("platform", "bundle_id"), att.json!!.keys)
        // the next launch: not new, and the cached token is reused
        val c2 = cloud()
        assertTrue(c2.register(null))
        assertEquals(false, stub.seen("/v1/register")[1].json!!["new_install"])
        assertEquals(1, stub.seen("/v1/attest").size)
    }

    @Test fun aFailedFirstRegistrationKeepsTheNewInstallFlag() {
        stub.down = true
        assertFalse(cloud().register(null))
        stub.down = false
        assertTrue(cloud().register(null))
        assertEquals(true, stub.seen("/v1/register").last().json!!["new_install"])
    }

    @Test fun signedConfigIsVerifiedStoredAndConditional() {
        val c = cloud()
        val cfg = c.refreshConfig()
        assertNotNull(cfg)
        assertEquals("2.0.0", stub.seen("/v1/config")[0].q("sdk"))
        assertNotNull(stub.seen("/v1/config")[0].q("bucket"))
        assertTrue(c.tajweedJson!!.contains("\"sensitive\""))
        assertEquals("v4.0+abc", cfg!!.recommendedRevision)
        // the next fetch is conditional: 304 keeps the stored body
        assertNotNull(c.refreshConfig())
        val second = stub.seen("/v1/config")[1]
        assertTrue(second.headers["if-none-match"]!!.startsWith("\""))
        assertTrue(c.tajweedJson!!.contains("sensitive"))
        // a change on the portal: 200 with the new block
        stub.tajweed = mapOf("v" to 1.0, "preset" to "strict", "word" to mapOf("preset" to "beginner"))
        c.refreshConfig()
        assertTrue(c.tajweedJson!!.contains("\"strict\""))
        assertTrue(c.tajweedJson!!.contains("beginner"))
        // survives a relaunch, re-verified from disk
        val c2 = cloud()
        assertTrue(c2.tajweedJson!!.contains("\"strict\""))
    }

    @Test fun aTamperedOrForeignConfigIsRejectedAndTheLastGoodOneKept() {
        val c = cloud()
        c.refreshConfig()
        stub.tajweed = mapOf("v" to 1.0, "preset" to "strict")
        stub.tamper = true
        c.refreshConfig()
        assertTrue("the last good block stays", c.tajweedJson!!.contains("sensitive"))
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

    private fun session(c: QuranLabCloud, seconds: Double = 30.0, preset: String = "balanced", backend: String = "cpu", context: String = "c16_l64") {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", context, backend, preset, "general", checksOn = setOf("letters", "vowels", "madd", "bogus")))
        repeat(10) { c.onChunk(12.0 + it, 320.0) }
        c.onTokens(floatArrayOf(0.3f, 1.5f, 9f), floatArrayOf(0.99f, 0.6f))
        c.onScored(TajweedObserver.Scored(1, 2, mapOf("vowel_sub" to (10 to 1), "cons_sub" to (8 to 0), "nonsense" to (1 to 1)), maddC2 = 3, maddC4 = 1, wordsScored = 7, wordsFlagged = 1))
        c.onScored(TajweedObserver.Scored(112, 1))
        c.onSessionEnd(TajweedObserver.SessionEnd(seconds, completed = true, feedbackShown = true))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun telemetryIsADailyAnonymousAggregateSentTheNextDay() {
        val c = cloud()
        c.refreshConfig()
        c.onModelLoad(TajweedObserver.ModelLoad("v4.0+abc", "c16_l64", "cpu", 800.0, cold = true))
        c.onDownload(TajweedObserver.Download("v4.0+abc", "android-cpu", 72_000_000, success = true, resumed = true))
        session(c)
        session(c, 15.0)
        c.onSelfTest("v4.0+abc", "c16_l64", "cpu", true)
        // today is not complete: nothing goes
        assertEquals(0 to 0, c.deliver())
        assertTrue(stub.seen("/v1/events").isEmpty())
        // the next UTC day
        now += 86_400_000
        val (days, _) = c.deliver()
        assertEquals(1, days)
        val ev = stub.seen("/v1/events").single()
        assertTrue(ev.headers["authorization"]!!.startsWith("Bearer qlt_v1."))
        val b = ev.json!!
        assertEquals(2.0, b["v"])
        assertEquals(Day.of(now - 86_400_000), b["day"])
        assertEquals(mapOf("class" to "Pixel8", "soc" to "tensor_g3", "os_major" to 15.0, "ram_tier" to "8_16"), b["device"])
        val co = (b["cohorts"] as List<Map<String, Any?>>)
        val main = co.first { it["preset"] == "balanced" && (it["sessions"] as Double) > 0 }
        assertEquals(2.0, main["sessions"])
        assertEquals(0.75, main["minutes"])
        assertEquals(20.0, main["chunks"])
        assertEquals(mapOf("le_20" to 18.0, "le_40" to 2.0), main["latency_ms"])
        assertEquals(mapOf("le_0_1" to 20.0), main["rtf"])
        assertEquals(mapOf("le_250" to 1.0), main["cold_start_ms"])
        assertEquals(mapOf("le_0_5" to 2.0, "le_2" to 2.0, "gt_8" to 2.0), main["margin"])
        assertEquals(mapOf("le_0_7" to 2.0, "gt_0_95" to 2.0), main["confidence"])
        assertEquals(mapOf("letters" to 2.0, "vowels" to 2.0, "madd" to 2.0), main["checks_on"])
        assertEquals(mapOf("vowel_sub" to mapOf("sites" to 20.0, "flagged" to 2.0), "cons_sub" to mapOf("sites" to 16.0, "flagged" to 0.0)), main["classes"])
        assertEquals(mapOf("le_10" to 2.0), main["session_flag_pct"])
        assertEquals(mapOf("pass" to 1.0, "fail" to 0.0), main["self_test"])
        assertEquals(mapOf("c2" to 6.0, "c4" to 2.0, "c6" to 0.0, "flagged" to 0.0), main["madd"])
        assertEquals(mapOf("scored" to 14.0, "flagged" to 2.0), main["words"])
        assertEquals(mapOf("1" to 2.0, "112" to 2.0), b["coverage"])
        assertEquals(listOf(mapOf("revision" to "v4.0+abc", "profile" to "android-cpu", "bytes" to 7.2E7, "success" to 1.0, "failed" to 0.0, "resumed" to 1.0)), b["downloads"])
        // nothing that could identify anyone: only the schema's fields (the stub refuses any other)
        val text = String(ev.body)
        assertFalse(text.contains("org.example.recite"))
        assertFalse(text.contains("Recite"))
        // 202 deleted the day: nothing more to send
        assertEquals(0 to 0, c.deliver())
        assertEquals(1, stub.seen("/v1/events").size)
    }

    @Test fun refusedDaysAreDroppedAndFailuresBackOff() {
        val c = cloud()
        session(c)
        now += 86_400_000
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(503, "storage_unavailable") }
        assertEquals(0, c.deliver().first)
        assertEquals(1, c.telemetry.pendingDays().size)
        // backoff: a minute
        assertEquals(0, c.deliver().first)
        assertEquals(1, stub.seen("/v1/events").size)
        now += 61_000
        stub.overrides["/v1/events"] = { _, _ -> StubQlab.Reply.error(400, "invalid_body") }
        assertEquals(0, c.deliver().first)
        assertTrue("a refused body is never retried", c.telemetry.pendingDays().isEmpty())
        assertTrue("reported, and delivered in the same pass", stub.seen("/v1/issues").any { it.json!!["code"] == "bad_request:events" })
    }

    @Test fun theKillSwitchAndTheDeveloperOptOutDropEverything() {
        val c = cloud()
        session(c)
        stub.flags = stub.flags + ("analytics" to false)
        c.refreshConfig()
        assertFalse(c.telemetry.enabled)
        now += 86_400_000
        assertEquals(0, c.deliver().first)
        assertTrue(stub.seen("/v1/events").isEmpty())
        assertTrue(c.telemetry.pendingDays().isEmpty())

        val off = QuranLabCloud(CloudOptions(stub.key, analytics = CloudOptions.Analytics.OFF, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(Files.createTempDirectory("tj-off").toFile(), clock = clock))
        session(off)
        now += 86_400_000
        assertEquals(0, off.deliver().first)
        assertTrue(off.telemetry.pendingDays().isEmpty())
    }

    @Test fun daysOlderThanAWeekAreDropped() {
        val c = cloud()
        session(c)
        now += 9 * 86_400_000L
        assertTrue(c.telemetry.pendingDays().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun cohortsBeyondSixteenAreMergedIntoTheLargest() {
        val c = cloud()
        for (i in 0 until 20) session(c, backend = if (i < 17) "npu:soc$i" else "cpu")
        now += 86_400_000
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
        now += 86_400_000
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
