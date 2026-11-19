package ai.quranlab.tajweedformer

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The downloader and the model store against a local HTTP server that speaks Range / If-Range /
 * ETag: a resume after a cut, a resume refused by a changed ETag, a bad hash, gzip, the unmetered
 * gate for NPU files, concurrent fetches of one file, and the observer's events.
 */
class DownloaderTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: HttpServer
    private lateinit var served: File
    private val requests = Collections.synchronizedList(ArrayList<String>())
    /** Cut the body of the next response for this path after this many bytes. */
    private val cutAfter = Collections.synchronizedMap(HashMap<String, Int>())
    @Volatile private var etag = "\"v1\""
    private val bodyBytes = AtomicInteger()

    private fun dir() = Files.createTempDirectory("tj-dl-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/m/")
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            requests.add("$path range=$range if-range=$ifRange key=${ex.requestHeaders.getFirst("x-qlab-key")}")
            val f = File(served, path)
            if (!f.isFile) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            val all = f.readBytes()
            ex.responseHeaders.add("ETag", etag)
            var start = 0
            val m = range?.let { Regex("bytes=(\\d+)-").find(it) }
            if (m != null && (ifRange == null || ifRange == etag)) {
                start = m.groupValues[1].toInt()
                ex.responseHeaders.add("Content-Range", "bytes $start-${all.size - 1}/${all.size}")
                ex.sendResponseHeaders(206, (all.size - start).toLong())
            } else {
                ex.sendResponseHeaders(200, all.size.toLong())
            }
            val cut = cutAfter.remove(path)
            val body = all.copyOfRange(start, all.size)
            try {
                ex.responseBody.use { out ->
                    if (cut != null) {
                        out.write(body, 0, minOf(cut, body.size))
                        out.flush()
                        bodyBytes.addAndGet(minOf(cut, body.size))
                        throw java.io.IOException("cut")
                    }
                    out.write(body)
                    bodyBytes.addAndGet(body.size)
                }
            } catch (_: java.io.IOException) {
            }
            ex.close()
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

    private fun publish(files: List<TestKit.F>, m: ModelManifest = TestKit.sign(TestKit.manifest(files))): ModelManifest {
        TestKit.writeTransport(served, files, m)
        return m
    }

    private fun store(root: File) = ModelStore(root, base, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier, retry = RetryPlan.NONE)

    /** The CPU profile is the CPU files only: an accelerator or self-test file is never in it, whatever its `need`. */
    @Test
    fun theCpuProfileNeverHoldsAnAcceleratorFile() {
        val files = TestKit.v4Files(listOf("tensor_g5")) +
            TestKit.F("selftest/selftest.json", "{}".toByteArray(), "selftest", "*", "required") +
            TestKit.F("selftest/expected_c16_l64.json", "{}".toByteArray(), "selftest", "c16_l64", "required") +
            TestKit.F("npu/tensor_g5/c16_l64/x.tflite", ByteArray(8), "npu-model", "c16_l64", "required", listOf("android"), "tensor_g5")
        val m = TestKit.manifest(files)
        val cpu = ModelProfile.cpu(m, "c16_l64")
        assertTrue(cpu.isNotEmpty())
        assertTrue(cpu.map { it.role }.toString(), cpu.none { it.role in ModelProfile.ACCELERATOR_ROLES })
        assertTrue(cpu.none { it.path.startsWith("npu/") || it.path.startsWith("selftest/") })
    }

    @Test
    fun aCutDownloadResumesWithRangeAndIfRange() {
        val files = TestKit.v4Files()
        publish(files)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == "layers.zfw.gz" }
        cutAfter["layers.zfw.gz"] = 100_000
        try {
            s.downloader.fetch(layers)
            fail("a cut download succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
        }
        val part = File(root, "layers.zfw.gz.part")
        assertTrue(part.isFile)
        val had = part.length()
        assertTrue("part $had", had in 1..100_000)
        bodyBytes.set(0)
        requests.clear()
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertEquals(had, r.resumedFrom)
        assertEquals(layers.size - had, r.networkBytes)
        assertEquals((layers.size - had).toInt(), bodyBytes.get())
        assertTrue(requests.single().contains("range=bytes=$had- if-range=\"v1\""))
        assertTrue(s.downloader.isInPlace(layers))
        assertFalse(part.exists())
    }

    @Test
    fun aChangedFileIsNotContinued() {
        val files = TestKit.v4Files()
        publish(files)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == "layers.zfw.gz" }
        cutAfter["layers.zfw.gz"] = 50_000
        runCatching { s.downloader.fetch(layers) }
        // the server's file changed (new ETag): If-Range fails, the server sends all of it (200)
        etag = "\"v2\""
        bodyBytes.set(0)
        val r = s.downloader.fetch(layers)
        assertTrue(r.ok)
        assertEquals(layers.size.toInt(), bodyBytes.get())
    }

    @Test
    fun aBadHashDeletesEverything() {
        val files = TestKit.v4Files()
        val m = publish(files)
        // the server's frontend file is not the one the manifest lists (same size, other bytes)
        val fe = files.first { it.path == "frontend.zfw" }
        val bad = TestKit.gzip("frontenD".toByteArray())
        File(served, fe.storedPath).writeBytes(bad)
        val root = dir()
        val s = store(root)
        val e = s.manifest(true).files.first { it.path == fe.storedPath }
        try {
            s.downloader.fetch(e)
            fail()
        } catch (x: TajweedError) {
            // contract 24.4 rule 6: a file whose hash differs is `checksum_mismatch` (until 4.0 `download_failed`)
            assertTrue(x.message, x is RecognizerException.ChecksumMismatch ||
                (x is RecognizerException.DownloadFailed && x.reason.contains("more than")))
        }
        assertFalse(File(root, fe.storedPath + ".part").exists())
        assertFalse(File(root, "frontend.zfw").exists())
        // a stored file that matches but inflates to other bytes: the decoded check deletes both
        val liar = TestKit.F("frontend.zfw", "frontend".toByteArray(), "frontend")
        val other = TestKit.gzip("other!!!".toByteArray())
        File(served, liar.storedPath).writeBytes(other)
        val forged = ModelManifest.FileEntry(liar.storedPath, other.size.toLong(), TestKit.sha(other), listOf("*"), "*", "*", "frontend",
            ModelManifest.Need.REQUIRED, ModelManifest.Encoding.GZIP, ModelManifest.Decoded("frontend.zfw", 8, TestKit.sha("frontend".toByteArray())))
        try {
            s.downloader.fetch(forged)
            fail()
        } catch (x: RecognizerException.DownloadFailed) {
            assertTrue(x.reason, x.reason.contains("decoded"))
        }
        assertFalse(File(root, "frontend.zfw").exists())
        assertFalse(File(root, "frontend.zfw.tmp").exists())
        assertNotNull(m)
    }

    @Test
    fun oneDownloadPerFile() {
        val files = TestKit.v4Files()
        publish(files)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == "layers.zfw.gz" }
        requests.clear()
        val pool = Executors.newFixedThreadPool(4)
        val results = (0 until 4).map { pool.submit<DownloadResult> { s.downloader.fetch(layers) } }.map { it.get() }
        pool.shutdown()
        assertTrue(results.all { it.ok })
        assertEquals(1, requests.size)
        assertEquals(1, results.count { it.networkBytes > 0 })
    }

    @Test
    fun aCancelledDownloadKeepsItsPart() {
        val files = TestKit.v4Files()
        publish(files)
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == "layers.zfw.gz" }
        val c = Cancellation()
        c.cancel()
        try {
            s.downloader.fetch(layers, c)
            fail()
        } catch (e: RecognizerException.DownloadFailed) {
            assertEquals("cancelled", e.reason)
        }
        assertTrue(s.downloader.fetch(layers).ok)
    }

    @Test
    fun aTamperedServerManifestIsRefusedAndTheCachedOneUsed() {
        val files = TestKit.v4Files()
        publish(files)
        val root = dir()
        val s = store(root)
        val good = s.manifest(true)
        File(served, ModelManifest.FILE_NAME).writeText(File(served, ModelManifest.FILE_NAME).readText().replace("v4.0-test.1", "v4.0-test.2"))
        try {
            s.manifest(true)
            fail("a tampered manifest was accepted")
        } catch (e: RecognizerException.SignatureInvalid) {
        }
        // the server down: the verified copy on disk
        server.stop(0)
        assertEquals(good.revision, store(root).manifest(true).revision)
    }
}
