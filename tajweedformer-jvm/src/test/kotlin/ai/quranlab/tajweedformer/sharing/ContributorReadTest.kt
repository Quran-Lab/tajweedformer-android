package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * SDK 3.1.4: the contributor's id, device key and recovery code. Something that is there and cannot be read is not
 * "not there": while one of the three cannot be read, nothing of the contributor is made, replaced or deleted, no take
 * is recorded for sharing, the upload pass sends nothing, no summary is asked, no code is shown, and "delete what I
 * shared" asks the server nothing and fails with the storage error. The device key and the recovery code are made
 * only together, and only when both are not there; the id only when it is not there and neither of them is.
 *
 * Whole sessions through the observer hooks, against [StubQlab] (which binds a contributor's key once, answers a start
 * with another key 409 `contributor_key_mismatch`, and deletes only for a deletion message signed by the bound key).
 * The Keystore is [FakeAndroidKeyStore]: the answers of the Android Keystore on Android 12 and later, as AOSP's sources
 * give them. A real Keystore failing on a device is not shown here.
 *
 * Each test that makes something unreadable makes it readable again afterwards, and then checks that the files are the
 * bytes they were, that the start carries the same id, key and recovery hash and is accepted, and that a deletion is
 * signed by the key that was there.
 */
class ContributorReadTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()
    private val keystore = FakeAndroidKeyStore()

    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
    }

    @After fun down() {
        dirs.forEach { it.deleteRecursively() }
        if (::stub.isInitialized) stub.close()
    }

    private fun storage(): File = Files.createTempDirectory("tj-contributor").toFile().also { dirs += it }

    private fun idFile(at: File) = File(File(at, "sharing"), "contributor")

    private fun codeFile(at: File) = File(File(at, "sharing"), "recovery.sealed")

    private fun alias(id: String) = "quranlab.contributor.$id"

    /** A start of the app: a cloud on [at] over the fake Keystore, its config fetched, the learner said yes. */
    private fun cloud(at: File, box: SecretBox = keystoreBox(keystore)): QuranLabCloud {
        val c = stub.cloud(at, keys = keystoreKeys(keystore), box = box)
        c.refreshConfig()
        if (!c.sharing.isOn) c.sharing.accept()
        return c
    }

    private var seed = 1L

    /** One finished take of [seconds] through the observer hooks; the join window passes, so its recording closes. */
    private fun take(c: QuranLabCloud, seconds: Double = 3.0) {
        val rate = 16_000
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = rate, inputRoute = "built_in", decodeBackend = "mock"))
        val n = Math.round(rate * seconds).toInt()
        var at = 0
        while (at < n) {
            val k = minOf(rate / 10, n - at)
            c.onAudio(OggTestKit.noise(k, seed = seed++), 0, k, rate)
            at += k
        }
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))
        stub.skewMs += TakeJoin.DEFAULT_WINDOW_MS + 1
        c.sharing.checkJoinWindow()
        c.sharing.lastCapture?.await()
    }

    private fun upload(c: QuranLabCloud) = repeat(4) { c.sharing.outbox.drain(ignoringBackoff = true) }

    private fun uploaded(c: QuranLabCloud) = c.sharing.items.count { it.status == Contribution.Status.UPLOADED }

    /** The requests to the sharing routes from request [from] on. */
    private fun sharingRequests(from: Int): List<String> = synchronized(stub.seen) { stub.seen.drop(from).toList() }
        .filter { it.path.startsWith("/v1/uploads") || it.path.startsWith("/v1/contributions/") || it.method == "PUT" }
        .map { "${it.method} ${it.path}" }

    private fun seen(): Int = synchronized(stub.seen) { stub.seen.size }

    private fun standing(c: QuranLabCloud): String = c.sharing.contributor.standing().let { it.javaClass.simpleName + ((it as? Contributor.Standing.Lost)?.let { l -> " (" + l.why + ")" } ?: "") }

    /** What one shared take left on the device and at the server. */
    private class Shared(val at: File, val id: String, val idBytes: ByteArray, val codeBytes: ByteArray, val point: String, val hash: String)

    /** A device that shared one take: the id, the key and the code are made and bound at the server. */
    private fun sharedOnce(at: File = storage()): Shared {
        val c = cloud(at)
        take(c)
        upload(c)
        assertEquals("the first take was not uploaded: ${c.sharing.items}, sharing on ${c.sharing.isOn}, the contributor ${standing(c)}", 1, uploaded(c))
        val start = stub.starts().last().json!!
        val id = start["contributor"] as String
        assertEquals(id, idFile(at).readText())
        return Shared(at, id, idFile(at).readBytes(), codeFile(at).readBytes(), start["contributor_key"] as String, start["recovery_hash"] as String)
    }

    /** The take that comes when the three read again: its start carries the same id, key and hash and is accepted;
     *  the files are the bytes they were; a deletion is signed by the key that was there. */
    private fun allAsBefore(s: Shared, c: QuranLabCloud = cloud(s.at)) {
        val starts = stub.starts().size
        val ok = uploaded(c)
        take(c)
        upload(c)
        assertEquals("a take after the three read again was not recorded", starts + 1, stub.starts().size)
        val start = stub.starts().last().json!!
        assertEquals("the id of the start", s.id, start["contributor"])
        assertEquals("the device key of the start", s.point, start["contributor_key"])
        assertEquals("the recovery hash of the start", s.hash, start["recovery_hash"])
        assertEquals("the start was not accepted: ${c.sharing.items}", ok + 1, uploaded(c))
        assertArrayEquals("the id file's bytes", s.idBytes, idFile(s.at).readBytes())
        assertArrayEquals("the sealed code's bytes", s.codeBytes, codeFile(s.at).readBytes())
        assertEquals(1, keystore.madeUnder("quranlab.contributor."))
        val deletions = stub.deletions
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertEquals("the deletion was not signed by the key that was there", deletions + 1, stub.deletions)
    }

    /** A take while something cannot be read: no recording begins, nothing of the contributor is made, the files stay. */
    private fun nothingRecorded(s: Shared, c: QuranLabCloud, what: String) {
        val starts = stub.starts().size
        val items = c.sharing.items.size
        take(c)
        upload(c)
        assertEquals("$what: a recording began", items, c.sharing.items.size)
        assertEquals("$what: a start was sent", starts, stub.starts().size)
        assertEquals("$what: a device key was made", 1, keystore.madeUnder("quranlab.contributor."))
        assertTrue("$what: the device key that was there is gone", keystore.has(alias(s.id)))
    }

    /** "Delete what I shared" while something cannot be read: the storage error, no request, nothing changed. */
    private fun deletionRefused(s: Shared, c: QuranLabCloud, what: String) {
        val state = File(File(s.at, "sharing"), "state.json").readBytes()
        val items = c.sharing.items.map { it.id to it.status }
        val from = seen()
        val e = try {
            "returned ${c.sharing.deleteEverything()}"
        } catch (x: TajweedError) {
            x.code
        }
        assertEquals("$what: the deletion", "storage_failed", e)
        assertEquals("$what: requests of the deletion", emptyList<String>(), sharingRequests(from))
        assertArrayEquals("$what: the sharing state changed", state, File(File(s.at, "sharing"), "state.json").readBytes())
        assertTrue("$what: sharing went off", c.sharing.isOn)
        assertEquals("$what: the queue changed", items, c.sharing.items.map { it.id to it.status })
        assertEquals("$what: deletions at the server", 0, stub.deletions)
    }

    // ---- the id file ----

    @Test
    fun anIdFileWhoseTextIsNotAnIdIsNeitherReplacedNorUsed() {
        val s = sharedOnce()
        val damaged = "0123456789abcdef".toByteArray()
        idFile(s.at).writeBytes(damaged)
        val c = cloud(s.at)
        nothingRecorded(s, c, "a damaged id file")
        assertArrayEquals("the damaged id file was written over", damaged, idFile(s.at).readBytes())
        deletionRefused(s, c, "a damaged id file")
        assertArrayEquals("the damaged id file was written over", damaged, idFile(s.at).readBytes())
        idFile(s.at).writeBytes(s.idBytes)
        allAsBefore(s, c)
    }

    @Test
    fun anIdFileThatCannotBeReadIsNeitherReplacedNorUsed() {
        val s = sharedOnce()
        // the read is refused: a folder stands where the file is (the file is kept aside and put back after)
        val aside = File(s.at, "contributor.aside")
        assertTrue(idFile(s.at).renameTo(aside))
        assertTrue(idFile(s.at).mkdir())
        val c = cloud(s.at)
        nothingRecorded(s, c, "an id file that cannot be read")
        deletionRefused(s, c, "an id file that cannot be read")
        assertTrue(idFile(s.at).isDirectory)
        assertTrue(idFile(s.at).delete())
        assertTrue(aside.renameTo(idFile(s.at)))
        allAsBefore(s, c)
    }

    // ---- the device key ----

    @Test
    fun aKeystoreEntryThatCannotBeReadIsNotReplaced() {
        val s = sharedOnce()
        keystore.fail[alias(s.id)] = FakeAndroidKeyStore.Fault.SYSTEM
        val c = cloud(s.at)
        nothingRecorded(s, c, "a Keystore entry that cannot be read")
        deletionRefused(s, c, "a Keystore entry that cannot be read")
        keystore.fail.remove(alias(s.id))
        allAsBefore(s, c)
    }

    /**
     * The answers that the install key's rule of 3.1.3 reads as "gone for good" are, for the contributor key, "cannot be
     * read" like any other, at every start: no new key, no take recorded, no deletion request.
     */
    @Test
    fun aKeyTheKeystoreCallsGoneForGoodIsNeverMadeAnewAtAnyStart() {
        for (fault in listOf(FakeAndroidKeyStore.Fault.INVALIDATED, FakeAndroidKeyStore.Fault.CORRUPTED, FakeAndroidKeyStore.Fault.SYSTEM)) {
            val s = sharedOnce()
            val deletions = stub.deletions
            keystore.fail[alias(s.id)] = fault
            repeat(3) { n ->
                val c = cloud(s.at)
                nothingRecorded(s, c, "$fault, start ${n + 1}")
                val from = seen()
                val e = try { "returned ${c.sharing.deleteEverything()}" } catch (x: TajweedError) { x.code }
                assertEquals("$fault, start ${n + 1}: the deletion", "storage_failed", e)
                assertEquals("$fault, start ${n + 1}: requests of the deletion", emptyList<String>(), sharingRequests(from))
            }
            assertEquals("$fault: deletions at the server", deletions, stub.deletions)
            assertArrayEquals("$fault: the id file's bytes", s.idBytes, idFile(s.at).readBytes())
            assertArrayEquals("$fault: the sealed code's bytes", s.codeBytes, codeFile(s.at).readBytes())
            keystore.fail.remove(alias(s.id))
            // the next case shares on a device of its own: its key is the one key made
            keystore.made.clear()
        }
    }

    @Test
    fun aKeystoreEntryGoneWhileTheIdAndTheCodeAreThereMakesNoNewKey() {
        val s = sharedOnce()
        val c0 = cloud(s.at)
        val code = c0.sharing.recoveryCode()
        keystore.lose(alias(s.id))
        val c = cloud(s.at)
        val starts = stub.starts().size
        val items = c.sharing.items.size
        take(c)
        upload(c)
        assertEquals("a recording began", items, c.sharing.items.size)
        assertEquals("a start was sent", starts, stub.starts().size)
        assertEquals("a new device key was made under the old id", 1, keystore.madeUnder("quranlab.contributor."))
        assertFalse(keystore.has(alias(s.id)))
        assertEquals("the code that is there is still shown (it deletes on the portal)", code, c.sharing.recoveryCode())
        assertArrayEquals("the sealed code's bytes", s.codeBytes, codeFile(s.at).readBytes())
        val from = seen()
        val e = try { "returned ${c.sharing.deleteEverything()}" } catch (x: TajweedError) { x.code }
        assertEquals("the deletion", "storage_failed", e)
        assertEquals("requests of the deletion", emptyList<String>(), sharingRequests(from))
        assertTrue(c.sharing.isOn)
    }

    // ---- the recovery code ----

    @Test
    fun aSealedCodeThatCannotBeReadIsNotReplaced() {
        val s = sharedOnce()
        val aside = File(s.at, "recovery.aside")
        assertTrue(codeFile(s.at).renameTo(aside))
        assertTrue(codeFile(s.at).mkdir())
        val c = cloud(s.at)
        nothingRecorded(s, c, "a sealed code that cannot be read")
        assertNull("a code was shown", c.sharing.recoveryCode())
        deletionRefused(s, c, "a sealed code that cannot be read")
        assertTrue(codeFile(s.at).delete())
        assertTrue(aside.renameTo(codeFile(s.at)))
        allAsBefore(s, c)
    }

    @Test
    fun aSealedCodeThatDoesNotOpenIsNotReplaced() {
        val s = sharedOnce()
        val damaged = s.codeBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        codeFile(s.at).writeBytes(damaged)
        val c = cloud(s.at)
        nothingRecorded(s, c, "a sealed code that does not open")
        val shown = try { c.sharing.recoveryCode() } catch (x: Exception) { "threw ${x.javaClass.simpleName}" }
        assertNull("a code was shown", shown)
        deletionRefused(s, c, "a sealed code that does not open")
        assertArrayEquals("the sealed code was written over", damaged, codeFile(s.at).readBytes())
        codeFile(s.at).writeBytes(s.codeBytes)
        allAsBefore(s, c)
    }

    /** The box's own key is gone: the sealed code cannot be opened any more, and no box key is made by reading it. */
    @Test
    fun aSealingKeyThatIsGoneIsNotMadeByAReadOfTheCode() {
        val s = sharedOnce()
        assertEquals(1, keystore.made[BOX_ALIAS])
        keystore.lose(BOX_ALIAS)
        val c = cloud(s.at)
        nothingRecorded(s, c, "the sealing key gone")
        val shown = try { c.sharing.recoveryCode() } catch (x: Exception) { "threw ${x.javaClass.simpleName}" }
        assertNull("a code was shown", shown)
        assertEquals("a box key was made by a read", 1, keystore.made[BOX_ALIAS])
        assertFalse(keystore.has(BOX_ALIAS))
        assertArrayEquals("the sealed code's bytes", s.codeBytes, codeFile(s.at).readBytes())
    }

    @Test
    fun aCodeFileGoneWhileTheKeyIsThereMakesNoNewCode() {
        val s = sharedOnce()
        assertTrue(codeFile(s.at).delete())
        val c = cloud(s.at)
        nothingRecorded(s, c, "the code file gone")
        assertFalse("a new code was made", codeFile(s.at).exists())
        assertNull(c.sharing.recoveryCode())
        deletionRefused(s, c, "the code file gone")
        assertFalse("a new code was made", codeFile(s.at).exists())
    }

    // ---- the callers ----

    @Test
    fun whileTheKeyCannotBeReadTheUploadPassSendsNothing() {
        val at = storage()
        val c = cloud(at)
        take(c)
        val id = idFile(at).readText()
        keystore.fail[alias(id)] = FakeAndroidKeyStore.Fault.SYSTEM
        val from = seen()
        upload(c)
        assertEquals("the pass sent", emptyList<String>(), sharingRequests(from))
        keystore.fail.remove(alias(id))
        upload(c)
        assertEquals(1, uploaded(c))
    }

    @Test
    fun whileTheCodeCannotBeReadNoSummaryIsAsked() {
        val s = sharedOnce()
        codeFile(s.at).writeBytes(s.codeBytes.copyOf(5))
        val c = cloud(s.at)
        val from = seen()
        assertNull(c.sharing.summary())
        val asked = synchronized(stub.seen) { stub.seen.drop(from).toList() }.filter { it.path == "/v1/contributions/summary" }
        assertEquals("the summary was asked", 0, asked.size)
    }

    // ---- the controls: what was right in 3.1.3 stays so ----

    @Test
    fun aDeviceWithNoneMakesThemAtTheFirstShare() {
        val at = storage()
        val c = cloud(at)
        assertFalse(idFile(at).exists())
        take(c)
        upload(c)
        assertEquals(1, uploaded(c))
        val start = stub.starts().single().json!!
        val id = idFile(at).readText()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(id))
        assertEquals(id, start["contributor"])
        assertEquals(1, keystore.madeUnder("quranlab.contributor."))
        val code = c.sharing.recoveryCode()!!
        assertEquals(ContributorKeys.recoveryHash(ContributorKeys.normaliseRecoveryCode(code)!!), start["recovery_hash"])
    }

    @Test
    fun afterAnAcceptedDeletionNewOnesAreMade() {
        val s = sharedOnce()
        val c = cloud(s.at)
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertEquals(1, stub.deletions)
        c.sharing.setOn(true)
        take(c)
        upload(c)
        val start = stub.starts().last().json!!
        assertNotEquals("the id after the deletion", s.id, start["contributor"])
        assertNotEquals("the device key after the deletion", s.point, start["contributor_key"])
        assertEquals("the start under the new contributor was not accepted", 1, uploaded(c))
    }

    /**
     * The first share is stopped between the device key and the recovery code (the code cannot be sealed once): nothing
     * was sent with that key, and the next take makes the pair and shares.
     */
    @Test
    fun aFirstShareCutBetweenTheKeyAndTheCodeSharesAtTheNextTake() {
        val at = storage()
        val inner = keystoreBox(keystore)
        var refuse = 1
        val box = object : SecretBox {
            override fun seal(plain: ByteArray): ByteArray {
                if (refuse-- > 0) throw IllegalStateException("the process ended here")
                return inner.seal(plain)
            }
            override fun open(sealed: ByteArray): ByteArray = inner.open(sealed)
        }
        val c = cloud(at, box)
        take(c)
        assertEquals("a recording began without a code", 0, c.sharing.items.size)
        take(c)
        upload(c)
        assertEquals("the next take did not share", 1, uploaded(c))
        val start = stub.starts().single().json!!
        assertEquals(idFile(at).readText(), start["contributor"])
        assertEquals(ContributorKeys.recoveryHash(ContributorKeys.normaliseRecoveryCode(c.sharing.recoveryCode()!!)!!), start["recovery_hash"])
    }
}
