package ai.quranlab.tajweedformer

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDK 3.1.3: a large model file is fetched in ranges, against a local server that answers closed ranges (206 with
 * `Content-Range`), `If-Range` and an ETag, and can drop a connection or give another answer for one range.
 *
 * Written against what 3.1.1 already had (ModelStore, the downloader, UrlConnectionTransport), with the SDK's own
 * numbers (8 MiB ranges, four at a time, a file above 8 MiB in ranges), so that the class ran on 3.1.1's code and
 * showed which rule is new there (the weights file here is 38 MiB, five ranges); the last two tests were added after,
 * with 3.1.3's types.
 */
class RangedDownloadTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: HttpServer
    private lateinit var served: File

    /** One request: the path, its Range and If-Range, the status, the ETag answered, the body bytes sent, the headers. */
    class Req(val path: String, val range: String?, val ifRange: String?, val status: Int, val etag: String?, val sent: Long,
              val headers: Map<String, String>) {
        override fun toString() = "$path [$range if-range=$ifRange] -> $status ($etag) $sent bytes"
    }

    private val requests = Collections.synchronizedList(ArrayList<Req>())
    @Volatile private var etag: String? = "\"v1\""
    /** The server honours Range (false: every answer is the whole file, 200). */
    @Volatile private var honoursRanges = true
    /** For a path, its Range and how many times that range was asked: body bytes to send before dropping the connection. */
    @Volatile private var cut: (String, String?, Int) -> Long? = { _, _, _ -> null }
    /** For a path, its Range and how many times: a status and JSON body to answer instead of the file. */
    @Volatile private var answer: (String, String?, Int) -> Pair<Int, String>? = { _, _, _ -> null }
    /** Called with the path and the Range before the answer. */
    @Volatile private var onRequest: (String, String?) -> Unit = { _, _ -> }
    /** A pause after every 64 KiB of a body, so that ranges overlap in time. */
    @Volatile private var slowMs = 0L
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val atOnce = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    private fun dir() = Files.createTempDirectory("tj-rng-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(10)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/m/")
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.last() }
            var status = 404
            var sent = 0L
            var tag = etag
            try {
                onRequest(path, range)
                tag = etag
                val n = asked.merge("$path $range", 1) { a, b -> a + b }!!
                val given = answer(path, range, n)
                val f = File(served, path)
                if (given != null) {
                    status = given.first
                    val b = given.second.toByteArray()
                    ex.responseHeaders.add("content-type", "application/json")
                    ex.sendResponseHeaders(status, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                } else if (!f.isFile) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    val size = f.length()
                    if (tag != null) ex.responseHeaders.add("ETag", tag)
                    val m = range?.let { Regex("^bytes=(\\d+)-(\\d*)$").find(it) }
                    val ok = honoursRanges && m != null && (ifRange == null || ifRange == tag)
                    var from = 0L
                    var to = size - 1
                    if (ok) {
                        from = m!!.groupValues[1].toLong()
                        m.groupValues[2].takeIf { it.isNotEmpty() }?.let { to = minOf(it.toLong(), size - 1) }
                    }
                    if (ok && from >= size) {
                        status = 416
                        ex.responseHeaders.add("Content-Range", "bytes */$size")
                        ex.sendResponseHeaders(416, -1)
                    } else {
                        status = if (ok) 206 else 200
                        if (ok) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
                        val length = to - from + 1
                        val stop = cut(path, range, n)?.coerceIn(0, length) ?: length
                        ex.sendResponseHeaders(status, length)
                        mostAtOnce.accumulateAndGet(atOnce.incrementAndGet()) { a, b -> maxOf(a, b) }
                        try {
                            RandomAccessFile(f, "r").use { raf ->
                                raf.seek(from)
                                val buf = ByteArray(64 shl 10)
                                val out = ex.responseBody
                                while (sent < stop) {
                                    val k = raf.read(buf, 0, minOf(buf.size.toLong(), stop - sent).toInt())
                                    if (k < 0) break
                                    out.write(buf, 0, k)
                                    sent += k
                                    if (slowMs > 0) Thread.sleep(slowMs)
                                }
                                out.flush()
                                if (stop >= length) out.close()
                            }
                        } finally {
                            atOnce.decrementAndGet()
                        }
                    }
                }
            } catch (_: IOException) {
            } finally {
                requests.add(Req(path, range, ifRange, status, tag, sent, headers))
                ex.close()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        SdkEvents.clear()
        dirs.forEach { it.deleteRecursively() }
    }

    private val base: String get() = "http://127.0.0.1:${server.address.port}/m/"

    private fun publish(): ModelManifest {
        val m = TestKit.sign(TestKit.manifest(files))
        TestKit.writeTransport(served, files, m)
        return m
    }

    private fun store(root: File, transport: HttpTransport = UrlConnectionTransport()) =
        ModelStore(root, base, transport, { mapOf("x-qlab-key" to "k1") }, TestKit.verifier, retry = QUICK)

    private fun layersOf(s: ModelStore) = s.manifest(true).files.first { it.path == BIG }

    /** The requests for the weights file, in the order they ended. */
    private fun big(): List<Req> = synchronized(requests) { requests.filter { it.path == BIG } }

    private fun rangeOf(k: Int, size: Long) = "bytes=${k * R}-${minOf(size, (k + 1) * R) - 1}"

    private fun leftovers(root: File) = root.walk().filter { it.isFile && (it.name.contains(".part") || it.name.endsWith(".tmp")) }.map { it.name }.toList()

    @Test
    fun aLargeFileComesInRangesFourAtATime() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        assertEquals("the test file is five ranges", 5, ((layers.size + R - 1) / R).toInt())
        slowMs = 2
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        val asked = big()
        assertEquals("every range once, as an 8 MiB range of the file: $asked", (0 until 5).map { rangeOf(it, layers.size) }.toSet(),
            asked.map { it.range }.toSet())
        assertEquals(5, asked.size)
        assertTrue("every range answered 206: $asked", asked.all { it.status == 206 })
        assertTrue("ranges at once: ${mostAtOnce.get()}", mostAtOnce.get() in 2..4)
        // every range request: the app's header, the bytes as stored, and (but the first) the validator of the first answer
        assertTrue(asked.all { it.headers["x-qlab-key"] == "k1" && it.headers["accept-encoding"] == "identity" })
        assertEquals("If-Range on every range after the first", 4, asked.count { it.ifRange == "\"v1\"" })
        assertEquals(layers.size, r.networkBytes)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertEquals("nothing of the download is left beside the file", emptyList<String>(), leftovers(root))
    }

    @Test
    fun aServerWithoutRangesGivesTheWholeFileInOneAnswer() {
        publish()
        honoursRanges = false
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        requests.clear()
        assertTrue(s.downloader.fetch(layers).ok)
        val asked = big()
        assertEquals("one request: the first range was asked for and the whole file came (200): $asked", 1, asked.size)
        assertEquals(rangeOf(0, layers.size), asked[0].range)
        assertEquals(200, asked[0].status)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertEquals(emptyList<String>(), leftovers(root))
    }

    @Test
    fun aDroppedRangeIsAskedForAgainFromWhereItStopped() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        val third = rangeOf(2, layers.size)
        cut = { p, range, n -> if (p == BIG && range == third && n == 1) 1L shl 20 else null }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        // the rest of the third range: from where the dropped answer stopped (at most its first MiB) to the range's end
        val again = big().filter { q -> q.range != third && q.range?.substringAfter("=")?.substringBefore("-")?.toLong()?.let { it in (2 * R + 1)..(2 * R + (1L shl 20)) } == true }
        assertEquals("the rest of the third range, once: ${big()}", 1, again.size)
        assertTrue(again[0].range!!, again[0].range!!.endsWith("-${3 * R - 1}"))
        assertEquals("\"v1\"", again[0].ifRange)
        assertEquals(206, again[0].status)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
    }

    @Test
    fun aRestartContinuesFromTheRangesThatAreComplete() {
        publish()
        val root = dir()
        val layers = layersOf(store(root))
        val second = rangeOf(1, layers.size)
        // the second range drops every time: the call ends with the other four complete and the second half written
        cut = { p, range, _ -> if (p == BIG && range != null && range.startsWith("bytes=") && range.substringAfter("=").substringBefore("-").toLong() in R until 2 * R) 1L shl 20 else null }
        try {
            store(root).downloader.fetch(layers)
            fail("a download whose second range never arrives succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
        }
        assertTrue("the part is kept", File(root, "$BIG.part").isFile)
        // a new process: only the second range is asked for, whole and from its start, and only if the file is unchanged
        cut = { _, _, _ -> null }
        requests.clear()
        val r = store(root).downloader.fetch(layers)
        assertTrue(r.ok)
        val asked = big()
        assertEquals("only the range that was not complete: $asked", listOf(second), asked.map { it.range })
        assertEquals("\"v1\"", asked[0].ifRange)
        assertEquals(R, r.networkBytes)
        assertEquals(layers.size - R, r.resumedFrom)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertEquals(emptyList<String>(), leftovers(root))
    }

    @Test
    fun aFileThatChangedBetweenTwoCallsIsFetchedWholeAndNeverContinued() {
        publish()
        val root = dir()
        val layers = layersOf(store(root))
        cut = { p, range, _ -> if (p == BIG && range != null && range.substringAfter("=").substringBefore("-").toLong() in R until 2 * R) 1L shl 20 else null }
        runCatching { store(root).downloader.fetch(layers) }
        cut = { _, _, _ -> null }
        // the server's file has another validator now: nothing of the part may be continued
        etag = "\"v2\""
        requests.clear()
        val r = store(root).downloader.fetch(layers)
        assertTrue(r.ok)
        // the missing range is asked for with the old validator; the server's file is another one, so it answers with
        // the whole file (200), and that one answer is the file: nothing of the old part is in it
        val asked = big()
        assertEquals("one request, answered with the whole file: $asked", 1, asked.size)
        assertEquals(rangeOf(1, layers.size), asked[0].range)
        assertEquals("\"v1\"", asked[0].ifRange)
        assertEquals(200, asked[0].status)
        assertEquals("\"v2\"", asked[0].etag)
        assertEquals("the whole file came in that answer", layers.size, asked[0].sent)
        assertEquals(0L, r.resumedFrom)
        assertFalse(r.ranged)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertEquals("nothing of either download is left beside the file", emptyList<String>(), leftovers(root))
    }

    @Test
    fun aFileThatChangesDuringTheDownloadIsNeverMixed() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        slowMs = 2
        // when the last range is asked for, the server's file gets another validator: the ranges asked before it are of
        // the old one
        val last = rangeOf(4, layers.size)
        onRequest = { p, range -> if (p == BIG && range == last && etag == "\"v1\"") etag = "\"v3\"" }
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        // what the file is made of: one answer of the new validator, the whole file, asked for without a Range once the
        // last range's answer (a 200 to If-Range) had shown that the file is another one. No range of the old file is in it
        val whole = big().last()
        assertEquals("the last request is for the whole file: ${big()}", null, whole.range)
        assertEquals(200, whole.status)
        assertEquals("\"v3\"", whole.etag)
        assertEquals(layers.size, whole.sent)
        assertFalse(r.ranged)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertEquals(emptyList<String>(), leftovers(root))
    }

    @Test
    fun theDailyLimitOnARangeEndsTheDownloadWithoutARetry() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        val third = rangeOf(2, layers.size)
        answer = { p, range, _ -> if (p == BIG && range == third) 429 to """{"error":"model_limit","message":"The daily limit is reached.","scope":"install"}""" else null }
        requests.clear()
        try {
            s.downloader.fetch(layers)
            fail("a download with a refused range succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
            assertTrue(e.reason, e.reason.contains("429"))
        }
        assertEquals("the refused range was asked for once: ${big()}", 1, big().count { it.range == third })
        assertFalse(File(root, "layers.zfw").exists())
    }

    @Test
    fun noRangeIsHeldInMemoryWhole() {
        publish()
        val root = dir()
        // the transport's bodies, watched: how much one read asks for, and how many bodies are open at once
        val most = AtomicInteger()
        val open = AtomicInteger()
        val mostOpen = AtomicInteger()
        val real = UrlConnectionTransport()
        val watched = HttpTransport { url, h ->
            val r = real.get(url, h)
            mostOpen.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
            val body = object : InputStream() {
                override fun read(): Int = r.body.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    most.accumulateAndGet(len) { a, x -> maxOf(a, x) }
                    return r.body.read(b, off, len)
                }
                override fun close() = r.body.close()
            }
            HttpResponse(r.code, r.headers, body) { r.close(); open.decrementAndGet() }
        }
        slowMs = 1
        val s = store(root, watched)
        assertTrue(s.downloader.fetch(layersOf(s)).ok)
        assertTrue("one read asked for ${most.get()} bytes", most.get() in 1..(64 shl 10))
        assertTrue("${mostOpen.get()} answers open at once", mostOpen.get() <= 4)
    }

    @Test
    fun progressNeverGoesBack() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = layersOf(s)
        val seen = Collections.synchronizedList(ArrayList<Long>())
        slowMs = 1
        // one range drops once, so that a range is asked for again in the middle
        cut = { p, range, n -> if (p == BIG && range == rangeOf(1, layers.size) && n == 1) 3L shl 20 else null }
        assertTrue(s.downloader.fetch(layers, null, { p -> seen.add(p.bytes) }).ok)
        val list = synchronized(seen) { seen.toList() }
        assertTrue("progress went back: $list", list.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(layers.size, list.last())
    }

    // ---- what 3.1.1 did not have (these two use 3.1.3's types) ----

    @Test
    fun aTransportThatSharesOneConnectionFetchesOneRangeAtATime() {
        publish()
        val root = dir()
        val real = UrlConnectionTransport()
        val multiplexed = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = real.get(url, headers)
            override val separateConnections: Boolean get() = false
        }
        val layers = layersOf(store(root, multiplexed))
        slowMs = 1
        // in ranges all the same, for the map: the second drops every time (the call ends there: no range starts after
        // one failed), and a new process continues after the first, one range after another
        cut = { p, range, _ -> if (p == BIG && range != null && range.substringAfter("=").substringBefore("-").toLong() in R until 2 * R) 1L shl 20 else null }
        requests.clear()
        runCatching { store(root, multiplexed).downloader.fetch(layers) }
        assertEquals("ranges at once over one connection", 1, mostAtOnce.get())
        cut = { _, _, _ -> null }
        requests.clear()
        assertTrue(store(root, multiplexed).downloader.fetch(layers).ok)
        assertEquals((1 until 5).map { rangeOf(it, layers.size) }, big().map { it.range })
    }

    @Test
    fun theProtocolOfTheAnswersIsRecorded() {
        publish()
        val root = dir()
        val s = store(root)
        val m = s.manifest(true)
        val r = s.downloader.fetch(m.files.first { it.path == BIG })
        assertTrue(r.ranged)
        assertEquals("the JVM's HttpURLConnection: HTTP/1.1", setOf("http/1.1"), r.protocols.keys)
        assertEquals(r.networkBytes, r.protocols.values.sum())
        assertEquals("http/1.1", r.protocol)
        val small = s.downloader.fetch(m.files.first { it.path == "tokens.txt" })
        assertFalse(small.ranged)
        assertEquals(setOf("http/1.1"), small.protocols.keys)
        // what Android's HttpURLConnection names, and the status line otherwise
        assertEquals("h2", HttpResponse.protocolOf("h2", "HTTP/1.1 200 OK"))
        assertEquals("http/1.1", HttpResponse.protocolOf("http/1.1", null))
        assertEquals("http/1.1", HttpResponse.protocolOf(null, "HTTP/1.1 206 Partial Content"))
        assertEquals("h2", HttpResponse.protocolOf(null, "HTTP/2 200"))
        assertEquals("h3", HttpResponse.protocolOf("h3", null))
        assertEquals("unknown", HttpResponse.protocolOf(null, null))
    }

    /**
     * A stand-in for the Client's model-request rules: a token that [renew] replaces after [renewMs], and a 401 with a
     * stop reason as the refusal it is, [refusalMs] after it was read (the Client removes the model in that time).
     */
    private class Auth(private val renewMs: Long = 0, private val refusalMs: Long = 0) : ModelAuth {
        @Volatile var token = "old"
        val renewals = AtomicInteger()
        override fun headers(url: String): Map<String, String> = mapOf("authorization" to token)
        override fun renewal(status: Int, body: ByteArray): Boolean = status == 401 && String(body).contains("invalid_token")
        override fun renew() {
            renewals.incrementAndGet()
            Thread.sleep(renewMs)
            token = "new"
        }
        /** The refusal's work on the device is through. */
        @Volatile var worked = false
        override fun refused(status: Int, body: ByteArray): TajweedError? =
            if (status == 401 && String(body).contains("key_revoked")) RecognizerException.InvalidKey() else null

        override fun refuse(status: Int, body: ByteArray) {
            Thread.sleep(refusalMs)
            worked = true
        }
        override fun served() {}
    }

    /**
     * Two ranges are refused at the same time (401 `invalid_token`), and the renewal of the token takes 400 ms: the
     * second range waits for that renewal and is repeated with the new token, never with the one that was refused.
     * Before the fix the second range went on at once, with the old token, and the download failed (seen on the public
     * CI's Linux runner; on a fast renewal it is a matter of microseconds).
     */
    @Test
    fun twoRangesRefusedAtOnceAreRepeatedWithTheNewTokenOnly() {
        publish()
        val root = dir()
        val auth = Auth(renewMs = 400)
        val real = UrlConnectionTransport()
        val withOld = Collections.synchronizedList(ArrayList<String>())
        var two: Set<String> = emptySet()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = get(url, headers, null)
            override fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse {
                val range = headers["Range"]
                if (url.endsWith(BIG) && range != null && range in two && headers["authorization"] == "old") {
                    withOld.add(range)
                    return HttpResponse(401, emptyMap(), """{"error":"invalid_token"}""".byteInputStream())
                }
                return real.get(url, headers, cancel)
            }
        }
        val s = ModelStore(root, base, transport, auth, TestKit.verifier, retry = QUICK)
        val layers = layersOf(s)
        two = setOf(rangeOf(1, layers.size), rangeOf(2, layers.size))
        val failure = runCatching { s.downloader.fetch(layers) }.exceptionOrNull()
        assertEquals("each of the two ranges was asked once with the token that was refused", two.sorted(), withOld.sorted())
        assertEquals("the token was renewed once for the whole download", 1, auth.renewals.get())
        assertEquals("the download failed", null, failure)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
    }

    /**
     * A refusal on one range decides the call's error. Here another range's request waits for its answer when the
     * refusal ends the attempt, and fails with an exception that is no IOException (an `HttpURLConnection` that is
     * disconnected from another thread can do that), 400 ms before the refusing range has its error: that request was
     * cut off by the end of the attempt and is no failure of its own. Before the fix its exception was the call's
     * (seen on the public CI's Linux runner: `engine_failed` where `invalid_key` was expected).
     */
    @Test
    fun aRefusalDecidesTheCallAlsoWhenARequestItEndedFailsInItsOwnWay() {
        publish()
        val root = dir()
        val auth = Auth(refusalMs = 400)
        val real = UrlConnectionTransport()
        var waits: String? = null
        var refused: String? = null
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = get(url, headers, null)
            override fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse {
                val range = headers["Range"]
                if (url.endsWith(BIG) && range != null && range == refused) {
                    return HttpResponse(401, emptyMap(), """{"error":"invalid_key","reason":"key_revoked"}""".byteInputStream())
                }
                if (url.endsWith(BIG) && range != null && range == waits && cancel != null) {
                    val ended = java.util.concurrent.CountDownLatch(1)
                    cancel.whenCancelled { ended.countDown() }.use { ended.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                    throw IllegalStateException("the connection was disconnected")
                }
                return real.get(url, headers, cancel)
            }
        }
        val s = ModelStore(root, base, transport, auth, TestKit.verifier, retry = QUICK)
        val layers = layersOf(s)
        waits = rangeOf(1, layers.size)
        refused = rangeOf(2, layers.size)
        val failure = runCatching { s.downloader.fetch(layers) }.exceptionOrNull()
        assertTrue("the call's failure is not the refusal: $failure", failure is RecognizerException.InvalidKey)
    }

    /**
     * A refusal on one range is the call's error, and the call returns when the refusal's work on the device is done,
     * however long that takes: here 6 s, longer than the 5 s the call waits for the other ranges' streams to end.
     * Before the fix the call went on after those 5 s without the refusal ("not every range of the file arrived").
     */
    @Test
    fun aRefusalIsTheCallsErrorHoweverLongItsWorkTakes() {
        publish()
        val root = dir()
        val auth = Auth(refusalMs = 6_000)
        val real = UrlConnectionTransport()
        var refused: String? = null
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = get(url, headers, null)
            override fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse {
                val range = headers["Range"]
                if (url.endsWith(BIG) && range != null && range == refused) {
                    return HttpResponse(401, emptyMap(), """{"error":"invalid_key","reason":"key_revoked"}""".byteInputStream())
                }
                return real.get(url, headers, cancel)
            }
        }
        val s = ModelStore(root, base, transport, auth, TestKit.verifier, retry = QUICK)
        val layers = layersOf(s)
        refused = rangeOf(2, layers.size)
        val t = System.nanoTime()
        val failure = runCatching { s.downloader.fetch(layers) }.exceptionOrNull()
        val took = (System.nanoTime() - t) / 1_000_000
        assertTrue("the call's failure is not the refusal (after $took ms): $failure", failure is RecognizerException.InvalidKey)
        assertTrue("the call returned after $took ms, before the refusal's work was done", auth.worked && took >= 6_000)
    }

    companion object {
        const val BIG = "layers.zfw.gz"
        const val R = 8L shl 20

        /** The retry rule with short pauses (three resumes, 300 ms apart): a range that drops every time ends the call within a
         *  second, after the ranges beside it are through. */
        private val QUICK = RetryPlan(longArrayOf(300, 300, 300))

        /** The test kit's model with a 38 MiB weights file (random, so its gzip is as large): five 8 MiB ranges. */
        private val files: List<TestKit.F> by lazy {
            TestKit.v4Files(contexts = listOf("c16_l64")).map { f ->
                if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(38 shl 20).also { java.util.Random(7).nextBytes(it) }, "weights") else f
            }
        }

    }
}
