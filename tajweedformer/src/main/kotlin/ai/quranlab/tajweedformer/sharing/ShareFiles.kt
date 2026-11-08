package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The files of one shared session in its outbox folder (`<storage>/outbox/<id>/`), as the Swift
 * SDK lays them out: `session.json` (what POST /v1/uploads gets), `capture.json` (the pre-skip),
 * `seg-NNNNN.opus` and a marker `seg-NNNNN.json` per closed segment, `end.json` once the session
 * ended, and the outbox's own `item.json`.
 */
object ShareFiles {
    const val SESSION = "session.json"
    const val CAPTURE = "capture.json"
    const val END = "end.json"

    fun name(n: Int): String = "seg-%05d".format(n)

    /** A closed segment: size, hash and where the stream stood. */
    data class Marker(val n: Int, val bytes: Long, val sha256: String, val granule: Long)

    data class End(val durationMs: Int, val completed: Boolean)

    fun sha256(f: File): Pair<Long, String>? = try {
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
                n += r
            }
        }
        n to md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Exception) {
        null
    }

    fun writeJson(f: File, v: Any?) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(Json.write(v))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot write ${f.name}") }
    }

    @Suppress("UNCHECKED_CAST")
    fun readJson(f: File): Map<String, Any?>? = try { Json.parse(f.readText()) as? Map<String, Any?> } catch (_: Exception) { null }

    /** Closes segment [n]: its marker, from the file as it is on disk. */
    fun mark(folder: File, n: Int, granule: Long) {
        val (bytes, sha) = sha256(File(folder, name(n) + ".opus")) ?: throw java.io.IOException("no segment $n")
        writeJson(File(folder, name(n) + ".json"), linkedMapOf("n" to n, "bytes" to bytes, "sha256" to sha, "granule" to granule))
    }

    fun markers(folder: File): List<Marker> =
        (folder.listFiles() ?: emptyArray()).filter { it.name.startsWith("seg-") && it.name.endsWith(".json") }.mapNotNull { f ->
            val o = readJson(f) ?: return@mapNotNull null
            val n = (o["n"] as? Double)?.toInt() ?: return@mapNotNull null
            Marker(n, (o["bytes"] as? Double)?.toLong() ?: 0, o["sha256"] as? String ?: "", (o["granule"] as? Double)?.toLong() ?: 0)
        }.sortedBy { it.n }

    fun end(folder: File): End? = readJson(File(folder, END))?.let {
        End((it["durationMs"] as? Double)?.toInt() ?: 0, it["completed"] as? Boolean ?: false)
    }

    fun writeEnd(folder: File, e: End) = writeJson(File(folder, END), linkedMapOf("durationMs" to e.durationMs, "completed" to e.completed))

    /**
     * A session the app was killed in: the open segment is cut back to its last whole page and
     * closed, and the session ended as not completed. False when there is nothing to share (the
     * folder is then removed).
     */
    fun recover(folder: File): Boolean {
        val cap = readJson(File(folder, CAPTURE))
        if (cap == null) { folder.deleteRecursively(); return false }
        val preSkip = (cap["preSkip"] as? Double)?.toLong() ?: 312
        val marked = markers(folder).map { it.n }.toSet()
        val open = (folder.listFiles() ?: emptyArray()).filter { it.name.matches(Regex("^seg-\\d{5,}\\.opus$")) }
            .mapNotNull { it.name.substring(4, it.name.length - 5).toIntOrNull() }.filter { it !in marked }.sorted()
        for (n in open) {
            val audio = File(folder, name(n) + ".opus")
            val d = try { audio.readBytes() } catch (_: Exception) { ByteArray(0) }
            val (keep, granule) = wholePages(d)
            if (keep == 0) { audio.delete(); continue }
            if (keep < d.size) RandomAccessFile(audio, "rw").use { it.setLength(keep.toLong()) }
            try { mark(folder, n, granule) } catch (_: Exception) {}
        }
        val last = markers(folder).lastOrNull()
        if (last == null) { folder.deleteRecursively(); return false }
        writeEnd(folder, End((maxOf(0L, last.granule - preSkip) / 48).toInt(), false))
        return true
    }

    /** The length of the whole pages at the start of [d] (each with a valid CRC), and the last one's granule position. */
    fun wholePages(d: ByteArray): Pair<Int, Long> {
        var at = 0
        var granule = 0L
        while (at + 27 <= d.size && d[at] == 0x4f.toByte() && d[at + 1] == 0x67.toByte() && d[at + 2] == 0x67.toByte() && d[at + 3] == 0x53.toByte()) {
            val nseg = d[at + 26].toInt() and 0xff
            if (at + 27 + nseg > d.size) break
            var len = 0
            for (i in 0 until nseg) len += d[at + 27 + i].toInt() and 0xff
            val end = at + 27 + nseg + len
            if (end > d.size) break
            var crc = 0
            for (i in 0 until 4) crc = crc or ((d[at + 22 + i].toInt() and 0xff) shl (8 * i))
            if (OggOpusWriter.crc(d, at, end) != crc) break
            var g = 0L
            for (i in 0 until 8) g = g or ((d[at + 6 + i].toLong() and 0xff) shl (8 * i))
            granule = g
            at = end
        }
        return at to granule
    }
}
