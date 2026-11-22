package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.BackgroundDownloads
import ai.quranlab.tajweedformer.Continued
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedErrorCause
import ai.quranlab.tajweedformer.TajweedSessionOptions
import ai.quranlab.tajweedformer.core.CoreLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Section 33 (NO_WAIT_40.md, SDK 4.0.2): nothing waits for Wi-Fi. The report that started it (Apple 4.0.1): after
 * `prefetch()` installed the default context, a session on another context (c24_l256) on mobile data never got that
 * context's files. Here through the public Client with the REAL Core and the two-context test build, the platform's
 * connection set by the test; every case asserts on the requests the stand-in server received, not only on the outcome.
 *
 * Run first on the 4.0.1 code (4f30872 and, with the test build's whole NPU build, fba85df: CI runs 44941896751 and
 * 44944800011, 4 of 5 failed). `anotherContextDownloadsOnMobileData` PASSED there: Android 4.0.1 never held another
 * context's files (its `ensureContext` went straight to `ModelStore.ensureCpu`). The rest FAILED: with no network the
 * session was `download_failed`, not `needs_connection`; with the data saver on the reference data was never asked for
 * (`saveData()`); the accelerator program was never asked for on mobile data (`connection() != "unmetered"`); and a
 * download owed `unmetered_only` gave `RETRY` with no request. On 4.0.1 the test's platform also answered
 * `isUnmetered()` and `saveData()`; 4.0.2's has neither.
 */
class NoWaitTest : ai.quranlab.tajweedformer.OpenChecked() {
    private lateinit var kit: TwoContextKit
    private val clients = ArrayList<TajweedClient>()

    @Before
    fun up() {
        assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)
        kit = TwoContextKit()
    }

    @After
    fun down() {
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        if (::kit.isInitialized) kit.close()
    }

    private fun client(soc: String? = null, downloads: BackgroundDownloads? = null): TajweedClient {
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        return kit.client(soc = soc, downloads = downloads).also { clients += it }
    }

    private fun asked(path: String) = kit.served.requests.filter { it.path == path }

    /**
     * The 4.0.1 report (33.4 `other_context_metered`, and `other_context_low_data` with [dataSaver]): the default context
     * installed by prefetch(), the model open, then a session on c24_l256 on mobile data. Its three files are asked for
     * and come whole, the session opens on it, and the build's reference data came with the model (33.1: nothing the SDK
     * downloads waits). With the data saver on, Android reports `metered` and 4.0.2 asks nothing else ([TwoContextKit]).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun otherContext(dataSaver: Boolean) = runBlocking {
        withTimeout(120_000) {
            kit.connection = "metered"
            val c = client()
            c.prefetch()
            val other = kit.contextFiles("c24_l256")
            assertTrue("prefetch() asked for another context's files: ${kit.paths()}", other.none { it in kit.paths() })
            kit.contextFiles("c16_l64").forEach { p -> assertEquals("$p by prefetch()", 200, asked(p).single().status) }
            val m = c.model()
            assertEquals("the reference data with the model, on mobile data${if (dataSaver) " with the data saver on" else ""}: ${kit.paths()}",
                listOf(200), asked(kit.reference).map { it.status })
            val s = m.session(TajweedSessionOptions(context = "c24_l256"))
            assertEquals("c24_l256", m.contextInForce())
            for (p in other) {
                val r = asked(p)
                assertEquals("$p: ${kit.paths()}", listOf(200), r.map { it.status })
                assertEquals(p, File(kit.served.root, p).length(), r.single().sent)
            }
            s.cancel()
            m.close()
        }
    }

    @Test
    fun anotherContextDownloadsOnMobileData() = otherContext(dataSaver = false)

    @Test
    fun anotherContextDownloadsWithTheDataSaverOn() = otherContext(dataSaver = true)

    /**
     * 33.4 `other_context_none` and 24.13 item 3: no network at all, the default context on the device, a session on
     * c24_l256: `needs_connection` (the files the call needs are not on the device), not a download failure.
     */
    @Test
    fun anotherContextWithNoNetworkNeedsAConnection() = runBlocking {
        withTimeout(120_000) {
            val c = client()
            c.prefetch()
            val m = c.model()
            kit.connection = "none"
            kit.served.down = true
            val e = try {
                m.session(TajweedSessionOptions(context = "c24_l256"))
                null
            } catch (x: TajweedError) {
                x
            }
            assertNotNull("the session opened with no network", e)
            assertEquals("needs_connection", e!!.code)
            // the cause (24.13): no answer from the model host, and the model's files (c24_l256's) are not on the device
            assertEquals(TajweedErrorCause("download", "network", errorName = e.errorCause?.errorName, needs = "model"), e.errorCause)
            assertNotNull("the system error's class", e.errorCause?.errorName)
            m.close()
        }
    }

    /**
     * 24.13 item 3, `lease`: the model on the device (installed by prefetch()), its lease gone from the device and no
     * answer from Quran Lab: `needs_connection`, and it waits for the lease, not for the model's files.
     */
    @Test
    fun theModelOnTheDeviceWithNoLeaseAndNoAnswerNeedsTheLease() = runBlocking {
        withTimeout(120_000) {
            client().prefetch()
            clients.forEach { it.close() }
            clients.clear()
            File(File(kit.storage, "core"), "lease.json").delete()
            kit.stub.down = true
            kit.served.down = true
            val e = try {
                client().model().close()
                null
            } catch (x: TajweedError) {
                x
            }
            assertNotNull("the model opened with no lease and no answer", e)
            assertEquals("needs_connection", e!!.code)
            assertEquals("${e.errorCause} ${File(kit.storage, "models").walkTopDown().filter { it.isFile }.map { it.path.substringAfter("models") }.toList()}",
                "lease", e.errorCause?.needs)
        }
    }

    /** The platform's job, recorded: how often it was asked for (4.0.2 asks with no network rule: there is none). */
    private class Job : BackgroundDownloads {
        val owed = java.util.concurrent.atomic.AtomicInteger()
        override fun owe() { owed.incrementAndGet() }
        override fun settled() {}
    }

    /**
     * 33.3 (`queued_by_401_metered`): a download 4.0.1 owed with `unmetered_only: true` (its `prefetch(unmeteredOnly =
     * true)`) goes on after the update on mobile data: the new Client gives the job again at its start (Android's unique
     * work replaced by one on any network), the stored `unmetered_only` is read as absent and dropped, and the job
     * continues the download: the missing file is asked for.
     */
    @Test
    fun aDownloadOwedUnmeteredOnlyBy401GoesOnOnMobileData() = runBlocking {
        withTimeout(120_000) {
            val weights = kit.manifest.files.single { it.role == "weights" }.path
            // a first download that stops in the weights: owed
            kit.served.cut = { p -> if (p == weights) 100L else null }
            val first = client(downloads = Job())
            try { first.prefetch() } catch (_: TajweedError) {}
            val owed = File(File(kit.storage, "models"), "download.json")
            assertTrue("nothing owed", owed.isFile)
            // as 4.0.1's prefetch(unmeteredOnly = true) left it
            owed.writeText(Json.write(linkedMapOf("unmetered_only" to true, "version" to null)))
            first.close()
            kit.served.cut = null
            kit.served.requests.clear()
            kit.connection = "metered"
            val job = Job()
            val c = client(downloads = job)
            assertEquals("the job given again at the start, on any network", 1, job.owed.get())
            assertEquals("the stored network rule dropped", mapOf("version" to null), Json.parse(owed.readText()))
            assertTrue("a request at the start: ${kit.paths()}", kit.paths().isEmpty())
            assertEquals(Continued.DONE, c.continueDownload())
            // the rest of the weights (a resume of the part the cut left, or the whole file)
            val got = asked(weights)
            assertTrue("the weights on mobile data: ${kit.paths()}", got.isNotEmpty() && got.all { it.status == 200 || it.status == 206 })
            assertTrue("still owed", !owed.exists())
        }
    }

    /**
     * 34.6: the counts per `models` host are kept with the day from the first start, and the background job's download
     * counts too. A first process's download is cut in the weights (the call ends, and the files under way beside it
     * are cancelled); a new process (the job's) finishes it with continueDownload(). The day's counts for the one models
     * host: every request the stand-in received over both processes, the cut as the one unreachable (a cancel is not),
     * one file per path that came whole (the manifest among them), and at least each file's bytes once, at most what
     * the stand-in sent (a cancelled answer's bytes that were never read are not received).
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun theJobsDownloadCountsWithTheFirstProcesssForItsHost() = runBlocking {
        withTimeout(120_000) {
            val weights = kit.manifest.files.single { it.role == "weights" }.path
            kit.served.cut = { p -> if (p == weights) 100L else null }
            val first = client(downloads = Job())
            try { first.prefetch() } catch (_: TajweedError) {}
            first.close()
            kit.served.cut = null
            val c = client(downloads = Job())
            assertEquals(Continued.DONE, c.continueDownload())
            val ctx = TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel8", "tensor_g3", 14, "14"))
            fun n(h: Map<String, Any?>, k: String) = (h[k] as Number).toLong()
            // each path asked for, whole once: its size on the stand-in
            fun whole(all: List<ServedModel.Request>) = all.map { it.path }.distinct().sumOf { File(kit.served.root, it).length() }
            fun files(all: List<ServedModel.Request>) = all.map { it.path }.distinct().size +
                maxOf(0, all.count { it.path == ai.quranlab.tajweedformer.ModelManifest.FILE_NAME && it.status == 200 } - 1).toLong()
            // what the open starts in the background (the reference data) may still be under way: a request counts at its
            // status, its bytes and its file when its answer is read; read once both sides agree
            fun now() = (c.cloud.telemetry.peek(ctx)!!["hosts"] as List<Map<String, Any?>>).single() to
                synchronized(kit.served.requests) { kit.served.requests.toList() }
            var (h, all) = now()
            val until = System.currentTimeMillis() + 20_000
            while ((n(h, "requests") != all.size.toLong() || n(h, "files") != files(all) || n(h, "bytes") < whole(all)) &&
                System.currentTimeMillis() < until) {
                Thread.sleep(200)
                now().let { h = it.first; all = it.second }
            }
            println("34.6 over two processes: $h; the stand-in: ${all.size} requests, ${all.sumOf { it.sent }} bytes sent, ${whole(all)} " +
                "the files' own: $all")
            assertEquals(HostNames.MODELS[0], h["host"])
            assertEquals(all.size.toLong(), n(h, "requests"))
            assertEquals(1L, n(h, "unreachable"))
            assertEquals(files(all), n(h, "files"))
            assertTrue("bytes ${n(h, "bytes")} of ${whole(all)} to ${all.sumOf { it.sent }}", n(h, "bytes") in whole(all)..all.sumOf { it.sent })
        }
    }

    /**
     * SPEC 16.7 with the accelerator build: a fresh install of the default context's CPU set, the model's own small files
     * (the reference data, the sample clips) and tensor_g4's NPU build (program, I/O contract, mask table) asks each
     * file once, with no Range. The test build's files are all small, so 4.0.1 asked them once each too: the difference
     * is in a file above 8 MiB (OneRequestPerFileTest).
     */
    @Test
    fun aFullInstallWithTheNpuBuildAsksEachFileOnce() = runBlocking {
        withTimeout(120_000) {
            kit.leaseFlags = 8
            val c = client(soc = "tensor_g4")
            val m = c.model()
            val npu = kit.manifest.files.filter { it.role.startsWith("npu-") }.map { it.path }
            val until = System.currentTimeMillis() + 15_000
            while (!npu.all { p -> asked(p).isNotEmpty() } && System.currentTimeMillis() < until) Thread.sleep(100)
            val files = synchronized(kit.served.requests) { kit.served.requests.filter { it.path != ai.quranlab.tajweedformer.ModelManifest.FILE_NAME }.toList() }
            val cpu = kit.contextFiles("c16_l64") + kit.manifest.files.filter { it.context == "*" && it.need == ai.quranlab.tajweedformer.ModelManifest.Need.REQUIRED }.map { it.path }
            println("SPEC 16.7 with the NPU build: ${files.size} model-file requests (CPU set ${cpu.size}, NPU ${npu.size}, other ${files.size - cpu.size - npu.size}), ranges ${files.map { it.range }.toSet()}")
            assertEquals("each file once: ${files.map { it.path }}", files.map { it.path }.toSet().size, files.size)
            assertTrue("a Range on a fresh install", files.all { it.range == null })
            assertTrue("the CPU set and the NPU build: ${files.map { it.path }}", (cpu + npu).all { p -> files.any { it.path == p } })
            m.close()
        }
    }

    /**
     * 33.4 `accelerator_metered`: the lease allows accelerator programs, the phone's SoC has a program in the build and
     * a dispatch library: after the model opens on mobile data, the program is asked for.
     */
    @Test
    fun theAcceleratorProgramDownloadsOnMobileData() = runBlocking {
        withTimeout(120_000) {
            kit.leaseFlags = 8
            kit.connection = "metered"
            val c = client(soc = "tensor_g4")
            val m = c.model()
            val until = System.currentTimeMillis() + 15_000
            while (asked(kit.program).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
            assertEquals("the program on mobile data: ${kit.paths()}", listOf(200), asked(kit.program).map { it.status })
            m.close()
        }
    }
}
