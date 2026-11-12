package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.RemoteConfig
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared vector of SDK 3.0's recordings, `vectors30/sharing_30.json` (the contract's `sdk_3.0.0/vectors`, the
 * same file every SDK replays): the feed's ring, pre-roll and tail, the take list with a feed, `take_seq` and
 * `gap_ms_prev`, the 20 minute cap inside a take, and the hold whose fit is counted in samples. Every case runs
 * through [ShareRecorder] with a clock and a timer the test moves (nothing sleeps) and recordings that only count
 * samples: no audio is kept, a take of 20 minutes is one small buffer given many times.
 */
class Sharing30VectorsTest : OpenChecked() {
    /** A recording that counts what it is given. */
    private class Counted : Recording {
        var samples = 0L
        var ended = false

        override fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
            check(!ended) { "audio after the end" }
            this.samples += length
        }

        override fun takes(list: List<Take>) { check(!ended) { "a take list after the end" } }
        override fun finish(completed: Boolean) { check(!ended) { "ended twice" }; ended = true }
        override fun abandon() { check(!ended) { "ended twice" }; ended = true }
        override val alive: Boolean get() = !ended
    }

    private class World(val window: Int, var on: Boolean, val block: Int) {
        var now = 0L
        val closed = ArrayList<ShareRecorder.Closed>()
        private val due = ArrayList<Pair<Long, () -> Unit>>()
        val recorder = ShareRecorder({ now }, { window }, { on }, { "learner" }, { _, _ -> Counted() })
        private val buffer = FloatArray(block)

        init {
            recorder.onClosed = { closed.add(it) }
            recorder.timer = JoinTimer { ms, task ->
                val e = (now + ms) to task
                due.add(e)
                return@JoinTimer { due.remove(e); Unit }
            }
        }

        /** Moves the clock to [t], running the timers that come due on the way, each at its time. */
        fun at(t: Long) {
            while (true) {
                val next = due.filter { it.first <= t }.minByOrNull { it.first } ?: break
                due.remove(next)
                now = maxOf(now, next.first)
                next.second()
            }
            now = maxOf(now, t)
        }

        fun audio(samples: Long, rate: Int) {
            var left = samples
            while (left > 0) {
                val k = minOf(left, buffer.size.toLong()).toInt()
                recorder.audio(buffer, 0, k, rate)
                left -= k
            }
        }
    }

    private fun Map<String, Any?>.long(k: String): Long = (this[k] as Double).toLong()
    private fun Map<String, Any?>.int(k: String): Int = (this[k] as Double).toInt()

    @Suppress("UNCHECKED_CAST")
    @Test fun everyCaseOfTheSharedVector() {
        val v = Json.parse(javaClass.getResourceAsStream("/vectors30/sharing_30.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        assertEquals(v.long("default_window_ms").toInt(), TakeJoin.DEFAULT_WINDOW_MS)
        assertEquals(v.long("cap_ms"), TakeJoin.CAP_MS)
        assertEquals(v.long("hold_ms"), TakeJoin.HOLD_MS)
        assertEquals(v.long("stale_ms"), TakeJoin.STALE_MS)
        assertEquals(v.long("gap_ms_prev_max"), TakeJoin.GAP_PREV_MAX_MS)
        assertEquals(v.int("ring_ms"), ShareRecorder.PRE_ROLL_MS)
        assertEquals(v.int("tail_ms"), ShareRecorder.TAIL_MS)
        for ((rate, cap) in v["cap_samples"] as Map<String, Any?>) assertEquals((cap as Double).toLong(), TakeJoin.capSamples(rate.toInt()))
        val cases = v["cases"] as List<Map<String, Any?>>
        assertTrue("the vector has the feed, the cap, the fit in samples and the tail wait: ${cases.size} cases", cases.size >= 41)
        assertEquals(4, cases.count { (it["name"] as String).startsWith("the fit is in samples") })
        assertEquals(v.long("tail_wait_ms"), TakeJoin.TAIL_WAIT_MS)
        assertEquals(3, cases.count { (it["name"] as String).startsWith("the tail wait") })
        // the take's audio in buffers of two sizes: where a buffer ends must not matter
        for (c in cases) for (block in listOf(4_096, 7_919)) {
            val name = "${c["name"]} (buffers of $block)"
            val w = World(RemoteConfig.Flags.parse(c["flags"]).shareJoinMs, c["sharing_on_at_start"] as Boolean, block)
            for ((i, e) in (c["events"] as List<Map<String, Any?>>).withIndex()) {
                val want = e["expect"] as Map<String, Any?>
                val at = "$name, event $i"
                when (e["event"]) {
                    "feed" -> {
                        w.at(e.long("wall_ms"))
                        val n = e.int("samples")
                        w.recorder.feed(FloatArray(n), 0, n, e.int("rate"))
                        assertEquals("$at to_tail", want.long("to_tail"), w.recorder.lastFeed[0])
                        assertEquals("$at to_ring", want.long("to_ring"), w.recorder.lastFeed[1])
                        assertEquals("$at dropped", want.long("dropped"), w.recorder.lastFeed[2])
                    }
                    "take" -> {
                        w.at(e.long("start_wall_ms"))
                        // a take is recited in the foreground
                        w.recorder.foreground()
                        val rate = e.int("rate")
                        w.recorder.takeStarted(TajweedObserver.SessionStart("4.0.0", "c16_l64", "cpu", sampleRate = rate))
                        w.audio(e.long("samples"), rate)
                        (e["fed_during"] as? Double)?.toInt()?.let { n ->
                            w.recorder.feed(FloatArray(n), 0, n, rate)
                            assertEquals("$at fed_during_dropped", want.long("fed_during_dropped"), w.recorder.lastFeed[2])
                        }
                        w.at(e.long("end_wall_ms"))
                        w.recorder.takeEnded(true)
                        val t = w.recorder.lastTake
                        assertEquals("$at recorded", want["recorded"], t.recorded)
                        assertEquals("$at pre_roll", want.int("pre_roll"), t.preRoll)
                        assertEquals("$at joined", want["joined"] ?: false, t.joined)
                        assertEquals("$at held", want["held"] ?: false, t.held)
                        val cut = (want["cut_at_take_samples"] as? List<Double>)?.also { assertEquals(at, 1, it.size) }?.single()?.toLong() ?: -1L
                        assertEquals("$at cut_at_take_samples", cut, t.cutAt)
                    }
                    "background" -> {
                        w.at(e.long("wall_ms"))
                        w.recorder.background()
                    }
                    "sharing" -> {
                        w.on = e["on"] as Boolean
                        if (!w.on) w.recorder.stop()
                    }
                    "delete" -> {
                        // an accepted "Delete what I shared": sharing goes off with it (the case turns it on again)
                        w.on = false
                        w.recorder.stop()
                        w.recorder.forget()
                    }
                    else -> throw AssertionError("$at: an event this test does not know: ${e["event"]}")
                }
                assertEquals("$at ring_samples", want.int("ring_samples"), w.recorder.keptSamples)
                assertTrue("$at: the ring holds more than a second", w.recorder.keptSamples <= (e["rate"] as? Double)?.toInt() ?: 48_000)
            }
            // time passes until nothing waits
            w.at(w.now + 60_000)
            w.recorder.checkWindow()
            assertTrue("$name: a recording is still open", w.recorder.current == null)
            val want = c["recordings"] as List<Map<String, Any?>>
            assertEquals("$name: recordings", want.size, w.closed.size)
            for ((k, r) in want.withIndex()) {
                val got = w.closed[k]
                val rec = "$name, recording $k"
                assertEquals("$rec rate", r.int("rate"), got.rate)
                assertEquals("$rec samples", r.long("samples"), got.samples)
                assertEquals("$rec samples given to the recording", got.samples, (got.recording as Counted).samples)
                assertEquals("$rec audio_ms", r.long("audio_ms"), TakeJoin.ms(got.samples, got.rate))
                assertTrue("$rec holds more than 20 minutes", got.samples <= TakeJoin.capSamples(got.rate))
                assertEquals("$rec take_seq", r.int("take_seq"), got.order.takeSeq)
                assertEquals("$rec gap_ms_prev", (r["gap_ms_prev"] as? Double)?.toInt(), got.order.gapMsPrev)
                val takes = (r["takes"] as? List<Map<String, Any?>>)?.map { it.long("start_ms") to it.int("gap_ms") }
                assertEquals("$rec takes", takes, got.takes.takeIf { it.size > 1 }?.map { it.startMs to it.gapMs })
                assertEquals("$rec parts", (r["parts"] as List<Map<String, Any?>>).map { listOf(it.long("pre_roll"), it.long("take"), it.long("tail")) },
                    got.parts.map { it.toList() })
                assertEquals("$rec deleted", r["deleted"] ?: false, got.deleted)
                assertEquals("$rec closed_at_cap", r["closed_at_cap"] ?: false, got.atCap)
            }
        }
    }
}
