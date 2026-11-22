package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.HttpResponse
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contract 34.6 (SDK 4.0.2): what this install's model requests measured, per `models` host and UTC day, for Quran
 * Lab's choice of host order. Counts only: the host (a name of the allow-list, 28.1), the files whose last byte came
 * from it (the build's manifest is one), the bytes, the requests (resumes included), those of them that ended
 * unreachable (28.3 item 3), the time from sent to status in [TTFB_MS] buckets, and the rate of each transfer of at
 * least [MIN_TRANSFER] bytes in [KBPS] buckets. No identifier, no address, no path, no time finer than the day.
 * Kept with the telemetry day ([Telemetry]) from the first start, the background job's downloads included, and sent as
 * the daily telemetry's `hosts` while [send] is on.
 */
internal object PerHostCounts {
    /** The one switch of the sending: on since the server takes `hosts` (34.6). Off: counted and kept, never sent. */
    @Volatile @JvmField var send: Boolean = true

    /** At most this many hosts a day; a further host's counts are not kept. */
    const val MAX_HOSTS = 8

    /** `ttfb_ms`: under each edge, and the last bucket for the rest (9 buckets). */
    val TTFB_MS = longArrayOf(100, 250, 500, 1000, 1500, 3000, 6000, 10000)

    /** `kbps`, in kB/s of 1000 bytes: under each edge, and the last bucket for the rest (10 buckets). */
    val KBPS = longArrayOf(32, 64, 128, 256, 512, 1024, 2048, 4096, 8192)

    /** A transfer smaller than this (bytes) has no rate counted. */
    const val MIN_TRANSFER = 256L * 1024

    /** The bucket of [v] among [edges]: the first edge it is under, else the last bucket. */
    fun bucket(v: Double, edges: LongArray): Int {
        for (i in edges.indices) if (v < edges[i]) return i
        return edges.size
    }

    /** The `kbps` bucket of [bytes] read in [nanos] (kB/s of 1000 bytes is bytes per ms). */
    fun rateBucket(bytes: Long, nanos: Long): Int = bucket(bytes * 1_000_000.0 / maxOf(nanos, 1L), KBPS)
}

/** Where the model requests' counts go (34.6): the Client's [Telemetry]. Every call is for a `models` host. */
internal interface ModelHostMeter {
    /** One request sent to [host]; [ttfbMs] from sent to its status, null when it got none. */
    fun request(host: String, ttfbMs: Long?)

    /** One request to [host] ended unreachable (28.3 item 3). */
    fun unreachable(host: String)

    /** One answer's body from [host]: [bytes] read in [nanos] from the status to the last byte. */
    fun transfer(host: String, bytes: Long, nanos: Long)

    /** A file of the build whose last byte came from [host] is in place. */
    fun file(host: String)
}

/**
 * One model request to [host] (an allow-list name): measured for [meter] (34.6), and held by [holds] under [key] (28.11
 * item 2: per file path and host, `models <host><path>`). [lastByte] learns the host of an answer that brought bytes;
 * [sent] learns that the request went out to the host (an answer or none), which a held path's never does (28.7).
 */
internal class HostTap(private val meter: ModelHostMeter?, val host: String, private val lastByte: (String) -> Unit = {},
                       private val holds: RouteHolds? = null, private val key: String? = null, private val sent: () -> Unit = {}) {
    /** The request got no status (no connection, a timeout before the answer, the call's end). */
    fun noAnswer() {
        quiet { sent() }
        quiet { meter?.request(host, null) }
    }

    /** The request got its status [nanos] after it was sent. */
    fun answered(nanos: Long) {
        quiet { sent() }
        quiet { meter?.request(host, nanos / 1_000_000) }
    }

    fun unreachable() = quiet { meter?.unreachable(host) }

    /** The answer this path is held with on this host now, or null when it may be asked. */
    fun held(): RouteHolds.Hold? = key?.let { k -> try { holds?.held(k) } catch (_: Exception) { null } }

    /** [h] again, as the host's answer, without a request: its body, its type and the `Retry-After` left. */
    fun heldAnswer(h: RouteHolds.Hold): HttpResponse {
        val leftS = maxOf(1L, (h.untilMs - (holds?.now() ?: 0L) + 999) / 1000)
        val headers = h.headers.mapKeys { it.key.lowercase() } + ("retry-after" to leftS.toString())
        return HttpResponse(h.status, headers, java.io.ByteArrayInputStream(h.body))
    }

    /** The host's answer [status] (a 429, or a 503 with `Retry-After`: held until it ends). */
    fun hold(status: Int, headers: Map<String, String>, body: ByteArray) {
        val k = key ?: return
        quiet { holds?.hold(k, status, headers["retry-after"], headers.filterKeys { it.equals("content-type", true) }, body) }
    }

    /** [r], whose bytes are counted as they are read; the transfer is counted once, when the answer is closed. */
    fun metered(r: HttpResponse, statusAt: Long): HttpResponse {
        val body = Counted(r.body, statusAt)
        return HttpResponse(r.code, r.headers, body, r.protocol, onAbort = { r.abort() }) {
            try { r.close() } finally { body.report() }
        }
    }

    private inner class Counted(private val inner: InputStream, private val statusAt: Long) : InputStream() {
        @Volatile private var bytes = 0L
        @Volatile private var lastAt = statusAt
        private val reported = AtomicBoolean()

        override fun read(): Int = inner.read().also { if (it >= 0) got(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) got(it) }
        override fun available(): Int = inner.available()

        /** The answer closes the stream ([metered]). */
        override fun close() {}

        private fun got(n: Int) {
            bytes += n
            lastAt = System.nanoTime()
        }

        fun report() {
            if (!reported.compareAndSet(false, true) || bytes <= 0) return
            quiet { meter?.transfer(host, bytes, lastAt - statusAt) }
            quiet { lastByte(host) }
        }
    }

    private inline fun quiet(f: () -> Unit) = try { f() } catch (_: Exception) {}
}

/** One host's counts of a day (34.6). */
internal class HostCounts {
    var files = 0
    var bytes = 0L
    var requests = 0
    var unreachable = 0
    val ttfb = IntArray(PerHostCounts.TTFB_MS.size + 1)
    val kbps = IntArray(PerHostCounts.KBPS.size + 1)

    /** The body's entry: exactly the fields the server takes, `hedged` and `slow_switches` 0 in 4.0.2. */
    fun json(host: String): Map<String, Any?> = linkedMapOf(
        "role" to "models", "host" to host, "files" to cap(files), "bytes" to bytes.coerceIn(0, 1_000_000_000_000),
        "requests" to cap(requests), "unreachable" to cap(minOf(unreachable, requests)), "hedged" to 0, "slow_switches" to 0,
        "ttfb_ms" to ttfb.map { cap(it) }, "kbps" to kbps.map { cap(it) })

    fun toStore(host: String): Map<String, Any?> = linkedMapOf("host" to host, "files" to files, "bytes" to bytes, "requests" to requests,
        "unreachable" to unreachable, "ttfb_ms" to ttfb.toList(), "kbps" to kbps.toList())

    companion object {
        private fun cap(n: Int) = n.coerceIn(0, 1_000_000)

        /** A stored entry, or null when it does not read. */
        fun fromStore(o: Map<*, *>): Pair<String, HostCounts>? {
            val host = o["host"] as? String ?: return null
            val c = HostCounts()
            fun n(k: String) = (o[k] as? Number)?.toLong() ?: 0L
            c.files = n("files").toInt(); c.bytes = n("bytes"); c.requests = n("requests").toInt(); c.unreachable = n("unreachable").toInt()
            (o["ttfb_ms"] as? List<*>)?.forEachIndexed { i, v -> if (i < c.ttfb.size) c.ttfb[i] = (v as? Number)?.toInt() ?: 0 }
            (o["kbps"] as? List<*>)?.forEachIndexed { i, v -> if (i < c.kbps.size) c.kbps[i] = (v as? Number)?.toInt() ?: 0 }
            return host to c
        }
    }
}
