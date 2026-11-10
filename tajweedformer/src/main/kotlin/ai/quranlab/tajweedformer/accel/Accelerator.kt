package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.SelfTestSet
import ai.quranlab.tajweedformer.TajweedBackend
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TajweedHead
import java.io.File
import java.io.FileOutputStream

/**
 * The accelerator's state. The exact CPU engine serves in every state; only [Ready] lets a NEW session run on
 * the accelerator.
 */
sealed class AcceleratorState {
    /** No accelerator for this device, model, context or app (the CPU engine serves). [reason] says why. */
    data class Cpu(val reason: String) : AcceleratorState()

    /**
     * The promotion pipeline is on its way: [stage] is the stage that runs or runs next (`download`, `compile`,
     * `warmup`, `golden`, `measure`); [waitingFor] is set while it waits without counting a failure
     * (`first_session`, `session`, `unmetered_network`, `battery`, `power_save`, `thermal`, `retry`).
     */
    data class Preparing(val stage: String = Stages.DOWNLOAD, val waitingFor: String? = null) : AcceleratorState()

    /** Promoted: passed its golden test for this key, loaded and warm; the next session runs on it. [speedup] is the
     *  CPU median chunk time over the accelerator's (telemetry only: a slower build is promoted too). */
    data class Ready(val soc: String?, val timeToReadyMs: Long, val speedup: Double = Double.NaN) : AcceleratorState()

    /**
     * Not used now: a failed stage, a failover, the denylist. [reason] is a stable code ([Reasons]); [persistent]
     * when nothing will try again for this key (a new model revision or OS build is a new key); otherwise the
     * pipeline tries again at [retryAtMs] (a later launch).
     */
    data class Disabled(val reason: String, val detail: String = "", val persistent: Boolean = false, val retryAtMs: Long = 0) : AcceleratorState()

    /** A short name for logs and telemetry: `cpu`, `preparing`, `ready`, `disabled`. */
    val key: String get() = when (this) {
        is Cpu -> "cpu"
        is Preparing -> "preparing"
        is Ready -> "ready"
        is Disabled -> "disabled"
    }
}

/** The promotion pipeline's stages, in order (the record names the last one completed). */
object Stages {
    const val DOWNLOAD = "download"
    const val COMPILE = "compile"
    const val WARMUP = "warmup"
    const val GOLDEN = "golden"
    const val MEASURE = "measure"
    const val PROMOTE = "promote"
    /** Not a pipeline stage: a running session (a failover, or a crash at a session's first inference). */
    const val SESSION = "session"

    @JvmField val ORDER = listOf(DOWNLOAD, COMPILE, WARMUP, GOLDEN, MEASURE, PROMOTE)

    /** The stage after [done] ("" = none done yet). */
    @JvmStatic
    fun after(done: String): String = if (done.isEmpty()) DOWNLOAD else ORDER.getOrElse(ORDER.indexOf(done) + 1) { PROMOTE }
}

/** Stable reason codes: the CPU choices, and the pipeline's failure reasons (the `promotions` telemetry's `reasons`). */
object Reasons {
    const val NO_BUILD = "no_npu_build"
    const val SOC_UNKNOWN = "soc_unknown"
    const val RUNTIME_MISSING = "runtime_missing"
    const val NO_SELFTEST_SET = "no_selftest_set"
    const val FORCED_CPU = "forced_cpu"
    const val THERMAL = "thermal"
    const val MEMORY = "memory"
    /** A late call of the last take has not returned yet: this session runs on the CPU, the next may use it again. */
    const val BUSY = "accelerator_busy"

    // pipeline failures (telemetry `reasons`)
    const val NETWORK = "network"
    const val STORAGE = "storage"
    const val VERIFY = "verify"
    const val COMPILE_ERROR = "compile_error"
    const val COMPILE_TIMEOUT = "compile_timeout"
    const val LOAD_ERROR = "load_error"
    const val CRASH = "crash"
    const val HANG = "hang"
    const val NON_FINITE = "non_finite"
    const val GOLDEN_MISMATCH = "golden_mismatch"
    const val CPU_MISMATCH = "cpu_mismatch"
    const val FAILOVER = "failover"
    const val DENYLIST = "denylist"
    const val OTHER = "other"

    @JvmField val PIPELINE = listOf(NETWORK, STORAGE, VERIFY, COMPILE_ERROR, COMPILE_TIMEOUT, LOAD_ERROR, CRASH, HANG, NON_FINITE,
        GOLDEN_MISMATCH, CPU_MISMATCH, FAILOVER, DENYLIST, OTHER)

    /** Never retried for the same key: the same build on the same OS fails the same way. A new model revision or
     *  OS build (a new key) tries again. */
    @JvmField val DETERMINISTIC = setOf(GOLDEN_MISMATCH, CPU_MISMATCH, NON_FINITE, CRASH)
}

/**
 * What a promotion record is valid for: the model revision, the context, the accelerator build (`npu:<soc>`), the
 * OS build (Android `Build.FINGERPRINT`) and the device model. A new revision or OS build is a new key: a fresh
 * pipeline with a fresh retry budget.
 */
data class AcceleratorKey(val revision: String, val osBuild: String, val deviceModel: String, val soc: String?, val context: String) {
    /** The accelerator build: `npu:<soc>`. */
    val build: String get() = "npu:${soc ?: "-"}"

    /** The record's key in `accelerator.json`. */
    val id: String get() = listOf(revision, context, build, osBuild, deviceModel).joinToString("|")
}

/** The device conditions the pipeline runs in (section "conditions": not battery saver, battery 20 % or more or
 *  charging, thermal nominal or light). */
data class Conditions(
    val batteryPct: Int = 100,
    val charging: Boolean = true,
    val powerSave: Boolean = false,
    /** Android `PowerManager` thermal status name: `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, `shutdown`. */
    val thermal: String = "none",
) {
    /** Null when the pipeline may run now, else what it waits for. */
    fun waitingFor(): String? = when {
        powerSave -> "power_save"
        !charging && batteryPct < 20 -> "battery"
        thermal !in setOf("none", "light") -> "thermal"
        else -> null
    }
}

/** The platform around the controller (Android: [ai.quranlab.tajweedformer.android.AndroidAccelerator]; tests: fakes). */
interface AcceleratorEnvironment {
    /** The OS build of the key (Android: Build.FINGERPRINT). */
    val osBuild: String
    val deviceModel: String
    val soc: String?

    /** For incident reports: the model code (`SM-S921B`, `Pixel 9`), never a name or serial. */
    val deviceModelCode: String get() = deviceModel

    /** For incident reports: Android `<release>/<Build.ID>/<Build.VERSION.INCREMENTAL>`. */
    val osBuildReport: String get() = osBuild

    /** For incident reports: the delegate or dispatch library and its version, when known. */
    val delegate: String? get() = null

    /** Serious thermal state at a session start (Android: thermal status >= SEVERE): that session runs on the CPU. */
    fun thermalSevere(): Boolean = false

    /** A memory warning at a session start (Android: onTrimMemory RUNNING_LOW or worse): that session runs on the CPU. */
    fun lowMemory(): Boolean = false

    /** The signed config's denylist: `exact` / `cpu` forces the CPU for this revision. */
    fun forcedBackend(revision: String): String? = null

    /** The signed config's `accel_deny`: the reason when a rule keeps [accelerator] (`npu:<soc>`) off for [revision]. */
    fun accelDenied(revision: String, accelerator: String): String? = null

    /** The pipeline's conditions now (battery, battery saver, thermal). */
    fun conditions(): Conditions = Conditions()

    /** Registers [listener] for every time the app becomes active (Android: the first activity started, a rotation
     *  not counted): the accelerator is warmed up then and the next chunk gets the first-chunk limit. */
    fun onAppActive(listener: () -> Unit) {}

    /** Whether the network is unmetered now (the download stage waits otherwise). */
    fun unmetered(): Boolean = true

    /**
     * Runs [work] once in the background at low priority, after [delayMs] (and, with [unmetered], when the network
     * is unmetered: Android hands both to WorkManager). The JVM runs it on a background thread.
     */
    fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit)

    fun nowMs(): Long = System.currentTimeMillis()
}

/** A stage failed with a pipeline reason ([Reasons]); [cause] keeps the error for the incident report. */
class StageFailure(val reason: String, message: String, cause: Throwable? = null, val clip: String? = null,
                   val divergence: Divergence? = null) : Exception(message, cause)

/** How far a golden clip diverged (incident `detail.divergence`). */
data class Divergence(val tokensExpected: Int, val tokensDiffer: Int, val sitesDiffer: Int, val wordsDiffer: Int)

/** The golden stage's observations: every clip through the accelerator path and through the CPU engine, in one run. */
class GoldenRun(val accelerator: Map<String, SelfTestSet.ClipObserved>, val cpu: Map<String, SelfTestSet.ClipObserved>)

/** The golden stage stopped because a session started (not a failure: it runs again later). */
class StageAborted : Exception("a session started")

/** An accelerator loaded for this key ([AcceleratorPreparer.compile]): warm-up, golden test, session streams. */
interface PreparedAccelerator : AutoCloseable {
    /** Audio per chunk, ms. */
    val chunkMs: Int

    /** The thresholds its heads are scored with ([TajweedBackend.FP32] for an NPU's). */
    val headsBackend: TajweedBackend get() = TajweedBackend.FP32

    /** The context's expected decisions (shipped in the model folder, `selftest/expected_<ctx>.json`). */
    val expected: SelfTestSet.Expected

    /** Ids of the planted clips (their tokens are not compared). */
    val planted: Set<String> get() = emptySet()

    /** The values its heads can take; a chunk outside is a failed chunk (`out_of_range`). */
    val range: HeadRange get() = HeadRange()

    /** The build's golden-test allowance (the signed manifest's `golden_tolerance`; exact when absent). */
    val tolerance: ai.quranlab.tajweedformer.ModelManifest.GoldenTolerance get() = ai.quranlab.tajweedformer.ModelManifest.GoldenTolerance.EXACT

    /** The planted site(s) of each planted clip, which the accelerator must flag whatever the tolerance. */
    val plantedSites: Map<String, Set<Pair<Int, String>>> get() = emptyMap()

    /** Runs a second of silence and the first self-test clip through the accelerator path: lazy initialisation done.
     *  Throws [StageFailure] (`non_finite`, `load_error`). */
    fun warmup()

    /**
     * Decodes every self-test clip end to end through the accelerator path (the streaming code a session uses) and
     * through the CPU engine, in this run, chunk by chunk with each chunk's compute time. Checks [abort] between
     * pieces and throws [StageAborted] when it says so (the engine allows one live stream per process: a starting
     * session gets it within one piece).
     */
    fun golden(abort: () -> Boolean): GoldenRun

    /** A raw accelerator stream for one take (no fallback inside: [FailoverEngine] watches it). */
    fun stream(heads: Set<TajweedHead>, sampleRate: Int): TajweedEngineStream
}

/** Brings an accelerator to [PreparedAccelerator]: availability, download, compile. */
interface AcceleratorPreparer {
    /** Null when there is something to prepare; else why not (`no_npu_build`, `soc_unknown`, `runtime_missing`, ...).
     *  Not a failure: nothing is recorded. */
    fun unavailable(key: AcceleratorKey): String?

    /** Downloads what is missing (resumable, each file verified). Throws [StageFailure] (`network`, `storage`, `verify`). */
    fun download(key: AcceleratorKey)

    /** What the build's programs are (a hash of their SHA-256); a promoted key whose programs changed is tested again. */
    fun programId(key: AcceleratorKey): String = ""

    /** Whether the build's files are all in place (a promoted key whose files are gone goes through the pipeline again). */
    fun inPlace(key: AcceleratorKey): Boolean = true

    /** Builds the accelerator program for this device and loads it (Android: LiteRT with the vendor dispatch; the
     *  shipped builds are compiled ahead of time). Throws [StageFailure] (`compile_error`) or anything (mapped to it). */
    fun compile(key: AcceleratorKey): PreparedAccelerator
}

/**
 * The persisted promotion record of one key: the last stage completed, the attempt and failure counts, the backoff,
 * and what the measure stage found. Written atomically after each stage.
 */
data class PromotionRecord(
    /** The last stage completed in the current attempt ("" = none), or [Stages.PROMOTE] once promoted. */
    val stage: String = "",
    /** `pending` (an attempt to run or resume), `promoted`, `failed` (retryable at [nextAttemptAtMs]), `final` (never again). */
    val status: String = PENDING,
    /** Attempts started for this key (1 for the first). */
    val attempt: Int = 0,
    /** Failures counted against the retry budget (deterministic ones end it). */
    val failures: Int = 0,
    val reason: String = "",
    val failedStage: String = "",
    val detail: String = "",
    val nextAttemptAtMs: Long = 0,
    /** The launch the current attempt last ran in (one attempt per launch). */
    val launch: String = "",
    val compileMs: Long = -1,
    val cpuMedianMs: Double = Double.NaN,
    val accMedianMs: Double = Double.NaN,
    val accP90Ms: Double = Double.NaN,
    val promotedAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    /** A denylist or `accel_deny` match was counted for this key (`reasons.denylist`, once). */
    val denyCounted: Boolean = false,
    /** What the promotion was verified on: the SHA-256 of the accelerator program files; another one reruns the golden test. */
    val programSha: String = "",
    /** A promoted key paused by a transient demotion (missed limits in three sessions within a day, one confirmed blank
     *  disagreement): no session uses it before this time; then the same verified build serves again (no golden test). */
    val pausedUntilMs: Long = 0,
    /** The failover cause of that pause. */
    val pauseCause: String = "",
    /** When each of the recent sessions that missed a chunk limit did (the last 24 h; one entry per session). */
    val missSessionsAtMs: List<Long> = emptyList(),
    /** Confirmed blank disagreements with the CPU for this key (the second turns the build off for good). */
    val blankDisagreements: Int = 0,
) {
    val speedup: Double get() = if (accMedianMs > 0 && cpuMedianMs > 0) cpuMedianMs / accMedianMs else Double.NaN

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "stage" to stage, "status" to status, "attempt" to attempt, "failures" to failures, "reason" to reason,
        "failed_stage" to failedStage, "detail" to detail, "next_attempt_at_ms" to nextAttemptAtMs, "launch" to launch,
        "compile_ms" to compileMs, "cpu_median_ms" to num(cpuMedianMs), "acc_median_ms" to num(accMedianMs),
        "acc_p90_ms" to num(accP90Ms), "promoted_at_ms" to promotedAtMs, "updated_at_ms" to updatedAtMs,
        "deny_counted" to denyCounted, "program_sha256" to programSha, "paused_until_ms" to pausedUntilMs, "pause_cause" to pauseCause,
        "miss_sessions_at_ms" to missSessionsAtMs, "blank_disagreements" to blankDisagreements,
    )

    companion object {
        const val PENDING = "pending"
        const val PROMOTED = "promoted"
        const val FAILED = "failed"
        const val FINAL = "final"

        private fun num(d: Double): Double? = if (d.isFinite()) d else null

        fun fromJson(o: Map<*, *>): PromotionRecord {
            fun s(k: String) = o[k] as? String ?: ""
            fun l(k: String, d: Long = 0) = (o[k] as? Double)?.toLong() ?: d
            fun d(k: String) = o[k] as? Double ?: Double.NaN
            return PromotionRecord(s("stage"), (o["status"] as? String) ?: PENDING, l("attempt").toInt(), l("failures").toInt(), s("reason"),
                s("failed_stage"), s("detail"), l("next_attempt_at_ms"), s("launch"), l("compile_ms", -1), d("cpu_median_ms"),
                d("acc_median_ms"), d("acc_p90_ms"), l("promoted_at_ms"), l("updated_at_ms"), o["deny_counted"] == true, s("program_sha256"),
                l("paused_until_ms"), s("pause_cause"), (o["miss_sessions_at_ms"] as? List<*>).orEmpty().mapNotNull { (it as? Double)?.toLong() },
                l("blank_disagreements").toInt())
        }

        /** 2.0.1's demotions for silence or a late chunk (2.0.2 no longer counts those as faults): failover causes
         *  `blank` and `deadline`, as the record's reason or as the cause of a `failover`. */
        private val UPGRADE_CLEARED = setOf("blank", "deadline")

        /**
         * The upgrade from 2.0.1 (run once per records file): a key demoted or turned off for good by a `blank` or
         * `deadline` failover goes back to the golden stage (the golden test runs again in the background, then
         * promotes as usual) with a fresh retry budget; every other record stays as it is (NaN, a crash, a golden
         * mismatch: still off). Returns null when [r] stays.
         */
        @JvmStatic
        fun upgradedFrom201(r: PromotionRecord): PromotionRecord? {
            if (r.status != FAILED && r.status != FINAL) return null
            val cause = when (r.reason) {
                Reasons.FAILOVER -> r.detail
                else -> r.reason
            }
            if (cause !in UPGRADE_CLEARED) return null
            return r.copy(status = PENDING, stage = Stages.WARMUP, failures = 0, reason = "", detail = "", failedStage = "", nextAttemptAtMs = 0,
                launch = "", attempt = r.attempt + 1, missSessionsAtMs = emptyList(), blankDisagreements = 0, pausedUntilMs = 0, pauseCause = "")
        }
    }
}

/**
 * The accelerator's persistent state in the model folder: one [PromotionRecord] per [AcceleratorKey]
 * (`accelerator.json`, written atomically: a temporary file renamed over it), the install's first-session flag, and
 * the crash sentinel (`accelerator.sentinel`, written and synced before every compile, load, first inference and
 * golden run, removed after; found at launch, the process died there).
 */
class AcceleratorStore(private val dir: File) {
    private val sentinel = File(dir, "accelerator.sentinel")
    private val records = File(dir, "accelerator.json")
    private val lock = Any()

    /** Written and synced to disk before the risky step, naming the key and the stage. */
    fun armSentinel(key: AcceleratorKey, stage: String) = synchronized(lock) {
        dir.mkdirs()
        FileOutputStream(sentinel).use { out ->
            out.write(Json.write(mapOf("key" to key.id, "stage" to stage)).toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    fun clearSentinel() = synchronized(lock) { sentinel.delete(); Unit }

    /** The key id and stage of a sentinel left by a process that died, or null. */
    fun leftSentinel(): Pair<String, String>? = synchronized(lock) {
        if (!sentinel.isFile) return null
        return try {
            val m = Json.parse(sentinel.readText()) as Map<*, *>
            (m["key"] as String) to (m["stage"] as? String ?: "")
        } catch (_: Exception) {
            "" to ""
        }
    }

    fun record(key: AcceleratorKey): PromotionRecord? = synchronized(lock) { load().second[key.id] }

    fun record(keyId: String): PromotionRecord? = synchronized(lock) { load().second[keyId] }

    fun put(key: AcceleratorKey, r: PromotionRecord) = put(key.id, r)

    fun put(keyId: String, r: PromotionRecord) = synchronized(lock) {
        val (install, all) = load()
        val m = HashMap(all)
        m[keyId] = r
        save(install, m)
    }

    /** A session has started on this install before (the pipeline then may start at launch). */
    var sessionStarted: Boolean
        get() = synchronized(lock) { load().first["session_started"] == true }
        set(v) = synchronized(lock) {
            val (install, all) = load()
            if ((install["session_started"] == true) == v) return
            save(install + ("session_started" to v), all)
        }

    fun forget() = synchronized(lock) { records.delete(); sentinel.delete(); Unit }

    private fun save(install: Map<String, Any?>, all: Map<String, PromotionRecord>) {
        dir.mkdirs()
        val tmp = File(records.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            // every file this SDK writes says its upgrades are done (a file 2.0.2 made is never upgraded again)
            out.write(Json.write(linkedMapOf("format" to FORMAT, "install" to install, "upgrades" to UPGRADES,
                "keys" to all.mapValues { it.value.toJson() }.toSortedMap())).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(records)) {
            records.delete()
            tmp.renameTo(records)
        }
    }

    private fun load(): Pair<Map<String, Any?>, Map<String, PromotionRecord>> {
        if (!records.isFile) return emptyMap<String, Any?>() to emptyMap()
        return try {
            val m = Json.parse(records.readText()) as Map<*, *>
            // a 2.0.0 file (a map of verdicts, no format) is not carried over: every key prepares again
            if (m["format"] != FORMAT) return emptyMap<String, Any?>() to emptyMap()
            @Suppress("UNCHECKED_CAST")
            val install = (m["install"] as? Map<String, Any?>).orEmpty()
            val all = (m["keys"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k as String to PromotionRecord.fromJson(v as Map<*, *>) }
            if ((m["upgrades"] as? List<*>)?.contains(UPGRADE_2_0_2) == true) return install to all
            // a 2.0.1 file: its blank and deadline demotions are cleared, once (the file is written back with the mark)
            val up = all.mapValues { (_, r) -> PromotionRecord.upgradedFrom201(r) ?: r }
            try { save(install, up) } catch (_: Exception) {}
            install to up
        } catch (_: Exception) {
            emptyMap<String, Any?>() to emptyMap()
        }
    }

    companion object {
        const val FORMAT = "quranlab.accelerator/2"

        /** The records file's upgrades done ([PromotionRecord.upgradedFrom201]). */
        const val UPGRADE_2_0_2 = "2.0.2"
        private val UPGRADES = listOf(UPGRADE_2_0_2)
    }
}
