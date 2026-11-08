package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedFormer
import java.io.File

/**
 * Telemetry v2 (PLATFORM_CLIENT.md section 6): on by default, silent, anonymous by construction. The
 * recorder keeps one aggregate per UTC day: sums and histograms per cohort (revision, context,
 * backend, preset, word level), surah coverage and downloads. No audio, text, phonemes, verdict
 * lists, timestamps finer than the day, or identifiers are ever recorded, so none can be sent.
 *
 * Each day is a JSON file `<dir>/<yyyy-mm-dd>.json`, written when a session ends, when a download
 * or load is recorded, and at most every 30 s while chunks come in; at most 7 days are kept.
 */
class Telemetry(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Off: nothing is recorded and the stored days are dropped (developer opt-out or `flags.analytics` false). */
    @Volatile var enabled = true
        set(v) {
            field = v
            if (!v) dropAll()
        }

    private val lock = Any()
    private var day: String? = null
    private var agg: DayAggregate? = null
    private var session: CohortKey? = null
    private var sessionSites = 0
    private var sessionFlagged = 0
    private var sessionThrottled = false
    private var coldStartPending = true
    private var lastWrite = 0L
    private var dirty = false

    // ---- recording (TajweedObserver calls these through QuranLabCloud) ----

    fun modelLoad(e: TajweedObserver.ModelLoad) = rec { a ->
        val c = a.cohort(CohortKey(e.revision, e.context, e.backend, "balanced", "general", "", 0))
        Hist.startup(c.modelLoad, e.ms)
        persist(force = true)
    }

    fun selfTest(revision: String, context: String, backend: String, passed: Boolean) = rec { a ->
        val c = a.cohort(session ?: CohortKey(revision, context, backend, "balanced", "general", "", 0))
        if (passed) c.selfTestPass++ else c.selfTestFail++
        persist(force = true)
    }

    fun fallback(revision: String, context: String, from: String, reason: TajweedObserver.FallbackReason) = rec { a ->
        val c = a.cohort(session ?: CohortKey(revision, context, from, "balanced", "general", "", 0))
        c.fallbacks[reason.key] = (c.fallbacks[reason.key] ?: 0) + 1
        persist(force = true)
    }

    fun download(e: TajweedObserver.Download) = rec { a ->
        val k = DownloadKey(e.revision, DeviceProfile.token(e.profile) ?: "other")
        val d = a.downloads.getOrPut(k) { DownloadCounts() }
        d.bytes += e.bytes
        if (e.success) d.success++ else d.failed++
        if (e.resumed) d.resumed++
        persist(force = true)
    }

    fun sessionStart(e: TajweedObserver.SessionStart) = rec { a ->
        val bucket = if (e.wordLevel == "custom") Hist.wordBucket(e.wordThreshold ?: 0.0) else ""
        val k = CohortKey(e.revision, e.context, e.backend, e.preset, e.wordLevel, bucket, e.siteOverrides.coerceIn(0, 8))
        session = k
        sessionSites = 0
        sessionFlagged = 0
        sessionThrottled = false
        val c = a.cohort(k)
        for (ch in e.checksOn) if (ch in CHECKS) c.checksOn[ch] = (c.checksOn[ch] ?: 0) + 1
        if (coldStartPending) c.coldStartPending = true
    }

    /** The first chunk's latency of the first session of a launch is its cold start. */
    fun chunk(computeMs: Double, audioMs: Double) = rec { a ->
        val k = session ?: return@rec
        val c = a.cohort(k)
        c.chunks++
        Hist.latency(c.latency, computeMs)
        if (audioMs > 0) Hist.rtf(c.rtf, computeMs / audioMs)
        if (c.coldStartPending && coldStartPending) {
            Hist.startup(c.coldStart, computeMs)
            coldStartPending = false
        }
        c.coldStartPending = false
        persist(force = false)
    }

    fun tokens(margins: FloatArray?, confidences: FloatArray?) = rec { a ->
        val c = a.cohort(session ?: return@rec)
        margins?.forEach { Hist.margin(c.margin, it.toDouble()) }
        confidences?.forEach { Hist.confidence(c.confidence, it.toDouble()) }
    }

    fun scored(e: TajweedObserver.Scored) = rec { a ->
        val c = a.cohort(session ?: return@rec)
        for ((cls, sf) in e.classes) {
            if (cls !in CLASSES) continue
            val x = c.classes.getOrPut(cls) { intArrayOf(0, 0) }
            x[0] += sf.first
            x[1] += sf.second
            sessionSites += sf.first
            sessionFlagged += sf.second
        }
        c.maddC2 += e.maddC2
        c.maddC4 += e.maddC4
        c.maddC6 += e.maddC6
        c.maddFlagged += e.maddFlagged
        c.wordsScored += e.wordsScored
        c.wordsFlagged += e.wordsFlagged
        if (e.surah in 1..114) a.coverage[e.surah]++
    }

    fun thermal(throttled: Boolean) = rec { a ->
        val c = a.cohort(session ?: return@rec)
        c.thermalTransitions++
        if (throttled) sessionThrottled = true
    }

    fun sessionEnd(e: TajweedObserver.SessionEnd) = rec { a ->
        val k = session ?: return@rec
        val c = a.cohort(k)
        c.sessions++
        c.minutes += e.audioSeconds / 60
        if (!e.completed) c.aborted++
        if (sessionThrottled) c.throttledSessions++
        if (sessionSites > 0) Hist.flagPct(c.flagPct, 100.0 * sessionFlagged / sessionSites)
        session = null
        persist(force = true)
    }

    private inline fun rec(body: (DayAggregate) -> Unit) {
        if (!enabled) return
        try {
            synchronized(lock) {
                val today = Day.of(clock())
                if (day != today) {
                    persistNow()
                    day = today
                    agg = load(today) ?: DayAggregate()
                }
                body(agg!!)
                dirty = true
            }
        } catch (_: Exception) {
            // telemetry never disturbs recognition
        }
    }

    private fun persist(force: Boolean) {
        val now = clock()
        if (force || now - lastWrite > 30_000) persistNow()
    }

    private fun persistNow() {
        val d = day ?: return
        val a = agg ?: return
        if (!dirty) return
        try {
            dir.mkdirs()
            val tmp = File(dir, "$d.json.tmp")
            tmp.writeText(Json.write(a.toStore()))
            val f = File(dir, "$d.json")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            lastWrite = clock()
            dirty = false
        } catch (_: Exception) {
        }
    }

    /** Writes today's aggregate now (the app goes to the background). */
    fun flushToDisk() = synchronized(lock) { persistNow() }

    private fun load(d: String): DayAggregate? = try {
        DayAggregate.fromStore(Json.parse(File(dir, "$d.json").readText()))
    } catch (_: Exception) {
        null
    }

    // ---- sending ----

    /** Complete days on disk, oldest first; days older than 7 are deleted. */
    fun pendingDays(): List<String> {
        val today = Day.of(clock())
        val out = ArrayList<String>()
        for (f in dir.listFiles() ?: emptyArray()) {
            val m = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.json$").matchEntire(f.name) ?: continue
            val d = m.groupValues[1]
            val age = try { Day.between(d, today) } catch (_: Exception) { 99 }
            if (age > 7) f.delete() else if (age >= 1) out.add(d)
        }
        return out.sorted()
    }

    /** The v2 body of [d] (closed schema, at most 32 KB), or null when there is nothing in it. */
    fun payload(d: String, ctx: TelemetryContext): ByteArray? {
        val a = synchronized(lock) { if (d == day) agg else load(d) } ?: return null
        return TelemetryPayload.build(d, a, ctx)
    }

    fun drop(d: String) = synchronized(lock) {
        File(dir, "$d.json").delete()
        if (d == day) { agg = DayAggregate(); dirty = false }
    }

    private fun dropAll() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        agg = DayAggregate()
        dirty = false
    }

    companion object {
        val CLASSES = listOf("vowel_sub", "vowel_del", "vowel_ins", "short_to_long", "long_to_short", "cons_sub", "cons_del", "insertion")
        val CHECKS = listOf("letters", "vowels", "length", "madd", "gemination", "qalqalah", "lazim")
        val FALLBACKS = TajweedObserver.FallbackReason.values().map { it.key }
    }
}

/** What the payload says about the install, besides the day's sums. */
data class TelemetryContext(val app: AppIdentity, val device: DeviceProfile, val product: String = "android")

internal data class CohortKey(val revision: String, val context: String, val backend: String, val preset: String,
                              val wordLevel: String, val wordBucket: String, val siteOverrides: Int)

internal data class DownloadKey(val revision: String, val profile: String)

internal class DownloadCounts(var bytes: Long = 0, var success: Int = 0, var failed: Int = 0, var resumed: Int = 0)

internal class Cohort {
    var sessions = 0
    var minutes = 0.0
    var chunks = 0
    var aborted = 0
    val fallbacks = LinkedHashMap<String, Int>()
    var selfTestPass = 0
    var selfTestFail = 0
    val latency = LinkedHashMap<String, Int>()
    val rtf = LinkedHashMap<String, Int>()
    val coldStart = LinkedHashMap<String, Int>()
    val modelLoad = LinkedHashMap<String, Int>()
    var thermalTransitions = 0
    var throttledSessions = 0
    val checksOn = LinkedHashMap<String, Int>()
    val classes = LinkedHashMap<String, IntArray>()
    val flagPct = LinkedHashMap<String, Int>()
    val margin = LinkedHashMap<String, Int>()
    val confidence = LinkedHashMap<String, Int>()
    var maddC2 = 0
    var maddC4 = 0
    var maddC6 = 0
    var maddFlagged = 0
    var wordsScored = 0
    var wordsFlagged = 0
    @Transient var coldStartPending = false

    fun merge(o: Cohort) {
        sessions += o.sessions; minutes += o.minutes; chunks += o.chunks; aborted += o.aborted
        selfTestPass += o.selfTestPass; selfTestFail += o.selfTestFail
        thermalTransitions += o.thermalTransitions; throttledSessions += o.throttledSessions
        maddC2 += o.maddC2; maddC4 += o.maddC4; maddC6 += o.maddC6; maddFlagged += o.maddFlagged
        wordsScored += o.wordsScored; wordsFlagged += o.wordsFlagged
        for ((dst, src) in listOf(fallbacks to o.fallbacks, latency to o.latency, rtf to o.rtf, coldStart to o.coldStart,
            modelLoad to o.modelLoad, checksOn to o.checksOn, flagPct to o.flagPct, margin to o.margin, confidence to o.confidence)) {
            for ((k, v) in src) dst[k] = (dst[k] ?: 0) + v
        }
        for ((k, v) in o.classes) { val x = classes.getOrPut(k) { intArrayOf(0, 0) }; x[0] += v[0]; x[1] += v[1] }
    }
}

internal class DayAggregate {
    val cohorts = LinkedHashMap<CohortKey, Cohort>()
    val coverage = IntArray(115)
    val downloads = LinkedHashMap<DownloadKey, DownloadCounts>()

    fun cohort(k: CohortKey): Cohort = cohorts.getOrPut(k) { Cohort() }

    fun isEmpty(): Boolean = cohorts.isEmpty() && downloads.isEmpty() && coverage.all { it == 0 }

    /** The on-disk form (the payload's fields plus the cohort keys). */
    fun toStore(): Map<String, Any?> = linkedMapOf(
        "cohorts" to cohorts.map { (k, c) -> TelemetryPayload.cohortJson(k, c) },
        "coverage" to coverage.withIndex().filter { it.value > 0 }.associate { it.index.toString() to it.value },
        "downloads" to downloads.map { (k, d) -> linkedMapOf("revision" to k.revision, "profile" to k.profile, "bytes" to d.bytes,
            "success" to d.success, "failed" to d.failed, "resumed" to d.resumed) },
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromStore(v: Any?): DayAggregate {
            val m = v as Map<String, Any?>
            val a = DayAggregate()
            for (x in m["cohorts"] as List<Any?>) {
                val o = x as Map<String, Any?>
                val k = CohortKey(o["revision"] as String, o["context"] as String, o["backend"] as String, o["preset"] as String,
                    o["word_level"] as String, o["word_bucket"] as? String ?: "", (o["site_overrides"] as? Double)?.toInt() ?: 0)
                val c = a.cohort(k)
                fun i(key: String) = (o[key] as? Double)?.toInt() ?: 0
                fun h(key: String, dst: MutableMap<String, Int>) {
                    (o[key] as? Map<String, Any?>)?.forEach { (b, n) -> dst[b] = (n as Double).toInt() }
                }
                fun pair(key: String, a: String, b: String): Pair<Int, Int> {
                    val p = o[key] as? Map<String, Any?> ?: return 0 to 0
                    return ((p[a] as? Double)?.toInt() ?: 0) to ((p[b] as? Double)?.toInt() ?: 0)
                }
                c.sessions = i("sessions"); c.minutes = o["minutes"] as? Double ?: 0.0; c.chunks = i("chunks"); c.aborted = i("aborted")
                h("fallbacks", c.fallbacks)
                pair("self_test", "pass", "fail").let { c.selfTestPass = it.first; c.selfTestFail = it.second }
                h("latency_ms", c.latency); h("rtf", c.rtf); h("cold_start_ms", c.coldStart); h("model_load_ms", c.modelLoad)
                pair("thermal", "transitions", "throttled_sessions").let { c.thermalTransitions = it.first; c.throttledSessions = it.second }
                h("checks_on", c.checksOn)
                (o["classes"] as? Map<String, Any?>)?.forEach { (cls, sf) ->
                    val s = sf as Map<String, Any?>
                    c.classes[cls] = intArrayOf((s["sites"] as? Double)?.toInt() ?: 0, (s["flagged"] as? Double)?.toInt() ?: 0)
                }
                h("session_flag_pct", c.flagPct); h("margin", c.margin); h("confidence", c.confidence)
                (o["madd"] as? Map<String, Any?>)?.let { md ->
                    c.maddC2 = (md["c2"] as? Double)?.toInt() ?: 0; c.maddC4 = (md["c4"] as? Double)?.toInt() ?: 0
                    c.maddC6 = (md["c6"] as? Double)?.toInt() ?: 0; c.maddFlagged = (md["flagged"] as? Double)?.toInt() ?: 0
                }
                pair("words", "scored", "flagged").let { c.wordsScored = it.first; c.wordsFlagged = it.second }
            }
            (m["coverage"] as? Map<String, Any?>)?.forEach { (s, n) -> s.toIntOrNull()?.takeIf { it in 1..114 }?.let { a.coverage[it] = (n as Double).toInt() } }
            (m["downloads"] as? List<Any?>)?.forEach { x ->
                val o = x as Map<String, Any?>
                a.downloads[DownloadKey(o["revision"] as String, o["profile"] as String)] = DownloadCounts(
                    (o["bytes"] as? Double)?.toLong() ?: 0, (o["success"] as? Double)?.toInt() ?: 0,
                    (o["failed"] as? Double)?.toInt() ?: 0, (o["resumed"] as? Double)?.toInt() ?: 0)
            }
            return a
        }
    }
}

/** The histogram buckets of section 6, exactly as the server's schema names them. */
internal object Hist {
    private fun add(h: MutableMap<String, Int>, k: String) { h[k] = (h[k] ?: 0) + 1 }

    private fun bucket(h: MutableMap<String, Int>, v: Double, edges: DoubleArray, names: List<String>) {
        if (!v.isFinite()) return
        for (i in edges.indices) if (v <= edges[i]) return add(h, names[i])
        add(h, names.last())
    }

    val LATENCY = listOf("le_5", "le_10", "le_20", "le_40", "le_80", "le_160", "le_320", "gt_320")
    val RTF = listOf("le_0_1", "le_0_25", "le_0_5", "le_1", "gt_1")
    val STARTUP = listOf("le_250", "le_500", "le_1000", "le_2000", "le_4000", "gt_4000")
    val MARGIN = listOf("le_0_5", "le_1", "le_2", "le_4", "le_8", "gt_8")
    val CONFIDENCE = listOf("le_0_5", "le_0_7", "le_0_8", "le_0_9", "le_0_95", "gt_0_95")
    val FLAG_PCT = listOf("le_1", "le_2", "le_5", "le_10", "le_20", "gt_20")
    val WORD = listOf("le_3", "le_4", "le_5", "le_6", "le_7", "gt_7")

    fun latency(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(5.0, 10.0, 20.0, 40.0, 80.0, 160.0, 320.0), LATENCY)
    fun rtf(h: MutableMap<String, Int>, r: Double) = bucket(h, r, doubleArrayOf(0.1, 0.25, 0.5, 1.0), RTF)
    fun startup(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0), STARTUP)
    fun margin(h: MutableMap<String, Int>, m: Double) = bucket(h, m, doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0), MARGIN)
    fun confidence(h: MutableMap<String, Int>, c: Double) = bucket(h, c, doubleArrayOf(0.5, 0.7, 0.8, 0.9, 0.95), CONFIDENCE)
    fun flagPct(h: MutableMap<String, Int>, p: Double) = bucket(h, p, doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0), FLAG_PCT)

    fun wordBucket(t: Double): String {
        val edges = doubleArrayOf(3.0, 4.0, 5.0, 6.0, 7.0)
        for (i in edges.indices) if (t <= edges[i]) return WORD[i]
        return WORD.last()
    }
}

/** The v2 body: closed schema, zero buckets left out ("missing buckets mean 0"), at most 32 KB. */
internal object TelemetryPayload {
    const val MAX_BYTES = 32 * 1024
    const val MAX_COHORTS = 16
    const val MAX_DOWNLOADS = 8

    private fun hist(h: Map<String, Int>, names: List<String>): Map<String, Int>? =
        names.filter { (h[it] ?: 0) > 0 }.associateWith { minOf(h[it]!!, 10_000_000) }.takeIf { it.isNotEmpty() }

    private fun cap(n: Int) = n.coerceIn(0, 10_000_000)

    fun cohortJson(k: CohortKey, c: Cohort): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        o["revision"] = k.revision
        o["context"] = k.context
        o["backend"] = k.backend
        o["preset"] = k.preset
        o["word_level"] = k.wordLevel
        o["word_bucket"] = k.wordBucket
        o["site_overrides"] = k.siteOverrides
        o["sessions"] = cap(c.sessions)
        o["minutes"] = Math.round(c.minutes * 100) / 100.0
        o["chunks"] = cap(c.chunks)
        if (c.aborted > 0) o["aborted"] = cap(c.aborted)
        hist(c.fallbacks, Telemetry.FALLBACKS)?.let { o["fallbacks"] = it }
        if (c.selfTestPass + c.selfTestFail > 0) o["self_test"] = linkedMapOf("pass" to cap(c.selfTestPass), "fail" to cap(c.selfTestFail))
        hist(c.latency, Hist.LATENCY)?.let { o["latency_ms"] = it }
        hist(c.rtf, Hist.RTF)?.let { o["rtf"] = it }
        hist(c.coldStart, Hist.STARTUP)?.let { o["cold_start_ms"] = it }
        hist(c.modelLoad, Hist.STARTUP)?.let { o["model_load_ms"] = it }
        if (c.thermalTransitions + c.throttledSessions > 0) {
            o["thermal"] = linkedMapOf("transitions" to cap(c.thermalTransitions), "throttled_sessions" to cap(c.throttledSessions))
        }
        hist(c.checksOn, Telemetry.CHECKS)?.let { o["checks_on"] = it }
        val cls = Telemetry.CLASSES.filter { c.classes[it] != null }
            .associateWith { linkedMapOf("sites" to cap(c.classes[it]!![0]), "flagged" to cap(c.classes[it]!![1])) }
        if (cls.isNotEmpty()) o["classes"] = cls
        hist(c.flagPct, Hist.FLAG_PCT)?.let { o["session_flag_pct"] = it }
        hist(c.margin, Hist.MARGIN)?.let { o["margin"] = it }
        hist(c.confidence, Hist.CONFIDENCE)?.let { o["confidence"] = it }
        if (c.maddC2 + c.maddC4 + c.maddC6 + c.maddFlagged > 0) {
            o["madd"] = linkedMapOf("c2" to cap(c.maddC2), "c4" to cap(c.maddC4), "c6" to cap(c.maddC6), "flagged" to cap(c.maddFlagged))
        }
        if (c.wordsScored + c.wordsFlagged > 0) o["words"] = linkedMapOf("scored" to cap(c.wordsScored), "flagged" to cap(c.wordsFlagged))
        return o
    }

    fun build(day: String, a: DayAggregate, ctx: TelemetryContext): ByteArray? {
        if (a.isEmpty()) return null
        // the 16 largest cohorts by sessions; the rest merged into the 16th
        val sorted = a.cohorts.entries.sortedByDescending { it.value.sessions }
        val kept = sorted.take(MAX_COHORTS).map { it.key to Cohort().apply { merge(it.value) } }.toMutableList()
        for (e in sorted.drop(MAX_COHORTS)) kept.last().second.merge(e.value)
        val dl = a.downloads.entries.toList()
        val dlKept = dl.take(MAX_DOWNLOADS).map { it.key to DownloadCounts(it.value.bytes, it.value.success, it.value.failed, it.value.resumed) }
        for (e in dl.drop(MAX_DOWNLOADS)) dlKept.last().second.let { d -> d.bytes += e.value.bytes; d.success += e.value.success; d.failed += e.value.failed; d.resumed += e.value.resumed }
        val dev = LinkedHashMap<String, Any?>()
        dev["class"] = ctx.device.deviceClass
        ctx.device.soc?.let { dev["soc"] = it }
        dev["os_major"] = ctx.device.osMajor.coerceIn(1, 99)
        ctx.device.ramTier?.let { dev["ram_tier"] = it }
        val b = LinkedHashMap<String, Any?>()
        b["v"] = 2
        b["day"] = day
        b["product"] = ctx.product
        b["platform"] = ctx.app.platform
        b["sdk_version"] = TajweedFormer.VERSION
        b["app_version"] = ctx.app.version
        b["device"] = dev
        b["cohorts"] = kept.map { cohortJson(it.first, it.second) }
        val cov = a.coverage.withIndex().filter { it.index in 1..114 && it.value > 0 }.associate { it.index.toString() to minOf(it.value, 1_000_000) }
        if (cov.isNotEmpty()) b["coverage"] = cov
        if (dlKept.isNotEmpty()) b["downloads"] = dlKept.map { (k, d) ->
            linkedMapOf("revision" to k.revision, "profile" to k.profile, "bytes" to d.bytes.coerceIn(0, 1_000_000_000_000), "success" to cap(d.success),
                "failed" to cap(d.failed), "resumed" to cap(d.resumed))
        }
        var bytes = Json.write(b).toByteArray(Charsets.UTF_8)
        // over the cap (never seen in practice): the histograms of quality go first, then cohorts
        if (bytes.size > MAX_BYTES) {
            @Suppress("UNCHECKED_CAST")
            val cs = (b["cohorts"] as List<Map<String, Any?>>).map { it.filterKeys { k -> k !in setOf("margin", "confidence", "session_flag_pct") } }
            b["cohorts"] = cs
            bytes = Json.write(b).toByteArray(Charsets.UTF_8)
            while (bytes.size > MAX_BYTES && (b["cohorts"] as List<*>).size > 1) {
                b["cohorts"] = (b["cohorts"] as List<*>).dropLast(1)
                bytes = Json.write(b).toByteArray(Charsets.UTF_8)
            }
        }
        return bytes
    }
}
