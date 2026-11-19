package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecitedOutcome
import ai.quranlab.tajweedformer.RecitedReport
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The SDK 4.0 counters of one UTC day (SPEC 17.1, 17.8, 17.9; scratch/sdk3/brief/SDK_32_CONTRACT_DRAFT.md section D):
 * the measured ayahs' juz, the app's fallback counts, speech by local hour, take and session lengths, the learner's
 * days, repeats, the recited takes by source, the lease's health, and the app's own judgement. Plain counts: no
 * identifier, no ayah number, no time finer than the hour bucket. Kept with the day's other sums ([DayAggregate]).
 */
internal class Measured40 {
    var appAyahs = 0
    val appSurah = IntArray(115)
    val juz = IntArray(31)
    val hourSpeechMs = LongArray(24)
    val takeS = LinkedHashMap<String, Int>()
    val sessionS = LinkedHashMap<String, Int>()
    var repeatAyahs = 0
    val recitedTakes = LinkedHashMap<String, Int>()
    var leaseOk = 0
    val leaseFailed = LinkedHashMap<String, Int>()
    /** The learner facts of the day (17.8), sent once: set at the day's first take with audio. */
    var learner: Map<String, Int>? = null
    var learnerGap: String? = null
    var streak: String? = null
    val judged = LinkedHashMap<String, Int>()
    val judgedSurah = IntArray(115)
    val correctSurah = IntArray(115)
    val tenure = LinkedHashMap<String, IntArray>()
    var trend: String? = null

    // kept across the day's deltas (never sent): the takes this day counted, and that its learner facts went
    val recitedIds = LinkedHashSet<Long>()
    var learnerDone = false

    fun isEmpty(): Boolean = appAyahs == 0 && juz.all { it == 0 } && hourSpeechMs.all { it == 0L } && takeS.isEmpty() && sessionS.isEmpty() &&
        repeatAyahs == 0 && recitedTakes.isEmpty() && leaseOk == 0 && leaseFailed.isEmpty() && learner == null && judged.isEmpty() && trend == null

    /** A fresh aggregate of the same day keeps what must not be counted again. */
    fun carryInto(next: Measured40) {
        next.recitedIds.addAll(recitedIds)
        next.learnerDone = learnerDone
    }

    private fun inc(h: MutableMap<String, Int>, k: String, n: Int = 1) { h[k] = (h[k] ?: 0) + n }

    fun takeLength(seconds: Double) = inc(takeS, bucket(seconds, TAKE_EDGES, TAKE_S))
    fun sessionLength(seconds: Double) = inc(sessionS, bucket(seconds, SESSION_EDGES, SESSION_S))
    fun recitedTake(kind: String) = inc(recitedTakes, kind)
    fun lease(failed: String?) = if (failed == null) leaseOk++ else inc(leaseFailed, failed.takeIf { it in LEASE_FAILED } ?: "other")

    /** The app's judgement of one ayah (17.9): [first] when the ayah was not judged before in this session. */
    fun judge(surah: Int, o: RecitedOutcome, first: Boolean, measured: Boolean, tenureBucket: String?) {
        inc(judged, "ayahs")
        inc(judged, if (o.correct) "correct" else "incorrect")
        o.mistakes?.let { inc(judged, "mistakes", it) }
        inc(judged, if (first) "first" else "repeat")
        if (o.correct) inc(judged, if (first) "first_correct" else "repeat_correct")
        if (measured) inc(judged, "measured")
        if (surah in 1..114) {
            judgedSurah[surah]++
            if (o.correct) correctSurah[surah]++
        }
        tenureBucket?.let { b -> tenure.getOrPut(b) { IntArray(2) }.let { it[0]++; if (o.correct) it[1]++ } }
    }

    fun unpaired(n: Int) { if (n > 0) inc(judged, "unpaired", n) }

    fun toStore(): Map<String, Any?> = linkedMapOf(
        "app_ayahs" to appAyahs, "app_surah" to sparse(appSurah), "juz" to sparse(juz),
        "hour_speech_ms" to hourSpeechMs.withIndex().filter { it.value > 0 }.associate { it.index.toString() to it.value },
        "take_s" to takeS, "session_s" to sessionS, "repeat_ayahs" to repeatAyahs, "recited_takes" to recitedTakes,
        "lease_ok" to leaseOk, "lease_failed" to leaseFailed, "learner" to learner, "learner_gap" to learnerGap, "streak" to streak,
        "judged" to judged, "judged_surah" to sparse(judgedSurah), "correct_surah" to sparse(correctSurah),
        "tenure" to tenure.mapValues { listOf(it.value[0], it.value[1]) }, "trend" to trend,
        "recited_ids" to recitedIds.toList(), "learner_done" to learnerDone,
    )

    @Suppress("UNCHECKED_CAST")
    fun fromStore(m: Map<String, Any?>) {
        fun n(v: Any?) = (v as? Double)?.toInt() ?: 0
        fun arr(k: String, a: IntArray) = (m[k] as? Map<String, Any?>)?.forEach { (i, v) -> i.toIntOrNull()?.takeIf { it in a.indices }?.let { a[it] = n(v) } }
        fun map(k: String, d: MutableMap<String, Int>) = (m[k] as? Map<String, Any?>)?.forEach { (i, v) -> d[i] = n(v) }
        appAyahs = n(m["app_ayahs"])
        arr("app_surah", appSurah); arr("juz", juz); arr("judged_surah", judgedSurah); arr("correct_surah", correctSurah)
        (m["hour_speech_ms"] as? Map<String, Any?>)?.forEach { (i, v) -> i.toIntOrNull()?.takeIf { it in 0..23 }?.let { hourSpeechMs[it] = (v as? Double)?.toLong() ?: 0 } }
        map("take_s", takeS); map("session_s", sessionS); map("recited_takes", recitedTakes); map("lease_failed", leaseFailed); map("judged", judged)
        repeatAyahs = n(m["repeat_ayahs"]); leaseOk = n(m["lease_ok"])
        learner = (m["learner"] as? Map<String, Any?>)?.mapValues { n(it.value) }
        learnerGap = m["learner_gap"] as? String; streak = m["streak"] as? String; trend = m["trend"] as? String
        (m["tenure"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? List<*>)?.let { l -> tenure[k] = intArrayOf(n(l.getOrNull(0)), n(l.getOrNull(1))) } }
        (m["recited_ids"] as? List<*>)?.forEach { (it as? Double)?.toLong()?.let { id -> recitedIds.add(id) } }
        learnerDone = m["learner_done"] == true
    }

    /** The app's fallback counts (17.1): `app_ayahs`, `app_surah_ayahs`, listed in [reported] first. */
    fun putApp(b: MutableMap<String, Any?>, reported: MutableList<String>) {
        if (appAyahs > 0) { b["app_ayahs"] = appAyahs.coerceIn(0, 10_000_000); reported += "app_ayahs" }
        sparse(appSurah, 1..114).takeIf { it.isNotEmpty() }?.let { b["app_surah_ayahs"] = it; reported += "app_surah_ayahs" }
    }

    /**
     * The fields of 17.8 and 17.9 into the body [b] (each left out when empty, and each group whose switch [on] says
     * off), and the app-reported ones into [reported]: the judgement is the app's (17.9, always listed); the rest is
     * the SDK's (never listed). Names, keys and bounds as qlab-api's EVENTS4 and sdk4Problem take them.
     */
    fun putInto(b: MutableMap<String, Any?>, reported: MutableList<String>, on: (String) -> Boolean = { true }) {
        fun cap(x: Int) = x.coerceIn(0, 10_000_000)
        if (on("juz")) sparse(juz, 1..30).takeIf { it.isNotEmpty() }?.let { b["juz_ayahs"] = it }
        val hours = hourSpeechMs.withIndex().filter { it.value > 0 }.associate { it.index.toString() to minOf(it.value / 1000.0, 86_400.0) }
        if (hours.isNotEmpty() && on("hours")) b["hour_speech_s"] = hours
        if (on("lengths")) {
            hist(takeS, TAKE_S)?.let { b["take_s"] = it }
            hist(sessionS, SESSION_S)?.let { b["session_s"] = it }
        }
        if (on("learner")) learner?.let { l ->
            b["learner"] = l
            learnerGap?.let { b["learner_gap"] = mapOf(it to 1) }
            streak?.let { b["streak"] = mapOf(it to 1) }
        }
        if (repeatAyahs > 0 && on("repeats")) b["repeat_ayahs"] = cap(repeatAyahs)
        if (on("health")) {
            hist(recitedTakes, RECITED_TAKES)?.let { b["recited_takes"] = it }
            if (leaseOk > 0 || leaseFailed.isNotEmpty()) {
                val o = LinkedHashMap<String, Any?>()
                if (leaseOk > 0) o["ok"] = cap(leaseOk)
                hist(leaseFailed, LEASE_FAILED)?.let { o["failed"] = it }
                b["lease"] = o
            }
        }
        putJudged(b, reported, on)
    }

    /** 17.9, the app's judgement: always listed in [reported]; off with the `judged` switch. */
    private fun putJudged(b: MutableMap<String, Any?>, reported: MutableList<String>, on: (String) -> Boolean) {
        if (!on("judged")) return
        fun cap(x: Int) = x.coerceIn(0, 10_000_000)
        hist(judged, JUDGED)?.let { b["judged"] = it; reported += "judged" }
        sparse(judgedSurah, 1..114).takeIf { it.isNotEmpty() }?.let { b["judged_surah_ayahs"] = it; reported += "judged_surah_ayahs" }
        sparse(correctSurah, 1..114).takeIf { it.isNotEmpty() }?.let { b["correct_surah_ayahs"] = it; reported += "correct_surah_ayahs" }
        if (tenure.isNotEmpty()) {
            b["judged_tenure"] = TENURE.filter { it in tenure }.associateWith { k -> linkedMapOf("judged" to cap(tenure[k]!![0]), "correct" to cap(tenure[k]!![1])) }
            reported += "judged_tenure"
        }
        trend?.let { b["trend"] = mapOf(it to 1); reported += "trend" }
    }

    companion object {
        val TAKE_S = listOf("le_5", "le_15", "le_30", "le_60", "le_120", "le_300", "le_600", "gt_600")
        private val TAKE_EDGES = doubleArrayOf(5.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
        val SESSION_S = listOf("le_60", "le_300", "le_600", "le_1200", "le_1800", "le_3600", "gt_3600")
        private val SESSION_EDGES = doubleArrayOf(60.0, 300.0, 600.0, 1200.0, 1800.0, 3600.0)
        val RECITED_TAKES = listOf("measured", "context", "none", "app", "recovered", "dropped")
        val LEASE_FAILED = listOf("offline", "refused", "expired", "clock", "attestation", "other")
        val JUDGED = listOf("ayahs", "correct", "incorrect", "mistakes", "first", "first_correct", "repeat", "repeat_correct", "measured", "unpaired")
        val TENURE = listOf("w1", "w2_4", "m2_3", "later")
        val TREND = listOf("better", "same", "worse", "not_enough")

        private fun bucket(v: Double, edges: DoubleArray, names: List<String>): String {
            for (i in edges.indices) if (v <= edges[i]) return names[i]
            return names.last()
        }

        private fun hist(h: Map<String, Int>, names: List<String>): Map<String, Int>? =
            names.filter { (h[it] ?: 0) > 0 }.associateWith { minOf(h[it]!!, 10_000_000) }.takeIf { it.isNotEmpty() }

        private fun sparse(a: IntArray, keys: IntRange = a.indices): Map<String, Int> =
            a.withIndex().filter { it.index in keys && it.value > 0 }.associate { it.index.toString() to minOf(it.value, 10_000_000) }

        /** 17.9's tenure bucket of an install [days] UTC days after its first active day. */
        fun tenureBucket(days: Long): String = when {
            days < 7 -> "w1"
            days < 28 -> "w2_4"
            days < 90 -> "m2_3"
            else -> "later"
        }
    }
}

/** The juz of an ayah (Hafs, the 30 standard starts as Tanzil's quran-data lists them), for `juz_ayahs` (17.8). */
internal object Juz {
    /** (surah, ayah) where juz 1 to 30 start. */
    private val STARTS = arrayOf(
        1 to 1, 2 to 142, 2 to 253, 3 to 93, 4 to 24, 4 to 148, 5 to 82, 6 to 111, 7 to 88, 8 to 41,
        9 to 93, 11 to 6, 12 to 53, 15 to 1, 17 to 1, 18 to 75, 21 to 1, 23 to 1, 25 to 21, 27 to 56,
        29 to 46, 33 to 31, 36 to 28, 39 to 32, 41 to 47, 46 to 1, 51 to 31, 58 to 1, 67 to 1, 78 to 1,
    )

    fun of(surah: Int, ayah: Int): Int {
        var j = 1
        for ((i, s) in STARTS.withIndex()) if (surah > s.first || (surah == s.first && ayah >= s.second)) j = i + 1
        return j
    }
}

/**
 * The learner's own days (17.8), kept on the device only (`<storage>/core/learner.json`): the install's first active
 * UTC day, its active UTC days of the last 31, its active local days for the streak, and five weeks of the app's
 * judgement for the weekly trend (17.9). Never sent: only the facts derived from it go into a day's delta.
 */
internal class LearnerDays(private val file: File) {
    private var first: Long? = null
    private val active = sortedSetOf<Long>()
    private val local = sortedSetOf<Long>()
    /** Monday's epoch day -> (judged, correct). */
    private val weeks = sortedMapOf<Long, IntArray>()
    private var trendWeek: Long? = null

    init {
        try {
            val o = Json.parse(file.readText()) as Map<*, *>
            first = (o["first"] as? Double)?.toLong()
            (o["active"] as? List<*>)?.forEach { (it as? Double)?.toLong()?.let(active::add) }
            (o["local"] as? List<*>)?.forEach { (it as? Double)?.toLong()?.let(local::add) }
            (o["weeks"] as? Map<*, *>)?.forEach { (k, v) ->
                val l = v as? List<*> ?: return@forEach
                (k as? String)?.toLongOrNull()?.let { weeks[it] = intArrayOf((l[0] as Double).toInt(), (l[1] as Double).toInt()) }
            }
            trendWeek = (o["trend_week"] as? Double)?.toLong()
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(linkedMapOf("first" to first, "active" to active.toList(), "local" to local.toList(),
                "weeks" to weeks.entries.associate { it.key.toString() to listOf(it.value[0], it.value[1]) }, "trend_week" to trendWeek)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    /** Days since the first active day (0 on it), or null before any. */
    fun tenureDays(today: Long): Long? = first?.let { today - it }

    fun isActive(utcDay: Long): Boolean = utcDay in active

    /**
     * The facts of an active UTC day (the day's first take with audio): `learner`, `learner_gap`, `streak`. The day
     * is recorded as active (call [save] after the day's aggregate holds the facts).
     */
    fun activate(utcDay: Long, localDay: Long): Triple<Map<String, Int>, String, String> {
        val f = first ?: utcDay
        val prev = active.filter { it < utcDay }.maxOrNull()
        val isNew = first == null || first == utcDay
        if (first == null) first = utcDay
        active.add(utcDay)
        local.add(localDay)
        while (active.size > 32) active.remove(active.first())
        while (local.size > 32) local.remove(local.first())
        val since = utcDay - f
        val learner = linkedMapOf("active" to 1, "new" to if (isNew) 1 else 0, "d1" to if (since == 1L) 1 else 0,
            "d7" to if (since == 7L) 1 else 0, "d30" to if (since == 30L) 1 else 0)
        val gap = when {
            prev == null -> "new"
            utcDay - prev > 30 -> "gt_30"
            else -> (utcDay - prev).toString()
        }
        var run = 0
        var d = localDay
        while (d in local) { run++; d-- }
        val streak = when {
            run >= 30 -> "ge_30"
            run >= 14 -> "14_29"
            run >= 7 -> "7_13"
            run >= 4 -> "4_6"
            run >= 2 -> "2_3"
            else -> "1"
        }
        return Triple(learner, gap, streak)
    }

    /** The app's judgement of [correct] ayahs out of [judged] in the week of [utcDay] (17.9's trend). */
    fun judged(utcDay: Long, judged: Int, correct: Int) {
        val monday = LocalDate.ofEpochDay(utcDay).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
        weeks.getOrPut(monday) { IntArray(2) }.let { it[0] += judged; it[1] += correct }
        while (weeks.size > 6) weeks.remove(weeks.firstKey())
    }

    /**
     * The trend of the last week that ended before [utcDay], once per week (17.9): `better` or `worse` when the
     * correct share moved by 5 points or more against the four weeks before, `same` otherwise, `not_enough` under 20
     * judged ayahs on either side. Null when that week was reported already.
     */
    fun trendDue(utcDay: Long): String? {
        val thisMonday = LocalDate.ofEpochDay(utcDay).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
        val last = thisMonday - 7
        if (trendWeek != null && trendWeek!! >= last) return null
        if (first == null || first!! > last + 6) return null
        trendWeek = last
        val w = weeks[last] ?: IntArray(2)
        var bj = 0
        var bc = 0
        for (k in 1..4) weeks[last - 7L * k]?.let { bj += it[0]; bc += it[1] }
        if (w[0] < 20 || bj < 20) return "not_enough"
        val now = 100.0 * w[1] / w[0]
        val before = 100.0 * bc / bj
        return when {
            now - before >= 5.0 -> "better"
            before - now >= 5.0 -> "worse"
            else -> "same"
        }
    }

    companion object {
        fun localDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

        fun localHour(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int = java.time.Instant.ofEpochMilli(ms).atZone(zone).hour
    }
}

/** A finished take waiting to be counted (17.1's precedence decides at finalise, 17.6). */
internal data class TakeRecord(
    val id: Long,
    /** The UTC epoch day the take started on: its count joins that day, whatever happens later (17.6). */
    val day: Long,
    val report: RecitedReport? = null,
    /** The Core's answer: null while not finished; `found` false when the detector counted nothing. */
    val found: Boolean? = null,
    val surah: Int = 0,
    val first: Int = 0,
    val last: Int = 0,
    val recovered: Boolean = false,
    /** R5: the session context decided the range (`recited_takes.context`). */
    val context: Boolean = false,
    /** R5: equally good ranges besides the one counted (a refrain): such a take counts toward no juz and no repeat. */
    val ties: Int = 0,
) {
    fun json(): Map<String, Any?> = linkedMapOf("id" to id, "day" to day, "report" to report?.json(), "found" to found,
        "surah" to surah, "first" to first, "last" to last, "recovered" to recovered, "context" to context, "ties" to ties)

    /** The Core's result for this take (R5): its range, the day it was counted into, the context flag, the ties. */
    fun withResult(r: ai.quranlab.tajweedformer.core.RecitedResult): TakeRecord =
        copy(found = r.found, surah = r.surah, first = r.first, last = r.last, context = r.context, ties = r.ties,
            day = if (r.found && r.day > 0) r.day else day)

    companion object {
        fun of(v: Any?): TakeRecord? {
            val m = v as? Map<*, *> ?: return null
            return TakeRecord((m["id"] as? Double)?.toLong() ?: return null, (m["day"] as? Double)?.toLong() ?: return null,
                RecitedReport.of(m["report"]), m["found"] as? Boolean, (m["surah"] as? Double)?.toInt() ?: 0,
                (m["first"] as? Double)?.toInt() ?: 0, (m["last"] as? Double)?.toInt() ?: 0, m["recovered"] == true,
                m["context"] == true, (m["ties"] as? Double)?.toInt() ?: 0)
        }
    }
}
