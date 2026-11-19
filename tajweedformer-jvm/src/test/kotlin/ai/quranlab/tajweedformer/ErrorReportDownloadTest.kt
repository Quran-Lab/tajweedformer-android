package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.DownloadRuleServer.Do
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.cloud.ErrorRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Contract section 24 from the failure itself, for the model's files: the SDK's real HTTP code against a local server
 * that answers the manifest and a file with a 503, a 429 `model_limit`, a refused connection, the bytes of another file,
 * and a store that cannot take the file. Each failure names its stage and reason (24.3), the status, the platform
 * error's class and the resumes inside the call; each failed download carries its telemetry reason (24.8), a failed
 * manifest under the build the config named.
 */
class ErrorReportDownloadTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: DownloadRuleServer
    private lateinit var served: File
    private val results = CopyOnWriteArrayList<DownloadResult>()

    private fun dir() = Files.createTempDirectory("tj-errdl-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = DownloadRuleServer(served)
        SdkEvents.add(object : SdkObserver {
            override fun onDownloadFinished(result: DownloadResult) { results.add(result) }
        })
    }

    @After
    fun stop() {
        server.close()
        SdkEvents.clear()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun publish(): ModelManifest = TestKit.sign(TestKit.manifest(files)).also { TestKit.writeTransport(served, files, it) }

    /** Two resumes of 10 ms: the rule as shipped with short pauses. */
    private fun store(root: File, base: String = server.base, expect: ModelManifest? = null) =
        ModelStore(root, base, UrlConnectionTransport(5_000, 5_000), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier,
            expectBuild = expect?.build, expectVersion = expect?.version, retry = RetryPlan(longArrayOf(10, 10)))

    private fun failure(body: () -> Unit): TajweedError = try {
        guard(body)
        throw AssertionError("the call did not fail")
    } catch (e: TajweedError) {
        e
    }

    private fun cause(e: TajweedError) = ErrorCause.of(e).let { listOf(it.stage, it.reason, it.httpStatus, it.errorCode, it.retries) }

    @Test
    fun aManifestAnswered503() {
        val m = publish()
        server.plan = { p, _, _ -> if (p == ModelManifest.FILE_NAME) Do.Status(503, """{"error":"unavailable"}""") else Do.Serve }
        val e = failure { store(dir(), expect = m).manifest(true) }
        assertEquals("download_failed", e.code)
        assertEquals(listOf("manifest", "http", 503, null, null), cause(e))
        // 24.8: the manifest counts as a failed download of the build the config named, under its version
        val r = results.single()
        assertEquals(listOf(ModelManifest.FILE_NAME, false, m.build, "http"), listOf(r.path, r.ok, r.revision, r.failureReason))
        assertEquals(m.version, ModelIds.version(r.revision))
    }

    @Test
    fun aManifestOverTheDailyLimit() {
        publish()
        server.plan = { p, _, _ -> if (p == ModelManifest.FILE_NAME) Do.Status(429, """{"error":"model_limit"}""") else Do.Serve }
        val e = failure { store(dir()).manifest(true) }
        assertEquals(listOf("manifest", "rate_limited", 429, "model_limit", null), cause(e))
        // 24.4: an answer of the server is download_failed, never needs_connection
        val c = ErrorCause.of(e)
        assertEquals("download_failed", ErrorRules.codeFor(c.stage, c.reason, c.httpStatus, "none", c.errorCode))
    }

    @Test
    fun aFileAnswered503AfterItsResumes() {
        publish()
        val s = store(dir())
        val layers = s.manifest(true).files.first { it.path == BIG }
        server.plan = { p, _, _ -> if (p == BIG) Do.Status(503, """{"error":"unavailable"}""") else Do.Serve }
        results.clear()
        val e = failure { s.downloader.fetch(layers) }
        assertEquals("download_failed", e.code)
        assertEquals(listOf("download", "http", 503, null, 2), cause(e))
        assertEquals("http", results.single().failureReason)
    }

    @Test
    fun aFileWithNoConnectionNamesThePlatformsError() {
        val m = publish()
        val root = dir()
        store(root).manifest(true)
        val closed = java.net.ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val s = store(root, "http://127.0.0.1:$closed/m/")
        val layers = s.manifest().files.first { it.path == BIG }
        val e = failure { s.downloader.fetch(layers) }
        val c = ErrorCause.of(e)
        assertEquals(listOf("download", "network", 2), listOf(c.stage, c.reason, c.retries))
        assertEquals("java.net.ConnectException", c.errorName)
        // nothing of the build on the device: needs_connection; part of it: download_failed (24.4 rule 1)
        assertEquals("needs_connection", ErrorRules.codeFor(c.stage, c.reason, c.httpStatus, "none"))
        assertEquals("download_failed", ErrorRules.codeFor(c.stage, c.reason, c.httpStatus, "partial"))
        assertEquals("network", results.last().failureReason)
        assertTrue(m.build.isNotEmpty())
    }

    @Test
    fun aFileWhoseHashDiffersIsChecksumMismatch() {
        publish()
        val s = store(dir())
        val layers = s.manifest(true).files.first { it.path == BIG }
        // the server's copy is the length of the file and not its bytes
        File(served, BIG).let { f -> f.writeBytes(ByteArray(f.length().toInt()) { 7 }) }
        results.clear()
        val e = failure { s.downloader.fetch(layers) }
        // 24.4 rule 6
        assertEquals("checksum_mismatch", e.code)
        assertEquals(listOf("download", "hash", null, null, null), cause(e))
        assertEquals("hash_mismatch", results.single().failureReason)
    }

    @Test
    fun aStoreThatCannotTakeTheFileIsTheStoresNotTheNetworks() {
        publish()
        val root = dir()
        val s = store(root)
        val layers = s.manifest(true).files.first { it.path == BIG }
        // part of the file is on the device; the whole file comes as a 200 and is written beside it, where the store
        // has a folder that is not empty: the write fails on the device
        File(root, "$BIG.part").writeBytes(ByteArray(100))
        File(root, "$BIG.part${ModelDownloader.NEW_SUFFIX}").also { it.mkdirs() }.resolve("x").writeText("x")
        server.plan = { p, _, _ -> if (p == BIG) Do.Whole else Do.Serve }
        results.clear()
        val e = failure { s.downloader.fetch(layers) }
        val c = ErrorCause.of(e)
        assertEquals(listOf("download", "storage"), listOf(c.stage, c.reason))
        assertEquals("storage_failed", ErrorRules.codeFor(c.stage, c.reason, c.httpStatus, "partial"))
        // never asked again: one request, not the five resumes of a network failure
        assertEquals(1, server.of(BIG).size)
        assertEquals("storage", results.single().failureReason)
    }

    @Test
    fun aWriteOnAFullVolumeIsQuota() {
        val x = RecognizerException.DownloadFailed(BIG, "network: No space left on device",
            StoreWriteFailed(IOException("No space left on device"))).tagged(BIG, "network: No space left on device", StoreWriteFailed(IOException("No space left on device")))
        val c = ErrorCause.of(x)
        assertEquals(listOf("download", "quota"), listOf(c.stage, c.reason))
        assertEquals("storage_full", ErrorRules.codeFor(c.stage, c.reason, null, "partial"))
        assertTrue(!RetryPlan.resumable(x))
    }

    @Test
    fun aStoreThatCannotBeWrittenAtAllIsStorageFailed() {
        publish()
        // the build's folder is a file: the manifest cannot be kept
        val root = dir().resolve("models").also { it.writeText("") }
        val e = failure { store(root).manifest(true) }
        assertEquals("storage_failed", e.code)
        val c = ErrorCause.of(e)
        assertEquals(listOf("storage", "write"), listOf(c.stage, c.reason))
        assertTrue("${c.errorName}", c.errorName?.startsWith("java.") == true)
    }

    companion object {
        const val BIG = "layers.zfw.gz"

        /** The test kit's model with a 3 MiB weights file (one request). */
        private val files: List<TestKit.F> by lazy {
            TestKit.v4Files(contexts = listOf("c16_l64")).map { f ->
                if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(3 shl 20).also { java.util.Random(21).nextBytes(it) }, "weights") else f
            }
        }
    }
}
