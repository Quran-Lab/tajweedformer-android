package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
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
            FakeAccStream(nextFail, CountDownLatch(1).also { latches.add(it) })
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
            // later sessions start on the CPU
            assertEquals(Reasons.FAILOVER, (acc.state.value as AcceleratorState.Disabled).reason)
            val again = session(rec, c)
            assertTrue(again.chunks.all { it.backend == Backend.CPU })
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
