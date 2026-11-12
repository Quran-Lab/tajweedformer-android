package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ManifestVerifier
import ai.quranlab.tajweedformer.ModelHost
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.Quran
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedReference
import ai.quranlab.tajweedformer.TajweedTokens
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.engine.EngineReference
import ai.quranlab.tajweedformer.engine.TajweedJni
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * `model.reference` with the REAL table of a model build, through the public API and the engine's reader (JNI):
 * every ayah of the Quran (6236) read with `units(surah, ayah)` and compared with what the engine's own lookup gives
 * for the same file, with the source table the build was made from (when the machine has it), and with a few ayahs
 * written out here; the table on a device that did not have it, fetched from a local server; a build without one.
 *
 * It needs the local model folders of this PC and the host JNI library, so it is skipped everywhere else:
 * `TJ_LOCAL30` names the index of the folders (`local30.json`: the stored folders `full` and
 * `full-reference-not-fetched`, the decoded ones `full-decoded` and `bare-decoded`, and the public half of the key
 * they are signed with, which is read from there and trusted through the test seam only). `TJ_EXPECTED_TJ3` names the
 * source table (`expected_tj3.jsonl.gz`); without it that one comparison is left out and the test says so.
 */
class ReferenceTableTest : OpenChecked() {
    private class Local(val key: ByteArray, val version: String, val build: String, val full: File, val notFetched: File,
                        val fullDecoded: File, val bareDecoded: File)

    private lateinit var local: Local
    private var stub: StubQlab? = null
    private var dir: File? = null
    private var served: ServedModel? = null
    private var client: TajweedClient? = null
    private val models = ArrayList<TajweedModel>()
    private val tables = ArrayList<EngineReference>()

    @Suppress("UNCHECKED_CAST")
    @Before
    fun up() {
        val index = TestKit.env("TJ_LOCAL30")?.let(::File)
        Assume.assumeTrue("the real table needs the local model folders (TJ_LOCAL30 names their local30.json)", index?.isFile == true)
        Assume.assumeTrue("the real table needs the host JNI library (scripts/build_host.sh, or -Pnative)", TajweedJni.available)
        val o = Json.parse(index!!.readText()) as Map<String, Any?>
        fun path(group: String, name: String) = File(((o[group] as Map<String, Any?>)[name] as Map<String, Any?>)["path"] as String)
        local = Local(TestKit.hex(o["public_key_hex"] as String), o["version"] as String,
            ((o["variants"] as Map<String, Any?>)["full"] as Map<String, Any?>)["build"] as String,
            path("variants", "full"), path("variants", "full-reference-not-fetched"), path("decoded", "full-decoded"), path("decoded", "bare-decoded"))
        for (f in listOf(local.full, local.notFetched, local.fullDecoded, local.bareDecoded)) assertTrue("$f is not a folder", f.isDirectory)
    }

    @After
    fun down() {
        tables.forEach { it.close() }
        models.forEach { it.close() }
        client?.close()
        served?.close()
        stub?.close()
        dir?.deleteRecursively()
    }

    private suspend fun code(body: suspend () -> Unit): String? = try {
        body()
        null
    } catch (e: TajweedError) {
        e.code
    }

    /** The source table by (surah, ayah): each ayah's units as ids of [tokens], and its word count. Null without the file. */
    @Suppress("UNCHECKED_CAST")
    private fun source(tokens: TajweedTokens): Map<Pair<Int, Int>, Pair<IntArray, Int>>? {
        val f = TestKit.env("TJ_EXPECTED_TJ3")?.let(::File)?.takeIf { it.isFile } ?: return null
        val out = HashMap<Pair<Int, Int>, Pair<IntArray, Int>>()
        GZIPInputStream(f.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = Json.parse(line) as Map<String, Any?>
                val names = o["tj3"] as List<String>
                val ids = IntArray(names.size) { i -> tokens.id(names[i]) ?: error("the source table has the unit ${names[i].map { it.code }}, which is not a token of the model") }
                out[(o["surah"] as Double).toInt() to (o["ayah"] as Double).toInt()] = ids to (o["n_words"] as Double).toInt()
            }
        }
        return out
    }

    /** What reading every ayah found. */
    private class Read(val ayahs: Int, val units: Long, val words: Long, val differFromEngine: List<String>, val differFromSource: List<String>?)

    /** Reads every ayah through the public API and compares each with the engine's own lookup of [file] and with the source table. */
    private fun readAll(model: TajweedModel, reference: TajweedReference, file: File): Read {
        val direct = EngineReference(file).also { tables.add(it) }
        val source = source(model.tokens)
        var ayahs = 0
        var units = 0L
        var words = 0L
        val fromEngine = ArrayList<String>()
        val fromSource = ArrayList<String>()
        for (surah in 1..114) {
            assertEquals("ayahs of surah $surah in the table", Quran.AYAHS[surah - 1], direct.ayahCount(surah))
            for (ayah in 1..Quran.AYAHS[surah - 1]) {
                val got = reference.units(surah, ayah)
                val want = direct.units(surah, ayah)
                if (want == null || !got.contentEquals(want.first)) fromEngine.add("$surah:$ayah")
                // the ids are the model's own tokens: never the blank, never outside the vocabulary
                if (got.isEmpty() || got.any { it < 1 || it >= model.tokens.count }) fromEngine.add("$surah:$ayah (ids)")
                val wordOf = reference.wordOfUnit(surah, ayah)!!
                val n = wordOf.last() + 1
                if (source != null) {
                    val s = source[surah to ayah]
                    if (s == null || !got.contentEquals(s.first) || n != s.second) fromSource.add("$surah:$ayah")
                }
                // the words of the ayah are what the word bars cut it into
                if (n != got.count { it == model.tokens.wordBar } + 1) fromEngine.add("$surah:$ayah (words)")
                ayahs++
                units += got.size
                words += n
            }
        }
        if (source != null) assertEquals("ayahs in the source table", 6236, source.size)
        return Read(ayahs, units, words, fromEngine, if (source == null) null else fromSource)
    }

    private fun check(what: String, r: Read) {
        println("$what: ${r.ayahs} ayahs, ${r.units} units, ${r.words} words read with units(surah, ayah); " +
            "${r.differFromEngine.size} differ from the engine's own lookup; " +
            (r.differFromSource?.let { "${it.size} differ from the source table" } ?: "the source table is not on this machine (TJ_EXPECTED_TJ3): not compared"))
        assertEquals(6236, r.ayahs)
        assertEquals("differ from the engine's lookup: ${r.differFromEngine.take(10)}", 0, r.differFromEngine.size)
        r.differFromSource?.let { assertEquals("differ from the source table: ${it.take(10)}", 0, it.size) }
        // the table of model version 4.0.0
        assertEquals(542_325L, r.units)
        assertEquals(77_481L, r.words)
    }

    /** A few ayahs written out: the ids of model version 4.0.0's tokens (`|` is 1). */
    private suspend fun literals(reference: TajweedReference, tokens: TajweedTokens) {
        assertEquals(1, tokens.wordBar)
        assertArrayEquals("1:1", intArrayOf(3, 32, 13, 25, 32, 1, 24, 34, 27, 32, 1, 11, 31, 7, 25, 34, 26, 32, 1, 11, 31, 7, 35, 25), reference.units(1, 1))
        assertArrayEquals("108:3", intArrayOf(2, 32, 26, 31, 1, 14, 34, 26, 32, 2, 31, 23, 31, 1, 27, 33, 28, 31, 1, 24, 2, 31, 3, 4, 31, 11), reference.units(108, 3))
        assertArrayEquals("112:1", intArrayOf(22, 33, 24, 1, 27, 33, 28, 31, 1, 24, 34, 27, 33, 1, 2, 31, 7, 31, 9), reference.units(112, 1))
        assertArrayEquals("114:6", intArrayOf(25, 32, 26, 31, 1, 24, 6, 32, 26, 31, 4, 32, 1, 28, 31, 26, 34, 13), reference.units(114, 6))
        // the same ayah by name: the vowels are tokens of their own
        assertEquals(listOf("i", "i", "|", "aa", "i", "|", "a", "aa", "i", "|", "a", "ii"), reference.units(1, 1).map { tokens.name(it) }.filter { it.all { c -> c.code < 128 } })
        for ((surah, ayah) in listOf(0 to 1, 115 to 1, -1 to 1, 1 to 0, 1 to 8, 2 to 287, 114 to 7, 9 to -3)) {
            assertEquals("$surah:$ayah", "invalid_argument", code { reference.units(surah, ayah) })
        }
    }

    @Test
    fun everyAyahOfAFolderThatHasTheTable() = runBlocking {
        withTimeout(300_000) {
            val model = TajweedModel.openVerifiedFolder(local.fullDecoded, ModelHost(verifier = ManifestVerifier(listOf(local.key)))).also { models.add(it) }
            assertEquals(local.build, model.build)
            val reference = model.reference!!
            assertTrue(reference.isDownloaded)
            // nothing to fetch, and nothing to fetch it with: the call returns
            reference.fetch()
            val file = File(local.fullDecoded, "expected_tj3.zfx")
            check("full-decoded (build ${model.build.take(8)})", readAll(model, reference, file))
            literals(reference, model.tokens)
        }
    }

    @Test
    fun theTableIsFetchedOnADeviceThatDoesNotHaveIt() = runBlocking {
        withTimeout(600_000) {
            val s = StubQlab().also { stub = it }
            val storage = Files.createTempDirectory("tj-reftable-").toFile().also { dir = it }
            // the server has every file of the build but the reference data: the stored folder `full-reference-not-fetched`
            val server = ServedModel.ofFolder(local.notFetched).also { served = it }
            assertEquals(local.build, server.build)
            s.recommended = server.recommended(local.version)
            s.inference = null
            val c = TajweedClient(s.key, TajweedClientOptions(), ClientPlatform(cloud = s.environment(storage), apiUrl = s.base,
                trustedKeys = listOf(s.publicKey, local.key), modelsUrl = server.url), autoLaunch = false).also { client = it }
            val model = c.model()
            assertEquals(local.build, model.build)
            val entry = model.folder.manifest.files.single { it.role == "reference" }
            assertTrue("the first download asked for the reference data: ${server.paths()}", entry.path !in server.paths())
            val reference = model.reference!!
            val onDevice = File(model.folder.dir, entry.localPath)

            // before the fetch
            assertFalse(reference.isDownloaded)
            assertFalse(onDevice.exists())
            assertEquals("reference_missing", code { reference.units(1, 1) })
            // this server does not have the file: the fetch fails and says so
            var before = server.requests.size
            assertEquals("reference_missing", code { reference.fetch() })
            assertEquals(listOf(entry.path to 404), server.requests.drop(before).map { it.path to it.status })
            assertFalse(reference.isDownloaded)

            // the server has the file now (the stored folder `full`, the same build): one request, for that file
            server.root = local.full
            before = server.requests.size
            reference.fetch()
            val asked = server.requests.drop(before)
            assertEquals("fetch() asked for exactly the reference file", listOf(entry.path), asked.map { it.path })
            assertEquals(200, asked.single().status)
            assertEquals("the stored file, whole", entry.size, asked.single().sent)
            // verified: what is kept is the manifest's decoded file, the same bytes as the folder that had it
            assertTrue(reference.isDownloaded)
            assertEquals(entry.localSize, onDevice.length())
            assertEquals(entry.localSha256, TestKit.sha(onDevice.readBytes()))
            assertEquals(TestKit.sha(File(local.fullDecoded, entry.localPath).readBytes()), TestKit.sha(onDevice.readBytes()))
            println("fetch(): 1 request (${entry.path}, ${asked.single().sent} bytes stored), kept ${onDevice.length()} bytes, SHA-256 as the manifest's")
            reference.fetch()
            assertEquals("a second fetch asked the server again", before + 1, server.requests.size)

            check("fetched through the Client (build ${model.build.take(8)})", readAll(model, reference, onDevice))
            literals(reference, model.tokens)
        }
    }

    @Test
    fun aBuildWithoutTheTableHasNoReference() = runBlocking {
        withTimeout(120_000) {
            val model = TajweedModel.openVerifiedFolder(local.bareDecoded, ModelHost(verifier = ManifestVerifier(listOf(local.key)))).also { models.add(it) }
            assertNull(model.reference)
            assertTrue("the bare build has reference data in its manifest", model.folder.manifest.files.none { it.role == "reference" })
        }
    }
}
