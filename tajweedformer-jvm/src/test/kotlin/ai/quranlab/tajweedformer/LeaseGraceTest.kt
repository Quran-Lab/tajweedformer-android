package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.ProtectedKit.b64u
import ai.quranlab.tajweedformer.ProtectedKit.hex
import ai.quranlab.tajweedformer.ProtectedKit.sha256
import ai.quranlab.tajweedformer.ProtectedKit.unb64u
import ai.quranlab.tajweedformer.cloud.ApiError
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.cloud.HttpRequest
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The grace when Quran Lab cannot be reached, through the
 * lease client and the REAL Core (the host build of the test release 4.0.0-c1 at quranlab-core f006676, the Core of
 * release 4.0.0-c3), against a fake API on a clock the test moves (the server's time moves with the device's):
 *
 * - grace.json's 40 outcomes: on a fresh install whose 24-hour lease expired two hours before, the fake gives the case's
 *   answers in order to the requests of their routes (token, attest, challenge, model-key; any other request is
 *   answered as usual), and a model open ([LeaseClient.ensure]) ends as the case says: a valid lease, a verified stop,
 *   refused (no grace: the app's error), or unreachable (the open goes on in the grace);
 * - 503 lease_unavailable with Retry-After 30: an open in grace, reported (lease, grace, 503, lease_unavailable), and
 *   the background refresh's next try 30 s later; a second open within the minute asks nothing; a refusal ends the
 *   grace and a later unreachable attempt gives none back; the end at exp + 86400; an open that waits no more than its
 *   wait for an answer that does not come; a 7-day lease of an unattested install (the server's capped_7d) held to its
 *   own exp.
 */
class LeaseGraceTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val clients = ArrayList<LeaseClient>()
    private fun temp(): File = Files.createTempDirectory("tj-grace-").toFile().also { dirs += it }

    @Before
    fun need() = assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)

    @After
    fun cleanUp() {
        clients.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private val keyId = "apps-one"
    private val key = QuranLabKey.parse("qlk_v1_${keyId}_" + "a".repeat(22))!!
    private val app = AppIdentity("org.example.recite", "1.0")

    private class Clock(var wallMs: Long = 1_790_000_000_000L, var bootMs: Long = 1_000_000) {
        fun pass(ms: Long) { wallMs += ms; bootMs += ms }
    }

    /** The fake API: the default answers of qlab-api's lease routes on the test's clock, a script of answers by route,
     *  and the app token as a request of its own route (POST /v1/attest without install keys). */
    @Suppress("UNCHECKED_CAST")
    private inner class Api(val clock: Clock, val bytes: ByteArray) {
        val seen = ArrayList<String>()
        var script: List<Map<String, Any?>> = emptyList()
        var at = 0
        var override: ((String, Map<String, Any?>?) -> HttpResponse?)? = null
        var leaseDays = 1L
        /** The lease's flags: 8 (bit 3, accelerator programs) for the 7-day class (18.4.6). */
        var leaseFlags = 0
        var needToken = false
        var lateMs = 3_000L
        private var lastChallenge = ""
        private var challenges = 0
        fun now() = clock.wallMs / 1000
        fun reply(status: Int, o: Any?, headers: Map<String, String> = mapOf("content-type" to "application/json")) =
            HttpResponse(status, headers, if (o is String) o.toByteArray() else Json.write(o).toByteArray())

        private fun route(path: String, body: Map<String, Any?>?): String? = when (path) {
            "/v1/attest" -> if (body?.containsKey("install_key") == true) "attest" else "token"
            "/v1/attest/challenge" -> "challenge"
            "/v1/model-key" -> "model-key"
            else -> null
        }

        val transport = { r: HttpRequest ->
            val path = r.url.substringAfter("http://stub").substringBefore('?')
            val body = r.body?.let { Json.parse(String(it)) as Map<String, Any?> }
            val rt = route(path, body)
            seen += rt ?: path
            val a = script.getOrNull(at)
            if (a != null && rt == a["route"]) {
                at++
                scripted(path, body, a)
            } else {
                override?.invoke(path, body) ?: default(path, body)
            }
        }

        private fun scripted(path: String, body: Map<String, Any?>?, a: Map<String, Any?>): HttpResponse {
            when (a["answer"] as String?) {
                "none", "stalled" -> throw IOException("the fake gives no answer")
                "late" -> { Thread.sleep(lateMs); throw IOException("the fake gave no answer in time") }
            }
            val st = (a["status"] as Double).toInt()
            val err = a["error"] as String?
            val why = a["reason"] as String?
            return when (a["body"] as String) {
                "html" -> reply(st, "<html><body>a page that is not the API's</body></html>", mapOf("content-type" to "text/html"))
                "empty" -> reply(st, "", emptyMap())
                else -> when {
                    a["core"] == "stop" -> {
                        val ih = hex(sha256(unb64u(body!!["install_key"] as String)))
                        reply(st, mapOf("error" to "invalid_key", "reason" to "key_revoked",
                            "signed" to ProtectedKit.signedRefusal(keyId, "key_revoked", ih, body["challenge"] as String, now())))
                    }
                    st == 200 && a["core"] == "refused" -> lease(body!!, foreign = true)
                    st == 200 -> default(path, body)
                    err != null -> reply(st, linkedMapOf<String, Any?>("error" to err).also { o -> if (why != null) o["reason"] = why })
                    else -> reply(st, mapOf("message" to "no error string"))
                }
            }
        }

        fun default(path: String, body: Map<String, Any?>?): HttpResponse = when (path) {
            "/v1/attest/challenge" -> {
                challenges++
                lastChallenge = "c1.${now() + 300}.${"A".repeat(21)}${'A' + (challenges % 26)}.${"B".repeat(22)}"
                reply(200, mapOf("challenge" to lastChallenge, "expires_in" to 300))
            }
            "/v1/attest" -> if (body?.containsKey("install_key") != true) reply(200, mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400, "attested" to false)) else {
                val ik = unb64u(body["install_key"] as String)
                val sk = unb64u(body["install_sign_key"] as String)
                reply(200, mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400, "attested" to false,
                    "install_cert" to ProtectedKit.cert("android:${app.bundleId}", "android", keyId, hex(sha256(ik)), hex(sha256(sk)), att = "none", hw = 0,
                        iat = now(), exp = now() + 30L * 86_400, recheck = 0)))
            }
            "/v1/model-key" -> lease(body!!)
            else -> reply(404, mapOf("error" to "not_found"))
        }

        fun lease(b: Map<String, Any?>, foreign: Boolean = false): HttpResponse {
            val ik = if (foreign) java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                .generateKeyPair().public.encoded else unb64u(b["install_key"] as String)
            val iat = now() - 60
            val exp = iat + leaseDays * 86_400
            val m = ai.quranlab.tajweedformer.ModelManifest.parse(bytes)
            val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = leaseFlags, iat = iat, exp = exp, refreshAfter = iat + 43_200,
                keyId = hex(m.encryption!!.keyId), build = hex(b["build"] as String), installHash = sha256(ik),
                model = b["model"] as String, version = b["version"] as String), ik)
            return reply(200, linkedMapOf("v" to 1, "lease" to b64u(lease), "iat" to iat, "exp" to exp, "refresh_after" to iat + 43_200,
                "base" to "https://cdn.quranlab.ai/models/x/y/${b["build"]}/sdk/v4.0/", "install_cert" to b["install_cert"]))
        }

        /** The app token: a request of its own when it must be renewed, as the client's token store makes one. */
        fun token(): String {
            if (!needToken) return "qlt_v1.e30.s"
            val r = try { transport(HttpRequest("POST", "http://stub/v1/attest", body = Json.write(mapOf("platform" to "android")).toByteArray())) }
                catch (e: IOException) { throw CloudException(null, "no answer", e, "/v1/attest") }
            if (r.status != 200) throw CloudException(r.error, "token: HTTP ${r.status}", null, "/v1/attest")
            needToken = false
            return "qlt_v1.e30.s"
        }
    }

    private inner class Setup(val api: Api, val clock: Clock, val l: LeaseClient, val pb: ProtectedBuild, val reports: MutableList<ErrorCause>)

    /** A fresh install leased at the clock's time, then the clock 2 hours past that lease's exp. */
    @Suppress("UNCHECKED_CAST")
    private fun install(waitMs: Long = 10_000, reuseMs: Long = 60_000, past: Boolean = true): Setup {
        val dir = temp()
        val v = ProtectedKit.vector("manifest_40.json")
        val bytes = Json.write(v["manifest"]).toByteArray()
        File(dir, "manifest.json").writeBytes(bytes)
        val m = ai.quranlab.tajweedformer.ModelManifest.parse(bytes)
        val clock = Clock()
        val api = Api(clock, bytes)
        val reports = ArrayList<ErrorCause>()
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            QuranLabApi("http://stub", api.transport), key, app, token = { api.token() }, invalidateToken = { api.needToken = true }, adoptToken = {},
            integrity = null, projectNumber = { null }, removeModels = { true }, clock = { clock.wallMs },
            hostClock = ({ clock.wallMs }) to ({ clock.bootMs }), onGrace = { reports += it }, graceWaitMs = waitMs, graceReuseMs = reuseMs)
            .also { clients += it }
        val pb = ProtectedBuild(dir, m, bytes, l)
        val c = pb.core()
        assertTrue(c.tick().valid)
        if (past) clock.pass((86_400L + 2 * 3600) * 1000)
        return Setup(api, clock, l, pb, reports)
    }

    private fun Setup.open(): Core = l.ensure(pb.target, pb::roots)

    @Suppress("UNCHECKED_CAST")
    @Test
    fun graceJsonOutcomes() {
        val outcomes = ProtectedKit.vector("grace.json")["outcomes"] as List<Map<String, Any?>>
        assertEquals(40, outcomes.size)
        var good = 0
        for (o in outcomes) {
            val s = install(waitMs = 1_000)
            val answers = o["answers"] as List<Map<String, Any?>>
            s.api.script = answers
            s.api.needToken = answers.first()["route"] == "token"
            s.api.lateMs = 2_000
            val got = try {
                val c = s.open()
                val st = c.tick()
                when {
                    st.valid -> "lease"
                    c.grace().first -> "unreachable"
                    else -> "an open with neither a lease nor a grace"
                }
            } catch (f: LeaseFailure) {
                val c = s.l.core()
                when {
                    f.stopped || c?.tick()?.stopped == true -> "stop"
                    c != null && c.grace().first -> "a failure in grace"
                    else -> "refused"
                }
            }
            if (got == o["outcome"]) good++ else System.err.println("grace outcome ${o["name"]}: want ${o["outcome"]}, got $got (answers given ${s.api.at} of ${answers.size}: ${s.api.seen})")
            if (got == "unreachable") assertTrue("${o["name"]}: the open in grace is reported", s.reports.any { it.stage == "lease" && it.reason == "grace" })
        }
        println("grace.json outcomes: $good of ${outcomes.size} as the contract says")
        assertEquals(outcomes.size, good)
    }

    @Test
    fun leaseUnavailableOpensInGraceAndTheRefreshWaitsItsRetryAfter() {
        val s = install()
        s.api.override = { path, _ ->
            if (path == "/v1/model-key") s.api.reply(503, mapOf("error" to "lease_unavailable", "message" to "the lease service is unavailable"),
                mapOf("content-type" to "application/json", "retry-after" to "30")) else null
        }
        val c = s.open()
        val (g, until) = c.grace()
        assertTrue(g)
        assertEquals(c.tick().exp + 86_400, until)
        val rep = s.reports.single()
        assertEquals(ErrorCause("lease", "grace", 503, null, "lease_unavailable"), rep)
        // a second open within the minute asks nothing
        val asked = s.api.seen.count { it == "model-key" }
        s.open()
        assertEquals(asked, s.api.seen.count { it == "model-key" })
        assertEquals(2, s.reports.size)
        // the background refresh: due in the grace, its 503 sets the next try 30 s on (18.4.7's backoff would say 60)
        assertTrue(s.l.refreshDue())
        s.l.refreshIfDue(s.pb.target, s.pb::roots)
        assertEquals(s.clock.wallMs + 30_000, s.l.record!!.nextTryMs)
        assertFalse(s.l.refreshDue())
    }

    @Test
    fun aRefusalEndsTheGraceForTheRestOfTheLease() {
        val s = install(reuseMs = 0)
        s.api.override = { path, _ -> if (path == "/v1/model-key") s.api.reply(503, mapOf("error" to "not_configured")) else null }
        assertTrue(s.open().grace().first)
        s.api.override = { path, _ -> if (path == "/v1/model-key") s.api.reply(403, mapOf("error" to "sdk_too_old", "min_sdk" to "4.1.0")) else null }
        try {
            s.open()
            fail("a refusal opened the model")
        } catch (f: LeaseFailure) {
            assertEquals("sdk_too_old", f.error.code)
        }
        s.api.override = { path, _ -> if (path == "/v1/model-key") s.api.reply(503, mapOf("error" to "not_configured")) else null }
        try {
            s.open()
            fail("an unreachable attempt after a refusal opened the model")
        } catch (f: LeaseFailure) {
            assertEquals("download_failed", f.error.code)
        }
        assertFalse(s.l.core()!!.grace().first)
        // a new lease clears it all
        s.api.override = null
        assertTrue(s.open().tick().valid)
    }

    /** Stage B (11-18): a verdict refused at the lease (18.4.10: a failed verdict recorded for the install pair) names
     *  its check to the app and in the report's error_code, with the same code and signature as before. */
    @Test
    fun aVerdictRefusedAtTheLeaseNamesItsCheck() {
        val s = install()
        s.api.override = { path, _ ->
            if (path == "/v1/model-key") s.api.reply(403, mapOf("error" to "lease_integrity_failed", "message" to "the install could not be verified",
                "why" to "verdict", "check" to "recorded")) else null
        }
        try {
            s.open()
            fail("a refusal opened the model")
        } catch (f: LeaseFailure) {
            assertEquals("server_rejected", f.error.code)
            assertTrue(f.error.message, f.error.message.contains("last 24 hours") && f.error.message.contains("(lease_integrity_failed, why verdict, check recorded)"))
            val c = ErrorCause.of(f.error)
            assertEquals(listOf("lease", "integrity_failed", 403, "verdict:recorded"), listOf(c.stage, c.reason, c.httpStatus, c.errorCode))
        }
    }

    /**
     * diagnose()'s `protected` object from the real Core: valid with its exp,
     * refresh time and class; in grace with its end; the class from the lease's flags (bit 3: the 7-day class), never
     * from exp - iat (an emergency capped_7d lease of the 24-hour class lasts 7 days and stays `24h`).
     */
    @Test
    fun theDiagnosisNamesTheLeaseItsClassAndItsGrace() {
        val s = install(past = false)
        var d = s.l.diagnosis()
        assertEquals("software", d["keyStore"])
        assertEquals("valid", d["lease"])
        val st = s.l.core()!!.tick()
        assertEquals(st.exp, d["leaseExpires"])
        assertEquals(st.refreshAfter, d["refreshAfter"])
        assertEquals("24h", d["leaseClass"])
        assertNull(d["graceUntil"])
        assertTrue(d["core"].toString(), (d["core"] as String).isNotEmpty())
        // past its exp and Quran Lab unreachable: the grace, and its end
        s.clock.pass((86_400L + 2 * 3600) * 1000)
        s.api.override = { path, _ -> if (path == "/v1/model-key") throw IOException("offline") else null }
        val (g, until) = s.open().grace()
        assertTrue(g)
        d = s.l.diagnosis()
        assertEquals("grace", d["lease"])
        assertEquals(until, d["graceUntil"])
        assertEquals(st.exp, d["leaseExpires"])
        // a 7-day lease (flags bit 3) and a 24-hour class lease stretched to 7 days by the emergency switch; past the
        // minute in which a second open reuses the grace without asking
        s.api.override = null
        s.api.leaseFlags = 8
        s.clock.pass(2 * 60_000L)
        assertTrue(s.open().tick().valid)
        assertEquals("7d", s.l.diagnosis()["leaseClass"])
        s.clock.pass(2L * 86_400 * 1000)
        s.api.leaseFlags = 0
        s.api.leaseDays = 7
        assertTrue(s.open().tick().valid)
        d = s.l.diagnosis()
        assertEquals("valid", d["lease"])
        assertEquals("24h", d["leaseClass"])
        assertTrue((d["leaseExpires"] as Long) - s.clock.wallMs / 1000 > 6 * 86_400L)
    }

    @Test
    fun theGraceEndsAtExpPlus24Hours() {
        val s = install()
        s.api.override = { path, _ -> if (path == "/v1/model-key") throw IOException("offline") else null }
        val c = s.open()
        val (g, until) = c.grace()
        assertTrue(g)
        s.clock.pass((until - c.tick().now - 1) * 1000) // a second before exp + 86400
        assertTrue(s.l.core()!!.grace().first)
        s.clock.pass(1000)
        try {
            s.open()
            fail("the grace outlived exp + 86400")
        } catch (f: LeaseFailure) {
            assertEquals("needs_connection", f.error.code)
        }
    }

    @Test
    fun anOpenWaitsNoMoreThanItsWait() {
        val s = install(waitMs = 1_000)
        s.api.lateMs = 4_000
        s.api.override = { path, _ -> if (path == "/v1/model-key") { Thread.sleep(s.api.lateMs); throw IOException("late") } else null }
        val t0 = System.nanoTime()
        val c = s.open()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the open waited $ms ms", ms < 3_000)
        assertTrue(c.grace().first)
        assertEquals("grace", s.reports.single().reason)
        assertEquals("timeout", s.reports.single().errorCode)
    }

    @Test
    fun aSevenDayLeaseOfAnUnattestedInstallIsHeldToItsOwnExp() {
        // the server's lease_grace switch, capped_7d: the 24-hour class gets the 7-day exp (18.4.7.2)
        val s = install(past = false)
        s.api.leaseDays = 7
        s.clock.pass(86_400L * 1000 + 60_000)
        val c = s.open() // lease 1 is over: a new one, of 7 days
        assertEquals(7L * 86_400, c.tick().exp - (s.api.now() - 60))
        s.clock.pass(3L * 86_400 * 1000)
        val asked = s.api.seen.count { it == "model-key" }
        s.api.override = { path, _ -> if (path == "/v1/model-key") throw IOException("offline") else null }
        val st = s.open().tick()
        assertTrue("3 days on, the 7-day lease is valid", st.valid)
        assertEquals("no request while it is valid", asked, s.api.seen.count { it == "model-key" })
    }
}
