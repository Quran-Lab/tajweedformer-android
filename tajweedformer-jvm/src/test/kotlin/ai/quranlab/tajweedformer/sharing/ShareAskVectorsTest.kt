package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ProtectedKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SHARE_ASK_40.md (section 20) through the SDK's port ([ShareAsk]): every `config_cases` entry through the config
 * reading, and every case of `vectors/share_ask.json` replayed step by step, each step's `action`, `why` and whole
 * `new_state` (the stored names of 20.4 and this app session's part) as the reference `tools/share_ask.py` gives them.
 */
class ShareAskVectorsTest : ai.quranlab.tajweedformer.OpenChecked() {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> by lazy { ProtectedKit.vector("share_ask.json") }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theConfigCases() {
        val cases = v["config_cases"] as List<Map<String, Any?>>
        assertEquals(21, cases.size)
        for (c in cases) assertEquals(c["name"] as String, norm(c["resolved"]), norm(ShareAsk.resolveConfig(c["raw"]).json()))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun everyCaseStepByStep() {
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(40, cases.size)
        var steps = 0
        for (c in cases) {
            val name = c["name"] as String
            var s = state(c["state"] as Map<String, Any?>)
            for ((i, st) in (c["steps"] as List<Map<String, Any?>>).withIndex()) {
                // stored fields another page or process wrote just before the event: merged into the stored part
                (st["set"] as? Map<String, Any?>)?.let { set -> s = state(json(s) + set).copy(app = s.app) }
                val now = st["now"] as Map<String, Any?>
                val d = ShareAsk.decide(s, event(st["event"] as Map<String, Any?>), c["config"],
                    ShareAsk.Now(ShareAsk.integral(now["ms"])!!, ShareAsk.integral(now["tz_min"])?.toInt() ?: 0))
                val where = "$name, step $i ${Json.write(st["event"])}"
                assertEquals("$where: action", st["action"], d.action)
                assertEquals("$where: why", st["why"], d.why)
                assertEquals("$where: state", norm(st["state"]), norm(json(d.state)))
                s = d.state
                steps++
            }
        }
        assertEquals(338, steps)
    }

    @Test
    fun theDayIsTheDevicesCalendarDay() {
        // floor((ms + tz_min * 60000) / 86400000), also before 1970 and west of UTC
        assertEquals(0L, ShareAsk.localDay(ShareAsk.Now(0, 0)))
        assertEquals(-1L, ShareAsk.localDay(ShareAsk.Now(0, -60)))
        assertEquals(1L, ShareAsk.localDay(ShareAsk.Now(86_400_000L - 3_600_000L, 60)))
        assertTrue(ShareAsk.localDay(ShareAsk.Now(-1, 0)) == -1L)
    }

    companion object {
        /** A stored state as the vectors write it (20.4's names), read as decide reads JSON (integral numbers only). */
        @Suppress("UNCHECKED_CAST")
        internal fun state(o: Map<String, Any?>): ShareAsk.State {
            fun n(k: String) = ShareAsk.integral(o[k])
            val app = (o["app"] as? Map<String, Any?>)?.let { a ->
                val ask = (a["ask"] as? Map<String, Any?>)?.let { k ->
                    ShareAsk.Ask(k["kind"] as String, ShareAsk.integral(k["number"])!!.toInt(), k["shown"] == true)
                }
                ShareAsk.App(ShareAsk.integral(a["takes"])?.toInt() ?: 0, ShareAsk.integral(a["recited_ms"]) ?: 0,
                    a["asked"] == true, a["take_open"] == true, ask)
            } ?: ShareAsk.App()
            return ShareAsk.State(o["consent"] as? String ?: "unknown", n("asks")?.toInt() ?: 0, n("sessions")?.toInt() ?: 0,
                n("last_ask_session")?.toInt() ?: 0, n("installed_at"), n("last_ask_at"), n("last_ask_day"), app)
        }

        internal fun event(o: Map<String, Any?>): ShareAsk.Event = ShareAsk.Event(o["type"] as? String ?: "", o["end"] as? String,
            ShareAsk.integral(o["speech_ms"]), o["offered"] as? Boolean, o["answer"] as? String, o["on"] as? Boolean)

        internal fun json(s: ShareAsk.State): Map<String, Any?> = linkedMapOf("consent" to s.consent, "asks" to s.asks, "sessions" to s.sessions,
            "last_ask_session" to s.lastAskSession, "installed_at" to s.installedAt, "last_ask_at" to s.lastAskAt, "last_ask_day" to s.lastAskDay,
            "app" to linkedMapOf("takes" to s.app.takes, "recited_ms" to s.app.recitedMs, "asked" to s.app.asked, "take_open" to s.app.takeOpen,
                "ask" to s.app.ask?.let { linkedMapOf("kind" to it.kind, "number" to it.number, "shown" to it.shown) }))

        /** Numbers as doubles, maps by key: the JSON reading of both sides compared alike. */
        fun norm(x: Any?): Any? = when (x) {
            is Map<*, *> -> x.entries.associate { (k, v) -> k.toString() to norm(v) }
            is List<*> -> x.map { norm(it) }
            is Number -> x.toDouble()
            else -> x
        }
    }
}
