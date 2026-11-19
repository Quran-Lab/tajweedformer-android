package ai.quranlab.tajweedformer.sharing

import java.io.File

/** SDK 4.0's ask (SHARE_ASK_40, section 20) in the tests that need one at a given moment. */
internal object AskKit {
    /**
     * A stored state of an install past its first day ([ShareAsk.HOUR_MS] * 48 before [now]) with [sessions] completed
     * sessions and never asked: the next good moment (a completed take with a minute of speech in this app session)
     * asks.
     */
    fun ready(now: Long, sessions: Int = 2) =
        """{"v":1,"consent":"unknown","asks":0,"lastAskSession":0,"sessions":$sessions,"installedAt":${now - 2 * ShareAsk.DAY_MS},"thanked":false}"""

    /** One "Not now" 15 days and 8 sessions before [now]: the re-ask is due at the next good moment. */
    fun reaskDue(now: Long): String {
        val at = now - 15 * ShareAsk.DAY_MS
        return """{"v":1,"consent":"declined","asks":1,"lastAskSession":1,"sessions":8,"installedAt":${now - 40 * ShareAsk.DAY_MS},""" +
            """"lastAskAt":$at,"lastAskDay":${ShareAsk.localDay(ShareAsk.Now(at, java.util.TimeZone.getDefault().getOffset(at) / 60_000))},"thanked":false,"decidedAt":1}"""
    }

    /** Writes [json] as the consent state of the storage [at] (before its Client starts). */
    fun store(at: File, json: String) {
        File(at, "sharing").mkdirs()
        File(File(at, "sharing"), "state.json").writeText(json)
    }

    /** A minute and a second of speech, in the model's 40 ms frames: one take is a good moment. */
    const val MINUTE_FRAMES = 1525
}
