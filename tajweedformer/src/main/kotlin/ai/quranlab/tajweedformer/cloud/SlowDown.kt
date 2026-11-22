package ai.quranlab.tajweedformer.cloud

/**
 * Contract 28.10 (SDK 4.0.2, the Ramadan capacity model): one route of the optional traffic (telemetry's
 * `POST /v1/events`, the error and issue reports' `POST /v1/issues`, the shared recordings' uploads) after the API asked
 * it to slow down. The API sheds load on these routes with `429 rate_limited` and a `Retry-After`; a 429 stays on its
 * host (28.3).
 *
 * - After a 429 the route's next request waits the larger of the answer's `Retry-After` (300 s when it has none) and
 *   its own next send point, and the wait doubles at each 429 in a row, up to 3600 s (never shorter than the
 *   `Retry-After` itself, which every route honours: item 1).
 * - A 503 that carries a `Retry-After` holds the route until then, as item 1 says; it is not a 429 and does not grow the
 *   row.
 * - An accepted answer ends the row. Nothing is dropped for it: the queues keep their own limits.
 *
 * 4.0.1 honoured no `Retry-After` on these routes: telemetry waited 1 minute, then 10, then until the app came back to
 * the foreground; the issue and error reports stopped the send point and were sent at the next one (another delivery a
 * minute later while the app was in use); an upload retried after the outbox's backoff, 30 s doubling with jitter.
 */
internal class SlowDown(
    private val clock: () -> Long,
    /** Where the row and its wait are kept across restarts (28.11 item 3), under [route]; null: in memory only. */
    private val holds: RouteHolds? = null,
    private val route: String = "",
) {
    /** 429s in a row on the route. */
    @Volatile var row = holds?.get(route)?.row ?: 0
        private set

    /** No request of the route before this (ms of [clock]). */
    @Volatile var notBeforeMs = holds?.get(route)?.notBeforeMs ?: 0L
        private set

    private fun keep() {
        try { holds?.row(route, row, notBeforeMs) } catch (_: Exception) {}
    }

    /** Whether the route may send now. */
    fun allowed(): Boolean = clock() >= notBeforeMs

    /** A 429 with [retryAfterS] (1 to 21600 s; null or anything else: none). Returns the wait in ms. */
    @Synchronized
    fun slowDown(retryAfterS: Int?): Long {
        row++
        val ra = retryAfterS?.takeIf { it in 1..MAX_RETRY_AFTER_S }?.toLong()
        val doubled = minOf(MAX_S, (ra ?: DEFAULT_S) shl minOf(row - 1, 12))
        val waitMs = maxOf(ra ?: 0L, doubled) * 1000
        notBeforeMs = maxOf(notBeforeMs, clock() + waitMs)
        keep()
        return waitMs
    }

    /** Any other answer that carries a `Retry-After` (a 503): no request before it. */
    @Synchronized
    fun honour(retryAfterS: Int?) {
        val ra = retryAfterS?.takeIf { it in 1..MAX_RETRY_AFTER_S } ?: return
        notBeforeMs = maxOf(notBeforeMs, clock() + ra * 1000L)
        keep()
    }

    /** An accepted answer: the row ends. */
    @Synchronized
    fun ok() {
        if (row == 0) return
        row = 0
        keep()
    }

    /** What an answer does to the route: a 429 grows the row, a 503 with a `Retry-After` holds it, a 2xx ends the row.
     *  Returns the wait a 429 set, else 0. */
    fun answered(status: Int, retryAfterS: Int?): Long = when {
        status == 429 -> slowDown(retryAfterS)
        status in 200..299 -> { ok(); 0 }
        else -> { honour(retryAfterS); 0 }
    }

    companion object {
        const val DEFAULT_S = 300L
        const val MAX_S = 3600L
        const val MAX_RETRY_AFTER_S = 21_600
    }
}
