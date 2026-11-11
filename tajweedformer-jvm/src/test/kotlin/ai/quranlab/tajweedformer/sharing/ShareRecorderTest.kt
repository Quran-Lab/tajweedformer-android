package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.cloud.RemoteConfig
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDK 2.0.3, takes in a row share one recording: the join rules of [ShareRecorder] on their own, with a clock and a
 * timer the test moves (nothing sleeps) and recordings that only count what they are given (no codec, so this runs
 * wherever the JVM does). The shared vector `takes_join.json` is replayed case by case; the rest are the rules the
 * vector leaves to the SDKs (a take without audio, the hold near the 20 minute cap, 256 takes, the app's life,
 * sharing going off, what a recording is bound to, a clock set back).
 */
class ShareRecorderTest {
    /** A recording that counts: its input rate, samples, the last take list it was told, and how it ended. */
    private class Counted : Recording {
        var rate = 0
        var samples = 0L
        var takes: List<Take>? = null
        var completed: Boolean? = null
        var abandoned = false
        var dead = false
        val ms: Long get() = if (rate == 0) 0 else samples * 1000 / rate

        override fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
            check(completed == null) { "audio after the end" }
            if (rate == 0) rate = sampleRate
            check(sampleRate == rate) { "a recording has one input rate" }
            this.samples += length
        }

        override fun takes(list: List<Take>) {
            check(completed == null) { "a take list after the end" }
            check(list.size >= 2 && list[0] == Take(0, 0)) { "a list is written when a take joins: $list" }
            check(list.zipWithNext().all { (a, b) -> b.startMs > a.startMs }) { "start_ms must increase: $list" }
            takes = list
        }

        override fun finish(completed: Boolean) {
            check(this.completed == null) { "ended twice" }
            this.completed = completed
        }

        override fun abandon() {
            check(completed == null) { "ended twice" }
            completed = false
            abandoned = true
        }

        override val alive: Boolean get() = completed == null && !dead
    }

    /** The recorder under test with its world: the clock, the timers, sharing's switches and every recording it opened. */
    private class World(var window: Int = TakeJoin.DEFAULT_WINDOW_MS, timers: Boolean = true) {
        var now = 0L
        var on = true
        var identity: Any? = "learner"
        val opened = ArrayList<Counted>()
        private val due = ArrayList<Pair<Long, () -> Unit>>()
        val recorder = ShareRecorder({ now }, { window }, { on }, { identity }, { Counted().also(opened::add) })

        init {
            recorder.timer = JoinTimer { ms, task ->
                val e = (now + ms) to task
                if (timers) due.add(e)
                return@JoinTimer { due.remove(e); Unit }
            }
        }

        /** Timers waiting. */
        val armed: Int get() = due.size

        /** Moves the clock to [t], running the timers that come due on the way, each at its time. */
        fun at(t: Long) {
            while (true) {
                val next = due.filter { it.first <= t }.minByOrNull { it.first } ?: break
                due.remove(next)
                now = maxOf(now, next.first)
                next.second()
            }
            now = t
        }

        fun start(rate: Int = 16_000, revision: String = "v4.0+abc") =
            recorder.takeStarted(TajweedObserver.SessionStart(revision, "c16_l64", "cpu", "balanced", "general", sampleRate = rate))

        private val silence = FloatArray(4_096)

        fun audio(samples: Long, rate: Int = 16_000) {
            var left = samples
            while (left > 0) {
                val k = minOf(left, silence.size.toLong()).toInt()
                recorder.audio(silence, 0, k, rate)
                left -= k
            }
        }

        /** One take: starts at [start], [samples] of audio at [rate], ends at [end]. */
        fun take(start: Long, samples: Long, end: Long, rate: Int = 16_000, completed: Boolean = true) {
            at(start)
            start(rate)
            audio(samples, rate)
            at(end)
            recorder.takeEnded(completed)
        }

        /** The recordings that got audio, in order (one without audio keeps nothing, as the real capture). */
        val recordings: List<Counted> get() = opened.filter { it.samples > 0 }

        /** Time passes until nothing waits; every recording must have ended. */
        fun settle() {
            at(now + 60_000)
            recorder.checkWindow()
            assertNull("no recording left open", recorder.current)
            assertTrue("every recording ended once", opened.all { it.completed != null })
        }
    }

    private fun takes(vararg t: Pair<Long, Int>) = t.map { Take(it.first, it.second) }

    /** The shared vector: every case, once with the window's timer running and once without it (the clock decides). */
    @Suppress("UNCHECKED_CAST")
    @Test fun takesJoinVector() {
        val v = Json.parse(javaClass.getResourceAsStream("/sharing_vectors/takes_join.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        assertEquals((v["default_window_ms"] as Double).toInt(), TakeJoin.DEFAULT_WINDOW_MS)
        assertEquals((v["cap_ms"] as Double).toLong(), TakeJoin.CAP_MS)
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(10, cases.size)
        var recordings = 0
        for (c in cases) for (timers in listOf(true, false)) {
            val name = "${c["name"]} (${if (timers) "timer" else "no timer"})"
            // the window as the signed config's flags give it
            val w = World(RemoteConfig.Flags.parse(c["flags"]).shareJoinMs, timers)
            for (e in c["events"] as List<Map<String, Any?>>) {
                if (e["event"] == "background") {
                    w.at((e["wall_ms"] as Double).toLong())
                    w.recorder.background()
                } else {
                    assertEquals("take", e["event"])
                    w.at((e["start_wall_ms"] as Double).toLong())
                    // a take is recited in the foreground
                    w.recorder.foreground()
                    val rate = (e["rate"] as Double).toInt()
                    w.start(rate)
                    w.audio((e["samples"] as Double).toLong(), rate)
                    w.at((e["end_wall_ms"] as Double).toLong())
                    w.recorder.takeEnded(true)
                }
            }
            w.settle()
            val want = c["expect"] as List<Map<String, Any?>>
            val got = w.recordings.map { r ->
                mapOf("rate" to r.rate.toDouble(), "audio_ms" to r.ms.toDouble(),
                    "takes" to r.takes?.map { mapOf("start_ms" to it.startMs.toDouble(), "gap_ms" to it.gapMs.toDouble()) })
            }
            assertEquals(name, want, got)
            assertTrue(name, w.recordings.all { it.completed == true })
            if (timers) recordings += got.size
        }
        println("takes join vector: ${cases.size} cases, $recordings recordings, with and without the window's timer")
    }

    /** `flags.share_join_ms`: a whole number 0 to 10000, anything else the default. */
    @Test fun theWindowIsAWholeNumberFrom0To10000() {
        fun w(v: Any?) = RemoteConfig.Flags.parse(mapOf("share_join_ms" to v)).shareJoinMs
        assertEquals("absent", 3000, RemoteConfig.Flags.parse(emptyMap<String, Any?>()).shareJoinMs)
        assertEquals("no flags", 3000, RemoteConfig.Flags.parse(null).shareJoinMs)
        assertEquals(3000, RemoteConfig.Flags().shareJoinMs)
        for ((v, want) in listOf<Pair<Any?, Int>>(0.0 to 0, 1.0 to 1, 3000.0 to 3000, 8000.0 to 8000, 10_000.0 to 10_000, 10_001.0 to 3000,
            -1.0 to 3000, 2.5 to 3000, 1e300 to 3000, Double.NaN to 3000, Double.POSITIVE_INFINITY to 3000, "5" to 3000, "" to 3000, true to 3000,
            false to 3000, null to 3000, listOf(5.0) to 3000, mapOf("ms" to 5.0) to 3000)) {
            assertEquals("share_join_ms $v", want, w(v))
        }
        // as the signed config's JSON gives them
        @Suppress("UNCHECKED_CAST")
        fun json(text: String) = RemoteConfig.Flags.parse((Json.parse(text) as Map<String, Any?>)["flags"]).shareJoinMs
        assertEquals(0, json("{\"flags\":{\"share_join_ms\":0}}"))
        assertEquals(8000, json("{\"flags\":{\"share_join_ms\":8000}}"))
        assertEquals(3000, json("{\"flags\":{\"share_join_ms\":\"8000\"}}"))
        assertEquals(3000, json("{\"flags\":{\"share_join_ms\":true}}"))
        assertEquals(3000, json("{\"flags\":{\"share_join_ms\":0.5}}"))
        assertEquals(3000, json("{\"flags\":{\"share_join_ms\":-100}}"))
        assertEquals(3000, json("{\"flags\":{\"contributions\":true}}"))
    }

    /** The gap is rounded to the nearest 100 ms, half up, within 0 to 10000; `start_ms` is floored. */
    @Test fun gapsRoundToHundredsAndStartsFloor() {
        for ((gap, want) in listOf(0L to 0, 49L to 0, 50L to 100, 149L to 100, 150L to 200, 1200L to 1200, 2999L to 3000, 9949L to 9900, 9950L to 10_000,
            10_000L to 10_000, 99_999L to 10_000, -1L to 0, -5_000L to 0)) {
            assertEquals("gap $gap", want, TakeJoin.gapMs(gap))
        }
        assertEquals(771, TakeJoin.ms(12_345, 16_000))
        assertEquals(834, TakeJoin.ms(12_345 + 999, 16_000))
        assertEquals(999, TakeJoin.ms(44_099, 44_100))
        assertEquals(1_200_000, TakeJoin.ms(20L * 60 * 48_000, 48_000))
        assertTrue(TakeJoin.fits(1199L * 16_000, 16_000, 16_000))
        assertFalse(TakeJoin.fits(1199L * 16_000, 16_016, 16_000))
    }

    /** A take that pushes no audio adds no entry and does not move the previous take's end. */
    @Test fun aTakeWithoutAudioAddsNoEntryAndDoesNotMoveTheEnd() {
        val w = World()
        w.take(0, 5 * 16_000L, 5_000)
        w.take(6_000, 0, 7_000)
        // 2900 ms after the take that had audio: inside the window, whatever the empty take did
        w.take(7_900, 3 * 16_000L, 10_900)
        w.settle()
        val r = w.recordings.single()
        assertEquals(takes(0L to 0, 5_000L to 2_900), r.takes)
        assertEquals(8_000, r.ms)
        assertEquals("no recording was made for the take without audio", 1, w.opened.size)

        // an empty take that ends after the window (from the last audio) closes the recording there
        val x = World()
        x.take(0, 5 * 16_000L, 5_000)
        x.take(6_000, 0, 8_500)
        assertNull("closed when the empty take ended, 3500 ms after the audio", x.recorder.current)
        x.take(8_600, 16_000, 9_600)
        x.settle()
        assertEquals(listOf(5_000L, 1_000L), x.recordings.map { it.ms })
        assertTrue(x.recordings.all { it.takes == null })

        // a recording whose first take has no audio keeps nothing, and the next take starts its own
        val y = World()
        y.take(0, 0, 1_000)
        assertNull(y.recorder.current)
        y.take(1_500, 16_000, 2_500)
        y.settle()
        assertEquals(1, y.recordings.size)
        assertNull(y.recordings[0].takes)
        // the empty take was cancelled: the completed flag is the last take's that had audio
        val z = World()
        z.take(0, 16_000, 1_000)
        z.take(1_500, 0, 2_000, completed = false)
        z.settle()
        assertEquals(true, z.recordings.single().completed)
    }

    /** `completed` is the last take's; a cancelled last take ends the recording as not completed. */
    @Test fun completedIsTheLastTakes() {
        val w = World()
        w.take(0, 16_000, 1_000, completed = false)
        w.take(1_500, 16_000, 2_500)
        w.settle()
        assertEquals(true, w.recordings.single().completed)
        val x = World()
        x.take(0, 16_000, 1_000)
        x.take(1_500, 16_000, 2_500, completed = false)
        x.settle()
        assertEquals(false, x.recordings.single().completed)
        assertEquals(takes(0L to 0, 1_000L to 500), x.recordings.single().takes)
    }

    /** A joining take whose start is not after the last entry's adds no entry; its audio still joins. */
    @Test fun aBoundaryThatDoesNotAdvanceAddsNoEntry() {
        val w = World()
        w.take(0, 5, 100)          // 5 samples: under a millisecond
        w.take(200, 5, 300)        // starts at 0 ms too: no entry
        w.take(400, 16_000, 1_400) // starts at 0 ms still (10 samples)
        w.take(1_500, 16_000, 2_500)
        w.settle()
        val r = w.recordings.single()
        assertEquals(32_010L, r.samples)
        assertEquals(takes(0L to 0, 1_000L to 100), r.takes)
    }

    /** The window's edge, its expiry by the timer, and a timer that fires early or not at all. */
    @Test fun theWindowExpiresByTheClock() {
        val w = World()
        w.take(0, 16_000, 1_000)
        assertEquals("one timer for the window", 1, w.armed)
        w.at(4_000)
        assertTrue("3000 ms later the recording still waits", w.recorder.current != null)
        w.at(4_001)
        assertNull("one millisecond past the window it is closed", w.recorder.current)
        assertEquals(0, w.armed)
        assertEquals(true, w.opened.single().completed)

        // the timer fired early (a coarse timer): the clock says the window has not passed, so it waits on
        val x = World(timers = false)
        x.take(0, 16_000, 1_000)
        x.now = 2_500
        x.recorder.checkWindow()
        assertTrue(x.recorder.current != null)
        x.now = 4_000
        x.recorder.checkWindow()
        assertTrue("exactly the window: still open", x.recorder.current != null)
        x.take(4_000, 16_000, 5_000)
        assertEquals("joined at the edge", takes(0L to 0, 1_000L to 3_000), x.opened.single().takes)
        // no timer at all: the next take's start decides
        x.take(8_001, 16_000, 9_001)
        x.settle()
        assertEquals(listOf(2_000L, 1_000L), x.recordings.map { it.ms })

        // a take that starts cancels the wait; the window starts again when it ends
        val y = World()
        y.take(0, 16_000, 1_000)
        y.at(3_000)
        y.start()
        assertEquals(0, y.armed)
        y.at(60_000)
        assertTrue("a running take keeps its recording however long it runs", y.recorder.current != null)
        y.audio(16_000)
        y.recorder.takeEnded(true)
        assertEquals(1, y.armed)
        y.at(63_000)
        assertTrue(y.recorder.current != null)
        y.at(63_001)
        assertNull(y.recorder.current)
        assertEquals(takes(0L to 0, 1_000L to 2_000), y.opened.single().takes)
    }

    /** Window 0 never joins: every take ends its recording, as 2.0.2 did. */
    @Test fun windowZeroEndsTheRecordingWithTheTake() {
        val w = World(window = 0)
        w.take(0, 16_000, 1_000)
        assertNull("closed with the take", w.recorder.current)
        assertEquals(0, w.armed)
        w.take(1_000, 16_000, 2_000)
        assertEquals(2, w.recordings.size)
        assertTrue(w.recordings.all { it.takes == null && it.completed == true })
        // the config's window changes between takes: the one in force at each decision counts
        val x = World(window = 8_000)
        x.take(0, 16_000, 1_000)
        x.take(8_500, 16_000, 9_500)
        assertEquals(takes(0L to 0, 1_000L to 7_500), x.opened.single().takes)
        x.window = 0
        x.take(9_600, 16_000, 10_600)
        x.settle()
        assertEquals("window 0 at the take's start: a new recording", 2, x.recordings.size)
    }

    /** The app in the background: a waiting recording closes at once, a running take's when the take ends. */
    @Test fun theBackgroundClosesTheRecording() {
        val w = World()
        w.take(0, 16_000, 1_000)
        w.at(1_200)
        w.recorder.background()
        assertNull("closed in the window", w.recorder.current)
        assertEquals(0, w.armed)
        // a take recited while the app is still in the background ends its recording with itself
        w.take(1_500, 16_000, 2_500)
        assertNull(w.recorder.current)
        w.recorder.foreground()
        w.take(2_600, 16_000, 3_600)
        assertTrue("in the foreground again the recording waits", w.recorder.current != null)
        // the background during a take: the take keeps its recording, which closes when it ends
        w.at(3_800)
        w.start()
        w.audio(16_000)
        w.recorder.background()
        assertTrue(w.recorder.current != null)
        w.audio(16_000)
        w.at(5_800)
        w.recorder.takeEnded(true)
        assertNull(w.recorder.current)
        w.settle()
        assertEquals(listOf(1_000L, 1_000L, 3_000L), w.recordings.map { it.ms })
        assertEquals(takes(0L to 0, 1_000L to 200), w.recordings[2].takes)
    }

    /** Sharing going off ends the recording at once, in a take or between takes, and nothing more is recorded. */
    @Test fun sharingOffEndsTheRecordingAtOnce() {
        val w = World()
        w.take(0, 16_000, 1_000)
        w.at(1_500)
        w.on = false
        w.recorder.stop()
        assertNull(w.recorder.current)
        assertTrue("nothing of it is kept", w.opened.single().abandoned)
        assertEquals(0, w.armed)
        w.take(1_600, 16_000, 2_600)
        assertEquals("nothing is opened while sharing is off", 1, w.opened.size)
        // off during a take: the rest of the take is not recorded
        w.on = true
        w.at(10_000)
        w.start()
        w.audio(16_000)
        w.on = false
        w.recorder.stop()
        w.audio(16_000)
        w.at(12_000)
        w.recorder.takeEnded(true)
        assertEquals(2, w.opened.size)
        assertEquals(16_000L, w.opened[1].samples)
        assertTrue(w.opened[1].abandoned)
        // off without the stop call (the signed config's flag read at the next take): the take does not join
        val x = World()
        x.take(0, 16_000, 1_000)
        x.on = false
        x.take(1_500, 16_000, 2_500)
        assertEquals(1, x.opened.size)
        assertNull(x.recorder.current)
        assertEquals(16_000L, x.opened[0].samples)
        assertFalse("closed as a recording ends; its files go where sharing's switch says", x.opened[0].abandoned)
    }

    /** A take joins only at the same input rate and under the same contributor, consent version and model revision. */
    @Test fun aRecordingIsBoundToItsRateAndIdentity() {
        val w = World()
        w.take(0, 16_000, 1_000)
        w.take(1_500, 48_000, 2_500, rate = 48_000)
        w.identity = "another contributor"
        w.take(3_000, 48_000, 4_000, rate = 48_000)
        w.take(4_500, 48_000, 5_500, rate = 48_000)
        w.settle()
        assertEquals(listOf(16_000, 48_000, 48_000), w.recordings.map { it.rate })
        assertEquals(listOf(1_000L, 1_000L, 2_000L), w.recordings.map { it.ms })
        assertEquals(listOf(null, null, takes(0L to 0, 1_000L to 500)), w.recordings.map { it.takes })
        // audio at another rate inside a take is ignored (one rate per recording), as before 2.0.3
        val x = World()
        x.at(0)
        x.start()
        x.audio(16_000)
        x.audio(48_000, rate = 48_000)
        x.audio(16_000)
        x.at(2_000)
        x.recorder.takeEnded(true)
        x.settle()
        assertEquals(32_000L, x.recordings.single().samples)
        // a recording that failed (a storage error) is not joined
        val y = World()
        y.take(0, 16_000, 1_000)
        y.opened[0].dead = true
        y.take(1_500, 16_000, 2_500)
        y.settle()
        assertEquals(2, y.recordings.size)
    }

    /** At most 256 takes in a recording: the 257th starts a new one. */
    @Test fun the257thTakeStartsANewRecording() {
        val w = World()
        for (i in 0 until 300) w.take(i * 200L, 1_600, i * 200L + 100)
        w.settle()
        assertEquals(listOf(256, 44), w.recordings.map { it.takes!!.size })
        assertEquals(listOf(25_600L, 4_400L), w.recordings.map { it.ms })
        assertEquals(Take(25_500, 100), w.recordings[0].takes!!.last())
        assertEquals(TakeJoin.MAX_TAKES, 256)
    }

    /** Near the 20 minute cap a joining take is held until it ends: it joins when it fits, else it starts a new recording whole. */
    @Test fun theCapIsExactNearItAndNeverCutsATake() {
        val s = 16_000L
        // it fits: held while it runs, joined when it ends, and the recording, now at the cap, closes
        val w = World()
        w.take(0, 1199 * s, 1_199_000)
        w.at(1_199_500)
        w.start()
        w.audio(8_000)
        assertEquals("held, not yet in the recording", 1199 * s, w.opened[0].samples)
        w.audio(8_000)
        w.at(1_200_500)
        w.recorder.takeEnded(true)
        assertEquals(1200 * s, w.opened[0].samples)
        assertEquals(takes(0L to 0, 1_199_000L to 500), w.opened[0].takes)
        assertNull("20 minutes: closed at once", w.recorder.current)
        assertEquals(1, w.opened.size)

        // it outgrows the room: the recording closes without it and the take starts a new one with all its audio
        val x = World()
        x.take(0, 1199 * s, 1_199_000)
        x.at(1_199_500)
        x.start()
        x.audio(s)
        assertEquals(1, x.opened.size)
        x.audio(16)
        assertEquals("one sample block past the room: a new recording", 2, x.opened.size)
        assertEquals(true, x.opened[0].completed)
        assertNull(x.opened[0].takes)
        assertEquals(s + 16, x.opened[1].samples)
        x.audio(s)
        x.at(1_201_600)
        x.recorder.takeEnded(true)
        x.settle()
        assertEquals(listOf(1_199_000L, 2_001L), x.recordings.map { it.ms })
        assertTrue(x.recordings.all { it.takes == null })

        // exactly 30 s of room: held; 30 s and one millisecond: joined at once
        val y = World()
        y.take(0, 1170 * s, 1_170_000)
        y.at(1_170_100)
        y.start()
        y.audio(10 * s)
        assertEquals(1170 * s, y.opened[0].samples)
        y.at(1_180_100)
        y.recorder.takeEnded(true)
        assertEquals(1180 * s, y.opened[0].samples)
        val z = World()
        z.take(0, 1170 * s - 16, 1_170_000)
        z.at(1_170_100)
        z.start()
        z.audio(s)
        assertEquals("more than 30 s of room: in the recording as it comes", 1171 * s - 16, z.opened[0].samples)

        // with room, a take that runs past 20 minutes stays whole; the recording closes when it ends
        val v = World()
        v.take(0, 1100 * s, 1_100_000)
        v.take(1_100_500, 200 * s, 1_300_500)
        assertNull("past the cap: closed with the take", v.recorder.current)
        assertEquals(1300 * s, v.opened.single().samples)
        assertEquals(takes(0L to 0, 1_100_000L to 500), v.opened.single().takes)
        v.take(1_300_600, s, 1_301_600)
        v.settle()
        assertEquals(2, v.recordings.size)
        // a single take longer than 20 minutes is one recording too
        val u = World()
        u.take(0, 1500 * s, 1_500_000)
        assertNull(u.recorder.current)
        assertEquals(1500 * s, u.opened.single().samples)
    }

    /** A held take: the background, sharing off and an empty take behave as for any other. */
    @Test fun aHeldTakeFollowsTheSameRules() {
        val s = 16_000L
        val w = World()
        w.take(0, 1199 * s, 1_199_000)
        w.at(1_199_500)
        w.start()
        w.audio(4_000)
        w.on = false
        w.recorder.stop()
        w.at(1_199_900)
        w.recorder.takeEnded(true)
        assertEquals("sharing went off: the held audio is gone", 1199 * s, w.opened.single().samples)
        assertTrue(w.opened.single().abandoned)
        // a held take that pushed nothing adds nothing
        val x = World()
        x.take(0, 1199 * s, 1_199_000)
        x.take(1_199_500, 0, 1_199_900)
        x.take(1_200_000, 4_000, 1_200_300)
        x.settle()
        assertEquals(takes(0L to 0, 1_199_000L to 1_000), x.recordings.single().takes)
        assertEquals(1199 * s + 4_000, x.recordings.single().samples)
    }

    /** A clock set back: the gap is 0 and the take joins; a recording that waits is closed by its timer. */
    @Test fun aClockSetBack() {
        val w = World()
        w.take(0, 16_000, 10_000)
        w.now = 4_000
        w.start()
        w.audio(16_000)
        w.now = 5_000
        w.recorder.takeEnded(true)
        assertEquals(takes(0L to 0, 1_000L to 0), w.opened.single().takes)
        // the clock goes back while the recording waits: its timer ran its time, so it closes
        w.now = 1_000
        w.recorder.checkWindow()
        assertNull(w.recorder.current)
        assertEquals(1, w.opened.size)
    }

    /** The default timer is a thread of the process: with the real clock a waiting recording closes by itself. */
    @Test fun theDefaultTimerClosesTheRecordingByItself() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val r = object : Recording {
            var samples = 0L
            override fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) { this.samples += length }
            override fun takes(list: List<Take>) {}
            override fun finish(completed: Boolean) = closed.countDown()
            override fun abandon() = closed.countDown()
        }
        val recorder = ShareRecorder(System::currentTimeMillis, { 40 }, { true }, { null }, { r })
        recorder.takeStarted(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", "balanced", "general"))
        recorder.audio(FloatArray(1_600), 0, 1_600, 16_000)
        recorder.takeEnded(true)
        assertTrue("the window's timer closed it", closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(recorder.current)
        // a cancelled timer does not run
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = JoinTimer.DEFAULT.schedule(30) { ran.set(true) }
        cancel()
        val after = java.util.concurrent.CountDownLatch(1)
        JoinTimer.DEFAULT.schedule(60) { after.countDown() }
        assertTrue(after.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(ran.get())
    }

    /**
     * A take that starts while another is open ends that one's recording as not completed (as 2.0.2 ended its
     * session) and starts a new one: when the open take really ended is not known, so nothing joins.
     */
    @Test fun aTakeThatNeverEndedIsEndedByTheNext() {
        val w = World()
        w.at(0)
        w.start()
        w.audio(16_000)
        w.at(1_000)
        w.start()
        assertEquals("the first take's recording ended there", false, w.opened[0].completed)
        w.audio(16_000)
        w.at(2_000)
        w.recorder.takeEnded(true)
        w.settle()
        assertEquals(listOf(16_000L, 16_000L), w.recordings.map { it.samples })
        assertEquals(listOf(false, true), w.recordings.map { it.completed })
        assertTrue(w.recordings.all { it.takes == null })
        // the same after takes that joined: the recording so far is kept, the take that never ended closes it
        val y = World()
        y.take(0, 16_000, 1_000)
        y.at(1_500)
        y.start()
        y.audio(16_000)
        y.at(2_600)
        y.start()
        y.audio(16_000)
        y.at(3_600)
        y.recorder.takeEnded(true)
        y.settle()
        assertEquals(listOf(32_000L, 16_000L), y.recordings.map { it.samples })
        assertEquals(takes(0L to 0, 1_000L to 500), y.recordings[0].takes)
        assertEquals(listOf(false, true), y.recordings.map { it.completed })
        // audio outside a take is never recorded
        val x = World()
        x.audio(16_000)
        x.take(0, 16_000, 1_000)
        x.audio(16_000)
        x.settle()
        assertEquals(16_000L, x.recordings.single().samples)
    }
}
