package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.AcceleratorIncident
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedFormer
import java.io.File

/**
 * Issue reports (the platform client doc, section 7): a kind, a code and up to 16 SDK-internal frames
 * (`File.kt:line`), with the day, platform, versions and device class. Never a path or user data; a message only
 * for the accelerator incidents (section 7.1, path-stripped), with their closed `detail` object. At most one report
 * per signature (kind, code, frames) per day per install, except an incident kind's first report for a model
 * revision; queued on disk (`issues.json`, at most 32) until sent.
 */
class IssueQueue(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val queueFile = File(dir, "issues.json")
    private val seenFile = File(dir, "issues-seen.json")
    /** The accelerator incident kinds already reported for a revision ("<kind>|<revision>"). */
    private val firstsFile = File(dir, "issues-firsts.json")
    private val lock = Any()

    /** Queues a report unless one with the same signature was queued today. Returns whether it was. */
    fun add(kind: String, code: String, frames: List<String>, app: AppIdentity, device: DeviceProfile,
            backend: String? = null, revision: String? = null): Boolean = synchronized(lock) {
        if (kind !in KINDS || kind in INCIDENT_KINDS) return false
        val day = Day.of(clock())
        val c = cleanCode(code)
        val sig = "$kind|$c|" + frames.take(8).joinToString(",") { it.replace(Regex("\\d+"), "") }
        val seen = readSeen()
        val today = if (seen.first == day) seen.second.toMutableSet() else mutableSetOf()
        if (sig in today) return false
        today.add(sig)
        write(seenFile, linkedMapOf("day" to day, "sigs" to today.toList()))
        val b = LinkedHashMap<String, Any?>()
        b["day"] = day
        b["platform"] = app.platform
        b["sdk_version"] = TajweedFormer.VERSION
        b["app_version"] = app.version
        b["os_version"] = device.osVersion.take(32)
        b["device_class"] = device.deviceClass
        backend?.let { b["backend"] = it }
        revision?.let { r -> r.filter { it.isLetterOrDigit() || it in "._+@-" }.take(64).takeIf { it.isNotEmpty() }?.let { b["revision"] = it } }
        b["kind"] = kind
        b["code"] = c
        if (frames.isNotEmpty()) b["frames"] = frames.take(16)
        val q = read().toMutableList()
        q.add(b)
        write(queueFile, trim(q))
        true
    }

    /**
     * Queues an accelerator incident (platform client doc, section 7.1): kind `accelerator_failover` or
     * `promotion_failed`, code `<stage>:<cause>`, the revision, the error's path-stripped [message][stripMessage] and the
     * closed `detail` object. The usual dedupe applies (one per signature per day), except that the FIRST report of each
     * kind per model revision on this install is always queued, and kept (never trimmed from the queue) until the
     * server accepted or refused it. Returns whether it was queued.
     */
    fun addIncident(i: AcceleratorIncident, frames: List<String>, app: AppIdentity, device: DeviceProfile): Boolean = synchronized(lock) {
        if (i.kind !in INCIDENT_KINDS) return false
        val day = Day.of(clock())
        val c = cleanCode(i.code)
        val rev = cleanRevision(i.revision) ?: return false
        val pin = "${i.kind}|$rev"
        val firsts = readFirsts()
        val first = pin !in firsts
        val sig = "${i.kind}|$c|" + frames.take(8).joinToString(",") { it.replace(Regex("\\d+"), "") }
        val seen = readSeen()
        val today = if (seen.first == day) seen.second.toMutableSet() else mutableSetOf()
        if (!first && sig in today) return false
        today.add(sig)
        write(seenFile, linkedMapOf("day" to day, "sigs" to today.toList()))
        if (first) write(firstsFile, (firsts + pin).toList())
        val b = LinkedHashMap<String, Any?>()
        b["day"] = day
        b["platform"] = app.platform
        b["sdk_version"] = TajweedFormer.VERSION
        b["app_version"] = app.version
        b["os_version"] = device.osVersion.take(32)
        b["device_class"] = device.deviceClass
        b["revision"] = rev
        b["kind"] = i.kind
        b["code"] = c
        if (frames.isNotEmpty()) b["frames"] = frames.take(16)
        stripMessage(i.message ?: i.error?.message)?.let { b["message"] = it }
        b["detail"] = detail(i)
        if (first) b[PIN] = pin
        val q = read().toMutableList()
        q.add(b)
        write(queueFile, trim(q))
        true
    }

    /** At most 32 queued: the oldest unpinned go first; a pinned first report stays until it is settled. */
    private fun trim(q: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (q.size <= 32) return q
        val out = q.toMutableList()
        var i = 0
        while (out.size > 32 && i < out.size) {
            if (out[i][PIN] == null) out.removeAt(i) else i++
        }
        return out
    }

    /** The first queued report as it is sent (without the queue's own fields). */
    fun firstToSend(): Map<String, Any?>? = synchronized(lock) { read().firstOrNull()?.filterKeys { !it.startsWith("_") } }

    @Suppress("UNCHECKED_CAST")
    private fun readFirsts(): Set<String> = try {
        (Json.parse(firstsFile.readText()) as List<Any?>).map { it as String }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun pending(): List<Map<String, Any?>> = synchronized(lock) { read() }

    /** Removes the first queued report (sent, or refused for good). */
    fun removeFirst() = synchronized(lock) {
        val q = read()
        if (q.isNotEmpty()) write(queueFile, q.drop(1))
    }

    fun clear() = synchronized(lock) { queueFile.delete() }

    @Suppress("UNCHECKED_CAST")
    private fun read(): List<Map<String, Any?>> = try {
        (Json.parse(queueFile.readText()) as List<Any?>).map { it as Map<String, Any?> }
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSeen(): Pair<String?, List<String>> = try {
        val o = Json.parse(seenFile.readText()) as Map<String, Any?>
        (o["day"] as? String) to ((o["sigs"] as? List<Any?>)?.map { it as String } ?: emptyList())
    } catch (_: Exception) {
        null to emptyList()
    }

    private fun write(f: File, v: Any?) {
        try {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(Json.write(v))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** The accelerator incidents (section 7.1). */
        val INCIDENT_KINDS = setOf(AcceleratorIncident.FAILOVER, AcceleratorIncident.PROMOTION_FAILED)

        /** The queue's own field on a pinned first report (never sent). */
        const val PIN = "_pin"

        val STAGES = setOf("download", "compile", "warmup", "golden", "session")
        val CAUSES = setOf("exception", "nan", "out_of_range", "blank", "timeout", "crash_on_last_run", "golden_mismatch", "cpu_mismatch",
            "compile_error", "compile_timeout", "load_error", "verify", "network", "storage", "other")
        val COMPUTE_UNITS = setOf("cpuAndNeuralEngine", "cpuAndGPU", "all", "cpuOnly", "npu", "gpu")
        val THERMAL = setOf("nominal", "fair", "serious", "critical", "none", "light", "moderate", "severe", "emergency", "shutdown")
        private val SOC = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,23}$")

        @JvmStatic
        fun cleanRevision(r: String?): String? = r?.filter { it.isLetterOrDigit() && it.code < 128 || it in "._+@-" }?.take(64)?.takeIf { it.isNotEmpty() }

        /** Keeps the characters [allowed] matches, at most [max]; null when nothing is left. */
        private fun clean(v: String?, max: Int, allowed: (Char) -> Boolean): String? =
            v?.filter { it.code < 128 && allowed(it) }?.take(max)?.takeIf { it.isNotEmpty() }

        /**
         * An error's message as an incident may carry it: URLs, paths (anything with a slash or backslash between
         * name characters, a drive letter) and runs of five or more digits replaced by `_`, control characters and
         * anything outside printable ASCII dropped, at most 200 characters. Null when nothing is left.
         */
        @JvmStatic
        fun stripMessage(m: String?): String? {
            if (m == null) return null
            var s = m.replace(Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S*"), "_")
            s = s.replace(Regex("(?:[A-Za-z]:)?(?:[\\\\/]+[^\\\\/\\s:;,'\"()\\[\\]]+)+[\\\\/]?"), "_")
            s = s.replace(Regex("[^\\s:;,'\"()\\[\\]]*[\\\\/][^\\s:;,'\"()\\[\\]]*"), "_")
            s = s.replace(Regex("\\d{5,}"), "_")
            s = s.filter { it.code in 0x20..0x7e }.trim()
            return s.take(200).takeIf { it.isNotEmpty() }
        }

        /** The closed `detail` object of section 7.1: every value checked against its type and range, absent when unknown. */
        @JvmStatic
        fun detail(i: AcceleratorIncident): Map<String, Any?> {
            val d = LinkedHashMap<String, Any?>()
            d["stage"] = if (i.stage in STAGES) i.stage else "session"
            d["cause"] = if (i.cause in CAUSES) i.cause else "other"
            val e = i.error
            clean(e?.javaClass?.simpleName, 64) { it.isLetterOrDigit() || it in "_.:-" }?.let { d["error_domain"] = it }
            clean(i.errorCode, 32) { it.isLetterOrDigit() || it in "_.:-" }?.let { d["error_code"] = it }
            i.chunk?.let { d["chunk"] = it.coerceIn(0, 1_000_000) }
            fun ms(v: Double?) = v?.takeIf { it.isFinite() }?.let { Math.round(it.coerceIn(0.0, 600_000.0) * 10) / 10.0 }
            ms(i.elapsedMs)?.let { d["elapsed_ms"] = it }
            ms(i.deadlineMs)?.let { d["deadline_ms"] = it }
            clean(i.clip, 32) { it.isLetterOrDigit() || it in "_.-" }?.let { d["clip"] = it }
            i.divergence?.let { v ->
                fun n(x: Int) = x.coerceIn(0, 100_000)
                d["divergence"] = linkedMapOf("tokens_expected" to n(v.tokensExpected), "tokens_differ" to n(v.tokensDiffer),
                    "sites_differ" to n(v.sitesDiffer), "words_differ" to n(v.wordsDiffer))
            }
            clean(i.deviceModel?.replace(' ', '_'), 32) { it.isLetterOrDigit() || it in "_.,-" }?.let { d["device_model"] = it }
            clean(i.osBuild?.replace(' ', '_'), 64) { it.isLetterOrDigit() || it in "_.:/-" }?.let { d["os_build"] = it }
            i.chip?.takeIf { SOC.matches(it) }?.let { d["chip"] = it }
            i.accelerator.takeIf { it == "ane" || it == "gpu" || (it.startsWith("npu:") && SOC.matches(it.substring(4))) }?.let { d["accelerator"] = it }
            i.computeUnits?.takeIf { it in COMPUTE_UNITS }?.let { d["compute_units"] = it }
            clean(i.delegate, 48) { it.isLetterOrDigit() || it in "_.:/ -" }?.let { d["delegate"] = it }
            i.context.takeIf { SOC.matches(it) }?.let { d["context"] = it }
            i.thermal?.takeIf { it in THERMAL }?.let { d["thermal"] = it }
            i.lowPower?.let { d["low_power"] = it }
            i.stallMs?.takeIf { it.isFinite() }?.let { d["stall_ms"] = Math.round(it).toInt().coerceIn(0, 600_000) }
            i.attempt?.let { d["attempt"] = it.coerceIn(1, 100) }
            return d
        }

        val KINDS = setOf("engine_failed", "model_load", "checksum_mismatch", "signature_invalid", "update_failed", "fallback",
            "self_test_failed", "audio_input", "other") + INCIDENT_KINDS

        @JvmStatic
        fun cleanCode(code: String): String =
            code.filter { it.code < 128 && (it.isLetterOrDigit() || it in "_.:-") }.take(64).ifEmpty { "other" }

        /** The SDK's own frames of [t] as `File.kt:line`: nothing of the app, no paths. */
        @JvmStatic
        fun frames(t: Throwable?): List<String> {
            val out = ArrayList<String>()
            var e = t
            var depth = 0
            while (e != null && depth < 4 && out.size < 16) {
                for (f in e.stackTrace) {
                    if (!f.className.startsWith("ai.quranlab.tajweedformer")) continue
                    val file = f.fileName ?: continue
                    if (!Regex("^[A-Za-z0-9_]+\\.(kt|java)$").matches(file)) continue
                    out.add("$file:${maxOf(0, f.lineNumber)}")
                    if (out.size == 16) break
                }
                e = e.cause
                depth++
            }
            return out
        }
    }
}
