package ai.quranlab.tajweedformer.sharing

/**
 * The adaptive Opus bitrate of shared sessions (SDK 2.0.1), the same in every SDK and replayed against the
 * shared vector `bitrate_ladder.json`. One ladder per sharing instance, in memory (a new process starts at
 * the top), shared by the captures and the outbox, which run on different threads.
 *
 * - [uploadFailed]: an upload request (session start, more URLs, a segment PUT) failed for network
 *   reasons (no connection, a timeout, HTTP 408 or 5xx): one rung down, the success streak back to 0.
 *   Refusals (403 on a PUT, other 4xx, volume limits) and waits (sharing not allowed, unmetered only) are not.
 * - [uploadOk]: a segment PUT answered 2xx, with the seconds of closed audio still waiting after it: the
 *   streak grows (capped at 3); at 3 with at most 30 s waiting and not at the top, one rung up, streak 0.
 * - [segmentClosed]: a capture closed a segment, with the seconds of closed audio not yet uploaded (every
 *   session in the outbox, this one included): at 120 s or more, one rung down, streak 0.
 *
 * The encoder takes [bitrate] when a segment opens, so each segment has exactly one bitrate (recorded in
 * its marker). Tried and rejected: changing mid-segment (a segment would have no single bitrate to record);
 * keeping the rung across launches (a stale bad network would cost quality on a good one); a floor below
 * 32 kbit/s (the log-mel features the model reads differ by 1.17 dB at 32k, 1.60 at 24k, 2.48 at 16k, against
 * 0.22 at 192k, for about 0.07 MB a minute saved per step).
 */
class BitrateLadder {
    private var rung = 0
    private var streak = 0

    /** The bit rate the next segment is encoded at. */
    val bitrate: Int @Synchronized get() = RUNGS[rung]

    @Synchronized
    fun uploadFailed() {
        rung = minOf(rung + 1, RUNGS.size - 1)
        streak = 0
    }

    @Synchronized
    fun uploadOk(backlogS: Double) {
        streak = minOf(streak + 1, UP_STREAK)
        if (streak >= UP_STREAK && backlogS <= UP_MAX_BACKLOG_S && rung > 0) {
            rung--
            streak = 0
        }
    }

    @Synchronized
    fun segmentClosed(backlogS: Double) {
        if (backlogS >= DOWN_BACKLOG_S) {
            rung = minOf(rung + 1, RUNGS.size - 1)
            streak = 0
        }
    }

    companion object {
        /** bit/s, from the top. */
        @JvmField val RUNGS = intArrayOf(192_000, 96_000, 64_000, 32_000)
        const val UP_STREAK = 3
        const val UP_MAX_BACKLOG_S = 30.0
        const val DOWN_BACKLOG_S = 120.0
    }
}
