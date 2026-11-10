package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.Demotion
import ai.quranlab.tajweedformer.accel.Failover
import ai.quranlab.tajweedformer.accel.GoldenRun
import ai.quranlab.tajweedformer.accel.PreparedAccelerator
import ai.quranlab.tajweedformer.accel.PromotionRecord
import ai.quranlab.tajweedformer.accel.Reasons
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.Telemetry
import ai.quranlab.tajweedformer.cloud.TelemetryContext
import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.engine.TajweedJni
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The in-session failover through the REAL exact engine: the accelerator is a fake that wraps the CPU engine and fails on
 * demand (an exception, NaN, values out of range, a hang past its deadline) at the first, a middle and the last chunk of
 * every self-test clip; the session continues on the real CPU engine (released while the "accelerator" ran, so the switch
 * is the cold one: weights loaded, stream built, the ring replayed). Checks: no crash, every chunk delivered, every word
 * of the ayah decoded, later sessions on the CPU, the telemetry counts; prints the stall (cold and warm), the standby's
 * memory, and the divergence from a CPU-only run.
 *
 * Needs the JNI library with the engine and a decoded V4 folder with its `selftest/` set: `TJ_FAILOVER_MODEL` (or
 * `TJ_SELFTEST_MODEL`); `TJ_FAILOVER_AUDIO` (a folder of the engine's float32 parity clips, `<reciter>_<sss><aaa>.pcm`)
 * adds the divergence over those ayahs. Skipped without them.
 */
class FailoverEngineTest {
    companion object {
        private var dirOrNull: File? = null
        private lateinit var model: TajweedModel
        private lateinit var inner: ExactTajweedEngine
        private lateinit var promotedRecord: PromotionRecord
        private val key = AcceleratorKey("v4.0-test", "host", "host", "fake", "c16_l64")

        /** Engine threads (a phone runs 4: its big and mid cores). On a many-core desktop the engine's own default
         *  gives erratic chunk times (a barrier over every core). */
        private val THREADS = (TestKit.env("TJ_FAILOVER_THREADS") ?: "4").toInt()

        @BeforeClass
        @JvmStatic
        fun open() {
            if (!TajweedJni.available) return
            val d = (TestKit.env("TJ_FAILOVER_MODEL") ?: TestKit.env("TJ_SELFTEST_MODEL"))?.let(::File) ?: return
            if (!File(d, "selftest/selftest.json").isFile || !File(d, "layers.zfw").isFile) return
            dirOrNull = d
            model = TajweedModel.open(d, ManifestVerifier(listOf(TestKit.hex(ManifestTest.STAGED_TEST_KEY)), requireSignature = false))
            inner = ExactTajweedEngine(model.dir, "c16_l64", loadEngine = true, threads = THREADS)
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (dirOrNull != null) inner.close()
        }
    }

    private val dirs = ArrayList<File>()
    private val latches = Collections.synchronizedList(ArrayList<CountDownLatch>())
    private val failovers = Collections.synchronizedList(ArrayList<AcceleratorFailoverEvent>())
    private val promotions = Collections.synchronizedList(ArrayList<PromotionEvent>())
    private val observer = object : SdkObserver {
        override fun onFailover(event: AcceleratorFailoverEvent) { failovers.add(event) }
        override fun onPromotion(event: PromotionEvent) { promotions.add(event) }
    }

    @Before
    fun setUp() {
        Assume.assumeTrue("the JNI engine and a decoded V4 folder with selftest/ are needed (TJ_FAILOVER_MODEL)", dirOrNull != null)
        SdkEvents.add(observer)
    }

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir() = Files.createTempDirectory("tj-fo-").toFile().also { dirs.add(it) }

    data class Fail(val mode: String, val at: Int)

    /** The fake accelerator's stream: the real CPU engine's chunks, failing at chunk [Fail.at]. Before it fails it closes
     *  its engine stream (the engine allows one per process; a real NPU holds none, so the CPU can open its own). */
    private inner class FakeAccStream(private val fail: Fail?, private val hang: CountDownLatch) : TajweedEngineStream {
        private var s: TajweedEngineStream? = inner.createStream()
        private var made = 0
        override val backend: Backend get() = Backend.NPU

        private fun check(got: List<TajweedHeads>): List<TajweedHeads> {
            val f = fail ?: return got
            val out = ArrayList<TajweedHeads>()
            for (h in got) {
                val i = made++
                if (i != f.at) { out.add(h); continue }
                s?.close()
                s = null
                when (f.mode) {
                    "exception" -> throw IllegalStateException("the fake accelerator failed at chunk $i")
                    "hang" -> { hang.await(60, TimeUnit.SECONDS); throw IllegalStateException("released") }
                    "nan" -> { h[TajweedHead.LOG_PROBS]!![7] = Float.NaN; out.add(h) }
                    "range" -> { h[TajweedHead.LOG_PROBS]!![7] = 4f; out.add(h) }
                }
                return out
            }
            return out
        }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> =
            check(s?.pushAudio(samples, offset, length) ?: throw IllegalStateException("closed"))

        override fun finish(): List<TajweedHeads> = check(s?.finish() ?: throw IllegalStateException("closed"))
        override fun reset() { s?.reset(); made = 0 }
        override fun close() { s?.close(); s = null }
    }

    private var nextFail: Fail? = null

    /** When set, the fake accelerator replays these chunks instead of running the engine ([Replayed]). */
    private var nextReplay: (() -> TajweedEngineStream)? = null

    /** The real CPU engine's chunks of [audio] and the input samples each needed (pushed 16 samples at a time); the
     *  chunks after the last listed sample count are the flush. */
    private class Precomputed(val chunks: List<TajweedHeads>, val need: LongArray)

    private fun precompute(audio: FloatArray): Precomputed {
        val chunks = ArrayList<TajweedHeads>()
        val need = ArrayList<Long>()
        inner.createStream().use { s ->
            var at = 0
            while (at < audio.size) {
                val k = minOf(16, audio.size - at)
                val got = s.pushAudio(audio, at, k)
                at += k
                for (g in got) { chunks += g; need += at.toLong() }
            }
            chunks += s.finish()
        }
        return Precomputed(chunks, need.toLongArray())
    }

    /** [h] with every frame's best token the blank (log_probs only; the other heads as they were). */
    private fun blanked(h: TajweedHeads): TajweedHeads {
        val m = HashMap<TajweedHead, FloatArray>()
        for (k in h.available) m[k] = h[k]!!.copyOf()
        val lp = m.getValue(TajweedHead.LOG_PROBS)
        val w = TajweedHead.LOG_PROBS.width
        for (t in 0 until h.n40) for (j in 0 until w) lp[t * w + j] = if (j == TajweedModel.BLANK) -0.001f else -12f
        return TajweedHeads(h.n40, m)
    }

    /**
     * An accelerator that replays the real CPU engine's chunks computed beforehand ([precompute]), blank over [blank]:
     * it holds no engine stream (a real NPU holds none), so the CPU engine can check it while it serves.
     */
    private class Replayed(private val p: Precomputed, private val blank: IntRange?, private val blanked: (TajweedHeads) -> TajweedHeads) : TajweedEngineStream {
        private var got = 0L
        private var next = 0
        override val backend: Backend get() = Backend.NPU
        private fun emit(i: Int) = if (blank != null && i in blank) blanked(p.chunks[i]) else p.chunks[i]
        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            got += length
            val out = ArrayList<TajweedHeads>()
            while (next < p.need.size && p.need[next] <= got) out += emit(next++)
            return out
        }
        override fun finish(): List<TajweedHeads> = (next until p.chunks.size).map { emit(it) }.also { next = p.chunks.size }
        override fun reset() { got = 0; next = 0 }
        override fun close() {}
    }

    private inner class FakePrepared : PreparedAccelerator {
        private val dir = File(model.dir, SelfTestSet.DIR)
        private val set = SelfTestSet.read(dir)
        override val chunkMs = 320
        override val headsBackend = TajweedBackend.INT8
        override val expected = SelfTestSet.readExpected(dir, "c16_l64")
        override val planted = set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id }.toSet()
        override fun warmup() {}
        override fun golden(abort: () -> Boolean): GoldenRun {
            val cpu = SelfTestRunner.run({ inner.createStream() }, inner, model.calibration, "c16_l64", set, dir, TajweedBackend.INT8, abort)
            val acc = SelfTestRunner.run({ FakeAccStream(null, CountDownLatch(0)) }, inner, model.calibration, "c16_l64", set, dir, TajweedBackend.INT8, abort)
            return GoldenRun(acc, cpu)
        }
        override fun stream(heads: Set<TajweedHead>, sampleRate: Int): TajweedEngineStream =
            nextReplay?.invoke() ?: FakeAccStream(nextFail, CountDownLatch(1).also { latches.add(it) })
        override fun close() {}
    }

    private val env = object : AcceleratorEnvironment {
        override val osBuild = "host"
        override val deviceModel = "host"
        override val soc = "fake"
        override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) = work()
    }

    private val prep = object : AcceleratorPreparer {
        override fun unavailable(key: AcceleratorKey): String? = null
        override fun download(key: AcceleratorKey) {}
        override fun compile(key: AcceleratorKey): PreparedAccelerator = FakePrepared()
    }

    /** A controller already promoted for [key] (the record of [promoteThroughTheGoldenTest]): it loads and is Ready. */
    private fun promoted(): AcceleratorController {
        if (!recordReady) promoteOnce()
        val store = dir()
        AcceleratorStore(store).also { it.sessionStarted = true; it.put(key, promotedRecord) }
        return AcceleratorController(env, AcceleratorStore(store), prep, key).also { it.start() }.also {
            assertTrue(it.state.value.toString(), it.state.value is AcceleratorState.Ready)
        }
    }

    private var recordReady = false

    private fun promoteOnce() {
        val store = dir()
        AcceleratorStore(store).sessionStarted = true
        val acc = AcceleratorController(env, AcceleratorStore(store), prep, key)
        acc.start()
        assertTrue(acc.state.value.toString(), acc.state.value is AcceleratorState.Ready)
        promotedRecord = AcceleratorStore(store).record(key)!!
        recordReady = true
        acc.close()
    }

    /** The golden stage with the real self-test clips: the CPU engine against the shipped expected file, the (fake)
     *  accelerator against the CPU, then promoted whatever the speed. */
    @Test
    fun theGoldenStagePromotesWithTheRealSelfTestClips() {
        promoteOnce()
        val e = promotions.single()
        assertTrue(e.promoted)
        println(String.format(Locale.ROOT, "golden (real clips, 5): promoted; speedup %.2f, accelerator median %.1f ms p90 %.1f ms -> deadline %.0f ms",
            promotedRecord.speedup, promotedRecord.accMedianMs, promotedRecord.accP90Ms, Failover.deadlineMs(promotedRecord.accMedianMs, promotedRecord.accP90Ms)))
    }

    private class Clip(val id: String, val surah: Int, val ayah: Int, val audio: FloatArray)

    private fun selftestClips(): List<Clip> {
        val dir = File(model.dir, SelfTestSet.DIR)
        return SelfTestSet.read(dir).clips.map { Clip(it.id, it.surah, it.ayah, SelfTestSet.samples(File(dir, it.file))) }
    }

    private class Run(val chunks: List<TajweedChunk>, val heads: TajweedHeads, val result: TajweedResult)

    /** One session over [c], pushed in 100 ms pieces as a microphone would. */
    private fun session(rec: TajweedRecognizer, c: Clip): Run = runBlocking {
        val s = rec.startSession()
        val got = async { s.chunks.toList() }
        var at = 0
        while (at < c.audio.size) {
            val k = minOf(1600, c.audio.size - at)
            s.append(c.audio, at, k)
            at += k
        }
        val heads = s.finish()
        Run(got.await(), heads, rec.score(heads, c.surah, c.ayah))
    }

    private fun cpuOnly(c: Clip): Pair<IntArray, TajweedResult> {
        val heads = inner.createStream().use { s -> TajweedHeads.concat(s.pushAudio(c.audio, 0, c.audio.size) + s.finish()) }
        val config = TajweedConfig(app = TajweedOptions(context = "c16_l64"), backend = TajweedBackend.INT8)
        return heads.greedy() to inner.score(heads, config, config.resolve(model.calibration), c.surah, c.ayah)
    }

    private class Div(var takes: Int = 0, var exact: Int = 0, var tokenTakes: Int = 0, var edits: Int = 0, var tokens: Int = 0,
                      var siteDiffs: Int = 0, var wordDiffs: Int = 0, var siteTakes: Int = 0, var wordTakes: Int = 0)

    private fun failoverRun(c: Clip, fail: Fail, warm: Boolean, div: Div?, base: Pair<IntArray, TajweedResult>?,
                            windowMs: Int = Failover.REPLAY_WINDOW_MS): AcceleratorFailoverEvent {
        nextFail = fail
        failovers.clear()
        val acc = promoted()
        acc.replayWindowMs = windowMs
        val rec = TajweedRecognizer.open(model, TajweedRecognizer.Options(context = "c16_l64", accelerator = acc, soc = "fake", threads = THREADS))
        rec.releaseCpuForAccelerator = !warm
        try {
            val r = session(rec, c)
            val tag = "${c.id} ${fail.mode} at ${fail.at} (failovers: ${failovers.map { "${it.failover.cause}@${it.failover.chunk} ${it.failover.elapsedMs} ms" }}, " +
                "backends ${r.chunks.map { it.backend.key.first() }.joinToString("")})"
            val n = r.heads.n40 / 8 + (if (r.heads.n40 % 8 != 0) 1 else 0)
            assertEquals(tag, (r.chunks.indices).toList(), r.chunks.map { it.index })
            val f = failovers.single()
            // the failed call's first chunk: a call completes one chunk, except the end of the take (its flush, up to four)
            val at = f.failover.chunk
            assertTrue(tag, at <= fail.at && at >= fail.at - 4)
            // a chunk is labelled with its call's backend: good chunks of the failing call (the flush) read CPU too
            assertTrue(tag, r.chunks.take(maxOf(0, at - 3)).all { it.backend == Backend.NPU })
            assertTrue(tag, r.chunks.drop(at).all { it.backend == Backend.CPU })
            assertEquals(tag, warm, f.failover.warm)
            // every word of the ayah decoded: the scorer read every expected word
            val words = rec.expectedWordsBlocking(c.surah, c.ayah)
            assertEquals(tag, words, r.result.words.size)
            if (base != null && div != null) {
                val t = r.heads.greedy()
                val ed = SelfTest.editDistance(base.first, t)
                fun <T> d(a: Set<T>, b: Set<T>) = (a - b).size + (b - a).size
                val sd = d(base.second.sites.filter { it.flagged }.map { it.position to it.siteClass }.toSet(),
                    r.result.sites.filter { it.flagged }.map { it.position to it.siteClass }.toSet())
                val wd = d(base.second.words.filter { it.flagged }.map { it.word }.toSet(), r.result.words.filter { it.flagged }.map { it.word }.toSet())
                div.takes++
                if (f.failover.exact) div.exact++
                if (ed > 0) div.tokenTakes++
                div.edits += ed
                div.tokens += base.first.size
                div.siteDiffs += sd
                div.wordDiffs += wd
                if (sd > 0) div.siteTakes++
                if (wd > 0) div.wordTakes++
                if (f.failover.exact) assertEquals("$tag: a replay from the take's start is exact", 0, ed + sd + wd)
            }
            // later sessions: on the CPU after a demotion (an exception: retried later; NaN, out of range: off for good);
            // a single missed limit demotes nothing, and the next take is on the CPU only while the late call runs
            when (fail.mode) {
                "hang" -> assertTrue(tag, acc.state.value is AcceleratorState.Ready)
                "exception" -> assertFalse(tag, (acc.state.value as AcceleratorState.Disabled).persistent)
                else -> assertTrue(tag, (acc.state.value as AcceleratorState.Disabled).persistent)
            }
            val again = session(rec, c)
            assertTrue(again.chunks.all { it.backend == Backend.CPU })
            if (fail.mode == "hang") assertEquals(Reasons.BUSY, rec.backendChoice.reason)
            assertTrue(n > 0)
            return f
        } finally {
            rec.close()
            acc.close()
        }
    }

    private fun TajweedRecognizer.expectedWordsBlocking(s: Int, a: Int): Int = runBlocking { (expectedWords(s, a).maxOrNull() ?: -1) + 1 }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    /** Every mode at the first, a middle and the last chunk of every self-test clip (cold standby, as shipped). */
    @Test
    fun aFailureAtTheFirstMiddleAndLastChunkOfEverySelfTestClip() {
        val telemetry = Telemetry(dir())
        val feed = object : SdkObserver {
            override fun onFailover(event: AcceleratorFailoverEvent) = telemetry.failover(event)
        }
        SdkEvents.add(feed)
        val div = Div()
        val stalls = HashMap<String, ArrayList<Double>>()
        try {
            for (c in selftestClips()) {
                val base = cpuOnly(c)
                val n = inner.createStream().use { s -> (s.pushAudio(c.audio, 0, c.audio.size) + s.finish()).size }
                for (mode in listOf("exception", "nan", "range", "hang")) for (at in listOf(0, n / 2, n - 1)) {
                    val f = failoverRun(c, Fail(mode, at), warm = false, div = div, base = base)
                    stalls.getOrPut(mode) { ArrayList() }.add(f.failover.stallMs)
                    if (mode == "hang") assertTrue("deadline ${f.failover.deadlineMs} ms", f.failover.elapsedMs >= f.failover.deadlineMs)
                }
            }
        } finally {
            SdkEvents.remove(feed)
        }
        println("self-test clips, failover at the first / a middle / the last chunk, 4 causes, cold standby:")
        for ((m, v) in stalls) println("  $m: ${v.size} takes, stall median ${fmt(median(v))} ms, max ${fmt(v.max())} ms")
        println("  divergence vs CPU-only: ${div.takes} takes (${div.exact} replayed from the take's start: exact), " +
            "${div.tokenTakes} with a token difference (${div.edits} edits of ${div.tokens} tokens), " +
            "${div.siteTakes} with a site flag difference (${div.siteDiffs}), ${div.wordTakes} with a word flag difference (${div.wordDiffs})")
        // the telemetry counted every failover with its cause and stall
        telemetry.seal(TelemetryContext(AppIdentity("app.test", "1.0"), DeviceProfile("host", null, 15, "15")))
        val body = telemetry.pendingReports().single().readText()
        @Suppress("UNCHECKED_CAST")
        val p = ((Json.parse(body) as Map<String, Any?>)["promotions"] as List<Map<String, Any?>>).single()
        val total = stalls.values.sumOf { it.size }
        assertEquals(total.toDouble(), p["failovers"] as Double, 0.0)
        assertEquals(total.toDouble(), (p["failover_stall_ms"] as Map<String, Double>).values.sum(), 0.0)
        assertEquals(setOf("exception", "non_finite", "out_of_range", "deadline"), (p["failover_causes"] as Map<String, Any?>).keys)
    }

    /** The stall, warm (weights loaded, a kept stream) and cold (weights released, as shipped), repeated: medians. */
    @Test
    fun theStallWarmAndCold() {
        val c = selftestClips().maxBy { it.audio.size }
        val n = inner.createStream().use { s -> (s.pushAudio(c.audio, 0, c.audio.size) + s.finish()).size }
        val rounds = (TestKit.env("TJ_FAILOVER_ROUNDS") ?: "7").toInt()
        for (at in listOf(0, n / 2, n - 1)) {
            val warm = ArrayList<Double>()
            val cold = ArrayList<Double>()
            var replayed = 0
            var failed = 0
            repeat(rounds) {
                warm += failoverRun(c, Fail("exception", at), warm = true, div = null, base = null).failover.stallMs
                val e = failoverRun(c, Fail("exception", at), warm = false, div = null, base = null).failover
                cold += e.stallMs
                replayed = e.replayedChunks
                failed = e.chunk
            }
            println("stall, ${c.id} failing at chunk $failed of $n ($replayed chunks replayed, then the failed call's), $rounds rounds: " +
                "warm median ${fmt(median(warm))} ms (min ${fmt(warm.min())}), cold median ${fmt(median(cold))} ms (min ${fmt(cold.min())})")
        }
        // the CPU's own chunk time on this host, for the phone estimates
        val times = ArrayList<Double>()
        repeat(3) {
            inner.createStream().use { s ->
                var at = 0
                while (at < c.audio.size) {
                    val k = minOf(5120, c.audio.size - at)
                    val t0 = System.nanoTime()
                    val got = s.pushAudio(c.audio, at, k)
                    if (got.isNotEmpty()) times += (System.nanoTime() - t0) / 1e6 / got.size
                    at += k
                }
            }
        }
        val t0 = System.nanoTime()
        val e = ExactTajweedEngine(model.dir, "c16_l64", loadEngine = true, threads = THREADS)
        val openMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        e.createStream(TajweedHead.SCORER_HEADS).use { }
        val streamMs = (System.nanoTime() - t1) / 1e6
        e.close()
        println("this host: CPU chunk median ${fmt(median(times))} ms; engine open (weights in the page cache) ${fmt(openMs)} ms; first stream ${fmt(streamMs)} ms")
    }

    /** What the CPU standby costs while the accelerator serves: the process's memory with the weights loaded, released
     *  (a scoring handle only), and loaded again. */
    @Test
    fun theStandbysMemory() {
        fun mem(): Pair<Long, Long> {
            System.gc()
            Thread.sleep(200)
            return ProcessMemory.snapshot()
        }
        val base = mem()
        val e = ExactTajweedEngine(model.dir, "c16_l64", loadEngine = true, threads = THREADS)
        e.createStream(TajweedHead.SCORER_HEADS).use { s -> s.pushAudio(FloatArray(16_000), 0, 16_000) }
        val loaded = mem()
        e.close()
        val closed = mem()
        val scorer = ExactTajweedEngine(model.dir, "c16_l64", loadEngine = false)
        val scorerOnly = mem()
        scorer.close()
        fun mb(b: Long) = String.format(Locale.ROOT, "%.1f", b / 1e6)
        println("process memory (${ProcessMemory.what}): engine loaded +${mb(loaded.first - base.first)} MB private, +${mb(loaded.second - base.second)} MB resident; " +
            "after release ${mb(closed.first - base.first)} MB private; a scoring-only handle +${mb(scorerOnly.first - closed.first)} MB private")
    }

    private val blanks = Collections.synchronizedList(ArrayList<BlankConfirmedEvent>())
    private val blankObserver = object : SdkObserver {
        override fun onBlankConfirmed(event: BlankConfirmedEvent) { blanks.add(event) }
    }

    private fun basmalaClips() = selftestClips().filter { it.surah == 1 && it.ayah == 1 }

    /**
     * The blank rule with the real CPU engine checking: the accelerator (the CPU's own chunks, replayed) writes nothing over
     * speech from chunk 1 of each Basmala clip. The check (the real engine, its weights loaded for it) disagrees: the take
     * continues on the check's CPU stream from the first silent chunk with energy, the chunks from there are delivered again,
     * every word is decoded, and the transcript equals the CPU-only run (the check replayed from the take's start: exact).
     */
    @Test
    fun aBlankOverSpeechIsCaughtByTheRealCpuCheckFromTheFirstSuspiciousChunk() {
        val stalls = ArrayList<Double>()
        val checks = ArrayList<Double>()
        // a 1.28 s run: the Basmala clips are 2.7 to 5.1 s long, and recitation pauses (quiet audio is never suspicious)
        // break a 4 s run of speech; the shipped 4 s run with the real CPU is the held-tone test's
        for ((c, runMs) in basmalaClips().map { it to 1280 }) {
            val base = cpuOnly(c)
            val pre = precompute(c.audio)
            nextReplay = { Replayed(pre, 1 until pre.chunks.size, ::blanked) }
            failovers.clear()
            val acc = promoted()
            acc.blankRunMs = runMs
            val rec = TajweedRecognizer.open(model, TajweedRecognizer.Options(context = "c16_l64", accelerator = acc, soc = "fake", threads = THREADS))
            try {
                val r = session(rec, c)
                val tag = "${c.id} blank from chunk 1, run $runMs ms"
                val f = failovers.single().failover
                assertEquals(tag, Failover.BLANK, f.cause)
                // the first silent chunk with energy (a quiet onset or pause is no suspicion): chunk 1, or a little later
                assertTrue(tag + ": from chunk ${f.chunk}", f.chunk in 1..8)
                assertEquals(tag, f.chunk, r.chunks.first { it.replaces }.index)
                assertEquals(tag, Demotion.PAUSED, f.demotion)
                assertTrue(tag, f.divergence!!.tokensDiffer > 0)
                assertTrue(tag, f.exact)
                assertTrue(tag, r.chunks.any { it.replaces })
                assertEquals(tag, (0 until pre.chunks.size).toSet(), r.chunks.map { it.index }.toSet())
                // every word of the ayah decoded
                assertEquals(tag, rec.expectedWordsBlocking(c.surah, c.ayah), r.result.words.size)
                // the check replayed from the take's first sample: the transcript is the CPU-only run's
                assertEquals(tag, base.first.toList(), r.heads.greedy().toList())
                stalls += f.stallMs
                checks += f.elapsedMs
                println(String.format(Locale.ROOT, "%s: CPU check (cold: weights loaded, %d chunks replayed) %.1f ms off the audio path; switch from chunk %d %.1f ms; " +
                    "%d of %d tokens differed", tag, f.replayedChunks + (runMs / 320), f.elapsedMs, f.chunk, f.stallMs, f.divergence!!.tokensDiffer, f.divergence!!.tokensExpected))
            } finally {
                nextReplay = null
                rec.close()
                acc.close()
            }
        }
        println("blank over speech, the real CPU check: check median ${fmt(median(checks))} ms, switch median ${fmt(median(stalls))} ms (this host, $THREADS threads)")
    }

    /**
     * A held stretch: a Basmala clip followed by 6 s of a steady tone (energy, no speech; the self-test set has no long
     * madd clip). The accelerator (the CPU's own chunks, replayed) writes nothing over it; the real CPU, checking the same
     * window off the audio path, writes nothing either: no failover, no demotion, `blank_confirmed_ok`, and the CPU
     * engine closed again. Prints the check's time and the memory it holds while it runs.
     */
    @Test
    fun aHeldStretchTheRealCpuConfirmsStaysOnTheAccelerator() {
        val c0 = selftestClips().first { it.id == "Husary_001001" }
        val tone = FloatArray(6 * 16_000) { (0.05 * Math.sin(2 * Math.PI * 220.0 * it / 16_000)).toFloat() }
        val c = Clip(c0.id + "+tone", c0.surah, c0.ayah, c0.audio + tone)
        val pre = precompute(c.audio)
        // the stretch the CPU itself writes nothing over, with energy
        val blankRun = pre.chunks.indices.reversed().takeWhile { pre.chunks[it].greedy().isEmpty() }.size
        println("the CPU engine writes nothing over the last $blankRun chunks of ${c.id} (${pre.chunks.size} chunks); " +
            "the first chunk needs ${pre.need[0]} input samples, the second ${pre.need[1]}")
        Assume.assumeTrue("the CPU writes tokens over the tone", blankRun * 320 >= Failover.BLANK_RUN_MS)
        SdkEvents.add(blankObserver)
        try {
            for (warm in listOf(false, true)) {
                blanks.clear()
                failovers.clear()
                nextReplay = { Replayed(pre, null, ::blanked) }
                val acc = promoted()
                val rec = TajweedRecognizer.open(model, TajweedRecognizer.Options(context = "c16_l64", accelerator = acc, soc = "fake", threads = THREADS))
                rec.releaseCpuForAccelerator = !warm
                try {
                    val r = session(rec, c)
                    assertTrue(failovers.isEmpty())
                    assertTrue(r.chunks.all { it.backend == Backend.NPU })
                    assertTrue(acc.state.value is AcceleratorState.Ready)
                    val until = System.currentTimeMillis() + 10_000
                    while (blanks.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
                    val b = blanks.first().check
                    assertTrue(b.agreed)
                    assertEquals(!warm, !b.warm)
                    // the CPU engine closed again after a check that agreed (as it was: released while the accelerator serves)
                    if (!warm) assertFalse(rec.cpuStandby.warm)
                    println(String.format(Locale.ROOT, "held tone, CPU check %s: %.1f ms off the audio path (chunks %d..%d), CPU blank too: no switch",
                        if (warm) "warm (weights loaded)" else "cold (weights loaded for it, closed after)", b.ms, b.fromChunk, b.toChunk))
                } finally {
                    nextReplay = null
                    rec.close()
                    acc.close()
                }
            }
        } finally {
            SdkEvents.remove(blankObserver)
        }
    }

    /**
     * What a check holds while it runs (the CPU engine loaded and one stream, against the standby released while the
     * accelerator serves), and the warm-up's cost through the exact engine (one prediction of silence; on a phone it is one
     * accelerator chunk).
     */
    @Test
    fun theWarmUpAndTheChecksMemoryMeasured() {
        fun mem(): Pair<Long, Long> {
            System.gc()
            Thread.sleep(200)
            return ProcessMemory.snapshot()
        }
        fun mb(b: Long) = String.format(Locale.ROOT, "%.1f", b / 1e6)
        val c = selftestClips().first { it.id == "Husary_001001" }
        val pre = precompute(c.audio)
        // an accelerator session releases the CPU weights; then a blank over speech loads them for the check (kept: it disagreed)
        nextReplay = { Replayed(pre, null, ::blanked) }
        val acc = promoted()
        acc.blankRunMs = 1280
        val rec = TajweedRecognizer.open(model, TajweedRecognizer.Options(context = "c16_l64", accelerator = acc, soc = "fake", threads = THREADS))
        try {
            session(rec, c)
            val released = mem()
            assertFalse(rec.cpuStandby.warm)
            nextReplay = { Replayed(pre, 1 until pre.chunks.size, ::blanked) }
            session(rec, c)
            assertTrue(rec.cpuStandby.warm)
            val loaded = mem()
            println("a CPU check holds (${ProcessMemory.what}): +${mb(loaded.first - released.first)} MB private, +${mb(loaded.second - released.second)} MB resident " +
                "over the released standby (the engine's weights and one stream; the window copy is ${mb(4L * (16 + 4 + 1) * 5120)} MB)")
        } finally {
            nextReplay = null
            rec.close()
            acc.close()
        }
        // the warm-up through the exact engine as the accelerator (one stream opened, one chunk of silence)
        val acc2 = promoted()
        val rec2 = TajweedRecognizer.open(model, TajweedRecognizer.Options(context = "c16_l64", accelerator = acc2, soc = "fake", threads = THREADS))
        rec2.releaseCpuForAccelerator = false
        try {
            val times = ArrayList<Double>()
            val firsts = ArrayList<Double>()
            repeat(5) {
                runBlocking {
                    val s = rec2.startSession()
                    val got = async { s.chunks.toList() }
                    // the first chunk's audio arrives over 320 ms (and a little more: the front end's lookahead)
                    Thread.sleep(400)
                    times += rec2.acceleratorEngine!!.lastWarmUpMs
                    var at = 0
                    while (at < c.audio.size) {
                        val k = minOf(1600, c.audio.size - at)
                        s.append(c.audio, at, k)
                        at += k
                    }
                    s.finish()
                    firsts += 1000 * got.await().first().seconds
                }
            }
            val e = rec2.acceleratorEngine!!
            println("(${failovers.size} of the 5 takes switched: the exact engine as the accelerator, on a shared desktop)")
            println("warm-up through the exact engine as the accelerator (a stream opened, silence until one chunk ran): median " +
                "${fmt(median(times))} ms on the accelerator thread at take start (${e.warmUps.get()} warm-ups in 5 takes); " +
                "the first chunk then took median ${fmt(median(firsts))} ms (it did not wait for the warm-up)")
        } finally {
            rec2.close()
            acc2.close()
        }
    }

    /** The divergence over the engine's parity ayahs (`TJ_FAILOVER_AUDIO`), failing at the first, a middle and the last chunk. */
    @Test
    fun divergenceOverTheParityAyahs() {
        val audio = TestKit.env("TJ_FAILOVER_AUDIO")?.let(::File)
        Assume.assumeTrue("TJ_FAILOVER_AUDIO names the parity clips", audio != null && audio.isDirectory)
        val windows = (TestKit.env("TJ_FAILOVER_WINDOWS") ?: Failover.REPLAY_WINDOW_MS.toString()).split(",").map { it.trim().toInt() }
        for (w in windows) divergenceAt(audio!!, w)
    }

    private fun divergenceAt(audio: File, windowMs: Int) {
        val div = Div()
        val stalls = ArrayList<Double>()
        val byPos = mapOf("first" to Div(), "middle" to Div(), "last" to Div())
        for (f in audio.listFiles()!!.filter { it.name.endsWith(".pcm") }.sortedBy { it.name }) {
            val id = f.name.removeSuffix(".pcm")
            val sa = id.substringAfter('_')
            val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val c = Clip(id, sa.substring(0, 3).toInt(), sa.substring(3).toInt(), FloatArray(fb.remaining()).also { fb.get(it) })
            val base = cpuOnly(c)
            val n = inner.createStream().use { s -> (s.pushAudio(c.audio, 0, c.audio.size) + s.finish()).size }
            for ((pos, at) in listOf("first" to 0, "middle" to n / 2, "last" to n - 1)) {
                val d = byPos.getValue(pos)
                val before = Div(d.takes, d.exact, d.tokenTakes, d.edits, d.tokens, d.siteDiffs, d.wordDiffs, d.siteTakes, d.wordTakes)
                stalls += failoverRun(c, Fail("exception", at), warm = false, div = d, base = base, windowMs = windowMs).failover.stallMs
                div.takes++; div.exact += d.exact - before.exact; div.tokenTakes += d.tokenTakes - before.tokenTakes
                div.edits += d.edits - before.edits; div.tokens += d.tokens - before.tokens; div.siteDiffs += d.siteDiffs - before.siteDiffs
                div.wordDiffs += d.wordDiffs - before.wordDiffs; div.siteTakes += d.siteTakes - before.siteTakes; div.wordTakes += d.wordTakes - before.wordTakes
            }
        }
        for ((pos, d) in byPos + ("all" to div)) {
            println("parity ayahs, window $windowMs ms, failover at the $pos chunk: ${d.takes} takes (${d.exact} exact replays), ${d.tokenTakes} with a token difference " +
                "(${d.edits} edits of ${d.tokens} tokens), ${d.siteTakes} with a site flag difference (${d.siteDiffs}), " +
                "${d.wordTakes} with a word flag difference (${d.wordDiffs})")
        }
        println("parity ayahs, window $windowMs ms: stall median ${fmt(median(stalls))} ms, max ${fmt(stalls.max())} ms (cold, this host)")
    }
}

/** The process's memory: (private or anonymous bytes, resident bytes). Linux: /proc/self/status; Windows: the process's
 *  private bytes and working set; elsewhere zeros. */
internal object ProcessMemory {
    val what: String get() = when {
        File("/proc/self/status").isFile -> "Linux RssAnon / VmRSS"
        System.getProperty("os.name").lowercase().startsWith("windows") -> "Windows private bytes / working set"
        else -> "not measured"
    }

    fun snapshot(): Pair<Long, Long> {
        val st = File("/proc/self/status")
        if (st.isFile) {
            val m = st.readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            fun kb(k: String) = (m[k]?.substringBefore(' ')?.toLongOrNull() ?: 0L) * 1024
            return kb("RssAnon") to kb("VmRSS")
        }
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            val pid = ProcessHandle.current().pid()
            fun ps(field: String): Long {
                val p = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id $pid).$field").redirectErrorStream(true).start()
                val v = p.inputStream.bufferedReader().readText().trim().toLongOrNull() ?: 0L
                p.waitFor()
                return v
            }
            val out = listOf(ps("PrivateMemorySize64").toString(), ps("WorkingSet64").toString())
            return (out.getOrNull(0)?.toLongOrNull() ?: 0L) to (out.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        return 0L to 0L
    }
}
