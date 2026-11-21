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

    private fun handle(): Long = h.takeIf { it != 0L } ?: throw IllegalStateException("the Core is closed")

    private fun check(call: String, status: Int) {
        if (status < 0) throw CoreException(status, CoreNative.detail(handle()), call)
    }

    /** The folders of the build in use; set before an install, a lease accept or an open of that build. */
    fun roots(plain: File, cdn: File, device: File) = CoreNative.setRoots(handle(), plain.path, cdn.path, device.path)

    /** The `core` object (JSON) of POST /v1/model-key and its `install_sig`. */
    fun leaseAsk(challenge: String, build: String): Pair<String, String> {
        val out = IntArray(2)
        val r = CoreNative.leaseAsk(handle(), challenge, build, out) ?: throw CoreException(out[0], out[1], "qlc_lease_ask")
        return r[0] to r[1]
    }

    /** Accepts a lease for [build] of [manifest]: its expiry and refresh time (server seconds). */
    fun leaseAccept(lease: ByteArray, manifest: ByteArray, build: String): Pair<Long, Long> {
        val t = LongArray(2)
        check("qlc_lease_accept", CoreNative.leaseAccept(handle(), lease, manifest, build, t))
        return t[0] to t[1]
    }

    /** A refusal body of the lease route: true when the Core verified it as a signed stop (the lease is gone and the
     *  models are removed, now or when the open model closes). */
    fun refusalAccept(body: ByteArray, apiKeyId: String): Boolean = CoreNative.refusalAccept(handle(), body, apiKeyId) == E_STOPPED

    fun leasePiece(piece: ByteArray): Boolean = CoreNative.leasePiece(handle(), piece) == OK

    /** 18.4.7.1: tells the Core how a lease attempt that brought no lease ended; the Core's status (OK, E_NEEDS_CONNECTION
     *  without a lease, E_STOPPED after a verified stop). */
    fun leaseAttempt(refused: Boolean): Int = CoreNative.leaseAttempt(handle(), if (refused) ATTEMPT_REFUSED else ATTEMPT_UNREACHABLE)

    /** 18.4.7.1: whether the Core opens the lease now while Quran Lab cannot be reached, and until when (0 without a lease). */
    fun grace(): Pair<Boolean, Long> {
        val out = LongArray(1)
        return (CoreNative.leaseGrace(handle(), out) == 1) to out[0]
    }

    fun tick(): LeaseStatus {
        val o = LongArray(7)
        check("qlc_tick", CoreNative.tick(handle(), o))
        return LeaseStatus(o[0] != 0L, o[1] != 0L, o[2] != 0L, o[3] != 0L, o[4], o[5], o[6])
    }

    /** Audio seconds the streams processed: (UTC day, seconds) today and the day before, by the Core's clock. */
    fun usage(): DoubleArray {
        val o = DoubleArray(4)
        check("qlc_usage", CoreNative.usage(handle(), o))
        return o
    }

    /** CDN form to device form of one encrypted file, its plaintext SHA-256 checked once. */
    fun install(manifest: ByteArray, decodedPath: String) = check("qlc_install", CoreNative.install(handle(), manifest, decodedPath))

    fun modelOpen(manifest: ByteArray, context: String?, threads: Int, sampleRate: Int, noPin: Int): CoreModel {
        val out = IntArray(2)
        val m = CoreNative.modelOpen(handle(), manifest, context, threads, 0, sampleRate, 1, noPin, out)
        if (m == 0L) throw CoreException(out[0], out[1], "qlc_model_open")
        return CoreModel(m)
    }

    fun recitedIndex(manifest: ByteArray) = check("qlc_recited_index", CoreNative.recitedIndex(handle(), manifest))

    /** True when [take] was finished before (it counts once). */
    fun recitedBegin(take: Long): Boolean {
        val r = CoreNative.recitedBegin(handle(), take)
        check("qlc_recited_begin", r)
        return r == 1
    }

    /** The id of a take left open (a kill), or null. */
    fun recitedOpen(): Long? {
        val o = LongArray(1)
        val r = CoreNative.recitedOpen(handle(), o)
        check("qlc_recited_open", r)
        return if (r == 1) o[0] else null
    }

    fun recitedFeedStream(stream: Long) = check("qlc_recited_feed_stream", CoreNative.recitedFeedStream(handle(), stream))

    fun recitedFeed(units: IntArray) = check("qlc_recited_feed", CoreNative.recitedFeed(handle(), units, units.size))

    fun recitedFinish(take: Long, flags: Int): RecitedResult {
        val o = IntArray(9)
        check("qlc_recited_finish", CoreNative.recitedFinish(handle(), take, flags, o))
        return RecitedResult.of(o)
    }

    /** The finished takes the journal remembers (the last 64), oldest first: id and result (R5). */
    fun recitedDone(): List<Pair<Long, RecitedResult>> {
        val id = LongArray(1)
        val o = IntArray(9)
        val n = CoreNative.recitedDone(handle(), -1, id, o)
        check("qlc_recited_done", n)
        return (0 until n).mapNotNull { i ->
            if (CoreNative.recitedDone(handle(), i, id, o) > i) id[0] to RecitedResult.of(o) else null
        }
    }

    /** Marks a finished take as counted by the SDK (its report sent), so a restart never counts it again. */
    fun recitedReported(take: Long): Boolean = CoreNative.recitedReported(handle(), take) == OK

    // ---- R10: the learner days, in the recited journal (committed with it: a kill leaves the last state) ----
    fun daysMark(utcDay: Long, localDay: Long) = check("qlc_days_mark", CoreNative.daysMark(handle(), utcDay, localDay))
    fun daysJudged(utcDay: Long, judged: Int, correct: Int) = check("qlc_days_judged", CoreNative.daysJudged(handle(), utcDay, judged, correct))
    fun daysSeal(utcDay: Long) = check("qlc_days_seal", CoreNative.daysSeal(handle(), utcDay))
    fun daysTrendSent(week: Long) = check("qlc_days_trend_sent", CoreNative.daysTrendSent(handle(), week))
    fun daysRead(): CoreDays = CoreDays.of(CoreNative.daysRead(handle()) ?: throw CoreException(E_ENGINE, 0, "qlc_days_read"))

    /** Whether the lease in force allows accelerator programs (flags bit 3): asked before the program's download. */
    fun accelAllowed(): Boolean {
        val r = CoreNative.accelAllowed(handle())
        check("qlc_accel_allowed", r)
        return r == 1
    }

    /**
     * The accelerator program of [kind] (`npu:<soc>`) decrypted into the Core's memory and compiled by the runtime from
     * there (R2): the backend's name. Throws [CoreException] (status, `QLC_D_ACCEL_*` or `QLC_D_LEASE_NO_ACCEL`) when the
     * device runs the CPU engine instead; the model and its CPU streams are untouched. Seconds long: a background
     * thread, never the audio one, and never while the model is being closed.
     */
    fun accelPrepare(model: CoreModel, kind: String, dispatchDir: String?): String {
        val st = IntArray(2)
        return CoreNative.accelPrepare(handle(), model.handle, kind, dispatchDir, st) ?: throw CoreException(st[0], st[1], "qlc_accel_prepare")
    }

    fun recitedTotals(): RecitedTotals {
        val o = LongArray(6)
        check("qlc_recited_totals", CoreNative.recitedTotals(handle(), o))
        return RecitedTotals(o[0], o[1].toInt(), o[2].toInt(), o[3], o[4].toInt(), o[5].toInt())
    }

    override fun close() {
        val x = h
        h = 0
        if (x != 0L) CoreNative.destroy(x)
    }

    /** The bridge's handle, for a [CoreModel]'s streams' recited feed. */
    internal val raw: Long get() = handle()

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
            E_STOPPED -> "stopped"; E_KEYSTORE -> "key store"; E_BUSY -> "busy"; else -> "status $s"
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

/** An open model of the Core (qlc_model): the device forms decrypted into the Core's memory. */
internal class CoreModel(handle: Long) : AutoCloseable {
    @Volatile private var h = handle
    val handle: Long get() = h.takeIf { it != 0L } ?: throw IllegalStateException("the model is closed")

    val chunkMs: Int get() = CoreNative.chunkMs(handle)

    /** `calibration.json` as shipped (public data), or null. */
    val calibration: ByteArray? get() = CoreNative.calibration(handle)

    /** The build's vocabulary (its tokens.txt, R4): the names of the output units by id. */
    fun tokenNames(): List<String> {
        val n = CoreNative.tokenCount(handle)
        if (n <= 0) throw CoreException(if (n < 0) n else Core.E_ENGINE, 0, "qlc_model_token_count")
        return (0 until n).map { CoreNative.tokenName(handle, it) ?: throw CoreException(Core.E_ENGINE, 0, "qlc_model_token_name") }
    }

    /**
     * One ayah's expected units (R3: tokens.txt ids in reading order, the word bar between words) and its words' first
     * units. Read from the build's reference data at the first call: that needs a valid lease (E_NEEDS_CONNECTION,
     * E_LEASE, E_KEYSTORE then; a later call tries again); E_FILE when the build has none, it is not installed, or it
     * was refused; E_ARG for an ayah the table does not have.
     */
    fun referenceExpected(surah: Int, ayah: Int): Pair<IntArray, IntArray> {
        val st = IntArray(1)
        val r = CoreNative.referenceExpected(handle, surah, ayah, st) ?: throw CoreException(st[0], 0, "qlc_reference_expected")
        return r[0] to r[1]
    }

    /** The ayahs the reference table has of [surah] (0 for one it does not have). */
    fun referenceAyahCount(surah: Int): Int = CoreNative.referenceAyahCount(handle, surah)

    /** Frees the accelerator program (E_BUSY while a stream runs on it); closing the model does the same. */
    fun accelRelease(): Int = CoreNative.accelRelease(handle)

    override fun close() {
        val x = h
        h = 0
        if (x != 0L) CoreNative.modelClose(x)
    }
}
