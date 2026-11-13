package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.CloudScheduler
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Sharing is turned off, or what was shared is deleted, WHILE an upload is in flight (SPEC 11.7, SDK 3.0.1). The
 * situations and what is asserted in each are the shared file `vectors30/sharing_off.json` (the contract's, byte for
 * byte): a recording is being uploaded, the stub server HOLDS one request of the pass (a segment PUT, the upload's
 * start, a batch of URLs, the take list) so that it never answers by itself, the learner turns sharing off or
 * deletes what they shared, and where the case says so turns sharing on again at once. The assertions carry the
 * file's keys, so the four SDKs' reports line up:
 *
 * - `returns_within_bound`: the call is back within 3000 ms and does not throw;
 * - `no_request_after`: no start, URLs, PUT or take-list request starts after the call was made;
 * - `no_audio_after`: at the test's stub no audio arrives after the call, neither in a PUT that starts later nor
 *   as the rest of a PUT that was in flight (the request in flight is ended, not finished; what a real system
 *   had already taken into its send buffer is outside this test);
 * - `nothing_unsent_on_device`: when the call returns the queue is empty ON DISK (the outbox's memory is not asked);
 * - `still_nothing_after_release`: the held request is let go: still empty, still nothing sent;
 * - an accepted delete: `queue_stopped_before_the_server_is_asked`, `sharing_is_off_after_an_accepted_delete`,
 *   `on_again_works` (the switch, then a new recording is uploaded under a new contributor);
 * - a delete the server does not take: `a_refused_delete_changes_nothing`, `queue_resumes`;
 * - on again at once: `on_again_works`, `the_old_recording_is_gone`.
 *
 * Rows of this SDK's own, with the same keys ([ANDROID]): a PUT whose body is still being SENT (the uploader has the
 * segment's file open: a system that does not delete an open file keeps it, and the rest of the segment must not go
 * out; the run prints how many bytes of it had left the device when the request was ended); a recording that is
 * still OPEN, its encoder busy, with closed segments waiting behind the one in flight (the off call must not return
 * before its folder is gone, and the next segment must not go out); a pass that begins while "delete what I shared"
 * is asking the server; and, with one key of this SDK's own (`request_in_flight_is_ended`), that the request in
 * flight is ENDED by the call: the upload task is back before the held request is let go.
 *
 * What stays of an UPLOADED recording is its status (the item, the end, the session JSON, the segments' markers);
 * a take list that was still to send is unsent and goes.
 *
 * A race passes most single runs: every case runs the file's `runs` (20) times (`TJ_REPEAT` sets another count;
 * `TJ_CASES` runs only the cases whose name contains its text).
 * A run reports every key that failed, not only the first.
 */
@RunWith(Parameterized::class)
class SharingOffTest(private val name: String, @Suppress("unused") private val run: Int) : OpenChecked() {
    internal class Case(val name: String, val held: String, val action: String, val then: String?, val keys: List<String>)

    /** This SDK's own key: the upload task is back within [ENDED_MS] of the call, the held request never answered. */
    private val ended = "request_in_flight_is_ended"

    /** The platform's job: runs a pass when the SDK asks for one, once the test lets it ([auto]). */
    private class Jobs : CloudScheduler {
        @Volatile var auto = false
        /** The passes the SDK asked for while [auto] was off (they were not run). */
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "test-uploads").apply { isDaemon = true } }
        override fun scheduleDelivery(cloud: QuranLabCloud) {}
        override fun scheduleUploads(cloud: QuranLabCloud, atMs: Long?) {
            if (atMs != null) return
            if (!auto) {
                asked.incrementAndGet()
                return
            }
            try { pool.execute { try { cloud.sharing.outbox.drain() } catch (_: Exception) {} } } catch (_: Exception) {}
        }
        /** Waits until the passes asked for so far are through. False when one still runs. */
        fun idle(ms: Long = 20_000): Boolean = try { pool.submit {}.get(ms, TimeUnit.MILLISECONDS); true } catch (_: Exception) { false }
    }

    private lateinit var stub: StubQlab
    private lateinit var storage: File
    private val jobs = Jobs()
    private val threads = ArrayList<Thread>()
    private var seed = 1L
    private val fails = ArrayList<String>()
    private val checked = HashSet<String>()

    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        storage = Files.createTempDirectory("tj-share-off").toFile()
    }

    @After fun down() {
        // whatever a failed run left held is let go, so its threads end and its files close
        if (::stub.isInitialized) stub.hold?.release?.countDown()
        threads.forEach { it.join(30_000) }
        jobs.pool.shutdown()
        jobs.pool.awaitTermination(30, TimeUnit.SECONDS)
        if (::stub.isInitialized) stub.close()
        if (::storage.isInitialized) storage.deleteRecursively()
    }

    private fun cloud(): QuranLabCloud {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)), scheduler = jobs)
        c.refreshConfig()
        if (!c.sharing.isOn) c.sharing.accept()
        return c
    }

    /** [seconds] of audio into the running take, in 100 ms pieces (or one piece: an app that hands over a long buffer). */
    private fun push(c: QuranLabCloud, seconds: Double, onePiece: Boolean = false) {
        val n = Math.round(RATE * seconds).toInt()
        var at = 0
        while (at < n) {
            val k = if (onePiece) n else minOf(RATE / 10, n - at)
            c.onAudio(OggTestKit.noise(k, seed = seed++), 0, k, RATE)
            at += k
        }
        stub.skewMs += Math.round(seconds * 1000)
    }

    private fun start(c: QuranLabCloud) =
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = RATE, inputRoute = "built_in", decodeBackend = "mock"))

    private fun end(c: QuranLabCloud) = c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))

    /**
     * One closed recording in the outbox, not sent yet: [takes] takes in a row of [seconds] of audio together (two
     * segments from 10 s on). Its id.
     */
    private fun recording(c: QuranLabCloud, seconds: Double, takes: Int = 1): String {
        repeat(takes) { i ->
            if (i > 0) stub.skewMs += 500
            start(c)
            push(c, seconds / takes)
            end(c)
        }
        stub.skewMs += TakeJoin.DEFAULT_WINDOW_MS + 1
        c.sharing.checkJoinWindow()
        val capture = c.sharing.lastCapture ?: throw AssertionError("no recording was made")
        capture.await()
        // the outbox picks it up now, so the recordings are sent in the order they were made
        if (c.sharing.outbox.item(capture.id) == null) throw AssertionError("the recording ${capture.id} is not in the outbox")
        return capture.id
    }

    private fun seen(): List<StubQlab.Seen> = synchronized(stub.seen) { stub.seen.toList() }

    /** Which request of an upload pass this is, or null (a token, the config, the deletion's own). */
    private fun kind(method: String, path: String): String? = when {
        method == "PUT" -> "put"
        method != "POST" -> null
        path == "/v1/uploads" -> "start"
        path.endsWith("/urls") -> "urls"
        path.endsWith("/takes") -> "takes"
        else -> null
    }

    private fun kind(s: StubQlab.Seen) = kind(s.method, s.path)

    /** The recording a request of an upload pass is about. */
    private fun idOf(s: StubQlab.Seen): String? = when (kind(s)) {
        "start" -> s.headers["x-qlab-id"]
        "put" -> Regex("/v1/([0-9a-f]{32})/seg-").find(s.path)?.groupValues?.get(1)
        "urls", "takes" -> s.path.removePrefix("/v1/uploads/").take(32)
        else -> null
    }

    private fun say(list: List<StubQlab.Seen>) = list.joinToString { "${kind(it)} of ${idOf(it)?.take(6)} (${it.body.size} bytes)" }

    /** Everything under the outbox's folder, as it is on disk now. */
    private fun onDisk(c: QuranLabCloud): List<String> {
        val root = c.sharing.outbox.root
        return root.walkBottomUp().filter { it != root }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    /**
     * What is on disk that is not the status of an uploaded recording: a recording or a segment still to send, a
     * state file of the queue, a take list that was never sent. Read from the disk, not from the outbox.
     */
    private fun unsent(c: QuranLabCloud): List<String> {
        val root = c.sharing.outbox.root
        val out = ArrayList<String>()
        for (f in root.listFiles() ?: emptyArray()) {
            val item = if (f.isDirectory) ShareFiles.readJson(File(f, ContributionOutbox.ITEM)) else null
            if (item == null || item["state"] != "uploaded") {
                out += f.name
                if (f.isDirectory) out += (f.listFiles() ?: emptyArray()).map { "${f.name}/${it.name}" }
                continue
            }
            if (item["takes"] == ContributionOutbox.TAKES_PENDING) out += "${f.name}: its take list still waits"
            for (x in f.listFiles() ?: emptyArray()) {
                val n = x.name
                val status = n == ContributionOutbox.ITEM || n == ShareFiles.END || n == ShareFiles.SESSION || (n.startsWith("seg-") && n.endsWith(".json"))
                if (!status) out += "${f.name}/$n"
            }
        }
        return out.sorted()
    }

    private fun check(key: String, ok: Boolean, why: () -> String) {
        checked += key
        if (!ok) fails += "$key: ${why()}"
    }

    private fun uploaded(c: QuranLabCloud, id: String) = c.sharing.outbox.item(id)?.status == Contribution.Status.UPLOADED

    /** Waits, at most [ms], until [done] says so. */
    private fun until(ms: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    /** A recording made now is uploaded in the same run by the passes the SDK itself asks for; its start request. */
    private fun aNewRecordingIsUploaded(c: QuranLabCloud): StubQlab.Seen? {
        val id = try { recording(c, 6.0) } catch (e: AssertionError) { return null }
        if (!until { uploaded(c, id) && stub.segments(id).isNotEmpty() }) return null
        return seen().firstOrNull { kind(it) == "start" && idOf(it) == id }
    }

    @Test
    fun theCase() {
        val case = ALL.first { it.name == name }
        if (case.held == "challenge") aPassBeginsWhileTheServerIsAsked(case) else aRequestHeld(case)
        for (k in case.keys) if (k !in checked) fails += "$k: this test does not check the key"
        if (fails.isNotEmpty()) throw AssertionError(case.name + "\n" + fails.joinToString("\n"))
    }

    private fun aRequestHeld(case: Case) {
        val bound = BOUND_MS
        val c = cloud()
        val old = ArrayList<String>()
        val open = case.held == "put_open"
        val body = case.held == "put_body"
        if (open) {
            // the take runs on: two segments are closed (10 s and 20 s), the third is being written
            start(c)
            push(c, 31.0)
            c.sharing.syncCapture()
            old += (c.sharing.recorder.current as ShareCapture).id
            if (c.sharing.outbox.item(old[0]) == null) throw AssertionError("the open recording is not in the outbox")
        } else {
            // the recording in flight (two segments), and one that waits behind it
            if (case.held == "urls") stub.batch = 1
            old += recording(c, 12.0, takes = if (case.held == "takes") 2 else 1)
            old += recording(c, 6.0)
            // a large segment on a slow network: the uploader is still sending it when the learner says no
            if (body) RandomAccessFile(File(File(c.sharing.outbox.root, old[0]), "seg-00001.opus"), "rw").use { it.setLength(LARGE) }
        }
        val which = if (open || body) "put" else case.held
        val hold = StubQlab.Hold(beforeBody = body) { m, p -> kind(m, p) == which }
        stub.hold = hold
        val task = thread(name = "test-upload") { c.sharing.outbox.drain(ignoringBackoff = true) }.also { threads += it }
        if (!hold.arrived.await(30, TimeUnit.SECONDS)) throw AssertionError("${case.name}: no ${case.held} request was made")
        // the body-held PUT: the client fills the connection's buffers and then waits in its write, the file open
        if (body) Thread.sleep(150)
        // the encoder has work when the learner says no (an app that hands over a long buffer)
        if (open) push(c, 5.0, onePiece = true)

        val n0 = seen().size
        val disk0 = onDisk(c)
        val contributor0 = c.sharing.contributor.id
        if (case.action == "delete_refused") stub.overrides["/v1/contributions/delete"] = { _, _ -> StubQlab.Reply.error(500, "internal") }
        // a delete the server does not take asks for a pass as its last act (the queue goes on). That pass is run after
        // the device was looked at: run at once, on its own thread, it can be in the middle of an item's write (a temp
        // file and a rename) at the moment of the look, which is the pass's work and not a change the delete made
        jobs.auto = case.action != "delete_refused"
        jobs.asked.set(0)
        var thrown: Throwable? = null
        var result: Sharing.Deletion? = null
        val t0 = System.nanoTime()
        try {
            if (case.action == "off") c.sharing.setOn(false) else result = c.sharing.deleteEverything()
        } catch (e: Throwable) {
            thrown = e
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val left = unsent(c)
        val all = onDisk(c)
        val asked = seen()

        val expected = when (case.action) {
            "off" -> null
            "delete" -> setOf(Sharing.Deletion.ACCEPTED)
            else -> setOf(Sharing.Deletion.REFUSED, Sharing.Deletion.OFFLINE)
        }
        check("returns_within_bound", thrown == null && ms <= bound && (expected == null || result in expected)) {
            "the call took $ms ms (bound $bound)" + (thrown?.let { ", threw $it" } ?: "") + (expected?.let { ", answered $result" } ?: "")
        }
        if (case.action != "off") {
            val at = asked.indexOfLast { it.path == "/v1/contributions/delete" }
            val between = if (at < 0) emptyList() else asked.subList(minOf(n0, at), at).filter { kind(it) != null }
            check("queue_stopped_before_the_server_is_asked", at >= 0 && between.isEmpty()) {
                if (at < 0) "the server was not asked" else "between the delete call and the server's answer: ${say(between)}"
            }
        }
        if (case.action == "delete_refused") {
            check("a_refused_delete_changes_nothing", c.sharing.isOn && all == disk0 && c.sharing.contributor.id == contributor0) {
                "sharing on: ${c.sharing.isOn}; on disk before ${disk0.size} files, after ${all.size}: gone ${disk0 - all.toSet()}, new ${all - disk0.toSet()}"
            }
            // the pass the SDK asked for when the server did not take the delete: now
            val askedForAPass = jobs.asked.get() > 0
            jobs.auto = true
            if (askedForAPass) jobs.scheduleUploads(c)
            hold.release.countDown()
            task.join(20_000)
            val up = until { uploaded(c, old[0]) && stub.segments(old[0]).size == 2 }
            check("queue_resumes", askedForAPass && up) {
                "the SDK asked for a pass: $askedForAPass; the recording that was in flight: ${c.sharing.outbox.item(old[0])}, ${stub.segments(old[0]).size} of 2 segments at the storage"
            }
            return
        }

        check("nothing_unsent_on_device", left.isEmpty() && (case.action == "off" || all.isEmpty())) {
            "on disk when the call returned: ${if (case.action == "off") left else all}"
        }
        if (case.action == "delete") {
            check("sharing_is_off_after_an_accepted_delete", !c.sharing.isOn && c.sharing.state.consent == ConsentState.Consent.FINAL && c.sharing.askNow() == null) {
                "sharing on: ${c.sharing.isOn}, consent ${c.sharing.state.consent}"
            }
        }
        if (case.then == "on") c.sharing.setOn(true)
        if (ended in case.keys) {
            task.join(ENDED_MS)
            check(ended, !task.isAlive) { "the upload task still waits for the held request $ENDED_MS ms after the call" }
        }

        // the held request is let go and answers
        hold.release.countDown()
        task.join(20_000)
        val returned = !task.isAlive
        if (body) {
            // what the storage got of the segment that was being sent: all of it, or what had left when it was ended
            hold.handled.await(30, TimeUnit.SECONDS)
            println("SHARING_OFF_BYTES case=${case.name} cut=${hold.cut} bytes=${hold.cutBytes}")
        }
        // the open recording's encoder ends, the passes the SDK asked for are through, and one more pass runs
        c.sharing.lastCapture?.await()
        val idle = jobs.idle()
        if (returned && idle) c.sharing.outbox.drain()
        val after = seen().drop(n0).filter { kind(it) != null }
        // a PUT that was being sent at the call started before it; what it still sent afterwards is audio after the call
        val started = after.filter { !(body && kind(it) == "put" && idOf(it) == old[0] && it.path.endsWith("seg-00001.opus")) }
        val audio = after.filter { kind(it) == "put" }
        check("no_request_after", started.isEmpty()) { "after the call: ${say(started)}" }
        check("no_audio_after", audio.sumOf { it.body.size.toLong() } == 0L) {
            "${audio.sumOf { it.body.size.toLong() }} bytes of audio in ${audio.size} PUT(s) that reached the storage after the call: ${say(audio)}"
        }
        val leftLater = unsent(c)
        if ("still_nothing_after_release" in case.keys) {
            check("still_nothing_after_release", returned && idle && leftLater.isEmpty() && after.isEmpty()) {
                "the upload task returned: $returned; on disk: $leftLater; sent: ${say(after)}"
            }
        }

        if ("on_again_works" in case.keys) {
            if (case.action == "delete") c.sharing.setOn(true)
            val s = aNewRecordingIsUploaded(c)
            val who = s?.json?.get("contributor")
            check("on_again_works", s != null && c.sharing.isOn && (case.action != "delete" || (who != null && who != contributor0))) {
                if (s == null) "a recording made after sharing came back on was not uploaded: ${c.sharing.items}"
                else "sharing on: ${c.sharing.isOn}; the new recording's contributor is the deleted one: ${who == contributor0}"
            }
        }
        if ("the_old_recording_is_gone" in case.keys) {
            val about = seen().drop(n0).filter { idOf(it) in old }
            val listed = c.sharing.items.filter { it.id in old }
            val folders = old.filter { File(c.sharing.outbox.root, it).exists() }
            check("the_old_recording_is_gone", about.isEmpty() && listed.isEmpty() && folders.isEmpty()) {
                "sent after the call: ${say(about)}; still listed: $listed; still on disk: $folders"
            }
        }
    }

    /**
     * "Delete what I shared" is asking the server (its first request is held), and the platform's job starts a pass:
     * nothing is uploaded under the contributor that is being deleted.
     */
    private fun aPassBeginsWhileTheServerIsAsked(case: Case) {
        val c = cloud()
        recording(c, 12.0)
        recording(c, 6.0)
        val contributor0 = c.sharing.contributor.id
        val hold = StubQlab.Hold { _, p -> p == "/v1/contributions/challenge" }
        stub.hold = hold
        jobs.auto = true
        var result: Sharing.Deletion? = null
        var thrown: Throwable? = null
        val delete = thread(name = "test-delete") { try { result = c.sharing.deleteEverything() } catch (e: Throwable) { thrown = e } }.also { threads += it }
        if (!hold.arrived.await(30, TimeUnit.SECONDS)) throw AssertionError("${case.name}: the server was not asked")
        val n0 = seen().size
        val pass = thread(name = "test-upload") { c.sharing.outbox.drain() }.also { threads += it }
        pass.join(20_000)
        val during = seen().drop(n0).filter { kind(it) != null }
        check("queue_stopped_before_the_server_is_asked", !pass.isAlive && during.isEmpty()) {
            "the pass returned: ${!pass.isAlive}; while the server was asked: ${say(during)}"
        }
        hold.release.countDown()
        delete.join(20_000)
        jobs.idle()
        c.sharing.outbox.drain()
        val after = seen().drop(n0).filter { kind(it) != null }
        val audio = after.filter { kind(it) == "put" }
        check("no_request_after", after.isEmpty()) { "after the delete call: ${say(after)}" }
        check("no_audio_after", audio.sumOf { it.body.size.toLong() } == 0L) { "${audio.sumOf { it.body.size.toLong() }} bytes of audio in ${audio.size} PUT(s) after the delete call" }
        val all = onDisk(c)
        check("nothing_unsent_on_device", !delete.isAlive && thrown == null && result == Sharing.Deletion.ACCEPTED && all.isEmpty()) {
            "the delete answered $result ${thrown ?: ""}; on disk: $all"
        }
        check("sharing_is_off_after_an_accepted_delete", !c.sharing.isOn && c.sharing.state.consent == ConsentState.Consent.FINAL && c.sharing.askNow() == null) {
            "sharing on: ${c.sharing.isOn}, consent ${c.sharing.state.consent}"
        }
        c.sharing.setOn(true)
        val s = aNewRecordingIsUploaded(c)
        val who = s?.json?.get("contributor")
        check("on_again_works", s != null && who != null && who != contributor0) {
            if (s == null) "a recording made after sharing came back on was not uploaded: ${c.sharing.items}" else "the new recording's contributor is the deleted one"
        }
    }

    companion object {
        private const val RATE = 16_000

        /** The padded segment of the "being sent" rows: more than a connection's buffers hold. */
        private const val LARGE = 24L * 1024 * 1024

        /** The upload task is back this long after the call at the latest, ms, when its request was ended. */
        private const val ENDED_MS = 1_000L

        @Suppress("UNCHECKED_CAST")
        private val VECTOR: Map<String, Any?> =
            Json.parse(SharingOffTest::class.java.getResourceAsStream("/vectors30/sharing_off.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

        private val BOUND_MS = (VECTOR["off_bound_ms"] as Double).toLong()

        @Suppress("UNCHECKED_CAST")
        private val SHARED: List<Case> = (VECTOR["cases"] as List<Map<String, Any?>>).map { c ->
            Case(c["name"] as String, c["held"] as String, c["action"] as String, c["then"] as String?, (c["assert"] as List<String>).map { it.substringBefore(':') })
        }

        private val OFF = listOf("returns_within_bound", "no_request_after", "no_audio_after", "nothing_unsent_on_device", "still_nothing_after_release")
        private val DELETE = OFF + listOf("queue_stopped_before_the_server_is_asked", "sharing_is_off_after_an_accepted_delete", "on_again_works")

        /** This SDK's own rows (not in the shared file), with the shared keys. */
        internal val ANDROID = listOf(
            Case("android: put being sent, off", "put_body", "off", null, OFF + "request_in_flight_is_ended"),
            Case("android: put being sent, delete", "put_body", "delete", null, DELETE + "request_in_flight_is_ended"),
            Case("android: put held, the recording still open, off", "put_open", "off", null, OFF),
            Case("android: put held, off, the request is ended", "put", "off", null, OFF + "request_in_flight_is_ended"),
            Case("android: start held, off, the request is ended", "start", "off", null, OFF + "request_in_flight_is_ended"),
            Case("android: urls held, off, the request is ended", "urls", "off", null, OFF + "request_in_flight_is_ended"),
            Case("android: takes held, off, the request is ended", "takes", "off", null, OFF + "request_in_flight_is_ended"),
            Case("android: a pass begins while the server is asked, delete", "challenge", "delete", null,
                listOf("queue_stopped_before_the_server_is_asked", "no_request_after", "no_audio_after", "nothing_unsent_on_device",
                    "sharing_is_off_after_an_accepted_delete", "on_again_works")),
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
