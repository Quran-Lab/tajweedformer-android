package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.util.Base64

/**
 * Contract 28.11 items 2 and 3 (SDK 4.0.2): every route of the API honours a `Retry-After`, the essential ones
 * included, and the holds outlive the process. After a `429`, or a `503` that carries one (1 to 21600 s), no request of
 * that route goes out before the hold ends, whatever the route's own schedule: a call that meets a held route gets that
 * route's last answer again, without a request ([QuranLabApi]). Beside each hold, the rows of 429s of the optional
 * traffic ([SlowDown]) are kept here too, so a new process waits as the old one would have.
 *
 * Kept in `<storage>/cloud/holds.json` (null [file]: in memory only). A hold whose end is more than 21600 s ahead of
 * the clock when it is read (a clock that moved back) is read as ending now.
 */
internal class RouteHolds(private val file: File?, private val clock: () -> Long) {
    /** One route's state: no request before [untilMs]; the answer that held it; the 429s in a row of [SlowDown]. */
    data class Hold(val untilMs: Long = 0, val status: Int = 0, val headers: Map<String, String> = emptyMap(),
                    val body: ByteArray = ByteArray(0), val row: Int = 0, val notBeforeMs: Long = 0)

    private val lock = Any()
    private val holds = LinkedHashMap<String, Hold>()

    init {
        load()
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        val f = file ?: return
        val o = try { Json.parse(f.readText()) as? Map<String, Any?> } catch (_: Exception) { null } ?: return
        val now = clock()
        fun at(v: Any?): Long = ((v as? Number)?.toLong() ?: 0L).let { if (it > now + MAX_AHEAD_MS) now else it }
        for ((route, v) in o) {
            val h = v as? Map<String, Any?> ?: continue
            holds[route] = Hold(at(h["until"]), (h["status"] as? Number)?.toInt() ?: 0,
                (h["headers"] as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap(),
                try { Base64.getDecoder().decode(h["body"] as? String ?: "") } catch (_: Exception) { ByteArray(0) },
                (h["row"] as? Number)?.toInt() ?: 0, at(h["not_before"]))
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val o = LinkedHashMap<String, Any?>()
            for ((route, h) in holds) {
                o[route] = linkedMapOf("until" to h.untilMs, "status" to h.status, "headers" to h.headers,
                    "body" to Base64.getEncoder().encodeToString(h.body), "row" to h.row, "not_before" to h.notBeforeMs)
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Json.write(o))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
        }
    }

    fun get(route: String): Hold = synchronized(lock) { holds[route] ?: Hold() }

    fun now(): Long = clock()

    /** The answer [route] is held with now, or null when it may be asked. */
    fun held(route: String): Hold? = synchronized(lock) { holds[route]?.takeIf { clock() < it.untilMs && it.status != 0 } }

    /** [route]'s answer was [r]: a 429, or a 503 with a `Retry-After`, holds the route until it ends. */
    fun answered(route: String, r: HttpResponse) {
        val ra = r.header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_RETRY_AFTER_S } ?: r.retryAfterMax?.toLong()
        if (ra == null || !(r.status == 429 || r.status == 503)) return
        // the headers that matter to the caller of a held route (its JSON error and its Retry-After)
        put(route, r.status, ra, r.headers.filterKeys { it.equals("content-type", ignoreCase = true) }, r.body)
    }

    /**
     * A model file's answer on one host (28.11 item 2: [key] is `models <host><path>`): a 429, or a 503, with a
     * [retryAfter] of 1 to 21600 s holds that path on that host until it ends. Ended holds of model files are dropped.
     */
    fun hold(key: String, status: Int, retryAfter: String?, headers: Map<String, String>, body: ByteArray) {
        val ra = retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_RETRY_AFTER_S } ?: return
        if (status != 429 && status != 503) return
        put(key, status, ra, headers, body)
    }

    private fun put(route: String, status: Int, ra: Long, headers: Map<String, String>, body: ByteArray) = synchronized(lock) {
        val now = clock()
        holds.entries.removeIf { (k, h) -> k.startsWith(MODELS_PREFIX) && h.untilMs <= now && k != route }
        val h = holds[route] ?: Hold()
        holds[route] = h.copy(untilMs = maxOf(h.untilMs, now + ra * 1000), status = status, headers = headers, body = body)
        save()
    }

    /** [route]'s 429 row of [SlowDown] as it stands now. */
    fun row(route: String, row: Int, notBeforeMs: Long) = synchronized(lock) {
        holds[route] = (holds[route] ?: Hold()).copy(row = row, notBeforeMs = notBeforeMs)
        save()
    }

    companion object {
        const val MAX_RETRY_AFTER_S = 21_600L
        const val MAX_AHEAD_MS = MAX_RETRY_AFTER_S * 1000

        /** The holds of model files (28.11 item 2): `models <host><path>`. */
        const val MODELS_PREFIX = "models "

        fun modelKey(host: String, path: String): String = "$MODELS_PREFIX$host$path"

        /**
         * The route of an API path (28.11 item 2): `config`, `register`, `attest_challenge`, `attest`, `attest_assert`,
         * `model_key`, `events`, `issues`, `sharing` (the uploads and their URLs and take lists, the contributions); null
         * for any other.
         */
        fun routeOf(path: String): String? {
            val p = path.substringBefore('?')
            return when {
                p.startsWith("/v1/config") -> "config"
                p.startsWith("/v1/register") -> "register"
                p.startsWith("/v1/attest/challenge") -> "attest_challenge"
                p.startsWith("/v1/attest/assert") -> "attest_assert"
                p.startsWith("/v1/attest") -> "attest"
                p.startsWith("/v1/model-key") -> "model_key"
                p.startsWith("/v1/events") -> "events"
                p.startsWith("/v1/issues") -> "issues"
                p.startsWith("/v1/uploads") || p.startsWith("/v1/contributions") -> "sharing"
                else -> null
            }
        }
    }
}
