package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedFormer
import java.io.File

/**
 * Telemetry v2 (docs/platform-client.md): on by default, silent, anonymous by construction. The
 * recorder sums what happens per UTC day: per cohort (revision, context, backend, preset, word level)
 * sums and histograms, surah coverage, downloads, and since 2.0.1 launches, ayahs, words, per-rule checks
 * and flags, and the sharing sheet's asks and answers. No audio, text, phonemes, verdict lists,
 * timestamps finer than the day, or identifiers are ever recorded, so none can be sent.
 *
 * Deltas (2.0.1): what was counted since the last send point waits in `<dir>/<yyyy-mm-dd>.json` (one per
 * UTC day, written when a session ends, when a download or load is recorded, and at most every 30 s while
 * chunks come in). At a send point ([seal]) each day's counts become a sealed report: the exact body, with
 * a fresh random `report_id`, written to `<dir>/sealed/` before it is sent, so a retry sends the same bytes
 * and the server counts it once. A report never spans midnight; reports go oldest first; unsent ones older
 * than 7 days are deleted.
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
    private var lastSeq = 0L
    private val sealedDir get() = File(dir, "sealed")

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

    /** The app was launched or came back to the foreground. */
    fun launch() = rec { a ->
        a.launches++
        persist(force = true)
    }

    /** A consent sheet was shown, or the app's own UI was asked to ask (where the SDK counts an ask). */
    fun shareAsked() = rec { a ->
        a.shareAsked++
        persist(force = true)
    }

    /** The learner answered a consent sheet (the SDK's or the app's own): share, or not now. */
    fun shareAnswer(yes: Boolean) = rec { a ->
        if (yes) a.shareYes++ else a.shareNo++
        persist(force = true)
    }

    fun scored(e: TajweedObserver.Scored) = rec { a ->
        a.ayahs++
        a.words += e.wordsScored
        for ((k, n) in e.ruleChecks) {
            if (!RULE_KEY.matches(k) || n <= 0) continue
            if (k !in a.ruleChecks && a.ruleChecks.size >= MAX_RULES) continue
            a.ruleChecks[k] = (a.ruleChecks[k] ?: 0) + n
            val f = minOf(e.ruleFlags[k] ?: 0, n)
            if (f > 0) a.ruleFlags[k] = (a.ruleFlags[k] ?: 0) + f
        }
        if (e.surah in 1..114) a.coverage[e.surah]++
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
    }

    /** A promotion attempt ended (or a crash at a session's first accelerator call was found at launch). */
    fun promotion(e: ai.quranlab.tajweedformer.PromotionEvent) = rec { a ->
        val p = a.promotion(PromoKey(e.backend, e.revision, e.context))
        if (e.countsAttempt) {
            p.attempts++
            if (e.promoted) p.promoted++ else if (e.stage in Promo.FAILED_AT) inc(p.failedAt, e.stage)
        }
        if (!e.promoted) inc(p.reasons, e.reason?.takeIf { it in Promo.REASONS } ?: "other")
        // a crash at a promoted build's first session call (found at launch) took it back to the CPU
        if (!e.promoted && e.stage == "session") p.demoted++
        e.compileMs?.let { Hist.compile(p.compileMs, it.toDouble()) }
        e.speedup?.let { Hist.speedup(p.speedup, it) }
        persist(force = true)
    }

    /** A session switched from the accelerator to the CPU: `failovers` and its cause and stall always (a single missed
     *  limit too: it is still a switch for that session); `demoted` and `reasons.failover` only when it demoted the key. */
    fun failover(e: ai.quranlab.tajweedformer.AcceleratorFailoverEvent) = rec { a ->
        val p = a.promotion(PromoKey(e.backend, e.revision, e.context))
        p.failovers++
        inc(p.causes, e.failover.cause.takeIf { it in Promo.CAUSES } ?: "exception")
        Hist.stall(p.stall, e.failover.stallMs)
        if (e.failover.demoted) {
            p.demoted++
            inc(p.reasons, "failover")
        }
        persist(force = true)
    }

    /** A blank stretch the CPU confirmed (no switch): `blank_confirmed_ok`. */
    fun blankConfirmed(e: ai.quranlab.tajweedformer.BlankConfirmedEvent) = rec { a ->
        a.promotion(PromoKey(e.backend, e.revision, e.context)).blankConfirmedOk++
        persist(force = true)
    }

    private fun inc(h: MutableMap<String, Int>, k: String) { h[k] = (h[k] ?: 0) + 1 }

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

    // ---- sealing and sending ----

    private fun age(d: String): Long = try { Day.between(d, Day.of(clock())) } catch (_: Exception) { 99L }

    /** Days with counts not sealed yet, oldest first (today's included); days older than 7 are deleted. */
    fun openDays(): List<String> = synchronized(lock) {
        persistNow()
        val out = ArrayList<String>()
        for (f in dir.listFiles() ?: emptyArray()) {
            val m = DAY_FILE.matchEntire(f.name) ?: continue
            val d = m.groupValues[1]
            if (age(d) > 7) f.delete() else out.add(d)
        }
        out.sorted()
    }

    /**
     * A send point: every day's counts since the last seal become one sealed report each (the v2 body with a
     * fresh `report_id`), on disk before anything is sent; the counting starts again from zero. Returns the
     * number of reports sealed.
     */
    fun seal(ctx: TelemetryContext): Int {
        if (!enabled) return 0
        return try {
            synchronized(lock) {
                var n = 0
                for (d in openDays()) {
                    val f = File(dir, "$d.json")
                    val a = if (d == day) agg else load(d)
                    val id = newReportId()
                    val body = a?.let { TelemetryPayload.build(d, it, ctx, id) }
                    if (body != null) {
                        sealedDir.mkdirs()
                        lastSeq = maxOf(clock(), lastSeq + 1)
                        val name = "%s.%015d.%s.json".format(d, lastSeq, id)
                        val tmp = File(sealedDir, "$name.tmp")
                        tmp.writeBytes(body)
                        val out = File(sealedDir, name)
                        if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) throw java.io.IOException("cannot write $name") }
                        n++
                    }
                    f.delete()
                    if (d == day) {
                        // the open session goes on counting into a fresh aggregate (its cold start still pending)
                        val pending = session?.let { agg?.cohorts?.get(it)?.coldStartPending } == true
                        agg = DayAggregate()
                        if (pending) session?.let { agg!!.cohort(it).coldStartPending = true }
                        dirty = false
                    }
                }
                n
            }
        } catch (_: Exception) {
            0
        }
    }

    /** Sealed reports not yet accepted, oldest first; unsent ones older than 7 days are deleted. */
    fun pendingReports(): List<File> = synchronized(lock) {
        val out = ArrayList<File>()
        for (f in sealedDir.listFiles() ?: emptyArray()) {
            val m = REPORT_FILE.matchEntire(f.name) ?: continue
            if (age(m.groupValues[1]) > 7) f.delete() else out.add(f)
        }
        out.sortedBy { it.name }
    }

    /** A report was accepted (202) or refused for good (400, 413): it is never sent again. */
    fun dropReport(f: File) = synchronized(lock) { f.delete() }

    private fun dropAll() = synchronized(lock) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        agg = DayAggregate()
        dirty = false
    }

    companion object {
        /** A send point while the app is in use: every 15 minutes (Quran Lab's choice for 2.0.1). */
        const val SEND_INTERVAL_MS = 15 * 60_000L
        /** `rule_checks` / `rule_flags` keys: the server's pattern, at most 32 of them. */
        val RULE_KEY = Regex("^[a-z][a-z0-9_]{0,23}$")
        const val MAX_RULES = 32
        private val DAY_FILE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.json$")
        private val REPORT_FILE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.([0-9a-f]{32})\\.json$")
        private val random = java.security.SecureRandom()

        /** 16 random bytes, 32 lower-case hex: drawn per report, never per install or user. */
        fun newReportId(): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

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

internal data class PromoKey(val backend: String, val revision: String, val context: String)

/** One `promotions` entry's counts. */
internal class PromoCounts {
    var attempts = 0
    var promoted = 0
    val failedAt = LinkedHashMap<String, Int>()
    val reasons = LinkedHashMap<String, Int>()
    val compileMs = LinkedHashMap<String, Int>()
    val speedup = LinkedHashMap<String, Int>()
    var failovers = 0
    /** Promoted builds taken back to the CPU after an in-session failure. */
    var demoted = 0
    /** Blank suspicions the CPU confirmed as correct (no failover). */
    var blankConfirmedOk = 0
    val causes = LinkedHashMap<String, Int>()
    val stall = LinkedHashMap<String, Int>()
}

/** The `promotions` entry's closed key sets. */
internal object Promo {
    const val MAX = 8
    val FAILED_AT = listOf("download", "compile", "warmup", "golden")
    val REASONS = listOf("network", "storage", "verify", "compile_error", "compile_timeout", "load_error", "crash", "hang", "non_finite",
        "golden_mismatch", "cpu_mismatch", "failover", "denylist", "other")
    val CAUSES = listOf("exception", "non_finite", "out_of_range", "blank", "deadline")

    /** The entry as the payload has it (and as the day file keeps it): zero counts and empty objects left out. */
    fun json(k: PromoKey, c: PromoCounts, store: Boolean = false): Map<String, Any?> {
        fun cap(n: Int) = n.coerceIn(0, 10_000_000)
        fun hist(h: Map<String, Int>, names: List<String>): Map<String, Int>? =
            names.filter { (h[it] ?: 0) > 0 }.associateWith { cap(h[it]!!) }.takeIf { it.isNotEmpty() }
        val o = LinkedHashMap<String, Any?>()
        o["backend"] = k.backend
        o["revision"] = k.revision
        o["context"] = k.context
        if (c.attempts > 0 || store) o["attempts"] = cap(c.attempts)
        if (c.promoted > 0 || store) o["promoted"] = cap(c.promoted)
        hist(c.failedAt, FAILED_AT)?.let { o["failed_at"] = it }
        hist(c.reasons, REASONS)?.let { o["reasons"] = it }
        hist(c.compileMs, Hist.COMPILE)?.let { o["compile_ms"] = it }
        hist(c.speedup, Hist.SPEEDUP)?.let { o["speedup"] = it }
        if (c.failovers > 0 || store) o["failovers"] = cap(c.failovers)
        hist(c.causes, CAUSES)?.let { o["failover_causes"] = it }
        hist(c.stall, Hist.STALL)?.let { o["failover_stall_ms"] = it }
        if (c.demoted > 0 || store) o["demoted"] = cap(c.demoted)
        if (c.blankConfirmedOk > 0 || store) o["blank_confirmed_ok"] = cap(c.blankConfirmedOk)
        return o
    }

    @Suppress("UNCHECKED_CAST")
    fun fromJson(o: Map<String, Any?>): Pair<PromoKey, PromoCounts> {
        val c = PromoCounts()
        fun n(v: Any?) = (v as? Double)?.toInt() ?: 0
        fun h(key: String, dst: MutableMap<String, Int>) { (o[key] as? Map<String, Any?>)?.forEach { (b, v) -> dst[b] = n(v) } }
        c.attempts = n(o["attempts"]); c.promoted = n(o["promoted"]); c.failovers = n(o["failovers"]); c.demoted = n(o["demoted"])
        c.blankConfirmedOk = n(o["blank_confirmed_ok"])
        h("failed_at", c.failedAt); h("reasons", c.reasons); h("compile_ms", c.compileMs); h("speedup", c.speedup)
        h("failover_causes", c.causes); h("failover_stall_ms", c.stall)
        return PromoKey(o["backend"] as String, o["revision"] as String, o["context"] as String) to c
    }
}

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
    var launches = 0
    var ayahs = 0
    var words = 0
    val ruleChecks = LinkedHashMap<String, Int>()
    val ruleFlags = LinkedHashMap<String, Int>()
    var shareAsked = 0
    var shareYes = 0
    var shareNo = 0
    val promotions = LinkedHashMap<PromoKey, PromoCounts>()

    fun cohort(k: CohortKey): Cohort = cohorts.getOrPut(k) { Cohort() }

    fun promotion(k: PromoKey): PromoCounts = promotions.getOrPut(k) { PromoCounts() }

    fun isEmpty(): Boolean = cohorts.isEmpty() && downloads.isEmpty() && coverage.all { it == 0 } && launches == 0 && ayahs == 0 &&
        words == 0 && ruleChecks.isEmpty() && shareAsked == 0 && shareYes == 0 && shareNo == 0 && promotions.isEmpty()

    /** The on-disk form (the payload's fields plus the cohort keys). */
    fun toStore(): Map<String, Any?> = linkedMapOf(
        "cohorts" to cohorts.map { (k, c) -> TelemetryPayload.cohortJson(k, c) },
        "coverage" to coverage.withIndex().filter { it.value > 0 }.associate { it.index.toString() to it.value },
        "downloads" to downloads.map { (k, d) -> linkedMapOf("revision" to k.revision, "profile" to k.profile, "bytes" to d.bytes,
            "success" to d.success, "failed" to d.failed, "resumed" to d.resumed) },
        "launches" to launches, "ayahs" to ayahs, "words" to words, "rule_checks" to ruleChecks, "rule_flags" to ruleFlags,
        "share" to linkedMapOf("asked" to shareAsked, "yes" to shareYes, "no" to shareNo),
        "promotions" to promotions.map { (k, c) -> Promo.json(k, c, store = true) },
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
            fun n(v: Any?) = (v as? Double)?.toInt() ?: 0
            a.launches = n(m["launches"]); a.ayahs = n(m["ayahs"]); a.words = n(m["words"])
            (m["rule_checks"] as? Map<String, Any?>)?.forEach { (k, v) -> a.ruleChecks[k] = n(v) }
            (m["rule_flags"] as? Map<String, Any?>)?.forEach { (k, v) -> a.ruleFlags[k] = n(v) }
            (m["share"] as? Map<String, Any?>)?.let { sh -> a.shareAsked = n(sh["asked"]); a.shareYes = n(sh["yes"]); a.shareNo = n(sh["no"]) }
            (m["promotions"] as? List<Any?>)?.forEach { x -> val (k, c) = Promo.fromJson(x as Map<String, Any?>); a.promotions[k] = c }
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
    val COMPILE = listOf("le_1000", "le_5000", "le_15000", "le_30000", "le_60000", "le_120000", "gt_120000")
    val SPEEDUP = listOf("lt_0_5", "lt_1", "le_1_5", "le_2", "le_3", "le_5", "gt_5")
    val STALL = listOf("le_50", "le_100", "le_200", "le_300", "le_500", "le_1000", "gt_1000")

    fun latency(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(5.0, 10.0, 20.0, 40.0, 80.0, 160.0, 320.0), LATENCY)
    fun rtf(h: MutableMap<String, Int>, r: Double) = bucket(h, r, doubleArrayOf(0.1, 0.25, 0.5, 1.0), RTF)
    fun startup(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0), STARTUP)
    fun margin(h: MutableMap<String, Int>, m: Double) = bucket(h, m, doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0), MARGIN)
    fun confidence(h: MutableMap<String, Int>, c: Double) = bucket(h, c, doubleArrayOf(0.5, 0.7, 0.8, 0.9, 0.95), CONFIDENCE)
    fun flagPct(h: MutableMap<String, Int>, p: Double) = bucket(h, p, doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0), FLAG_PCT)
    fun compile(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(1000.0, 5000.0, 15000.0, 30000.0, 60000.0, 120000.0), COMPILE)
    fun stall(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(50.0, 100.0, 200.0, 300.0, 500.0, 1000.0), STALL)

    /** CPU median over the accelerator's: `lt_0_5` and `lt_1` mean the accelerator was slower (and promoted anyway). */
    fun speedup(h: MutableMap<String, Int>, x: Double) {
        if (!x.isFinite()) return
        val k = when {
            x < 0.5 -> "lt_0_5"
            x < 1.0 -> "lt_1"
            x <= 1.5 -> "le_1_5"
            x <= 2.0 -> "le_2"
            x <= 3.0 -> "le_3"
            x <= 5.0 -> "le_5"
            else -> "gt_5"
        }
        add(h, k)
    }

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

    /** The v2 body of one delta; [reportId] (32 lower-case hex) is always sent. Null when nothing was counted. */
    fun build(day: String, a: DayAggregate, ctx: TelemetryContext, reportId: String): ByteArray? {
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
        // 2.0.1: the delta's id and counters, each left out when zero
        b["report_id"] = reportId
        if (a.launches > 0) b["launches"] = cap(a.launches)
        if (a.ayahs > 0) b["ayahs"] = cap(a.ayahs)
        if (cov.isNotEmpty()) b["surah_ayahs"] = cov
        if (a.words > 0) b["words"] = cap(a.words)
        val checks = a.ruleChecks.filter { (k, n) -> n > 0 && Telemetry.RULE_KEY.matches(k) }.entries.take(Telemetry.MAX_RULES).associate { it.key to cap(it.value) }
        if (checks.isNotEmpty()) b["rule_checks"] = checks
        // every flagged key is a checked key, with a count no larger
        val flags = checks.mapNotNull { (k, n) -> minOf(a.ruleFlags[k] ?: 0, n).takeIf { it > 0 }?.let { k to it } }.toMap()
        if (flags.isNotEmpty()) b["rule_flags"] = flags
        if (a.shareAsked > 0) b["share_asked"] = cap(a.shareAsked)
        if (a.shareYes > 0) b["share_yes"] = cap(a.shareYes)
        if (a.shareNo > 0) b["share_no"] = cap(a.shareNo)
        // the accelerator's promotions and failovers: at most 8 entries, the busiest kept
        val promos = a.promotions.entries.sortedByDescending { it.value.attempts + it.value.failovers + it.value.blankConfirmedOk }.take(Promo.MAX)
            .map { (k, c) -> Promo.json(k, c) }.filter { it.size > 3 }
        if (promos.isNotEmpty()) b["promotions"] = promos
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
