package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 3.1.3: the model's large file in ranges, through the public Client (the API stub and a test CDN that answers
 * closed ranges). What 3.1.0 made true of every model request holds for every range request: the key and the install's
 * token (SPEC 15.4), the token renewed on 401 `invalid_token` (once for the whole download), a refusal with a stop
 * reason on any range stops the whole download and removes the model (15.3), the daily limit is not retried, the
 * install record says `downloading` before the first request (15.2). And a download that was cut continues at the next
 * launch from the ranges that are complete.
 *
 * Uses only what 3.1.1 already had, so it runs on 3.1.1's code too (where the weights file is one request).
 */
class RangedClientTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()
    private val served = ArrayList<ServedModel>()
    private var client: TajweedClient? = null

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

    private fun storage(): File = Files.createTempDirectory("tj-rcl-").toFile().also { dirs += it }

    private fun build(): ServedModel = ServedModel.of(files).also { served += it }

    private class Keys : InstallKeyStore {
        private val real = MachineInstallKey(machineId = { "this-device" }, user = { "learner" })
        override fun mac(message: ByteArray): ByteArray? = real.mac(message)
        override fun create() {}
    }

    /** [retry]: three resumes 300 ms apart unless a test asks for the rule as shipped (`RetryPlan.DEFAULT`). */
    private fun launch(s: ServedModel, dir: File, retry: ai.quranlab.tajweedformer.RetryPlan = ai.quranlab.tajweedformer.RetryPlan(longArrayOf(300, 300, 300))): TajweedClient {
        client?.close()
        client = null
        stub.recommended = s.recommended()
        return TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, s, Keys(), stubPlay(stub), retry = retry), autoLaunch = false).also { client = it }
    }

    private suspend fun error(body: suspend () -> Unit): TajweedError? = try {
        body()
        null
    } catch (e: TajweedError) {
        e
    }

    private fun buildDir(dir: File, s: ServedModel) = File(File(File(dir, "models"), "v4.0"), s.build)

    private fun recordState(dir: File, s: ServedModel): Any? =
        try { (Json.parse(File(File(File(dir, "models"), "v4.0"), s.build + ".install").readText()) as Map<*, *>)["state"] } catch (_: Exception) { null }

    private fun modelFiles(dir: File): List<String> =
        File(dir, "models").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun big(s: ServedModel) = synchronized(s.requests) { s.requests.filter { it.path == BIG } }

    private fun range(k: Int, size: Long) = "bytes=${k * R}-${minOf(size, (k + 1) * R) - 1}"

    private fun start(r: String?) = r?.substringAfter("=")?.substringBefore("-")?.toLongOrNull()

    private val size: Long by lazy { files.first { it.path == "layers.zfw" }.stored.size.toLong() }

    @Test
    fun everyRangeRequestCarriesTheKeyAndTheInstallsToken() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val seen = java.util.Collections.synchronizedList(ArrayList<Any?>())
            s.onRequest = { path -> if (path == BIG) seen += recordState(dir, s) }
            val model = launch(s, dir).model()
            val asked = big(s)
            assertEquals("the weights file in 8 MiB ranges: $asked", (0 until 5).map { range(it, size) }.toSet(), asked.map { it.range }.toSet())
            for (r in asked) {
                assertEquals("${r.range}: the key", stub.key, r.headers["x-qlab-key"])
                val auth = r.headers["authorization"]
                assertTrue("${r.range}: no token ($auth)", auth != null && auth.startsWith("Bearer ") && stub.issued(auth.removePrefix("Bearer ")))
                assertEquals("${r.range}: accept-encoding", "identity", r.headers["accept-encoding"])
                assertEquals(206, r.status)
            }
            assertEquals("If-Range on every range after the first: $asked", 4, asked.count { it.ifRange != null })
            assertTrue("the record when a range was asked for: $seen", seen.isNotEmpty() && seen.all { it == "downloading" })
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun aRefusedTokenOnRangesIsRenewedOnceForTheWholeDownload() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            // the first answer to the second and to the third range: the token has expired
            val expired = """{"error":"invalid_token","message":"Renew the token."}"""
            s.rangeAnswer = { path, r, n -> if (path == BIG && n == 1 && (r == range(1, size) || r == range(2, size))) ServedModel.Answer(401, expired) else null }
            val model = launch(s, storage()).model()
            for (k in 1..2) {
                val asked = big(s).filter { it.range == range(k, size) || (start(it.range) ?: -1) in (k * R + 1) until (k + 1) * R }
                assertEquals("range $k: one refusal, then the range: $asked", listOf(401, 206), asked.map { it.status })
                assertNotEquals("range $k was repeated with the refused token", asked[0].headers["authorization"], asked[1].headers["authorization"])
            }
            val tokens = synchronized(s.requests) { s.requests.mapNotNull { it.headers["authorization"] }.toSet() }
            assertEquals("tokens used for the model: one before and one after ONE renewal: $tokens", 2, tokens.size)
            model.close()
        }
    }

    @Test
    fun aStopReasonOnALateRangeStopsTheDownloadAndRemovesTheModel() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val fourth = range(3, size)
            s.rangeAnswer = { path, r, _ ->
                if (path == BIG && r == fourth) ServedModel.Answer(401, """{"error":"invalid_key","message":"missing or invalid x-qlab-key","reason":"key_revoked"}""") else null
            }
            val e = error { launch(s, dir).model() }
            assertEquals("invalid_key", e?.code)
            assertEquals("a model file, a part or a record is left when the call returns", emptyList<String>(), modelFiles(dir))
            assertTrue("the refusal is remembered", File(File(dir, "cloud"), "refused.json").isFile)
            // nothing more of the model is asked for once the call has returned
            val n = s.requests.size
            Thread.sleep(500)
            assertEquals("requests after the call returned", n, s.requests.size)
        }
    }

    @Test
    fun aDownloadThatWasCutContinuesAtTheNextLaunchFromTheCompleteRanges() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            // the second range drops after 1 MiB every time it is asked for
            s.rangeCut = { path, r, _ -> if (path == BIG && (start(r) ?: -1) in R until 2 * R) 1L shl 20 else null }
            val e = error { launch(s, dir).model() }
            assertEquals("download_failed", e?.code)
            assertEquals("downloading", recordState(dir, s))
            assertTrue(File(buildDir(dir, s), "$BIG.part").isFile)
            s.rangeCut = null
            s.requests.clear()
            val model = launch(s, dir).model()
            assertEquals("only the range that was not complete, whole: ${big(s)}", listOf(range(1, size)), big(s).map { it.range })
            assertEquals(206, big(s).single().status)
            assertEquals("installed", recordState(dir, s))
            assertFalse(File(buildDir(dir, s), "$BIG.part").exists())
            model.close()
        }
    }

    @Test
    fun theDailyLimitOnARangeIsNotRetriedInALoop() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val third = range(2, size)
            s.rangeAnswer = { path, r, _ ->
                if (path == BIG && r == third) ServedModel.Answer(429, """{"error":"model_limit","message":"The daily limit is reached.","scope":"install"}""", mapOf("Retry-After" to "3600")) else null
            }
            val e = error { launch(s, storage()).model() }
            assertEquals("download_failed", e?.code)
            assertEquals("The model download stopped: Quran Lab's daily limit for model downloads is reached; try again later.", e?.message)
            assertEquals("the refused range was asked for once: ${big(s)}", 1, big(s).count { it.range == third })
        }
    }

    // ---- SDK 3.1.3 ----

    @Test
    fun aRangeRequestIsNeverRedirected() = runBlocking {
        withTimeout(120_000) {
            for (code in listOf(302, 307)) {
                val s = build()
                val elsewhere = build()
                // every range request for the weights is answered with a redirect to a second server
                s.rangeAnswer = { path, r, _ ->
                    if (path == BIG && r != null) ServedModel.Answer(code, "", mapOf("Location" to "${elsewhere.url}/${ServedModel.REPO}/${elsewhere.build}/${ServedModel.FOLDER}/$path")) else null
                }
                val e = error { launch(s, storage()).model() }
                assertEquals("$code to a range request", "download_failed", e?.code)
                assertEquals("$code: the key and the token were taken to another address", emptyList<String>(), elsewhere.paths())
                assertEquals("$code: the redirected range was asked for once: ${big(s)}", 1, big(s).size)
            }
        }
    }

    /**
     * An answer to a range with [status] whose body is the JSON of a refusal with a stop reason is not a stop (SPEC
     * 15.3, 15.4): the call fails as a download that failed, nothing is remembered, the part and its map stay, and the
     * next call opens the model. The range is asked for [asked] times.
     */
    private suspend fun notAStopOnARange(status: Int, asked: Int, headers: (String) -> Map<String, String> = { emptyMap() }) {
        val stop = """{"error":"invalid_key","message":"This key is not valid.","reason":"key_revoked"}"""
        val s = build()
        val dir = storage()
        val third = range(2, size)
        s.rangeAnswer = { path, r, _ -> if (path == BIG && r == third) ServedModel.Answer(status, stop, headers(path)) else null }
        val e = error { launch(s, dir).model() }
        assertEquals("$status to a range with a stop reason in its body", "download_failed", e?.code)
        assertEquals("$status: how often the range was asked for: ${big(s)}", asked, big(s).count { it.range == third })
        assertFalse("$status to a range was remembered as a refusal", File(File(dir, "cloud"), "refused.json").exists())
        val left = modelFiles(dir)
        assertTrue("$status to a range removed the part or its map: $left", left.any { it.endsWith("$BIG.part") } && left.any { it.endsWith("$BIG.part.ranges") })
        // nothing stops the next call: it needs the ranges that were not complete, and the model opens
        s.rangeAnswer = null
        assertNull("$status to a range: the next call did not open the model", error { launch(s, dir).model().close() })
    }

    /** A redirect to a range is not a stop of the model either, whatever its body says (SPEC 15.4). */
    @Test
    fun aRedirectedRangeWithAStopReasonInItsBodyIsNeverARefusal() = runBlocking {
        withTimeout(120_000) {
            for (code in listOf(302, 307)) {
                val elsewhere = build()
                notAStopOnARange(code, asked = 1) { path -> mapOf("Location" to "${elsewhere.url}/${ServedModel.REPO}/${elsewhere.build}/${ServedModel.FOLDER}/$path") }
                assertEquals("$code: the key and the token were taken to another address", emptyList<String>(), elsewhere.paths())
            }
        }
    }

    /**
     * SPEC 15.3: a stop is read only from a 401 or a 403. The same body on a range under 404 and 429 is a failed
     * download, asked once; under 500 and 503 a server failure, asked again by the retry rule (here three resumes);
     * under 403 it is the stop (the control; 401 is aStopReasonOnALateRangeStopsTheDownloadAndRemovesTheModel).
     */
    @Test
    fun aStopOnARangeIsReadOnlyFromA401OrA403() = runBlocking {
        withTimeout(120_000) {
            notAStopOnARange(404, asked = 1)
            notAStopOnARange(429, asked = 1)
            notAStopOnARange(500, asked = 4)
            notAStopOnARange(503, asked = 4)
            val s = build()
            val dir = storage()
            val third = range(2, size)
            s.rangeAnswer = { path, r, _ ->
                if (path == BIG && r == third) ServedModel.Answer(403, """{"error":"app_blocked","message":"This app is not served.","reason":"app_blocked"}""") else null
            }
            assertEquals("server_rejected", error { launch(s, dir).model() }?.code)
            assertTrue("a 403 with a stop reason was not remembered", File(File(dir, "cloud"), "refused.json").isFile)
            assertEquals("a model file, a part or a record is left when the call returns", emptyList<String>(), modelFiles(dir))
        }
    }

    @Test
    fun aDownloadThatIsCutGoesOnInsideTheCallByTheRuleAsShipped() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            // the second range drops after 1 MiB the first time it is asked for
            s.rangeCut = { path, r, n -> if (path == BIG && r == range(1, size) && n == 1) 1L shl 20 else null }
            val t0 = System.nanoTime()
            val model = launch(s, dir, ai.quranlab.tajweedformer.RetryPlan.DEFAULT).model()
            val took = (System.nanoTime() - t0) / 1_000_000
            val rest = big(s).filter { (start(it.range) ?: -1) in (R + 1) until 2 * R }
            assertEquals("the rest of the range that was cut, asked for once in the same call: ${big(s)}", 1, rest.size)
            assertTrue("from the byte where it stopped to the range's end: ${rest[0].range}", rest[0].range == "bytes=${R + (1L shl 20)}-${2 * R - 1}")
            assertTrue("with the validator", rest[0].ifRange != null)
            assertEquals(206, rest[0].status)
            assertTrue("the first pause of the rule is 1 s: the call took $took ms", took >= 1_000)
            assertEquals("installed", recordState(dir, s))
            assertFalse(File(buildDir(dir, s), "$BIG.part").exists())
            model.close()
        }
    }

    /**
     * SPEC 15.3: a stop leaves no model file and no install record when the call returns. Here the removal of the
     * model's files takes 6.5 s (a slow storage), longer than the 5 s the call waits for the other ranges' streams:
     * the call fails with the refusal, and only when the removal is done. Before the fix the call went on after
     * those 5 s as a download that failed, with the removal still under way.
     */
    @Test
    fun aStopOnARangeLeavesNothingWhenTheCallReturnsHoweverLongTheRemovalTakes() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val fourth = range(3, size)
            val refused = java.util.concurrent.atomic.AtomicBoolean(false)
            s.rangeAnswer = { path, r, _ ->
                if (path == BIG && r == fourth) {
                    refused.set(true)
                    ServedModel.Answer(401, """{"error":"invalid_key","message":"missing or invalid x-qlab-key","reason":"key_revoked"}""")
                } else null
            }
            // the first removal after the refusal is slow
            val slow: (File) -> Boolean = { f ->
                if (refused.compareAndSet(true, false)) Thread.sleep(6_500)
                f.deleteRecursively()
            }
            client?.close()
            client = null
            stub.recommended = s.recommended()
            val c = TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, s, Keys(), stubPlay(stub), deleteTree = slow), autoLaunch = false).also { client = it }
            val t = System.nanoTime()
            val e = error { c.model() }
            val took = (System.nanoTime() - t) / 1_000_000
            assertEquals("the call's error after $took ms", "invalid_key", e?.code)
            assertEquals("a model file, a part or a record is left when the call returns (after $took ms)", emptyList<String>(), modelFiles(dir))
            assertTrue("the refusal is remembered", File(File(dir, "cloud"), "refused.json").isFile)
        }
    }

    @Test
    fun closingTheClientEndsACallThatIsDownloadingAndKeepsWhatWasStored() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val c = launch(s, dir)
            // the last range's answer does not come for 6 s; the four before it are through long before
            val last = range(4, size)
            s.rangeAnswer = { path, r, _ ->
                if (path == BIG && r == last) Thread.sleep(6_000)
                null
            }
            val call = async(Dispatchers.Default) { runCatching { c.prefetch() }.exceptionOrNull() }
            val part = File(buildDir(dir, s), "$BIG.part")
            val map = File(buildDir(dir, s), "$BIG.part.ranges")
            val validator = File(buildDir(dir, s), "$BIG.part.v")
            fun done(): List<Int> = try { ((Json.parse(map.readText()) as Map<*, *>)["done"] as List<*>).map { (it as Double).toInt() } } catch (_: Exception) { emptyList() }
            while (done().size < 4) delay(20)
            val t0 = System.nanoTime()
            c.close()
            val e = call.await()
            val took = (System.nanoTime() - t0) / 1_000_000
            assertEquals("the call that was downloading when the Client was closed", "download_failed", (e as? TajweedError)?.code)
            assertTrue("the call returned $took ms after close() (the server's answer was 6 s away)", took < 2_000)
            // what was stored stays: the part, the complete ranges in their map, the validator, the install record
            assertTrue("the part is gone", part.isFile)
            assertEquals("the map of the complete ranges", listOf(0, 1, 2, 3), done())
            assertTrue("the validator is gone", validator.isFile)
            assertEquals("downloading", recordState(dir, s))
            // the next Client goes on from it: the one range that was missing
            s.rangeAnswer = null
            s.requests.clear()
            val model = launch(s, dir).model()
            assertEquals("only the range that was not complete: ${big(s)}", listOf(last), big(s).map { it.range })
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun cancellingOneCallerEndsThatCallersWaitAndNothingElse() = runBlocking {
        withTimeout(120_000) {
            // every range's answer starts a little late, so that the download lasts while the callers are arranged
            fun slow(s: ServedModel): java.util.concurrent.atomic.AtomicBoolean {
                val underWay = java.util.concurrent.atomic.AtomicBoolean()
                s.onRequest = { path -> if (path == BIG) { underWay.set(true); Thread.sleep(150) } }
                return underWay
            }
            // 1: the caller that waits for the same install is cancelled; the caller that downloads is not touched
            run {
                val s = build()
                val dir = storage()
                val c = launch(s, dir)
                val underWay = slow(s)
                val downloads = async(Dispatchers.Default) { c.prefetch() }
                while (!underWay.get()) delay(5)
                val waits = async(Dispatchers.Default) { c.model() }
                delay(100)
                waits.cancel()
                assertTrue("the cancelled caller's call did not end as cancelled", runCatching { waits.await() }.exceptionOrNull() is CancellationException)
                downloads.await()
                assertEquals("the download of the caller that was not cancelled", "installed", recordState(dir, s))
            }
            // 2: the caller that downloads is cancelled; the caller that waits is not: it goes on from what is stored and
            // gets the model
            run {
                val s = build()
                val dir = storage()
                val c = launch(s, dir)
                val underWay = slow(s)
                val downloads = async(Dispatchers.Default) { c.prefetch() }
                while (!underWay.get()) delay(5)
                val waits = async(Dispatchers.Default) { c.model() }
                delay(100)
                downloads.cancel()
                assertTrue(runCatching { downloads.await() }.exceptionOrNull() is CancellationException)
                val model = waits.await()
                assertEquals("installed", recordState(dir, s))
                assertEquals(s.build, model.build)
                model.close()
            }
        }
    }

    @Test
    fun theDiagnoseReportNamesTheProtocolOfTheLastModelRequest() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val c = launch(s, dir)
            val before = c.diagnose()
            assertNull("no model request was made in this process yet", before.downloadProtocol)
            assertTrue("the report has the field also then", before.toMap().containsKey("downloadProtocol"))
            val model = c.model()
            val after = c.diagnose()
            assertEquals("HttpURLConnection speaks HTTP/1.1 and nothing else", "http/1.1", after.downloadProtocol)
            assertEquals("http/1.1", (Json.parse(after.toJson()) as Map<*, *>)["downloadProtocol"])
            // the values are the contract's (names.json), not this test's
            @Suppress("UNCHECKED_CAST")
            val names = (Json.parse(javaClass.getResourceAsStream("/vectors30/names.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>)["diagnosis"] as Map<String, Any?>
            assertTrue("${after.downloadProtocol} is not a value the contract names", after.downloadProtocol in (names["downloadProtocol"] as List<*>))
            // in memory only: no file of the SDK's folder holds the field
            c.cloud.telemetry.flushToDisk()
            val holders = dir.walkTopDown().filter { it.isFile && it.length() < 1_000_000 && it.readText(Charsets.ISO_8859_1).contains("downloadProtocol") }.map { it.name }.toList()
            assertEquals("the protocol of the diagnose report was written to the device", emptyList<String>(), holders)
            model.close()
        }
    }

    companion object {
        const val BIG = "layers.zfw.gz"
        const val R = 8L shl 20

        /** The test kit's model with a 38 MiB weights file: five 8 MiB ranges. */
        private val files: List<TestKit.F> by lazy {
            TestKit.v4Files().map { f ->
                if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(38 shl 20).also { java.util.Random(11).nextBytes(it) }, "weights") else f
            }
        }
    }
}
