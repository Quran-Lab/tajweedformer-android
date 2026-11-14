package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.InstallKeyUnavailable
import ai.quranlab.tajweedformer.InstallRecords
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 3.1.0 (SPEC section 15): a model comes only from Quran Lab's server, through the Client. Everything here goes
 * through the public Client against the suite's stub of the API and a small build behind a local server that stands
 * in for the CDN. Each test fails on the released 3.0.1 code.
 *
 * - A: no public way is left to hand the SDK a model folder.
 * - B: the Client opens only a build it downloaded itself (the install record). A storage filled in advance exactly
 *   as a real download leaves it is not opened and is replaced by a download; so is one with a record of another
 *   device, or of another build. A real download and a relaunch open with no request for a file. An interrupted
 *   download resumes. The record is written before the first request. The key store's two ways of failing. An install
 *   that updates from an earlier SDK without a connection.
 * - C: a Client the server has refused does not run the model, and removes it: every case of the shared
 *   `vectors30/model_gate.json`, with a model from a real download on the device and without one;
 *   a stop leaves no model file and no record (and nothing else goes), an answer that is not a stop removes nothing;
 *   a relaunch without a network stays stopped; an accepting answer clears the stop and the model is downloaded
 *   again; a removal that fails is owed to the next start; a refusal of a model request is not cleared by an API
 *   answer.
 * - D: a model request carries the key and the install's token; the token is renewed once on 401 `invalid_token`; a
 *   model request is never redirected; the token is attested only where the signed config gives the Play Integrity
 *   project number, and unattested where Play cannot answer.
 */
class ModelFromServerOnlyTest : OpenChecked() {
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

    private fun storage(): File = Files.createTempDirectory("tj-gate-").toFile().also { dirs += it }

    private fun build(files: List<TestKit.F> = TestKit.v4Files()): ServedModel = ServedModel.of(files).also { served += it }

    /** A key store a test can make fail, or leave without a key until one is made. */
    private class Keys(var hasKey: Boolean = true, var fails: Boolean = false, private val device: String = "this-device") : InstallKeyStore {
        var created = 0
        private val real = MachineInstallKey(machineId = { device }, user = { "learner" })
        override fun mac(message: ByteArray): ByteArray? {
            if (fails) throw InstallKeyUnavailable("the key store is locked")
            return if (hasKey) real.mac(message) else null
        }
        override fun create() {
            if (fails) throw InstallKeyUnavailable("the key store is locked")
            created++
            hasKey = true
        }
    }

    /** A launch of the app: a new Client on [dir] (the one before is closed). */
    private fun launch(s: ServedModel, dir: File, keys: InstallKeyStore = Keys(), integrity: IntegrityProvider? = stubPlay(stub),
                       deleteTree: ((File) -> Boolean)? = null): TajweedClient {
        client?.close()
        client = null
        stub.recommended = s.recommended()
        return TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, s, keys, integrity, deleteTree), autoLaunch = false).also { client = it }
    }

    /** Every file the SDK's storage holds under `models/` (builds, records, parts, the pointer file). */
    private fun modelFiles(dir: File): List<String> =
        File(dir, "models").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun modelsOf(dir: File) = File(File(dir, "models"), "v4.0")

    private fun buildDir(dir: File, s: ServedModel) = File(modelsOf(dir), s.build)

    private fun recordFile(dir: File, s: ServedModel) = File(modelsOf(dir), s.build + ".install")

    private fun recordState(dir: File, s: ServedModel): Any? = try { (Json.parse(recordFile(dir, s).readText()) as Map<*, *>)["state"] } catch (_: Exception) { null }

    private suspend fun error(body: suspend () -> Unit): TajweedError? = try {
        body()
        null
    } catch (e: TajweedError) {
        e
    }

    /** A real download of [s] into [dir], the Client closed afterwards: what an install holds after its first use. */
    private suspend fun realDownload(s: ServedModel, dir: File, keys: InstallKeyStore = Keys()) {
        launch(s, dir, keys).model().close()
        client?.close()
        client = null
    }

    /** The files asked for with a whole answer (200), without the manifest. */
    private fun filesServed(s: ServedModel) = s.requests.filter { it.status == 200 && it.path != "manifest.json" }.map { it.path }.toSet()

    // ---- A: no public door ----

    @Test
    fun noPublicCallOpensAModelFolder() {
        val companion = TajweedModel::class.java.declaredClasses.firstOrNull { it.simpleName == "Companion" }
        val open = (TajweedModel::class.java.methods.toList() + (companion?.methods?.toList() ?: emptyList()))
            .filter { it.name == "open" || it.name.startsWith("open$") }
        assertEquals("TajweedModel still has a call that opens a folder", emptyList<String>(), open.map { it.toGenericString() })
        // none of the public types takes a folder, a URL, a reader, bytes, a key or a transport for a model: the API page
        // lists every public declaration (PublicContractTest), and the README tells nobody to open a folder
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val api = File(root, "docs/api.md").readText()
        assertFalse("docs/api.md still names an open call", Regex("fun open\\(").containsMatchIn(api))
        for (word in listOf("baseUrl", "modelsUrl", "trustedKeys", "ManifestVerifier", "HttpTransport", "HeaderProvider", "ModelFolder", "ModelStore")) {
            assertFalse("the public API names $word", word in api)
        }
        val readme = File(root, "README.md").readText()
        assertFalse("README.md still tells a developer to open a model folder", "TajweedModel.open" in readme)
        assertFalse("README.md still names the manifest meta-data for Play Integrity", "PLAY_INTEGRITY_PROJECT" in readme)
    }

    // ---- B: the install record ----

    @Test
    fun aStorageFilledInAdvanceIsNotOpenedAndIsReplacedByADownload() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val first = storage()
            realDownload(s, first)
            val firstFiles = filesServed(s)
            // another install's storage, filled in advance with the files and pointer files exactly as a real download
            // leaves them (what an app that ships the model copies into place): everything but the install's record
            val other = storage()
            File(first, "models").copyRecursively(File(other, "models"))
            File(other, "models").walkTopDown().filter { it.name.endsWith(InstallRecords.SUFFIX) }.toList().forEach { it.delete() }
            val planted = File(buildDir(other, s), "planted-by-the-app.txt").apply { writeText("x") }
            assertTrue(File(buildDir(other, s), "manifest.json").isFile)
            s.requests.clear()

            val model = launch(s, other).model()
            assertFalse("the folder that was filled in advance was kept", planted.exists())
            assertTrue("the manifest was not asked for again: ${s.paths()}", "manifest.json" in s.paths())
            assertTrue("a file of the first download was not downloaded again: ${s.paths()}", firstFiles.size >= 5 && filesServed(s).containsAll(firstFiles))
            assertTrue("a download was continued: ${s.requests}", s.requests.none { it.range != null })
            assertEquals("installed", recordState(other, s))
            model.close()
        }
    }

    @Test
    fun aRecordOfAnotherDeviceIsNotValid() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val first = storage()
            realDownload(s, first, Keys(device = "another-device"))
            assertEquals("installed", recordState(first, s))
            // the whole storage, record included, on this device
            val here = storage()
            File(first, "models").copyRecursively(File(here, "models"))
            val planted = File(buildDir(here, s), "planted-by-the-app.txt").apply { writeText("x") }
            s.requests.clear()

            val model = launch(s, here, Keys(device = "this-device")).model()
            assertFalse("a folder with another device's record was kept", planted.exists())
            assertTrue("nothing was downloaded: ${s.paths()}", "manifest.json" in s.paths() && filesServed(s).size >= 5)
            assertEquals("installed", recordState(here, s))
            model.close()
        }
    }

    @Test
    fun aRecordOfAnotherBuildIsNotValid() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            // a record this device made, for another build, put in this build's place
            val elsewhere = File(File(storage(), "models"), "v4.0")
            val otherBuild = "0123456789abcdef0123456789abcdef01234567"
            InstallRecords(keys).write(File(elsewhere, otherBuild), otherBuild, InstallRecords.State.INSTALLED)
            File(elsewhere, otherBuild + InstallRecords.SUFFIX).copyTo(recordFile(dir, s), overwrite = true)
            val planted = File(buildDir(dir, s), "planted-by-the-app.txt").apply { writeText("x") }
            s.requests.clear()

            val model = launch(s, dir, keys).model()
            assertFalse("a folder with another build's record was kept", planted.exists())
            assertTrue("nothing was downloaded: ${s.paths()}", "manifest.json" in s.paths() && filesServed(s).size >= 5)
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun aRealDownloadThenARelaunchOpensWithNoRequestForAFile() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            assertEquals("the record after a real download", "installed", recordState(dir, s))
            s.requests.clear()
            val model = launch(s, dir, keys).model()
            assertEquals("a relaunch asked the server for model files", emptyList<String>(), s.paths())
            model.close()
            // and without any network at all: a learner offline after a real download keeps working
            stub.down = true
            s.down = true
            val offline = launch(s, dir, keys).model()
            assertEquals(emptyList<String>(), s.paths())
            offline.close()
        }
    }

    @Test
    fun anInterruptedDownloadResumes() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            val big = "layers.zfw.gz"
            s.cut = { path -> if (path == big) 1000L else null }
            assertNotNull("a download that was cut did not fail", error { launch(s, dir, keys).model() })
            assertEquals("the record of a download that was cut", "downloading", recordState(dir, s))
            val part = File(buildDir(dir, s), "$big.part")
            assertTrue("the part of the file that was cut is not kept", part.isFile && part.length() in 1..1000)
            val have = part.length()
            // the next launch continues it
            s.cut = null
            s.requests.clear()
            val model = launch(s, dir, keys).model()
            val again = s.requests.filter { it.path == big }
            assertEquals("the file that was cut was asked for once more", 1, again.size)
            assertEquals("it was continued where it stopped", "bytes=$have-", again[0].range)
            assertEquals(206, again[0].status)
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun theRecordIsWrittenBeforeTheFirstFileIsAskedFor() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val seen = java.util.Collections.synchronizedList(ArrayList<Pair<String, Any?>>())
            s.onRequest = { path -> seen += path to recordState(dir, s) }
            val model = launch(s, dir).model()
            assertTrue(seen.isNotEmpty())
            assertEquals("the record when the first request for the model arrived (${seen.first().first})", "downloading", seen.first().second)
            assertTrue("the record during the download: $seen", seen.all { it.second == "downloading" })
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun theKeyStoreHasNoKey() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            realDownload(s, dir, Keys())
            val planted = File(buildDir(dir, s), "planted-by-the-app.txt").apply { writeText("x") }
            s.requests.clear()
            // the system's key store has no key (a restored backup, a cleared store): no record can be valid
            val keys = Keys(hasKey = false)
            val model = launch(s, dir, keys).model()
            assertEquals("a key was made", 1, keys.created)
            assertFalse("the folder whose record cannot be valid was kept", planted.exists())
            assertTrue("nothing was downloaded: ${s.paths()}", "manifest.json" in s.paths() && filesServed(s).size >= 5)
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    @Test
    fun theKeyStoreFails() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            val planted = File(buildDir(dir, s), "left-alone.txt").apply { writeText("x") }
            val before = buildDir(dir, s).walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList()
            s.requests.clear()
            // locked or unavailable: nothing is opened, nothing is deleted, nothing is downloaded
            keys.fails = true
            val c = launch(s, dir, keys)
            val e = error { c.model() }
            assertEquals("storage_failed", e?.code)
            assertEquals("a model request was made", emptyList<String>(), s.paths())
            assertEquals("something was deleted", before, buildDir(dir, s).walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList())
            assertEquals("storage_failed", error { c.prefetch() }?.code)
            assertTrue(planted.exists() && recordFile(dir, s).isFile)
            // the next call tries again
            keys.fails = false
            val model = c.model()
            assertEquals("the model that a real download left was downloaded again", emptyList<String>(), s.paths())
            model.close()
        }
    }

    @Test
    fun anInstallThatUpdatesFromAnEarlierSdkWithoutAConnection() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            realDownload(s, dir)
            // what 3.0 left: the model, the pointer file, the stored config, and no record
            recordFile(dir, s).delete()
            s.requests.clear()
            stub.down = true
            s.down = true
            val c = launch(s, dir)
            assertEquals("needs_connection", error { c.model() }?.code)
            assertFalse("the model without a record was kept", File(buildDir(dir, s), "manifest.json").exists())
            // with a connection it is downloaded once more
            stub.down = false
            s.down = false
            val model = launch(s, dir).model()
            assertTrue(filesServed(s).size >= 5)
            assertEquals("installed", recordState(dir, s))
            model.close()
        }
    }

    // ---- D: a model request carries the install's token ----

    @Test
    fun theHeadersOfEveryModelRequest() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            val big = "layers.zfw.gz"
            s.cut = { path -> if (path == big) 1000L else null }
            assertNotNull(error { launch(s, dir, keys).model() })
            s.cut = null
            val model = launch(s, dir, keys).model()
            fun check(what: String, r: ServedModel.Request) {
                assertEquals("$what: the key", stub.key, r.headers["x-qlab-key"])
                val auth = r.headers["authorization"]
                assertTrue("$what: no token (authorization: $auth)", auth != null && auth.startsWith("Bearer qlt_v1."))
                assertTrue("$what: the token is not one the server gave this install", stub.issued(auth!!.removePrefix("Bearer ")))
                assertEquals("$what: accept-encoding", "identity", r.headers["accept-encoding"])
            }
            check("the manifest request", s.requests.first { it.path == "manifest.json" })
            check("a file request", s.requests.first { it.path == "tokens.txt" })
            val range = s.requests.first { it.range != null }
            check("a Range request", range)
            assertEquals(big, range.path)
            for (r in s.requests) check("the request for ${r.path}", r)
            model.close()
        }
    }

    @Test
    fun aRefusedTokenIsRenewedOnceAndTheRequestRepeatedOnce() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            // the first request for every path is answered as an expired token is
            s.answer = { _, n -> if (n == 1) ServedModel.Answer(401, """{"error":"invalid_token","message":"Renew the token."}""") else null }
            val model = launch(s, storage()).model()
            val manifest = s.requests.filter { it.path == "manifest.json" }
            assertEquals("the manifest: one refusal, one repeat", listOf(401, 200), manifest.map { it.status })
            assertNotEquals("the repeat carries a new token", manifest[0].headers["authorization"], manifest[1].headers["authorization"])
            assertTrue(stub.issued(manifest[1].headers["authorization"]!!.removePrefix("Bearer ")))
            model.close()

            // a server that keeps refusing the token: one renewal, one repeat, and the download fails as any does
            val t = build(TestKit.v4Files(contexts = listOf("c16_l64")))
            t.answer = { _, _ -> ServedModel.Answer(401, """{"error":"invalid_token","message":"Renew the token."}""") }
            val e = error { launch(t, storage()).model() }
            assertEquals("download_failed", e?.code)
            assertEquals("requests for the manifest", 2, t.requests.count { it.path == "manifest.json" })
            assertEquals("nothing else was asked for", 2, t.requests.size)
        }
    }

    @Test
    fun aModelRequestIsNeverRedirected() = runBlocking {
        withTimeout(120_000) {
            // 302 and 307
            for (code in listOf(302, 307)) {
                val s = build()
                val elsewhere = build()
                s.answer = { path, _ -> ServedModel.Answer(code, "", mapOf("Location" to "${elsewhere.url}/${ServedModel.REPO}/${elsewhere.build}/${ServedModel.FOLDER}/$path")) }
                val e = error { launch(s, storage()).model() }
                assertNotNull("$code: a redirected request gave a model", e)
                assertEquals("$code: the key and the token were taken to another address", emptyList<String>(), elsewhere.paths())
                assertEquals("$code: the redirected request was asked once", listOf("manifest.json"), s.paths())
            }
        }
    }

    /** The JSON of a refusal with a stop reason, as a 401 carries it. */
    private val stopBody = """{"error":"invalid_key","message":"This key is not valid.","reason":"key_revoked"}"""

    private fun remembered(dir: File) = File(File(dir, "cloud"), "refused.json")

    /** The API's calls, by what they carry: the key, the token, a sharing start. Each on the Client's own API object,
     *  so every answer passes the place where a refusal of the API is remembered. */
    private fun apiCalls(c: TajweedClient): List<Pair<String, () -> Any?>> {
        val api = c.cloud.api
        val key = c.cloud.key!!
        val app = c.cloud.env.app
        return listOf(
            "attest (the key)" to { api.attest(key, app) },
            "the challenge (the key)" to { api.challenge(key) },
            "config (the token)" to { api.config(Credential.Token("qlt_v1.test"), 1, app, null) },
            "register (the token)" to { api.register(Credential.Token("qlt_v1.test"), app, "4.0.0", false) },
            "a telemetry send (the token)" to { api.events("qlt_v1.test", "{}".toByteArray()) },
            "a sharing start (the token)" to { api.uploadStart("qlt_v1.test", "0123456789abcdef", "{}".toByteArray()) },
        )
    }

    /**
     * An answer with [status] whose body is the JSON of a refusal with a stop reason is NOT a stop (SPEC 15.3: a stop is
     * read only from a 401 or a 403; 15.4: a redirect is a failed request whatever its body says): to the manifest, to
     * a file, to a request of an open Model, and to each call of the API. The call fails as a download that failed (an
     * API call as that call fails), no refusal is remembered, what the device holds of the model stays, and the next
     * call opens the model. [headers]: what else the answer carries (a redirect's `Location`).
     */
    private suspend fun notAStop(status: Int, headers: (String) -> Map<String, String> = { emptyMap() }) {
        fun answer(path: String) = ServedModel.Answer(status, stopBody, headers(path))

        // the manifest
        var s = build()
        var dir = storage()
        s.answer = { path, _ -> answer(path) }
        assertEquals("$status to the manifest", "download_failed", error { launch(s, dir).model() }?.code)
        assertEquals("$status: the manifest was asked once", listOf("manifest.json"), s.paths())
        assertFalse("$status to the manifest was remembered as a refusal", remembered(dir).exists())

        // a file, with the manifest and the record on the device
        s = build()
        dir = storage()
        s.answer = { path, _ -> if (path == "frontend.zfw.gz") answer(path) else null }
        assertEquals("$status to a file", "download_failed", error { launch(s, dir).model() }?.code)
        assertEquals("$status: the file was asked once: ${s.paths()}", 1, s.paths().count { it == "frontend.zfw.gz" })
        assertFalse("$status to a file was remembered as a refusal", remembered(dir).exists())
        assertTrue("$status to a file removed what the device held: ${modelFiles(dir)}", modelFiles(dir).isNotEmpty())
        // nothing stops the next call: the server answers as it should, and the model opens
        s.answer = null
        assertNull("$status to a file: the next call did not open the model", error { launch(s, dir).model().close() })

        // a request of an open Model, with the whole model on the device
        s = build()
        dir = storage()
        val keys = Keys()
        realDownload(s, dir, keys)
        val held = modelFiles(dir)
        s.answer = { path, _ -> answer(path) }
        var c = launch(s, dir, keys)
        val model = c.model()
        val e = error { model.reference!!.fetch() }
        assertTrue("$status to a request of an open Model: ${e?.code}", e != null && e.code != "invalid_key" && e.code != "server_rejected")
        model.close()
        assertFalse("$status to a request of an open Model was remembered as a refusal", remembered(dir).exists())
        assertTrue("$status removed files of the model: ${held - modelFiles(dir).toSet()}", modelFiles(dir).containsAll(held))

        // the API: each call is answered with the same status and body
        s.answer = null
        c = launch(s, dir, keys)
        stub.everything = { _ -> StubQlab.Reply(status, stopBody.toByteArray(), mapOf("content-type" to "application/json") + headers("v1")) }
        try {
            for ((name, call) in apiCalls(c)) {
                val outcome = try { (call() as? HttpResponse)?.status ?: "returned" } catch (x: CloudException) { "failed" }
                assertTrue("$status to $name was taken for a success: $outcome", outcome == "failed" || outcome == status)
                assertFalse("$status to $name was remembered as a refusal", remembered(dir).exists())
                assertTrue("$status to $name removed files of the model: ${held - modelFiles(dir).toSet()}", modelFiles(dir).containsAll(held))
            }
            assertFalse("$status: a registration was taken for an accepted one", c.cloud.register())
            assertFalse("$status to the registration was remembered as a refusal", remembered(dir).exists())
        } finally {
            stub.everything = null
        }
        // nothing stops the next launch: the model that is on the device opens
        assertNull("$status to the API's calls: the model on the device did not open at the next launch", error { launch(s, dir, keys).model().close() })
        assertTrue("$status: files of the model went: ${held - modelFiles(dir).toSet()}", modelFiles(dir).containsAll(held))
    }

    /** A redirect (302, 307) with a stop reason in its body, and the address in `Location` is never asked (SPEC 15.4). */
    @Test
    fun aRedirectWithAStopReasonInItsBodyIsNeverARefusal() = runBlocking {
        withTimeout(240_000) {
            for (code in listOf(302, 307)) {
                val elsewhere = build()
                notAStop(code) { path -> mapOf("Location" to "${elsewhere.url}/${ServedModel.REPO}/${elsewhere.build}/${ServedModel.FOLDER}/$path") }
                assertEquals("$code: the key and the token were taken to another address", emptyList<String>(), elsewhere.paths())
            }
        }
    }

    /**
     * SPEC 15.3: a stop is read only from an answer whose status is 401 or 403. The same body under 404, 429, 500 and
     * 503 is not a stop; under 401 and 403 it is one (the control: remembered, the model removed), for a model request
     * and for a call of the API.
     */
    @Test
    fun aStopIsReadOnlyFromA401OrA403() = runBlocking {
        withTimeout(240_000) {
            for (status in listOf(404, 429, 500, 503)) notAStop(status)
            for (status in listOf(401, 403)) {
                // a model request
                var s = build()
                var dir = storage()
                var keys = Keys()
                realDownload(s, dir, keys)
                s.answer = { _, _ -> ServedModel.Answer(status, stopBody) }
                var c = launch(s, dir, keys)
                val model = c.model()
                assertEquals("$status with a stop reason to a request of an open Model", "invalid_key", error { model.reference!!.fetch() }?.code)
                model.close()
                assertTrue("$status with a stop reason to a model request was not remembered", remembered(dir).isFile)
                assertEquals("$status with a stop reason to a model request left the model on the device", emptyList<String>(), modelFiles(dir))
                // a call of the API
                s = build()
                dir = storage()
                keys = Keys()
                realDownload(s, dir, keys)
                c = launch(s, dir, keys)
                stub.everything = { _ -> StubQlab.Reply(status, stopBody.toByteArray(), mapOf("content-type" to "application/json")) }
                try {
                    val attest = apiCalls(c).first()
                    try { attest.second() } catch (_: CloudException) {}
                    assertTrue("$status with a stop reason to ${attest.first} was not remembered", remembered(dir).isFile)
                    assertEquals("$status with a stop reason to ${attest.first} left the model on the device", emptyList<String>(), modelFiles(dir))
                } finally {
                    stub.everything = null
                }
            }
        }
    }

    @Test
    fun theDailyLimitIsNotRetriedInALoop() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            s.answer = { _, _ -> ServedModel.Answer(429, """{"error":"model_limit","message":"The daily limit is reached.","scope":"install"}""", mapOf("Retry-After" to "3600")) }
            val c = launch(s, storage())
            val e = error { c.model() }
            assertEquals("download_failed", e?.code)
            assertEquals("The model download stopped: Quran Lab's daily limit for model downloads is reached; try again later.", e?.message)
            assertEquals("one request, no loop", listOf("manifest.json"), s.paths())
        }
    }

    @Test
    fun aModelRequestCarriesAnAttestedTokenWhereTheConfigGivesTheProjectNumber() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val model = launch(s, storage()).model()
            val token = s.requests.first().headers["authorization"]?.removePrefix("Bearer ")
            assertTrue("the model request's token is not attested: $token", token != null && token in stub.attestedTokens)
            assertTrue("Play was asked with the config's project number: ${stub.integrityAsked}",
                stub.integrityAsked.isNotEmpty() && stub.integrityAsked.all { it == StubQlab.PROJECT_NUMBER.toLong() })
            model.close()
        }
    }

    @Test
    fun withoutTheProjectNumberPlayIsNeverAsked() = runBlocking {
        withTimeout(120_000) {
            stub.flags = stub.flags - "play_integrity_project_number"
            val s = build()
            val model = launch(s, storage()).model()
            assertEquals("Play was asked for an integrity token", emptyList<Long>(), stub.integrityAsked)
            assertEquals("a challenge for an attestation was asked for", 0, stub.seen("/v1/attest/challenge").size)
            val token = s.requests.first().headers["authorization"]?.removePrefix("Bearer ")
            assertTrue("no token, or not the stub's: $token", token != null && stub.issued(token) && token !in stub.attestedTokens)
            model.close()
        }
    }

    @Test
    fun wherePlayCannotAnswerTheTokenIsUnattested() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val model = launch(s, storage(), integrity = failingPlay()).model()
            val token = s.requests.first().headers["authorization"]?.removePrefix("Bearer ")
            assertTrue("no unattested token: $token", token != null && stub.issued(token) && token !in stub.attestedTokens)
            assertTrue(s.paths().size >= 6)
            model.close()
        }
    }

    // ---- C: a Client the server has refused does not run the model ----

    @Suppress("UNCHECKED_CAST")
    private val gate: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors30/model_gate.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    /** The error a case expects, with this SDK's version where the vector has its own. */
    private fun expected(e: Map<String, Any?>): Triple<String, String, String> {
        val sdk = gate["sdk"] as String
        fun mine(t: String) = t.replace("this is $sdk.", "this is ${SdkInfo.VERSION}.")
        return Triple(e["code"] as String, mine(e["message"] as String), e["fix"] as String)
    }

    private fun same(what: String, want: Map<String, Any?>, got: TajweedError?) {
        assertNotNull("$what: the call gave a model", got)
        val (code, message, fix) = expected(want)
        assertEquals("$what: code", code, got!!.code)
        assertEquals("$what: message", message, got.message)
        assertEquals("$what: fix", fix, got.fix)
    }

    /** Gives the stub the answer of [case]: the API's to every API request, or the CDN's to every model request. */
    @Suppress("UNCHECKED_CAST")
    private fun answerWith(case: Map<String, Any?>, s: ServedModel) {
        val status = (case["status"] as? Double)?.toInt()
        val body = case["body"] as? Map<String, Any?>
        when {
            case["from"] == "cdn" -> s.answer = { _, _ -> ServedModel.Answer(status!!, Json.write(body)) }
            status == null -> stub.down = true
            status == 200 -> stub.tamper = true
            else -> stub.everything = { _ -> StubQlab.Reply.json(status, body) }
        }
    }

    /** Both servers answer as usual. */
    private fun answerNormally(s: ServedModel) {
        s.answer = null
        s.down = false
        stub.down = false
        stub.tamper = false
        stub.everything = null
    }

    /** Neither server can be reached. */
    private fun noNetwork(s: ServedModel) {
        answerNormally(s)
        stub.down = true
        s.down = true
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun everyCaseOfTheModelGate() = runBlocking {
        withTimeout(600_000) {
            stub.appId = gate["app"] as String
            val cases = gate["cases"] as List<Map<String, Any?>>
            assertEquals(34, cases.size)
            val stops = gate["stop_reasons"] as List<String>
            assertEquals("the SDK's list is the vector's", stops.toSet(), ModelGate.STOP_REASONS)
            assertEquals((gate["key_reasons"] as List<String>).toSet(), ModelGate.KEY_REASONS)
            var stopping = 0
            for (case in cases) {
                val name = case["name"] as String
                val expect = case["expect"] as Map<String, Any?>
                val stopsModel = expect["stops_model"] as Boolean
                val removes = expect["removes_models"] as Boolean
                assertEquals("$name: a stop removes the models, and only a stop", stopsModel, removes)
                val want = expect["error"] as? Map<String, Any?>
                val cdn = case["from"] == "cdn"
                val reason = (case["body"] as? Map<String, Any?>)?.get("reason") as? String
                val status = (case["status"] as? Number)?.toInt()
                // SPEC 15.3: a reason of the list stops under a 401 or a 403 only
                assertEquals("$name: the rule (a reason of the list under a 401 or a 403, and only that, stops)",
                    (status == 401 || status == 403) && reason != null && reason in stops, stopsModel)
                assertEquals("$name: the SDK's rule", ModelGate.stops(status ?: 0, reason), stopsModel)
                if (stopsModel) stopping++
                val keys = Keys()

                // (a) without a model on the device
                run {
                    val s = build()
                    val dir = storage()
                    answerWith(case, s)
                    val c = launch(s, dir, keys)
                    val e = error { c.model() }
                    assertNotNull("$name, no model on the device: the call gave a model", e)
                    if (want != null) same("$name, no model on the device", want, e)
                    if (want != null) same("$name, no model on the device, prefetch", want, error { c.prefetch() })
                    answerNormally(s)
                }

                // (b) with a model from a real download on the device
                val s = build()
                val dir = storage()
                realDownload(s, dir, keys)
                val firstFiles = filesServed(s)
                val held = modelFiles(dir)
                assertTrue("$name: the real download left no model", held.any { it.endsWith(".install") } && held.size >= 6)
                answerWith(case, s)
                var c = launch(s, dir, keys)
                if (cdn && stopsModel) {
                    // the CDN refuses a model request while a Model is open: the open Model keeps running until the app
                    // closes it, the request fails with the refusal, and the next call gives no model
                    val model = c.model()
                    same("$name, a model request while a model is open", want!!, error { model.reference!!.fetch() })
                    assertEquals("$name: the call that got the refusal left model files", emptyList<String>(), modelFiles(dir))
                    assertTrue("$name: the open Model stopped running", model.contexts.isNotEmpty())
                    model.close()
                }
                val e = error { c.model().close() }
                if (removes) {
                    assertEquals("$name: the call that got the refusal left model files", emptyList<String>(), modelFiles(dir))
                } else {
                    assertEquals("$name: an answer that is not a stop removed a file", held, modelFiles(dir))
                }
                if (stopsModel) {
                    same("$name, a model on the device", want!!, e)
                    same("$name, a model on the device, prefetch", want, error { c.prefetch() })
                    // a relaunch without a network stays stopped
                    noNetwork(s)
                    c = launch(s, dir, keys)
                    same("$name, a relaunch without a network", want, error { c.model().close() })
                    // an accepting answer clears the stop, and the device holds no model: it is downloaded again
                    answerNormally(s)
                    s.requests.clear()
                    c = launch(s, dir, keys)
                    assertNull("$name: an accepting answer did not clear the stop", error { c.model().close() })
                    assertTrue("$name: not every file was asked for again: ${s.paths()}", filesServed(s).containsAll(firstFiles))
                } else {
                    assertNull("$name: a model that a real download left did not open (${e?.code}: ${e?.message})", e)
                    // and it still opens at the next launch without a network
                    noNetwork(s)
                    c = launch(s, dir, keys)
                    assertNull("$name: the model did not open at a relaunch without a network", error { c.model().close() })
                    answerNormally(s)
                }
                client?.close()
                client = null
            }
            assertEquals("cases that stop a model", 13, stopping)
        }
    }

    @Test
    fun aRefusalOfAModelRequestIsNotClearedByAnApiAnswer() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            s.answer = { _, _ -> ServedModel.Answer(401, """{"error":"missing or invalid x-qlab-key","reason":"key_revoked"}""") }
            var c = launch(s, dir, keys)
            val model = c.model()
            assertEquals("invalid_key", error { model.reference!!.fetch() }?.code)
            model.close()
            // the API accepts the key: a registration and a telemetry send leave the refusal of the model request
            assertTrue("the registration was not accepted", c.cloud.register())
            c.cloud.sendPoint()
            c.cloud.deliver()
            noNetwork(s)
            c = launch(s, dir, keys)
            assertEquals("the refusal of the model request was cleared by an API answer", "invalid_key", error { c.model().close() }?.code)
            // a fresh config lets the next model call ask the CDN again, and the CDN's answer decides: it still refuses
            answerNormally(s)
            s.answer = { _, _ -> ServedModel.Answer(401, """{"error":"missing or invalid x-qlab-key","reason":"key_revoked"}""") }
            c = launch(s, dir, keys)
            assertEquals("invalid_key", error { c.model().close() }?.code)
            assertTrue("the CDN was not asked again", s.requests.any { it.path == "manifest.json" && it.status == 401 })
            // now it serves: the refusal is gone, and the model, removed at the refusal, is downloaded again
            s.answer = null
            s.requests.clear()
            c = launch(s, dir, keys)
            assertNull(error { c.model().close() })
            assertTrue("the model was not downloaded again: ${s.paths()}", filesServed(s).size >= 5)
        }
    }

    @Test
    fun anOpenModelKeepsRunningWhenTheServerRefusesTheKey() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val c = launch(s, dir)
            val model = c.model()
            stub.everything = { _ -> StubQlab.Reply.json(401, mapOf("error" to "invalid_key", "message" to "This key is not valid.", "reason" to "key_revoked")) }
            // the next config answer refuses the key: the call that got it removed the model files
            c.cloud.refreshConfig()
            assertEquals("the files of the open Model are still there", emptyList<String>(), modelFiles(dir))
            assertEquals("invalid_key", error { c.model() }?.code)
            // the Model the app holds keeps running until the app closes it
            val session = model.session()
            session.push(FloatArray(16_000))
            session.finish()
            model.close()
            assertEquals(emptyList<String>(), modelFiles(dir))
        }
    }

    /**
     * Where the system lets go of a file that is open and mapped (Linux, as Android), the files of an open Model go at
     * the refusal, and the Model keeps running on what it mapped. Windows does not let go of a mapped file: there the
     * removal is owed until the Model is closed (aRemovalThatFailsIsTriedAgainAtTheNextStart), so this runs on CI's
     * Linux runner.
     */
    @Test
    fun aMappedModelFileIsRemovedWhileTheModelRuns() = runBlocking {
        org.junit.Assume.assumeFalse("Windows keeps a mapped file", (System.getProperty("os.name") ?: "").lowercase().startsWith("windows"))
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            var mapped: java.nio.MappedByteBuffer? = null
            client?.close()
            stub.recommended = s.recommended()
            val c = TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, s, keys, stubPlay(stub), engine = MappingEngines { mapped = it }),
                autoLaunch = false).also { client = it }
            val model = c.model()
            val m = mapped!!
            val before = ByteArray(m.capacity()).also { m.duplicate().get(it) }
            stub.everything = { _ -> StubQlab.Reply.json(401, mapOf("error" to "invalid_key", "message" to "This key is not valid.", "reason" to "key_revoked")) }
            c.cloud.refreshConfig()
            assertEquals("the files of the open Model are still there", emptyList<String>(), modelFiles(dir))
            val after = ByteArray(m.capacity()).also { m.duplicate().get(it) }
            assertTrue("the mapping changed after the file was removed", before.contentEquals(after))
            val session = model.session()
            session.push(FloatArray(16_000))
            session.finish()
            model.close()
            println("a mapped model file removed while its Model ran: ${before.size} bytes still read the same through the mapping")
        }
    }

    @Test
    fun aStopRemovesTheModelAndNothingElse() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            // the device key of a machine without an id is a file in the storage folder: it must stay
            val keyFile = File(File(dir, "cloud"), "install.key")
            val keys = MachineInstallKey(keyFile, machineId = { null })
            realDownload(s, dir, keys)
            assertTrue(keyFile.isFile)
            val key = keyFile.readBytes()
            // the learner's sharing state and a recording not sent yet
            val state = File(File(dir, "sharing"), "state.json").apply { parentFile.mkdirs(); writeText("{\"v\":1,\"consent\":\"granted\"}") }
            val unsent = File(File(File(dir, "outbox"), "0123456789abcdef0123456789abcdef"), "seg-000.ogg").apply { parentFile.mkdirs(); writeBytes(ByteArray(4096) { 1 }) }
            assertTrue(modelFiles(dir).size >= 6)
            stub.everything = { _ -> StubQlab.Reply.json(403, mapOf("error" to "app_blocked", "message" to "This app is not served.", "reason" to "app_blocked")) }
            val c = launch(s, dir, keys)
            assertEquals("server_rejected", error { c.model() }?.code)
            assertEquals("a model file or a record is left", emptyList<String>(), modelFiles(dir))
            assertTrue("the remembered refusal is gone", File(File(dir, "cloud"), "refused.json").isFile)
            assertTrue("the device key is gone", keyFile.isFile && keyFile.readBytes().contentEquals(key))
            assertTrue("the sharing state is gone", state.isFile)
            assertTrue("the unsent recording is gone", unsent.isFile && unsent.length() == 4096L)
        }
    }

    @Test
    fun aRemovalThatFailsIsTriedAgainAtTheNextStart() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            stub.everything = { _ -> StubQlab.Reply.json(401, mapOf("error" to "invalid_key", "message" to "This key is not valid.", "reason" to "key_revoked")) }
            // the storage refuses the removal (an error of its own)
            val c = launch(s, dir, keys, deleteTree = { false })
            assertEquals("the app did not get the refusal's error", "invalid_key", error { c.model() }?.code)
            assertTrue("the removal could not run and nothing is left to retry it", modelFiles(dir).isNotEmpty())
            assertEquals("invalid_key", error { c.prefetch() }?.code)
            // the next start, without a network: the files go before the Client does anything else
            noNetwork(s)
            launch(s, dir, keys)
            assertEquals("the owed removal was not done at the start", emptyList<String>(), modelFiles(dir))
            assertEquals("invalid_key", error { client!!.model() }?.code)
        }
    }

    @Test
    fun aStartWithARememberedRefusalRemovesFilesPutBack() = runBlocking {
        withTimeout(120_000) {
            val s = build()
            val dir = storage()
            val keys = Keys()
            realDownload(s, dir, keys)
            val copy = storage()
            File(dir, "models").copyRecursively(File(copy, "models"))
            stub.everything = { _ -> StubQlab.Reply.json(401, mapOf("error" to "invalid_key", "message" to "This key is not valid.", "reason" to "key_unknown")) }
            assertEquals("invalid_key", error { launch(s, dir, keys).model() }?.code)
            assertEquals(emptyList<String>(), modelFiles(dir))
            client?.close()
            client = null
            // the files put back by hand, records included; a start without a network
            File(copy, "models").copyRecursively(File(dir, "models"))
            assertTrue(modelFiles(dir).size >= 6)
            noNetwork(s)
            val c = launch(s, dir, keys)
            assertEquals("the files put back were not removed at the start", emptyList<String>(), modelFiles(dir))
            assertEquals("invalid_key", error { c.model() }?.code)
            assertEquals(emptyList<String>(), modelFiles(dir))
        }
    }
}
