package ai.quranlab.tajweedformer

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 4.0.1, the perf plan's row 5 on Android: the files of one call at the same time (at most four, the largest first) and
 * each file's SHA-256 made while its bytes are written, against a local server that speaks Range, If-Range and ETag
 * and can be slow, refuse or fail one file.
 *
 * - at most four files are in flight at once, and the largest is asked for first;
 * - the first failure is the call's error, the other files stop and keep their parts, and their end is not reported
 *   as a download of its own;
 * - a refusal's work on the device comes only after every other file stopped writing;
 * - progress is the sum over the files, never lower than a report before it, and ends at the total;
 * - the digest: a file in one stream, one resumed from a part an earlier call left, and one in ranges are hashed while
 *   written ([DownloadResult.hashedInFlight] is the whole file when the last byte is in), and [PartHash] alone agrees
 *   with a plain SHA-256 of the file.
 */
class FilesAtOnceTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: HttpServer
    private lateinit var served: File
    /** Paths whose body is being sent now, and the most distinct paths at once. */
    private val sending = ConcurrentHashMap<String, AtomicInteger>()
    private val mostFiles = AtomicInteger()
    private val order = Collections.synchronizedList(ArrayList<String>())
    /** A pause after every 64 KiB of a body, per path ("" for every path). */
    @Volatile private var slowMs: (String) -> Long = { 0 }
    /** For a path: a status and JSON body to answer instead of the file, after a pause (ms). */
    @Volatile private var answer: (String) -> Triple<Int, String, Long>? = { null }
    /** For a path and its Range: body bytes to send before the connection is dropped (the first such request only). */
    @Volatile private var cutAt: (String, String?) -> Long? = { _, _ -> null }
    private val cutDone = ConcurrentHashMap.newKeySet<String>()

    private fun dir() = Files.createTempDirectory("tj-many-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(12)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/m/")
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            order.add(path)
            try {
                val given = answer(path)
                val f = File(served, path)
                if (given != null) {
                    Thread.sleep(given.third)
                    val b = given.second.toByteArray()
                    ex.responseHeaders.add("content-type", "application/json")
                    ex.sendResponseHeaders(given.first, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                } else if (!f.isFile) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    val size = f.length()
                    ex.responseHeaders.add("ETag", "\"v1\"")
                    val m = range?.let { Regex("^bytes=(\\d+)-(\\d*)$").find(it) }
                    val ok = m != null && (ifRange == null || ifRange == "\"v1\"")
                    var from = 0L
                    var to = size - 1
                    if (ok) {
                        from = m!!.groupValues[1].toLong()
                        m.groupValues[2].takeIf { it.isNotEmpty() }?.let { to = minOf(it.toLong(), size - 1) }
                        ex.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
                    }
                    val length = to - from + 1
                    val stop = (if (cutDone.add("$path $range")) cutAt(path, range) else null)?.coerceIn(0, length) ?: length
                    ex.sendResponseHeaders(if (ok) 206 else 200, length)
                    val c = sending.computeIfAbsent(path) { AtomicInteger() }
                    c.incrementAndGet()
                    mostFiles.accumulateAndGet(sending.values.count { it.get() > 0 }) { a, b -> maxOf(a, b) }
                    try {
                        RandomAccessFile(f, "r").use { raf ->
                            raf.seek(from)
                            val buf = ByteArray(64 shl 10)
                            val out = ex.responseBody
                            var sent = 0L
                            while (sent < stop) {
                                val k = raf.read(buf, 0, minOf(buf.size.toLong(), stop - sent).toInt())
                                if (k < 0) break
                                out.write(buf, 0, k)
                                sent += k
                                val s = slowMs(path)
                                if (s > 0) Thread.sleep(s)
                            }
                            out.flush()
                            if (stop >= length) out.close()
                        }
                    } finally {
                        c.decrementAndGet()
                    }
                }
            } catch (_: IOException) {
            } finally {
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

    /** The test kit's model, both contexts, with a 3 MiB weights file (random): ten files, one far the largest. */
    private val files: List<TestKit.F> by lazy {
        TestKit.v4Files(contexts = listOf("c16_l64", "c24_l72")).map { f ->
            if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(3 shl 20).also { java.util.Random(11).nextBytes(it) }, "weights") else f
        }
    }

    private fun publish(): ModelManifest {
        val m = TestKit.sign(TestKit.manifest(files))
        TestKit.writeTransport(served, files, m)
        return m
    }

    private fun store(root: File, auth: HeaderProvider? = HeaderProvider { mapOf("x-qlab-key" to "k1") }, ranges: RangePlan = RangePlan.DEFAULT) =
        ModelStore(root, base, UrlConnectionTransport(), auth, TestKit.verifier, ranges = ranges, retry = RetryPlan(longArrayOf(100, 100)))

    private fun profile(s: ModelStore): List<ModelManifest.FileEntry> {
        val m = s.manifest(true)
        return m.files.filter { it.need == ModelManifest.Need.REQUIRED && "*" in it.platforms }
    }

    private fun sha(b: ByteArray) = ModelManifest.hex(MessageDigest.getInstance("SHA-256").digest(b))

    @Test
    fun atMostFourFilesAtOnceTheLargestFirst() {
        publish()
        val root = dir()
        val s = store(root)
        val want = profile(s)
        assertTrue("the test needs more than four files: ${want.size}", want.size > 4)
        slowMs = { 3 }
        order.clear()
        val events = Collections.synchronizedList(ArrayList<DownloadResult>())
        SdkEvents.add(object : SdkObserver { override fun onDownloadFinished(result: DownloadResult) { events.add(result) } })
        s.fetch(want)
        assertTrue("files at once: ${mostFiles.get()}", mostFiles.get() in 2..4)
        // the largest is taken first: it is among the four requests that start together (their order on the wire is the
        // threads')
        assertTrue("the largest starts at once: $order", want.maxByOrNull { it.size }!!.path in order.take(4))
        for (e in want) assertTrue("${e.path} in place", s.downloader.isInPlace(e))
        assertEquals("one result per file, every one a success", want.map { it.path }.toSet(), events.filter { it.ok }.map { it.path }.toSet())
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
    }

    @Test
    fun theFirstFailureIsTheCallsErrorAndTheOtherFilesKeepTheirParts() {
        publish()
        val root = dir()
        val s = store(root)
        val want = profile(s)
        val big = want.maxByOrNull { it.size }!!
        val bad = want.first { it.path.startsWith("frontend") }
        // the weights stream slowly; the frontend is answered 404 after 400 ms, while the weights are on their way
        slowMs = { p -> if (p == big.path) 20 else 0 }
        answer = { p -> if (p == bad.path) Triple(404, """{"error":"not_found"}""", 400L) else null }
        val events = Collections.synchronizedList(ArrayList<DownloadResult>())
        SdkEvents.add(object : SdkObserver { override fun onDownloadFinished(result: DownloadResult) { events.add(result) } })
        val failure = runCatching { s.fetch(want) }.exceptionOrNull()
        assertTrue("the call's error is the frontend's 404: $failure",
            failure is RecognizerException.DownloadFailed && failure.path == bad.path && causeOf(failure).httpStatus == 404)
        val part = File(root, big.path + ".part")
        assertTrue("the weights' part is kept: ${part.length()} of ${big.size}", part.isFile && part.length() in 1 until big.size)
        val failed = events.filter { !it.ok }.map { it.path }
        assertEquals("only the file that failed is reported as a failed download: $failed", listOf(bad.path), failed)
        // the next call goes on from the part
        answer = { null }
        slowMs = { 0 }
        val had = part.length()
        events.clear()
        s.fetch(want)
        val r = events.first { it.path == big.path }
        assertEquals("the weights resumed from their part", had, r.resumedFrom)
        assertEquals("hashed while written, the part's bytes first", big.size, r.hashedInFlight)
        for (e in want) assertTrue("${e.path} in place", s.downloader.isInPlace(e))
    }

    private class Refusing : ModelAuth {
        val refusals = AtomicInteger()
        /** The parts' sizes when the refusal's work began, and when it ended 300 ms later. */
        @Volatile var atStart: Map<String, Long> = emptyMap()
        @Volatile var atEnd: Map<String, Long> = emptyMap()
        lateinit var root: File
        private fun parts() = root.walk().filter { it.isFile && it.name.contains(".part") }.associate { it.path to it.length() }
        override fun headers(url: String): Map<String, String> = mapOf("x-qlab-key" to "k1")
        override fun renewal(status: Int, body: ByteArray): Boolean = false
        override fun renew() {}
        override fun refused(status: Int, body: ByteArray): TajweedError? =
            if (status == 401 && String(body).contains("key_revoked")) RecognizerException.InvalidKey() else null
        override fun refuse(status: Int, body: ByteArray) {
            refusals.incrementAndGet()
            atStart = parts()
            Thread.sleep(300)
            atEnd = parts()
        }
        override fun served() {}
    }

    @Test
    fun aRefusalsWorkComesOnlyAfterEveryOtherFileStoppedWriting() {
        publish()
        val root = dir()
        val auth = Refusing().also { it.root = root }
        val s = store(root, auth)
        val want = profile(s)
        val big = want.maxByOrNull { it.size }!!
        val bad = want.first { it.path.startsWith("frontend") }
        slowMs = { p -> if (p == big.path) 20 else 0 }
        answer = { p -> if (p == bad.path) Triple(401, """{"error":"invalid_key","reason":"key_revoked"}""", 400L) else null }
        val failure = runCatching { s.fetch(want) }.exceptionOrNull()
        assertTrue("the refusal is the call's error: $failure", failure is RecognizerException.InvalidKey)
        assertEquals(1, auth.refusals.get())
        assertTrue("the weights were on their way when the refusal came: ${auth.atStart}", (auth.atStart[File(root, big.path + ".part").path] ?: 0L) > 0)
        assertEquals("nothing was written while the refusal's work ran", auth.atStart, auth.atEnd)
        val after = root.walk().filter { it.isFile && it.name.contains(".part") }.associate { it.path to it.length() }
        assertEquals("nothing was written after it", auth.atEnd, after)
    }

    @Test
    fun aCancelledCallFailsAsCancelledAndEveryFileKeepsItsPart() {
        publish()
        val root = dir()
        val s = store(root)
        val want = profile(s)
        // cancelled before it starts: no file is downloaded, and the call fails as cancelled
        val before = runCatching { s.fetch(want, Cancellation().apply { cancel() }) }.exceptionOrNull()
        assertTrue("$before", before is RecognizerException.DownloadFailed && before.reason == "cancelled")
        // cancelled while the files come: every file ends, the call fails as cancelled, the weights keep their part
        val big = want.maxByOrNull { it.size }!!
        slowMs = { p -> if (p == big.path) 10 else 0 }
        val c = Cancellation()
        val canceller = Thread { Thread.sleep(250); c.cancel() }.apply { start() }
        val during = runCatching { s.fetch(want, c) }.exceptionOrNull()
        canceller.join()
        assertTrue("$during", during is RecognizerException.DownloadFailed && during.reason == "cancelled")
        assertTrue("the weights' part is kept", File(root, big.path + ".part").length() in 1 until big.size)
        // the next call goes on from it
        slowMs = { 0 }
        s.fetch(want)
        for (e in want) assertTrue("${e.path} in place", s.downloader.isInPlace(e))
    }

    @Test
    fun progressIsTheSumOfTheFilesAndNeverGoesBack() {
        publish()
        val root = dir()
        val s = store(root)
        val want = profile(s)
        val total = ModelProfile.bytes(want)
        slowMs = { 1 }
        // the weights' first answer is cut: the file is resumed inside the call, its progress from its part
        val big = want.maxByOrNull { it.size }!!
        cutAt = { p, range -> if (p == big.path && range == null) 1_000_000L else null }
        val seen = Collections.synchronizedList(ArrayList<ProfileProgress>())
        FilesAtOnce(s.downloader, want.sortedByDescending { it.size }, 4, null, { p -> seen.add(p) }, "").run()
        val bytes = seen.map { it.bytes }
        assertTrue("reports: ${bytes.size}", bytes.size > want.size)
        assertTrue("never lower than a report before it: $bytes", bytes.zipWithNext().all { (a, c) -> c >= a })
        assertTrue("every report is of the call's total", seen.all { it.total == total })
        assertEquals("the last report is every byte", total, seen.last().bytes)
        for (e in want) assertTrue("${e.path} in place", s.downloader.isInPlace(e))
    }

    @Test
    fun aFileInOneStreamIsHashedWhileItIsWritten() {
        publish()
        val root = dir()
        val s = store(root, ranges = RangePlan.SINGLE)
        val big = profile(s).maxByOrNull { it.size }!!
        val r = s.downloader.fetch(big)
        assertTrue(r.ok)
        assertEquals("the whole file was in the digest when its last byte came", big.size, r.hashedInFlight)
    }

    @Test
    fun aFileResumedFromItsPartIsHashedFromThePartThenAsItIsWritten() {
        publish()
        val root = dir()
        val s = store(root, ranges = RangePlan.SINGLE)
        val big = profile(s).maxByOrNull { it.size }!!
        cutAt = { p, range -> if (p == big.path && range == null) 700_000L else null }
        assertTrue("the first call is cut", runCatching { ModelStore(root, base, UrlConnectionTransport(), HeaderProvider { mapOf("x-qlab-key" to "k1") },
            TestKit.verifier, ranges = RangePlan.SINGLE, retry = RetryPlan.NONE).downloader.fetch(big) }.isFailure)
        val part = File(root, big.path + ".part")
        assertTrue("a part is kept: ${part.length()}", part.length() in 1 until big.size)
        val r = s.downloader.fetch(big)
        assertTrue(r.ok)
        assertEquals("resumed from the part", 700_000L, r.resumedFrom)
        assertEquals("the part's bytes, then the rest as it came", big.size, r.hashedInFlight)
        assertTrue(s.downloader.isInPlace(big))
    }

    @Test
    fun aFileInRangesIsHashedAsItsRangesComplete() {
        publish()
        val root = dir()
        // 256 KiB ranges, four at a time: the 3 MiB weights in twelve ranges, out of order
        val plan = RangePlan(threshold = 1L shl 20, rangeSize = 256L shl 10, parallel = 4)
        slowMs = { 2 }
        val s = store(root, ranges = plan)
        val big = profile(s).maxByOrNull { it.size }!!
        val r = s.downloader.fetch(big)
        assertTrue(r.ok && r.ranged)
        assertEquals("the complete ranges were followed into the digest", big.size, r.hashedInFlight)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
    }

    @Test
    fun aRangedFileResumedFromItsMapIsHashedFromTheKeptRangesFirst() {
        publish()
        val root = dir()
        val plan = RangePlan(threshold = 1L shl 20, rangeSize = 256L shl 10, parallel = 4)
        val big = profile(store(root, ranges = plan)).maxByOrNull { it.size }!!
        // the sixth range is cut: the five before it are kept in the map (and those after it that were under way)
        val r5 = "bytes=${5 * (256L shl 10)}-${6 * (256L shl 10) - 1}"
        cutAt = { p, range -> if (p == big.path && range == r5) 100_000L else null }
        slowMs = { 1 }
        val first = runCatching { ModelStore(root, base, UrlConnectionTransport(), HeaderProvider { mapOf("x-qlab-key" to "k1") }, TestKit.verifier,
            ranges = plan, retry = RetryPlan.NONE).downloader.fetch(big) }
        assertTrue("the first call is cut", first.isFailure)
        assertTrue("its map is kept", File(root, big.path + RangedFetch.MAP_SUFFIX).isFile)
        cutAt = { _, _ -> null }
        val r = store(root, ranges = plan).downloader.fetch(big)
        assertTrue("the five ranges before the sixth were kept: ${r.resumedFrom}", r.ok && r.resumedFrom >= 5 * (256L shl 10) && r.resumedFrom < big.size)
        assertEquals(big.size, r.hashedInFlight)
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
    }

    @Test
    fun thePartsDigestAgreesWithTheFile() {
        val d = dir()
        val bytes = ByteArray(1_234_567).also { java.util.Random(3).nextBytes(it) }
        val part = File(d, "x.part")
        // what an earlier call left, read once, then the rest as it is written
        part.writeBytes(bytes.copyOfRange(0, 500_000))
        val h = PartHash(part)
        h.upTo(500_000)
        assertEquals(500_000L, h.bytes)
        RandomAccessFile(part, "rw").use { f ->
            f.seek(500_000)
            var at = 500_000
            while (at < bytes.size) {
                val n = minOf(65_536, bytes.size - at)
                f.write(bytes, at, n)
                h.add(bytes.copyOfRange(at, at + n), n)
                at += n
            }
        }
        assertEquals(bytes.size.toLong(), h.bytes)
        assertEquals(bytes.size.toLong() to sha(bytes), h.finish())
        // following ranges reads them; a reset starts over; finish reads what is not held
        val g = PartHash(part)
        g.follow(300_000)
        assertEquals(300_000L, g.bytes)
        g.follow(200_000)
        assertEquals("never back", 300_000L, g.bytes)
        g.reset()
        assertEquals(0L, g.bytes)
        assertEquals(bytes.size.toLong() to sha(bytes), g.finish())
        // a digest of a file written beside the part takes the part's place with it
        val other = ByteArray(9_999).also { java.util.Random(4).nextBytes(it) }
        val beside = File(d, "x.part.new").apply { writeBytes(other) }
        val n = PartHash(beside).apply { add(other, other.size) }
        Files.move(beside.toPath(), part.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val p = PartHash(part)
        p.adopt(n)
        assertEquals(other.size.toLong(), p.bytes)
        assertEquals(other.size.toLong() to sha(other), p.finish())
    }

    /** The cause an error report names for [x] (its HTTP status). */
    private fun causeOf(x: Throwable) = ai.quranlab.tajweedformer.cloud.ErrorCause.of(x as TajweedError)
}
