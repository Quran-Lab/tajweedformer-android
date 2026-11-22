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
import ai.quranlab.tajweedformer.core.CoreRegistry
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

        /**
         * SDK 4.0.1 (endpoints_40.json `attempts`): the requests of one attempt on the `api` hosts, each with every host's
         * answer. A request to a host of the list is answered from the scripted request of its route (the next one of
         * that route), [asked] records each (route, host) in order and [bodies] the body each scripted request was sent
         * with on each host (28.3 item 2: every host is asked the same).
         */
        var hostScript: List<Map<String, Any?>>? = null
        private var qi = 0
        val asked = ArrayList<Pair<String, String>>()
        val bodies = HashMap<Int, MutableList<String>>()
        /** The places in [asked] of requests the script has no answer for (answered as usual). */
        val extra = ArrayList<Int>()

        val transport = { r: HttpRequest ->
            val u = java.net.URI(r.url)
            val path = u.path
            val body = r.body?.let { Json.parse(String(it)) as Map<String, Any?> }
            val rt = route(path, body)
            seen += rt ?: path
            val hs = hostScript
            if (hs != null && u.host != "stub") hosted(hs, u.host, path, rt, body, r.body, r.headers.containsKey("x-qlab-key")) else stubbed(path, rt, body)
        }

        private fun hosted(hs: List<Map<String, Any?>>, host: String, path: String, rt: String?, body: Map<String, Any?>?, raw: ByteArray?,
                           keyed: Boolean): HttpResponse {
            // the challenge an attest asks with the key (18.4.3: the Key Attestation carries it) is part of the attest,
            // which the vectors count as one request: recorded apart, answered as usual
            if (keyed && rt == "challenge") {
                asked += "attest-challenge" to host
                extra += asked.size - 1
                return override?.invoke(path, body) ?: default(path, body)
            }
            asked += (rt ?: path) to host
            var j = qi
            while (j < hs.size && hs[j]["route"] != rt) j++
            val a = hs.getOrNull(j)?.let { (it["answers"] as Map<String, Any?>)[host] as Map<String, Any?>? }
            if (a == null) {
                extra += asked.size - 1
                return override?.invoke(path, body) ?: default(path, body)
            }
            qi = j
            bodies.getOrPut(qi) { ArrayList() } += String(raw ?: ByteArray(0))
            val r = scripted(path, body, a)
            val ra = (a["retry_after"] as? Number)?.toInt() ?: return r
            return HttpResponse(r.status, r.headers + ("retry-after" to "$ra"), r.body)
        }

        private fun stubbed(path: String, rt: String?, body: Map<String, Any?>?): HttpResponse {
            val a = script.getOrNull(at)
            return if (a != null && rt == a["route"]) {
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

    private inner class Setup(val api: Api, val clock: Clock, val l: LeaseClient, val pb: ProtectedBuild, val reports: MutableList<ErrorCause>,
                              val dir: File)

    /** A fresh install leased at the clock's time, then the clock 2 hours past that lease's exp. With [hosts] (SDK 4.0.1,
     *  section 28) every later request goes through that `api` list, the app token's renewal too: the fake answers the
     *  stand-in host `stub` for the first lease, and each listed host by [Api.hostScript]. */
    @Suppress("UNCHECKED_CAST")
    private fun install(waitMs: Long = 10_000, reuseMs: Long = 60_000, past: Boolean = true, hosts: List<String>? = null,
                        leased: Boolean = true): Setup {
        val dir = temp()
        val v = ProtectedKit.vector("manifest_40.json")
        val bytes = Json.write(v["manifest"]).toByteArray()
        File(dir, "manifest.json").writeBytes(bytes)
        val m = ai.quranlab.tajweedformer.ModelManifest.parse(bytes)
        val clock = Clock()
        val api = Api(clock, bytes)
        val reports = ArrayList<ErrorCause>()
        // the first lease from the stand-in alone; then, with [hosts], the hosts of the list (a config's list, 28.2)
        var first = true
        val endpoints = hosts?.let { h ->
            ai.quranlab.tajweedformer.cloud.Endpoints.mapped(listOf("stub") + h, ai.quranlab.tajweedformer.cloud.HostNames.MODELS,
                (h.map { it to "http://$it" } + ("stub" to "http://stub")).toMap()) { clock.wallMs }
                .also { e -> e.configured = { if (first) mapOf("api" to listOf("stub")) else mapOf("api" to h) } }
        }
        val qapi = if (endpoints == null) QuranLabApi("http://stub", api.transport) else QuranLabApi("http://stub", api.transport, endpoints)
        val token: () -> String = if (endpoints == null) ({ api.token() }) else ({
            if (api.needToken) {
                qapi.attest(key, app)
                api.needToken = false
            }
            "qlt_v1.e30.s"
        })
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            qapi, key, app, token = token, invalidateToken = { api.needToken = true }, adoptToken = {},
            integrity = null, projectNumber = { null }, removeModels = { true }, clock = { clock.wallMs },
            hostClock = ({ clock.wallMs }) to ({ clock.bootMs }), onGrace = { reports += it }, graceWaitMs = waitMs, graceReuseMs = reuseMs)
            .also { clients += it }
        val pb = ProtectedBuild(dir, m, bytes, l)
        // [leased] false: no lease yet (the app's first call asks for it)
        if (!leased) return Setup(api, clock, l, pb, reports, dir)
        val c = pb.core()
        assertTrue(c.tick().valid)
        first = false
        if (past) clock.pass((86_400L + 2 * 3600) * 1000)
        return Setup(api, clock, l, pb, reports, dir)
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

    /**
     * Contract section 28 (SDK 4.0.1), endpoints_40.json `attempts` on the real Core: an install whose lease expired two
     * hours before asks for one through the four `api` hosts, each request answered by host as the case says. The
     * outcome is the case's (a lease from the standby, a refusal or a signed stop from it, unreachable and the grace only
     * when every host was or a 429 ended it), each request asks the case's hosts in order, and every host of one request
     * is sent the same body (a lease the Core refuses goes to the next host with the same challenge, 28.5). The requests
     * alone, without the Core: EndpointsVectorsTest.theRequestsOfEveryAttempt.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun endpointsAttempts() {
        val v = ProtectedKit.vector("endpoints_40.json")
        val hosts = ((v["roles"] as Map<String, Any?>)["api"] as List<String>)
        val cases = v["attempts"] as List<Map<String, Any?>>
        assertEquals(10, cases.size)
        for (o in cases) {
            val name = o["name"]
            val s = install(waitMs = 1_000, hosts = hosts)
            val requests = o["requests"] as List<Map<String, Any?>>
            s.api.hostScript = requests
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
            assertEquals("$name: the outcome (asked ${s.api.asked})", o["outcome"], got)
            val want = requests.flatMap { q -> (q["asked"] as List<String>).map { q["route"] as String to it } }
            // the requests Android makes that the case does not count apart (4.0.0's, 28.3 item 7: retries inside a route stay
            // as they are), each of the host the request around it went to: a fresh challenge after renewing the token,
            // and the attest's own challenge (asked with the key) right before the attest
            val asked = s.api.asked
            for (i in s.api.extra) {
                val afterToken = i > 0 && asked[i].first == "challenge" && asked[i - 1].first == "token" && asked[i].second == asked[i - 1].second
                val attests = asked[i].first == "attest-challenge" && asked.getOrNull(i + 1)?.let { it.first == "attest" && it.second == asked[i].second } == true
                assertTrue("$name: a request the case does not have: ${asked[i]} (asked $asked)", afterToken || attests)
            }
            assertEquals("$name: the hosts each request asked, in order", want, asked.filterIndexed { i, _ -> i !in s.api.extra })
            for ((i, b) in s.api.bodies) assertEquals("$name, request $i: every host is sent the same body", 1, b.toSet().size)
            if (got == "unreachable") {
                assertTrue("$name: the open in grace is reported", s.reports.any { it.stage == "lease" && it.reason == "grace" && it.hostRole == "api" })
            }
        }
    }

    /** 28.7: every host unreachable, the first host's answer a lease the Core refuses (the emulator's E8 run, 11-20): the
     *  open in its grace is reported with fallback true, since more than one host was asked, and the first host's 200. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun aGraceAfterEveryHostIsReportedWithItsFallback() {
        val hosts = (ProtectedKit.vector("endpoints_40.json")["roles"] as Map<String, Any?>)["api"] as List<String>
        val s = install(waitMs = 1_000, hosts = hosts)
        val none = mapOf("answer" to "none")
        s.api.hostScript = listOf(
            mapOf("route" to "challenge", "answers" to mapOf(hosts[0] to mapOf("status" to 200.0, "body" to "json"))),
            mapOf("route" to "model-key", "answers" to mapOf(hosts[0] to mapOf("status" to 200.0, "body" to "json", "core" to "refused"),
                hosts[1] to none, hosts[2] to none, hosts[3] to none)),
        )
        assertTrue(s.open().grace().first)
        assertEquals(listOf("model-key" to hosts[0], "model-key" to hosts[1], "model-key" to hosts[2], "model-key" to hosts[3]),
            s.api.asked.filter { it.first == "model-key" })
        val rep = s.reports.single { it.stage == "lease" && it.reason == "grace" }
        assertEquals(listOf<Any?>("api", true, 200), listOf(rep.hostRole, rep.fallback, rep.httpStatus))
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
        // 4.0.1: the lease route's role, one host asked (the test's one stand-in)
        assertEquals(ErrorCause("lease", "grace", 503, null, "lease_unavailable", hostRole = "api", fallback = false), rep)
        // a second open within the minute asks nothing
        val asked = s.api.seen.count { it == "model-key" }
        s.open()
        assertEquals(asked, s.api.seen.count { it == "model-key" })
        assertEquals(2, s.reports.size)
        // SDK 4.0.2 (18.4.7.3): the open's attempt counts, and its Retry-After (30 s) is only a floor: the background
        // refresh waits a drawn 600 to 1800 s (4.0.1: 30 s, the Retry-After in place of the step)
        assertTrue(s.l.record!!.nextTryMs - s.clock.wallMs in 600_000L..1_800_000L)
        assertFalse(s.l.refreshDue())
        s.clock.pass(1_800_000)
        assertTrue(s.l.refreshDue())
        s.l.refreshIfDue(s.pb.target, s.pb::roots)
        // the next failure: again 600 to 1800 s, never the 30 s the answer names
        assertTrue(s.l.record!!.nextTryMs - s.clock.wallMs in 600_000L..1_800_000L)
        assertFalse(s.l.refreshDue())
    }

    @Test
    fun aRefusalEndsTheGraceForTheRestOfTheLease() {
        val s = install(reuseMs = 0)
        s.api.override = { path, _ -> if (path == "/v1/model-key") s.api.reply(503, mapOf("error" to "not_configured")) else null }
        assertTrue(s.open().grace().first)
        s.api.override = { path, _ -> if (path == "/v1/model-key") s.api.reply(403, mapOf("error" to "sdk_too_old", "min_sdk" to "4.1.0")) else null }
        // SDK 4.0.2 (18.4.7.3 item 3): an open in the grace asks again only once the wait after the failure (600 to
        // 1800 s) has passed; before it, it opens in the grace with no request
        val asked = s.api.seen.count { it == "model-key" }
        assertTrue(s.open().grace().first)
        assertEquals("a request inside the wait", asked, s.api.seen.count { it == "model-key" })
        s.clock.pass(1_800_000)
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
     * refresh time and class; in grace with its end; the class from the lease's flags (bit 3: the attested class),
     * never from exp - iat (a lease of the standard class may last days, by the server's lease_days or its emergency
     * capped_7d, and stays `standard`).
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
        assertEquals("standard", d["leaseClass"])
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
        // wait after a failed attempt (SDK 4.0.2, 18.4.7.3: 600 to 1800 s; until then an open in the grace asks nothing)
        s.api.override = null
        s.api.leaseFlags = 8
        s.clock.pass(1_800_000L)
        assertTrue(s.open().tick().valid)
        assertEquals("attested", s.l.diagnosis()["leaseClass"])
        s.clock.pass(2L * 86_400 * 1000)
        s.api.leaseFlags = 0
        s.api.leaseDays = 7
        assertTrue(s.open().tick().valid)
        d = s.l.diagnosis()
        assertEquals("valid", d["lease"])
        assertEquals("standard", d["leaseClass"])
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

    @Test
    fun aLongLeaseOfTheServersLeaseDaysIsHeldWholeOpensOfflineAndEndsWithItsGrace() {
        // qlab-api lease_days (owner, 2026-11-19): every install's lease as long as the server makes it, here 10 days for
        // an unattested install. Nothing in the SDK shortens it or reads its class from exp - iat.
        val s = install(past = false)
        s.api.leaseDays = 10
        s.clock.pass(86_400L * 1000 + 60_000)
        val c = s.open() // lease 1 is over: a new one, of 10 days
        val exp = c.tick().exp
        assertEquals(10L * 86_400, exp - (s.api.now() - 60))
        assertEquals("standard", s.l.diagnosis()["leaseClass"])
        // day 9, offline: valid, no request
        s.clock.pass(9L * 86_400 * 1000)
        val asked = s.api.seen.count { it == "model-key" }
        s.api.override = { path, _ -> if (path == "/v1/model-key") throw IOException("offline") else null }
        assertTrue("day 9 of a 10-day lease is valid", s.open().tick().valid)
        assertEquals("no request while it is valid", asked, s.api.seen.count { it == "model-key" })
        // two hours past its exp, still offline: its grace
        s.clock.pass((exp - s.l.core()!!.tick().now + 2 * 3600) * 1000)
        val (g, until) = s.open().grace()
        assertTrue("in the grace after exp", g)
        assertEquals(exp + 86_400, until)
        // past exp and the grace: needs_connection
        s.clock.pass((86_400L - 2 * 3600 + 60) * 1000 + 2 * 60_000)
        try {
            s.open()
            fail("a lease outlived its exp and its grace")
        } catch (f: LeaseFailure) {
            assertEquals("needs_connection", f.error.code)
        }
    }

    // ---------------------------------------------------------------- close() and a lease attempt in flight (4.0.2)

    /** The lease request's answer, made when the request came (its lease issued then), held until [release]; with
     *  [answers] false no answer at all then. An interrupt of the waiting request is an InterruptedIOException, as
     *  Android's HTTP stack gives it. */
    private inner class Held(s: Setup, private val answers: Boolean = true) {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        init {
            s.api.override = { path, body ->
                if (path == "/v1/model-key") {
                    val answer = s.api.default(path, body)
                    arrived.countDown()
                    try { release.await() } catch (_: InterruptedException) { throw java.io.InterruptedIOException("interrupted") }
                    if (!answers) throw IOException("no answer")
                    answer
                } else null
            }
        }
    }

    /** A new lease client on the same storage: in this process (it shares a live Core of the folder), or with
     *  [registry] another process's (its Core made again from the state saved). [offline]: nothing answers. */
    private fun Setup.restart(offline: Boolean, registry: CoreRegistry = CoreRegistry.PROCESS): Setup {
        api.override = if (offline) ({ _, _ -> throw IOException("offline") }) else null
        val l2 = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            QuranLabApi("http://stub", api.transport), key, app, token = { api.token() }, invalidateToken = { api.needToken = true }, adoptToken = {},
            integrity = null, projectNumber = { null }, removeModels = { true }, clock = { clock.wallMs },
            hostClock = ({ clock.wallMs }) to ({ clock.bootMs }), onGrace = { reports += it }, graceWaitMs = 1_000, graceReuseMs = 60_000,
            registry = registry)
            .also { clients += it }
        return Setup(api, clock, l2, ProtectedBuild(dir, pb.manifest, pb.manifestBytes, l2), reports, dir)
    }

    /** 13 hours into a 24-hour lease: past its refresh_after (12 h), still valid. */
    private fun refreshDueInstall(): Setup = install(past = false).also {
        it.clock.pass(13L * 3600 * 1000)
        assertTrue(it.l.refreshDue())
    }

    /** The exp of the lease the fake answers now (24 hours from a minute ago). */
    private fun Setup.expNow(): Long = api.now() - 60 + 86_400

    private fun background(name: String, f: () -> Unit): Thread =
        Thread({ try { f() } catch (_: Throwable) {} }, name).apply { isDaemon = true; start() }

    /**
     * close() during a lease renewal in flight (SDK 4.0.2; the web SDK had the same bug): the background refresh's
     * answer is held, the client closes, then the answer comes. close() waits for the attempt (at most 3 s), so the
     * renewed lease is accepted and saved with the Core's state before the Core goes: a restart offline opens it. 4.0.2
     * before the fix destroyed the Core at once, the answer met a closed Core, and the restart had the old lease.
     */
    @Test
    fun closeWaitsForARefreshInFlightAndTheRestartHasTheRenewedLease() {
        val s = refreshDueInstall()
        val old = s.l.core()!!.tick().exp
        val held = Held(s)
        val refresh = background("refresh") { s.l.refreshIfDue(s.pb.target, s.pb::roots) }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val renewed = s.expNow()
        val closing = background("close") { s.l.close() }
        Thread.sleep(300)
        held.release.countDown()
        closing.join(10_000)
        refresh.join(10_000)
        assertFalse("close() returned", closing.isAlive)
        val st = s.restart(offline = true).open().tick()
        assertTrue(st.valid)
        assertEquals("the restart's lease (the old one's exp: $old)", renewed, st.exp)
    }

    /**
     * The same in the grace (the worst case of the web's bug): a model open two hours past the lease's exp, its lease
     * request's answer held, the client closes, the answer comes. A restart offline opens the renewed lease, valid, not
     * the grace. Before the fix the close cancelled the request, which counted as a failure, and the restart had only
     * the grace.
     */
    @Test
    fun closeWaitsForARenewalInGraceAndTheRestartOpensTheRenewedLeaseOffline() {
        val s = install()
        val held = Held(s)
        val open = background("open") { s.open() }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val renewed = s.expNow()
        val closing = background("close") { s.l.close() }
        Thread.sleep(300)
        held.release.countDown()
        closing.join(10_000)
        open.join(15_000)
        assertFalse("close() returned", closing.isAlive)
        val st = s.restart(offline = true).open().tick()
        assertTrue("the renewed lease is valid offline", st.valid)
        assertEquals(renewed, st.exp)
    }

    /**
     * The cancel rule (SDK 4.0.2): a lease request that the client's close ends is never a failed attempt.
     * An open in the grace whose lease request gets no answer; the client closes (it waits its 3 s and returns), then the
     * request fails. No failure is in the row and no backoff waits, and a restart asks at once. Before the fix close()
     * interrupted the request, which was told to the Core as unreachable and written as a failure with its backoff.
     */
    @Test
    fun aRenewalThatCloseCancelsIsNoFailedAttempt() {
        val s = install()
        Held(s, answers = false).also { h ->
            val open = background("open") { s.open() }
            assertTrue(h.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            s.l.close()
            val ms = (System.nanoTime() - t0) / 1_000_000
            h.release.countDown()
            open.join(15_000)
            assertTrue("close() waited at most its 3 s: $ms ms", ms < 6_000)
        }
        val r = s.l.record!!
        val asked = s.api.seen.count { it == "model-key" }
        val again = s.restart(offline = false)
        assertTrue("the next start may ask at once", again.l.refreshDue())
        assertTrue(again.open().tick().valid)
        assertEquals("the next start asked", asked + 1, s.api.seen.count { it == "model-key" })
        assertEquals("no failure in the row after the cancelled request", 0, r.failures)
        assertEquals("no backoff after the cancelled request", 0L, r.nextTryMs)
    }

    /**
     * Process death right after a renewal (only the Core's own save helps there: qlc_lease_accept saves the state before
     * it returns, and lease.json follows on the same thread): nothing is closed, and a restart offline opens the renewed
     * lease. Holds before the fix too.
     */
    @Test
    fun aRenewalIsKeptWithoutAnyClose() {
        val s = refreshDueInstall()
        s.l.refreshIfDue(s.pb.target, s.pb::roots)
        val renewed = s.expNow()
        // the process ends here: nothing is closed, and the next process makes its Core from what was saved
        val st = s.restart(offline = true, registry = CoreRegistry()).open().tick()
        assertTrue(st.valid)
        assertEquals(renewed, st.exp)
    }

    /**
     * A new client of the folder after close() while the closed client's attempt still holds the answer (SDK
     * 4.0.2, the same design as the Apple SDK's): the closed client returns from close() after its 3 s, a new client
     * starts, then the answer comes. The new client shares the live Core, the late lease goes into it and is kept, and a
     * restart offline opens it. Before the fix the new client made a second Core over the old state, the late answer met
     * a destroyed Core, and the refreshed lease was lost.
     */
    @Test
    fun aNewClientAfterCloseKeepsTheClosedClientsLateLease() {
        val s = refreshDueInstall()
        val held = Held(s)
        val refresh = background("refresh") { s.l.refreshIfDue(s.pb.target, s.pb::roots) }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val renewed = s.expNow()
        s.l.close()
        val b = s.restart(offline = true)
        val core = b.l.core()!!
        held.release.countDown()
        refresh.join(10_000)
        assertEquals("the new client's Core has the late lease", renewed, core.tick().exp)
        b.l.close()
        val st = s.restart(offline = true).open().tick()
        assertTrue(st.valid)
        assertEquals(renewed, st.exp)
    }

    /** The same in the grace: a model open past exp whose renewal is held, close(), a new client, then the answer. */
    @Test
    fun aNewClientAfterCloseKeepsTheClosedClientsLateLeaseInGrace() {
        val s = install()
        val held = Held(s)
        val open = background("open") { s.open() }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val renewed = s.expNow()
        s.l.close()
        val b = s.restart(offline = true)
        val core = b.l.core()!!
        held.release.countDown()
        open.join(15_000)
        assertEquals("the new client's Core has the late lease", renewed, core.tick().exp)
        assertTrue(core.tick().valid)
        b.l.close()
        val st = s.restart(offline = true).open().tick()
        assertTrue("the renewed lease opens offline, not the grace", st.valid)
        assertEquals(renewed, st.exp)
    }

    /** Two clients of one folder made back to back share one Core (the registry of the process). */
    @Test
    fun twoClientsOfOneFolderShareOneCore() {
        val s = install(past = false)
        val b = s.restart(offline = true)
        assertSame(s.l.core(), b.l.core())
    }

    /** The shared Core goes with its last holder, and a client made after that makes a new one from the saved state. */
    @Test
    fun theSharedCoreGoesWithItsLastHolder() {
        val s = install(past = false)
        val b = s.restart(offline = true)
        val shared = s.l.core()!!
        assertSame(shared, b.l.core())
        s.l.close()
        assertTrue("one holder left: the Core lives", shared.tick().valid)
        b.l.close()
        assertFalse("its last holder let go", CoreRegistry.PROCESS.live(shared))
        val c = s.restart(offline = true)
        val fresh = c.l.core()!!
        assertNotSame(shared, fresh)
        assertTrue("the new Core has the saved lease", fresh.tick().valid)
    }

    /**
     * The monotonic save: a lease the server issued before the one in force never replaces it, in the Core or in
     * lease.json. A closed client's renewal is held (its lease issued then); a new client of the folder takes a newer
     * one a minute later; then the older answer comes. The newer lease stays, and lease.json names it.
     */
    @Test
    fun anOlderLeaseNeverReplacesANewerOne() {
        val s = refreshDueInstall()
        val held = Held(s)
        val refresh = background("refresh") { s.l.refreshIfDue(s.pb.target, s.pb::roots) }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val older = s.expNow()
        s.l.close()
        val b = s.restart(offline = false)
        s.clock.pass(60_000)
        val newer = s.expNow()
        b.l.request(b.pb.target, b.pb::roots)
        assertEquals(newer, b.l.core()!!.tick().exp)
        held.release.countDown()
        refresh.join(10_000)
        assertEquals("the Core keeps the newer lease (the older one's exp: $older)", newer, b.l.core()!!.tick().exp)
        assertEquals("lease.json names the newer lease", newer, b.l.record!!.exp)
        b.l.close()
        assertEquals(newer, s.restart(offline = true, registry = CoreRegistry()).open().tick().exp)
    }

    /**
     * A deliberate clear is never held back by the monotonic save (SDK 4.0.2): a stored valid lease, then
     * Quran Lab's signed stop at its renewal. lease.json is gone, the Core holds no lease, and a restart offline does
     * not open the model.
     */
    @Test
    fun aStopClearsTheLeaseWhateverItsIssueTime() {
        val s = refreshDueInstall()
        assertTrue(s.l.record!!.iat > 0)
        s.api.script = listOf(mapOf("route" to "model-key", "status" to 401.0, "body" to "json", "core" to "stop"))
        var stopped = false
        s.l.refreshIfDue(s.pb.target, s.pb::roots) { f -> stopped = f.stopped }
        assertTrue("the stop verified", stopped)
        assertNull("lease.json is gone", s.l.record)
        assertFalse(s.l.core()!!.tick().hasLease)
        s.l.close()
        val r = s.restart(offline = true, registry = CoreRegistry())
        try {
            r.open()
            fail("a stopped lease opened offline")
        } catch (_: LeaseFailure) {
        }
        assertFalse(r.l.core()!!.tick().hasLease)
        assertNull(r.l.record)
    }

    /**
     * A lease save started before a stop never lands after it (SDK 4.0.2, the web SDK's race too): one request's
     * answer is in hand and its save is held just before the Core takes the lease; a second request gets Quran Lab's
     * signed stop, which clears the lease; then the held save goes on. The lease stays cleared, and a restart offline
     * does not open the model. Before the fix the held save could put the lease back.
     */
    @Test
    fun aSaveStartedBeforeAStopNeverPutsTheLeaseBack() {
        val s = refreshDueInstall()
        val inSave = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        val held = s.pb.target { _ -> inSave.countDown(); go.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val first = background("save") { s.l.request(held, s.pb::roots) }
        assertTrue(inSave.await(10, java.util.concurrent.TimeUnit.SECONDS))
        s.api.script = listOf(mapOf("route" to "model-key", "status" to 401.0, "body" to "json", "core" to "stop"))
        val stop = try { s.l.request(s.pb.target, s.pb::roots); null } catch (f: LeaseFailure) { f }
        assertTrue("the stop verified", stop?.stopped == true)
        go.countDown()
        first.join(10_000)
        assertNull("lease.json stays cleared", s.l.record)
        assertFalse("the Core holds no lease", s.l.core()!!.tick().hasLease)
        s.l.close()
        val r = s.restart(offline = true, registry = CoreRegistry())
        try {
            r.open()
            fail("the model opened offline after a stop")
        } catch (_: LeaseFailure) {
        }
    }

    /**
     * The native guard on its own (SDK 4.0.2: no use-after-free): 300 times, four threads call tick() and
     * grace() on one Core in a loop while its last holder closes it. Every call either returns or fails with E_CLOSED
     * (the SDK's error, never a crash), a call after the close is E_CLOSED, and a second close does nothing. Under
     * ASan on CI too (the bridge instrumented). Not run on the code before the fix: there a destroy under a call can
     * crash the JVM.
     */
    @Test
    fun theCoreClosesUnderCallsInFlightWithNoUseAfterFree() {
        val random = java.util.Random(17)
        val bad = java.util.Collections.synchronizedList(ArrayList<String>())
        var calls = 0L
        val counted = java.util.concurrent.atomic.AtomicLong()
        for (round in 0 until 300) {
            val s = install(past = false)
            val core = s.l.core()!!
            val callers = (0 until 4).map { k ->
                Thread({
                    while (true) {
                        try {
                            if (k % 2 == 0) core.tick() else core.grace()
                            counted.incrementAndGet()
                        } catch (e: ai.quranlab.tajweedformer.core.CoreException) {
                            if (e.status != Core.E_CLOSED) bad += "$round/$k: ${e.message}"
                            break
                        } catch (t: Throwable) {
                            bad += "$round/$k: $t"
                            break
                        }
                    }
                }, "caller-$round-$k").apply { isDaemon = true; start() }
            }
            Thread.sleep(random.nextInt(4).toLong())
            s.l.close()
            callers.forEach { it.join(10_000) }
            if (callers.any { it.isAlive }) bad += "$round: a caller still running"
            try {
                core.tick()
                bad += "$round: a call after close went through"
            } catch (e: ai.quranlab.tajweedformer.core.CoreException) {
                if (e.status != Core.E_CLOSED) bad += "$round: after close: ${e.message}"
            }
            core.close()
            clients -= s.l
            s.dir.deleteRecursively()
            dirs -= s.dir
        }
        calls = counted.get()
        println("the Core closed under 4 callers 300 times: $calls calls returned, ${bad.size} bad")
        assertEquals(emptyList<String>(), bad.take(20))
    }

    /**
     * The stress (SDK 4.0.2): 1,000 times, close() races a lease attempt held in flight, by turns the
     * background refresh, a model open in the grace and an app's first lease (ensure()), the answer released 0 to 20 ms
     * after close() began. Each time the lease the attempt brought is the one a restart offline opens (0 lost), and
     * nothing crashes: the JVM lives, no thread ends in an exception but the attempt's own LeaseFailure, and no thread
     * ends uncaught.
     */
    @Test
    fun closeRacesALeaseAttemptInFlight1000Times() {
        val random = java.util.Random(11)
        val unexpected = java.util.Collections.synchronizedList(ArrayList<String>())
        val uncaught = java.util.concurrent.atomic.AtomicInteger()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught.incrementAndGet(); System.err.println("uncaught in ${t.name}: $e") }
        var lost = 0
        val kinds = IntArray(3)
        try {
            for (i in 0 until 1000) {
                val kind = i % 3
                kinds[kind]++
                val s = when (kind) {
                    0 -> refreshDueInstall()
                    1 -> install()
                    else -> install(leased = false)
                }
                val held = Held(s)
                val attempt = Thread({
                    try {
                        if (kind == 0) s.l.refreshIfDue(s.pb.target, s.pb::roots) else s.open()
                    } catch (_: LeaseFailure) {
                    } catch (t: Throwable) {
                        unexpected += "$i/$kind: $t"
                    }
                }, "attempt-$i").apply { isDaemon = true; start() }
                if (!held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) { unexpected += "$i/$kind: no request"; continue }
                val renewed = s.expNow()
                val closing = Thread({ try { s.l.close() } catch (t: Throwable) { unexpected += "$i/close: $t" } }, "close-$i")
                    .apply { isDaemon = true; start() }
                Thread.sleep(random.nextInt(21).toLong())
                held.release.countDown()
                closing.join(10_000)
                attempt.join(15_000)
                if (closing.isAlive || attempt.isAlive) unexpected += "$i/$kind: still running"
                val r = s.restart(offline = true)
                val st = try { r.open().tick() } catch (_: LeaseFailure) { null }
                if (st == null || !st.valid || st.exp != renewed) {
                    lost++
                    System.err.println("lost $i/$kind: ${st?.valid} ${st?.exp} want $renewed")
                }
                r.l.close()
                clients -= s.l
                clients -= r.l
                s.dir.deleteRecursively()
                dirs -= s.dir
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        println("close raced a lease attempt 1000 times (refresh ${kinds[0]}, grace ${kinds[1]}, ensure ${kinds[2]}): lost $lost, " +
            "unexpected ${unexpected.size}, uncaught ${uncaught.get()}")
        assertEquals(emptyList<String>(), unexpected.take(20))
        assertEquals("uncaught", 0, uncaught.get())
        assertEquals("leases lost", 0, lost)
    }
}
