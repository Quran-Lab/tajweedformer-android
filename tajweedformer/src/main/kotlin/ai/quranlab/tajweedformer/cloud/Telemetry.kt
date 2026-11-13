package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.SdkInfo
import java.io.File

/**
 * Telemetry v2 (docs/platform-client.md): on by default, silent, anonymous by construction. The
 * recorder sums what happens per UTC day. SDK-measured: per cohort (model version and build, context, backend)
 * sums and histograms, and since 3.0 the takes, the seconds of audio, speech, silence and compute and the greedy
 * tokens per head; downloads, launches, promotions, the sharing sheet's asks and answers. App-reported (only when the
 * app calls `session.reportRecited` or `client.analytics.reportChecks`): ayahs, the ayahs per surah, words and
 * per-rule checks and flags, listed in the payload's `reported`. No audio, text, phonemes, timestamps finer than
 * the day, or identifiers are ever recorded, so none can be sent.
 *
 * Deltas (2.0.1): what was counted since the last send point waits in `<dir>/<yyyy-mm-dd>.json` (one per
 * UTC day, written when a session ends, when a download or load is recorded, and at most every 30 s while
 * chunks come in). At a send point ([seal]) each day's counts become a sealed report: the exact body, with
 * a fresh random `report_id`, written to `<dir>/sealed/` before it is sent, so a retry sends the same bytes
 * and the server counts it once. A report never spans midnight; reports go oldest first; unsent ones older
 * than 7 days are deleted.
 */
internal class Telemetry(
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
    private var sessionThrottled = false
    /** What the chunks of the open take added to its cohort so far: taken back when the take ends without audio. */
    private var added = Cohort()
    private var coldStartPending = true
    private var lastWrite = 0L
    private var dirty = false
    private var lastSeq = 0L
    private val sealedDir get() = File(dir, "sealed")

    // ---- recording (TajweedObserver calls these through QuranLabCloud) ----

    fun modelLoad(e: TajweedObserver.ModelLoad) = rec { a ->
        val c = a.cohort(CohortKey(e.revision, e.context, e.backend, e.build))
        Hist.startup(c.modelLoad, e.ms)
        persist(force = true)
    }

    fun selfTest(revision: String, context: String, backend: String, passed: Boolean, build: String = "") = rec { a ->
        val c = a.cohort(session ?: CohortKey(revision, context, backend, build))
        if (passed) c.selfTestPass++ else c.selfTestFail++
        persist(force = true)
    }

    fun fallback(revision: String, context: String, from: String, reason: TajweedObserver.FallbackReason, build: String = "") = rec { a ->
        val c = a.cohort(session ?: CohortKey(revision, context, from, build))
        c.fallbacks[reason.key] = (c.fallbacks[reason.key] ?: 0) + 1
        persist(force = true)
    }

    fun download(e: TajweedObserver.Download) = rec { a ->
        val k = DownloadKey(e.revision, DeviceProfile.token(e.profile) ?: "other", e.build)
        val d = a.downloads.getOrPut(k) { DownloadCounts() }
        d.bytes += e.bytes
        if (e.success) d.success++ else d.failed++
        if (e.resumed) d.resumed++
        persist(force = true)
    }

    fun sessionStart(e: TajweedObserver.SessionStart) = rec { a ->
        val k = CohortKey(e.revision, e.context, e.backend, e.build)
        session = k
        sessionThrottled = false
        added = Cohort()
        val c = a.cohort(k)
        if (coldStartPending) c.coldStartPending = true
    }

    /** The first chunk's latency of the first session of a launch is its cold start. */
    fun chunk(computeMs: Double, audioMs: Double) = rec { a ->
        val k = session ?: return@rec
        val c = a.cohort(k)
        c.chunks++
        Hist.latency(c.latency, computeMs)
        if (audioMs > 0) Hist.rtf(c.rtf, computeMs / audioMs)
        added.chunks++
        Hist.latency(added.latency, computeMs)
        if (audioMs > 0) Hist.rtf(added.rtf, computeMs / audioMs)
        if (c.coldStartPending && coldStartPending) {
            Hist.startup(c.coldStart, computeMs)
            Hist.startup(added.coldStart, computeMs)
            coldStartPending = false
        }
        c.coldStartPending = false
        persist(force = false)
    }

    fun tokens(margins: FloatArray?, confidences: FloatArray?) = rec { a ->
        val c = a.cohort(session ?: return@rec)
        margins?.forEach { Hist.margin(c.margin, it.toDouble()); Hist.margin(added.margin, it.toDouble()) }
        confidences?.forEach { Hist.confidence(c.confidence, it.toDouble()); Hist.confidence(added.confidence, it.toDouble()) }
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

    /**
     * App-reported (SPEC 6.3): the learner recited ayahs [ayahFrom]..[ayahTo] of [surah] in a take that counts. The
     * caller has validated the range and decided that this report is the take's one.
     */
    fun recited(surah: Int, ayahFrom: Int, ayahTo: Int) = rec { a ->
        if (surah !in 1..114 || ayahFrom < 1 || ayahTo < ayahFrom) return@rec
        val n = ayahTo - ayahFrom + 1
        a.ayahs += n
        a.coverage[surah] += n
        persist(force = true)
    }

    /**
     * App-reported (SPEC 6.3): the app's own judge checked [checked] places for [rule] and flagged [flagged] of them.
     * The caller has validated the input. A call with nothing checked counts nothing and takes no place among the
     * day's rules. At most [MAX_RULES] distinct rules a UTC day (kept with the day, across its deltas): a later one
     * is dropped (returns false). The rule `word` also counts `words`.
     */
    fun checks(rule: String, checked: Int, flagged: Int): Boolean {
        var kept = false
        rec { a ->
            if (!RULE_KEY.matches(rule) || checked <= 0 || flagged < 0 || flagged > checked) return@rec
            if (rule !in a.dayRules && a.dayRules.size >= MAX_RULES) return@rec
            a.dayRules.add(rule)
            a.ruleChecks[rule] = (a.ruleChecks[rule] ?: 0) + checked
            if (flagged > 0) a.ruleFlags[rule] = (a.ruleFlags[rule] ?: 0) + flagged
            if (rule == "word") a.words += checked
            kept = true
            persist(force = true)
        }
        return kept
    }

    /** A promotion attempt ended (or a crash at a session's first accelerator call was found at launch). */
    fun promotion(e: ai.quranlab.tajweedformer.PromotionEvent) = rec { a ->
        val p = a.promotion(PromoKey(e.backend, ai.quranlab.tajweedformer.ModelIds.version(e.revision), e.context, ai.quranlab.tajweedformer.ModelIds.build(e.revision)))
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
        val p = a.promotion(PromoKey(e.backend, ai.quranlab.tajweedformer.ModelIds.version(e.revision), e.context, ai.quranlab.tajweedformer.ModelIds.build(e.revision)))
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
        a.promotion(PromoKey(e.backend, ai.quranlab.tajweedformer.ModelIds.version(e.revision), e.context, ai.quranlab.tajweedformer.ModelIds.build(e.revision))).blankConfirmedOk++
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
        if (e.withoutAudio) {
            // a take without audio is not a session (SPEC 6.1, contract 1.13.0): no `sessions`, no `aborted`, no
            // `minutes`, and what its chunks added (an engine gives a last chunk also for a take that got no sample:
            // `chunks`, the latency, the cold start, the margins) is taken back. The cohort its start made, when nothing
            // else is counted in it, goes again; the launch's cold start is still to be measured
            a.cohorts[k]?.let { c ->
                c.takeBack(added)
                if (c.isBlank()) a.cohorts.remove(k)
            }
            if (added.coldStart.isNotEmpty()) coldStartPending = true
            added = Cohort()
            session = null
            return@rec
        }
        val c = a.cohort(k)
        c.sessions++
        c.minutes += e.audioSeconds / 60
        if (!e.completed) c.aborted++
        if (sessionThrottled) c.throttledSessions++
        // SPEC 6.2: what the SDK measured of the take, from the model's own outputs. Only a take that ended with its
        // Heads counts: a cancelled or failed one adds nothing here, so takes = sessions - aborted.
        if (e.completed) {
            c.takes++
            c.audioMs += e.audioMs
            c.speechMs += 40L * e.speechFrames
            c.silenceMs += 40L * e.silenceFrames
            c.computeUs += e.computeUs
            for ((h, n) in e.tokens) if (n > 0) c.tokens[h] = (c.tokens[h] ?: 0) + n
        }
        session = null
        persist(force = true)
    }

    /**
     * Runs [body] on today's sums, under the lock. Telemetry never disturbs recognition: whatever it throws is dropped.
     *
     * A plain function, never `inline`: the copy Kotlin keeps of an inline function's own body puts a call between the
     * end of the lock's catch-all range and its `monitorexit`, inside this `try`. Android's verifier rejects a class
     * with such a method (`VerifyError: ... expected to be within a catch-all for an instruction where a monitor is
     * held`), and the app dies when the Client is made. SDK 2.0.0 to 2.0.2 shipped it. scripts/check_monitor_handlers.py
     * fails the build on any method of that shape.
     */
    private fun rec(body: (DayAggregate) -> Unit) {
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

    /** Today's counts as the body a send point would make of them, without sealing anything (the vector tests read it). */
    @Suppress("UNCHECKED_CAST")
    fun peek(ctx: TelemetryContext): Map<String, Any?>? = synchronized(lock) {
        val d = day ?: return null
        val a = agg ?: return null
        TelemetryPayload.build(d, a, ctx, "0".repeat(32))?.let { Json.parse(String(it, Charsets.UTF_8)) as Map<String, Any?> }
    }

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
                        // the open session goes on counting into a fresh aggregate (its cold start still pending); the
                        // day's rules stay with the day, across its deltas
                        val pending = session?.let { agg?.cohorts?.get(it)?.coldStartPending } == true
                        val rules = agg?.dayRules
                        agg = DayAggregate()
                        if (rules != null) agg!!.dayRules.addAll(rules)
                        // the fresh aggregate must reach the disk with the day's rules, or a restart forgets them
                        if (rules != null && rules.isNotEmpty()) { dirty = true; persistNow() }
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

        val FALLBACKS = TajweedObserver.FallbackReason.values().map { it.key }
        /** The head names a cohort's `tokens` may carry (the CTC heads). */
        val TOKEN_HEADS = ai.quranlab.tajweedformer.HeadId.CTC_HEADS.map { it.key }
    }
}

/** What the payload says about the install, besides the day's sums. */
internal data class TelemetryContext(val app: AppIdentity, val device: DeviceProfile, val product: String = "android")

/** One cohort: a model version ([revision], the wire's name) and build, a context, a backend. */
internal data class CohortKey(val revision: String, val context: String, val backend: String, val build: String = "")

internal data class DownloadKey(val revision: String, val profile: String, val build: String = "")

internal data class PromoKey(val backend: String, val revision: String, val context: String, val build: String = "")

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
        if (k.build.isNotEmpty()) o["build"] = k.build
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
        return PromoKey(o["backend"] as String, o["revision"] as String, o["context"] as String, o["build"] as? String ?: "") to c
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
    val margin = LinkedHashMap<String, Int>()
    val confidence = LinkedHashMap<String, Int>()
    // SPEC 6.2 (SDK 3.0)
    var takes = 0
    var audioMs = 0L
    var speechMs = 0L
    var silenceMs = 0L
    var computeUs = 0L
    val tokens = LinkedHashMap<String, Int>()
    @Transient var coldStartPending = false

    /** What the chunks of a take added ([o]: its `chunks` and the figures of each chunk) is taken out again. */
    fun takeBack(o: Cohort) {
        chunks = maxOf(0, chunks - o.chunks)
        for ((dst, src) in listOf(latency to o.latency, rtf to o.rtf, coldStart to o.coldStart, margin to o.margin, confidence to o.confidence)) {
            for ((k, v) in src) {
                val left = (dst[k] ?: 0) - v
                if (left > 0) dst[k] = left else dst.remove(k)
            }
        }
    }

    /** Nothing is counted in it. */
    fun isBlank(): Boolean = sessions == 0 && minutes == 0.0 && chunks == 0 && aborted == 0 && fallbacks.isEmpty() &&
        selfTestPass == 0 && selfTestFail == 0 && latency.isEmpty() && rtf.isEmpty() && coldStart.isEmpty() && modelLoad.isEmpty() &&
        thermalTransitions == 0 && throttledSessions == 0 && margin.isEmpty() && confidence.isEmpty() && takes == 0 &&
        audioMs == 0L && speechMs == 0L && silenceMs == 0L && computeUs == 0L && tokens.isEmpty()

    fun merge(o: Cohort) {
        sessions += o.sessions; minutes += o.minutes; chunks += o.chunks; aborted += o.aborted
        selfTestPass += o.selfTestPass; selfTestFail += o.selfTestFail
        thermalTransitions += o.thermalTransitions; throttledSessions += o.throttledSessions
        takes += o.takes; audioMs += o.audioMs; speechMs += o.speechMs; silenceMs += o.silenceMs; computeUs += o.computeUs
        for ((dst, src) in listOf(fallbacks to o.fallbacks, latency to o.latency, rtf to o.rtf, coldStart to o.coldStart,
            modelLoad to o.modelLoad, margin to o.margin, confidence to o.confidence, tokens to o.tokens)) {
            for ((k, v) in src) dst[k] = (dst[k] ?: 0) + v
        }
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
    /** The distinct rules the app reported today (at most [Telemetry.MAX_RULES]), kept across the day's deltas. */
    val dayRules = LinkedHashSet<String>()

    fun cohort(k: CohortKey): Cohort = cohorts.getOrPut(k) { Cohort() }

    fun promotion(k: PromoKey): PromoCounts = promotions.getOrPut(k) { PromoCounts() }

    /** Nothing to send (the day's rule names alone are not something to send). */
    fun isEmpty(): Boolean = cohorts.isEmpty() && downloads.isEmpty() && coverage.all { it == 0 } && launches == 0 && ayahs == 0 &&
        words == 0 && ruleChecks.isEmpty() && shareAsked == 0 && shareYes == 0 && shareNo == 0 && promotions.isEmpty()

    /** The on-disk form (the payload's fields plus the cohort keys). */
    fun toStore(): Map<String, Any?> = linkedMapOf(
        "cohorts" to cohorts.map { (k, c) -> TelemetryPayload.cohortJson(k, c, store = true) },
        "coverage" to coverage.withIndex().filter { it.value > 0 }.associate { it.index.toString() to it.value },
        "downloads" to downloads.map { (k, d) -> linkedMapOf("revision" to k.revision, "build" to k.build, "profile" to k.profile, "bytes" to d.bytes,
            "success" to d.success, "failed" to d.failed, "resumed" to d.resumed) },
        "launches" to launches, "ayahs" to ayahs, "words" to words, "rule_checks" to ruleChecks, "rule_flags" to ruleFlags,
        "share" to linkedMapOf("asked" to shareAsked, "yes" to shareYes, "no" to shareNo),
        "promotions" to promotions.map { (k, c) -> Promo.json(k, c, store = true) },
        "day_rules" to dayRules.toList(),
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromStore(v: Any?): DayAggregate {
            val m = v as Map<String, Any?>
            val a = DayAggregate()
            for (x in m["cohorts"] as List<Any?>) {
                val o = x as Map<String, Any?>
                val k = CohortKey(o["revision"] as String, o["context"] as String, o["backend"] as String, o["build"] as? String ?: "")
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
                h("margin", c.margin); h("confidence", c.confidence)
                c.takes = i("takes"); h("tokens", c.tokens)
                fun l(key: String) = (o[key] as? Double)?.toLong() ?: 0L
                c.audioMs = l("audio_ms"); c.speechMs = l("speech_ms"); c.silenceMs = l("silence_ms"); c.computeUs = l("compute_us")
            }
            (m["coverage"] as? Map<String, Any?>)?.forEach { (s, n) -> s.toIntOrNull()?.takeIf { it in 1..114 }?.let { a.coverage[it] = (n as Double).toInt() } }
            (m["downloads"] as? List<Any?>)?.forEach { x ->
                val o = x as Map<String, Any?>
                a.downloads[DownloadKey(o["revision"] as String, o["profile"] as String, o["build"] as? String ?: "")] = DownloadCounts(
                    (o["bytes"] as? Double)?.toLong() ?: 0, (o["success"] as? Double)?.toInt() ?: 0,
                    (o["failed"] as? Double)?.toInt() ?: 0, (o["resumed"] as? Double)?.toInt() ?: 0)
            }
            fun n(v: Any?) = (v as? Double)?.toInt() ?: 0
            a.launches = n(m["launches"]); a.ayahs = n(m["ayahs"]); a.words = n(m["words"])
            (m["rule_checks"] as? Map<String, Any?>)?.forEach { (k, v) -> a.ruleChecks[k] = n(v) }
            (m["rule_flags"] as? Map<String, Any?>)?.forEach { (k, v) -> a.ruleFlags[k] = n(v) }
            (m["share"] as? Map<String, Any?>)?.let { sh -> a.shareAsked = n(sh["asked"]); a.shareYes = n(sh["yes"]); a.shareNo = n(sh["no"]) }
            (m["promotions"] as? List<Any?>)?.forEach { x -> val (k, c) = Promo.fromJson(x as Map<String, Any?>); a.promotions[k] = c }
            (m["day_rules"] as? List<Any?>)?.forEach { r -> (r as? String)?.let { a.dayRules.add(it) } }
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

    val COMPILE = listOf("le_1000", "le_5000", "le_15000", "le_30000", "le_60000", "le_120000", "gt_120000")
    val SPEEDUP = listOf("lt_0_5", "lt_1", "le_1_5", "le_2", "le_3", "le_5", "gt_5")
    val STALL = listOf("le_50", "le_100", "le_200", "le_300", "le_500", "le_1000", "gt_1000")

    fun latency(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(5.0, 10.0, 20.0, 40.0, 80.0, 160.0, 320.0), LATENCY)
    fun rtf(h: MutableMap<String, Int>, r: Double) = bucket(h, r, doubleArrayOf(0.1, 0.25, 0.5, 1.0), RTF)
    fun startup(h: MutableMap<String, Int>, ms: Double) = bucket(h, ms, doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0), STARTUP)
    fun margin(h: MutableMap<String, Int>, m: Double) = bucket(h, m, doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0), MARGIN)
    fun confidence(h: MutableMap<String, Int>, c: Double) = bucket(h, c, doubleArrayOf(0.5, 0.7, 0.8, 0.9, 0.95), CONFIDENCE)

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

}

/** The v2 body: closed schema, zero buckets left out ("missing buckets mean 0"), at most 32 KB. */
internal object TelemetryPayload {
    const val MAX_BYTES = 32 * 1024
    const val MAX_COHORTS = 16
    const val MAX_DOWNLOADS = 8

    private fun hist(h: Map<String, Int>, names: List<String>): Map<String, Int>? =
        names.filter { (h[it] ?: 0) > 0 }.associateWith { minOf(h[it]!!, 10_000_000) }.takeIf { it.isNotEmpty() }

    private fun cap(n: Int) = n.coerceIn(0, 10_000_000)

    /** A cohort as the payload has it; with [store] (the day file) the 6.2 sums keep their whole ms and microseconds. */
    fun cohortJson(k: CohortKey, c: Cohort, store: Boolean = false): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        o["revision"] = k.revision
        if (k.build.isNotEmpty()) o["build"] = k.build
        o["context"] = k.context
        o["backend"] = k.backend
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
        hist(c.margin, Hist.MARGIN)?.let { o["margin"] = it }
        hist(c.confidence, Hist.CONFIDENCE)?.let { o["confidence"] = it }
        // SPEC 6.2: each left out when zero
        if (c.takes > 0) o["takes"] = cap(c.takes)
        if (store) {
            o["audio_ms"] = c.audioMs; o["speech_ms"] = c.speechMs; o["silence_ms"] = c.silenceMs; o["compute_us"] = c.computeUs
        } else {
            // whole ms sent as seconds; the compute time in whole microseconds, rounded to the ms
            if (c.audioMs > 0) o["audio_s"] = c.audioMs / 1000.0
            if (c.speechMs > 0) o["speech_s"] = c.speechMs / 1000.0
            if (c.silenceMs > 0) o["silence_s"] = c.silenceMs / 1000.0
            val computeMs = (c.computeUs + 500) / 1000
            if (computeMs > 0) o["compute_s"] = computeMs / 1000.0
        }
        hist(c.tokens, Telemetry.TOKEN_HEADS)?.let { o["tokens"] = it }
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
        b["sdk_version"] = SdkInfo.VERSION
        b["app_version"] = ctx.app.version
        b["device"] = dev
        b["cohorts"] = kept.map { cohortJson(it.first, it.second) }
        val cov = a.coverage.withIndex().filter { it.index in 1..114 && it.value > 0 }.associate { it.index.toString() to minOf(it.value, 1_000_000) }
        if (cov.isNotEmpty()) b["coverage"] = cov
        if (dlKept.isNotEmpty()) b["downloads"] = dlKept.map { (k, d) ->
            val o = LinkedHashMap<String, Any?>()
            o["revision"] = k.revision
            if (k.build.isNotEmpty()) o["build"] = k.build
            o["profile"] = k.profile
            o["bytes"] = d.bytes.coerceIn(0, 1_000_000_000_000)
            o["success"] = cap(d.success)
            o["failed"] = cap(d.failed)
            o["resumed"] = cap(d.resumed)
            o
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
        // SPEC 6.3: those of the app-reported fields that this payload carries, in this order
        val reported = listOf("ayahs", "surah_ayahs", "coverage", "words", "rule_checks", "rule_flags").filter { b.containsKey(it) }
        if (reported.isNotEmpty()) b["reported"] = reported
        // the accelerator's promotions and failovers: at most 8 entries, the busiest kept
        val promos = a.promotions.entries.sortedByDescending { it.value.attempts + it.value.failovers + it.value.blankConfirmedOk }.take(Promo.MAX)
            .map { (k, c) -> Promo.json(k, c) }.filter { it.size > 3 }
        if (promos.isNotEmpty()) b["promotions"] = promos
        var bytes = Json.write(b).toByteArray(Charsets.UTF_8)
        // over the cap (never seen in practice): the histograms of quality go first, then cohorts
        if (bytes.size > MAX_BYTES) {
            @Suppress("UNCHECKED_CAST")
            val cs = (b["cohorts"] as List<Map<String, Any?>>).map { it.filterKeys { k -> k !in setOf("margin", "confidence") } }
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
