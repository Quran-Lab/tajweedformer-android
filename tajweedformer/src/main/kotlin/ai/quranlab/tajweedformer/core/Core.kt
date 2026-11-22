package ai.quranlab.tajweedformer.core

import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.at
import java.io.File

/** A Core call that failed: the Core's status ([status], negative) and its refusal code ([detail]). */
internal class CoreException(val status: Int, val detail: Int, val call: String) :
    RuntimeException("$call: ${Core.statusName(status)}${Core.detailName(status, detail)?.let { " ($it)" } ?: if (detail != 0) " (detail $detail)" else ""}") {
    override fun fillInStackTrace(): Throwable = this

    /** The SDK's error for this failure (the catalogue's codes). */
    fun toError(): TajweedError = when (status) {
        Core.E_NEEDS_CONNECTION -> RecognizerException.NeedsConnection(this)
        Core.E_STOPPED -> RecognizerException.NeedsConnection(this)
        Core.E_STORAGE -> RecognizerException.StorageFailed("the model's files: $message")
        Core.E_MANIFEST -> if (detail == Core.MANIFEST_SIGNATURE) RecognizerException.SignatureInvalid(message ?: "") else RecognizerException.ManifestInvalid(message ?: "")
        Core.E_FILE -> if (detail == Core.D_FILE_PLAIN_SHA256) RecognizerException.ChecksumMismatch(message ?: "") else RecognizerException.ManifestInvalid(message ?: "")
        // a lease the Core could not use (not this install's, another build, a broken signature): the next call asks
        // again; one past its time is a lease to renew
        Core.E_LEASE -> if (detail == Core.D_LEASE_EXPIRED) RecognizerException.NeedsConnection(this) else RecognizerException.DownloadFailed("lease", message ?: "")
        // the install key store failed (as 3.x's key store failures): the next call tries again
        Core.E_KEYSTORE -> RecognizerException.StorageFailed("the install key store: $message")
        Core.E_ENGINE, Core.E_NOMEM -> RecognizerException.EngineFailed(message ?: "")
        else -> RecognizerException.EngineFailed(message ?: "")
    }

    /**
     * The SDK's error for a failed `qlc_model_open`: a status answered
     * `engine_failed` ([openCause]: QLC_E_NOMEM, QLC_E_BUSY, QLC_E_ENGINE, QLC_E_ARG) names itself in the report's
     * `error_code`, its reason `memory` for QLC_E_NOMEM and `engine` for the others, and the app's error keeps this
     * failure as its cause; the code stays `engine_failed`. Any other status is [toError]'s (the open tags it).
     */
    fun toOpenError(): TajweedError {
        val (reason, code) = openCause(status) ?: return toError()
        return RecognizerException.EngineFailed(message ?: "", this)
            .at(ai.quranlab.tajweedformer.cloud.ErrorCause("model_open", reason, errorName = REPORT_NAME, errorCode = code))
    }

    companion object {
        /** The report's `error_name` of a Core failure (24.2: the Kotlin exception's class; a constant, so an app's code
         *  shrinker that renames the class changes nothing in the report). */
        const val REPORT_NAME = "ai.quranlab.tajweedformer.core.CoreException"

        /** 24.3 (`errors_40.json` `core_open`): the reason and `error_code` of a Core status that fails the model's open
         *  with `engine_failed`, or null for a status that is another error. */
        @JvmStatic
        fun openCause(status: Int): Pair<String, String>? = when (status) {
            Core.E_NOMEM -> "memory" to "nomem"
            Core.E_BUSY -> "engine" to "busy"
            Core.E_ENGINE -> "engine" to "engine"
            Core.E_ARG -> "engine" to "arg"
            else -> null
        }
    }
}

/**
 * What the Core calls back into Kotlin through the bridge (native/core_jni.c looks these up by name and signature;
 * consumer-rules.pro keeps them): the install key store, the saved state and its seal, the monotonic counter, a stop's
 * removal of the models, and (tests only) the clocks.
 */
internal open class CoreHost(
    private val identity: InstallIdentity,
    private val key: AgreementKey,
    private val vault: StateVault,
    private val stateFile: File,
    /** A verified stop: every model file goes (SPEC 15.3). */
    private val onRemoveAll: () -> Boolean,
) {
    open fun agree(eph: ByteArray): ByteArray? = try { identity.agree(key, eph) } catch (_: Exception) { null }

    open fun decryptRsa(c: ByteArray): ByteArray? = try { identity.decryptRsa(key, c) } catch (_: Exception) { null }

    open fun sign(msg: ByteArray): ByteArray? = try { identity.sign(msg) } catch (_: Exception) { null }

    /** The Core's sealed state: written whole, then renamed over the old one. */
    open fun saveState(blob: ByteArray) {
        try {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            java.io.FileOutputStream(tmp).use { o ->
                o.write(blob)
                o.fd.sync()
            }
            if (!tmp.renameTo(stateFile)) {
                stateFile.delete()
                tmp.renameTo(stateFile)
            }
        } catch (_: Exception) {
        }
    }

    open fun stateSeal(data: ByteArray): ByteArray? = vault.seal(data)
    open fun stateOpen(data: ByteArray): ByteArray? = vault.open(data)
    open fun counterGet(): Long = vault.counterGet()
    open fun counterRaise(v: Long): Boolean = vault.counterRaise(v)
    open fun removeAll(@Suppress("UNUSED_PARAMETER") form: Int): Boolean = try { onRemoveAll() } catch (_: Exception) { false }

    /** Tests only (Core.FLAG_HOST_CLOCKS): the device's wall clock and boot clock in ms. */
    open fun wallMs(): Long = System.currentTimeMillis()
    open fun bootMs(): Long = System.nanoTime() / 1_000_000
}

/** The Core's answer to `qlc_tick`. Times are the Core's clock, in seconds. */
internal data class LeaseStatus(val hasLease: Boolean, val valid: Boolean, val refreshDue: Boolean, val stopped: Boolean,
                                val now: Long, val exp: Long, val refreshAfter: Long)

/**
 * A finished take's recited range (the Core's detector; never shown to the app), with R5's [day] (the UTC day it was
 * counted into, days since 1970), [context] (the session context decided it), [ties] (equally good ranges besides it)
 * and [reported] (the SDK marked the take counted, [Core.recitedReported]).
 */
internal data class RecitedResult(val found: Boolean, val surah: Int, val first: Int, val last: Int, val score: Int,
                                  val day: Long = 0, val context: Boolean = false, val ties: Int = 0, val reported: Boolean = false) {
    companion object {
        fun of(o: IntArray) = RecitedResult(o[0] != 0, o[1], o[2], o[3], o[4], o[5].toLong(), o[6] != 0, o[7], o[8] != 0)
    }
}

/** The learner days the Core keeps in its journal (qlc_days, R10). Days are days since 1970. */
internal class CoreDays(val firstDay: Long, val active: List<Long>, val local: List<Long>, val streakDays: List<Long>, val streaks: List<Int>,
                        val sealed: List<Long>, val weeks: List<Long>, val judged: List<Int>, val correct: List<Int>, val trendWeek: Long) {
    companion object {
        private const val KEEP = 40
        private const val STREAKS = 10
        private const val WEEKS = 6

        fun of(v: LongArray): CoreDays {
            val n = IntArray(5) { v[1 + it].toInt() }
            var k = 6
            fun take(cap: Int, count: Int): List<Long> = (0 until cap).map { v[k + it] }.take(count).also { k += cap }
            val active = take(KEEP, n[0])
            val local = take(KEEP, n[1])
            val streakDays = take(STREAKS, n[2])
            val streaks = take(STREAKS, n[2]).map { it.toInt() }
            val sealed = take(STREAKS, n[3])
            val weeks = take(WEEKS, n[4])
            val judged = take(WEEKS, n[4]).map { it.toInt() }
            val correct = take(WEEKS, n[4]).map { it.toInt() }
            return CoreDays(v[0], active, local, streakDays, streaks, sealed, weeks, judged, correct, v[k])
        }
    }
}

/** The Core's two daily totals of the recited journal. */
internal data class RecitedTotals(val day: Long, val takes: Int, val ayahs: Int, val prevDay: Long, val prevTakes: Int, val prevAyahs: Int)

/**
 * One Core handle (qlc_core): the install key it was made with, the saved state, the lease, the recited journal. The
 * Core serialises its own calls; the SDK makes one handle per install key and process.
 *
 * Files: the Core's own (the state blob, the recited journal) in [coreDir]; a model build's in the three folders
 * [roots] names (plain files, the downloaded CDN forms, the device forms the Core writes at install).
 */
internal class Core private constructor(handle: Long, val key: AgreementKey, private val host: CoreHost) : AutoCloseable {
    @Volatile private var h = handle

    /**
     * Every native call of this handle holds the read side of [gate] (calls still run side by side: the Core serialises
     * its own), and [close] takes the write side before the destroy: a call never meets freed memory. A call after
     * [close] began fails with [E_CLOSED], a [CoreException] like any other (the SDK's normal error path), and touches
     * nothing native.
     */
    private val gate = java.util.concurrent.locks.ReentrantReadWriteLock()
    @Volatile private var closed = false

    private inline fun <T> gated(call: String, f: (Long) -> T): T {
        val r = gate.readLock()
        r.lock()
        try {
            val x = h
            if (closed || x == 0L) throw CoreException(E_CLOSED, 0, call)
            return f(x)
        } finally {
            r.unlock()
        }
    }

    private fun check(x: Long, call: String, status: Int) {
        if (status < 0) throw CoreException(status, CoreNative.detail(x), call)
    }

    /** The folders of the build in use; set before an install, a lease accept or an open of that build. */
    fun roots(plain: File, cdn: File, device: File) = gated("qlc_set_roots") { x -> CoreNative.setRoots(x, plain.path, cdn.path, device.path) }

    /** The `core` object (JSON) of POST /v1/model-key and its `install_sig`. */
    fun leaseAsk(challenge: String, build: String): Pair<String, String> = gated("qlc_lease_ask") { x ->
        val out = IntArray(2)
        val r = CoreNative.leaseAsk(x, challenge, build, out) ?: throw CoreException(out[0], out[1], "qlc_lease_ask")
        r[0] to r[1]
    }

    /** Accepts a lease for [build] of [manifest]: its expiry and refresh time (server seconds). The Core saves its state
     *  with the lease ([CoreHost.saveState]) before this returns. */
    fun leaseAccept(lease: ByteArray, manifest: ByteArray, build: String): Pair<Long, Long> = gated("qlc_lease_accept") { x ->
        val t = LongArray(2)
        check(x, "qlc_lease_accept", CoreNative.leaseAccept(x, lease, manifest, build, t))
        t[0] to t[1]
    }

    /** A refusal body of the lease route: true when the Core verified it as a signed stop (the lease is gone and the
     *  models are removed, now or when the open model closes). */
    fun refusalAccept(body: ByteArray, apiKeyId: String): Boolean =
        gated("qlc_refusal_accept") { x -> CoreNative.refusalAccept(x, body, apiKeyId) == E_STOPPED }

    fun leasePiece(piece: ByteArray): Boolean = gated("qlc_lease_piece") { x -> CoreNative.leasePiece(x, piece) == OK }

    /** 18.4.7.1: tells the Core how a lease attempt that brought no lease ended; the Core's status (OK, E_NEEDS_CONNECTION
     *  without a lease, E_STOPPED after a verified stop). */
    fun leaseAttempt(refused: Boolean): Int =
        gated("qlc_lease_attempt") { x -> CoreNative.leaseAttempt(x, if (refused) ATTEMPT_REFUSED else ATTEMPT_UNREACHABLE) }

    /** 18.4.7.1: whether the Core opens the lease now while Quran Lab cannot be reached, and until when (0 without a lease). */
    fun grace(): Pair<Boolean, Long> = gated("qlc_lease_grace") { x ->
        val out = LongArray(1)
        (CoreNative.leaseGrace(x, out) == 1) to out[0]
    }

    fun tick(): LeaseStatus = gated("qlc_tick") { x ->
        val o = LongArray(7)
        check(x, "qlc_tick", CoreNative.tick(x, o))
        LeaseStatus(o[0] != 0L, o[1] != 0L, o[2] != 0L, o[3] != 0L, o[4], o[5], o[6])
    }

    /** Audio seconds the streams processed: (UTC day, seconds) today and the day before, by the Core's clock. */
    fun usage(): DoubleArray = gated("qlc_usage") { x ->
        val o = DoubleArray(4)
        check(x, "qlc_usage", CoreNative.usage(x, o))
        o
    }

    /** CDN form to device form of one encrypted file, its plaintext SHA-256 checked once. */
    fun install(manifest: ByteArray, decodedPath: String) =
        gated("qlc_install") { x -> check(x, "qlc_install", CoreNative.install(x, manifest, decodedPath)) }

    fun modelOpen(manifest: ByteArray, context: String?, threads: Int, sampleRate: Int, noPin: Int): CoreModel = gated("qlc_model_open") { x ->
        val out = IntArray(2)
        val m = CoreNative.modelOpen(x, manifest, context, threads, 0, sampleRate, 1, noPin, out)
        if (m == 0L) throw CoreException(out[0], out[1], "qlc_model_open")
        CoreModel(m)
    }

    fun recitedIndex(manifest: ByteArray) =
        gated("qlc_recited_index") { x -> check(x, "qlc_recited_index", CoreNative.recitedIndex(x, manifest)) }

    /** True when [take] was finished before (it counts once). */
    fun recitedBegin(take: Long): Boolean = gated("qlc_recited_begin") { x ->
        val r = CoreNative.recitedBegin(x, take)
        check(x, "qlc_recited_begin", r)
        r == 1
    }

    /** The id of a take left open (a kill), or null. */
    fun recitedOpen(): Long? = gated("qlc_recited_open") { x ->
        val o = LongArray(1)
        val r = CoreNative.recitedOpen(x, o)
        check(x, "qlc_recited_open", r)
        if (r == 1) o[0] else null
    }

    fun recitedFeedStream(stream: Long) =
        gated("qlc_recited_feed_stream") { x -> check(x, "qlc_recited_feed_stream", CoreNative.recitedFeedStream(x, stream)) }

    fun recitedFeed(units: IntArray) =
        gated("qlc_recited_feed") { x -> check(x, "qlc_recited_feed", CoreNative.recitedFeed(x, units, units.size)) }

    fun recitedFinish(take: Long, flags: Int): RecitedResult = gated("qlc_recited_finish") { x ->
        val o = IntArray(9)
        check(x, "qlc_recited_finish", CoreNative.recitedFinish(x, take, flags, o))
        RecitedResult.of(o)
    }

    /** The finished takes the journal remembers (the last 64), oldest first: id and result (R5). */
    fun recitedDone(): List<Pair<Long, RecitedResult>> = gated("qlc_recited_done") { x ->
        val id = LongArray(1)
        val o = IntArray(9)
        val n = CoreNative.recitedDone(x, -1, id, o)
        check(x, "qlc_recited_done", n)
        (0 until n).mapNotNull { i ->
            if (CoreNative.recitedDone(x, i, id, o) > i) id[0] to RecitedResult.of(o) else null
        }
    }

    /** Marks a finished take as counted by the SDK (its report sent), so a restart never counts it again. */
    fun recitedReported(take: Long): Boolean = gated("qlc_recited_reported") { x -> CoreNative.recitedReported(x, take) == OK }

    // ---- R10: the learner days, in the recited journal (committed with it: a kill leaves the last state) ----
    fun daysMark(utcDay: Long, localDay: Long) = gated("qlc_days_mark") { x -> check(x, "qlc_days_mark", CoreNative.daysMark(x, utcDay, localDay)) }
    fun daysJudged(utcDay: Long, judged: Int, correct: Int) =
        gated("qlc_days_judged") { x -> check(x, "qlc_days_judged", CoreNative.daysJudged(x, utcDay, judged, correct)) }
    fun daysSeal(utcDay: Long) = gated("qlc_days_seal") { x -> check(x, "qlc_days_seal", CoreNative.daysSeal(x, utcDay)) }
    fun daysTrendSent(week: Long) = gated("qlc_days_trend_sent") { x -> check(x, "qlc_days_trend_sent", CoreNative.daysTrendSent(x, week)) }
    fun daysRead(): CoreDays = gated("qlc_days_read") { x -> CoreDays.of(CoreNative.daysRead(x) ?: throw CoreException(E_ENGINE, 0, "qlc_days_read")) }

    /** Whether the lease in force allows accelerator programs (flags bit 3): asked before the program's download. */
    fun accelAllowed(): Boolean = gated("qlc_accel_allowed") { x ->
        val r = CoreNative.accelAllowed(x)
        check(x, "qlc_accel_allowed", r)
        r == 1
    }

    /**
     * The accelerator program of [kind] (`npu:<soc>`) decrypted into the Core's memory and compiled by the runtime from
     * there (R2): the backend's name. Throws [CoreException] (status, `QLC_D_ACCEL_*` or `QLC_D_LEASE_NO_ACCEL`) when the
     * device runs the CPU engine instead; the model and its CPU streams are untouched. Seconds long: a background
     * thread, never the audio one, and never while the model is being closed.
     */
    fun accelPrepare(model: CoreModel, kind: String, dispatchDir: String?): String = gated("qlc_accel_prepare") { x ->
        val st = IntArray(2)
        CoreNative.accelPrepare(x, model.handle, kind, dispatchDir, st) ?: throw CoreException(st[0], st[1], "qlc_accel_prepare")
    }

    fun recitedTotals(): RecitedTotals = gated("qlc_recited_totals") { x ->
        val o = LongArray(6)
        check(x, "qlc_recited_totals", CoreNative.recitedTotals(x, o))
        RecitedTotals(o[0], o[1].toInt(), o[2].toInt(), o[3], o[4].toInt(), o[5].toInt())
    }

    /**
     * Destroys the handle, once (a second call does nothing). New calls fail with [E_CLOSED] from here on; the calls
     * already running end first (the write side of [gate] waits for them), at most [CLOSE_WAIT_MS] on this thread, after
     * which a thread of its own waits for the last of them and destroys the handle then. Never on freed memory.
     */
    override fun close() = close(CLOSE_WAIT_MS)

    /** [close], waiting at most [waitMs] on this thread for the calls in flight. */
    fun close(waitMs: Long) {
        synchronized(gate) {
            if (closed) return
            closed = true
        }
        val w = gate.writeLock()
        val now = try { w.tryLock(maxOf(0L, waitMs), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (now) {
            try { destroyNow() } finally { w.unlock() }
        } else {
            Thread({ w.lock(); try { destroyNow() } finally { w.unlock() } }, "tajweedformer-core-close").apply { isDaemon = true }.start()
        }
    }

    private fun destroyNow() {
        val x = h
        h = 0
        if (x != 0L) CoreNative.destroy(x)
    }

    /** The bridge's handle, for a [CoreModel]'s streams' recited feed. */
    internal val raw: Long get() = gated("raw") { x -> x }

    companion object {
        const val OK = 0
        const val E_ARG = -1
        const val E_NOMEM = -2
        const val E_STORAGE = -3
        const val E_MANIFEST = -4
        const val E_NEEDS_CONNECTION = -5
        const val E_LEASE = -6
        const val E_FILE = -7
        const val E_ENGINE = -8
        const val E_STOPPED = -9
        const val E_KEYSTORE = -10
        const val E_BUSY = -11
        /** The SDK's own status (no Core call returns it): a call on a handle [close] has begun to destroy. */
        const val E_CLOSED = -100

        /** How long [close] waits on its own thread for the native calls already running (the destroy waits for them). */
        const val CLOSE_WAIT_MS = 3_000L

        /** qlc_lease_attempt's outcomes (18.4.7.1). */
        const val ATTEMPT_UNREACHABLE = 1
        const val ATTEMPT_REFUSED = 2

        /** qlc_detail of a manifest whose signature no pinned key verifies (core/src/manifest.h QM_E_SIGNATURE). */
        const val MANIFEST_SIGNATURE = 3

        /** qlc_detail's codes by name (qlcore.h QLC_D_*): after E_LEASE, E_FILE, and E_ENGINE from qlc_accel_prepare. */
        const val D_LEASE_EXPIRED = 8
        const val D_LEASE_NO_ACCEL = 33
        const val D_FILE_PLAIN_SHA256 = 15
        const val D_ACCEL_RUNTIME = 1
        const val D_ACCEL_HOOKED = 2
        const val D_ACCEL_PROGRAM = 3
        const val D_ACCEL_CONTRACT = 4
        const val D_ACCEL_PLATFORM = 5
        const val D_ACCEL_FILES = 6
        /** Core 69276aa: this firmware's NPU service is known not to set a program up (the Pixel 10 family's launch
         *  firmware, BD1A.250702), refused in milliseconds before anything is decrypted; an OS update brings it back. */
        const val D_ACCEL_FIRMWARE = 7
        /** Core 69276aa: the vendor's NPU service did not answer while the program was set up; every later prepare in
         *  the process is refused at once. */
        const val D_ACCEL_DISPATCH = 8

        /** qlc_stream_create_on and qlc_stream_backend. */
        const val BACKEND_CPU = 0
        const val BACKEND_ACCEL = 1
        const val BACKEND_NPU = 2

        private val LEASE_DETAILS = mapOf(1 to "format", 2 to "signature", 3 to "install", 4 to "release", 5 to "build", 6 to "key_id",
            7 to "model", 8 to "expired", 10 to "outer_tag", 11 to "inner_tag", 12 to "site", 32 to "unwrap", 33 to "no_accel")
        private val FILE_DETAILS = mapOf(1 to "magic", 2 to "version", 3 to "header_len", 4 to "form", 5 to "suite", 6 to "payload",
            7 to "record_shift", 8 to "reserved", 9 to "variant", 10 to "size", 11 to "path_hash", 12 to "key_id", 13 to "index", 14 to "tag",
            15 to "plain_sha256", 16 to "notice")
        private val ACCEL_DETAILS = mapOf(1 to "runtime", 2 to "hooked", 3 to "program", 4 to "contract", 5 to "platform", 6 to "files",
            7 to "firmware", 8 to "dispatch")

        /** The name of a detail code (qlcore.h's QLC_D_* without the prefix, lower case), or null. */
        fun detailName(status: Int, detail: Int): String? = when (status) {
            E_LEASE -> LEASE_DETAILS[detail]?.let { "lease_$it" }
            E_FILE -> FILE_DETAILS[detail]?.let { "file_$it" }
            E_ENGINE -> ACCEL_DETAILS[detail]?.let { "accel_$it" }
            else -> null
        }

        /** The bridge's create flags. */
        const val FLAG_RSA = 1
        const val FLAG_HOST_CLOCKS = 2
        const val FLAG_NO_COUNTER = 4
        const val FLAG_NO_SEAL = 8

        /** Risk signals the platform reports. */
        const val SIGNAL_ACCEL_HOOK = 8
        const val SIGNAL_SOFTWARE_KEYSTORE = 16

        /** qlc_recited_finish flags. */
        const val RECITED_NO_CONTEXT = 1
        const val RECITED_NO_CROSS_SURAH = 2

        fun statusName(s: Int): String = when (s) {
            OK -> "ok"; E_ARG -> "argument"; E_NOMEM -> "no memory"; E_STORAGE -> "storage"; E_MANIFEST -> "manifest"
            E_NEEDS_CONNECTION -> "needs a lease"; E_LEASE -> "lease refused"; E_FILE -> "file refused"; E_ENGINE -> "engine"
            E_STOPPED -> "stopped"; E_KEYSTORE -> "key store"; E_BUSY -> "busy"; E_CLOSED -> "closed"; else -> "status $s"
        }

        /**
         * Makes a Core handle for [key]. [state]: what [CoreHost.saveState] stored last (null on a first start). Throws
         * [CoreException] when the Core refuses to start, and `runtime_unsupported` when the Core is not in the app.
         */
        fun open(host: CoreHost, key: AgreementKey, state: ByteArray?, coreDir: File, bootId: ByteArray, signals: Int, flags: Int): Core {
            if (!CoreLibrary.available) throw RecognizerException.RuntimeUnsupported(
                "the Quran Lab Core did not load on this CPU (${System.getProperty("os.arch") ?: "unknown"}): ${CoreLibrary.loadError}")
            coreDir.mkdirs()
            val none = File(coreDir, "none").path
            val roots = arrayOf(none, none, none, coreDir.path)
            val out = IntArray(2)
            val f = flags or (if (key.rsa) FLAG_RSA else 0)
            val h = CoreNative.create(host, key.spki, state, roots, bootId, signals, f, out)
            if (h == 0L) throw CoreException(out[0], out[1], "qlc_create")
            return Core(h, key, host)
        }
    }
}

/** An open model of the Core (qlc_model): the device forms decrypted into the Core's memory. Its own calls and its close
 *  are gated as [Core]'s are; its streams (CoreEngine) are closed before it. */
internal class CoreModel(handle: Long) : AutoCloseable {
    @Volatile private var h = handle
    private val gate = java.util.concurrent.locks.ReentrantReadWriteLock()
    @Volatile private var closed = false

    private inline fun <T> gated(call: String, f: (Long) -> T): T {
        val r = gate.readLock()
        r.lock()
        try {
            val x = h
            if (closed || x == 0L) throw CoreException(Core.E_CLOSED, 0, call)
            return f(x)
        } finally {
            r.unlock()
        }
    }

    /** The model's handle, for its streams (CoreEngine, which closes them before the model): [Core.E_CLOSED] once closed. */
    val handle: Long get() = gated("qlc_model") { x -> x }

    val chunkMs: Int get() = gated("qlc_model_chunk_ms") { x -> CoreNative.chunkMs(x) }

    /** `calibration.json` as shipped (public data), or null. */
    val calibration: ByteArray? get() = gated("qlc_model_calibration") { x -> CoreNative.calibration(x) }

    /** The build's vocabulary (its tokens.txt, R4): the names of the output units by id. */
    fun tokenNames(): List<String> = gated("qlc_model_token_count") { x ->
        val n = CoreNative.tokenCount(x)
        if (n <= 0) throw CoreException(if (n < 0) n else Core.E_ENGINE, 0, "qlc_model_token_count")
        (0 until n).map { CoreNative.tokenName(x, it) ?: throw CoreException(Core.E_ENGINE, 0, "qlc_model_token_name") }
    }

    /**
     * One ayah's expected units (R3: tokens.txt ids in reading order, the word bar between words) and its words' first
     * units. Read from the build's reference data at the first call: that needs a valid lease (E_NEEDS_CONNECTION,
     * E_LEASE, E_KEYSTORE then; a later call tries again); E_FILE when the build has none, it is not installed, or it
     * was refused; E_ARG for an ayah the table does not have.
     */
    fun referenceExpected(surah: Int, ayah: Int): Pair<IntArray, IntArray> = gated("qlc_reference_expected") { x ->
        val st = IntArray(1)
        val r = CoreNative.referenceExpected(x, surah, ayah, st) ?: throw CoreException(st[0], 0, "qlc_reference_expected")
        r[0] to r[1]
    }

    /** The ayahs the reference table has of [surah] (0 for one it does not have). */
    fun referenceAyahCount(surah: Int): Int = gated("qlc_reference_ayah_count") { x -> CoreNative.referenceAyahCount(x, surah) }

    /** Frees the accelerator program (E_BUSY while a stream runs on it); closing the model does the same. */
    fun accelRelease(): Int = gated("qlc_accel_release") { x -> CoreNative.accelRelease(x) }

    /** Closes the model once, after the calls already running end (as [Core.close]). */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
        }
        val w = gate.writeLock()
        val now = try { w.tryLock(Core.CLOSE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (now) {
            try { closeNow() } finally { w.unlock() }
        } else {
            Thread({ w.lock(); try { closeNow() } finally { w.unlock() } }, "tajweedformer-model-close").apply { isDaemon = true }.start()
        }
    }

    private fun closeNow() {
        val x = h
        h = 0
        if (x != 0L) CoreNative.modelClose(x)
    }
}
