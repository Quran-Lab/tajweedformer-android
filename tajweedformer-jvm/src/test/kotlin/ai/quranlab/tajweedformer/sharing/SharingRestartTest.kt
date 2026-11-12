package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * A recording made under an earlier consent is never uploaded, whatever happened in between (SPEC 11.7, R5). The
 * situations and what is asserted in each are the `restart_cases` of the shared file `vectors30/sharing_off.json`
 * (the contract's, byte for byte): nine recordings wait in the queue, the learner turns sharing off or deletes what
 * they shared, and from the moment of the call every deletion of a recording fails (the outbox's switch for tests,
 * [ContributionOutbox.deleteForTests]): what a storage that refuses leaves, and, where the case says the process
 * ends, what a kill at the first deletion leaves. A new client on the same folder is the next launch. The keys:
 *
 * - `off_is_on_disk_first`: the new process finds sharing off (and the saved state said so when the first deletion
 *   was tried): the change of consent was written before the queue was touched;
 * - `earlier_consent_never_uploaded`: no start, URLs, PUT or take-list request for any of the nine, in any later
 *   pass of this process or of a new one;
 * - `earlier_consent_deleted`: they are gone from the device at the next launch and before the next upload pass, as
 *   soon as deleting works again;
 * - `on_again_works`: a recording made after sharing came on again is uploaded, and it is the only one;
 * - `returns_within_bound`: the call is back within 3000 ms.
 *
 * What refuses the nine is on disk and on each recording: the count of consent changes saved with the consent, and
 * the count of its creation in every recording's folder. The outbox's own mark on a folder it could not delete is
 * taken away by the test after the call, so that nothing here rests on it.
 *
 * Rows of this SDK's own ([ANDROID]): one of the nine is a recording that was still open, its encoder not yet done
 * with the folder, when the process ended; and the process ends between the server's answer to a deletion and the
 * device's next write.
 *
 * Every case runs the file's `runs` (20) times (`TJ_REPEAT` sets another count, `TJ_CASES` picks cases by name).
 * A run reports every key that failed, not only the first.
 */
@RunWith(Parameterized::class)
class SharingRestartTest(private val name: String, private val run: Int) : OpenChecked() {
    internal class Case(val name: String, val action: String, val ends: Boolean, val then: String?, val keys: List<String>)

    /** Thrown where the process ends: nothing after that point runs, as after a kill. */
    private class Ended : Error("the process ended here")

    private lateinit var stub: StubQlab
    private lateinit var storage: File
    private var seed = 1L
    private val fails = ArrayList<String>()
    private val checked = HashSet<String>()
    private val dangling = ArrayList<Recording>()

    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        storage = Files.createTempDirectory("tj-share-restart").toFile()
    }

    @After fun down() {
        ContributionOutbox.deleteForTests = null
        // the recording a process that ended left open: its encoder's thread ends
        dangling.forEach { try { it.abandon() } catch (_: Exception) {} }
        if (::stub.isInitialized) stub.close()
        if (::storage.isInitialized) storage.deleteRecursively()
    }

    /** A process on the storage folder, through the SDK's launch sequence: the first, or the next launch. */
    private fun launch(): QuranLabCloud {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)))
        c.launch(foreground = false)
        return c
    }

    private fun take(c: QuranLabCloud, seconds: Int) {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = RATE, inputRoute = "built_in", decodeBackend = "mock"))
        repeat(seconds * 10) { c.onAudio(OggTestKit.noise(RATE / 10, seed = seed++), 0, RATE / 10, RATE) }
        stub.skewMs += seconds * 1000L
    }

    /** One closed recording of 2 s in the outbox, not sent. Its id. */
    private fun recording(c: QuranLabCloud): String {
        take(c, 2)
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))
        stub.skewMs += TakeJoin.DEFAULT_WINDOW_MS + 1
        c.sharing.checkJoinWindow()
        val capture = c.sharing.lastCapture ?: throw AssertionError("no recording was made")
        capture.await()
        if (c.sharing.outbox.item(capture.id) == null) throw AssertionError("the recording ${capture.id} is not in the outbox")
        return capture.id
    }

    private fun seen(): List<StubQlab.Seen> = synchronized(stub.seen) { stub.seen.toList() }

    private fun upload(s: StubQlab.Seen) = s.method == "PUT" || (s.method == "POST" && s.path.startsWith("/v1/uploads"))

    private fun about(s: StubQlab.Seen, ids: List<String>): String? = ids.firstOrNull { it in s.path || s.headers["x-qlab-id"] == it }

    /** The requests of an upload (start, URLs, PUT, take list) that are about one of [ids], from request [from] on. */
    private fun uploads(ids: List<String>, from: Int): List<StubQlab.Seen> = seen().drop(from).filter { upload(it) && about(it, ids) != null }

    private fun say(list: List<StubQlab.Seen>, ids: List<String>) =
        "${list.mapNotNull { about(it, ids) }.toSet().size} of ${ids.size} recordings: ${list.size} requests (${list.count { it.method == "POST" && it.path == "/v1/uploads" }} starts, " +
            "${list.count { it.method == "PUT" }} PUTs, ${list.filter { it.method == "PUT" }.sumOf { it.body.size.toLong() }} bytes of audio)"

    /** The folders of [ids] that are still on disk, with how many files each holds. */
    private fun left(c: QuranLabCloud, ids: List<String>): List<String> =
        ids.map { File(c.sharing.outbox.root, it) }.filter { it.exists() }.map { "${it.name.take(6)} (${it.list()?.size ?: 0} files)" }

    private fun check(key: String, ok: Boolean, why: () -> String) {
        checked += key
        if (!ok) fails += "$key: ${why()}"
    }

    private fun passes(c: QuranLabCloud) = repeat(3) { c.sharing.outbox.drain(ignoringBackoff = true) }

    private fun uploaded(c: QuranLabCloud, id: String) = c.sharing.outbox.item(id)?.status == Contribution.Status.UPLOADED && stub.segments(id).isNotEmpty()

    /** The learner's answer as it is saved on the device now. */
    private fun answerOnDisk(): Any? = ShareFiles.readJson(File(File(storage, "sharing"), "state.json"))?.get("consent")

    @Test
    fun theCase() {
        val case = ALL.first { it.name == name }
        val first = launch()
        first.sharing.accept()
        val deletion = case.action.startsWith("delete")
        // for a deletion: one recording is shared already (the server knows the contributor), nine wait
        val shared = if (deletion) listOf(recording(first)) else emptyList()
        if (deletion) {
            passes(first)
            if (!uploaded(first, shared[0])) throw AssertionError("${case.name}: the first recording was not uploaded: ${first.sharing.outbox.item(shared[0])}")
        }
        val who = first.sharing.contributor.id
        val ids = ArrayList<String>()
        repeat(if (case.action == "off_encoder") 8 else 9) { ids += recording(first) }
        if (case.action == "off_encoder") {
            // the ninth is still open: a closed segment in the outbox, the encoder at work on the next
            take(first, 12)
            first.sharing.syncCapture()
            val live = first.sharing.recorder.current as ShareCapture
            dangling += live
            if (first.sharing.outbox.item(live.id) == null) throw AssertionError("${case.name}: the open recording is not in the outbox")
            ids += live.id
        }
        if (left(first, ids).size != 9) throw AssertionError("${case.name}: the nine recordings are not all in the outbox: ${left(first, ids)}")
        val root = first.sharing.outbox.root
        val n0 = seen().size

        // from the moment of the call every deletion of a recording fails; where the process ends, it ends at the first
        val attempts = AtomicInteger()
        var offAtTheFirstDeletion: Boolean? = null
        ContributionOutbox.deleteForTests = { _ ->
            if (attempts.getAndIncrement() == 0) offAtTheFirstDeletion = answerOnDisk() != ConsentState.Consent.GRANTED.key
            if (case.ends) throw Ended()
            false
        }
        when (case.action) {
            // the process ends while the server is asked: the request never leaves
            "delete_unanswered" -> stub.offlineFor = { r -> if (r.url.endsWith("/v1/contributions/challenge")) throw Ended() else false }
            // the server took the deletion; the process ends when its answer arrives
            "delete_answered" -> stub.afterSend = { r, _ -> if (r.url.endsWith("/v1/contributions/delete")) throw Ended() }
        }
        var ended = false
        var thrown: Throwable? = null
        val t0 = System.nanoTime()
        try {
            when (case.action) {
                "off" -> first.sharing.setOn(false)
                "off_encoder" -> {
                    // the no is written, and the process ends before the open recording's encoder is done with its
                    // folder: the folder stays as it is, and nothing holds or writes it any more. In one JVM that
                    // takes a copy: the encoder is ended (which closes its file and removes its folder), and the
                    // folder is put back as the process left it.
                    first.sharing.state.setFromSettings(false, first.sharing.copy.consentVersion)
                    val live = dangling.single() as ShareCapture
                    val asLeft = File(storage, "as-the-process-left-it")
                    live.folder.copyRecursively(asLeft)
                    live.abandon()
                    live.await()
                    live.folder.deleteRecursively()
                    asLeft.copyRecursively(live.folder)
                    asLeft.deleteRecursively()
                    ended = true
                }
                else -> first.sharing.deleteEverything()
            }
        } catch (e: Ended) {
            ended = true
        } catch (e: Throwable) {
            thrown = e
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        stub.offlineFor = null
        stub.afterSend = null
        // nothing below rests on the outbox's mark on a folder it could not delete
        for (id in ids + shared) File(File(root, id), ContributionOutbox.GONE).delete()

        if ("returns_within_bound" in case.keys) check("returns_within_bound", thrown == null && ms <= BOUND_MS) { "the call took $ms ms${thrown?.let { ", threw $it" } ?: ""}" }
        if (case.ends && !ended) throw AssertionError("${case.name}: the call never came to the point where the process ends${thrown?.let { " (it threw $it)" } ?: ""}")
        if (case.action == "delete_answered" && stub.deletions != 1) throw AssertionError("${case.name}: the server did not take the deletion")

        // the next launch. The storage still refuses where the learner turns sharing on at once; else deleting works.
        ContributionOutbox.deleteForTests = if (case.then == "on") { _ -> false } else null
        val c = if (case.ends) launch() else first
        if (case.ends) {
            check("off_is_on_disk_first", !c.sharing.isOn && (case.action != "off" || offAtTheFirstDeletion == true)) {
                "sharing is ${if (c.sharing.isOn) "ON" else "off"} at the next launch" +
                    if (case.action == "off") "; the saved answer was a no when the first deletion was tried: $offAtTheFirstDeletion" else ""
            }
        }
        if (case.then == null) {
            // right after the launch, before any upload pass was asked for
            check("earlier_consent_deleted", left(c, ids).isEmpty()) { "${left(c, ids).size} of 9 still on disk right after the launch: ${left(c, ids)}" }
            passes(c)
            check("earlier_consent_never_uploaded", uploads(ids, n0).isEmpty()) { say(uploads(ids, n0), ids) }
            val later = launch()
            passes(later)
            check("earlier_consent_never_uploaded", uploads(ids, n0).isEmpty()) { "in a later launch: " + say(uploads(ids, n0), ids) }
        } else {
            // the learner turns sharing on again, while the nine still cannot be deleted
            c.sharing.setOn(true)
            val fresh = arrayListOf(recording(c))
            passes(c)
            if (case.action == "delete_answered" && !uploaded(c, fresh[0])) {
                // the server deleted the contributor and the device could not know: the first new upload is refused,
                // the SDK takes a new contributor, and the next recording goes up
                fresh += recording(c)
                passes(c)
            }
            val others = seen().drop(n0).filter { upload(it) && about(it, fresh) == null }
            check("on_again_works", c.sharing.isOn && uploaded(c, fresh.last()) && others.isEmpty()) {
                "sharing on: ${c.sharing.isOn}; the recording made after it: ${c.sharing.outbox.item(fresh.last())?.status}; other upload requests: ${others.size}"
            }
            check("earlier_consent_never_uploaded", uploads(ids, n0).isEmpty()) { say(uploads(ids, n0), ids) }
            // deleting works again: gone before the next upload pass (odd runs), or at the next launch (even runs)
            ContributionOutbox.deleteForTests = null
            if (run % 2 == 1) {
                passes(c)
                check("earlier_consent_deleted", left(c, ids).isEmpty()) { "${left(c, ids).size} of 9 still on disk after a pass with deleting at work: ${left(c, ids)}" }
            } else {
                val next = launch()
                check("earlier_consent_deleted", left(next, ids).isEmpty()) { "${left(next, ids).size} of 9 still on disk right after the next launch: ${left(next, ids)}" }
            }
            val later = launch()
            passes(later)
            check("earlier_consent_never_uploaded", uploads(ids, n0).isEmpty()) { "in a later launch: " + say(uploads(ids, n0), ids) }
            check("earlier_consent_deleted", left(later, ids).isEmpty() && later.sharing.items.none { it.id in ids }) {
                "${left(later, ids).size} of 9 on disk, ${later.sharing.items.count { it.id in ids }} listed, in a later launch"
            }
            if (case.action == "delete") {
                // this SDK's own look: a deletion the device knows was accepted leaves no status of what was shared,
                // and a new contributor (where the process ended before the device knew, the status of the shared
                // recording stays until the learner deletes again)
                check("nothing_of_what_was_shared_stays", left(later, shared).isEmpty() && later.sharing.contributor.id != who) {
                    "the status of the shared recording on disk: ${left(later, shared)}; the contributor is the deleted one: ${later.sharing.contributor.id == who}"
                }
            }
        }
        for (k in case.keys) if (k !in checked) fails += "$k: this test does not check the key"
        if (fails.isNotEmpty()) throw AssertionError(case.name + "\n" + fails.joinToString("\n"))
    }

    companion object {
        private const val RATE = 16_000

        @Suppress("UNCHECKED_CAST")
        private val VECTOR: Map<String, Any?> =
            Json.parse(SharingRestartTest::class.java.getResourceAsStream("/vectors30/sharing_off.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

        private val BOUND_MS = (VECTOR["off_bound_ms"] as Double).toLong()

        @Suppress("UNCHECKED_CAST")
        private val SHARED: List<Case> = (VECTOR["restart_cases"] as List<Map<String, Any?>>).map { c ->
            if ((c["left"] as Double).toInt() != 9) throw AssertionError("${c["name"]}: this test builds nine recordings")
            Case(c["name"] as String, c["action"] as String, c["ends"] as Boolean, c["then"] as String?, (c["assert"] as List<String>).map { it.substringBefore(':') })
        }

        private val KEYS = listOf("off_is_on_disk_first", "earlier_consent_never_uploaded", "earlier_consent_deleted", "on_again_works")

        /** This SDK's own rows (not in the shared file), with the shared keys. */
        internal val ANDROID = listOf(
            Case("android: eight left and one still its encoder's by off, the process ends, on again", "off_encoder", true, "on", KEYS),
            Case("android: delete, the process ends when the server's answer arrives, on again", "delete_answered", true, "on", KEYS),
        )

        internal val ALL = SHARED + ANDROID

        @JvmStatic
        @Parameterized.Parameters(name = "{0} [{1}]")
        fun runs(): List<Array<Any>> {
            val n = TestKit.env("TJ_REPEAT")?.toIntOrNull() ?: (VECTOR["runs"] as Double).toInt()
            val only = TestKit.env("TJ_CASES")
            return ALL.filter { only == null || only in it.name }.flatMap { c -> (1..n).map { arrayOf<Any>(c.name, it) } }
        }
    }
}
