package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedFormer
import java.io.File

/**
 * Issue reports (PLATFORM_CLIENT.md section 7): a kind, a code and up to 16 SDK-internal frames
 * (`File.kt:line`), with the day, platform, versions and device class. Never a message, a path or
 * user data. At most one report per signature (kind, code, frames) per day per install; queued on
 * disk (`issues.json`, at most 32) until sent.
 */
class IssueQueue(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val queueFile = File(dir, "issues.json")
    private val seenFile = File(dir, "issues-seen.json")
    private val lock = Any()

    /** Queues a report unless one with the same signature was queued today. Returns whether it was. */
    fun add(kind: String, code: String, frames: List<String>, app: AppIdentity, device: DeviceProfile,
            backend: String? = null, revision: String? = null): Boolean = synchronized(lock) {
        if (kind !in KINDS) return false
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
        write(queueFile, q.takeLast(32))
        true
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
        val KINDS = setOf("engine_failed", "model_load", "checksum_mismatch", "signature_invalid", "update_failed", "fallback",
            "self_test_failed", "audio_input", "other")

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
