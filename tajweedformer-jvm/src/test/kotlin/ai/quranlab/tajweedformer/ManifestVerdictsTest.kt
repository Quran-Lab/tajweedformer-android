package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.FileStateVault
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
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 4.0.1: the verdict of the manifest's check (the ~300 ms Kotlin Ed25519 check of every model open on the Alcatel 1) is
 * remembered for the exact bytes that passed ([ManifestVerdicts]): in this process's memory, and sealed beside the build
 * by the install's state vault. Identical bytes skip the check; a changed byte is checked in full and refused; a verdict
 * file that is not sealed by the vault, or names other bytes or another verifier, is no verdict; a stop forgets it.
 * The tampered-manifest tests (TamperedManifestTest, CoreFlowTest) show the download's and the Core's refusals.
 */
class ManifestVerdictsTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: HttpServer
    private lateinit var served: File

    private fun dir() = Files.createTempDirectory("tj-verdict-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        ManifestVerdicts.forget()
        served = dir()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/") { ex ->
            val f = File(served, ex.requestURI.path.removePrefix("/m/"))
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
        ManifestVerdicts.forget()
        dirs.forEach { it.deleteRecursively() }
    }

    private val base: String get() = "http://127.0.0.1:${server.address.port}/m/"
    private val files = TestKit.v4Files(contexts = listOf("c16_l64"))

    private fun sha(b: ByteArray) = ModelManifest.hex(MessageDigest.getInstance("SHA-256").digest(b))

    /** A verifier like the test kit's (same keys and rules, so the same [ManifestVerifier.id]) with its own count. */
    private fun verifier() = ManifestVerifier(TestKit.verifier.trustedKeys, TestKit.verifier.requireSignature, TestKit.verifier.sdkVersion,
        TestKit.verifier.requireVersion)

    private fun store(root: File, v: ManifestVerifier, vault: File, url: String = "", build: String? = null) =
        ModelStore(root, url, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, v, expectBuild = build, retry = RetryPlan.NONE,
            verdicts = ManifestVerdicts(FileStateVault(vault)))

    /** The signed manifest's text with the weights' SHA-256 changed in one digit, its signature kept. */
    private fun tampered(text: String): String {
        val at = text.indexOf("layers.zfw")
        val m = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text, at)!!
        val i = m.groups[1]!!.range.first
        return text.substring(0, i) + (if (text[i] == '0') '1' else '0') + text.substring(i + 1)
    }

    private fun publish(): ModelManifest {
        val m = TestKit.sign(TestKit.manifest(files, contexts = listOf("c16_l64")))
        TestKit.writeTransport(served, files, m)
        return m
    }

    @Test
    fun identicalBytesSkipTheCheckInThisProcessAndInTheNext() {
        publish()
        val root = dir()
        val vault = dir()
        val v = verifier()
        // the download: checked in full once, and the verdict sealed beside the build
        val first = store(root, v, vault, url = base).manifest(refresh = true)
        assertEquals(1, v.checks.get())
        assertTrue("the sealed verdict is beside the manifest", File(root, ManifestVerdicts.FILE_NAME).isFile)
        // a later open in this process: the same bytes, no second check, the same manifest
        val again = store(root, v, vault).manifest()
        assertEquals("no second check of identical bytes", 1, v.checks.get())
        assertEquals(first.build, again.build)
        assertEquals(first.toleranceSigned, again.toleranceSigned)
        assertEquals(first.rawToleranceSigned, again.rawToleranceSigned)
        // the next process (the memory empty): the sealed verdict, no check
        ManifestVerdicts.forget()
        store(root, v, vault).manifest()
        assertEquals("the sealed verdict is used in the next process", 1, v.checks.get())
        // the config's build is still checked every time: another build named is refused at once
        try {
            store(root, v, vault, build = "0".repeat(40)).manifest()
            fail("a manifest that is not the config's build was accepted")
        } catch (_: RecognizerException.ChecksumMismatch) {
        }
    }

    @Test
    fun aChangedByteIsCheckedInFullAndRefused() {
        publish()
        val root = dir()
        val vault = dir()
        val v = verifier()
        store(root, v, vault, url = base).manifest(refresh = true)
        val local = File(root, ModelManifest.FILE_NAME)
        local.writeText(tampered(local.readText()))
        for (process in 1..2) {
            if (process == 2) ManifestVerdicts.forget()
            val before = v.checks.get()
            try {
                store(root, v, vault).manifest()
                fail("a changed manifest was accepted")
            } catch (x: TajweedError) {
                assertTrue("$x", x is RecognizerException.SignatureInvalid)
            }
            assertEquals("the changed bytes were checked in full", before + 1, v.checks.get())
        }
    }

    @Test
    fun aVerdictTheVaultDidNotSealIsNoVerdict() {
        publish()
        val root = dir()
        val vault = dir()
        val v = verifier()
        store(root, v, vault, url = base).manifest(refresh = true)
        val local = File(root, ModelManifest.FILE_NAME)
        val bad = tampered(local.readText()).toByteArray()
        local.writeBytes(bad)
        ManifestVerdicts.forget()
        // a verdict for the changed bytes, written as plain text beside it, and one sealed by another install's vault
        val text = "quranlab-manifest-verdict 1\n${sha(bad)}\n${v.id}\n0\n0\n".toByteArray()
        for (forged in listOf(text, FileStateVault(dir()).seal(text)!!)) {
            File(root, ManifestVerdicts.FILE_NAME).writeBytes(forged)
            val before = v.checks.get()
            try {
                store(root, v, vault).manifest()
                fail("a forged verdict let a changed manifest through")
            } catch (x: TajweedError) {
                assertTrue("$x", x is RecognizerException.SignatureInvalid)
            }
            assertEquals(before + 1, v.checks.get())
        }
    }

    @Test
    fun aVerdictOfAnotherVerifierOrOtherBytesIsNotUsed() {
        publish()
        val root = dir()
        val vault = dir()
        val v = verifier()
        store(root, v, vault, url = base).manifest(refresh = true)
        ManifestVerdicts.forget()
        // another verifier (another SDK version it compares min_sdk_version with): checked in full
        val other = ManifestVerifier(TestKit.verifier.trustedKeys, TestKit.verifier.requireSignature, "4.0.3", TestKit.verifier.requireVersion)
        assertTrue(other.id != v.id)
        store(root, other, vault).manifest()
        assertEquals("another verifier checks in full", 1, other.checks.get())
        // a new manifest from the server (other bytes): checked in full, and its own verdict then sealed
        val m2 = TestKit.sign(TestKit.manifest(files, contexts = listOf("c16_l64"), revision = "v4.0-test.2"))
        TestKit.writeTransport(served, files, m2)
        val before = v.checks.get()
        store(root, v, vault, url = base).manifest(refresh = true)
        assertEquals(before + 1, v.checks.get())
        ManifestVerdicts.forget()
        store(root, v, vault).manifest()
        assertEquals("the new bytes' verdict was sealed", before + 1, v.checks.get())
    }

    @Test
    fun aStopForgetsAndAFolderThatIsGoneIsNotMadeAgain() {
        publish()
        val root = dir()
        val vault = dir()
        val v = verifier()
        store(root, v, vault, url = base).manifest(refresh = true)
        val bytes = File(root, ModelManifest.FILE_NAME).readBytes()
        // a stop removes the build's folder (its sealed verdict with it) and forgets the memory
        assertTrue(root.deleteRecursively())
        ManifestVerdicts.forget()
        val verdicts = ManifestVerdicts(FileStateVault(vault))
        assertEquals("nothing is remembered after a stop", null, verdicts.known(root, sha(bytes), v))
        verdicts.passed(root, sha(bytes), v, ManifestVerdicts.Flags(false, false))
        assertFalse("a verdict never makes the removed folder again", root.exists())
    }
}
