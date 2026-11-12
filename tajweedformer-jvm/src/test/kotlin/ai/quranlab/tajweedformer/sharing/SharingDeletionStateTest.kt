package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What "delete what I shared" writes to the device, and when (SPEC 11.7, R5: the change of consent is on the device
 * before anything else is done about it). While the server is asked the saved state already says no, one count on,
 * and this process goes on as it was; a refusal writes the earlier state back.
 *
 * And when the device cannot store the off (a full disk, a storage that refuses the write; SPEC 11.7, "When the
 * device cannot store the off"): the server is asked all the same. Accepted: sharing is off in this process, the
 * queue is deleted, and the off is stored by the write after the queue's delete, or by the next write that
 * succeeds. Refused: nothing changed, and no later write stores an off nobody gave.
 *
 * The same for the settings switch: the state in force is the one in this process, never the file (the outbox
 * compares a recording's count with the state in memory); a write that fails is not thrown at the learner's tap and
 * is tried again after the queue's delete and at every later change of the state.
 */
class SharingDeletionStateTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var storage: File
    private var seed = 1L

    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        storage = Files.createTempDirectory("tj-share-delete").toFile()
    }

    @After fun down() {
        ContributionOutbox.deleteForTests = null
        if (::stub.isInitialized) stub.close()
        if (::storage.isInitialized) storage.deleteRecursively()
    }

    private fun cloud(): QuranLabCloud {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)))
        c.refreshConfig()
        return c
    }

    /** One closed recording of 2 s in the outbox. Its id. */
    private fun recording(c: QuranLabCloud): String {
        val rate = 16_000
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = rate, inputRoute = "built_in", decodeBackend = "mock"))
        repeat(20) { c.onAudio(OggTestKit.noise(rate / 10, seed = seed++), 0, rate / 10, rate) }
        stub.skewMs += 2_000
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))
        stub.skewMs += TakeJoin.DEFAULT_WINDOW_MS + 1
        c.sharing.checkJoinWindow()
        val capture = c.sharing.lastCapture!!
        capture.await()
        assertTrue(c.sharing.outbox.item(capture.id) != null)
        return capture.id
    }

    private fun stateFile() = File(File(storage, "sharing"), "state.json")

    private fun onDisk(): Map<String, Any?> = ShareFiles.readJson(stateFile())!!

    private fun count(o: Map<String, Any?>) = (o["changes"] as Double).toInt()

    /** The learner said yes; one recording is shared, one waits. The ids. */
    private fun oneSharedOneWaiting(c: QuranLabCloud): Pair<String, String> {
        c.sharing.accept()
        val shared = recording(c)
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals(Contribution.Status.UPLOADED, c.sharing.outbox.item(shared)!!.status)
        return shared to recording(c)
    }

    /** The state cannot be written: the file it is written through is taken by a folder. Removing it lets writes through again. */
    private fun theStateCannotBeWritten(): File {
        val inTheWay = File(stateFile().parentFile, stateFile().name + ".tmp")
        assertTrue(inTheWay.mkdir())
        return inTheWay
    }

    private fun asked(from: Int): List<String> = synchronized(stub.seen) { stub.seen.drop(from).map { it.path } }.filter { it.startsWith("/v1/contributions/") }

    private fun uploadsOf(id: String, from: Int): Int = synchronized(stub.seen) { stub.seen.drop(from).toList() }.count { s ->
        (s.method == "PUT" || (s.method == "POST" && s.path.startsWith("/v1/uploads"))) && (id in s.path || s.headers["x-qlab-id"] == id)
    }

    @Test
    fun whileTheServerIsAskedTheSavedStateSaysNoAndThisProcessGoesOn() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val before = onDisk()
        assertEquals("granted", before["consent"])
        val n = count(before)
        var during: Map<String, Any?>? = null
        var afterAWrite: Map<String, Any?>? = null
        var onInProcess: Boolean? = null
        // the server is not reached: the look at the device is taken at the moment the first request would leave
        stub.offlineFor = { r ->
            if (r.url.endsWith("/v1/contributions/challenge")) {
                during = onDisk()
                onInProcess = c.sharing.isOn
                // any other write of the state during the ask keeps saying no
                c.sharing.state.sessionCompleted(feedback = false, enabled = false)
                afterAWrite = onDisk()
                true
            } else false
        }
        assertEquals(Sharing.Deletion.OFFLINE, c.sharing.deleteEverything())
        stub.offlineFor = null
        assertEquals("the saved answer while the server is asked", "final", during!!["consent"])
        assertEquals("the saved count while the server is asked", n + 1, count(during!!))
        assertEquals("the saved answer after another write during the ask", "final", afterAWrite!!["consent"])
        assertEquals(n + 1, count(afterAWrite!!))
        assertEquals("for the learner and the app nothing changes during the ask", true, onInProcess)
        // not taken: the earlier state is back on the device, sharing is on, the waiting recording is still there
        val after = onDisk()
        assertEquals("granted", after["consent"])
        assertEquals(n, count(after))
        assertEquals(before["decidedAt"], after["decidedAt"])
        assertEquals(before["consentVersion"], after["consentVersion"])
        assertTrue(c.sharing.isOn)
        assertTrue(File(c.sharing.outbox.root, waiting).isDirectory)
        // and a new process reads it so
        val next = cloud()
        assertTrue(next.sharing.isOn)
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("the queue went on", Contribution.Status.UPLOADED, next.sharing.outbox.item(waiting)!!.status)
    }

    /** (a) The off cannot be stored, the server accepts: the write after the queue's delete stores it. */
    @Test
    fun theOffCannotBeStoredTheServerAcceptsAndTheWriteAfterTheQueuesDeleteStoresIt() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val who = c.sharing.contributor.id
        val n = count(onDisk())
        val inTheWay = theStateCannotBeWritten()
        val requests = stub.seen.size
        // the storage takes writes again once the queue's files go (a full disk with room again)
        var stateWhenTheQueueWent: Any? = null
        ContributionOutbox.deleteForTests = { folder ->
            if (inTheWay.exists()) {
                stateWhenTheQueueWent = onDisk()["consent"]
                assertTrue(inTheWay.delete())
            }
            folder.deleteRecursively()
        }
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertEquals("the server was asked", listOf("/v1/contributions/challenge", "/v1/contributions/delete"), asked(requests))
        assertEquals(1, stub.deletions)
        assertEquals("the off was not on the device when the queue's delete began (its writes had failed)", "granted", stateWhenTheQueueWent)
        assertFalse("sharing is off in this process", c.sharing.isOn)
        assertTrue("the queue and what was shared are gone", (c.sharing.outbox.root.listFiles() ?: emptyArray()).isEmpty())
        assertEquals("the write after the queue's delete stored the off", "final", onDisk()["consent"])
        assertEquals(n + 1, count(onDisk()))
        assertEquals(false, onDisk()["deletionUnfinished"])
        assertNotEquals(who, c.sharing.contributor.id)
        // a new process reads off, and nothing of the queue is sent
        val next = cloud()
        assertFalse(next.sharing.isOn)
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals(0, uploadsOf(waiting, requests))
    }

    /** (b) The off cannot be stored before or after the queue's delete: off in this process, stored by the next write that succeeds. */
    @Test
    fun theOffCannotBeStoredAtAllDuringAnAcceptedDeletionAndTheNextWriteStoresIt() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val n = count(onDisk())
        val inTheWay = theStateCannotBeWritten()
        val requests = stub.seen.size
        assertEquals("a failed write does not refuse the deletion or throw out of it", Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertEquals(listOf("/v1/contributions/challenge", "/v1/contributions/delete"), asked(requests))
        assertEquals(1, stub.deletions)
        assertFalse("sharing is off in this process", c.sharing.isOn)
        assertEquals(ConsentState.Consent.FINAL, c.sharing.state.consent)
        assertEquals("the count is one on in this process", n + 1, c.sharing.state.changes)
        assertTrue("the queue and what was shared are gone", (c.sharing.outbox.root.listFiles() ?: emptyArray()).isEmpty())
        assertEquals("the device still has the earlier answer: no write got through", "granted", onDisk()["consent"])
        // nothing of the queue is uploaded by this process
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals(0, uploadsOf(waiting, requests))
        // the next write of the state that succeeds stores the off
        assertTrue(inTheWay.delete())
        c.sharing.state.sessionCompleted(feedback = false, enabled = false)
        assertEquals("final", onDisk()["consent"])
        assertEquals(n + 1, count(onDisk()))
        assertEquals(false, onDisk()["deletionUnfinished"])
        assertFalse(cloud().sharing.isOn)
    }

    /**
     * The switch, while the device can store nothing: the off is in force in this process (the recording of before is
     * not uploaded, also while it cannot be deleted), sharing comes on again in force too (a recording made then
     * carries the count in force, is uploaded and is not swept), and what the device gets when it can store again is
     * the state in force.
     */
    @Test
    fun theSwitchWhileTheDeviceCannotStoreTheState() {
        val c = cloud()
        val (shared, waiting) = oneSharedOneWaiting(c)
        val n = count(onDisk())
        val root = c.sharing.outbox.root
        val inTheWay = theStateCannotBeWritten()
        val requests = stub.seen.size
        // the queue cannot be deleted either
        ContributionOutbox.deleteForTests = { _ -> false }
        c.sharing.setOn(false)
        assertFalse("the off is in force in this process", c.sharing.isOn)
        assertEquals(n + 1, c.sharing.state.changes)
        assertTrue(c.sharing.state.unsaved)
        assertEquals("the device still has the earlier answer", "granted", onDisk()["consent"])
        assertEquals(n, count(onDisk()))
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("the recording of before the off is not uploaded", 0, uploadsOf(waiting, requests))

        // on again, still nothing can be stored: the count in force is two on, the file's is not
        c.sharing.setOn(true)
        assertTrue(c.sharing.isOn)
        assertEquals(n + 2, c.sharing.state.changes)
        assertEquals(n, count(onDisk()))
        val fresh = recording(c)
        assertEquals("the new recording carries the count in force", n + 2, ShareFiles.stamp(File(root, fresh)))
        repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("a recording made after sharing came on is uploaded, not swept", Contribution.Status.UPLOADED, c.sharing.outbox.item(fresh)?.status)
        assertTrue(stub.segments(fresh).isNotEmpty())
        assertEquals("the recording of before the off is still not uploaded", 0, uploadsOf(waiting, requests))
        assertTrue("it is still on the device: it cannot be deleted", File(root, waiting).exists())

        // deleting works again: it goes before the next pass uploads anything
        ContributionOutbox.deleteForTests = null
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertFalse(File(root, waiting).exists())
        assertEquals(0, uploadsOf(waiting, requests))
        assertEquals("the status of what was shared before a plain off stays", Contribution.Status.UPLOADED, c.sharing.outbox.item(shared)?.status)

        // the device can store again: the next change of the state stores the state in force
        assertTrue(inTheWay.delete())
        c.sharing.state.sessionCompleted(feedback = false, enabled = false)
        assertFalse(c.sharing.state.unsaved)
        assertEquals("granted", onDisk()["consent"])
        assertEquals(n + 2, count(onDisk()))
        val next = cloud()
        assertTrue(next.sharing.isOn)
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals(Contribution.Status.UPLOADED, next.sharing.outbox.item(fresh)?.status)
        assertEquals(0, uploadsOf(waiting, requests))
    }

    /** The switch's off cannot be stored until the queue's files are gone: it is stored right after the queue's delete. */
    @Test
    fun anOffTheDeviceCouldNotStoreIsStoredRightAfterTheQueueIsDeleted() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val n = count(onDisk())
        val inTheWay = theStateCannotBeWritten()
        var stateWhenTheQueueWent: Any? = null
        ContributionOutbox.deleteForTests = { folder ->
            if (inTheWay.exists()) {
                stateWhenTheQueueWent = onDisk()["consent"]
                assertTrue(inTheWay.delete())
            }
            folder.deleteRecursively()
        }
        c.sharing.setOn(false)
        assertEquals("the off was not on the device when the queue's delete began", "granted", stateWhenTheQueueWent)
        assertFalse(c.sharing.isOn)
        assertFalse(File(c.sharing.outbox.root, waiting).exists())
        assertFalse(c.sharing.state.unsaved)
        assertEquals("final", onDisk()["consent"])
        assertEquals(n + 1, count(onDisk()))
        assertFalse(cloud().sharing.isOn)
    }

    /**
     * The remainder SPEC 11.7 accepts: the off was stored before the server was asked, the server refuses, the earlier
     * state cannot be written back, and the process ends. The next launch finds sharing off, deletes the queue, and
     * uploads nothing. (In the process that went on, sharing was still on, as after any refusal.)
     */
    @Test
    fun theServerRefusesTheWriteBackFailsAndTheProcessEnds() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val n = count(onDisk())
        val requests = stub.seen.size
        // the server refuses, and from its answer on the state cannot be written
        var inTheWay: File? = null
        stub.overrides["/v1/contributions/delete"] = { _, _ ->
            inTheWay = theStateCannotBeWritten()
            StubQlab.Reply.error(500, "internal")
        }
        assertEquals(Sharing.Deletion.REFUSED, c.sharing.deleteEverything())
        stub.overrides.remove("/v1/contributions/delete")
        assertTrue("in the process that goes on, sharing is still on", c.sharing.isOn)
        assertTrue(c.sharing.state.unsaved)
        assertEquals("the device still has the off that was stored before the server was asked", "final", onDisk()["consent"])
        assertEquals(n + 1, count(onDisk()))
        // the process ends here; the next launch can write again
        assertTrue(inTheWay!!.delete())
        val next = cloud()
        assertFalse("the next launch finds sharing off", next.sharing.isOn)
        next.launch(foreground = false)
        assertFalse("the queue is deleted at the launch", File(next.sharing.outbox.root, waiting).exists())
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("never an upload", 0, uploadsOf(waiting, requests))
        // the learner is not asked again, and turns sharing on again with the switch
        assertEquals("the learner is not asked again", null, next.sharing.askNow())
        next.sharing.setOn(true)
        assertTrue(next.sharing.isOn)
        val fresh = recording(next)
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals("a recording made after the switch came on is uploaded", Contribution.Status.UPLOADED, next.sharing.outbox.item(fresh)?.status)
        assertEquals("never an upload of the one that waited", 0, uploadsOf(waiting, requests))
    }

    /** (c), (d) The off cannot be stored and the server refuses: nothing changed, and no later write stores an off nobody gave. */
    @Test
    fun theOffCannotBeStoredAndTheServerRefusesNothingChangedAndNoLaterWriteStoresAnOff() {
        val c = cloud()
        val (_, waiting) = oneSharedOneWaiting(c)
        val who = c.sharing.contributor.id
        val before = onDisk()
        val n = count(before)
        val inTheWay = theStateCannotBeWritten()
        val requests = stub.seen.size
        stub.overrides["/v1/contributions/delete"] = { _, _ -> StubQlab.Reply.error(500, "internal") }
        assertEquals(Sharing.Deletion.REFUSED, c.sharing.deleteEverything())
        stub.overrides.remove("/v1/contributions/delete")
        assertEquals("the server was asked", listOf("/v1/contributions/challenge", "/v1/contributions/delete"), asked(requests))
        assertEquals(0, stub.deletions)
        assertTrue("sharing is still on", c.sharing.isOn)
        assertEquals(n, c.sharing.state.changes)
        assertEquals(who, c.sharing.contributor.id)
        assertTrue("nothing on the device was deleted", File(c.sharing.outbox.root, waiting).isDirectory)
        assertEquals("the device has the earlier state", before, onDisk())
        // writing works again: what is written next is not an off (nothing of the failed write is left in memory)
        assertTrue(inTheWay.delete())
        c.sharing.state.sessionCompleted(feedback = false, enabled = false)
        assertEquals("granted", onDisk()["consent"])
        assertEquals(n, count(onDisk()))
        // a new process reads the earlier state, and the queue goes on
        val next = cloud()
        assertTrue(next.sharing.isOn)
        repeat(3) { next.sharing.outbox.drain(ignoringBackoff = true) }
        assertEquals(Contribution.Status.UPLOADED, next.sharing.outbox.item(waiting)!!.status)
    }
}
