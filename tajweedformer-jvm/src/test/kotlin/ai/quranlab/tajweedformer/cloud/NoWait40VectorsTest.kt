package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.BackgroundDownloads
import ai.quranlab.tajweedformer.Continued
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedSessionOptions
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.sharing.Contribution
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Section 33.4: every case of `vectors40/no_wait_40.json` that names android, through the public Client with the REAL
 * Core and the two-context test build ([TwoContextKit]), the platform's connection set to the case's. With the data
 * saver on, Android's report is `metered` and SDK 4.0.2 asks the platform nothing else (it has no such question). The
 * network condition of every request and job is none by construction: a model request carries none, and the platform's
 * job is asked for with no network rule ([BackgroundDownloads.owe]; Android's WorkManager work is
 * `NetworkType.CONNECTED`). `requests: true` is shown by the requests the stand-in servers received; `null` (no network
 * at all) by servers that answer nothing.
 */
@Suppress("UNCHECKED_CAST")
class NoWait40VectorsTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val v: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors40/no_wait_40.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    @Before
    fun need() = assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)

    private class Job : BackgroundDownloads {
        val owed = java.util.concurrent.atomic.AtomicInteger()
        override fun owe() { owed.incrementAndGet() }
        override fun settled() {}
    }

    @Test
    fun theVectorsContexts() {
        assertEquals(mapOf("default" to "c16_l64", "other" to "c24_l256"), v["contexts"])
        assertEquals(listOf("unmetered", "metered", "none", "unknown"), v["connections"])
        assertEquals(TajweedClient.CONNECTIONS, v["connections"])
    }

    @Test
    fun everyAndroidCase() {
        val cases = (v["cases"] as List<Map<String, Any?>>).filter { "android" in (it["platforms"] as List<*>) }
        assertEquals(12, cases.size)
        // every case runs; the failures are listed together at the end
        val failed = ArrayList<String>()
        for (c in cases) {
            assertEquals("${c["name"]}", "none", c["network_condition"])
            try {
                TwoContextKit().use { kit -> runBlocking { withTimeout(120_000) { replay(kit, c) } } }
            } catch (e: Throwable) {
                failed += "${c["name"]}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        assertEquals(emptyList<String>(), failed)
    }

    private suspend fun replay(kit: TwoContextKit, c: Map<String, Any?>) {
        val name = c["name"] as String
        val onDevice = c["on_device"] as Map<String, Any?>
        val clients = ArrayList<TajweedClient>()
        fun client(soc: String? = null, downloads: BackgroundDownloads? = null): TajweedClient {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
            return kit.client(soc = soc, downloads = downloads).also { clients += it }
        }
        fun asked(p: String) = kit.served.requests.filter { it.path == p }
        fun whole(p: String) = asked(p).let { r -> r.isNotEmpty() && r.all { it.status == 200 || it.status == 206 } }
        val cpu16 = kit.manifest.files.filter { it.context == "*" && it.role in setOf("tokens", "calibration", "weights", "frontend") }.map { it.path } +
            kit.contextFiles("c16_l64")
        try {
            // what is on the device, made on an unmetered network before the case (the lease of the install with it)
            if (onDevice["lease_accel"] == true) kit.leaseFlags = 8
            if (onDevice["c16_l64"] == "complete") client().prefetch()
            val newer = if (onDevice["newer_build_offered"] == true) kit.newerBuild().also { kit.offer(it) } else null
            if (onDevice["queued_401"] == "unmetered_only") {
                val weights = kit.manifest.files.single { it.role == "weights" }.path
                kit.served.cut = { p -> if (p == weights) 100L else null }
                try { client(downloads = Job()).prefetch() } catch (_: TajweedError) {}
                File(File(kit.storage, "models"), "download.json").writeText(Json.write(linkedMapOf("unmetered_only" to true, "version" to null)))
                kit.served.cut = null
            }
            kit.served.requests.clear()
            kit.connection = c["connection"] as String
            if (c["requests"] == null) {
                // no network at all: nothing answers
                kit.served.down = true
                kit.stub.down = true
            }
            val outcome: Any? = try {
                when (c["call"]) {
                    "model" -> client().model().also { m -> m.close() }
                    "prefetch" -> client().prefetch()
                    "session" -> client().model().let { m ->
                        try { m.session(TajweedSessionOptions(context = "c24_l256")).cancel() } finally { m.close() }
                    }
                    "update" -> client().model().also { m ->
                        assertEquals(name, newer, m.build)
                        m.close()
                    }
                    "accelerator" -> client(soc = "tensor_g4").model().also { m ->
                        val until = System.currentTimeMillis() + 20_000
                        while (asked(kit.program).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
                        m.close()
                    }
                    "upload" -> upload(kit, client())
                    "start" -> {
                        val job = Job()
                        val cl = client(downloads = job)
                        assertEquals("$name: the job given again at the start", 1, job.owed.get())
                        assertEquals(name, Continued.DONE, cl.continueDownload())
                    }
                    else -> throw AssertionError("$name: call ${c["call"]}")
                }
            } catch (e: TajweedError) {
                e
            }
            when (c["outcome"]) {
                "error" -> {
                    val e = outcome as? TajweedError ?: throw AssertionError("$name: no error")
                    assertEquals(name, c["code"], e.code)
                    assertEquals(name, c["needs"], e.errorCause?.needs)
                    assertTrue("$name: a request was answered: ${kit.paths()}", kit.served.requests.none { it.status in 200..299 })
                }
                else -> {
                    assertTrue("$name: ${(outcome as? TajweedError)?.let { "${it.code} ${it.errorCause}" }}", outcome !is TajweedError)
                    when (c["call"]) {
                        "model", "prefetch" -> for (p in cpu16) assertTrue("$name: $p: ${kit.paths()}", whole(p))
                        "session" -> for (p in kit.contextFiles("c24_l256")) assertTrue("$name: $p: ${kit.paths()}", whole(p))
                        "update" -> for (p in cpu16) assertTrue("$name: $p of the newer build: ${kit.paths()}", whole("$newer/$p"))
                        "accelerator" -> assertTrue("$name: the program: ${kit.paths()}", whole(kit.program))
                        "start" -> assertTrue("$name: the weights: ${kit.paths()}", whole(kit.manifest.files.single { it.role == "weights" }.path))
                        "upload" -> {}
                    }
                }
            }
        } finally {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    /** `sharing_upload_metered`: a finished shared recording (Ogg FLAC at 16 kHz, no Opus library needed) goes up. */
    private fun upload(kit: TwoContextKit, client: TajweedClient) {
        kit.stub.flags = kit.stub.flags + mapOf("share_flac" to true, "share_join_ms" to 0)
        val cloud = client.cloud
        cloud.refreshConfig()
        if (!cloud.sharing.isOn) cloud.sharing.accept()
        cloud.onSessionStart(TajweedObserver.SessionStart("4.1.0", "c16_l64", "cpu", sampleRate = 16_000, inputRoute = "built_in", decodeBackend = "mock"))
        val x = ai.quranlab.tajweedformer.sharing.OggTestKit.noise(16_000 * 12, seed = 7)
        var at = 0
        while (at < x.size) {
            val k = minOf(320, x.size - at)
            cloud.onAudio(x, at, k, 16_000)
            at += k
        }
        cloud.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true, speechFrames = 250))
        // the join window passes: the recording closes
        kit.stub.skewMs += 61_000
        cloud.sharing.checkJoinWindow()
        cloud.sharing.lastCapture?.await(10_000)
        repeat(40) {
            cloud.sharing.outbox.drain(ignoringBackoff = true)
            if (cloud.sharing.items.isNotEmpty() && cloud.sharing.items.all { it.status == Contribution.Status.UPLOADED }) return@repeat
            Thread.sleep(25)
        }
        val item = cloud.sharing.items.single()
        assertEquals("the recording on mobile data: ${item.lastError}", Contribution.Status.UPLOADED, item.status)
        assertEquals(1, kit.stub.starts().size)
        assertTrue(kit.stub.segments(item.id).isNotEmpty())
    }
}
