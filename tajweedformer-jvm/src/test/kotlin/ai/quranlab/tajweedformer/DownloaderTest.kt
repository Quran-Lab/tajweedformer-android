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

    private fun store(root: File) = ModelStore(root, base, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier)

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
    fun cpuProfileDownloadsVerifiesAndOpens() {
        val files = TestKit.v4Files(listOf("tensor_g5"))
        publish(files)
        val root = dir()
        val events = Collections.synchronizedList(ArrayList<DownloadResult>())
        SdkEvents.add(object : SdkObserver {
            override fun onDownloadFinished(result: DownloadResult) { events.add(result) }
        })
        val progress = ArrayList<ProfileProgress>()
        val model = store(root).ensureCpu(onProgress = { progress.add(it) })
        assertEquals(listOf("c16_l64"), model.availableContexts)
        // only the CPU profile of c16_l64 was requested (plus the manifest), with the app key
        val asked = requests.map { it.substringBefore(" ") }.toSet()
        // the reference data (the expected units) is not in the CPU set: it is fetched only when the app asks
        assertEquals(setOf("manifest.json", "tokens.txt", "calibration.json.gz", "layers.zfw.gz", "frontend.zfw.gz",
            "c16_l64/model_engine2.onnx.zfg.gz", "c16_l64/model_engine2.onnx.zfg.bin.gz", "c16_l64/frames.zfw.gz"), asked)
        assertTrue(requests.all { it.endsWith("key=k1") })
        assertTrue("a first install fetches no NPU file, though the manifest lists a build for this SoC: $requests",
            requests.none { it.startsWith("npu/") || it.startsWith("selftest/") })
        // decoded files kept, transport files and parts gone
        assertArrayEquals(files.first { it.path == "layers.zfw" }.bytes, File(root, "layers.zfw").readBytes())
        assertFalse(File(root, "layers.zfw.gz").exists())
        assertFalse(root.walk().any { it.name.endsWith(".part") || it.name.endsWith(".tmp") })
        assertEquals(7, events.count { it.ok })   // the seven files of the CPU set (2.x had the expected table as an eighth)
        assertEquals(progress.last().total, progress.last().bytes)
        // a second call downloads nothing (the verified manifest on disk is used unless refreshed)
        requests.clear()
        store(root).ensureCpu()
        assertEquals(emptyList<String>(), requests.toList())
        // another context later: only its three files
        requests.clear()
        val s = store(root)
        s.ensureCpu("c24_l72")
        assertEquals(setOf("c24_l72/model_engine2.onnx.zfg.gz", "c24_l72/model_engine2.onnx.zfg.bin.gz", "c24_l72/frames.zfw.gz"),
            requests.map { it.substringBefore(" ") }.toSet())
        assertEquals(listOf("c16_l64", "c24_l72"), s.openIfReady("c24_l72")!!.availableContexts)
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
        } catch (x: RecognizerException.DownloadFailed) {
            assertTrue(x.reason, x.reason.contains("do not match") || x.reason.contains("more than"))
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
    fun npuFilesWaitForTheCpuSelfTestAndAnUnmeteredNetwork() {
        val files = TestKit.v4Files(listOf("tensor_g5"))
        publish(files)
        val root = dir()
        val s = store(root)
        s.ensureCpu()
        val metered = NetworkPolicy { false }
        assertEquals("soc_unknown", s.ensureNpu(null, "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        assertEquals("no_npu_build", s.ensureNpu("tensor_g3", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        assertEquals("no_npu_build", s.ensureNpu("tensor_g5", "c24_l72", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        requests.clear()
        assertEquals("cpu_self_test_pending", s.ensureNpu("tensor_g5", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, false).reason)
        val gated = s.ensureNpu("tensor_g5", "c16_l64", metered, true)
        assertEquals("metered_network", gated.reason)
        assertNull(gated.files)
        assertTrue("nothing fetched while gated: $requests", requests.isEmpty())
        val ok = s.ensureNpu("tensor_g5", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true)
        assertEquals("downloaded", ok.reason)
        assertTrue(ok.files!!.model.isFile && ok.files!!.io.isFile && ok.files!!.masks.isFile)
        assertEquals(3, requests.size)
        // in place now: ready even on a metered network
        assertEquals("ready", s.ensureNpu("tensor_g5", "c16_l64", metered, true).reason)
        assertTrue(s.isNpuReady("tensor_g5", "c16_l64"))
    }

    private fun vec(name: String): ByteArray = javaClass.getResourceAsStream("/progdelta_vectors/$name")!!.use { it.readBytes() }

    /** A V4 folder whose CPU files are the delta vectors' (so the vectors' deltas rebuild against them), with an NPU
     *  build for exynos2600 made of [program] (full, delta, or both). */
    private fun deltaFolder(delta: ByteArray?, full: ByteArray?): List<TestKit.F> {
        val cpu = mapOf("layers.zfw" to "cpu/layers.zfw", "frontend.zfw" to "cpu/frontend.zfw",
            "c16_l64/model_engine2.onnx.zfg" to "cpu/model_engine2.onnx.zfg", "c16_l64/model_engine2.onnx.zfg.bin" to "cpu/model_engine2.onnx.zfg.bin")
        val files = TestKit.v4Files().map { f -> cpu[f.path]?.let { TestKit.F(f.path, vec(it), f.role, f.context, f.need, f.platforms, f.soc, f.gz) } ?: f }
        val d = "npu/exynos2600/c16_l64/tajweedformer_v4_exynos2600_c16_l64"
        val out = files.toMutableList()
        if (full != null) out += TestKit.F("$d.tflite", full, "npu-model", "c16_l64", "optional", listOf("android"), "exynos2600")
        if (delta != null) out += TestKit.F("$d.tflite.qlpd", delta, "npu-delta", "c16_l64", "optional", listOf("android"), "exynos2600")
        out += TestKit.F("$d.io.json", "{}".toByteArray(), "npu-io", "c16_l64", "optional", listOf("android"), "exynos2600")
        out += TestKit.F("$d.masks.json", "{}".toByteArray(), "npu-masks", "c16_l64", "optional", listOf("android"), "exynos2600")
        return out
    }

    /** A program shipped as a QLPD1 delta: rebuilt from the CPU files on the device, checked, kept (the delta's decoded file
     *  goes); preferred over the full program when both are listed; a delta that does not rebuild falls back to the full
     *  program, and without one the download fails (`verify`). */
    @Test
    fun anNpuProgramComesFromItsDeltaFirstAndFromTheFullFileOnlyAsAFallback() {
        val delta = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(vec("cases/int8_frontend_and_two_tensors.qlpd.gz"))).use { it.readBytes() }
        val target = vec("cases/int8_frontend_and_two_tensors.bin")
        val program = "npu/exynos2600/c16_l64/tajweedformer_v4_exynos2600_c16_l64.tflite"
        // delta and full both listed: the delta is used, the full program never requested
        publish(deltaFolder(delta, target))
        val root = dir()
        val s = store(root)
        s.ensureCpu()
        requests.clear()
        val ok = s.ensureNpu("exynos2600", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true)
        assertEquals("downloaded", ok.reason)
        assertArrayEquals(target, ok.files!!.model.readBytes())
        assertTrue("the full program was not fetched: $requests", requests.none { it.startsWith("$program.gz ") })
        assertTrue(requests.any { it.startsWith("$program.qlpd.gz ") })
        assertFalse("the delta's decoded file is gone", File(root, "$program.qlpd").exists())
        assertTrue(s.isNpuReady("exynos2600", "c16_l64"))
        assertEquals("ready", s.ensureNpu("exynos2600", "c16_l64", NetworkPolicy { false }, true).reason)
        s.prune()
        assertTrue("prune keeps the rebuilt program", File(root, program).isFile)
        // a delta only: the same
        publish(deltaFolder(delta, null))
        val r2 = dir()
        val s2 = store(r2).also { it.ensureCpu() }
        assertArrayEquals(target, s2.ensureNpu("exynos2600", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).files!!.model.readBytes())
        assertTrue(s2.isNpuReady("exynos2600", "c16_l64"))
        // a delta that does not rebuild to its target (one body byte changed), with the full program listed: the full one
        val bad = delta.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        publish(deltaFolder(bad, target))
        val r3 = dir()
        val s3 = store(r3).also { it.ensureCpu() }
        requests.clear()
        assertArrayEquals(target, s3.ensureNpu("exynos2600", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).files!!.model.readBytes())
        assertTrue("fell back to the full program: $requests", requests.any { it.startsWith("$program.gz ") })
        // ... and without it: the download fails, nothing is left
        publish(deltaFolder(bad, null))
        val r4 = dir()
        val s4 = store(r4).also { it.ensureCpu() }
        try {
            s4.ensureNpu("exynos2600", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true)
            fail("a delta that does not rebuild was used")
        } catch (e: RecognizerException.DownloadFailed) {
            assertTrue(e.reason, e.reason.contains("sha256"))
        }
        assertFalse(File(r4, program).exists())
        assertFalse(s4.isNpuReady("exynos2600", "c16_l64"))
    }

    @Test
    fun aChainedNpuBuildDownloadsEveryPartUnderTheSameGates() {
        // the Exynos 2500 build: 9 parts in the real thing, 4 here, each its own npu-part file
        val files = TestKit.v4Files(listOf("tensor_g5"), chainSocs = listOf("exynos2500"), chainParts = 4)
        publish(files)
        val root = dir()
        val s = store(root)
        s.ensureCpu()
        requests.clear()
        assertEquals("metered_network", s.ensureNpu("exynos2500", "c16_l64", NetworkPolicy { false }, true).reason)
        assertEquals("cpu_self_test_pending", s.ensureNpu("exynos2500", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, false).reason)
        assertEquals("no_npu_build", s.ensureNpu("exynos2500", "c24_l72", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        assertTrue("nothing fetched while gated: $requests", requests.isEmpty())
        val ok = s.ensureNpu("exynos2500", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true)
        assertEquals("downloaded", ok.reason)
        val f = ok.files!!
        assertEquals((0 until 4).map { "tajweedformer_v4_exynos2500_c16_l64.P$it.tflite" }, f.parts.map { it.name })
        assertEquals(f.parts[0], f.model)
        assertTrue(f.parts.all { it.isFile } && f.io.isFile && f.masks.isFile)
        for ((k, p) in f.parts.withIndex()) assertArrayEquals(files.first { it.path.endsWith(".P$k.tflite") }.bytes, p.readBytes())
        assertEquals(6, requests.size)   // 4 parts + io + masks, nothing of tensor_g5
        assertTrue(requests.none { it.contains("tensor_g5") })
        assertTrue(s.isNpuReady("exynos2500", "c16_l64"))
        // one part lost: not ready (all or nothing), and only that part is fetched again
        f.parts[2].delete()
        assertFalse(s.isNpuReady("exynos2500", "c16_l64"))
        assertNull(s.ensureNpu("exynos2500", "c16_l64", NetworkPolicy { false }, true).files)
        requests.clear()
        assertEquals("downloaded", s.ensureNpu("exynos2500", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        assertEquals(1, requests.size)
        assertTrue(requests[0], requests[0].startsWith("npu/exynos2500/c16_l64/tajweedformer_v4_exynos2500_c16_l64.P2.tflite.gz"))
        // the one-model build is still three files
        assertEquals(emptyList<File>(), s.ensureNpu("tensor_g5", "c16_l64", NetworkPolicy.ALWAYS_UNMETERED, true).files!!.parts)
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
