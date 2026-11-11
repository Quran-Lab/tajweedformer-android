package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `model.reference` through the public Client, with a small build behind a local server: the reference data is not
 * part of the first download; before `fetch()` it is not on the device and `units` is `reference_missing`; `fetch()`
 * asks the server for exactly that one file and keeps it only when its size and SHA-256 are the signed manifest's; a
 * server that does not have the file, or that sends other bytes, leaves nothing behind; a build without reference data
 * has no `reference` at all; a surah or ayah the Quran does not have is `invalid_argument`.
 *
 * The table of this small build is not a real one, so no unit is read here. `ReferenceTableTest` reads every ayah of
 * the real table, on a machine that has a real model folder and the engine.
 */
class ReferenceFetchTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var served: ServedModel? = null
    private var client: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-reference-").toFile()
    }

    @After
    fun down() {
        client?.close()
        served?.close()
        stub.close()
        dir.deleteRecursively()
    }

    private fun client(s: ServedModel): TajweedClient {
        served = s
        stub.recommended = s.recommended()
        stub.inference = null
        return TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir), apiUrl = stub.base,
            trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = s.url, engine = CountingEngines()), autoLaunch = false).also { client = it }
    }

    private suspend fun code(body: suspend () -> Unit): String? = try {
        body()
        null
    } catch (e: TajweedError) {
        assertTrue("${e.code} has no fix", e.fix.isNotBlank())
        e.code
    }

    private val stored = TestKit.REFERENCE_TABLE + ".gz"

    @Test
    fun fetchDownloadsAndVerifiesExactlyTheReferenceFile() = runBlocking {
        withTimeout(60_000) {
            val files = TestKit.v4Files()
            val s = ServedModel.of(files)
            val model = client(s).model()
            assertTrue("the first download asked for the reference data: ${s.paths()}", s.paths().none { it.startsWith(TestKit.REFERENCE_TABLE) })
            val reference = model.reference!!
            val onDevice = File(model.folder.dir, TestKit.REFERENCE_TABLE)

            // before the fetch: not on the device, and a lookup says so
            assertFalse(reference.isDownloaded)
            assertFalse(onDevice.exists())
            assertEquals("reference_missing", code { reference.units(1, 1) })
            assertEquals("reference_missing", code { reference.units(114, 6) })
            // what the Quran does not have is refused before anything is looked up, fetched or not
            val outside = listOf(0 to 1, 115 to 1, -1 to 1, 1 to 0, 1 to 8, 2 to 287, 114 to 7, 9 to -3)
            for ((surah, ayah) in outside) assertEquals("$surah:$ayah", "invalid_argument", code { reference.units(surah, ayah) })
            assertTrue("a lookup asked the server for something", s.paths().none { it.startsWith(TestKit.REFERENCE_TABLE) })

            // the fetch: one request, for that file
            val before = s.requests.size
            reference.fetch()
            val asked = s.requests.drop(before)
            assertEquals("fetch() asked for exactly the reference file", listOf(stored), asked.map { it.path })
            assertEquals(200, asked.single().status)
            val table = files.first { it.path == TestKit.REFERENCE_TABLE }
            assertEquals("the stored file, whole", table.stored.size.toLong(), asked.single().sent)
            // verified: what is kept is the manifest's decoded file, byte for byte
            assertTrue(reference.isDownloaded)
            assertEquals(table.bytes.size.toLong(), onDevice.length())
            assertEquals(TestKit.sha(table.bytes), TestKit.sha(onDevice.readBytes()))
            assertFalse("the download's part was left behind", File(model.folder.dir, "$stored.part").exists())

            // once: a second fetch asks nothing, and so does a Model opened again on this device
            reference.fetch()
            assertEquals(before + 1, s.requests.size)
            for ((surah, ayah) in outside) assertEquals("$surah:$ayah", "invalid_argument", code { reference.units(surah, ayah) })
            // this build's table is a placeholder, not a table: reading it is an error of the catalogue, never a wrong
            // answer (`model_invalid` from the engine's reader; `runtime_unsupported` where the engine is not built)
            val read = code { reference.units(1, 1) }
            assertTrue("reading a file that is not a table gave $read", read == "model_invalid" || read == "runtime_unsupported")
        }
    }

    @Test
    fun aFetchThatFailsIsReferenceMissingAndKeepsNothing() = runBlocking {
        withTimeout(60_000) {
            val files = TestKit.v4Files()
            val s = ServedModel.of(files)
            val model = client(s).model()
            val reference = model.reference!!
            val onDevice = File(model.folder.dir, TestKit.REFERENCE_TABLE)
            val table = files.first { it.path == TestKit.REFERENCE_TABLE }

            // the server sends other bytes of the same length: refused by the SHA-256 of the signed manifest
            s.replace = { path -> if (path == stored) table.stored.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else null }
            assertEquals("reference_missing", code { reference.fetch() })
            assertFalse(reference.isDownloaded)
            assertFalse("a file that did not verify was kept", onDevice.exists())
            assertFalse("its part was kept", File(model.folder.dir, "$stored.part").exists())
            assertEquals("reference_missing", code { reference.units(1, 1) })

            // the connection drops before a byte of the file arrives
            s.replace = null
            s.cut = { path -> if (path == stored) 0L else null }
            assertEquals("reference_missing", code { reference.fetch() })
            assertFalse(reference.isDownloaded)
            assertFalse(onDevice.exists())

            // the server is whole again: the same call succeeds
            s.cut = null
            reference.fetch()
            assertTrue(reference.isDownloaded)
            assertEquals(TestKit.sha(table.bytes), TestKit.sha(onDevice.readBytes()))
        }
    }

    @Test
    fun aServerWithoutTheReferenceFileIsReferenceMissing() = runBlocking {
        withTimeout(60_000) {
            // the folder on the server has every file of the build but the reference data (a build whose reference
            // was not uploaded): the Model opens, the fetch fails cleanly
            val files = TestKit.v4Files()
            val s = ServedModel.of(files)
            assertTrue(File(s.root, stored).delete())
            val model = client(s).model()
            val reference = model.reference!!
            assertFalse(reference.isDownloaded)
            val before = s.requests.size
            assertEquals("reference_missing", code { reference.fetch() })
            assertEquals(listOf(stored to 404), s.requests.drop(before).map { it.path to it.status })
            assertFalse(reference.isDownloaded)
            assertEquals("reference_missing", code { reference.units(1, 1) })
        }
    }

    @Test
    fun aBuildWithoutReferenceDataHasNoReference() = runBlocking {
        withTimeout(60_000) {
            val bare = TestKit.v4Files().filter { it.role != "reference" }
            val model = client(ServedModel.of(bare)).model()
            assertNull(model.reference)
        }
    }
}
