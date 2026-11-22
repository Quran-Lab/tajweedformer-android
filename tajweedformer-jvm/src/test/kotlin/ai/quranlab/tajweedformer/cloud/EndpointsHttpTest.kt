package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ModelDownloader
import ai.quranlab.tajweedformer.ModelHosts
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.RangePlan
import ai.quranlab.tajweedformer.RetryPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Contract 28.8's checks on the SDK's real HTTP code (`HttpURLConnection` through both transports) against stand-ins
 * for two hosts of a role, mapped as a test build maps its hosts (28.1): the first `api` host refuses the connection or
 * answers 503, and the standby answers, is `P` for the next request, and the failover is in diagnose's `lastFailover`
 * and in a silent report (check 1, check 8); a 429 is the answer, no other host asked (check 2); a model file cut on
 * the first CDN goes on at the standby from the stored bytes, with Range and no If-Range, and installs at the manifest's
 * size and SHA-256 (check 4). The order, the classes and the sticky host on every case of the vectors:
 * EndpointsVectorsTest.
 */
class EndpointsHttpTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val first = StubQlab()
    private val standby = StubQlab()
    private val served = ArrayList<ServedModel>()
    private val dirs = ArrayList<File>()

    @After
    fun down() {
        first.close()
        standby.close()
        served.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-ephttp-").toFile().also { dirs += it }

    /** An address where nothing listens: a connection to it is refused at once. */
    private fun refused(): String = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { "http://127.0.0.1:${it.localPort}" }

    private val apiHosts = listOf(HostNames.API[0], HostNames.API[1])

    /** The cloud side with the first `api` host at [firstOrigin] and the standby's at the second stand-in. */
    private fun cloud(firstOrigin: String): QuranLabCloud {
        val e = Endpoints.mapped(apiHosts, HostNames.MODELS, mapOf(apiHosts[0] to firstOrigin, apiHosts[1] to standby.base))
        return QuranLabCloud(CloudOptions(first.key, apiUrl = first.base, trustedKeys = listOf(first.publicKey, standby.publicKey), endpoints = e),
            first.environment(temp()))
    }

    @Test
    fun theFirstHostRefusesTheConnectionAndTheStandbyAnswers() {
        val c = cloud(refused())
        assertNotNull("the config came from the standby", c.refreshConfig())
        assertEquals(apiHosts[1], c.endpoints.api.preferred().first)
        val f = c.endpoints.lastFailover!!
        assertEquals(listOf("api", apiHosts[0], apiHosts[1], "network", null), listOf(f.role, f.from, f.to, f.reason, f.status))
        // the next request goes to the standby first: no failover, the standby asked again
        val before = standby.seen.count { it.path == "/v1/config" }
        assertNotNull(c.refreshConfig())
        assertSame(f, c.endpoints.lastFailover)
        assertEquals(before + 1, standby.seen.count { it.path == "/v1/config" })
        // diagnose's endpoints (28.7) and the silent failover report: the role, fallback, the first host's reason, no host
        val d = c.endpoints.diagnosis()
        assertEquals(listOf("api", "models", "lastFailover"), d.keys.toList())
        assertEquals(apiHosts[1], d["api"])
        assertEquals(HostNames.MODELS[0], d["models"])
        val q = c.errors.queued().filter { it.reason == "failover" }
        assertTrue("a silent failover report: ${c.errors.queued().map { "${it.stage}/${it.reason}/${it.code}" }}", q.isNotEmpty())
        for (r in q) {
            assertEquals("none", r.code)
            assertEquals("api", r.detail["host_role"])
            assertEquals(true, r.detail["fallback"])
            assertEquals("network", r.detail["error_code"])
            assertTrue(ai.quranlab.tajweedformer.Json.write(r.detail).let { apiHosts.none { h -> it.contains(h) } })
        }
    }

    @Test
    fun theFirstHostAnswers503AndTheStandbyAnswers() {
        first.everything = { StubQlab.Reply.error(503, "service_unavailable") }
        val c = cloud(first.base)
        assertNotNull(c.refreshConfig())
        assertEquals(apiHosts[1], c.endpoints.api.preferred().first)
        assertEquals(listOf("http", 503), c.endpoints.lastFailover!!.let { listOf(it.reason, it.status) })
        assertTrue(first.seen.isNotEmpty())
    }

    @Test
    fun a429OnTheFirstHostIsTheAnswerAndNoOtherHostIsAsked() {
        first.everything = { StubQlab.Reply(429, """{"error":"rate_limited","message":"slow down"}""".toByteArray(), mapOf("content-type" to "application/json", "retry-after" to "60")) }
        val c = cloud(first.base)
        assertNull(c.refreshConfig())
        assertTrue(first.seen.isNotEmpty())
        assertEquals("the standby was never asked", 0, standby.seen.size)
        assertEquals(apiHosts[0], c.endpoints.api.preferred().first)
        assertNull(c.endpoints.lastFailover)
    }

    /** The two models hosts served by [a] and [b], with the holds of [holds] (a new process: a new [Endpoints]). */
    private fun twoHosts(a: ServedModel, b: ServedModel, holds: RouteHolds?): Endpoints {
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        return Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to a.url, hosts[1] to b.url)).also { it.holds = holds }
    }

    private fun downloader(a: ServedModel, ep: Endpoints, retry: RetryPlan = RetryPlan.NONE): ModelDownloader {
        val base = "https://${HostNames.MODELS[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        return ModelDownloader(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null, RangePlan.DEFAULT, retry, ModelHosts.of(base, ep))
    }

    /**
     * Contract 28.11 item 2 (e330c08): on the models role a Retry-After hold is per file path and host. A host answers
     * an accelerator file 503 with Retry-After 3600 (a standby holds the CPU set only): that path is held there, nothing
     * else, and a CPU file is still asked there. The test build has no NPU file: its calibration file stands in for the
     * accelerator build's.
     */
    @Test
    fun aRetryAfterOnAnAcceleratorFileHoldsNoOtherFileOnItsHost() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val m = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes())
        val npu = m.files.first { it.localPath == "calibration.json" }
        val cpu = m.files.first { it.localPath == "layers.zfw" }
        val held = { p: String, _: Int -> if (p == npu.path) ServedModel.Answer(503, """{"error":"not_held","message":"accelerator builds are not on this host"}""",
            mapOf("retry-after" to "3600")) else null }
        a.answer = held
        b.answer = held
        var now = System.currentTimeMillis()
        val ep = twoHosts(a, b, RouteHolds(null) { now })
        val d = downloader(a, ep)
        val failed = runCatching { d.fetch(npu) }.exceptionOrNull()
        assertTrue("the held file failed: $failed", failed != null)
        assertEquals("the held file asked of the first host once", 1, a.requests.count { it.path == npu.path })
        // the CPU file: asked of that same first host, and served there
        val r = d.fetch(cpu)
        assertTrue(r.ok)
        assertEquals("the CPU file on the first host", listOf(200), a.requests.filter { it.path == cpu.path }.map { it.status })
        assertEquals("the CPU file asked of the other host", 0, b.requests.count { it.path == cpu.path })
        // the accelerator file again within the hour: not asked of the first host
        now += 60_000
        assertTrue(runCatching { downloader(a, twoHosts(a, b, ep.holds)).fetch(npu) }.isFailure)
        assertEquals("the held path asked again on its host", 1, a.requests.count { it.path == npu.path })
    }

    /**
     * Contract 28.11 items 2 and 3: a CPU file's 429 with Retry-After 90 on the first host holds that path there across
     * calls and a restart. The next call within the 90 s asks nothing and fails as the 429 did (a 429 is never taken to
     * another host, 28.3); after the 90 s it is asked there again.
     */
    @Test
    fun a429OnAModelFileHoldsItsPathOnItsHostAcrossCallsAndARestart() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val e = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "layers.zfw" }
        a.answer = { p, n -> if (p == e.path && n == 1) ServedModel.Answer(429, """{"error":"rate_limited","message":"too many requests"}""",
            mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        val first = runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now })).fetch(e) }.exceptionOrNull() as ai.quranlab.tajweedformer.TajweedError
        assertEquals(1, a.requests.count { it.path == e.path })
        // 30 s later, the same process: no request, the same failure
        now += 30_000
        val again = runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now })).fetch(e) }.exceptionOrNull() as ai.quranlab.tajweedformer.TajweedError
        assertEquals(first.code, again.code)
        assertEquals("rate_limited", again.errorCause?.reason)
        // a restart 10 s later: the hold is read from the device
        now += 10_000
        assertTrue(runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now })).fetch(e) }.isFailure)
        assertEquals("a request inside the 90 s", 1, a.requests.count { it.path == e.path })
        assertEquals("another host asked for a 429", 0, b.requests.count { it.path == e.path })
        // after the 90 s: asked there again, and served
        now += 51_000
        assertTrue(downloader(a, twoHosts(a, b, RouteHolds(file) { now })).fetch(e).ok)
        assertEquals(listOf(429, 200), a.requests.filter { it.path == e.path }.map { it.status })
    }

    /**
     * Contract 28.11 item 2: a 503 with Retry-After 90 on a model file moves on to the next host at once, and holds that
     * path on the first host: a new process within the 90 s (its order starts at the first host again) goes to the next
     * host without asking the first; after the 90 s the first host is asked again.
     */
    @Test
    fun a503WithRetryAfterOnAModelFileSkipsItsHostForThatPathUntilItEnds() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val e = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "layers.zfw" }
        a.answer = { p, n -> if (p == e.path && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        assertTrue(downloader(a, twoHosts(a, b, RouteHolds(file) { now }), RetryPlan(longArrayOf(10, 10))).fetch(e).ok)
        assertEquals(listOf(503), a.requests.filter { it.path == e.path }.map { it.status })
        assertEquals(listOf(200), b.requests.filter { it.path == e.path }.map { it.status })
        // a restart 40 s later: straight to the next host
        now += 40_000
        assertTrue(downloader(a, twoHosts(a, b, RouteHolds(file) { now }), RetryPlan(longArrayOf(10, 10))).fetch(e).ok)
        assertEquals("the first host asked inside the 90 s", 1, a.requests.count { it.path == e.path })
        assertEquals(2, b.requests.count { it.path == e.path })
        // after the 90 s: the first host again
        now += 51_000
        assertTrue(downloader(a, twoHosts(a, b, RouteHolds(file) { now }), RetryPlan(longArrayOf(10, 10))).fetch(e).ok)
        assertEquals(listOf(503, 200), a.requests.filter { it.path == e.path }.map { it.status })
    }

    /** The two models hosts of [twoHosts], with the failover reports their requests send (28.7) kept in [reports]. */
    private fun twoHosts(a: ServedModel, b: ServedModel, holds: RouteHolds?, reports: MutableList<Failover>): Endpoints =
        twoHosts(a, b, holds).also { it.onFailover = { f -> reports += f } }

    private fun Failover.fields(): List<Any?> = listOf(role, from, to, reason, status, stage)

    /**
     * 28.7 with 28.11 item 2: the silent `failover` report is a first host that the request asked and found unreachable.
     * A model file's 503 with Retry-After 90 is one report in the process that got it. After a restart the hold is read
     * from the device and the file goes to the next host without a request to the first: no report. The held attempt
     * still uses one of the file's resumes and its pause (24.2 `retries`), and diagnose's `lastFailover` still names the
     * move, as before.
     */
    @Test
    fun aHeld503AfterARestartSendsNoFailoverReport() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val e = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "layers.zfw" }
        a.answer = { p, n -> if (p == e.path && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        // the process that got the 503: the first host was asked, and one report says so
        val first = java.util.Collections.synchronizedList(ArrayList<Failover>())
        assertTrue(downloader(a, twoHosts(a, b, RouteHolds(file) { now }, first), RetryPlan(longArrayOf(10))).fetch(e).ok)
        assertEquals(listOf(listOf("models", hosts[0], hosts[1], "http", 503, "download")), first.map { it.fields() })
        // a restart 40 s later: the next host is asked, the first is not, and no report goes out
        now += 40_000
        val restarted = java.util.Collections.synchronizedList(ArrayList<Failover>())
        val ep = twoHosts(a, b, RouteHolds(file) { now }, restarted)
        assertTrue(downloader(a, ep, RetryPlan(longArrayOf(10))).fetch(e).ok)
        assertEquals("the first host asked inside the 90 s", 1, a.requests.count { it.path == e.path })
        assertEquals(listOf(200, 200), b.requests.filter { it.path == e.path }.map { it.status })
        // diagnose's lastFailover as before: the held host, the host that answered, the held answer's reason and status
        assertEquals(listOf("models", hosts[0], hosts[1], "http", 503, "download"), ep.lastFailover!!.fields())
        // the held attempt is one of the file's resumes: with one resume, the next host is asked once, and when it fails
        // the call's failure counts that one resume (24.2 retries)
        b.answer = { p, _ -> if (p == e.path) ServedModel.Answer(500, "", emptyMap()) else null }
        val failing = java.util.Collections.synchronizedList(ArrayList<Failover>())
        val x = runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now }, failing), RetryPlan(longArrayOf(10))).fetch(e) }
            .exceptionOrNull() as ai.quranlab.tajweedformer.TajweedError
        assertEquals(1, x.cause40?.retries)
        assertEquals(1, a.requests.count { it.path == e.path })
        assertEquals(listOf(200, 200, 500), b.requests.filter { it.path == e.path }.map { it.status })
        assertEquals(emptyList<List<Any?>>(), failing.map { it.fields() })
        // no failover report for the host the restarted process did not ask (4.0.2 before the fix: one)
        assertEquals("a failover report for a host that was not asked", emptyList<List<Any?>>(), restarted.map { it.fields() })
    }

    /**
     * 28.7 `fallback` on the error report (24.2): true only when a request of the call went to more than one host. A model
     * file whose first host answers a 503 with Retry-After 90 and whose next host fails too: both were asked, fallback
     * true. After a restart the first host is held and not asked, and the next host fails again: one host was asked,
     * fallback false. The held attempt is still one of the file's resumes.
     */
    @Test
    fun aHostSkippedForAHoldIsNoFallback() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val e = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "layers.zfw" }
        a.answer = { p, n -> if (p == e.path && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        b.answer = { p, _ -> if (p == e.path) ServedModel.Answer(500, "", emptyMap()) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        fun fetch() = runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now }), RetryPlan(longArrayOf(10))).fetch(e) }
            .exceptionOrNull() as ai.quranlab.tajweedformer.TajweedError
        val asked = fetch().cause40!!
        assertEquals(listOf("models", true, 1), listOf(asked.hostRole, asked.fallback, asked.retries))
        // a restart 40 s later: the first host held, not asked; the next host asked and failing
        now += 40_000
        val held = fetch().cause40!!
        assertEquals("the first host asked inside the 90 s", 1, a.requests.count { it.path == e.path })
        assertEquals(listOf(500, 500), b.requests.filter { it.path == e.path }.map { it.status })
        assertEquals(listOf("models", 1), listOf(held.hostRole, held.retries))
        assertEquals("fallback for a host that was not asked", false, held.fallback)
    }

    /** The same for the manifest (one request across the model hosts, 28.3): the held host is not asked, no fallback. */
    @Test
    fun aManifestHostSkippedForAHoldIsNoFallback() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val name = ModelManifest.FILE_NAME
        a.answer = { p, n -> if (p == name && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        b.answer = { p, _ -> if (p == name) ServedModel.Answer(500, "", emptyMap()) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        fun manifest(): ErrorCause {
            val ep = twoHosts(a, b, RouteHolds(file) { now })
            val x = runCatching {
                ai.quranlab.tajweedformer.ModelStore(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null,
                    ai.quranlab.tajweedformer.TestKit.verifier, retry = RetryPlan(longArrayOf(10)), endpoints = ep).manifest(refresh = true)
            }.exceptionOrNull()
            assertNotNull("the manifest failed", x)
            return ErrorCause.of(x!!)
        }
        val asked = manifest()
        assertEquals(listOf("manifest", "models", true), listOf(asked.stage, asked.hostRole, asked.fallback))
        now += 40_000
        val held = manifest()
        assertEquals("the first host asked inside the 90 s", 1, a.requests.count { it.path == name })
        assertEquals(listOf(500, 500), b.requests.filter { it.path == name }.map { it.status })
        assertEquals(listOf("manifest", "models"), listOf(held.stage, held.hostRole))
        assertEquals("fallback for a host that was not asked", false, held.fallback)
    }

    /** The first three models hosts served by [a], [b] and [c], with the holds of [holds] and the reports kept in [reports]. */
    private fun threeHosts(a: ServedModel, b: ServedModel, c: ServedModel, holds: RouteHolds?, reports: MutableList<Failover>): Endpoints {
        val hosts = HostNames.MODELS.take(3)
        return Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to a.url, hosts[1] to b.url, hosts[2] to c.url))
            .also { it.holds = holds; it.onFailover = { f -> reports += f } }
    }

    private fun reports() = java.util.Collections.synchronizedList(ArrayList<Failover>())

    /**
     * 28.7 and ERRORS_40 `failover` ("answered on another of Quran Lab's hosts"): a report needs the host the request
     * ended on to have been asked. A model file's first host fails (a 500), and the next host answers a 429 with
     * Retry-After 90: both asked, one report, fallback true. After a restart the first host fails again and the next
     * host's path is held by that 429: the request ends on the held answer, asks no one else, sends no report, and
     * fallback is false. The control, on three hosts: first asked and unreachable, second held by a 503, third answers:
     * one report.
     */
    @Test
    fun aRequestThatEndsOnAHeld429SendsNoFailoverReport() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val c = ServedModel.ofFolder(a.root).also { served += it }
        val hosts = HostNames.MODELS.take(3)
        val e = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "layers.zfw" }
        a.answer = { p, _ -> if (p == e.path) ServedModel.Answer(500, "", emptyMap()) else null }
        b.answer = { p, n -> if (p == e.path && n == 1) ServedModel.Answer(429, """{"error":"rate_limited","message":"too many requests"}""",
            mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        fun fetch(r: MutableList<Failover>) = runCatching { downloader(a, twoHosts(a, b, RouteHolds(file) { now }, r), RetryPlan(longArrayOf(10))).fetch(e) }
            .exceptionOrNull() as ai.quranlab.tajweedformer.TajweedError
        val first = reports()
        val asked = fetch(first).cause40!!
        assertEquals(listOf("rate_limited", true), listOf(asked.reason, asked.fallback))
        assertEquals(listOf(listOf("models", hosts[0], hosts[1], "http", 500, "download")), first.map { it.fields() })
        // a restart 40 s later: the first host fails again, the next host's path is held by the 429
        now += 40_000
        val restarted = reports()
        val held = fetch(restarted).cause40!!
        assertEquals(2, a.requests.count { it.path == e.path })
        assertEquals("the held host asked inside the 90 s", 1, b.requests.count { it.path == e.path })
        assertEquals(listOf("rate_limited", false), listOf(held.reason, held.fallback))
        // the control: a 503 with Retry-After holds the second host, the third answers
        val e2 = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes()).files.first { it.localPath == "frontend.zfw" }
        a.answer = { p, _ -> if (p == e.path || p == e2.path) ServedModel.Answer(500, "", emptyMap()) else null }
        b.answer = { p, n -> if (p == e2.path && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        val setHold = reports()
        assertTrue(downloader(a, threeHosts(a, b, c, RouteHolds(file) { now }, setHold), RetryPlan(longArrayOf(10, 10))).fetch(e2).ok)
        val control = reports()
        assertTrue(downloader(a, threeHosts(a, b, c, RouteHolds(file) { now + 10_000 }, control), RetryPlan(longArrayOf(10, 10))).fetch(e2).ok)
        assertEquals("the held second host asked once", 1, b.requests.count { it.path == e2.path })
        assertEquals(listOf(listOf("models", hosts[0], hosts[2], "http", 500, "download")), control.map { it.fields() })
        // no report for a request that ended on a held answer (4.0.2 before the fix: one, naming the held host)
        assertEquals("a failover report for a held answer", emptyList<List<Any?>>(), restarted.map { it.fields() })
    }

    /** The same for the manifest (one request across the model hosts, 28.3). */
    @Test
    fun aManifestThatEndsOnAHeld429SendsNoFailoverReport() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val c = ServedModel.ofFolder(a.root).also { served += it }
        val hosts = HostNames.MODELS.take(3)
        val name = ModelManifest.FILE_NAME
        a.answer = { p, _ -> if (p == name) ServedModel.Answer(500, "", emptyMap()) else null }
        b.answer = { p, n -> if (p == name && n == 1) ServedModel.Answer(429, """{"error":"rate_limited","message":"too many requests"}""",
            mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        fun store(ep: Endpoints) = ai.quranlab.tajweedformer.ModelStore(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null,
            ai.quranlab.tajweedformer.TestKit.verifier, retry = RetryPlan(longArrayOf(10)), endpoints = ep)
        fun manifest(ep: Endpoints): ErrorCause {
            val x = runCatching { store(ep).manifest(refresh = true) }.exceptionOrNull()
            assertNotNull("the manifest failed", x)
            return ErrorCause.of(x!!)
        }
        val first = reports()
        val asked = manifest(twoHosts(a, b, RouteHolds(file) { now }, first))
        assertEquals(listOf("rate_limited", true), listOf(asked.reason, asked.fallback))
        assertEquals(listOf(listOf("models", hosts[0], hosts[1], "http", 500, "manifest")), first.map { it.fields() })
        now += 40_000
        val restarted = reports()
        val held = manifest(twoHosts(a, b, RouteHolds(file) { now }, restarted))
        assertEquals(2, a.requests.count { it.path == name })
        assertEquals("the held host asked inside the 90 s", 1, b.requests.count { it.path == name })
        assertEquals(listOf("rate_limited", false), listOf(held.reason, held.fallback))
        // the control on three hosts: the second held by a 503 (a new holds file), the third answers
        val file2 = File(temp(), "holds2.json")
        b.answer = { p, _ -> if (p == name) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        store(threeHosts(a, b, c, RouteHolds(file2) { now }, reports())).manifest(refresh = true)
        val bAsked = b.requests.count { it.path == name }
        val control = reports()
        store(threeHosts(a, b, c, RouteHolds(file2) { now + 10_000 }, control)).manifest(refresh = true)
        assertEquals("the held second host not asked", bAsked, b.requests.count { it.path == name })
        assertEquals(listOf(listOf("models", hosts[0], hosts[2], "http", 500, "manifest")), control.map { it.fields() })
        assertEquals("a failover report for a held answer", emptyList<List<Any?>>(), restarted.map { it.fields() })
    }

    /** The same for the manifest (stage `manifest`, one request across the model hosts, 28.3): held, no report. */
    @Test
    fun aHeldManifestAfterARestartSendsNoFailoverReport() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val name = ModelManifest.FILE_NAME
        a.answer = { p, n -> if (p == name && n == 1) ServedModel.Answer(503, "", mapOf("retry-after" to "90")) else null }
        var now = System.currentTimeMillis()
        val file = File(temp(), "holds.json")
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        fun store(ep: Endpoints) = ai.quranlab.tajweedformer.ModelStore(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null,
            ai.quranlab.tajweedformer.TestKit.verifier, retry = RetryPlan(longArrayOf(10)), endpoints = ep)
        val first = java.util.Collections.synchronizedList(ArrayList<Failover>())
        store(twoHosts(a, b, RouteHolds(file) { now }, first)).manifest(refresh = true)
        assertEquals(listOf(listOf("models", hosts[0], hosts[1], "http", 503, "manifest")), first.map { it.fields() })
        now += 40_000
        val restarted = java.util.Collections.synchronizedList(ArrayList<Failover>())
        val ep = twoHosts(a, b, RouteHolds(file) { now }, restarted)
        store(ep).manifest(refresh = true)
        assertEquals("the first host asked inside the 90 s", 1, a.requests.count { it.path == name })
        assertEquals(listOf(200, 200), b.requests.filter { it.path == name }.map { it.status })
        assertEquals(listOf("models", hosts[0], hosts[1], "http", 503, "manifest"), ep.lastFailover!!.fields())
        assertEquals("a failover report for a host that was not asked", emptyList<List<Any?>>(), restarted.map { it.fields() })
    }

    /**
     * Check 4 in ranges (the ranged path, which since 4.0.2 only completes ranges an earlier SDK stored; here 64 KiB
     * ranges two at a time):
     * the first CDN drops one range's connection after 1,000 bytes. That range goes on at the standby from where it
     * stopped, with no If-Range (the validator is the first CDN's), the other ranges keep their bytes, and the file
     * installs at the manifest's SHA-256. The standby's ETag is the same string as the first CDN's here, so only the
     * host decides whether the validator is sent.
     */
    @Test
    fun aRangeCutOnTheFirstCdnGoesOnAtTheStandby() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val m = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes())
        val e = m.files.first { it.localPath == "layers.zfw" }
        assertTrue("the weights span several ranges: ${e.size}", e.size > 4 * 65_536)
        a.rangeCut = { p, r, n -> if (p == e.path && r != null && r.startsWith("bytes=65536-") && n == 1) 1_000L else null }
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val ep = Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to a.url, hosts[1] to b.url))
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        val root = temp()
        val d = ModelDownloader(root, base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null, RangePlan(65_536, 65_536, 2),
            RetryPlan(longArrayOf(10, 10, 10, 10, 10)), ModelHosts.of(base, ep))
        val r = d.fetch(e)
        assertTrue(r.ok)
        assertTrue("the file is the manifest's", ai.quranlab.tajweedformer.TestKit.sha(d.local(e).readBytes()) == e.localSha256)
        val onA = a.requests.filter { it.path == e.path }
        val onB = b.requests.filter { it.path == e.path }
        assertTrue("the standby was asked: $onA / $onB", onB.isNotEmpty())
        assertTrue("the cut range went on at the standby from byte 66536: $onB", onB.any { it.range?.startsWith("bytes=66536-") == true })
        for (q in onB) assertNull("no If-Range to another host's store: $q", q.ifRange)
        for (q in onB) assertEquals("$q", 206, q.status)
        // the first CDN's own range requests after its first answer (the probe, bytes=0-) carry its validator
        assertTrue("the first CDN got its validator back: $onA", onA.filter { it.range?.startsWith("bytes=0-") != true }.all { it.ifRange != null })
        assertEquals(hosts[1], ep.models.preferred().first)
        assertEquals(listOf("models", hosts[0], hosts[1]), ep.lastFailover!!.let { listOf(it.role, it.from, it.to) })
    }

    /**
     * 4.0.1's files four at a time (perf-4.0.1) across the model hosts (section 28), on the store's own path: the first
     * CDN gives no answer at all; the manifest and every file of the CPU set come from the standby, each file installs
     * at the manifest's size and SHA-256, and the standby is P after.
     */
    @Test
    fun theFilesAtOnceComeFromTheStandbyWhenTheFirstCdnIsDown() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        a.down = true
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val ep = Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to a.url, hosts[1] to b.url))
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        val store = ai.quranlab.tajweedformer.ModelStore(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null,
            ai.quranlab.tajweedformer.TestKit.verifier, retry = RetryPlan(longArrayOf(10, 10, 10, 10, 10)), endpoints = ep)
        val m = store.manifest(refresh = true)
        val files = m.files.filter { it.need == ModelManifest.Need.REQUIRED && "*" in it.platforms }
        assertTrue("several files at once: ${files.size}", files.size > 4)
        store.fetch(files)
        for (e in files) assertTrue("${e.path} in place", store.downloader.isInPlace(e))
        assertTrue("every file came from the standby", files.all { e -> b.requests.any { it.path == e.path && it.status in setOf(200, 206) } })
        assertTrue("the first CDN answered nothing", a.requests.isEmpty())
        assertEquals(hosts[1], ep.models.preferred().first)
    }

    /** Check 4: the first CDN drops the connection in the middle of the weights; the standby CDN gives the rest. */
    @Test
    fun aDownloadCutOnTheFirstCdnGoesOnAtTheStandby() {
        val a = ServedModel.of().also { served += it }
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val m = ModelManifest.parse(File(a.root, ModelManifest.FILE_NAME).readBytes())
        val e = m.files.first { it.localPath == "layers.zfw" }
        a.cut = { p -> if (p == e.path) 100_000L else null }
        val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
        val ep = Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to a.url, hosts[1] to b.url))
        val base = "https://${hosts[0]}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/"
        val root = temp()
        val d = ModelDownloader(root, base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null, RangePlan.DEFAULT,
            RetryPlan(longArrayOf(10, 10, 10, 10, 10)), ModelHosts.of(base, ep))
        val r = d.fetch(e)
        assertTrue(r.ok)
        assertTrue("the file is the manifest's", ai.quranlab.tajweedformer.TestKit.sha(d.local(e).readBytes()) == e.localSha256)
        val onA = a.requests.filter { it.path == e.path }
        val onB = b.requests.filter { it.path == e.path }
        assertEquals("one request on the first CDN: $onA", 1, onA.size)
        assertEquals("one on the standby: $onB", 1, onB.size)
        assertEquals("bytes=100000-", onB[0].range)
        assertNull("no If-Range to another host's store", onB[0].ifRange)
        assertEquals(206, onB[0].status)
        assertEquals(hosts[1], ep.models.preferred().first)
        assertEquals(listOf("models", hosts[0], hosts[1]), ep.lastFailover!!.let { listOf(it.role, it.from, it.to) })
    }
    /**
     * Contract 28.3 item 6: a host has 5 s to connect, TCP and TLS. The first host takes the TCP connection and never
     * answers the TLS handshake (a host that accepts, then stalls); the next host is asked at 5 s, not at the read
     * timeout (60 s on the API, 30 s on a model file). Both transports as shipped.
     */
    private fun stallingTls(): Pair<java.net.ServerSocket, MutableList<java.net.Socket>> {
        val held = java.util.Collections.synchronizedList(ArrayList<java.net.Socket>())
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread({ try { while (true) held += ss.accept() } catch (_: Exception) {} }, "tls-staller").apply { isDaemon = true }.start()
        return ss to held
    }

    @Test
    fun anApiHostThatStallsTheTlsHandshakeLosesItsTurnAt5Seconds() {
        val (ss, held) = stallingTls()
        try {
            val ep = Endpoints.mapped(apiHosts, HostNames.MODELS, mapOf(apiHosts[0] to "https://127.0.0.1:${ss.localPort}", apiHosts[1] to standby.base))
            val api = QuranLabApi(standby.base, UrlConnectionTransport(), ep)
            val t0 = System.nanoTime()
            val challenge = api.challenge(QuranLabKey.parse(standby.key)!!)
            val s = (System.nanoTime() - t0) / 1e9
            assertTrue(challenge.isNotEmpty())
            assertTrue("the stalling host took the connection", held.isNotEmpty())
            assertTrue("the standby was asked after ${"%.1f".format(s)} s", s in 4.5..8.0)
            assertEquals(listOf("api", apiHosts[0], apiHosts[1], "network"), ep.lastFailover!!.let { listOf(it.role, it.from, it.to, it.reason) })
        } finally {
            held.forEach { try { it.close() } catch (_: Exception) {} }
            ss.close()
        }
    }

    @Test
    fun aModelHostThatStallsTheTlsHandshakeLosesItsTurnAt5Seconds() {
        val (ss, held) = stallingTls()
        val b = ServedModel.of().also { served += it }
        try {
            val m = ModelManifest.parse(File(b.root, ModelManifest.FILE_NAME).readBytes())
            val e = m.files.first { it.localPath == "frontend.zfw" }
            val hosts = listOf(HostNames.MODELS[0], HostNames.MODELS[1])
            val ep = Endpoints.mapped(HostNames.API, hosts, mapOf(hosts[0] to "https://127.0.0.1:${ss.localPort}", hosts[1] to b.url))
            val base = "https://${hosts[0]}/${ServedModel.REPO}/${b.build}/${ServedModel.FOLDER}/"
            val d = ModelDownloader(temp(), base, ai.quranlab.tajweedformer.UrlConnectionTransport(), null, RangePlan.DEFAULT,
                RetryPlan(longArrayOf(10, 10, 10, 10, 10)), ModelHosts.of(base, ep))
            val t0 = System.nanoTime()
            assertTrue(d.fetch(e).ok)
            val s = (System.nanoTime() - t0) / 1e9
            assertTrue("the stalling host took the connection", held.isNotEmpty())
            assertTrue("the standby gave the file after ${"%.1f".format(s)} s", s in 4.5..8.0)
            assertEquals(hosts[1], ep.models.preferred().first)
        } finally {
            held.forEach { try { it.close() } catch (_: Exception) {} }
            ss.close()
        }
    }
}
