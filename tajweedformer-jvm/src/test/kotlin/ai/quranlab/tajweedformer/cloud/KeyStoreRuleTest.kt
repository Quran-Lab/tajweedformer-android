package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.InstallKeyUnavailable
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A key store must never keep a device from the model for good (SPEC 15.2, contract 1.14.0), the rule for Android's
 * Keystore: an error of the store is "the store failed" for that start (nothing is opened, deleted or downloaded);
 * when the SAME failure has been met at three starts in a row the SDK makes a new key, downloads the model, and
 * reports the issue `other` / `install_key_replaced:<the exception's class>`.
 *
 * On the JVM, with a stand-in for the Keystore whose key cannot be used (every MAC fails with the exception the test
 * gives) until a new key is made; the Client, the stub API and a served build are the real ones. Written with what the
 * code had before the rule, so that the class ran on that code first: there the third start fails like the first.
 */
class KeyStoreRuleTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var served: ServedModel
    private lateinit var dir: File
    private var client: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        stub.inference = null
        served = ServedModel.of()
        dir = Files.createTempDirectory("tj-keyrule-").toFile()
    }

    @After
    fun down() {
        client?.close()
        served.close()
        stub.close()
        dir.deleteRecursively()
    }

    /** The stand-in store: its key works until [failure] is set; then every MAC fails with it until a new key is made. */
    private class Store : InstallKeyStore {
        @Volatile var failure: Exception? = null
        var made = 0
        private var generation = 0
        private fun real() = MachineInstallKey(machineId = { "this-device-key-$generation" }, user = { "learner" })
        override fun mac(message: ByteArray): ByteArray? {
            failure?.let { throw InstallKeyUnavailable("the Android Keystore did not compute the MAC: ${it.javaClass.simpleName}", it) }
            return real().mac(message)
        }
        override fun create() {
            made++
            generation++
            failure = null
        }
    }

    /** A start of the app: a new Client on the same storage (the one before is closed). */
    private fun start(keys: InstallKeyStore): TajweedClient {
        client?.close()
        client = null
        stub.recommended = served.recommended()
        return TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, served, keys, stubPlay(stub)), autoLaunch = false).also { client = it }
    }

    private suspend fun code(body: suspend () -> Unit): String? = try {
        body()
        null
    } catch (e: TajweedError) {
        e.code
    }

    private fun modelFiles(): List<String> =
        File(dir, "models").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun filesServed() = served.requests.filter { it.status == 200 && it.path != "manifest.json" }.map { it.path }.toSet()

    /** The issue reports about the install key that the server got (the queue is sent first). The stand-in engine of
     *  these tests fails its self-test, which is reported too and is no part of this rule. */
    private fun issues(c: TajweedClient): List<Pair<Any?, Any?>> {
        c.cloud.deliver()
        return stub.seen("/v1/issues").mapNotNull { it.json }.map { it["kind"] to it["code"] }
            .filter { (_, code) -> (code as? String)?.startsWith("self_test") != true }
    }

    /** A start whose store fails: the call fails with `storage_failed`, with nothing deleted and no model request. */
    private suspend fun failingStart(keys: Store, what: String) {
        val before = modelFiles()
        served.requests.clear()
        val c = start(keys)
        assertEquals("$what: the call", "storage_failed", code { c.model() })
        assertEquals("$what: something was deleted or written under models/", before, modelFiles())
        assertEquals("$what: a model request was made", emptyList<String>(), served.paths())
        assertEquals("$what: a key was made", 0, keys.made)
        assertEquals("$what: an issue was reported", emptyList<Pair<Any?, Any?>>(), issues(c))
    }

    @Test
    fun theSameFailureAtThreeStartsInARowMakesANewKeyAndDownloads() = runBlocking {
        withTimeout(120_000) {
            val keys = Store()
            start(keys).model().close()
            // from now on the store cannot use its key
            keys.failure = java.security.ProviderException("Keystore operation failed")
            failingStart(keys, "the first start")
            // two calls in one start are one start
            assertEquals("storage_failed", code { client!!.prefetch() })
            failingStart(keys, "the second start")
            // the third start in a row with the same failure: a new key, one download, one report
            served.requests.clear()
            val c = start(keys)
            val model = c.model()
            assertEquals("a new key was made, once", 1, keys.made)
            assertTrue("the model was not downloaded again: ${served.paths()}", "manifest.json" in served.paths() && filesServed().size >= 5)
            assertEquals("the build has a record made with the new key", "installed",
                (Json.parse(File(File(File(dir, "models"), "v4.0"), served.build + ".install").readText()) as Map<*, *>)["state"])
            assertEquals("one report, naming the failure's class and nothing more",
                listOf<Pair<Any?, Any?>>("other" to "install_key_replaced:ProviderException"), issues(c))
            model.close()
            // and the start after it opens what is on the device, with no request for a file
            served.requests.clear()
            start(keys).model().close()
            assertEquals(emptySet<String>(), filesServed())
            assertEquals(1, keys.made)
        }
    }

    @Test
    fun anotherFailureStartsTheCountAgain() = runBlocking {
        withTimeout(120_000) {
            val keys = Store()
            start(keys).model().close()
            keys.failure = java.security.ProviderException("Keystore operation failed")
            failingStart(keys, "start 1 (the first failure)")
            keys.failure = IllegalStateException("the Keystore service is not there")
            failingStart(keys, "start 2 (another failure)")
            failingStart(keys, "start 3 (the second failure, for the second time)")
            // the fourth start is the third in a row with the second failure
            val c = start(keys)
            c.model().close()
            assertEquals(1, keys.made)
            assertEquals(listOf<Pair<Any?, Any?>>("other" to "install_key_replaced:IllegalStateException"), issues(c))
        }
    }

    @Test
    fun aStartWhereTheStoreAnswersStartsTheCountAgain() = runBlocking {
        withTimeout(120_000) {
            val keys = Store()
            start(keys).model().close()
            val broken = java.security.ProviderException("Keystore operation failed")
            keys.failure = broken
            failingStart(keys, "start 1")
            failingStart(keys, "start 2")
            // the store answers at the third start: the model on the device opens, and the two failures are forgotten
            keys.failure = null
            served.requests.clear()
            start(keys).model().close()
            assertEquals("the model was downloaded again", emptySet<String>(), filesServed())
            keys.failure = broken
            failingStart(keys, "start 4")
            failingStart(keys, "start 5")
            assertEquals(0, keys.made)
        }
    }
}
