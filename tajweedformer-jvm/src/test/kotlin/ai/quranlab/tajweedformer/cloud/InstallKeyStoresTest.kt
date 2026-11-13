package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.InstallKeyFailures
import ai.quranlab.tajweedformer.InstallKeyGuard
import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.InstallKeyUnavailable
import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * The key stores of the install records never keep a device from the model for good (SPEC 15.2, contract 1.14.0): the
 * parts of the rule that [KeyStoreRuleTest] does not reach through the Client.
 *
 * - Which errors say a key is gone for good (the classes; Android's by name, with a stand-in class of that name in the
 *   test sources, since the JVM tests have no Android).
 * - The row of failures: a start is counted once, the store's answer ends the row, and a start that cannot make the
 *   new key fails as before and leaves the next start to try again.
 * - The desktop JVM's machine id: a read that works is kept and wins, the kept answer serves when a read fails, a read
 *   that differs is another machine (one download, nothing reported), a machine whose id can never be read is bound by
 *   its key file. The reads are stand-ins; no command is run.
 *
 * Not here, and not run anywhere: the Android Keystore's own answers on a device (its exception classes and, from
 * Android 13, its error codes) are read by KeystoreInstallKey, which the JVM cannot load.
 */
class InstallKeyStoresTest : OpenChecked() {
    private lateinit var dir: File
    private val message = "quranlab-model-install 1\nabc\ninstalled\n".toByteArray()

    @Before
    fun up() {
        dir = Files.createTempDirectory("tj-keys-").toFile()
    }

    @After
    fun down() {
        dir.deleteRecursively()
    }

    // ---- gone for good, and a failure's name ----

    @Test
    fun whichErrorsSayTheKeyIsGoneForGood() {
        assertTrue(InstallKeyFailures.goneForGood(java.security.UnrecoverableKeyException("Failed to obtain information about key")))
        assertTrue(InstallKeyFailures.goneForGood(java.security.UnrecoverableEntryException()))
        assertTrue("as a cause", InstallKeyFailures.goneForGood(RuntimeException("wrapped", java.security.UnrecoverableKeyException())))
        assertTrue("Android's class, by its name", InstallKeyFailures.goneForGood(android.security.keystore.KeyPermanentlyInvalidatedException()))
        assertTrue(InstallKeyFailures.goneForGood(java.security.InvalidKeyException("init", android.security.keystore.KeyPermanentlyInvalidatedException())))
        for (other in listOf(java.security.KeyStoreException("the Keystore is busy"), java.security.ProviderException("Keystore operation failed"),
            java.security.InvalidKeyException("not this key"), java.io.IOException("the Keystore did not load"), IllegalStateException("no service"))) {
            assertFalse("${other.javaClass.simpleName} is a failure of the store, not a key that is gone", InstallKeyFailures.goneForGood(other))
        }
    }

    @Test
    fun aFailureIsNamedByItsExceptionClassAndNeverByAMessage() {
        assertEquals("ProviderException", InstallKeyUnavailable("the Keystore did not compute the MAC", java.security.ProviderException("alias quranlab.model.install.1, uid 10234")).failure)
        assertEquals("InstallKeyUnavailable", InstallKeyUnavailable("locked").failure)
    }

    // ---- the row of failures ----

    /** A store whose MAC fails with [failure] while it is set, and whose new key can be made only when [canReplace]. */
    private class Broken : InstallKeyStore {
        var failure: Exception? = java.security.ProviderException("Keystore operation failed")
        var canReplace = false
        var hasKey = true
        var replaceAsked = 0
        override fun mac(message: ByteArray): ByteArray? {
            failure?.let { throw InstallKeyUnavailable("the store failed", it) }
            return if (hasKey) byteArrayOf(1, 2, 3) else null
        }
        override fun create() {
            hasKey = true
        }
        override fun replace() {
            replaceAsked++
            if (!canReplace) throw InstallKeyUnavailable("the store cannot make a key", java.security.ProviderException("no key"))
            failure = null
            hasKey = true
        }
    }

    /** A start of the app: a new guard over the same store and the same row. */
    private fun start(keys: InstallKeyStore, replaced: MutableList<String>) = InstallKeyGuard(keys, File(dir, InstallKeyGuard.FILE_NAME), { replaced.add(it) })

    private fun fails(g: InstallKeyGuard): Boolean = try {
        g.mac(message)
        false
    } catch (e: InstallKeyUnavailable) {
        true
    }

    @Test
    fun whenNoNewKeyCanBeMadeTheStoreFailsAsBeforeAndTheNextStartTriesAgain() {
        val keys = Broken()
        val replaced = ArrayList<String>()
        assertTrue(fails(start(keys, replaced)))
        val second = start(keys, replaced)
        // many calls in one start are one start
        repeat(5) { assertTrue(fails(second)) }
        assertEquals("a new key was asked for before the third start", 0, keys.replaceAsked)
        assertTrue("the third start: the new key could not be made, the call fails as before", fails(start(keys, replaced)))
        assertEquals(1, keys.replaceAsked)
        assertTrue(fails(start(keys, replaced)))
        assertEquals("the fourth start tried again", 2, keys.replaceAsked)
        assertEquals("nothing was reported", emptyList<String>(), replaced)
        // the store can make a key again
        keys.canReplace = true
        assertNotNull(start(keys, replaced).mac(message))
        assertEquals("reported once, with the class of the failure that was met", listOf("ProviderException"), replaced)
        assertFalse("the row is gone", File(dir, InstallKeyGuard.FILE_NAME).exists())
    }

    @Test
    fun aStoreThatAnswersNoKeyEndsTheRow() {
        val keys = Broken()
        val replaced = ArrayList<String>()
        assertTrue(fails(start(keys, replaced)))
        assertTrue(fails(start(keys, replaced)))
        // the key is gone for good at the third start (the store says "no key"): that is an answer
        keys.failure = null
        keys.hasKey = false
        assertNull(start(keys, replaced).mac(message))
        keys.failure = java.security.ProviderException("Keystore operation failed")
        assertTrue(fails(start(keys, replaced)))
        assertTrue(fails(start(keys, replaced)))
        assertEquals("the row started again after the answer", 0, keys.replaceAsked)
    }

    // ---- the desktop JVM: the machine id that was read is kept ----

    private val kept: File get() = File(dir, MachineInstallKey.KEPT_ID_NAME)

    /** The machine id in a kept answer (the file also holds its MAC under the folder's key file). */
    private fun keptId(f: File = kept): Any? = (ai.quranlab.tajweedformer.Json.parse(f.readText()) as Map<*, *>)["id"]

    /** A start on a machine whose id reads as [id] (null: the command failed or timed out). */
    private fun machine(id: String?, folder: File = dir) =
        MachineInstallKey(File(folder, "install.key"), machineId = { id }, user = { "learner" }, keptId = File(folder, MachineInstallKey.KEPT_ID_NAME))

    @Test
    fun aMachineIdThatWasReadIsKeptAndServesWhenAReadFails() {
        val first = machine("machine-A").mac(message)!!
        assertEquals("machine-A", keptId())
        assertEquals("the key file the kept answer's MAC is made with", 32L, File(dir, "install.key").length())
        // the next start's read fails (a slow start, a command that timed out): the kept answer, so the same key
        assertArrayEquals(first, machine(null).mac(message))
        assertArrayEquals(first, machine("machine-A").mac(message))
        assertEquals("machine-A", keptId())
    }

    @Test
    fun aKeptAnswerCountsOnlyInTheStoreThatKeptIt() {
        val first = machine("machine-A").mac(message)!!
        // the whole folder carried to a machine whose read fails: the kept answer and its key file came with it
        val carried = File(dir, "carried").apply { mkdirs() }
        kept.copyTo(File(carried, MachineInstallKey.KEPT_ID_NAME))
        File(dir, "install.key").copyTo(File(carried, "install.key"))
        assertArrayEquals("a carried folder does not open where the read fails", first, machine(null, carried).mac(message))
        // the kept answer alone, in a folder with another key file: it does not verify there, so it is no answer
        val alone = File(dir, "alone").apply { mkdirs() }
        machine(null, alone).create()
        kept.copyTo(File(alone, MachineInstallKey.KEPT_ID_NAME))
        assertFalse("a kept answer counted under a key file that did not make it", first.contentEquals(machine(null, alone).mac(message)!!))
        // a kept answer changed by hand
        kept.writeText(kept.readText().replace("machine-A", "machine-Z"))
        assertFalse("a changed kept answer counted", machine(null).mac(message)?.contentEquals(machine("machine-Z").mac(message)!!) == true)
        // a kept answer whose key file is gone
        machine("machine-A").mac(message)
        assertEquals("machine-A", keptId())
        File(dir, "install.key").delete()
        assertNull("a kept answer counted without its key file", machine(null).mac(message))
    }

    @Test
    fun aReadThatWorksAndDiffersWinsAndIsKept() {
        val a = machine("machine-A").mac(message)!!
        val b = machine("machine-B").mac(message)!!
        assertFalse("another machine's id gave the same key", a.contentEquals(b))
        assertEquals("machine-B", keptId())
        assertArrayEquals("the kept answer is the last read that worked", b, machine(null).mac(message))
    }

    @Test
    fun aMachineWhoseIdCanNeverBeReadIsBoundByItsKeyFile() {
        val m = machine(null)
        assertNull("no id and nothing kept: no key yet", m.mac(message))
        m.create()
        val key = File(dir, "install.key")
        assertEquals(32L, key.length())
        assertFalse(kept.exists())
        val byFile = m.mac(message)!!
        assertArrayEquals("the next start, still without an id: the key file", byFile, machine(null).mac(message))
        // the day a read works, it wins (the records made under the key file are not valid any more: one download)
        val byId = machine("machine-A").mac(message)!!
        assertFalse(byFile.contentEquals(byId))
        assertEquals("machine-A", keptId())
    }

    @Test
    fun withoutAPlaceToKeepItTheIdIsReadEveryTime() {
        var id: String? = "machine-A"
        val m = MachineInstallKey(File(dir, "install.key"), machineId = { id }, user = { "learner" })
        val a = m.mac(message)!!
        id = "machine-B"
        assertFalse("Linux reads a file each time: nothing is kept", a.contentEquals(m.mac(message)!!))
        assertFalse(kept.exists())
    }

    @Test
    fun aStorageFolderFromAnotherMachineDownloadsOnceAndReportsNothing() = runBlocking {
        withTimeout(120_000) {
            val stub = StubQlab()
            stub.inference = null
            val served = ServedModel.of()
            var client: TajweedClient? = null
            try {
                fun start(id: String?): TajweedClient {
                    client?.close()
                    stub.recommended = served.recommended()
                    return TajweedClient(stub.key, TajweedClientOptions(), gatePlatform(stub, dir, served, machine(id, File(dir, "cloud")), stubPlay(stub)), autoLaunch = false)
                        .also { client = it }
                }
                fun filesServed() = served.requests.filter { it.status == 200 && it.path != "manifest.json" }.map { it.path }.toSet()
                start("machine-A").model().close()
                // a start whose read fails: the kept id, the model on the device opens with no request for a file
                served.requests.clear()
                start(null).model().close()
                assertEquals("a failed read of the machine id cost a download", emptySet<String>(), filesServed())
                // the folder on another machine (a restored backup, a cloned disk): its records are not valid there
                served.requests.clear()
                val c = start("machine-B")
                c.model().close()
                assertTrue("the model was not downloaded on the other machine: ${served.paths()}", filesServed().size >= 5)
                c.cloud.deliver()
                // (the stand-in engine's failed self-test is reported, and is no part of this)
                assertEquals("something was reported", emptyList<Any?>(),
                    stub.seen("/v1/issues").map { it.json?.get("code") }.filter { (it as? String)?.startsWith("self_test") != true })
                // and there it stays: the next start downloads nothing
                served.requests.clear()
                start("machine-B").model().close()
                assertEquals(emptySet<String>(), filesServed())
            } finally {
                client?.close()
                served.close()
                stub.close()
            }
        }
    }
}
