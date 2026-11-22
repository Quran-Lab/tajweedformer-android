package ai.quranlab.tajweedformer.core

import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.ModelManifest
import java.io.File

/**
 * The accelerator of SDK 4.0 (R2, quranlab-core docs/hardening.md section 10): the build's program for this phone's NPU
 * (the manifest's `npu-*` files of the SoC and context) is a file of the encrypted build like every other: downloaded
 * in its CDN form, installed by the Core into its device form, and handed to LiteRT by the Core from its own memory
 * (`qlc_accel_prepare`). The app never gets the program's bytes, a path to them, or a runtime handle.
 *
 * What a [ai.quranlab.tajweedformer.Recognizer] is given to promote it once its CPU engine is open: [kind]
 * (`npu:<soc>`), the folder of the vendor dispatch library ([dispatchDir]: the app's `nativeLibraryDir`), the build's
 * signed tolerance for the golden test, the self-test clips the golden test runs, and the verdicts kept across starts.
 */
internal class AcceleratorPlan(
    val soc: String,
    val dispatchDir: String?,
    /** `golden_raw_tolerance`'s entry for `npu:<soc>:<context>`; null: the tokens of every CTC head must match. */
    val tolerance: ModelManifest.RawTolerance?,
    /** The golden clips as 16 kHz samples (the build's self-test clips); empty: no promotion (nothing to check with). */
    val clips: () -> List<Pair<String, FloatArray>>,
    val records: AcceleratorRecords,
    /** What the promotion did, for the `promotions` telemetry. */
    val report: (PromotionInfo) -> Unit = {},
    /** The signed config's `flags.accel_min_speedup` (null: none); [AcceleratorSpeed.minSpeedup] bounds it. */
    val minSpeedup: () -> Double? = { null },
    /** This SDK's version and the OS build ([ai.quranlab.tajweedformer.ClientPlatform.osBuild]): a transient verdict
     *  of another SDK version or OS build is tried again at once. */
    val sdk: String = ai.quranlab.tajweedformer.SdkInfo.VERSION,
    val os: String = "",
    val clock: () -> Long = System::currentTimeMillis,
    /** How long the promotion waits for the Core's prepare before it gives up on the program ([AcceleratorSpeed.PREPARE_DEADLINE_MS]). */
    val prepareDeadlineMs: Long = AcceleratorSpeed.PREPARE_DEADLINE_MS,
    /** The dispatch library's identity ([DispatchLibrary.Resolution.id]): a transient verdict found with another library
     *  (or none) is tried again at once, so an app update that adds or changes the library brings the NPU back. */
    val dispatchId: String = DispatchLibrary.NONE,
    /** SDK 4.0.2: whether the lease in force allows accelerator programs (`qlc_accel_allowed`), asked
     *  before an attempt starts. False: no attempt, nothing counted, the CPU engine ([leaseNoAccel]). */
    val allowed: () -> Boolean = { true },
    /** 21.6 item 1: the lease in force allows no accelerator program: said as the config's deny list says a denial
     *  (the `denylist` demotion, once while it holds), never as a failure of the build. */
    val leaseNoAccel: () -> Unit = {},
) {
    val kind: String get() = "npu:$soc"

    /** A transient verdict holds the build [key] off the accelerator now ([AcceleratorRecords.held]). */
    fun held(key: String): Boolean = records.held(key, sdk, os, clock(), dispatchId)
}

/**
 * Whether this device downloads, installs and prepares the build's NPU program for a SoC and context (Stage B, 11-18):
 * only a program that can be promoted. Nothing is fetched (110 to 167 MB) when:
 * - the build has no complete program for the SoC (or the SoC is not known);
 * - a verdict keeps the build off the accelerator (off for good, or held for this SDK, OS build and dispatch library);
 * - this OS cannot run it ([ai.quranlab.tajweedformer.ClientPlatform.npuRuntimeMissing]);
 * - the app has no dispatch library for the SoC ([DispatchLibrary]);
 * - the golden test has nothing to run: the build lists no self-test set for the context, and it has not passed with
 *   this dispatch library before.
 * The QLPD deltas (`npu-delta`, SDK 2.x and 3.x rebuilt a program from them) are never fetched: the Core reads only
 * `npu-model` or `npu-part`, `npu-io` and `npu-masks` (quranlab-core `accel_files`); the V4 4.0.0 build lists an 86 MB
 * delta beside each Tensor program.
 */
internal object AcceleratorGate {
    /**
     * The files to fetch and install ([files]), or none; [why] and [reason] when the device should say once why not.
     * [denied]: the signed config keeps this accelerator off (`accel_deny`, or a `denylist` row that forces the CPU):
     * said, never held, so the next config without the rule brings it back.
     */
    class Choice(val files: List<ModelManifest.FileEntry>, val why: String? = null, val reason: String? = null,
                 val dispatch: DispatchLibrary.Resolution? = null, val denied: Boolean = false)

    /**
     * [configInForce]: a verified signed config is in force. Without one nothing is fetched (it may deny the
     * accelerator); with one, [denied] (the SoC's `npu:<soc>` against the config's rules: the rule's reason, or null)
     * is asked first, before the dispatch library is resolved and before a byte is fetched (Stage B, 11-18: Quran Lab
     * keeps the Tensor NPU off for SDK 4.0 by a config rule until a program faster than the CPU exists).
     */
    fun choose(
        m: ModelManifest, soc: String?, ctx: String, records: AcceleratorRecords, key: String, sdk: String, os: String, now: Long,
        dispatch: (String) -> DispatchLibrary.Resolution, runtimeMissing: (String) -> String?,
        configInForce: Boolean = true, denied: (String) -> String? = { null },
    ): Choice {
        if (soc == null) return Choice(emptyList())
        val all = ai.quranlab.tajweedformer.ModelProfile.npu(m, soc, ctx)
        if (all.isEmpty() || records.blocked(key)) return Choice(emptyList())
        if (!configInForce) return Choice(emptyList())
        denied(soc)?.let { return Choice(emptyList(), "accel_deny: $it", "denylist", denied = true) }
        val d = dispatch(soc)
        if (records.held(key, sdk, os, now, d.id)) return Choice(emptyList(), dispatch = d)
        runtimeMissing(soc)?.let { return Choice(emptyList(), it, "load_error", d) }
        if (d.folder == null) return Choice(emptyList(), d.missing ?: "no dispatch library for $soc", "load_error", d)
        if (!records.passedWith(key, d.id) && !hasSelfTest(m, ctx)) {
            return Choice(emptyList(), "the build lists no self-test set for $ctx: its program is never promoted", "other", d)
        }
        val files = all.filter { it.role != ai.quranlab.tajweedformer.ModelProfile.NPU_DELTA_ROLE }
        if (files.none { it.role == "npu-model" || it.role == ai.quranlab.tajweedformer.ModelProfile.NPU_PART_ROLE }) {
            return Choice(emptyList(), "the build ships $soc's program only as deltas, which the Core does not read", "other", d)
        }
        return Choice(files, dispatch = d)
    }

    /**
     * Before the program is fetched: whether the Core refuses this phone's firmware for [kind] (QLC_D_ACCEL_FIRMWARE,
     * the Pixel 10 family's launch firmware). The Core checks the firmware before it reads any installed file, so a
     * prepare of a program that is not installed yet answers QLC_D_ACCEL_FIRMWARE at once on that firmware and
     * QLC_E_STORAGE ("not installed") elsewhere: the 110 to 167 MB are fetched only in the second case. Call it only
     * when none of the program's files is installed (nothing is prepared then, so nothing is replaced); a prepare that
     * succeeds anyway is released at once. a device lab's Pixel 10 Pro on BD1A.250702.001 fetched 121 MB to learn this.
     */
    fun firmwareRefused(host: AcceleratorHost, kind: String, dispatchDir: String?): Boolean = try {
        host.prepareAccelerator(kind, dispatchDir)
        try { host.releaseAccelerator() } catch (_: Exception) {}
        false
    } catch (x: CoreException) {
        x.status == Core.E_ENGINE && x.detail == Core.D_ACCEL_FIRMWARE
    } catch (_: Exception) {
        false
    }

    /** Whether the build lists the self-test set the golden test runs for [ctx]: `selftest.json`, the context's expected
     *  tokens and at least one clip. */
    fun hasSelfTest(m: ModelManifest, ctx: String): Boolean {
        val st = ai.quranlab.tajweedformer.ModelProfile.selfTest(m, ctx)
        return st.any { it.localPath.endsWith("/selftest.json") || it.localPath == "selftest.json" } &&
            st.any { it.localPath.endsWith("expected_$ctx.json") } && st.any { it.localPath.endsWith(".s16") }
    }
}

/**
 * What the promotion drives (R2): the Core's engine of a context ([CoreTajweedEngine]), or a test's. Every call but
 * [prepareAccelerator] runs on the recognizer's engine thread.
 */
internal interface AcceleratorHost {
    /** `qlc_accel_prepare` of [kind]: the backend's name, or a [CoreException] (on a background thread: it takes seconds). */
    fun prepareAccelerator(kind: String, dispatchDir: String?): String

    /** The golden clips on the prepared program and on the CPU engine, with the chunks' times; null when a take is open. */
    fun goldenRuns(clips: List<Pair<String, FloatArray>>): GoldenRuns?

    /** Takes asked for on the accelerator run on the prepared program from now on. */
    fun promote()

    /** The program wiped and released: later takes run on the CPU. */
    fun releaseAccelerator()

    /** Told when a take falls back to the CPU (telemetry's failovers). */
    var onFailover: (String) -> Unit
}

/**
 * The golden test's runs: per clip the accelerator's heads and the CPU's, and the compute time of each of their warm
 * chunks (the first chunk of each take left out), in ms: the measure stage's medians.
 */
internal class GoldenRuns(
    val runs: List<Triple<String, List<HeadValues>, List<HeadValues>>>,
    val acceleratorMs: List<Double> = emptyList(),
    val cpuMs: List<Double> = emptyList(),
)

/**
 * SDK 4.0's measure stage (Quran Lab's decision, the same in the Apple SDK, f1c15e1): a build that passed
 * the golden test is promoted only when the accelerator is not slower than the CPU engine on the same device, by the
 * median chunk times of the golden test's warm chunks: speedup = CPU median / accelerator median at least
 * [minSpeedup] (an accelerator exactly as fast as the CPU is promoted). Otherwise the CPU engine stays: reason
 * `slower_than_cpu`, the program released, transient ([AcceleratorRecords.SLOWER]). a device lab found the Pixel 10's
 * NPU path matching exactly at a tenth of the CPU's speed (61 ms against 6 ms a chunk, and 200 MB more).
 */
internal object AcceleratorSpeed {
    /** A transient verdict is tried again after this, or at once by another SDK version or OS build. */
    const val RETRY_MS = 30L * 86_400_000L

    /**
     * How long the promotion waits for `qlc_accel_prepare` (the program's load through the vendor's dispatch library):
     * a prepare takes 0.4 to 1.5 s on a Pixel 10, and the launch firmware's dispatch timeout fails it in about 2 s. One
     * that does not answer in this time is given up (`compile_timeout`): the takes stay on the CPU engine, and a late
     * answer is released.
     */
    const val PREPARE_DEADLINE_MS = 20_000L

    /** The signed config's `flags.accel_min_speedup` when it is a finite number, never below 1.0. */
    fun minSpeedup(configured: Double?): Double = if (configured == null || !configured.isFinite()) 1.0 else maxOf(1.0, configured)

    fun median(x: List<Double>): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Slower than the CPU engine by the rule: both medians measured and CPU / accelerator below [minSpeedup]. */
    fun slower(acceleratorMs: Double, cpuMs: Double, minSpeedup: Double): Boolean =
        acceleratorMs > 0 && cpuMs > 0 && cpuMs / acceleratorMs < minSpeedup
}

/** One promotion attempt's outcome (telemetry `promotions`, contract SPEC 6.1): where it stopped and why. */
internal data class PromotionInfo(
    val soc: String,
    val build: String,
    val context: String,
    val promoted: Boolean,
    /** `compile` (the Core's prepare: decryption and the runtime's compile) or `golden`; null when promoted. */
    val failedAt: String? = null,
    /** One of the telemetry's reasons (`compile_error`, `load_error`, `golden_mismatch`, `crash`, `other`). */
    val reason: String? = null,
    /** The Core's status and detail of a refused prepare (`accel_program`, `lease_no_accel`, ...). */
    val detail: String? = null,
    val compileMs: Double? = null,
    /** A take that went back to the CPU during its run (the 2.0.2 failover). */
    val failover: Boolean = false,
    /** The model version (`x.y.z`) the build is of. */
    val version: String = "",
    /** The measure stage's medians, ms a chunk: the accelerator's and the CPU engine's (SDK 4.0). */
    val acceleratorMs: Double? = null,
    val cpuMs: Double? = null,
)

/**
 * The verdicts of the accelerator kept across starts (`<storage>/core/accel.json`), per `<build>:npu:<soc>:<context>`:
 * `passed` (the golden test passed once: later starts prepare and use it at once), `failed` (never again for this
 * build: only a fault of the build itself, a golden mismatch or a program that breaks the host contract),
 * `preparing` with a count (a prepare that never came back: the process ended in it), or a transient verdict (SDK
 * 4.0): `slower` (`slower_than_cpu`) or `unsupported` (the runtime or the vendor's dispatch refused, did not answer,
 * failed while the golden test ran, the process ended twice during the preparation, or the app has no dispatch
 * library), which holds while the SDK version, the OS build and the dispatch library are those it was found under and
 * for [AcceleratorSpeed.RETRY_MS]; then the build is tried again (none of these is a failure of the build).
 */
internal class AcceleratorRecords(private val file: File) {
    private val lock = Any()

    @Suppress("UNCHECKED_CAST")
    private fun read(): MutableMap<String, Any?> = try {
        (Json.parse(file.readText()) as? Map<String, Any?>)?.toMutableMap() ?: LinkedHashMap()
    } catch (_: Exception) {
        LinkedHashMap()
    }

    private fun write(m: Map<String, Any?>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(m))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    fun state(key: String): String? = synchronized(lock) { (read()[key] as? Map<*, *>)?.get("state") as? String }

    /** Whether this build's accelerator is off for good on this device. */
    fun blocked(key: String): Boolean = state(key) == FAILED

    fun passed(key: String): Boolean = state(key) == PASSED

    /**
     * A prepare starts. Returns false (do not start) when [MAX_UNFINISHED] prepares in a row never came back (the
     * process ended in them): the caller holds the build off as a transient verdict, since a process the system or the
     * vendor's runtime ended is not a fault of the build.
     */
    fun begin(key: String, dispatch: String? = null): Boolean = synchronized(lock) {
        val m = read()
        val e = (m[key] as? Map<*, *>)
        val tries = if (e?.get("state") == PREPARING) ((e["tries"] as? Double)?.toInt() ?: 1) else 0
        if (tries >= MAX_UNFINISHED) return false
        // a pass with the same dispatch library (or one of before the library was recorded) is carried over
        val passed = e?.get("state") == PASSED && (dispatch == null || e["dispatch"] == null || e["dispatch"] == dispatch)
        m[key] = linkedMapOf("state" to PREPARING, "tries" to tries + 1, "was" to (if (passed) PASSED else e?.get("was")))
        write(m)
        true
    }

    /** Whether the last unfinished prepare of [key] had passed its golden test before. */
    fun passedBefore(key: String): Boolean = synchronized(lock) { ((read()[key] as? Map<*, *>)?.get("was")) == PASSED }

    fun end(key: String, state: String, why: String? = null, extra: Map<String, Any?> = emptyMap()) = synchronized(lock) {
        val m = read()
        m[key] = linkedMapOf<String, Any?>("state" to state, "why" to why).apply { putAll(extra) }
        write(m)
    }

    /** Whether [key] passed its golden test with the dispatch library [dispatch] (a verdict of before 4.0's dispatch
     *  identity names none and counts for any). Another library: the golden test runs again. */
    fun passedWith(key: String, dispatch: String): Boolean = synchronized(lock) {
        val e = read()[key] as? Map<*, *> ?: return false
        e["state"] == PASSED && (e["dispatch"] == null || e["dispatch"] == dispatch)
    }

    /** A transient verdict ([SLOWER] or [UNSUPPORTED]) found under SDK [sdk] and OS build [os] at [at] (unix ms). */
    fun hold(key: String, state: String, why: String, sdk: String, os: String, at: Long, extra: Map<String, Any?> = emptyMap()) = synchronized(lock) {
        val m = read()
        m[key] = linkedMapOf<String, Any?>("state" to state, "why" to why, "sdk" to sdk, "os" to os, "at" to at).apply { putAll(extra) }
        write(m)
    }

    /**
     * Whether a transient verdict holds [key] off the accelerator now: the same SDK version and OS build, and less than
     * [AcceleratorSpeed.RETRY_MS] since it was found; a [FIRMWARE] verdict for as long as the OS build is the one it was
     * found on (only a system update brings that NPU back).
     */
    fun held(key: String, sdk: String, os: String, now: Long, dispatch: String? = null): Boolean = synchronized(lock) {
        val e = read()[key] as? Map<*, *> ?: return false
        if (e["state"] == FIRMWARE) return e["os"] == os
        if (e["state"] != SLOWER && e["state"] != UNSUPPORTED) return false
        val at = (e["at"] as? Double)?.toLong() ?: return false
        // found with another dispatch library (or with none): an app update changed it, tried again at once
        val sameDispatch = dispatch == null || e["dispatch"] == null || e["dispatch"] == dispatch
        e["sdk"] == sdk && e["os"] == os && sameDispatch && now < at + AcceleratorSpeed.RETRY_MS
    }

    /** The whole verdict of [key] as stored (tests and diagnosis). */
    fun entry(key: String): Map<*, *>? = synchronized(lock) { read()[key] as? Map<*, *> }

    /** [key]'s verdict put back as it was before an attempt ([entry]; null: none, the key never tried): 21.6, an attempt
     *  the lease refused is no attempt, so a promoted key stays promoted and one never tried stays pending. */
    fun restore(key: String, entry: Map<*, *>?) = synchronized(lock) {
        val m = read()
        if (entry == null) m.remove(key) else m[key] = LinkedHashMap(entry.entries.associate { (k, v) -> k as String to v })
        write(m)
    }

    /**
     * 21.6 item 4, at the first start of 4.0.2: a verdict 4.0.1 stored for a prepare the Core refused because the lease
     * allowed no accelerator program (`waiting`, why `... (lease_no_accel)`: Android never counted it as a failure, but
     * the record replaced a promoted key's pass) goes: the key is pending. A verdict for any other reason stays.
     * Returns how many went.
     */
    fun migrateLeaseNoAccel(): Int = synchronized(lock) {
        val m = read()
        val gone = m.filterValues { v ->
            (v as? Map<*, *>)?.let { it["state"] == "waiting" && (it["why"] as? String)?.contains(LEASE_NO_ACCEL) == true } == true
        }.keys.toList()
        if (gone.isEmpty()) return 0
        gone.forEach { m.remove(it) }
        write(m)
        gone.size
    }

    companion object {
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val PREPARING = "preparing"
        const val SLOWER = "slower"
        const val UNSUPPORTED = "unsupported"
        /** Core 69276aa's QLC_D_ACCEL_FIRMWARE: held for the OS build it was found on, whatever the SDK or the time. */
        const val FIRMWARE = "firmware"
        const val MAX_UNFINISHED = 2
        /** The Core's detail name of QLC_D_LEASE_NO_ACCEL (21.6). */
        const val LEASE_NO_ACCEL = "lease_no_accel"
    }
}

/**
 * The golden test of an accelerator (SPEC 5, as 3.x ran it): the self-test clips through a stream on the program and a
 * stream on the CPU engine of the same model, their raw outputs compared per CTC head with the build's signed
 * tolerance: the greedy tokens' edit distance, the mean and 99th percentile of the log-prob distances. Without a
 * tolerance entry for a head, its tokens must match exactly. NPU int16 is not bit for bit the float engine; the
 * tolerance says how far it may be.
 */
internal object Golden {
    class Verdict(val passed: Boolean, val detail: String)

    private val CTC = listOf(HeadId.LOG_PROBS, HeadId.INTER1_LOG_PROBS, HeadId.INTER4_LOG_PROBS, HeadId.ID_LOG_PROBS, HeadId.TIMING_LOG_PROBS)

    /** [runs]: per clip, the accelerator's heads and the CPU's (each the take's chunks). */
    fun judge(runs: List<Triple<String, List<HeadValues>, List<HeadValues>>>, tolerance: ModelManifest.RawTolerance?): Verdict {
        if (runs.isEmpty()) return Verdict(false, "no golden clip")
        val heads = CTC.filter { h -> tolerance?.heads?.containsKey(h.key) ?: (h == HeadId.LOG_PROBS) }.ifEmpty { listOf(HeadId.LOG_PROBS) }
        val failed = ArrayList<String>()
        val text = StringBuilder()
        for (h in heads) {
            var edits = 0
            val d = ArrayList<Float>()
            for ((id, acc, cpu) in runs) {
                val a = HeadValues.concat(acc)
                val c = HeadValues.concat(cpu)
                if (a.n40 != c.n40) return Verdict(false, "$id: ${a.n40} frames on the accelerator, ${c.n40} on the CPU")
                val av = a[h] ?: continue
                val cv = c[h] ?: continue
                if (av.any { !it.isFinite() }) return Verdict(false, "$id: ${h.key} has NaN or inf on the accelerator")
                edits += editDistance(greedy(av, h.width), greedy(cv, h.width))
                for (i in av.indices) d.add(Math.abs(av[i] - cv[i]))
            }
            d.sort()
            val mean = if (d.isEmpty()) 0.0 else d.sumOf { it.toDouble() } / d.size
            val p99 = if (d.isEmpty()) 0.0 else d[(99 * d.size + 99) / 100 - 1].toDouble()
            val t = tolerance?.heads?.get(h.key)
            if (edits > (t?.tokenEdits ?: 0)) failed += "${h.key}.token_edits"
            if (t != null && mean > t.lpMean) failed += "${h.key}.lp_mean"
            if (t != null && p99 > t.lpP99) failed += "${h.key}.lp_p99"
            if (text.isNotEmpty()) text.append("; ")
            text.append("${h.key} $edits edits, lp_mean ${"%.4f".format(java.util.Locale.ROOT, mean)}, lp_p99 ${"%.4f".format(java.util.Locale.ROOT, p99)}")
        }
        return if (failed.isEmpty()) Verdict(true, "${runs.size} clips; $text")
        else Verdict(false, "${failed.joinToString(", ")} out of tolerance ($text)")
    }

    /** CTC greedy units: the best id per frame, repeats collapsed, blanks dropped. */
    fun greedy(v: FloatArray, width: Int): IntArray {
        val out = ArrayList<Int>()
        var prev = -1
        for (f in 0 until v.size / width) {
            var best = 0
            for (k in 1 until width) if (v[f * width + k] > v[f * width + best]) best = k
            if (best != prev && best != ModelFolder.BLANK) out.add(best)
            prev = best
        }
        return out.toIntArray()
    }

    fun editDistance(a: IntArray, b: IntArray): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }

    /** [x] at [from] Hz as samples at [to] Hz (linear): the golden clips pushed into a model opened for another rate.
     *  Both streams get the same samples, so the comparison holds. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * from / to
            val k = p.toInt()
            val f = (p - k).toFloat()
            if (k + 1 < x.size) x[k] * (1 - f) + x[k + 1] * f else x[x.size - 1]
        }
    }
}
