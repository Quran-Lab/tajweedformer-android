package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.SelfTestSet
import ai.quranlab.tajweedformer.TajweedEngine
import java.io.File
import java.io.FileOutputStream

/**
 * The accelerator's state (the 2.0.0 design point 7). The exact CPU engine serves in every state; only
 * [Ready] lets a new session run on the accelerator.
 */
sealed class AcceleratorState {
    /** No accelerator for this device, model or app (the CPU engine serves). [reason] says why. */
    data class Cpu(val reason: String) : AcceleratorState()

    /** Being prepared in the background (download, load, self-test). */
    object Preparing : AcceleratorState() {
        override fun toString() = "Preparing"
    }

    /** Passed its self-test; the next session runs on it. */
    data class Ready(val soc: String?, val timeToReadyMs: Long) : AcceleratorState()

    /** Turned off: a failed self-test, a crash during load, a timeout, the watchdog, the denylist. [reason]
     *  is a stable code (see [Reasons]); [persistent] when it holds until the model revision or OS build changes. */
    data class Disabled(val reason: String, val detail: String = "", val persistent: Boolean = false) : AcceleratorState()

    /** A short name for logs and telemetry: `cpu`, `preparing`, `ready`, `disabled`. */
    val key: String get() = when (this) {
        is Cpu -> "cpu"
        Preparing -> "preparing"
        is Ready -> "ready"
        is Disabled -> "disabled"
    }
}

/** Stable reason codes of [AcceleratorState] and of the CPU choices it causes. */
object Reasons {
    const val NO_BUILD = "no_npu_build"
    const val SOC_UNKNOWN = "soc_unknown"
    const val RUNTIME_MISSING = "runtime_missing"
    const val NOT_DOWNLOADED = "npu_not_downloaded"
    const val LOAD_FAILED = "npu_load_failed"
    const val LOAD_TIMEOUT = "npu_load_timeout"
    const val CRASHED = "npu_crashed_during_load"
    const val SELF_TEST = "npu_self_test_failed"
    const val DENYLIST = "denylist"
    const val FORCED_CPU = "forced_cpu"
    const val THERMAL = "thermal"
    const val MEMORY = "memory"
    const val WATCHDOG = "watchdog"
    /** Two watchdog trips on the same key across processes: off until the revision or OS build changes. */
    const val WATCHDOG_REPEATED = "npu_watchdog_repeated"
    const val RUNTIME_ERROR = "npu_runtime_error"
}

/**
 * What a prepared state is valid for: the model revision, the OS build (Android `Build.FINGERPRINT`), the
 * device model, the SoC and the context. An OS update gives a new key, so the accelerator is prepared and
 * self-tested again.
 */
data class AcceleratorKey(val revision: String, val osBuild: String, val deviceModel: String, val soc: String?, val context: String) {
    val id: String get() = listOf(revision, osBuild, deviceModel, soc ?: "-", context).joinToString("|")
}

/** The platform around the controller (Android: [ai.quranlab.tajweedformer.android.AndroidAccelerator]; tests: fakes). */
interface AcceleratorEnvironment {
    /** The OS build (Android: Build.FINGERPRINT). */
    val osBuild: String
    val deviceModel: String
    val soc: String?

    /** Serious thermal state (Android: PowerManager thermal status >= SEVERE): prefer the CPU. */
    fun thermalSevere(): Boolean = false

    /** A memory warning is in force (Android: onTrimMemory RUNNING_LOW or worse, or low memory): prefer the CPU. */
    fun lowMemory(): Boolean = false

    /** The signed config's denylist: `exact` / `cpu` forces the CPU for this revision. */
    fun forcedBackend(revision: String): String? = null

    /**
     * Runs [work] in the background at low priority; [constrained] asks for the device to be idle and
     * charging (Android: WorkManager with those constraints; the download inside also needs an unmetered
     * network). The JVM runs it at once on a background thread.
     */
    fun schedule(constrained: Boolean, work: () -> Unit)

    fun nowMs(): Long = System.currentTimeMillis()
}

/** An accelerator engine that passed its load, ready for sessions; [close] releases the runtime. */
interface PreparedAccelerator : AutoCloseable {
    /** A session engine on the accelerator over [cpu] (its scorer, and the CPU it falls back to mid-take). */
    fun engine(cpu: TajweedEngine, sampleRate: Int, onFallback: (String) -> Unit): TajweedEngine

    /** Runs the self-test set through the accelerator: what it decided on each clip, and its chunk times. */
    fun selfTest(): Map<String, SelfTestSet.ClipObserved>

    /** The context's expected decisions (shipped in the model folder). */
    val expected: SelfTestSet.Expected
    val chunkMs: Int

    /** Ids of the planted clips (their tokens are not compared). */
    val planted: Set<String> get() = emptySet()
}

/** Brings an accelerator to the point of its self-test: availability, download, load. */
interface AcceleratorPreparer {
    /** Null when there is nothing to prepare on this device: the reason (`no_npu_build`, `soc_unknown`, `runtime_missing`). */
    fun unavailable(key: AcceleratorKey): String?

    /** Downloads what is missing (may throw; on Android only on an unmetered network). */
    fun download(key: AcceleratorKey)

    /** Loads the accelerator (the slow, crash-prone step: LiteRT dispatch load). */
    fun load(key: AcceleratorKey): PreparedAccelerator
}

/**
 * The accelerator's persistent records in the model folder: the crash sentinel and one verdict per
 * [AcceleratorKey]. The sentinel is written and fsync'd before every load and first run and removed after
 * a clean run; if it is there at startup, the last load or first run killed the process.
 */
class AcceleratorStore(private val dir: File) {
    private val sentinel = File(dir, "accelerator.sentinel")
    private val records = File(dir, "accelerator.json")
    private val lock = Any()

    /** One watchdog trip: its reason (`latency`, `non_finite`, `all_blank`, `exception`) and time. */
    data class Trip(val reason: String, val atMs: Long)

    data class Record(
        val status: String, val reason: String, val detail: String, val atMs: Long, val timeToReadyMs: Long,
        /** Every watchdog trip on this key, across processes. */
        val trips: List<Trip> = emptyList(),
    )

    /** Written and synced to disk before the risky step, naming the key and the step. */
    fun armSentinel(key: AcceleratorKey, step: String) = synchronized(lock) {
        dir.mkdirs()
        FileOutputStream(sentinel).use { out ->
            out.write(Json.write(mapOf("key" to key.id, "step" to step)).toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    fun clearSentinel() = synchronized(lock) { sentinel.delete(); Unit }

    /** The key id and step of a sentinel left by a process that died, or null. */
    fun leftSentinel(): Pair<String, String>? = synchronized(lock) {
        if (!sentinel.isFile) return null
        return try {
            val m = Json.parse(sentinel.readText()) as Map<*, *>
            (m["key"] as String) to (m["step"] as? String ?: "")
        } catch (_: Exception) {
            "" to "unreadable"
        }
    }

    fun record(key: AcceleratorKey): Record? = synchronized(lock) { load()[key.id] }

    /** Stores [r] for [keyId]; the trips already recorded for the key are kept (and [r]'s added). */
    fun put(keyId: String, r: Record) = synchronized(lock) {
        val all = HashMap(load())
        val old = all[keyId]?.trips.orEmpty()
        all[keyId] = r.copy(trips = (old + r.trips).distinct())
        dir.mkdirs()
        val tmp = File(records.path + ".tmp")
        tmp.writeText(Json.write(all.mapValues { (_, v) ->
            mapOf("status" to v.status, "reason" to v.reason, "detail" to v.detail, "at_ms" to v.atMs, "time_to_ready_ms" to v.timeToReadyMs,
                "trips" to v.trips.map { t -> mapOf("reason" to t.reason, "at_ms" to t.atMs) })
        }.toSortedMap()))
        if (!tmp.renameTo(records)) {
            records.delete()
            tmp.renameTo(records)
        }
    }

    fun forget() = synchronized(lock) { records.delete(); sentinel.delete(); Unit }

    private fun load(): Map<String, Record> {
        if (!records.isFile) return emptyMap()
        return try {
            (Json.parse(records.readText()) as Map<*, *>).entries.associate { (k, v) ->
                val o = v as Map<*, *>
                k as String to Record(o["status"] as String, o["reason"] as? String ?: "", o["detail"] as? String ?: "",
                    (o["at_ms"] as Double).toLong(), (o["time_to_ready_ms"] as? Double)?.toLong() ?: 0,
                    (o["trips"] as? List<*>).orEmpty().map { t -> val m = t as Map<*, *>; Trip(m["reason"] as String, (m["at_ms"] as Double).toLong()) })
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val READY = "ready"
        const val FAILED = "failed"
        const val CRASHED = "crashed"
    }
}
