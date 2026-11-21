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

    /**
     * Check 4 in ranges (the weights' path, 8 MiB ranges four at a time as shipped; here 64 KiB ranges two at a time):
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
