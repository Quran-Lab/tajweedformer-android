package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ManifestVerifier
import ai.quranlab.tajweedformer.ModelDownloader
import ai.quranlab.tajweedformer.ModelHosts
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.ProtectedKit
import ai.quranlab.tajweedformer.RangePlan
import ai.quranlab.tajweedformer.RetryPlan
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.core.LeaseClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import ai.quranlab.tajweedformer.HttpResponse as ModelResponse
import ai.quranlab.tajweedformer.HttpTransport as ModelTransport

/**
 * Contract section 28 (`sdk_4.0.0/ENDPOINTS_40.md`, SDK 4.0.1), the vectors `tools/endpoints40.py` makes
 * (`vectors40/endpoints_40.json`, the contract's file byte for byte at 564c144; Protected40VectorsTest compares the copy),
 * replayed on the SDK's own classes with the hosts answered by a stand-in transport and the time on a fake clock:
 *
 * - `roles`, `suffixes`, `limits` (Android's: the 5 s connect, the probe at 30 minutes, the five resumes and their
 *   pauses, 8 hosts a config may name; `head_s` and the web's loader and frame times are the other SDKs'), `hosts`,
 *   `config_lists` (through [Endpoints.refresh], the config in force or none), `bases` (the lease's `base` and the hosts
 *   a build's first model request asks, through [ModelHosts]);
 * - `answers`, `requests` and the requests of `attempts`, through [QuranLabApi] (each route with its own check: a config
 *   that verifies, a challenge, a token, a lease the Core takes) and, for `models`, [ModelStore]'s manifest and
 *   [ModelDownloader]: the hosts asked in order, the class the request ended on, the host that ended it, `fallback`, the
 *   probe back, and `P` after it (the `web_frame` role is the web SDK's: Android has none). The outcome of each attempt
 *   on the real Core (lease, refused, stop, unreachable and the grace) is LeaseGraceTest.endpointsAttempts;
 * - `downloads` with `downloads_if_range` (one file through [ModelDownloader]: the host, the Range and the If-Range of each
 *   request, the pauses of 16.2 on a scale of 10 ms a second, the end, what is stored, and `P` after it);
 * - `first_manifest`: the folder and the hosts of a build's manifest through [ModelStore.manifestFor]. Android asks for a
 *   lease with the config's `key_id` (`recommended.key_id`), so it needs no manifest before its first lease: the first
 *   manifest comes from the folder the first lease answer names, which qlab-api makes from MODEL_BASE's path, the config's
 *   model, the build and the folder, on the first model host. Each case is replayed with that folder.
 */
@Suppress("UNCHECKED_CAST")
class EndpointsVectorsTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val v: Map<String, Any?> by lazy { ProtectedKit.vector("endpoints_40.json") }
    private fun list(k: String) = v[k] as List<Map<String, Any?>>
    private fun strings(x: Any?): List<String> = (x as List<*>).map { it as String }
    private fun int(x: Any?): Int? = (x as? Number)?.toInt()
    private val roles: Map<String, Any?> get() = v["roles"] as Map<String, Any?>
    private val api: List<String> get() = strings(roles["api"])
    private val models: List<String> get() = strings(roles["models"])

    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-ep-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val app = AppIdentity("org.example.recite", "2.4.0")
    private val key = QuranLabKey.parse("qlk_v1_apps-one_" + "a".repeat(22))!!

    // ------------------------------------------------------------------ the lists

    @Test
    fun theRolesSuffixesAndLimits() {
        assertEquals(api, HostNames.API)
        assertEquals(models, HostNames.MODELS)
        assertEquals(strings(v["suffixes"]), HostNames.SUFFIXES)
        assertEquals(listOf("api", "models"), strings(v["config_roles"]))
        val e = Endpoints()
        assertEquals(api, e.api.hosts)
        assertEquals(models, e.models.hosts)
        val l = v["limits"] as Map<String, Any?>
        assertEquals(int(l["connect_s"])!! * 1000, UrlConnectionTransport.CONNECT_TIMEOUT_MS)
        assertEquals(int(l["probe_s"])!! * 1000L, HostRole.PROBE_MS)
        assertEquals(int(l["max_config_hosts"]), HostNames.MAX_CONFIG_HOSTS)
        assertEquals(int(l["resumes"]), RetryPlan.DEFAULT.pausesMs.size)
        assertEquals((l["pauses"] as List<*>).map { (it as Number).toLong() * 1000 }, RetryPlan.DEFAULT.pausesMs.toList())
    }

    @Test
    fun everyHost() {
        val cases = list("hosts")
        assertEquals(29, cases.size)
        for (c in cases) assertEquals("${c["host"]}", c["allowed"], HostNames.allowed(c["host"] as String))
    }

    /** 28.2: a config's `endpoints` while it is in force, and the built-in lists when none is (the config read at every
     *  request's start, [Endpoints.configured]). */
    @Test
    fun everyConfigList() {
        val cases = list("config_lists")
        assertEquals(17, cases.size)
        for (c in cases) {
            val e = Endpoints(clock = { NOW })
            e.configured = { if (c["in_force"] == true) c["endpoints"] else null }
            e.refresh()
            val lists = c["lists"] as Map<String, Any?>
            assertEquals("${c["name"]}", strings(lists["api"]), e.api.hosts)
            assertEquals("${c["name"]}", strings(lists["models"]), e.models.hosts)
        }
    }

    /** 28.4: a lease's `base`, accepted or not, and the hosts the build's first model request asks with `P` the first
     *  model host and with `P` the standby. */
    @Test
    fun everyBase() {
        val cases = list("bases")
        assertEquals(18, cases.size)
        for (c in cases) {
            val b = c["base"] as String
            val name = c["name"]
            assertEquals("$name", c["accepted"], LeaseClient.baseOk(b))
            val parts = HostNames.base(b)
            if (c["accepted"] != true) {
                assertNull("$name", parts)
                continue
            }
            assertEquals("$name", c["host"] to c["path"], parts)
            val e = Endpoints(clock = { NOW })
            assertEquals("$name", strings(c["asks_with_p_first"]), ModelHosts.of(b, e).plan().hosts)
            e.models.prefer(models[1], NOW)
            assertEquals("$name", strings(c["asks_with_p_standby"]), ModelHosts.of(b, e).plan().hosts)
        }
    }

    // ------------------------------------------------------------------ one request

    /** The `api` hosts answered as a case says ([answers], by host), on the fake clock [now]; [asked] records each host
     *  asked, in order. */
    private inner class ApiRig(hosts: List<String>) {
        var now = NOW
        val asked = ArrayList<String>()
        var answers: Map<String, Any?> = emptyMap()
        var configured: Any? = null
        val endpoints = Endpoints.mapped(hosts, HostNames.MODELS, emptyMap()) { now }.also { it.configured = { configured } }
        val client = QuranLabApi(QuranLabApi.DEFAULT_API, HttpTransport { r ->
            val host = URI(r.url).host
            asked += host
            apiAnswer(answers[host] as Map<String, Any?>? ?: throw AssertionError("$host was asked; the case gives it no answer"), route)
        }, endpoints)
        var route = ""

        /** One request of [route]: the status of the answer the caller got, or of the failure it threw (null: no answer). */
        fun call(route: String): Int? {
            this.route = route
            return try {
                when (route) {
                    "config" -> client.config(Credential.Token("qlt_v1.e30.s"), 7, app, "\"e1\"", null) { a -> if (a.json()?.get("ok") != true) throw NotTaken() }.status
                    "register" -> client.register(Credential.Token("qlt_v1.e30.s"), app, null, false).let { 200 }
                    "token" -> client.attest(key, app).let { 200 }
                    "challenge" -> client.challenge("qlt_v1.e30.s").let { 200 }
                    "attest" -> client.attestInstall(key, linkedMapOf("platform" to "android")).also { lastAnswer = it }.status
                    "model-key" -> client.modelKey("qlt_v1.e30.s", linkedMapOf("v" to 1)) { a ->
                        // the Core refuses this lease (28.3: a 2xx that is not what was asked)
                        if (a.json()?.get("core") == "refused") throw NotTaken()
                    }.also { lastAnswer = it }.status
                    else -> throw AssertionError("route $route")
                }
            } catch (x: CloudException) {
                x.api?.status
            }
        }

        var lastAnswer: HttpResponse? = null
    }

    /** A host's answer of the vectors as the transport gives it: no answer and a stall as the platform's exceptions, else
     *  the status, the body (the route's own JSON, one that fails its check, a page, nothing) and Retry-After. */
    private fun apiAnswer(a: Map<String, Any?>, route: String): HttpResponse {
        when (a["answer"]) {
            "none" -> throw ConnectException("Connection refused")
            "stalled" -> throw SocketTimeoutException("Read timed out")
        }
        val st = int(a["status"])!!
        val h = LinkedHashMap<String, String>()
        int(a["retry_after"])?.let { h["retry-after"] = "$it" }
        val body: ByteArray = when (a["body"]) {
            "html" -> {
                h["content-type"] = "text/html"
                "<html><body>a page that is not the API's</body></html>".toByteArray()
            }
            "empty" -> ByteArray(0)
            "invalid" -> {
                h["content-type"] = "application/json"
                Json.write(mapOf("ok" to false)).toByteArray()
            }
            "json" -> {
                h["content-type"] = "application/json"
                Json.write(when {
                    a["error"] != null -> linkedMapOf<String, Any?>("error" to a["error"], "message" to "m").also { o -> a["reason"]?.let { o["reason"] = it } }
                    st in 200..299 -> own(route) + (if (a["core"] == "refused") mapOf("core" to "refused") else emptyMap())
                    else -> mapOf("message" to "an answer with no error string")
                }).toByteArray()
            }
            else -> throw AssertionError("body ${a["body"]}")
        }
        return HttpResponse(st, h, body)
    }

    /** The route's own answer (what its check takes). */
    private fun own(route: String): Map<String, Any?> = when (route) {
        "config", "register" -> mapOf("ok" to true)
        "challenge" -> mapOf("challenge" to "c1.1795000300.${"A".repeat(22)}.${"B".repeat(22)}", "expires_in" to 300)
        "token", "attest" -> mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400)
        "model-key" -> mapOf("v" to 1, "lease" to "AAAA")
        else -> throw AssertionError("route $route")
    }

    /** What a request across the hosts came to, as the SDK showed it: the hosts asked, the host that ended it (from the
     *  failover the SDK recorded when its first host was unreachable, [Endpoints.lastFailover]), and the class of that
     *  host's answer by the status the caller got. */
    private class Seen(val asked: List<String>, val by: String?, val cls: String, val fallback: Boolean, val probe: Boolean, val p: String)

    private fun seen(asked: List<String>, before: Failover?, after: Failover?, status: Int?, hostsBefore: List<String>, pBefore: String, pAfter: String): Seen {
        val by = if (after !== before) after?.to else asked.firstOrNull()
        val cls = when {
            by == null -> "unreachable"
            status == 429 -> "slow_down"
            status != null && (status in 200..299 || status == 304) -> "taken"
            else -> "refused"
        }
        return Seen(asked.toList(), by, cls, asked.size > 1, pBefore != hostsBefore[0] && asked.firstOrNull() == hostsBefore[0], pAfter)
    }

    private fun apiStep(rig: ApiRig, route: String): Seen {
        rig.asked.clear()
        rig.endpoints.refresh()
        val before = rig.endpoints.lastFailover
        val hosts = rig.endpoints.api.hosts
        val p = rig.endpoints.api.preferred().first
        val status = rig.call(route)
        return seen(rig.asked, before, rig.endpoints.lastFailover, status, hosts, p, rig.endpoints.api.preferred().first)
    }

    /** 28.3's table, per route: one host's answer, its class. The answer is the first host's; a second host answers what
     *  the route asks for, so a request that goes on to it shows the first answer unreachable. */
    @Test
    fun everyAnswer() {
        val cases = list("answers")
        assertEquals(31, cases.size)
        var replayed = 0
        for (c in cases) {
            val route = c["route"] as String
            val a = c["answer"] as Map<String, Any?>
            val got = when (route) {
                "file" -> fileAnswer(a)
                else -> {
                    val rig = ApiRig(api.take(2))
                    rig.answers = mapOf(api[0] to a, api[1] to mapOf("status" to 200.0, "body" to "json", "core" to "accepted"))
                    apiStep(rig, route).let { if (it.asked.size > 1) "unreachable" else it.cls }
                }
            }
            assertEquals("$route $a", c["class"], got)
            replayed++
        }
        assertEquals(31, replayed)
    }

    /** A file's answer of the first model host, with 500 of its 1000 bytes stored (a range request, so a 206 is the file):
     *  the class, from what the download did next. */
    private fun fileAnswer(a: Map<String, Any?>): String {
        val f = file(1000)
        val root = temp()
        File(root, "${f.first.path}.part").writeBytes(f.second.copyOf(500))
        val e = Endpoints(clock = { NOW })
        val rig = ModelRig(e, f.second, emptyMap())
        rig.byHost = mapOf(models[0] to a, models[1] to mapOf("status" to 206.0, "body" to "bytes"))
        val d = ModelDownloader(root, BASE, rig.transport, null, RangePlan.DEFAULT, RetryPlan(PAUSES), ModelHosts.of(BASE, e))
        val failure = try {
            d.fetch(f.first)
            null
        } catch (x: TajweedError) {
            x
        }
        return when {
            rig.seen.any { it.host == models[1] } -> "unreachable"
            failure == null -> "taken"
            failure.cause40?.reason == "rate_limited" -> "slow_down"
            else -> "refused"
        }
    }

    /** 28.3 over time: each step's hosts asked, the host that ended it, the class, fallback, the probe and `P` after it;
     *  a step with a list is a config whose list changes (28.2). The `models` sequences through the manifest and the
     *  files; `web_frame` is the web SDK's. */
    @Test
    fun everyRequestSequence() {
        val cases = list("requests")
        assertEquals(24, cases.size)
        var replayed = 0
        for (case in cases) {
            val name = case["name"]
            when (case["role"]) {
                "api" -> {
                    val rig = ApiRig(strings(case["hosts"]))
                    for ((i, step) in (case["steps"] as List<Map<String, Any?>>).withIndex()) {
                        if (step.containsKey("list")) {
                            rig.configured = mapOf("api" to step["list"])
                            rig.endpoints.refresh()
                            assertEquals("$name, step $i", step["p_after"], rig.endpoints.api.preferred().first)
                            continue
                        }
                        rig.now = NOW + (step["at"] as Number).toLong() * 1000
                        rig.answers = step["answers"] as Map<String, Any?>
                        check("$name, step $i", step, apiStep(rig, step["route"] as String))
                    }
                }
                "models" -> modelSequence(case)
                "web_frame" -> continue
                else -> throw AssertionError("role ${case["role"]}")
            }
            replayed++
        }
        assertEquals(23, replayed)
    }

    private fun check(what: String, want: Map<String, Any?>, got: Seen) {
        assertEquals("$what: asked", strings(want["asked"]), got.asked)
        assertEquals("$what: by", want["by"], got.by)
        assertEquals("$what: class", want["class"], got.cls)
        assertEquals("$what: fallback", want["fallback"], got.fallback)
        want["probe"]?.let { assertEquals("$what: probe", it, got.probe) }
        want["p_after"]?.let { assertEquals("$what: P after", it, got.p) }
    }

    /** The `models` sequences: the manifest through [ModelStore] (a manifest that verifies, the contract's), then files. */
    private fun modelSequence(case: Map<String, Any?>) {
        val e = Endpoints(clock = { now })
        val manifest = Json.write(ProtectedKit.vector("manifest_40.json")["manifest"]).toByteArray()
        val rig = ModelRig(e, ByteArray(0), emptyMap(), manifest)
        for ((i, step) in (case["steps"] as List<Map<String, Any?>>).withIndex()) {
            now = NOW + (step["at"] as Number).toLong() * 1000
            rig.byHost = step["answers"] as Map<String, Any?>
            rig.seen.clear()
            val before = e.lastFailover
            val hosts = e.models.hosts
            val p = e.models.preferred().first
            val failure: TajweedError? = try {
                when (step["route"]) {
                    "manifest" -> {
                        ModelStore(temp(), BASE, rig.transport, null, ManifestVerifier(listOf(ProtectedKit.signerPublic), sdkVersion = "4.0.0"),
                            retry = RetryPlan(PAUSES), endpoints = e).manifest(refresh = true)
                    }
                    "file" -> {
                        val f = file(1000 + i)
                        rig.bytes = f.second
                        ModelDownloader(temp(), BASE, rig.transport, null, RangePlan.DEFAULT, RetryPlan(PAUSES), ModelHosts.of(BASE, e)).fetch(f.first)
                    }
                    else -> throw AssertionError("route ${step["route"]}")
                }
                null
            } catch (x: TajweedError) {
                x
            }
            val status = if (failure == null) 200 else failure.cause40?.httpStatus
            check("${case["name"]}, step $i", step, seen(rig.seen.map { it.host }, before, e.lastFailover, status, hosts, p, e.models.preferred().first))
        }
    }

    @Volatile private var now = NOW

    /** The requests of each lease attempt (28.5), one request of 28.3 each: the hosts asked, the class, the host that
     *  ended it, fallback; and the Retry-After the attempt goes on with when it ended unreachable (the largest any host
     *  gave, or the 429's). The outcome on the real Core: LeaseGraceTest.endpointsAttempts. */
    @Test
    fun theRequestsOfEveryAttempt() {
        val cases = list("attempts")
        assertEquals(10, cases.size)
        for (c in cases) {
            val rig = ApiRig(api)
            val requests = c["requests"] as List<Map<String, Any?>>
            for ((i, q) in requests.withIndex()) {
                rig.answers = q["answers"] as Map<String, Any?>
                rig.lastAnswer = null
                check("${c["name"]}, request $i (${q["route"]})", q, apiStep(rig, q["route"] as String))
            }
            int(c["retry_after"])?.let { ra ->
                assertEquals("${c["outcome"]}", "unreachable", c["outcome"])
                assertEquals("${c["name"]}: the Retry-After the attempt goes on with", ra, rig.lastAnswer!!.retryAfter)
            }
        }
    }

    /**
     * 28.9 is the server's rules and no SDK replays `standby_40.json`; this is the SDK's side of it, through the same rig:
     * a standby's own `503 service_unavailable` is unreachable (the next host is asked), a refusal it passes on is final,
     * its 429 a slow down, a 5xx it passes on unreachable. Each `passes` case of the SDK's routes but the 2xx and 3xx
     * answers (taken from any host as from the first), with the standby the first host asked.
     */
    @Test
    fun aStandbysAnswersAreReadBy283() {
        val passes = ProtectedKit.vector("standby_40.json")["passes"] as List<Map<String, Any?>>
        var n = 0
        for (c in passes) {
            val route = c["route"] as String
            if (route !in setOf("config", "register", "challenge", "attest", "model-key", "file")) continue
            val up = c["upstream"] as Map<String, Any?>
            val st = int(up["status"])!!
            if (st in 200..399) continue
            val json = up["json"] as Map<String, Any?>?
            val unavailable = c["standby"] == "unavailable"
            val given: Map<String, Any?> = if (unavailable) mapOf("status" to 503.0, "body" to "json", "error" to "service_unavailable", "retry_after" to 30.0)
                else mapOf("status" to st.toDouble(), "body" to if (json == null) "html" else "json", "error" to json?.get("error"), "reason" to json?.get("reason"))
            val want = when {
                unavailable || st >= 500 -> "unreachable"
                st == 429 -> "slow_down"
                else -> "refused"
            }
            val got = if (route == "file") fileAnswer(given) else {
                val rig = ApiRig(api.take(2))
                rig.answers = mapOf(api[0] to given, api[1] to mapOf("status" to 200.0, "body" to "json", "core" to "accepted"))
                apiStep(rig, route).let { if (it.asked.size > 1) "unreachable" else it.cls }
            }
            assertEquals("${c["name"]} ($route $st, ${c["standby"]})", want, got)
            n++
        }
        assertEquals(42, n)
    }

    // ------------------------------------------------------------------ downloads

    /** A file of [size] bytes and its manifest entry (stored as it is). */
    private fun file(size: Int): Pair<ModelManifest.FileEntry, ByteArray> {
        val bytes = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }
        return TestKit.F("layers-$size.zfw", bytes, "weights", gz = false).entry() to bytes
    }

    private class Req(val host: String, val range: String?, val ifRange: String?, val atMs: Double)

    /**
     * The `models` hosts: each request's answer from [byHost] (by host) or else the next of [inOrder]; [bytes] the file
     * every bytes answer is cut from, [etags] each host's ETag, [manifest] the body of a manifest's JSON answer.
     */
    private class ModelRig(val e: Endpoints, @Volatile var bytes: ByteArray, val etags: Map<String, String>, val manifest: ByteArray? = null) {
        @Volatile var byHost: Map<String, Any?>? = null
        var inOrder: List<Map<String, Any?>> = emptyList()
        private var next = 0
        val seen = java.util.Collections.synchronizedList(ArrayList<Req>())
        private val t0 = System.nanoTime()

        val transport = ModelTransport { url, headers ->
            val host = URI(url).host
            seen += Req(host, headers["Range"], headers["If-Range"], (System.nanoTime() - t0) / 1e6)
            val a = byHost?.let { it[host] as Map<String, Any?>? ?: throw AssertionError("$host was asked; the case gives it no answer") }
                ?: inOrder.getOrNull(next++) ?: throw AssertionError("request ${seen.size} ($host ${headers["Range"]}) is more than the case has")
            answer(a, host, headers["Range"])
        }

        private fun int(x: Any?): Int? = (x as? Number)?.toInt()

        fun answer(a: Map<String, Any?>, host: String, range: String?): ModelResponse {
            when (a["answer"]) {
                "none" -> throw ConnectException("Connection refused")
                "stalled" -> throw SocketTimeoutException("Read timed out")
            }
            val st = int(a["status"])!!
            val size = bytes.size
            val h = LinkedHashMap<String, String>()
            int(a["retry_after"])?.let { h["retry-after"] = "$it" }
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            return when (a["body"]) {
                "bytes" -> {
                    val start = if (st == 206) from else 0
                    etags[host]?.let { h["etag"] = it }
                    if (st == 206) h["content-range"] = "bytes $start-${size - 1}/$size"
                    h["content-length"] = "${size - start}"
                    val n = int(a["deliver"]) ?: (size - start)
                    ModelResponse(st, h, Cut(bytes.copyOfRange(start, start + n), a["end"] as String? ?: "complete"))
                }
                // a 206 whose total is another file's
                "invalid" -> {
                    h["content-range"] = "bytes $from-${size - 1}/${size * 2}"
                    h["content-length"] = "${size - from}"
                    ModelResponse(st, h, ByteArrayInputStream(bytes.copyOfRange(from, size)))
                }
                "json" -> {
                    h["content-type"] = "application/json"
                    val body = if (a["error"] != null) Json.write(mapOf("error" to a["error"], "message" to "m")).toByteArray()
                        else manifest ?: throw AssertionError("no manifest to answer with")
                    ModelResponse(st, h, ByteArrayInputStream(body))
                }
                "html" -> {
                    h["content-type"] = "text/html"
                    ModelResponse(st, h, ByteArrayInputStream("<html><body>not the CDN</body></html>".toByteArray()))
                }
                "empty" -> ModelResponse(st, h, ByteArrayInputStream(ByteArray(0)))
                else -> throw AssertionError("body ${a["body"]}")
            }
        }
    }

    /** An answer's body that ends as the case says: complete, a reset connection, or a stall (the read's time limit). */
    private class Cut(private val data: ByteArray, private val end: String) : InputStream() {
        private var at = 0
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at < data.size) {
                val n = minOf(len, data.size - at)
                System.arraycopy(data, at, b, off, n)
                at += n
                return n
            }
            return when (end) {
                "complete" -> -1
                "reset" -> throw SocketException("Connection reset")
                "stall" -> throw SocketTimeoutException("Read timed out")
                else -> throw AssertionError("end $end")
            }
        }
    }

    /** 16.2 across hosts (28.4): each case's requests (the host, the Range, the If-Range: none where the case says none,
     *  and the validator only to the host that gave it, `downloads_if_range`) and the pause before each, the end, what
     *  is stored, and `P` after it. */
    @Test
    fun everyDownload() {
        val cases = list("downloads")
        assertEquals(13, cases.size)
        for (c in cases) {
            val name = c["name"]
            val size = int(c["size"])!!
            val (entry, bytes) = file(size)
            val e = Endpoints(clock = { NOW })
            (c["p_before"] as String).let { if (it != models[0]) e.models.prefer(it, NOW) }
            val rig = ModelRig(e, bytes, c["etags"] as Map<String, String>)
            rig.inOrder = c["answers"] as List<Map<String, Any?>>
            val root = temp()
            val d = ModelDownloader(root, BASE, rig.transport, null, RangePlan.DEFAULT, RetryPlan(PAUSES), ModelHosts.of(BASE, e))
            val end = try {
                if (d.fetch(entry).ok) "installed" else "not installed"
            } catch (x: TajweedError) {
                when (x.cause40?.reason) {
                    "refused" -> "refused"
                    "rate_limited" -> "rate_limited"
                    else -> x.code
                }
            }
            val want = c["requests"] as List<Map<String, Any?>>
            val got = rig.seen.toList()
            assertEquals("$name: requests ${got.map { "${it.host} ${it.range} ${it.ifRange}" }}", want.size, got.size)
            for ((i, w) in want.withIndex()) {
                val g = got[i]
                assertEquals("$name, request $i: host", w["host"], g.host)
                assertEquals("$name, request $i: Range", w["range"], g.range)
                // downloads_if_range: none where the case gives none; the case's validator or none where it gives one
                if (w["if_range"] == null) assertNull("$name, request $i: If-Range", g.ifRange)
                else assertTrue("$name, request $i: If-Range ${g.ifRange}", g.ifRange == null || g.ifRange == w["if_range"])
                if (i > 0) {
                    val pause = (w["pause_before"] as Number).toDouble() * SCALE
                    assertTrue("$name, request $i: ${g.atMs - got[i - 1].atMs} ms after the one before, the pause is $pause", g.atMs - got[i - 1].atMs >= pause * 0.9)
                }
            }
            assertEquals("$name: the end", c["end"], end)
            val kept = d.local(entry)
            val stored = if (kept.isFile) kept.length() else File(root, "${entry.path}.part").let { if (it.isFile) it.length() else 0L }
            if (end == "installed") assertTrue("$name: the file is the manifest's", kept.readBytes().contentEquals(bytes))
            assertEquals("$name: stored", (c["stored"] as Number).toLong(), stored)
            assertEquals("$name: P after", c["p_after"], e.models.preferred().first)
        }
    }

    // ------------------------------------------------------------------ the first manifest

    /** 28.4: the folder and the hosts of a build's manifest; every host gives no answer here, so each is asked in turn. */
    @Test
    fun everyFirstManifest() {
        val cases = list("first_manifest")
        assertEquals(3, cases.size)
        val m = ProtectedKit.vector("manifest_40.json")["manifest"] as Map<String, Any?>
        val keyId = (m["encryption"] as Map<String, Any?>)["key_id"] as String
        for (c in cases) {
            val r = c["recommended"] as Map<String, Any?>
            val folder = "/models/${r["model"]}/${r["revision"]}/${r["folder"]}/"
            assertEquals("${c["name"]}", c["path"], folder + ModelManifest.FILE_NAME)
            // no lease yet: the folder the first lease answer names (qlab-api's MODEL_BASE on the first model host)
            val base = c["lease_base"] as String? ?: "https://${models[0]}$folder"
            for ((p, want) in listOf(models[0] to c["asks_with_p_first"], models[1] to c["asks_with_p_standby"])) {
                val e = Endpoints(clock = { NOW })
                if (p != models[0]) e.models.prefer(p, NOW)
                val asked = ArrayList<String>()
                val transport = ModelTransport { url, _ ->
                    val u = URI(url)
                    asked += u.host
                    assertEquals("${c["name"]}: the path", c["path"], u.path)
                    throw ConnectException("Connection refused")
                }
                val store = ModelStore(temp(), "", transport, null, ManifestVerifier(listOf(ProtectedKit.signerPublic), sdkVersion = "4.0.0"), endpoints = e)
                try {
                    store.manifestFor(base, keyId)
                    fail("a manifest no host gave")
                } catch (_: TajweedError) {
                }
                assertEquals("${c["name"]}, P $p", strings(want), asked)
            }
        }
    }

    private companion object {
        const val NOW = 1_795_000_000_000L
        const val BASE = "https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b/sdk/v4.0/"
        /** 16.2's pauses on a scale of [SCALE] ms a second. */
        const val SCALE = 10.0
        val PAUSES = longArrayOf(10, 20, 40, 80, 160)
    }
}
