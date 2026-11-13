package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.BackgroundDownloads
import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.Continued
import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 3.1.3: a model download goes on when the app leaves or its process ends. The Client says when a download starts
 * and when nothing more is owed ([BackgroundDownloads]; Android: one WorkManager job), and the platform's job calls
 * `continueDownload()`, which finishes it under the same rules as the app's own call. Here the job is a recorder and
 * the test calls `continueDownload()` as the worker does, also on a new Client over the same storage (a new process).
 */
class BackgroundDownloadTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()
    private val served = ArrayList<ServedModel>()
    private var client: TajweedClient? = null

    /** The platform's job, recorded. */
    private class Job : BackgroundDownloads {
        val owed = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        @Volatile var settled = 0
        override fun owe(unmeteredOnly: Boolean) { owed += unmeteredOnly }
        override fun settled() { settled++ }
    }

    private class Keys : InstallKeyStore {
        private val real = MachineInstallKey(machineId = { "this-device" }, user = { "learner" })
        override fun mac(message: ByteArray): ByteArray? = real.mac(message)
        override fun create() {}
    }

    @Volatile private var unmetered = true
    private val network = object : NetworkPolicy {
        override fun isUnmetered() = unmetered
        override fun connection() = if (unmetered) "unmetered" else "metered"
    }

    @Before
    fun up() {
        stub = StubQlab()
        stub.inference = null
    }

    @After
    fun down() {
        client?.close()
        served.forEach { it.close() }
        stub.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun storage(): File = Files.createTempDirectory("tj-bg-").toFile().also { dirs += it }

    private fun build(): ServedModel = ServedModel.of(files).also { served += it }

    private fun launch(s: ServedModel, dir: File, job: Job): TajweedClient {
        client?.close()
        client = null
        stub.recommended = s.recommended()
        val platform = ClientPlatform(modelRetry = ai.quranlab.tajweedformer.RetryPlan(longArrayOf(300, 300, 300)), cloud = stub.environment(dir, integrity = stubPlay(stub)), apiUrl = stub.base,
            trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = s.url, engine = CountingEngines(), installKeys = Keys(),
            network = network, downloads = job)
        return TajweedClient(stub.key, TajweedClientOptions(), platform, autoLaunch = false).also { client = it }
    }

    private suspend fun error(body: suspend () -> Unit): TajweedError? = try {
        body()
        null
    } catch (e: TajweedError) {
        e
    }

    private fun owedFile(dir: File) = File(File(dir, "models"), "download.json")

    private fun owed(dir: File): Map<*, *>? = try { Json.parse(owedFile(dir).readText()) as Map<*, *> } catch (_: Exception) { null }

    private fun big(s: ServedModel) = synchronized(s.requests) { s.requests.filter { it.path == BIG } }

    private fun start(r: String?) = r?.substringAfter("=")?.substringBefore("-")?.toLongOrNull()

    /** The second range of the weights file drops after 1 MiB, every time. */
    private fun cutSecondRange(s: ServedModel) {
        s.rangeCut = { path, r, _ -> if (path == BIG && (start(r) ?: -1) in R until 2 * R) 1L shl 20 else null }
    }

    @Test
    fun aDownloadIsOwedFromItsStartUntilItIsComplete() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            var seen: Map<*, *>? = null
            s.onRequest = { path -> if (path == BIG && seen == null) seen = owed(dir) }
            val c = launch(s, dir, job)
            val model = c.model()
            assertEquals("the job was asked for once, for any network", listOf(false), job.owed.toList())
            assertNotNull("nothing was owed while the file came", seen)
            assertNull("still owed once the model is on the device", owed(dir))
            assertTrue("the job was not called off", job.settled >= 1)
            model.close()
            // the model on the device: nothing is downloaded, nothing owed
            s.requests.clear()
            launch(s, dir, job).model().close()
            assertEquals(listOf(false), job.owed.toList())
            assertTrue(big(s).isEmpty())
        }
    }

    @Test
    fun aDownloadThatWasCutIsFinishedByTheJobWithNoCallOfTheApp() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            cutSecondRange(s)
            assertEquals("download_failed", error { launch(s, dir, job).model() }?.code)
            assertEquals("owed after the call failed", false, owed(dir)?.get("unmetered_only"))
            assertEquals(0, job.settled)
            // the app's process ended; the job starts a new one: a new Client over the same storage
            client?.close()
            client = null
            s.rangeCut = null
            s.requests.clear()
            val c = launch(s, dir, Job())
            assertEquals(Continued.DONE, c.continueDownload())
            assertEquals("only the range that was not complete: ${big(s)}", listOf("bytes=$R-${2 * R - 1}"), big(s).map { it.range })
            assertNull(owed(dir))
            // the app opens it afterwards with no request for a file
            s.requests.clear()
            c.model().close()
            assertTrue("a file was asked for again: ${s.paths()}", s.paths().none { it != "manifest.json" })
            // nothing owed: the job does nothing
            assertEquals(Continued.DONE, c.continueDownload())
        }
    }

    @Test
    fun anUnmeteredOnlyPrefetchIsContinuedOnlyOnAnUnmeteredNetwork() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            cutSecondRange(s)
            val c = launch(s, dir, job)
            assertNotNull(error { c.prefetch(unmeteredOnly = true) })
            assertEquals("the job waits for an unmetered network", listOf(true), job.owed.toList())
            s.rangeCut = null
            s.requests.clear()
            unmetered = false
            assertEquals(Continued.RETRY, c.continueDownload())
            assertTrue("a request on a metered network: ${s.paths()}", s.paths().isEmpty())
            unmetered = true
            assertEquals(Continued.DONE, c.continueDownload())
            assertNull(owed(dir))
        }
    }

    @Test
    fun theDailyLimitIsNotContinued() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            cutSecondRange(s)
            val c = launch(s, dir, job)
            assertNotNull(error { c.model() })
            s.rangeCut = null
            s.answer = { path, _ -> if (path == BIG) ServedModel.Answer(429, """{"error":"model_limit","message":"The daily limit is reached.","scope":"install"}""") else null }
            s.requests.clear()
            assertEquals(Continued.GAVE_UP, c.continueDownload())
            assertEquals("the refused file was asked for once", 1, big(s).size)
            assertNull("still owed after the daily limit", owed(dir))
            s.requests.clear()
            assertEquals(Continued.DONE, c.continueDownload())
            assertTrue(s.paths().isEmpty())
        }
    }

    @Test
    fun aStopIsNotContinuedAndLeavesNothing() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            cutSecondRange(s)
            val c = launch(s, dir, job)
            assertNotNull(error { c.model() })
            s.rangeCut = null
            s.answer = { path, _ ->
                if (path == BIG) ServedModel.Answer(401, """{"error":"invalid_key","message":"missing or invalid x-qlab-key","reason":"key_revoked"}""") else null
            }
            assertEquals(Continued.GAVE_UP, c.continueDownload())
            assertEquals("a model file is left", emptyList<String>(), File(dir, "models").walkTopDown().filter { it.isFile }.map { it.name }.toList())
            s.requests.clear()
            assertEquals(Continued.DONE, c.continueDownload())
            assertTrue(s.paths().isEmpty())
        }
    }

    @Test
    fun removingTheModelsCallsTheJobOff() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val job = Job()
            cutSecondRange(s)
            val c = launch(s, dir, job)
            assertNotNull(error { c.model() })
            assertNotNull(owed(dir))
            c.removeModels()
            assertNull(owed(dir))
            assertTrue(job.settled >= 1)
            s.requests.clear()
            assertEquals(Continued.DONE, c.continueDownload())
            assertTrue("removed models came back: ${s.paths()}", s.paths().isEmpty())
            // a closed Client continues nothing
            cutSecondRange(s)
            assertNotNull(error { c.model() })
            c.close()
            s.requests.clear()
            assertEquals(Continued.DONE, c.continueDownload())
            assertTrue(s.paths().isEmpty())
        }
    }

    @Test
    fun theProtocolOfADownloadIsInNoStatisticsAndNotOnTheDevice() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val c = launch(s, dir, Job())
            c.model().close()
            // the diagnose report has it, from memory
            assertEquals("http/1.1", c.diagnose().downloadProtocol)
            c.cloud.telemetry.flushToDisk()
            val day = File(dir, "telemetry").listFiles { f -> f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.json")) }!!.single()
            @Suppress("UNCHECKED_CAST")
            val downloads = (Json.parse(day.readText()) as Map<String, Any?>)["downloads"] as List<Map<String, Any?>>
            assertTrue(downloads.isNotEmpty())
            assertFalse("a protocol is in the day's counts on the device: $downloads", day.readText().contains("protocol") || day.readText().contains("http/1.1"))
            val wire = c.cloud.telemetry.peek(TelemetryContext(c.cloud.env.app, c.cloud.env.device))!!
            assertFalse("a protocol is in the body that is sent: $wire", Json.write(wire).contains("protocol") || Json.write(wire).contains("http/1.1"))
        }
    }

    companion object {
        const val BIG = "layers.zfw.gz"
        const val R = 8L shl 20

        /** The test kit's model with a 38 MiB weights file: five 8 MiB ranges. */
        private val files: List<TestKit.F> by lazy {
            TestKit.v4Files().map { f ->
                if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(38 shl 20).also { java.util.Random(13).nextBytes(it) }, "weights") else f
            }
        }
    }
}
