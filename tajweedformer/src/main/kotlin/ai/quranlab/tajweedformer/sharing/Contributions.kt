package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.cloud.ApiError
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.Day
import ai.quranlab.tajweedformer.cloud.ErrorAction
import ai.quranlab.tajweedformer.cloud.HttpAbort
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One shared recording in the outbox. Since 2.0.3 a recording holds the takes that followed one another within the
 * join window ([TakeJoin]): one Ogg stream, one upload id.
 */
internal data class Contribution(
    /** The session's upload id (32 hex), also the server's. */
    val id: String,
    val segments: Int,
    val bytes: Long,
    /** Known once the session ended (0 before). */
    val durationMs: Int,
    val day: String,
    /** Null while it runs; false when it failed, was cancelled or the app was killed. */
    val completed: Boolean?,
    val status: Status,
    val attempts: Int,
    val lastError: String?,
    /** The bit rate (bit/s) each closed segment was encoded at, in segment order (SDK 2.0.1, [BitrateLadder]). */
    val segmentBitrates: List<Int> = emptyList(),
    /** The takes in the recording so far (SDK 2.0.3): 1, or more when takes in a row joined it. */
    val takes: Int = 1,
) {
    enum class Status { QUEUED, UPLOADED, FAILED }
}

/** What the outbox needs to know from the rest of the SDK, read at every pass. */
internal interface SharingGate {
    /** Sharing is on here: the learner's consent to the wording in force, the signed config's flags and trust tier. */
    fun uploadsAllowed(): Boolean
    /** The token for this platform's tier (attested where the tier asks for it). Throws [CloudException]. */
    fun uploadToken(): String
    /** The token was refused (401): the next [uploadToken] gets a new one. */
    fun invalidateToken()
    /** The network may be used now (the developer's "unmetered only" option). */
    fun networkAllowed(): Boolean = true
    /** The first session of this learner reached R2 (the thank-you). */
    fun uploaded(id: String) {}
    /** The contributor id was revoked on the server (deleted from elsewhere): a new one from now on. */
    fun contributorRevoked() {}

    /** Whether this install's contributor reads (SDK 3.1.4): its id, device key and recovery code are there and can
     *  be read, or none of them is there yet. While not, the upload pass sends nothing. */
    fun contributorReadable(): Boolean = true
    /** A refusal that is an SDK bug (400): one issue a day. */
    fun badRequest(route: String) {}
    /** A line for the developer's log (debuggable builds). */
    fun log(message: String) {}
    /** The learner's own yes is in force, whatever the signed config says now (its pause is not a no). */
    fun consentInForce(): Boolean = true
    /** The count of consent changes now ([ConsentState.changes]); a recording carries the count of its creation. */
    fun consentCount(): Int = 0
    /** The count at the last accepted "delete what I shared" ([ConsentState.deletedBelow]). */
    fun deletedBelow(): Int = 0
}

/**
 * One recording being shared: Ogg Opus segments written while it runs, into its folder in the
 * outbox, each closed segment handed to the outbox at once. Audio is copied on the caller's thread
 * and encoded on a thread of its own, so recognition never waits for the encoder. Each segment is
 * encoded at the outbox's [BitrateLadder] rate in force when it opens; a closed segment tells the ladder
 * how much audio waits.
 *
 * Since 2.0.3 it outlives a take ([ShareRecorder]): takes in a row append to the same writer, so the stream, the
 * encoder, the resampler and the segment plan run on across them, and [takes] keeps their boundaries in `takes.json`.
 */
internal class ShareCapture(
    private val outbox: ContributionOutbox,
    /** The session JSON for an input rate and route. */
    private val session: (Int, String) -> ByteArray,
    private val route: String,
    private val stillOn: () -> Boolean,
    /** How long a caller waits for the encoder when [MAX_WAITING_MS] of audio already waits for it, ms. */
    private val catchUpMs: Long = CATCH_UP_MS,
    /** The count of consent changes when this recording was made ([ConsentState.changes]): written into its folder. */
    private val consent: Int = 0,
) : Recording {
    /** Samples copied for the encoder that it has not taken yet. */
    private val waiting = java.util.concurrent.atomic.AtomicLong()
    private val caughtUp = Object()

    /** Samples that wait for the encoder now (the tests of the bound read it). */
    val waitingSamples: Long get() = waiting.get()
    val id: String = UUID.randomUUID().toString().replace("-", "").lowercase()
    val folder = File(outbox.root, id)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tj-share").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private var writer: OggOpusWriter? = null
    private var rate = 0
    @Volatile private var failed = false
    private var segments = 0
    @Volatile private var ended = false
    private var preSkip = -1
    private val bitrates = ArrayList<Int>()
    private var abandoned = false
    /** Sharing went off ([abandon]): nothing of the capture is kept, and audio that still waits is not encoded. */
    @Volatile private var off = false

    init { ACTIVE.add(id) }

    /**
     * False once the capture failed or ended, or the server refused its upload or ended it at the capped tier's
     * segment cap: no take joins it (the next take starts a recording of its own).
     */
    override val alive: Boolean get() = !failed && !ended && outbox.accepts(id)

    /** The recording's takes so far (two or more), kept in `takes.json` before the joining take's audio is encoded. */
    override fun takes(list: List<Take>) {
        if (failed || ended) return
        worker.execute {
            if (failed || writer == null) return@execute
            // a list that cannot be written leaves the earlier one: the audio still joins
            try { ShareFiles.writeTakes(folder, list) } catch (_: Exception) {}
        }
    }

    /** Mono samples at [sampleRate]; a different rate than the session's first is ignored (one rate per session). */
    override fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        if (failed || ended || length <= 0) return
        // The encoder's queue is bounded: at most MAX_WAITING_MS of audio waits for it (or one buffer, when that is
        // longer). Audio that comes faster than real time waits here for the encoder. An encoder that does not catch
        // up within catchUpMs has stalled: the capture ends instead of keeping more audio in memory.
        if (!room(sampleRate.toLong() * MAX_WAITING_MS / 1000 - length)) {
            failed = true
            try { worker.execute { abandonNow(); outbox.poke() } } catch (_: java.util.concurrent.RejectedExecutionException) {}
            return
        }
        val copy = samples.copyOfRange(offset, offset + length)
        waiting.addAndGet(length.toLong())
        worker.execute {
            try {
                encode(copy, sampleRate)
            } finally {
                waiting.addAndGet(-length.toLong())
                synchronized(caughtUp) { caughtUp.notifyAll() }
            }
        }
    }

    /** Waits, at most [catchUpMs], until no more than [most] samples wait for the encoder. False when it did not get there. */
    private fun room(most: Long): Boolean {
        val goal = maxOf(most, 0L)
        if (waiting.get() <= goal) return true
        val until = System.nanoTime() + catchUpMs * 1_000_000
        synchronized(caughtUp) {
            while (waiting.get() > goal) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                caughtUp.wait(left)
            }
        }
        return true
    }

    /** On the encoder's thread. */
    private fun encode(copy: FloatArray, sampleRate: Int) {
        run {
            if (failed || off) return
            if (!stillOn()) { abandonNow(); return }
            try {
                if (writer == null) {
                    folder.mkdirs()
                    File(folder, ShareFiles.SESSION).writeBytes(session(sampleRate, route))
                    rate = sampleRate
                    val w = OggOpusWriter({ File(folder, ShareFiles.name(it) + ".opus") }, SegmentPlan.SECONDS, sampleRate,
                        bitrateFor = ::opened) { closed(it) }
                    preSkip = w.preSkip
                    ShareFiles.writeCapture(folder, w.preSkip, bitrates, consent)
                    writer = w
                }
                if (sampleRate == rate) writer?.append(copy)
            } catch (_: Exception) {
                abandonNow()
            }
        }
    }

    /** Segment [n] opens: the ladder's rate now, written where recovery reads it. */
    private fun opened(n: Int): Int {
        val b = outbox.ladder.bitrate
        bitrates.add(b)
        // segment 1 opens inside the writer's constructor: capture.json is written right after it
        if (preSkip >= 0) try { ShareFiles.writeCapture(folder, preSkip, bitrates, consent) } catch (_: Exception) {}
        return b
    }

    private fun closed(seg: OggSegment) {
        segments = seg.n
        try {
            ShareFiles.mark(folder, seg.n, seg.granule, seg.bitrate)
        } catch (_: Exception) {
            failed = true
            return
        }
        outbox.ladder.segmentClosed(outbox.backlogSeconds())
        outbox.poke()
    }

    /**
     * Ends the capture: the last segment and `end.json`; or nothing kept when sharing went off, or a
     * failed or cancelled session is shorter than [MINIMUM_PARTIAL_S] with no segment gone. For a recording of
     * several takes [completed] is the last take's, and the minimum counts the audio of all of them.
     */
    override fun finish(completed: Boolean) {
        if (ended) return
        ended = true
        worker.execute {
            val w = writer
            if (w == null || failed || !stillOn() || w.duration <= 0 || !(completed || w.duration >= MINIMUM_PARTIAL_S || segments > 0)) {
                abandonNow()
            } else {
                try {
                    val ms = w.finish()
                    ShareFiles.writeEnd(folder, ShareFiles.End(ms, completed))
                    release()
                } catch (_: Exception) {
                    abandonNow()
                }
            }
            outbox.poke()
        }
        worker.shutdown()
    }

    /**
     * Sharing went off (the settings switch, a deletion, the signed config): the capture ends at once and its folder
     * goes, whatever was already handed to the outbox. It does not wait for [stillOn] to say so: the recording may
     * be between takes, and the caller may record the learner's answer only afterwards.
     */
    override fun abandon() {
        if (ended) return
        ended = true
        // at once, not behind the audio that waits for the encoder: the folder goes as soon as the encoder is free
        off = true
        worker.execute {
            abandonNow()
            outbox.poke()
        }
        worker.shutdown()
    }

    /** Waits until the audio given so far is encoded and on disk. */
    override fun sync() { if (!worker.isShutdown) worker.submit {}.get() }

    /** Waits for the encoder thread (tests, and before the process is torn down). */
    fun await(ms: Long = 30_000) { worker.awaitTermination(ms, TimeUnit.MILLISECONDS) }

    private fun abandonNow() {
        // once: a capture that failed while it ran is ended later (a recording waits for the next take), when the
        // outbox may already have sent what it kept
        if (abandoned) return
        abandoned = true
        writer?.abandon()
        writer = null
        failed = true
        // segments already handed over stay with the outbox only while sharing is on
        if (segments == 0 || off || !stillOn()) folder.deleteRecursively() else ShareFiles.recover(folder)
        release()
    }

    private fun release() { ACTIVE.remove(id) }

    companion object {
        /** A failed or cancelled session is shared only from this long. */
        const val MINIMUM_PARTIAL_S = 2.0
        /** At most this much audio waits in memory for the encoder (or one buffer, when it is longer), ms. */
        const val MAX_WAITING_MS = 10_000L
        /** A caller waits this long for the encoder to catch up before the capture is given up, ms. */
        const val CATCH_UP_MS = 2_000L
        /** Folders being written in this process (so the outbox does not take them for crashed). */
        val ACTIVE: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /**
         * The session JSON (closed schema of POST /v1/uploads), sorted keys as the Swift SDK writes it.
         * `contributor_key` and `recovery_hash` bind the device key at the first session (P2.11).
         */
        fun sessionJson(modelRevision: String?, backend: String, weightsSha256: String?, consentVersion: String?, contributor: String,
                        contributorKey: String?, recoveryHash: String?, day: String, deviceClass: String, osMajor: Int,
                        inputSampleRate: Int, inputRoute: String, consentTextSha256: String? = null,
                        /** SDK 3.0: the model's build id (40 hex) next to `model_revision`, which is then the version. */
                        modelBuild: String? = null,
                        /** SDK 3.0: this install's count of shared recordings, from 1. */
                        takeSeq: Int? = null,
                        /** SDK 3.0: wall ms from the end of the install's previous recording to this one's start
                         *  (a multiple of 100, 0 to 3,600,000); null: absent. */
                        gapMsPrev: Int? = null): ByteArray {
            val audio = sortedMapOf<String, Any?>("codec" to "opus", "container" to "ogg", "sample_rate" to 48_000, "channels" to 1,
                "input_sample_rate" to inputSampleRate.coerceIn(8_000, 192_000), "input_route" to inputRoute)
            val decode = sortedMapOf<String, Any?>("model_revision" to modelRevision?.take(64), "backend" to backend,
                "weights_sha256" to weightsSha256, "sdk_version" to SdkInfo.VERSION)
            modelBuild?.takeIf { Regex("^[0-9a-f]{40}$").matches(it) }?.let { decode["model_build"] = it }
            val o = sortedMapOf<String, Any?>("format" to 1, "audio" to audio, "decode" to decode, "contributor" to contributor,
                "day" to day, "device_class" to deviceClass, "os_major" to osMajor.coerceIn(1, 99))
            consentVersion?.let { o["consent_version"] = it }
            // only under the app's own wording (2.0.0 servers' closed schema refuses the field)
            consentTextSha256?.let { o["consent_text_sha256"] = it }
            contributorKey?.let { o["contributor_key"] = it }
            recoveryHash?.let { o["recovery_hash"] = it }
            takeSeq?.takeIf { it >= 1 }?.let { o["take_seq"] = it }
            gapMsPrev?.takeIf { it in 0..3_600_000 && it % 100 == 0 }?.let { o["gap_ms_prev"] = it }
            return Json.write(o).toByteArray(Charsets.UTF_8)
        }
    }
}

/**
 * The shared sessions on their way to R2 (PLATFORM_CLIENT.md section 10), as the Swift SDK's outbox:
 * each session a folder `<root>/<id>/`; the upload starts once the first segment is closed (POST
 * /v1/uploads answers with 20 presigned PUT URLs), every closed segment is PUT straight to R2 to its
 * own URL, the next batch is asked for while 5 unused URLs remain, fresh ones from the same segment
 * when R2 answers 403. Nothing else is sent: the last page of a finished session ends the stream.
 * Everything is on disk (`item.json` per session), so a killed app or a reboot resumes where it
 * stopped; on Android a WorkManager job drains it whenever the network allows.
 *
 * The [ladder] hears how uploads go: a network failure of any upload request (no connection, a
 * timeout, HTTP 408 or 5xx) steps it down, a segment PUT answered 2xx counts towards a step up.
 *
 * Since 2.0.3 a recording may hold several takes. Its segments upload as before while it waits for the next
 * take; once it has closed and every segment is up, the item is uploaded, and a recording of two or more takes
 * then sends its take list (`POST /v1/uploads/{id}/takes`, the body of `takes.json`). That call never changes the
 * item's state: it is retried with the same backoff until it is accepted or dropped, across relaunches.
 *
 * Blocking; [drain] runs one pass at a time (a call during a pass runs one more after it).
 *
 * Sharing turned off, or what was shared deleted, while a pass runs (SPEC 11.7, SDK 3.0.1): [whileStopped] marks the
 * uploader stopped before anything else, ends the request it has in flight (every request of a pass can be ended:
 * the start, the URLs, a segment's PUT, the take list, the token's), and waits, at most [PASS_LEAVES_MS], until the
 * pass has left; only then is anything deleted. The pass asks [mayGoOn] before every request, also between two
 * segments of one recording, and a halted pass writes nothing. No pass starts while the uploader is stopped. A
 * removed recording is remembered ([gone]): it is never written back and never picked up again, also by a pass
 * that could not be ended in time; a folder that cannot be deleted at once (a file of it is open) is marked and
 * goes at the next scan.
 */
internal class ContributionOutbox internal constructor(
    val root: File,
    private val api: QuranLabApi,
    private val contributor: () -> String,
    private val gate: SharingGate,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The adaptive bitrate of the captures that write into this outbox. */
    val ladder: BitrateLadder = BitrateLadder(),
    /** Called when a segment closed or a session ended (Android: schedule the WorkManager job). */
    private val onWork: () -> Unit = {},
) {
    internal class Record(
        val id: String,
        var seq: Int = 0,
        var state: String = "active",
        var started: Boolean = false,
        /** n -> (url, expires_at in seconds). */
        val urls: MutableMap<Int, Pair<String, Long>> = sortedMapOf(),
        val uploaded: MutableSet<String> = sortedSetOf(),
        var failCode: String? = null,
        var attempts: Int = 0,
        var lastError: String? = null,
        var nextAttempt: Long = 0,
        /**
         * The take list of a recording of two or more takes (SDK 2.0.3): null when there is none to send,
         * [TAKES_PENDING] from the moment the item is uploaded until the list is [TAKES_SENT] or [TAKES_DROPPED].
         */
        var takes: String? = null,
        var takesAttempts: Int = 0,
        var takesNextAttempt: Long = 0,
        /** The server answered 400 to this list and the issue was reported: it is reported once. */
        var takesRefused: Boolean = false,
        /** The count of consent changes the recording was made under ([ShareFiles.stamp]); 0 before SDK 3.0.1. */
        var consent: Int = 0,
    ) {
        /** Something is still to send: segments, or the take list of an uploaded recording. */
        val waiting: Boolean get() = state == "active" || takes == TAKES_PENDING
        /** When the next try is due (ms). */
        val due: Long get() = if (state == "active") nextAttempt else takesNextAttempt
    }

    private val records = HashMap<String, Record>()
    private val lock = Object()
    private var running = false
    private var again = false

    /** How many [whileStopped] are at work. Guarded by [lock]; [stopped] is the same for the pass to read. */
    private var stops = 0
    @Volatile private var stopped = false

    /** True on a thread that is inside [whileStopped]. */
    private val stopping = ThreadLocal<Boolean>()

    /** The pass that runs now: its thread, and what ends its requests. Guarded by [lock]. */
    private var passThread: Thread? = null
    private var passAbort: HttpAbort? = null

    /**
     * Recordings removed for good (sharing went off, a deletion, expiry): never written back, never picked up again.
     * The newest [GONE_KEPT]; guarded by [records].
     */
    private val gone = LinkedHashSet<String>()
    private val takesRouteLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    init { root.mkdirs() }

    /** Every session, oldest first. */
    val items: List<Contribution> get() = synchronized(records) { scan(); records.values.sortedBy { it.seq }.map(::snapshot) }

    fun item(id: String): Contribution? = synchronized(records) { scan(); records[id]?.let(::snapshot) }

    /** A capture closed a segment or ended. */
    internal fun poke() = onWork()

    /** Whether the upload of a recording that is still written goes on: not refused, not ended at the segment cap. */
    internal fun accepts(id: String): Boolean = synchronized(records) { records[id]?.state ?: "active" } == "active"

    /** Seconds of audio in closed segments not yet uploaded, of every session still to send. */
    fun backlogSeconds(): Double = synchronized(records) {
        scan()
        records.values.filter { it.state == "active" }.sumOf { r ->
            ShareFiles.seconds(ShareFiles.markers(File(root, r.id))) { ShareFiles.name(it.n) + ".opus" in r.uploaded }
        }
    }

    /** A network failure of an upload request: no answer, or HTTP 408 or 5xx. */
    private fun networkFailure(status: Int?): Boolean = status == null || status == 408 || status in 500..599

    /**
     * The uploader may go on: nobody stopped it, and sharing is still on. Asked before every request of a pass (the
     * token's, the start, a batch of URLs, every segment's PUT, the take list).
     */
    private fun mayGoOn(): Boolean = !halted() && gate.uploadsAllowed()

    /**
     * This pass was told to stop: the uploader is stopped now, or was while this pass ran (its requests are ended
     * for good, also when the stop is over by the time the pass notices). A halted pass writes nothing.
     */
    private fun halted(): Boolean = stopped || HttpAbort.current()?.aborted == true

    /**
     * [work] with the uploader stopped (SPEC 11.7): the learner turns sharing off or deletes what they shared,
     * perhaps in the middle of an upload. First of all the uploader is marked stopped: it makes no further request,
     * writes nothing more, and no pass starts. The request it has in flight is ended. Then this waits until the pass
     * has left, at most [PASS_LEAVES_MS] (never for the network: a request that could not be ended is left behind,
     * and what it brings back later is thrown away). So nothing of a recording is open or half written when [work]
     * deletes it, and the SDK hands nothing more of it to the system afterwards (what the system had already taken
     * of the request that was ended may still go out). The uploader stays stopped while [work] runs ("delete what
     * I shared" asks the server in there); whoever stops it asks for a pass again where sharing is still on.
     */
    internal fun <T> whileStopped(work: () -> T): T {
        // a stop inside a stop on the same thread ("delete what I shared" empties the queue inside its own stop)
        if (stopping.get() == true) return work()
        stopping.set(true)
        // what runs in here is not the pass's work, also when a callback of the pass came here on the pass's thread
        val bound = HttpAbort.bind(null)
        val inFlight = synchronized(lock) {
            stops++
            stopped = true
            passAbort
        }
        try {
            inFlight?.abort()
            synchronized(lock) {
                if (passThread !== Thread.currentThread()) {
                    val until = System.nanoTime() + PASS_LEAVES_MS * 1_000_000
                    while (running) {
                        val left = (until - System.nanoTime()) / 1_000_000
                        if (left <= 0) break
                        lock.wait(left)
                    }
                }
            }
            return work()
        } finally {
            synchronized(lock) {
                stops--
                stopped = stops > 0
            }
            HttpAbort.bind(bound)
            stopping.remove()
        }
    }

    /** The recording is not the outbox's any more, for good. Caller holds [records]. */
    private fun forget(id: String) {
        records.remove(id)
        gone.add(id)
        while (gone.size > GONE_KEPT) gone.remove(gone.first())
    }

    /**
     * Deletes a recording's folder for good. One that cannot be deleted now (a file of it is still open somewhere;
     * some systems refuse that) gets a marker and goes at the next scan: it is never recovered and never sent.
     * Caller holds [records].
     */
    private fun erase(folder: File) {
        forget(folder.name)
        if (!delete(folder) && folder.isDirectory) try { File(folder, GONE).createNewFile() } catch (_: Exception) {}
    }

    /** The deletion of a recording's folder ([deleteForTests] stands in for it in the tests of a storage that refuses). */
    private fun delete(folder: File): Boolean = deleteForTests?.invoke(folder) ?: folder.deleteRecursively()

    /**
     * Sharing was turned off: every session not fully uploaded goes, and the SDK sends nothing else (a take list
     * neither).
     * The uploader is stopped first ([whileStopped]); [first] then ends the open recording, before the queue is
     * deleted.
     */
    fun cancelQueued(first: () -> Unit = {}): Unit = whileStopped {
        first()
        encodersLeave()
        synchronized(records) { dropUnsent() }
        // a second look: what an uploader or an encoder on its way out put back
        synchronized(records) { dropUnsent() }
    }

    /**
     * A recording that was just ended, or closed a moment ago, is still its encoder's (which deletes the folder of an
     * ended one on its own thread): waited for, at most [ENCODERS_LEAVE_MS], so nothing of it is left when sharing
     * has gone off. One that takes longer is forgotten here and goes at the next scan.
     */
    private fun encodersLeave() {
        val until = System.nanoTime() + ENCODERS_LEAVE_MS * 1_000_000
        while ((root.listFiles() ?: emptyArray()).any { it.name in ShareCapture.ACTIVE } && System.nanoTime() < until) Thread.sleep(5)
    }

    private fun dropUnsent() {
        scan()
        for (r in records.values.toList()) {
            if (r.state == "active") {
                // a recording whose encoder has not let go of it yet deletes its own folder; it is forgotten here
                if (r.id in ShareCapture.ACTIVE) forget(r.id) else remove(r.id)
            } else if (r.takes == TAKES_PENDING) {
                endTakes(r, TAKES_DROPPED)
                // the list was never sent: it does not stay on the device
                try { File(File(root, r.id), ShareFiles.TAKES).delete() } catch (_: Exception) {}
            }
        }
        // folders the outbox has not picked up yet
        for (f in root.listFiles() ?: emptyArray()) {
            if (!f.isDirectory || records[f.name] != null) continue
            if (f.name in ShareCapture.ACTIVE) forget(f.name) else erase(f)
        }
    }

    /** Everything, uploaded or not (after a deletion). The uploader is stopped first ([whileStopped]). */
    internal fun clearAll(): Unit = whileStopped {
        synchronized(records) { dropAll() }
        synchronized(records) { dropAll() }
    }

    private fun dropAll() {
        for (id in records.keys.toList()) forget(id)
        for (f in root.listFiles() ?: emptyArray()) {
            if (f.name in ShareCapture.ACTIVE) forget(f.name) else if (f.isDirectory) erase(f) else f.delete()
        }
    }

    /** The earliest retry due (ms), or null when nothing waits. */
    fun nextDue(): Long? = synchronized(records) { scan(); records.values.filter { it.waiting }.minOfOrNull { it.due } }

    /** True while some session still has something to send (segments, or the take list of an uploaded recording). */
    fun hasWork(): Boolean = synchronized(records) { scan(); records.values.any { it.waiting } }

    /** One upload pass. [ignoringBackoff] is for tests and an explicit retry. Returns false when the pass stopped offline. */
    fun drain(ignoringBackoff: Boolean = false): Boolean {
        val abort = HttpAbort()
        synchronized(lock) {
            // no pass starts while the uploader is stopped (whoever stopped it asks for one afterwards)
            if (stops > 0) return true
            if (running) { again = true; return true }
            running = true
            passThread = Thread.currentThread()
            passAbort = abort
        }
        // every request made on this thread during the pass can be ended from the thread that stops the uploader
        val bound = HttpAbort.bind(abort)
        var online = true
        var more = false
        try {
            do {
                synchronized(lock) { again = false }
                online = pass(ignoringBackoff)
                more = synchronized(lock) { again }
            } while (more && online && !halted())
        } finally {
            HttpAbort.bind(bound)
            synchronized(lock) {
                running = false
                passThread = null
                passAbort = null
                lock.notifyAll()
            }
        }
        // a pass that was ended does not go on; the one that was asked for while it ran is asked for again
        if (more && abort.aborted) onWork()
        return online
    }

    private fun pass(ignoringBackoff: Boolean): Boolean {
        if (halted()) return true
        val due = synchronized(records) {
            scan()
            expireOld()
            val now = clock()
            records.values.filter { it.waiting && (ignoringBackoff || it.due <= now) }.sortedBy { it.seq }.map { it.id }
        }
        if (due.isEmpty()) return true
        if (!gate.uploadsAllowed()) {
            for (id in due) note(id, "sharing is not allowed here now; waiting")
            return true
        }
        // read at every pass: nothing is sent while the contributor cannot be read
        if (!gate.contributorReadable()) {
            for (id in due) note(id, "the contributor cannot be read now; waiting")
            return true
        }
        if (!gate.networkAllowed()) return true
        for (id in due) {
            if (!mayGoOn()) return true
            val token = try {
                gate.uploadToken()
            } catch (e: CloudException) {
                // ended because the uploader was stopped: nothing is written
                if (halted()) return true
                if (synchronized(records) { records[id] }?.state == "active") retry(id, "token: ${e.message}", longWait = !e.retryable)
                else retryTakes(id, longWait = !e.retryable)
                return !e.offline
            }
            if (!step(id, token)) return false
        }
        return true
    }

    /** Moves one session on as far as it goes now. False when offline (the pass stops). */
    private fun step(id: String, token: String): Boolean {
        val folder = File(root, id)
        var rec = synchronized(records) { records[id] } ?: return true
        // uploaded, its take list still to send
        if (rec.state != "active") return sendTakes(id, token)
        val markers = ShareFiles.markers(folder)
        val end = ShareFiles.end(folder)
        if (!rec.started) {
            if (markers.isEmpty()) return true
            val session = try { File(folder, ShareFiles.SESSION).readBytes() } catch (_: Exception) { return true }
            if (!mayGoOn()) return true
            rec.attempts++
            save(rec)
            val r = send(id, "uploads") { api.uploadStart(token, id, session) } ?: return !lastOffline
            val urls = parseUrls(r, id) ?: run { retry(id, "no upload URLs in the answer"); return true }
            rec = synchronized(records) { records[id] } ?: return true
            rec.started = true
            rec.urls.putAll(urls)
            save(rec)
        }
        for (m in markers) {
            val name = ShareFiles.name(m.n) + ".opus"
            if (name in rec.uploaded) continue
            val u = rec.urls[m.n]
            if (u == null || u.second - clock() / 1000 <= 300) {
                // none for this segment yet (the next batch did not come in time), or it expired
                if (!fetchUrls(id, m.n, token)) return !lastOffline
                rec = synchronized(records) { records[id] } ?: return true
            }
            val url = rec.urls[m.n] ?: return true
            // before every segment: the recording may still be open, with more closed segments behind this one
            if (!mayGoOn()) return true
            val r = try {
                api.putSegment(url.first, File(folder, name))
            } catch (e: CloudException) {
                // ended because the uploader was stopped: not a failure of the network, and nothing is written
                if (halted()) return true
                ladder.uploadFailed()
                retry(id, "PUT $name: ${e.message}")
                return false
            }
            if (halted()) return true
            rec = synchronized(records) { records[id] } ?: return true
            if (rec.state != "active") return true
            if (r.ok) {
                rec.uploaded.add(name)
                rec.urls.remove(m.n)
                rec.lastError = null
                save(rec)
                File(folder, name).delete()
                ladder.uploadOk(backlogSeconds())
            } else {
                if (networkFailure(r.status)) ladder.uploadFailed()
                // 403: the URL expired or its signature is no longer valid: fresh URLs from this segment
                rec.attempts++
                if (r.status == 403) rec.urls.remove(m.n)
                save(rec)
                retry(id, "PUT $name: HTTP ${r.status}", immediately = r.status == 403 && rec.attempts < 5)
                return true
            }
        }
        // while the session runs: the next batch once 5 unused URLs remain
        val last = rec.urls.keys.maxOrNull()
        if (end == null && last != null && last - (markers.lastOrNull()?.n ?: 0) <= 5) {
            if (!fetchUrls(id, last + 1, token)) return !lastOffline
        }
        rec = synchronized(records) { records[id] } ?: return true
        if (end == null || !rec.started || markers.isEmpty() || !markers.all { ShareFiles.name(it.n) + ".opus" in rec.uploaded }) return true
        if (halted()) return true
        rec.state = "uploaded"
        rec.lastError = null
        rec.nextAttempt = 0
        rec.urls.clear()
        // 2.0.3: a recording of several takes sends its take list next; the session is uploaded whatever comes of it
        if (ShareFiles.takes(folder) != null) rec.takes = TAKES_PENDING
        save(rec)
        // the item, the markers, the end, the session JSON and the take list stay (the status's numbers)
        for (f in folder.listFiles() ?: emptyArray()) {
            val n = f.name
            if (n != ITEM && n != ShareFiles.END && n != ShareFiles.SESSION && n != ShareFiles.TAKES && !(n.startsWith("seg-") && n.endsWith(".json"))) f.delete()
        }
        gate.uploaded(id)
        return sendTakes(id, token)
    }

    /**
     * The take list of an uploaded recording of two or more takes (SDK 2.0.3): `POST /v1/uploads/{id}/takes` with the
     * body of `takes.json`, once the recording has closed and its segments are up. The session stays uploaded whatever
     * the answer: 2xx, the list is sent; 404 (a server without the route, or one that does not know the upload) and
     * 410 drop it; 401 renews the token and tries again; 408, 429, 5xx and no answer keep it for a later pass with the
     * outbox's backoff (the same body again; the server replaces the list). 400 keeps it too (SDK 3.0; 2.0.3 dropped
     * it): the list waits until the server accepts it, sharing goes off or the recording is seven days old, and the
     * SDK bug is reported once for that list.
     * The bitrate ladder does not hear of this call: it is not audio. False when offline (the pass stops).
     */
    private fun sendTakes(id: String, token: String): Boolean {
        val folder = File(root, id)
        var rec = synchronized(records) { records[id] } ?: return true
        if (rec.state != "uploaded" || rec.takes != TAKES_PENDING) return true
        if (ShareFiles.end(folder) == null) {
            // ended at the capped tier's segment cap while the capture still writes: the list is final when it ends
            if (id !in ShareCapture.ACTIVE) endTakes(rec, TAKES_DROPPED)
            return true
        }
        // what the cap left of the recording: only the takes inside the audio that went up
        if (rec.lastError == "segment_cap") ShareFiles.trimTakes(folder, uploadedMs(folder, rec))
        val list = ShareFiles.takes(folder)
        if (list == null) { endTakes(rec, null); return true }
        val body = ShareFiles.takesJson(list).toByteArray(Charsets.UTF_8)
        // the contributor the recording was shared under (the session JSON's)
        val who = ShareFiles.readJson(File(folder, ShareFiles.SESSION))?.get("contributor") as? String ?: contributor()
        if (!mayGoOn()) return true
        rec.takesAttempts++
        save(rec)
        val r = try {
            api.uploadTakes(token, id, who, body)
        } catch (e: CloudException) {
            // ended because the uploader was stopped: whoever stopped it drops the list, nothing is written here
            if (halted()) return true
            retryTakes(id)
            return false
        }
        if (halted()) return true
        rec = synchronized(records) { records[id] } ?: return true
        if (rec.takes != TAKES_PENDING) return true
        val e = r.error
        when {
            r.ok -> endTakes(rec, TAKES_SENT)
            r.status == 401 || e.action == ErrorAction.NEW_TOKEN -> {
                gate.invalidateToken()
                retryTakes(id, immediately = rec.takesAttempts < 3)
            }
            r.status == 408 || r.status == 429 || r.status >= 500 -> retryTakes(id)
            e.action == ErrorAction.PAUSE_SHARING || e.action == ErrorAction.NEED_ATTESTATION -> retryTakes(id, longWait = true)
            r.status == 400 -> {
                if (!rec.takesRefused) {
                    rec.takesRefused = true
                    gate.badRequest("uploads_takes")
                }
                retryTakes(id)
            }
            r.status == 404 -> {
                if (takesRouteLogged.compareAndSet(false, true)) {
                    gate.log("the server answered 404 to POST /v1/uploads/{id}/takes: the take boundaries of a recording are not sent (the recording is uploaded)")
                }
                endTakes(rec, TAKES_DROPPED)
            }
            // 410 (the upload was deleted) and any other refusal
            else -> endTakes(rec, TAKES_DROPPED)
        }
        return true
    }

    /** Milliseconds of audio in the segments that went up (from the last one's granule position). */
    private fun uploadedMs(folder: File, rec: Record): Long {
        val last = ShareFiles.markers(folder).lastOrNull { ShareFiles.name(it.n) + ".opus" in rec.uploaded } ?: return 0
        val preSkip = (ShareFiles.readJson(File(folder, ShareFiles.CAPTURE))?.get("preSkip") as? Double)?.toLong() ?: 312
        return maxOf(0L, last.granule - preSkip) / 48
    }

    /** The take list call is over: sent, dropped, or (null) there was none to send. */
    private fun endTakes(rec: Record, state: String?) {
        rec.takes = state
        rec.takesNextAttempt = 0
        save(rec)
    }

    private fun retryTakes(id: String, immediately: Boolean = false, longWait: Boolean = false) {
        val rec = synchronized(records) { records[id] } ?: return
        // a list that was dropped meanwhile (sharing went off) is not put back in line
        if (rec.takes != TAKES_PENDING) return
        rec.takesNextAttempt = clock() + delayMs(rec.takesAttempts, immediately, longWait)
        save(rec)
    }

    /** The outbox's backoff: 30 s doubling to 6 h, with jitter; an hour for a long wait. */
    private fun delayMs(attempts: Int, immediately: Boolean, longWait: Boolean): Long {
        val n = maxOf(1, attempts)
        val delayS = when {
            immediately -> 0.0
            longWait -> 3600.0
            else -> minOf(30.0 * Math.pow(2.0, minOf(n - 1, 16).toDouble()), 6 * 3600.0) * (0.8 + 0.4 * Math.random())
        }
        return (delayS * 1000).toLong()
    }

    @Volatile private var lastOffline = false

    /**
     * A Worker call: the response on 2xx; otherwise the item is retried or failed and null comes back. Null too, with
     * nothing sent or nothing written, when the uploader may not go on ([mayGoOn]) or was stopped during the call.
     */
    private fun send(id: String, route: String, call: () -> HttpResponse): HttpResponse? {
        lastOffline = false
        if (!mayGoOn()) return null
        val r = try { call() } catch (e: CloudException) {
            // ended because the uploader was stopped: not offline, not a failure of the network
            if (halted()) return null
            lastOffline = true
            if (networkFailure(e.api?.status)) ladder.uploadFailed()
            retry(id, e.message ?: "offline")
            return null
        }
        if (halted()) return null
        if (r.ok) return r
        if (networkFailure(r.status)) ladder.uploadFailed()
        val e = r.error
        val rec = synchronized(records) { records[id] }
        // a request timeout is the network's, not a refusal: tried again with the backoff
        if (r.status == 408) { retry(id, e.toString()); return null }
        when (e.action) {
            ErrorAction.RENEW_TOKEN, ErrorAction.STOP, ErrorAction.NEW_TOKEN -> {
                gate.invalidateToken()
                retry(id, e.toString(), immediately = (rec?.attempts ?: 0) < 3)
            }
            ErrorAction.BACKOFF, ErrorAction.RETRY_NEXT_LAUNCH, ErrorAction.PAUSE_SHARING, ErrorAction.NEED_ATTESTATION ->
                retry(id, e.toString(), longWait = e.action != ErrorAction.BACKOFF)
            ErrorAction.SEGMENT_CAP -> finishAtCap(id)
            ErrorAction.DROP_AND_REPORT -> { gate.badRequest(route); fail(id, e) }
            else -> {
                if (e.code == "contributor_revoked" || e.code == "contributor_key_mismatch") gate.contributorRevoked()
                fail(id, e)
            }
        }
        return null
    }

    private fun parseUrls(r: HttpResponse, id: String): Map<Int, Pair<String, Long>>? {
        val o = r.json() ?: return null
        if (o["id"] != id) return null
        val list = o["urls"] as? List<*> ?: return null
        val out = sortedMapOf<Int, Pair<String, Long>>()
        for (x in list) {
            val u = x as? Map<*, *> ?: continue
            val n = (u["n"] as? Double)?.toInt() ?: continue
            val url = u["url"] as? String ?: continue
            val exp = (u["expires_at"] as? Double)?.toLong() ?: continue
            if (n > 0) out[n] = url to exp
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** URLs for segments [from] to from + 19, merged in. False when the call failed. */
    private fun fetchUrls(id: String, from: Int, token: String): Boolean {
        val r = send(id, "uploads/urls") { api.uploadUrls(token, id, from, contributor()) }
        if (r == null) {
            if (!halted()) synchronized(records) { records[id] }?.let { it.attempts++; save(it) }
            return false
        }
        val urls = parseUrls(r, id) ?: run { retry(id, "no upload URLs in the answer"); return false }
        val rec = synchronized(records) { records[id] } ?: return false
        rec.urls.putAll(urls)
        save(rec)
        return true
    }

    /** The capped tier's segment cap: what is up is the session (the audio so far stays valid). */
    private fun finishAtCap(id: String) {
        val rec = synchronized(records) { records[id] } ?: return
        rec.state = "uploaded"
        rec.lastError = "segment_cap"
        // a recording of several takes: the list of those inside what went up follows once the capture has ended
        if (id in ShareCapture.ACTIVE || ShareFiles.takes(File(root, id)) != null) rec.takes = TAKES_PENDING
        save(rec)
        for (f in File(root, id).listFiles() ?: emptyArray()) if (f.name.endsWith(".opus")) f.delete()
    }

    private fun retry(id: String, why: String, immediately: Boolean = false, longWait: Boolean = false) {
        val rec = synchronized(records) { records[id] } ?: return
        rec.lastError = why
        rec.nextAttempt = clock() + delayMs(rec.attempts, immediately, longWait)
        save(rec)
    }

    private fun fail(id: String, e: ApiError) {
        val rec = synchronized(records) { records[id] } ?: return
        rec.state = "failed"
        rec.failCode = e.code
        rec.lastError = e.toString()
        rec.nextAttempt = 0
        rec.urls.clear()
        save(rec)
        for (f in File(root, id).listFiles() ?: emptyArray()) if (f.name != ITEM) f.delete()
    }

    private fun note(id: String, why: String) {
        val rec = synchronized(records) { records[id] } ?: return
        // an uploaded item whose take list waits keeps its state as it is
        if (rec.state != "active" || rec.lastError == why) return
        rec.lastError = why
        save(rec)
    }

    /**
     * Unsent sessions older than 7 days are deleted (their URLs and the server's window are gone); a take list that
     * could not be sent for as long is dropped (its recording stays uploaded).
     */
    private fun expireOld() {
        val today = Day.of(clock())
        for (r in records.values.toList()) {
            if (!r.waiting || r.id in ShareCapture.ACTIVE) continue
            val day = ShareFiles.readJson(File(File(root, r.id), ShareFiles.SESSION))?.get("day") as? String ?: continue
            if (try { Day.between(day, today) > 7 } catch (_: Exception) { false }) {
                if (r.state == "active") remove(r.id) else endTakes(r, TAKES_DROPPED)
            }
        }
    }

    /** Picks up folders the captures made, and recovers the ones a killed app left. Caller holds [records]. */
    private fun scan() {
        val yes = gate.consentInForce()
        val count = gate.consentCount()
        val below = gate.deletedBelow()
        val names = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.length == 32 && it.name.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        for (f in names) {
            val name = f.name
            if (name in gone || File(f, GONE).exists()) {
                // removed for good; its folder is still there (a file of it was open then, or its encoder was still
                // ending): it goes as soon as it can, and is never read
                if (name !in ShareCapture.ACTIVE) erase(f)
                continue
            }
            var known = records[name]
            if (known == null) {
                known = load(File(f, ITEM))?.takeIf { it.id == name }
                if (known != null) records[name] = known
            }
            val capturing = name in ShareCapture.ACTIVE
            // a folder that is being written now is its capture's: made under the yes in force, and the stop ends it
            if (!capturing && fromBefore(f, known, yes, count, below)) continue
            if (known != null) continue
            // an item appears with its first closed segment
            if (capturing && ShareFiles.markers(f).isEmpty()) continue
            if (!capturing && !File(f, ShareFiles.END).exists() && !ShareFiles.recover(f)) continue
            val rec = Record(name, seq = (records.values.maxOfOrNull { it.seq } ?: 0) + 1, consent = ShareFiles.stamp(f))
            records[name] = rec
            save(rec)
        }
        // a session whose capture is gone without an end (killed in an earlier run)
        for (rec in records.values.toList()) {
            if (rec.state != "active") continue
            val f = File(root, rec.id)
            if (rec.id !in ShareCapture.ACTIVE && !File(f, ShareFiles.END).exists() && !ShareFiles.recover(f)) forget(rec.id)
        }
    }

    /**
     * A recording from before the learner's last word (SPEC 11.7, across a restart). Every recording carries the
     * count of consent changes of its creation; what is asked here is on disk, so it holds whatever the process that
     * made the recording lived to do. One made before an accepted "delete what I shared" goes whole, its status
     * too. An unsent one goes when the learner's yes is not in force (none can rightly be there), or when it carries
     * another count than the one in force (it was made before a no): it is never recovered and never uploaded. The
     * signed config's pause is not a no: its queue is kept. An uploaded recording keeps its status; a take list of
     * it that was still to send is dropped. True when the folder is gone (or marked to go). Caller holds [records].
     */
    private fun fromBefore(f: File, rec: Record?, yes: Boolean, count: Int, below: Int): Boolean {
        val stamp = rec?.consent ?: ShareFiles.stamp(f)
        if (stamp < below) {
            erase(f)
            return true
        }
        if (yes && stamp == count) return false
        if (rec == null || rec.state == "active") {
            erase(f)
            return true
        }
        if (rec.takes == TAKES_PENDING) {
            endTakes(rec, TAKES_DROPPED)
            try { File(f, ShareFiles.TAKES).delete() } catch (_: Exception) {}
        }
        return false
    }

    private fun remove(id: String) = synchronized(records) { erase(File(root, id)) }

    private fun snapshot(r: Record): Contribution {
        val f = File(root, r.id)
        val markers = ShareFiles.markers(f)
        val end = ShareFiles.end(f)
        val status = when (r.state) {
            "uploaded" -> Contribution.Status.UPLOADED
            "failed" -> Contribution.Status.FAILED
            else -> Contribution.Status.QUEUED
        }
        val day = ShareFiles.readJson(File(f, ShareFiles.SESSION))?.get("day") as? String ?: ""
        return Contribution(r.id, markers.size, markers.sumOf { it.bytes }, end?.durationMs ?: 0, day, end?.completed, status, r.attempts, r.lastError,
            markers.map { it.bitrate ?: ShareFiles.LEGACY_BITRATE }, ShareFiles.takes(f)?.size ?: 1)
    }

    /** Where the take list of an item stands (null: none to send, else [TAKES_PENDING], [TAKES_SENT] or [TAKES_DROPPED]). */
    internal fun takesState(id: String): String? = synchronized(records) { scan(); records[id]?.takes }

    /**
     * Keeps a recording's state, in memory and in its folder. Not a removed one's ([gone]): an uploader that still held
     * it when sharing went off does not bring it back. Inside the lock a delete holds, so the two never interleave.
     */
    private fun save(r: Record): Unit = synchronized(records) {
        if (r.id !in gone) write(r)
    }

    /** Caller holds [records]. */
    private fun write(r: Record) {
        records[r.id] = r
        val o = linkedMapOf<String, Any?>("id" to r.id, "seq" to r.seq, "state" to r.state, "started" to r.started,
            "urls" to r.urls.map { (n, u) -> linkedMapOf("n" to n, "url" to u.first, "expires_at" to u.second) },
            "uploaded" to r.uploaded.toList(), "failCode" to r.failCode, "attempts" to r.attempts, "lastError" to r.lastError,
            "nextAttempt" to r.nextAttempt)
        // 2.0.3 fields, written only for a recording of several takes (an item of one take reads the same in 2.0.2)
        if (r.takes != null || r.takesAttempts != 0) {
            o["takes"] = r.takes
            o["takesAttempts"] = r.takesAttempts
            o["takesNextAttempt"] = r.takesNextAttempt
            if (r.takesRefused) o["takesRefused"] = true
        }
        // 3.0.1: the count of consent changes of its creation (an item made before reads 0)
        if (r.consent != 0) o["consent"] = r.consent
        try {
            val dir = File(root, r.id)
            if (dir.isDirectory) ShareFiles.writeJson(File(dir, ITEM), o)
        } catch (_: Exception) {
        }
    }

    private fun load(f: File): Record? {
        val o = ShareFiles.readJson(f) ?: return null
        val r = Record(o["id"] as? String ?: return null)
        r.seq = (o["seq"] as? Double)?.toInt() ?: 0
        r.state = o["state"] as? String ?: "active"
        r.started = o["started"] as? Boolean ?: false
        (o["urls"] as? List<*>)?.forEach { x ->
            val u = x as? Map<*, *> ?: return@forEach
            val n = (u["n"] as? Double)?.toInt() ?: return@forEach
            r.urls[n] = (u["url"] as? String ?: return@forEach) to ((u["expires_at"] as? Double)?.toLong() ?: 0)
        }
        (o["uploaded"] as? List<*>)?.forEach { (it as? String)?.let(r.uploaded::add) }
        r.failCode = o["failCode"] as? String
        r.attempts = (o["attempts"] as? Double)?.toInt() ?: 0
        r.lastError = o["lastError"] as? String
        r.nextAttempt = (o["nextAttempt"] as? Double)?.toLong() ?: 0
        r.takes = (o["takes"] as? String)?.takeIf { it == TAKES_PENDING || it == TAKES_SENT || it == TAKES_DROPPED }
        r.takesAttempts = (o["takesAttempts"] as? Double)?.toInt() ?: 0
        r.takesNextAttempt = (o["takesNextAttempt"] as? Double)?.toLong() ?: 0
        r.takesRefused = o["takesRefused"] as? Boolean ?: false
        r.consent = (o["consent"] as? Double)?.toInt() ?: 0
        return r
    }

    companion object {
        const val ITEM = "item.json"

        /** The marker of a folder that was removed for good and could not be deleted at once. */
        internal const val GONE = "removed"

        /**
         * Tests only (the shared restart cases of SPEC 11.7): stands in for the deletion of a recording's folder, so
         * a test can have a storage that refuses, or a process that ends at that point. Null: the real deletion.
         */
        @Volatile internal var deleteForTests: ((File) -> Boolean)? = null

        /**
         * How long turning sharing off waits for the uploader to leave its pass, ms. With [ENCODERS_LEAVE_MS] it
         * stays inside the 3000 ms the call may take (SPEC 11.7, R2).
         */
        internal const val PASS_LEAVES_MS = 2_000L

        /** How long turning sharing off waits for the encoders of the recordings it ends, ms. */
        internal const val ENCODERS_LEAVE_MS = 700L

        /** How many removed recordings the outbox remembers. */
        private const val GONE_KEPT = 512
        internal const val TAKES_PENDING = "pending"
        internal const val TAKES_SENT = "sent"
        internal const val TAKES_DROPPED = "dropped"
    }
}
