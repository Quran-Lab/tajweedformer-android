package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedFormer
import ai.quranlab.tajweedformer.cloud.ApiError
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.Day
import ai.quranlab.tajweedformer.cloud.ErrorAction
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One shared session in the outbox. */
data class Contribution(
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
) {
    enum class Status { QUEUED, UPLOADED, FAILED }
}

/** What the outbox needs to know from the rest of the SDK, read at every pass. */
internal interface SharingGate {
    /** Sharing is on here: the app's option, the learner's consent, the signed config's flags and trust tier. */
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
    /** A refusal that is an SDK bug (400): one issue a day. */
    fun badRequest(route: String) {}
}

/**
 * One session being shared: Ogg Opus segments written while the session runs, into its folder in the
 * outbox, each closed segment handed to the outbox at once. Audio is copied on the caller's thread
 * and encoded on a thread of its own, so recognition never waits for the encoder.
 */
internal class ShareCapture(
    private val outbox: ContributionOutbox,
    /** The session JSON for an input rate and route. */
    private val session: (Int, String) -> ByteArray,
    private val route: String,
    private val stillOn: () -> Boolean,
) {
    val id: String = UUID.randomUUID().toString().replace("-", "").lowercase()
    val folder = File(outbox.root, id)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tj-share").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private var writer: OggOpusWriter? = null
    private var rate = 0
    @Volatile private var failed = false
    private var segments = 0
    @Volatile private var ended = false

    init { ACTIVE.add(id) }

    /** Mono samples at [sampleRate]; a different rate than the session's first is ignored (one rate per session). */
    fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        if (failed || ended || length <= 0) return
        val copy = samples.copyOfRange(offset, offset + length)
        worker.execute {
            if (failed) return@execute
            if (!stillOn()) { abandonNow(); return@execute }
            try {
                if (writer == null) {
                    folder.mkdirs()
                    File(folder, ShareFiles.SESSION).writeBytes(session(sampleRate, route))
                    rate = sampleRate
                    val w = OggOpusWriter({ File(folder, ShareFiles.name(it) + ".opus") }, SegmentPlan.SECONDS, sampleRate) { closed(it) }
                    ShareFiles.writeJson(File(folder, ShareFiles.CAPTURE), linkedMapOf("preSkip" to w.preSkip))
                    writer = w
                }
                if (sampleRate == rate) writer?.append(copy)
            } catch (_: Exception) {
                abandonNow()
            }
        }
    }

    private fun closed(seg: OggSegment) {
        segments = seg.n
        try {
            ShareFiles.mark(folder, seg.n, seg.granule)
        } catch (_: Exception) {
            failed = true
            return
        }
        outbox.poke()
    }

    /**
     * Ends the capture: the last segment and `end.json`; or nothing kept when sharing went off, or a
     * failed or cancelled session is shorter than [MINIMUM_PARTIAL_S] with no segment gone.
     */
    fun finish(completed: Boolean) {
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

    /** Waits until the audio given so far is encoded and on disk. */
    fun sync() { if (!worker.isShutdown) worker.submit {}.get() }

    /** Waits for the encoder thread (tests, and before the process is torn down). */
    fun await(ms: Long = 30_000) { worker.awaitTermination(ms, TimeUnit.MILLISECONDS) }

    private fun abandonNow() {
        writer?.abandon()
        writer = null
        failed = true
        // segments already handed over stay with the outbox only while sharing is on
        if (segments == 0 || !stillOn()) folder.deleteRecursively() else ShareFiles.recover(folder)
        release()
    }

    private fun release() { ACTIVE.remove(id) }

    companion object {
        /** A failed or cancelled session is shared only from this long. */
        const val MINIMUM_PARTIAL_S = 2.0
        /** Folders being written in this process (so the outbox does not take them for crashed). */
        val ACTIVE: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /**
         * The session JSON (closed schema of POST /v1/uploads), sorted keys as the Swift SDK writes it.
         * `contributor_key` and `recovery_hash` bind the device key at the first session (P2.11).
         */
        fun sessionJson(modelRevision: String?, backend: String, weightsSha256: String?, consentVersion: String?, contributor: String,
                        contributorKey: String?, recoveryHash: String?, day: String, deviceClass: String, osMajor: Int,
                        inputSampleRate: Int, inputRoute: String): ByteArray {
            val audio = sortedMapOf<String, Any?>("codec" to "opus", "container" to "ogg", "sample_rate" to 48_000, "channels" to 1,
                "input_sample_rate" to inputSampleRate.coerceIn(8_000, 192_000), "input_route" to inputRoute)
            val decode = sortedMapOf<String, Any?>("model_revision" to modelRevision?.take(64), "backend" to backend,
                "weights_sha256" to weightsSha256, "sdk_version" to TajweedFormer.VERSION)
            val o = sortedMapOf<String, Any?>("format" to 1, "audio" to audio, "decode" to decode, "contributor" to contributor,
                "day" to day, "device_class" to deviceClass, "os_major" to osMajor.coerceIn(1, 99))
            consentVersion?.let { o["consent_version"] = it }
            contributorKey?.let { o["contributor_key"] = it }
            recoveryHash?.let { o["recovery_hash"] = it }
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
 * Blocking; [drain] runs one pass at a time (a call during a pass runs one more after it).
 */
class ContributionOutbox internal constructor(
    val root: File,
    private val api: QuranLabApi,
    private val contributor: () -> String,
    private val gate: SharingGate,
    private val clock: () -> Long = System::currentTimeMillis,
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
    )

    private val records = HashMap<String, Record>()
    private val lock = Object()
    private var running = false
    private var again = false

    init { root.mkdirs() }

    /** Every session, oldest first. */
    val items: List<Contribution> get() = synchronized(records) { scan(); records.values.sortedBy { it.seq }.map(::snapshot) }

    fun item(id: String): Contribution? = synchronized(records) { scan(); records[id]?.let(::snapshot) }

    /** A capture closed a segment or ended. */
    internal fun poke() = onWork()

    /** Sharing was turned off: every session not fully uploaded goes, and nothing else is sent. */
    fun cancelQueued() = synchronized(records) {
        scan()
        for (r in records.values.toList()) if (r.state == "active" && r.id !in ShareCapture.ACTIVE) remove(r.id)
        // folders the outbox has not picked up yet
        for (f in root.listFiles() ?: emptyArray()) if (f.isDirectory && f.name !in ShareCapture.ACTIVE && records[f.name] == null) f.deleteRecursively()
    }

    /** Everything, uploaded or not (after a deletion). */
    internal fun clearAll() = synchronized(records) {
        records.clear()
        for (f in root.listFiles() ?: emptyArray()) if (f.name !in ShareCapture.ACTIVE) f.deleteRecursively()
    }

    /** The earliest retry due (ms), or null when nothing waits. */
    fun nextDue(): Long? = synchronized(records) { scan(); records.values.filter { it.state == "active" }.minOfOrNull { it.nextAttempt } }

    /** True while some session still has something to send. */
    fun hasWork(): Boolean = synchronized(records) { scan(); records.values.any { it.state == "active" } }

    /** One upload pass. [ignoringBackoff] is for tests and an explicit retry. Returns false when the pass stopped offline. */
    fun drain(ignoringBackoff: Boolean = false): Boolean {
        synchronized(lock) {
            if (running) { again = true; return true }
            running = true
        }
        var online = true
        try {
            do {
                synchronized(lock) { again = false }
                online = pass(ignoringBackoff)
            } while (synchronized(lock) { again } && online)
        } finally {
            synchronized(lock) { running = false }
        }
        return online
    }

    private fun pass(ignoringBackoff: Boolean): Boolean {
        val due = synchronized(records) {
            scan()
            expireOld()
            val now = clock()
            records.values.filter { it.state == "active" && (ignoringBackoff || it.nextAttempt <= now) }.sortedBy { it.seq }.map { it.id }
        }
        if (due.isEmpty()) return true
        if (!gate.uploadsAllowed()) {
            for (id in due) note(id, "sharing is not allowed here now; waiting")
            return true
        }
        if (!gate.networkAllowed()) return true
        for (id in due) {
            val token = try {
                gate.uploadToken()
            } catch (e: CloudException) {
                retry(id, "token: ${e.message}", longWait = !e.retryable)
                return !e.offline
            }
            if (!step(id, token)) return false
        }
        return true
    }

    /** Moves one session on as far as it goes now. False when offline (the pass stops). */
    private fun step(id: String, token: String): Boolean {
        val folder = File(root, id)
        val markers = ShareFiles.markers(folder)
        val end = ShareFiles.end(folder)
        var rec = synchronized(records) { records[id] } ?: return true
        if (!rec.started) {
            if (markers.isEmpty()) return true
            val session = try { File(folder, ShareFiles.SESSION).readBytes() } catch (_: Exception) { return true }
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
            val r = try {
                api.putSegment(url.first, File(folder, name))
            } catch (e: CloudException) {
                retry(id, "PUT $name: ${e.message}")
                return false
            }
            rec = synchronized(records) { records[id] } ?: return true
            if (rec.state != "active") return true
            if (r.ok) {
                rec.uploaded.add(name)
                rec.urls.remove(m.n)
                rec.lastError = null
                save(rec)
                File(folder, name).delete()
            } else {
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
        rec.state = "uploaded"
        rec.lastError = null
        rec.nextAttempt = 0
        rec.urls.clear()
        save(rec)
        // the item, the markers, the end and the session JSON stay (the status's numbers)
        for (f in folder.listFiles() ?: emptyArray()) {
            val n = f.name
            if (n != ITEM && n != ShareFiles.END && n != ShareFiles.SESSION && !(n.startsWith("seg-") && n.endsWith(".json"))) f.delete()
        }
        gate.uploaded(id)
        return true
    }

    @Volatile private var lastOffline = false

    /** A Worker call: the response on 2xx; otherwise the item is retried or failed and null comes back. */
    private fun send(id: String, route: String, call: () -> HttpResponse): HttpResponse? {
        lastOffline = false
        val r = try { call() } catch (e: CloudException) {
            lastOffline = true
            retry(id, e.message ?: "offline")
            return null
        }
        if (r.ok) return r
        val e = r.error
        val rec = synchronized(records) { records[id] }
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
            synchronized(records) { records[id] }?.let { it.attempts++; save(it) }
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
        save(rec)
        for (f in File(root, id).listFiles() ?: emptyArray()) if (f.name.endsWith(".opus")) f.delete()
    }

    private fun retry(id: String, why: String, immediately: Boolean = false, longWait: Boolean = false) {
        val rec = synchronized(records) { records[id] } ?: return
        rec.lastError = why
        val n = maxOf(1, rec.attempts)
        val delayS = when {
            immediately -> 0.0
            longWait -> 3600.0
            else -> minOf(30.0 * Math.pow(2.0, minOf(n - 1, 16).toDouble()), 6 * 3600.0) * (0.8 + 0.4 * Math.random())
        }
        rec.nextAttempt = clock() + (delayS * 1000).toLong()
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
        if (rec.lastError == why) return
        rec.lastError = why
        save(rec)
    }

    /** Unsent sessions older than 7 days are deleted (their URLs and the server's window are gone). */
    private fun expireOld() {
        val today = Day.of(clock())
        for (r in records.values.toList()) {
            if (r.state != "active" || r.id in ShareCapture.ACTIVE) continue
            val day = ShareFiles.readJson(File(File(root, r.id), ShareFiles.SESSION))?.get("day") as? String ?: continue
            if (try { Day.between(day, today) > 7 } catch (_: Exception) { false }) remove(r.id)
        }
    }

    /** Picks up folders the captures made, and recovers the ones a killed app left. Caller holds [records]. */
    private fun scan() {
        val names = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.length == 32 && it.name.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        for (f in names) {
            val name = f.name
            if (records[name] != null) continue
            val stored = load(File(f, ITEM))?.takeIf { it.id == name }
            if (stored != null) { records[name] = stored; continue }
            val capturing = name in ShareCapture.ACTIVE
            // an item appears with its first closed segment
            if (capturing && ShareFiles.markers(f).isEmpty()) continue
            if (!capturing && !File(f, ShareFiles.END).exists() && !ShareFiles.recover(f)) continue
            val rec = Record(name, seq = (records.values.maxOfOrNull { it.seq } ?: 0) + 1)
            records[name] = rec
            save(rec)
        }
        // a session whose capture is gone without an end (killed in an earlier run)
        for (rec in records.values.toList()) {
            if (rec.state != "active") continue
            val f = File(root, rec.id)
            if (rec.id !in ShareCapture.ACTIVE && !File(f, ShareFiles.END).exists() && !ShareFiles.recover(f)) records.remove(rec.id)
        }
    }

    private fun remove(id: String) = synchronized(records) {
        File(root, id).deleteRecursively()
        records.remove(id)
    }

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
        return Contribution(r.id, markers.size, markers.sumOf { it.bytes }, end?.durationMs ?: 0, day, end?.completed, status, r.attempts, r.lastError)
    }

    private fun save(r: Record) {
        synchronized(records) { records[r.id] = r }
        val o = linkedMapOf<String, Any?>("id" to r.id, "seq" to r.seq, "state" to r.state, "started" to r.started,
            "urls" to r.urls.map { (n, u) -> linkedMapOf("n" to n, "url" to u.first, "expires_at" to u.second) },
            "uploaded" to r.uploaded.toList(), "failCode" to r.failCode, "attempts" to r.attempts, "lastError" to r.lastError,
            "nextAttempt" to r.nextAttempt)
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
        return r
    }

    companion object {
        const val ITEM = "item.json"
    }
}
