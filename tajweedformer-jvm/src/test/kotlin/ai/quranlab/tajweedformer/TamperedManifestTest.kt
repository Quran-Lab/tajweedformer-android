package ai.quranlab.tajweedformer

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 4.0.1 dropped the second Ed25519 check of the manifest at open ([ModelFolder.protected]; perf plan row 2). The check
 * that decides what is downloaded and installed stays: the store accepts a manifest ([ModelManifest.accept]: the
 * config's build, the signature, the version) before its file list is used, whether it came from the server or from
 * the device. A manifest with one file's SHA-256 changed and its signature kept (what someone who can write the app's
 * storage, or a server in the way, would try) is refused there, and no file it names is asked for. The Core refuses the
 * same manifest at install and at open: CoreFlowTest.aTamperedManifestIsRefusedByTheCoreAtInstallAndAtOpen.
 */
class TamperedManifestTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: HttpServer
    private lateinit var served: File
    private val requests = Collections.synchronizedList(ArrayList<String>())

    private fun dir() = Files.createTempDirectory("tj-tamper-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/m/")
            requests.add(path)
            val f = File(served, path)
            if (!f.isFile) ex.sendResponseHeaders(404, -1) else {
                val b = f.readBytes()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        dirs.forEach { it.deleteRecursively() }
    }

    private val base: String get() = "http://127.0.0.1:${server.address.port}/m/"

    private fun store(root: File, url: String = base, build: String? = null) =
        ModelStore(root, url, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier, expectBuild = build, retry = RetryPlan.NONE)

    private val files = TestKit.v4Files(contexts = listOf("c16_l64"))

    /** The signed manifest's text with the weights' SHA-256 changed in one digit, its signature kept. */
    private fun tampered(text: String): String {
        val at = text.indexOf("layers.zfw")
        val m = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text, at) ?: throw AssertionError("no sha256 after the weights")
        val i = m.groups[1]!!.range.first
        val c = text[i]
        return text.substring(0, i) + (if (c == '0') '1' else '0') + text.substring(i + 1)
    }

    @Test
    fun aTamperedManifestFromTheServerIsRefusedAndNoFileItNamesIsAsked() {
        val m = TestKit.sign(TestKit.manifest(files, contexts = listOf("c16_l64")))
        TestKit.writeTransport(served, files, m)
        val good = File(served, ModelManifest.FILE_NAME).readText()
        File(served, ModelManifest.FILE_NAME).writeText(tampered(good))
        val root = dir()
        try {
            store(root).manifest(refresh = true)
            fail("a tampered manifest was accepted")
        } catch (_: RecognizerException.SignatureInvalid) {
        }
        // with the build the signed config names, its SHA-256 refuses it first
        val build = ModelManifest.hex(java.security.MessageDigest.getInstance("SHA-256").digest(good.toByteArray())).take(40)
        try {
            store(root, build = build).manifest(refresh = true)
            fail("a manifest that is not the config's build was accepted")
        } catch (_: RecognizerException.ChecksumMismatch) {
        }
        assertEquals("only the manifest was asked for: $requests", setOf(ModelManifest.FILE_NAME), requests.toSet())
        assertFalse("nothing of it is kept", File(root, ModelManifest.FILE_NAME).exists())
    }

    @Test
    fun aTamperedManifestOnTheDeviceIsRefusedBeforeItsFileListIsUsed() {
        val m = TestKit.sign(TestKit.manifest(files, contexts = listOf("c16_l64")))
        TestKit.writeTransport(served, files, m)
        val root = dir()
        // the verified copy on the device, then changed there
        store(root).manifest(refresh = true)
        val local = File(root, ModelManifest.FILE_NAME)
        local.writeText(tampered(local.readText()))
        requests.clear()
        // a later start reads the device's copy (no lease has named a folder yet: the network is not asked)
        val s = store(root, url = "")
        try {
            s.manifest()
            fail("a tampered manifest on the device was accepted")
        } catch (x: TajweedError) {
            assertTrue("$x", x is RecognizerException.SignatureInvalid)
        }
        // what decides what is downloaded refuses it too: no file list is read from it
        try {
            s.missingCpu("c16_l64")
            fail("the file list of a tampered manifest was used")
        } catch (x: TajweedError) {
            assertTrue("$x", x is RecognizerException.SignatureInvalid)
        }
        assertEquals("no request: $requests", emptyList<String>(), requests.toList())
    }
}
