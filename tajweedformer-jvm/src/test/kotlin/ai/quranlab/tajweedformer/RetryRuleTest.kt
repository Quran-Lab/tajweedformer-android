package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.DownloadRuleServer.Do
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * SDK 3.1.3, the retry rule of a model download, through the SDK's real HTTP code (`HttpURLConnection`) against a
 * local server that can end a connection in the middle of a body, stop sending without closing, send slowly, fail
 * (5xx) or refuse:
 *
 * - no byte for the stall limit ends a request (the limit is the transport's read timeout, 30 s as shipped; the tests
 *   make a transport with a shorter one), and a slow link that keeps delivering is not cut;
 * - the call resumes inside itself from what the device holds, with `Range` and `If-Range`, at most five times per
 *   file, after pauses of 1, 2, 4, 8 and 16 s (measured here as shipped), which a cancel ends at once;
 * - asked again: a connection that ended, a stall, a server failure; never: 401, 403, 404, 429;
 * - a 200 to a range request replaces the part;
 * - in ranges: the rest of a range from the byte where it stopped, ranges that were cut together count as one resume,
 *   and a server failure on one range does not end the others.
 *
 * Written against what the code had before the rule (ModelStore, the downloader, UrlConnectionTransport and its two
 * timeouts), so that the class ran on that code first and showed which of these are new.
 */
class RetryRuleTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: DownloadRuleServer
    private lateinit var served: File

    private fun dir() = Files.createTempDirectory("tj-retry-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = DownloadRuleServer(served)
    }

    @After
    fun stop() {
        server.close()
        SdkEvents.clear()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun now() = server.now()
    private val requests get() = server.requests
    private var plan: (String, String?, Int) -> Do
        get() = server.plan
        set(value) { server.plan = value }
    private val base: String get() = server.base

    private fun publish(files: List<TestKit.F>): ModelManifest {
        val m = TestKit.sign(TestKit.manifest(files))
        TestKit.writeTransport(served, files, m)
        return m
    }

    private fun store(root: File, stallMs: Int = 30_000) =
        ModelStore(root, base, UrlConnectionTransport(15_000, stallMs), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier)

    private fun big(): List<DownloadRuleServer.Req> = server.of(BIG)

    private fun stored(files: List<TestKit.F>) = files.first { it.storedPath == BIG }.stored

    private fun kept(files: List<TestKit.F>, root: File) =
        assertArrayEquals("the file on the device is not the model's", files.first { it.storedPath == BIG }.bytes, File(root, "layers.zfw").readBytes())

    // ---- one stream (a file below 8 MiB) ----

    @Test
    fun aConnectionThatEndsResumesInsideTheCallWithRangeAndIfRange() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val cut = layers.size * 4 / 10
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        plan = { p, _, _ -> if (p == BIG && first.getAndSet(false)) Do.Cut(cut) else Do.Serve }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        val q = big()
        assertEquals("the file was asked for twice in the one call: $q", 2, q.size)
        assertEquals(null, q[0].range)
        assertEquals("the second request goes on from the byte where the first ended", "bytes=$cut-", q[1].range)
        assertEquals("with the validator of the first answer", "\"v1\"", q[1].ifRange)
        assertEquals(206, q[1].status)
        assertTrue("the pause before the resume is 1 s: ${q[1].at - q[0].at} ms", q[1].at - q[0].at in 900..2_500)
        assertEquals("no byte was fetched twice", layers.size, r.networkBytes)
        assertEquals("nothing came from an earlier call", 0L, r.resumedFrom)
        kept(small, root)
    }

    @Test
    fun noByteForTheStallLimitEndsTheRequestAndTheCallResumes() {
        publish(small)
        val root = dir()
        val s = store(root, stallMs = 1_500)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val sent = layers.size * 4 / 10
        // the first answer stops after 40 percent and stays open for 20 s
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        plan = { p, _, _ -> if (p == BIG && first.getAndSet(false)) Do.Stall(sent, 20_000) else Do.Serve }
        requests.clear()
        val t = now()
        val r = s.downloader.fetch(layers)
        val took = now() - t
        assertTrue(r.ok)
        val q = big()
        assertEquals("$q", 2, q.size)
        assertEquals("bytes=$sent-", q[1].range)
        assertEquals("\"v1\"", q[1].ifRange)
        // the stall limit (1.5 s here), then the pause of 1 s; never the 20 s the server would have taken
        assertTrue("the request was ended ${q[1].at - q[0].at} ms after it began", q[1].at - q[0].at in 2_300..6_000)
        assertTrue("the call took $took ms", took < 8_000)
        kept(small, root)
    }

    @Test
    fun aSlowLinkThatKeepsDeliveringIsNotCut() {
        publish(small)
        val root = dir()
        val s = store(root, stallMs = 1_000)
        val layers = s.manifest(true).files.first { it.path == BIG }
        // an eighth of the file every 400 ms: 2.8 s in all, nearly three times the stall limit, never 1 s without a byte
        plan = { p, _, _ -> if (p == BIG) Do.Drip((layers.size / 8).toInt() + 1, 400) else Do.Serve }
        requests.clear()
        val t = now()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertTrue("the download took ${now() - t} ms", now() - t >= 2_500)
        assertEquals("one request: ${big()}", 1, big().size)
        kept(small, root)
    }

    @Test
    fun fiveResumesThenTheCallFailsAndThePartIsKept() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val step = 64L shl 10
        // the first three answers deliver 64 KiB each and end: bytes keep arriving, and the count of resumes is not reset by
        // them. The last three end before their first byte: an attempt that brings nothing counts as well
        val answers = java.util.concurrent.atomic.AtomicInteger()
        plan = { p, _, _ -> if (p == BIG) Do.Cut(if (answers.incrementAndGet() <= 3) step else 0) else Do.Serve }
        requests.clear()
        try {
            s.downloader.fetch(layers)
            fail("a download whose connection ends every time succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
            assertTrue(e.reason, e.reason.contains("network"))
        }
        val q = big()
        assertEquals("the first request and five resumes: $q", 6, q.size)
        assertEquals(listOf(null, "bytes=$step-", "bytes=${2 * step}-", "bytes=${3 * step}-", "bytes=${3 * step}-", "bytes=${3 * step}-"), q.map { it.range })
        assertTrue("every resume carries the validator: $q", q.drop(1).all { it.ifRange == "\"v1\"" })
        val gaps = q.zipWithNext().map { (a, b) -> b.at - a.at }
        val pauses = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
        for (i in pauses.indices) assertTrue("pause ${i + 1} was ${gaps[i]} ms, the rule says ${pauses[i]}: $gaps", gaps[i] in (pauses[i] - 100)..(pauses[i] + 1_500))
        val part = File(root, "$BIG.part")
        assertEquals("the part holds what arrived", 3 * step, part.length())
        assertTrue("the validator is kept beside it", File(root, "$BIG.part.v").isFile)
        // the next call goes on from there
        plan = { _, _, _ -> Do.Serve }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertEquals(3 * step, r.resumedFrom)
        assertEquals(listOf<String?>("bytes=${3 * step}-"), big().map { it.range })
        kept(small, root)
    }

    @Test
    fun aCancelEndsThePauseAtOnce() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        plan = { p, _, _ -> if (p == BIG) Do.Cut(64L shl 10) else Do.Serve }
        requests.clear()
        val cancel = Cancellation()
        val cancelledAt = java.util.concurrent.atomic.AtomicLong()
        val t = Thread {
            // in the first pause (1 s): 300 ms after the first answer began
            while (big().isEmpty()) Thread.sleep(5)
            Thread.sleep(300)
            cancelledAt.set(now())
            cancel.cancel()
        }
        t.start()
        try {
            s.downloader.fetch(layers, cancel)
            fail("a cancelled download succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
            assertEquals("cancelled", e.reason)
        }
        val ended = now()
        t.join()
        assertTrue("the call ended ${ended - cancelledAt.get()} ms after the cancel", ended - cancelledAt.get() < 400)
        assertEquals("no request after the cancel: ${big()}", 1, big().size)
        assertTrue("the part is kept", File(root, "$BIG.part").isFile)
    }

    @Test
    fun aServerFailureIsAskedAgain() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        plan = { p, _, n -> if (p == BIG && n == 1) Do.Status(503, """{"error":"unavailable"}""") else Do.Serve }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        val q = big()
        assertEquals("$q", listOf(503, 200), q.map { it.status })
        assertTrue("the pause was ${q[1].at - q[0].at} ms", q[1].at - q[0].at in 900..2_500)
        kept(small, root)
    }

    @Test
    fun aNetworkThatIsNotServedIsAskedOnce() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        // the one server failure that is not asked for again (SPEC 16.2): the network the request came from is not served
        plan = { p, _, _ -> if (p == BIG) Do.Status(503, """{"error":"network_blocked","message":"This network is not served.","retry_after":3600}""") else Do.Serve }
        requests.clear()
        val t = now()
        try {
            s.downloader.fetch(layers)
            fail("a download from a network that is not served succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
            assertTrue(e.reason, e.reason.contains("503"))
        }
        assertEquals("asked once: ${big()}", 1, big().size)
        assertTrue("the call waited ${now() - t} ms", now() - t < 900)
    }

    @Test
    fun theManifestIsAskedOnce() {
        publish(small)
        val root = dir()
        // the manifest's answer ends after ten bytes, every time: the retry rule is for the files, not for the manifest
        plan = { p, _, _ -> if (p == ModelManifest.FILE_NAME) Do.Cut(10) else Do.Serve }
        requests.clear()
        val t = now()
        try {
            store(root).manifest(true)
            fail("a manifest that was cut was accepted")
        } catch (e: TajweedError) {
        }
        assertEquals("the manifest was asked for once: ${server.of(ModelManifest.FILE_NAME)}", 1, server.of(ModelManifest.FILE_NAME).size)
        assertTrue("the call waited ${now() - t} ms", now() - t < 900)
    }

    @Test
    fun anAnswerBelow500IsNeverAskedAgain() {
        publish(small)
        val bodies = mapOf(
            401 to """{"error":"invalid_key","message":"missing or invalid x-qlab-key"}""",
            403 to """{"error":"model_not_allowed"}""",
            404 to "{}",
            429 to """{"error":"model_limit","scope":"install"}""",
        )
        for ((code, body) in bodies) {
            val root = dir()
            val s = store(root)
            val layers = s.manifest(true).files.first { it.path == BIG }
            plan = { p, _, _ -> if (p == BIG) Do.Status(code, body) else Do.Serve }
            requests.clear()
            val t = now()
            try {
                s.downloader.fetch(layers)
                fail("a download answered $code succeeded")
            } catch (e: TajweedError) {
            }
            assertEquals("$code was asked for once: ${big()}", 1, big().size)
            assertTrue("$code: the call waited ${now() - t} ms", now() - t < 900)
            assertFalse(File(root, "layers.zfw").exists())
        }
    }

    @Test
    fun a200ToARangeRequestReplacesThePart() {
        publish(small)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        // what an earlier call left of another version of the file: 100 000 bytes that are not this file's, and its validator
        File(root, "$BIG.part").writeBytes(ByteArray(100_000) { 0x55 })
        File(root, "$BIG.part.v").writeText("\"v0\"")
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        val q = big()
        assertEquals("one request, a range with the old validator, answered with the whole file: $q", 1, q.size)
        assertEquals("bytes=100000-", q[0].range)
        assertEquals("\"v0\"", q[0].ifRange)
        assertEquals(200, q[0].status)
        assertEquals("the whole file came", layers.size, r.networkBytes)
        assertEquals("nothing of the old part was used", 0L, r.resumedFrom)
        kept(small, root)
    }

    @Test
    fun aResponseCacheOfTheAppNeverSeesAModelRequest() {
        publish(small)
        val root = dir()
        val seen = Collections.synchronizedList(ArrayList<String>())
        val before = java.net.ResponseCache.getDefault()
        java.net.ResponseCache.setDefault(object : java.net.ResponseCache() {
            override fun get(uri: java.net.URI, method: String, headers: MutableMap<String, MutableList<String>>): java.net.CacheResponse? {
                seen.add("get $uri")
                return null
            }
            override fun put(uri: java.net.URI, conn: java.net.URLConnection): java.net.CacheRequest? {
                seen.add("put $uri")
                return null
            }
        })
        try {
            val s = store(root)
            assertTrue(s.downloader.fetch(s.manifest(true).files.first { it.path == BIG }).ok)
        } finally {
            java.net.ResponseCache.setDefault(before)
        }
        assertEquals("the app's response cache was asked about a model request", emptyList<String>(), seen.toList())
    }

    // ---- in ranges (a file above 8 MiB: 20 MiB here, three ranges) ----

    private fun rangeOf(k: Int, size: Long) = "bytes=${k * R}-${minOf(size, (k + 1) * R) - 1}"

    @Test
    fun aRangeThatStallsGoesOnFromTheByteWhereItStopped() {
        publish(large)
        val root = dir()
        val s = store(root, stallMs = 1_500)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val second = rangeOf(1, layers.size)
        val sent = 1L shl 20
        plan = { p, range, n -> if (p == BIG && range == second && n == 1) Do.Stall(sent, 20_000) else Do.Serve }
        requests.clear()
        val t = now()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertTrue("the call took ${now() - t} ms", now() - t < 10_000)
        val rest = big().filter { it.range == "bytes=${R + sent}-${2 * R - 1}" }
        assertEquals("the rest of the second range, once, from the byte where it stopped: ${big()}", 1, rest.size)
        assertEquals("\"v1\"", rest[0].ifRange)
        assertEquals("the other ranges were asked for once", listOf(1, 1), listOf(0, 2).map { k -> big().count { it.range == rangeOf(k, layers.size) } })
        kept(large, root)
    }

    @Test
    fun aServerFailureOnOneRangeDoesNotEndTheOthers() {
        publish(large)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val second = rangeOf(1, layers.size)
        plan = { p, range, n -> if (p == BIG && range == second && n == 1) Do.Status(502) else Do.Serve }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertEquals("the failed range twice, the others once: ${big()}", listOf(1, 2, 1), (0 until 3).map { k -> big().count { it.range == rangeOf(k, layers.size) } })
        assertEquals("every byte of the file once", layers.size, r.networkBytes)
        kept(large, root)
    }

    @Test
    fun rangesThatAreCutTogetherCountAsOneResume() {
        publish(large)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        val step = 512L shl 10
        // the link fails twice: each time every range that is being fetched ends after another 512 KiB, all three at
        // one moment (ended one by one, a slow machine can start one range's request after another range's cut has
        // begun the resume, and that is another failure by the rule). Six requests fail, more than the file's five
        // resumes, in two failures of the link
        val failures = listOf(java.util.concurrent.CyclicBarrier(3), java.util.concurrent.CyclicBarrier(3))
        val cuts = ConcurrentHashMap<Int, Int>()
        plan = { p, range, _ ->
            val k = range?.let { Regex("^bytes=(\\d+)-").find(it) }?.groupValues?.get(1)?.toLong()?.let { (it / R).toInt() }
            val n = if (p == BIG && k != null) cuts.merge(k, 1) { a, b -> a + b }!! else 0
            if (n in 1..2) Do.CutTogether(step, failures[n - 1]) else Do.Serve
        }
        requests.clear()
        val t = now()
        val r = s.downloader.fetch(layers)
        val took = now() - t
        assertTrue(r.ok)
        assertEquals("three ranges, each asked for three times: ${big()}", 9, big().size)
        // two pauses (1 s and 2 s), not six
        assertTrue("the call took $took ms", took in 2_800..7_000)
        kept(large, root)
    }

    companion object {
        const val BIG = "layers.zfw.gz"
        const val R = 8L shl 20

        private fun withWeights(bytes: Int, seed: Long): List<TestKit.F> = TestKit.v4Files(contexts = listOf("c16_l64")).map { f ->
            if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(bytes).also { java.util.Random(seed).nextBytes(it) }, "weights") else f
        }

        /** The test kit's model with a 3 MiB weights file (random, so its gzip is as large): one request. */
        private val small: List<TestKit.F> by lazy { withWeights(3 shl 20, 11) }

        /** The same with 20 MiB: three ranges of 8 MiB. */
        private val large: List<TestKit.F> by lazy { withWeights(20 shl 20, 12) }
    }
}
