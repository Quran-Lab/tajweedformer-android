package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.Conditions
import ai.quranlab.tajweedformer.accel.Failover
import ai.quranlab.tajweedformer.accel.GoldenRun
import ai.quranlab.tajweedformer.accel.PreparedAccelerator
import ai.quranlab.tajweedformer.accel.PromotionRecord
import ai.quranlab.tajweedformer.accel.PromotionTimeouts
import ai.quranlab.tajweedformer.accel.Reasons
import ai.quranlab.tajweedformer.accel.StageAborted
import ai.quranlab.tajweedformer.accel.StageFailure
import ai.quranlab.tajweedformer.accel.StageWait
import ai.quranlab.tajweedformer.accel.Stages
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The promotion state machine and the in-session failover with fakes for the network, the files, the compiler, the
 * engines and the clock (no JNI): every stage succeeding, every failure at every stage with its reason, restarts in
 * the middle of each stage and the crash sentinel, OS build and revision changes, backoff and its cap, deterministic
 * failures never retried, conditions that wait without counting, a session aborting the golden stage, a slower build
 * promoted, the order (first install, sessions running at promotion, a later launch), and failovers at the first, a
 * middle and the last chunk for every cause.
 */
class AcceleratorTest {
    private val dirs = ArrayList<File>()
    private fun dir() = Files.createTempDirectory("tj-acc-").toFile().also { dirs.add(it) }
    private val choices = Collections.synchronizedList(ArrayList<BackendChoice>())
    private val promotions = Collections.synchronizedList(ArrayList<PromotionEvent>())
    private val failovers = Collections.synchronizedList(ArrayList<AcceleratorFailoverEvent>())
    private val latches = Collections.synchronizedList(ArrayList<CountDownLatch>())
    private val observer = object : SdkObserver {
        override fun onBackendSelected(choice: BackendChoice) { choices.add(choice) }
        override fun onPromotion(event: PromotionEvent) { promotions.add(event) }
        override fun onFailover(event: AcceleratorFailoverEvent) { failovers.add(event) }
    }

    @Before fun setUp() = SdkEvents.add(observer)

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    private val model: TajweedModel by lazy {
        val d = dir()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(d, files, TestKit.sign(TestKit.manifest(files)))
        TajweedModel.open(d, TestKit.verifier)
    }

    // ------------------------------------------------------------------------------------------------- fakes

    /** The platform with a clock: scheduled work runs when it is due ([runDue]). */
    private class FakeEnv(override var osBuild: String = "build-1") : AcceleratorEnvironment {
        override val deviceModel = "Google Pixel 10"
        override val deviceModelCode = "Pixel 10"
        override val osBuildReport = "16/BD1A.250101.001/12345678"
        override val soc = "tensor_g5"
        override val delegate = "LiteRtDispatch_GoogleTensor litert 2.2.0"
        var now = 1_000_000_000L
        var thermal = false
        var memory = false
        var denied = false
        var conditions = Conditions()
        var unmetered = true
        class Job(val at: Long, val unmetered: Boolean, val work: () -> Unit)
        val jobs = Collections.synchronizedList(ArrayList<Job>())
        override fun nowMs() = now
        override fun thermalSevere() = thermal
        override fun lowMemory() = memory
        override fun forcedBackend(revision: String) = if (denied) "exact" else null
        var accelDeny: String? = null
        override fun accelDenied(revision: String, accelerator: String) = accelDeny
        override fun conditions() = conditions
        override fun unmetered() = unmetered
        override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) { jobs.add(Job(now + delayMs, unmetered, work)) }

        /** Runs every job due now (and those they schedule, when due). Returns how many ran. */
        fun runDue(): Int {
            var n = 0
            while (true) {
                val j = synchronized(jobs) { jobs.firstOrNull { it.at <= now && (!it.unmetered || unmetered) }?.also { jobs.remove(it) } } ?: return n
                j.work()
                n++
            }
        }

        fun pendingDelays(): List<Long> = synchronized(jobs) { jobs.map { it.at - now } }
    }

    /** One chunk of 8 frames whose log_probs peak on [tok] (0 = blank); [nan] puts a NaN in conf; [range] a log-prob of +3. */
    private fun chunk(tok: Int, nan: Boolean = false, range: Boolean = false): TajweedHeads {
        val m = HashMap<TajweedHead, FloatArray>()
        for (h in TajweedHead.SCORER_HEADS + TajweedHead.CONF) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
        for (h in listOf(TajweedHead.LOG_PROBS, TajweedHead.INTER1_LOG_PROBS, TajweedHead.TIMING_LOG_PROBS)) {
            val lp = m.getValue(h)
            val frames = lp.size / 38
            for (t in 0 until frames) for (k in 0 until 38) lp[t * 38 + k] = if (k == tok) -0.01f else -8f
        }
        for (h in listOf(TajweedHead.GEM, TajweedHead.ATTR_QALQALAH)) { val v = m.getValue(h); for (i in v.indices step h.width) v[i] = 1f }
        if (nan) m.getValue(TajweedHead.CONF)[3] = Float.NaN
        if (range) m.getValue(TajweedHead.LOG_PROBS)[5] = 3f
        return TajweedHeads(8, m)
    }

    /** A stream that makes a chunk per 5120 samples with [tok] on even chunks; [fail] injects a failure at its chunk. */
    private inner class FakeStream(val backendKind: Backend, val tok: Int, val fail: Fail? = null, val hang: CountDownLatch? = null) : TajweedEngineStream {
        var pending = 0
        var made = 0
        override val backend: Backend get() = backendKind
        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            pending += length
            val out = ArrayList<TajweedHeads>()
            while (pending >= 5120) {
                pending -= 5120
                out.add(next())
            }
            return out
        }
        private fun next(): TajweedHeads {
            val i = made++
            val f = fail
            if (f != null && i >= f.at && (i == f.at || f.mode == "blank")) {
                when (f.mode) {
                    "exception" -> throw IllegalStateException("the fake NPU went away at chunk $i")
                    "hang" -> { hang!!.await(30, TimeUnit.SECONDS) }
                    "nan" -> return chunk(tok, nan = true)
                    "range" -> return chunk(tok, range = true)
                    "blank" -> return chunk(0)
                }
            }
            return chunk(if (i % 2 == 0) tok else 0)
        }
        override fun finish(): List<TajweedHeads> = if (pending > 0) listOf(next()).also { pending = 0 } else emptyList()
        override fun reset() { pending = 0; made = 0 }
        override fun close() {}
    }

    data class Fail(val mode: String, val at: Int)

    /** The CPU engine (tok 32), never released (a fake has no scoring-only handle). */
    private inner class CpuFake : TajweedEngine {
        override val chunkMs = 320
        var streams = 0
        override fun createStream(): TajweedEngineStream { streams++; return FakeStream(Backend.CPU, 32) }
        override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int) =
            TajweedResult(surah, ayah, settings, emptyList(), emptyList(), emptyList(), emptyList(), TajweedSummary(0, 0, 0, 0, 0, 0, 0, 0, 0.0, false))
        override fun expectedTokens(surah: Int, ayah: Int) = intArrayOf(31)
        override fun close() {}
    }

    private val clips = SelfTestSet.REQUIRED
    private val expected = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", clips.associateWith {
        SelfTestSet.ClipExpected(4, listOf(3, 32, 13), if (it == SelfTestSet.PLANTED_CLIP) setOf(5 to "vowel_sub") else emptySet(),
            if (it == SelfTestSet.PLANTED_CLIP) setOf(1) else emptySet(), listOf(1.0))
    })

    private fun obs(tokens: List<Int> = listOf(3, 32, 13), finite: Boolean = true, ms: Double = 10.0, sitesOf: (String) -> Set<Pair<Int, String>> = { expected.clips.getValue(it).flaggedSites }) =
        clips.associateWith { SelfTestSet.ClipObserved(4, tokens, sitesOf(it), expected.clips.getValue(it).flaggedWords, finite, List(4) { ms }) }

    /** The fake accelerator's behaviour per stage. */
    private inner class Behaviour {
        var download: () -> Unit = {}
        var compile: () -> Unit = {}
        var warmup: () -> Unit = {}
        var golden: ((() -> Boolean) -> GoldenRun) = { GoldenRun(obs(ms = 10.0), obs(ms = 40.0)) }
        var fail: Fail? = null
        val hang = CountDownLatch(1).also { latches.add(it) }
        val calls = Collections.synchronizedList(ArrayList<String>())
        var closes = 0
    }

    private inner class FakePreparer(val b: Behaviour = Behaviour(), var unavailable: String? = null) : AcceleratorPreparer {
        override fun unavailable(key: AcceleratorKey) = unavailable
        override fun download(key: AcceleratorKey) { b.calls.add(Stages.DOWNLOAD); b.download() }
        override fun compile(key: AcceleratorKey): PreparedAccelerator {
            b.calls.add(Stages.COMPILE)
            b.compile()
            return object : PreparedAccelerator {
                override val chunkMs = 320
                override val expected = this@AcceleratorTest.expected
                override val planted = setOf(SelfTestSet.PLANTED_CLIP)
                override fun warmup() { b.calls.add(Stages.WARMUP); b.warmup() }
                override fun golden(abort: () -> Boolean): GoldenRun { b.calls.add(Stages.GOLDEN); return b.golden(abort) }
                override fun stream(heads: Set<TajweedHead>, sampleRate: Int): TajweedEngineStream = FakeStream(Backend.NPU, 31, b.fail, b.hang)
                override fun close() { b.closes++ }
            }
        }
    }

    private val fast = PromotionTimeouts(compileMs = 3000, warmupMs = 3000, goldenMs = 3000, yieldMs = 1000)

    private fun key(rev: String = "r1", os: String = "build-1") = AcceleratorKey(rev, os, "Google Pixel 10", "tensor_g5", "c16_l64")

    private fun controller(env: FakeEnv, prep: FakePreparer, store: File, k: AcceleratorKey = key(os = env.osBuild), launch: String = "launch-1",
                           sessionSeen: Boolean = true): AcceleratorController {
        if (sessionSeen) AcceleratorStore(store).sessionStarted = true
        return AcceleratorController(env, AcceleratorStore(store), prep, k, fast, launch)
    }

    private fun recognizer(acc: AcceleratorController?, cpu: TajweedEngine = CpuFake()) =
        TajweedRecognizer.open(model, TajweedRecognizer.Options(accelerator = acc, soc = "tensor_g5", engine = { _, _, _ -> cpu }))

    /** A take of [chunks] chunks of audio at [amp] (energy for the blank rule); [between] runs after the first append. */
    private fun take(rec: TajweedRecognizer, chunks: Int = 3, amp: Float = 0f, between: (() -> Unit)? = null): Triple<List<TajweedChunk>, TajweedHeads, TajweedResult> = runBlocking {
        val s = rec.startSession()
        val got = async { s.chunks.toList() }
        repeat(chunks) { i ->
            s.append(FloatArray(5120) { amp * (if (it % 2 == 0) 1 else -1) })
            if (i == 0) between?.invoke()
        }
        val r = s.finishAndScore(1, 1)
        val c = got.await()
        Triple(c, TajweedHeads.concat(emptyList<TajweedHeads>()), r)
    }

    private fun record(store: File, k: AcceleratorKey = key()) = AcceleratorStore(store).record(k)

    // ------------------------------------------------------------------------------------------------- the stages

    @Test
    fun everyStageInOrderThenPromotedAndTheNextSessionUsesIt() {
        val env = FakeEnv()
        val store = dir()
        val p = FakePreparer()
        val acc = controller(env, p, store)
        acc.start()
        assertEquals(AcceleratorState.Preparing(Stages.DOWNLOAD), acc.state.value)
        assertTrue("nothing ran before the scheduled work", p.b.calls.isEmpty())
        env.runDue()
        assertEquals(listOf(Stages.DOWNLOAD, Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN), p.b.calls.toList())
        val ready = acc.state.value as AcceleratorState.Ready
        assertEquals(4.0, ready.speedup, 1e-9)
        val r = record(store)!!
        assertEquals(PromotionRecord.PROMOTED, r.status)
        assertEquals(Stages.PROMOTE, r.stage)
        assertEquals(1, r.attempt)
        assertEquals(0, r.failures)
        val e = promotions.single()
        assertTrue(e.promoted)
        assertEquals("npu:tensor_g5", e.backend)
        assertNotNull(e.compileMs)
        assertEquals(4.0, e.speedup!!, 1e-9)
        assertEquals(1, e.attempt)
        val rec = recognizer(acc)
        val (chunks, _, res) = take(rec)
        assertTrue(chunks.all { it.backend == Backend.NPU })
        assertEquals(Backend.NPU, res.backend)
        rec.close()
    }

    @Test
    fun aSlowerBuildIsPromotedAnyway() {
        val env = FakeEnv()
        val p = FakePreparer().also { it.b.golden = { GoldenRun(obs(ms = 90.0), obs(ms = 30.0)) } }
        val acc = controller(env, p, dir())
        acc.start()
        env.runDue()
        val s = acc.state.value as AcceleratorState.Ready
        assertEquals(1.0 / 3, s.speedup, 1e-9)
        assertEquals(1.0 / 3, promotions.single().speedup!!, 1e-9)
        // the deadline comes from the measured accelerator times: max(4 x 90, 2 x 90), capped at 300 ms
        assertEquals(300.0, acc.deadlineMs, 0.0)
    }

    @Test
    fun everyFailureAtEveryStageKeepsTheCpuWithItsReason() {
        data class Case(val stage: String, val reason: String, val set: Behaviour.() -> Unit)
        val cases = listOf(
            Case(Stages.DOWNLOAD, Reasons.NETWORK) { download = { throw StageFailure(Reasons.NETWORK, "connection reset") } },
            Case(Stages.DOWNLOAD, Reasons.STORAGE) { download = { throw java.io.IOException("No space left on device") } },
            Case(Stages.DOWNLOAD, Reasons.VERIFY) { download = { throw StageFailure(Reasons.VERIFY, "sha256 mismatch") } },
            Case(Stages.COMPILE, Reasons.COMPILE_ERROR) { compile = { throw IllegalStateException("dispatch refused the model") } },
            Case(Stages.COMPILE, Reasons.COMPILE_TIMEOUT) { compile = { Thread.sleep(6000) } },
            Case(Stages.WARMUP, Reasons.NON_FINITE) { warmup = { throw StageFailure(Reasons.NON_FINITE, "warm-up chunk 2: non_finite") } },
            Case(Stages.WARMUP, Reasons.LOAD_ERROR) { warmup = { throw IllegalStateException("no buffers") } },
            Case(Stages.WARMUP, Reasons.HANG) { warmup = { Thread.sleep(6000) } },
            Case(Stages.GOLDEN, Reasons.CPU_MISMATCH) { golden = { GoldenRun(obs(), obs(tokens = listOf(3, 32))) } },
            Case(Stages.GOLDEN, Reasons.GOLDEN_MISMATCH) { golden = { GoldenRun(obs(sitesOf = { emptySet() }), obs()) } },
            Case(Stages.GOLDEN, Reasons.NON_FINITE) { golden = { GoldenRun(obs(finite = false), obs()) } },
            Case(Stages.GOLDEN, Reasons.HANG) { golden = { Thread.sleep(6000); GoldenRun(obs(), obs()) } },
        )
        for (c in cases) {
            promotions.clear()
            val env = FakeEnv()
            val store = dir()
            val b = Behaviour().apply(c.set)
            val acc = controller(env, FakePreparer(b), store)
            acc.start()
            env.runDue()
            val s = acc.state.value as AcceleratorState.Disabled
            assertEquals("${c.stage}: ${s.detail}", c.reason, s.reason)
            val deterministic = c.reason in Reasons.DETERMINISTIC
            assertEquals(deterministic, s.persistent)
            val r = record(store)!!
            assertEquals(c.stage, r.failedStage)
            assertEquals(1, r.failures)
            assertEquals(if (deterministic) PromotionRecord.FINAL else PromotionRecord.FAILED, r.status)
            if (!deterministic) assertEquals(env.now + 3_600_000L, r.nextAttemptAtMs)
            val e = promotions.single()
            assertFalse(e.promoted)
            assertEquals(c.stage, e.stage)
            assertEquals(c.reason, e.reason)
            assertEquals("${c.stage}:${AcceleratorIncident.cause(c.reason)}", e.incident!!.code)
            assertEquals(AcceleratorIncident.PROMOTION_FAILED, e.incident!!.kind)
            if (c.reason == Reasons.GOLDEN_MISMATCH) {
                assertEquals(SelfTestSet.PLANTED_CLIP, e.incident!!.clip)
                assertEquals(1, e.incident!!.divergence!!.sitesDiffer.coerceAtLeast(e.incident!!.divergence!!.tokensDiffer).coerceAtMost(1))
            }
            // nothing else is scheduled in this launch
            env.now += 48 * 3_600_000L
            env.runDue()
            assertTrue(acc.state.value is AcceleratorState.Disabled)
        }
    }

    @Test
    fun theFiveGoldenClipsAreRequiredThePlantedOneIncluded() {
        val partial = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", expected.clips - SelfTestSet.PLANTED_CLIP)
        val v = SelfTestSet.judgeGolden(partial, obs(), obs(), setOf(SelfTestSet.PLANTED_CLIP))
        assertEquals(Reasons.CPU_MISMATCH, v.reason)
        assertTrue(v.detail, v.detail.contains("missing_clip"))
        assertEquals(5, SelfTestSet.REQUIRED.size)
        assertTrue(SelfTestSet.PLANTED_CLIP in SelfTestSet.REQUIRED)
        // and a missed planted flag is a golden mismatch on that clip
        val missed = SelfTestSet.judgeGolden(expected, obs(), obs(sitesOf = { emptySet() }), setOf(SelfTestSet.PLANTED_CLIP))
        assertEquals(Reasons.GOLDEN_MISMATCH, missed.reason)
        assertEquals(SelfTestSet.PLANTED_CLIP, missed.clip)
        assertEquals(1, missed.divergence!!.sitesDiffer)
    }

    // ------------------------------------------------------------------------------------------------- restarts

    @Test
    fun aRestartResumesAtTheStageAfterTheLastOneRecorded() {
        for ((done, wants) in listOf(
            "" to listOf(Stages.DOWNLOAD, Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN),
            Stages.DOWNLOAD to listOf(Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN),
            // a stage after compile needs the program loaded again (not recorded as a compile)
            Stages.COMPILE to listOf(Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN),
            Stages.WARMUP to listOf(Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN),
            Stages.GOLDEN to listOf(Stages.COMPILE, Stages.WARMUP),
            Stages.MEASURE to listOf(Stages.COMPILE, Stages.WARMUP),
        )) {
            promotions.clear()
            val store = dir()
            AcceleratorStore(store).put(key(), PromotionRecord(stage = done, attempt = 1, launch = "launch-0", compileMs = 1234,
                cpuMedianMs = 40.0, accMedianMs = 10.0, accP90Ms = 12.0))
            val env = FakeEnv()
            val p = FakePreparer()
            val acc = controller(env, p, store, launch = "launch-1")
            acc.start()
            assertEquals(AcceleratorState.Preparing(Stages.after(done)), acc.state.value)
            env.runDue()
            assertEquals("after $done", wants, p.b.calls.toList())
            assertTrue(acc.state.value is AcceleratorState.Ready)
            val r = record(store)!!
            assertEquals(PromotionRecord.PROMOTED, r.status)
            assertEquals("the same attempt", 1, r.attempt)
            val e = promotions.single()
            assertTrue(e.promoted)
            if (done.isNotEmpty() && done != Stages.DOWNLOAD) assertEquals(1234L, e.compileMs)
        }
    }

    @Test
    fun theCrashSentinelRecordsACrashAtItsStageNeverRetried() {
        for (stage in listOf(Stages.COMPILE, "load", Stages.WARMUP, Stages.GOLDEN, Stages.SESSION)) {
            promotions.clear()
            val store = dir()
            val s = AcceleratorStore(store)
            s.put(key(), PromotionRecord(stage = Stages.DOWNLOAD, attempt = 1, launch = "launch-0"))
            s.armSentinel(key(), stage)
            val env = FakeEnv()
            val p = FakePreparer()
            val acc = controller(env, p, store)
            acc.start()
            val d = acc.state.value as AcceleratorState.Disabled
            assertEquals(Reasons.CRASH, d.reason)
            assertTrue(d.persistent)
            assertNull("the sentinel is gone", s.leftSentinel())
            env.runDue()
            assertTrue("nothing runs for this key", p.b.calls.isEmpty())
            val e = promotions.single()
            assertEquals(Reasons.CRASH, e.reason)
            assertEquals(stage != Stages.SESSION, e.countsAttempt)
            assertEquals(if (stage == Stages.SESSION) AcceleratorIncident.FAILOVER else AcceleratorIncident.PROMOTION_FAILED, e.incident!!.kind)
            assertEquals("crash_on_last_run", e.incident!!.cause)
            // the next launch: still off, nothing reported twice
            controller(FakeEnv(), FakePreparer(), store, launch = "launch-2").also { it.start() }
                .let { assertEquals(Reasons.CRASH, (it.state.value as AcceleratorState.Disabled).reason) }
            assertEquals(1, promotions.size)
            // an OS update or a new model revision is a new key: a fresh pipeline
            val os = FakeEnv(osBuild = "build-2")
            controller(os, FakePreparer(), store, key(os = "build-2"), launch = "launch-3").also { it.start(); os.runDue() }
                .let { assertTrue(it.state.value is AcceleratorState.Ready) }
            val rev = FakeEnv()
            controller(rev, FakePreparer(), store, key(rev = "r2"), launch = "launch-4").also { it.start(); rev.runDue() }
                .let { assertTrue(it.state.value is AcceleratorState.Ready) }
        }
    }

    @Test
    fun theSentinelIsArmedAroundCompileWarmupAndGolden() {
        val store = dir()
        val seen = Collections.synchronizedList(ArrayList<String>())
        val s = AcceleratorStore(store)
        val b = Behaviour().apply {
            compile = { seen.add("compile:" + s.leftSentinel()?.second) }
            warmup = { seen.add("warmup:" + s.leftSentinel()?.second) }
            golden = { seen.add("golden:" + s.leftSentinel()?.second); GoldenRun(obs(ms = 10.0), obs(ms = 40.0)) }
        }
        val env = FakeEnv()
        controller(env, FakePreparer(b), store).also { it.start(); env.runDue() }
        assertEquals(listOf("compile:compile", "warmup:warmup", "golden:golden"), seen.toList())
        assertNull(s.leftSentinel())
    }

    // ------------------------------------------------------------------------------------------------- retries

    @Test
    fun backoffOneSixTwentyFourHoursThenNoMoreAndOneAttemptPerLaunch() {
        val store = dir()
        val b = Behaviour().apply { compile = { throw IllegalStateException("no") } }
        val env = FakeEnv()
        fun launch(n: Int) = controller(env, FakePreparer(b), store, launch = "launch-$n").also { it.start() }
        // attempt 1
        launch(1).also { env.runDue() }
        assertEquals(1, record(store)!!.attempt)
        assertEquals(listOf(3_600_000L), listOf(record(store)!!.nextAttemptAtMs - env.now))
        // the same launch never tries again, even when the time comes
        env.now += 2 * 3_600_000L
        assertEquals(0, env.runDue())
        assertEquals(1, record(store)!!.attempt)
        // launch 2, early: waits for its time (scheduled), then attempt 2
        env.now -= 2 * 3_600_000L - 1_800_000L
        val l2 = launch(2)
        assertEquals(listOf(1_800_000L), env.pendingDelays())
        assertTrue((l2.state.value as AcceleratorState.Disabled).retryAtMs > env.now)
        env.now += 1_800_000L
        env.runDue()
        assertEquals(2, record(store)!!.attempt)
        assertEquals(6 * 3_600_000L, record(store)!!.nextAttemptAtMs - env.now)
        env.now += 6 * 3_600_000L
        launch(3).also { env.runDue() }
        assertEquals(3, record(store)!!.attempt)
        assertEquals(24 * 3_600_000L, record(store)!!.nextAttemptAtMs - env.now)
        env.now += 24 * 3_600_000L
        launch(4).also { env.runDue() }
        assertEquals(4, record(store)!!.attempt)
        assertEquals(PromotionRecord.FINAL, record(store)!!.status)
        // the cap: no more for this key
        env.now += 100 * 3_600_000L
        val l5 = launch(5)
        env.runDue()
        assertEquals(4, record(store)!!.attempt)
        assertTrue((l5.state.value as AcceleratorState.Disabled).persistent)
        assertEquals(4, b.calls.count { it == Stages.COMPILE })
        assertEquals(4, promotions.size)
        assertEquals(listOf(1, 2, 3, 4), promotions.map { it.attempt })
    }

    @Test
    fun deterministicFailuresAreNeverRetriedForTheKey() {
        for (reason in listOf(Reasons.GOLDEN_MISMATCH, Reasons.CPU_MISMATCH, Reasons.NON_FINITE)) {
            val store = dir()
            val b = Behaviour().apply { golden = { throw StageFailure(reason, "x") } }
            val env = FakeEnv()
            controller(env, FakePreparer(b), store, launch = "a").also { it.start(); env.runDue() }
            env.now += 1000L * 3_600_000L
            controller(env, FakePreparer(b), store, launch = "b").also { it.start(); env.runDue() }
            assertEquals(1, b.calls.count { it == Stages.GOLDEN })
            assertEquals(PromotionRecord.FINAL, record(store)!!.status)
        }
    }

    @Test
    fun conditionsWaitWithoutCountingAFailure() {
        for ((set, what) in listOf<Pair<(FakeEnv) -> Unit, String>>(
            { e: FakeEnv -> e.conditions = Conditions(batteryPct = 15, charging = false) } to "battery",
            { e: FakeEnv -> e.conditions = Conditions(powerSave = true) } to "power_save",
            { e: FakeEnv -> e.conditions = Conditions(thermal = "moderate") } to "thermal",
            { e: FakeEnv -> e.unmetered = false } to "unmetered_network",
        )) {
            val store = dir()
            val env = FakeEnv()
            set(env)
            val b = Behaviour().apply { download = { if (!env.unmetered) throw StageWait("unmetered_network") } }
            val acc = controller(env, FakePreparer(b), store)
            acc.start()
            env.runDue()
            assertEquals(AcceleratorState.Preparing(Stages.DOWNLOAD, what), acc.state.value)
            assertEquals(0, record(store)?.failures ?: 0)
            assertTrue(promotions.isEmpty())
            assertEquals(listOf(fast.recheckMs), env.pendingDelays())
            // low battery but charging is fine
            env.conditions = Conditions(batteryPct = 15, charging = true)
            env.unmetered = true
            env.now += fast.recheckMs
            env.runDue()
            assertTrue(what, acc.state.value is AcceleratorState.Ready)
            promotions.clear()
        }
    }

    @Test
    fun aSessionStartingDuringGoldenAbortsItWithoutAFailureAndItRunsAgainAfter() {
        val env = FakeEnv()
        val store = dir()
        val entered = CountDownLatch(1)
        var goldens = 0
        val b = Behaviour().apply {
            golden = { abort ->
                goldens++
                if (goldens == 1) {
                    entered.countDown()
                    val until = System.currentTimeMillis() + 5_000
                    while (!abort() && System.currentTimeMillis() < until) Thread.sleep(2)
                    if (abort()) throw StageAborted()
                }
                GoldenRun(obs(ms = 10.0), obs(ms = 40.0))
            }
        }
        val acc = controller(env, FakePreparer(b), store)
        acc.start()
        val t = Thread { env.runDue() }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val rec = recognizer(acc)
        val (chunks, _, _) = take(rec)
        t.join(5_000)
        assertTrue(chunks.all { it.backend == Backend.CPU })
        // the take ended: the golden stage runs again, and passes
        env.runDue()
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertEquals(0, record(store)!!.failures)
        assertEquals(1, promotions.size)
        assertTrue(promotions.single().promoted)
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        rec.close()
    }

    @Test
    fun theDenylistAndAnUnavailableAcceleratorKeepTheCpuWithoutAnAttempt() {
        val env = FakeEnv().also { it.denied = true }
        val p = FakePreparer()
        controller(env, p, dir()).also { it.start(); env.runDue() }.let {
            assertEquals(Reasons.DENYLIST, (it.state.value as AcceleratorState.Disabled).reason)
        }
        assertTrue(p.b.calls.isEmpty())
        val none = controller(FakeEnv(), FakePreparer(unavailable = Reasons.NO_BUILD), dir())
        none.start()
        assertEquals(AcceleratorState.Cpu(Reasons.NO_BUILD), none.state.value)
        assertEquals(AcceleratorState.Cpu(Reasons.NO_BUILD), runBlocking { none.awaitReady(1000) })
        // the deny was counted once (reasons.denylist), no attempt and no incident; nothing for an unavailable build
        val e = promotions.single()
        assertEquals(Reasons.DENYLIST, e.reason)
        assertFalse(e.countsAttempt)
        assertNull(e.incident)
    }

    /** `accel_deny`: no promotion while it matches (counted once, no incident); a promoted key goes back to the CPU at the
     *  next session boundary, a session in progress stays on the accelerator, no failover and no `demoted`; lifted, the
     *  accelerator serves again. */
    @Test
    fun anAccelDenyRuleActsAtSessionBoundariesOnly() {
        val env = FakeEnv().also { it.accelDeny = "dispatch bug" }
        val p = FakePreparer()
        val before = controller(env, p, dir())
        before.start()
        env.runDue()
        assertEquals(Reasons.DENYLIST, (before.state.value as AcceleratorState.Disabled).reason)
        assertTrue("no stage ran", p.b.calls.isEmpty())
        assertEquals(1, promotions.size)
        promotions.clear()
        // promoted, then the rule arrives during a session
        val env2 = FakeEnv()
        val acc = controller(env2, FakePreparer(), dir())
        acc.start()
        env2.runDue()
        promotions.clear()
        val rec = recognizer(acc)
        val (during, _, _) = take(rec, chunks = 4) { env2.accelDeny = "dispatch bug" }
        assertTrue("the session in progress stays on the accelerator", during.all { it.backend == Backend.NPU })
        assertTrue(take(rec).first.all { it.backend == Backend.CPU })
        assertEquals(Reasons.DENYLIST, rec.backendChoice.reason)
        take(rec)
        val e = promotions.single()
        assertEquals(Reasons.DENYLIST, e.reason)
        assertFalse(e.countsAttempt)
        assertNull("no incident report for a deny", e.incident)
        assertTrue(failovers.isEmpty())
        assertTrue(acc.state.value is AcceleratorState.Ready)
        // lifted: the accelerator serves the next session again
        env2.accelDeny = null
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        rec.close()
    }

    // ------------------------------------------------------------------------------------------------- the order

    /** A first install: no accelerator work before the first session; that session runs on the CPU; the pipeline
     *  starts when it ends; the next session uses the accelerator. */
    @Test
    fun aFirstInstallRunsItsFirstSessionOnTheCpuAndPromotesOnlyAfterIt() {
        val env = FakeEnv()
        val store = dir()
        val p = FakePreparer()
        val acc = controller(env, p, store, sessionSeen = false)
        acc.start()
        assertEquals(AcceleratorState.Preparing(Stages.DOWNLOAD, "first_session"), acc.state.value)
        assertTrue(env.jobs.isEmpty())
        val rec = recognizer(acc)
        var duringTake = -1
        val (first, _, _) = take(rec) {
            duringTake = p.b.calls.size
            // the idle delay is armed (30 s after the first session started)
            assertEquals(listOf(fast.idleDelayMs), env.pendingDelays())
        }
        assertEquals(0, duringTake)
        assertTrue(first.all { it.backend == Backend.CPU })
        // the first session ended: the pipeline is due now
        assertTrue(env.pendingDelays().contains(0L))
        env.runDue()
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        assertTrue(AcceleratorStore(store).sessionStarted)
        rec.close()
        // a later launch starts at launch
        val env2 = FakeEnv()
        val c2 = controller(env2, FakePreparer(), dir(), sessionSeen = true)
        c2.start()
        assertEquals(listOf(0L), env2.pendingDelays())
    }

    /** A session that is running while the pipeline gets ready stays on the CPU; the next one uses the accelerator. */
    @Test
    fun aSessionRunningAtPromotionTimeStaysOnTheCpu() {
        val env = FakeEnv()
        val acc = controller(env, FakePreparer(), dir())
        acc.start()
        val rec = recognizer(acc)
        val (first, _, _) = take(rec, chunks = 4) {
            env.runDue()
            // warm-up and golden wait for the session (download and compile ran)
            assertEquals(AcceleratorState.Preparing(Stages.WARMUP, "session"), acc.state.value)
        }
        assertTrue(first.all { it.backend == Backend.CPU })
        env.runDue()
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        rec.close()
    }

    /** Promoted in an earlier launch: the CPU serves until the accelerator is loaded and warm again, without waiting. */
    @Test
    fun aLaterLaunchServesOnTheCpuUntilTheAcceleratorIsWarmAgain() {
        val store = dir()
        val env = FakeEnv()
        controller(env, FakePreparer(), store, launch = "a").also { it.start(); env.runDue() }
        promotions.clear()
        val env2 = FakeEnv()
        val p2 = FakePreparer()
        val acc = controller(env2, p2, store, launch = "b")
        acc.start()
        assertEquals(AcceleratorState.Preparing(Stages.COMPILE), acc.state.value)
        val rec = recognizer(acc)
        val t0 = System.nanoTime()
        val (before, _, _) = take(rec)
        assertTrue((System.nanoTime() - t0) / 1e6 < 2000)
        assertTrue(before.all { it.backend == Backend.CPU })
        env2.runDue()
        // loaded and warmed again; no golden test, no new attempt
        assertEquals(listOf(Stages.COMPILE, Stages.WARMUP), p2.b.calls.toList())
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertTrue(promotions.isEmpty())
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        rec.close()
    }

    @Test
    fun thermalMemoryDenylistAndForcedBackendsAtASessionStart() {
        val env = FakeEnv()
        val acc = controller(env, FakePreparer(), dir())
        acc.start()
        env.runDue()
        val rec = recognizer(acc)
        env.thermal = true
        assertTrue(take(rec).first.all { it.backend == Backend.CPU })
        assertEquals(Reasons.THERMAL, rec.backendChoice.reason)
        env.thermal = false
        env.memory = true
        take(rec)
        assertEquals(Reasons.MEMORY, rec.backendChoice.reason)
        acc.forced = Backend.NPU
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        acc.forced = Backend.CPU
        take(rec)
        assertEquals(Reasons.FORCED_CPU, rec.backendChoice.reason)
        acc.forced = null
        env.memory = false
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        env.denied = true
        take(rec)
        assertEquals(Reasons.DENYLIST, rec.backendChoice.reason)
        rec.close()
    }

    // ------------------------------------------------------------------------------------------------- failover

    private fun promoted(fail: Fail?, store: File = dir(), env: FakeEnv = FakeEnv()): Pair<AcceleratorController, Behaviour> {
        val b = Behaviour().apply { this.fail = fail }
        val acc = controller(env, FakePreparer(b), store)
        acc.start()
        env.runDue()
        assertTrue(acc.state.value is AcceleratorState.Ready)
        promotions.clear()
        return acc to b
    }

    /** Every cause at the first, a middle and the last chunk of a 10-chunk take: the take completes on the CPU with every
     *  chunk, the accelerator's chunks before the failed one kept, the key demoted, later sessions on the CPU. */
    @Test
    fun aFailedChunkSwitchesTheTakeToTheCpuAtOnce() {
        val n = 10
        for (mode in listOf("exception", "nan", "range", "hang")) for (at in listOf(0, n / 2, n - 1)) {
            failovers.clear()
            val env = FakeEnv()
            val store = dir()
            val (acc, b) = promoted(Fail(mode, at), store, env)
            val cpu = CpuFake()
            val rec = recognizer(acc, cpu)
            val s = rec.startSession()
            val got = runBlocking {
                val g = async { s.chunks.toList() }
                repeat(n) { s.append(FloatArray(5120)) }
                s.finish()
                g.await()
            }
            val tag = "$mode at $at"
            assertEquals(tag, n, got.size)
            assertEquals(tag, (0 until n).toList(), got.map { it.index })
            assertTrue(tag, got.take(at).all { it.backend == Backend.NPU })
            assertTrue(tag, got.drop(at).all { it.backend == Backend.CPU })
            val f = failovers.single()
            val cause = mapOf("exception" to Failover.EXCEPTION, "nan" to Failover.NON_FINITE, "range" to Failover.OUT_OF_RANGE,
                "hang" to Failover.DEADLINE).getValue(mode)
            assertEquals(tag, cause, f.failover.cause)
            assertEquals(tag, at, f.failover.chunk)
            assertTrue(tag, f.failover.stallMs >= 0)
            assertEquals(AcceleratorIncident.FAILOVER, f.incident.kind)
            assertEquals("session:${AcceleratorIncident.cause(cause)}", f.incident.code)
            assertEquals(at, f.incident.chunk)
            // the app saw the backend change, no error
            assertEquals("failover_$cause", rec.backendChoice.reason)
            // demoted for this key: a retryable failover at least 24 h later, and the next session starts on the CPU
            val d = acc.state.value as AcceleratorState.Disabled
            assertEquals(Reasons.FAILOVER, d.reason)
            assertFalse(d.persistent)
            assertEquals(env.now + 24 * 3_600_000L, d.retryAtMs)
            assertEquals(Reasons.FAILOVER, record(store)!!.reason)
            assertTrue(take(rec).first.all { it.backend == Backend.CPU })
            assertEquals(Reasons.FAILOVER, rec.backendChoice.reason)
            rec.close()
            if (mode == "hang") {
                // the hung call is abandoned: the accelerator is closed only after it returns
                assertEquals(0, b.closes)
                b.hang.countDown()
                val until = System.currentTimeMillis() + 5_000
                while (b.closes == 0 && System.currentTimeMillis() < until) Thread.sleep(5)
                assertEquals(1, b.closes)
            }
        }
    }

    /** The failover's replay keeps the accelerator's chunks before the failed one and the CPU's from it, for scoring. */
    @Test
    fun theTakesHeadsAreTheAcceleratorsBeforeTheFailedChunkAndTheCpusFromIt() {
        val (acc, _) = promoted(Fail("exception", 4))
        val rec = recognizer(acc)
        val s = rec.startSession()
        val heads = runBlocking {
            repeat(8) { s.append(FloatArray(5120)) }
            s.finish()
        }
        // tokens: the accelerator's 31 on even chunks 0 and 2, then the CPU's 32 on even chunks 4 and 6
        assertEquals(listOf(31, 31, 32, 32), heads.greedy().toList())
        assertEquals(64, heads.n40)
        rec.close()
    }

    @Test
    fun blankOutputOverAudioForFourSecondsFailsOverFromTheFirstBlankChunk() {
        val (acc, _) = promoted(Fail("blank", 2))
        val rec = recognizer(acc)
        val s = rec.startSession()
        val got = runBlocking {
            val g = async { s.chunks.toList() }
            repeat(20) { s.append(FloatArray(5120) { if (it % 2 == 0) 0.1f else -0.1f }) }
            s.finish()
            g.await()
        }
        val f = failovers.single()
        assertEquals(Failover.BLANK, f.failover.cause)
        // the fake's odd chunks carry no token: the energetic blank run starts at chunk 1 (and the CPU redoes it from there)
        assertEquals(1, f.failover.chunk)
        assertEquals((0 until 20).toList(), got.map { it.index })
        // silence never counts as blank output
        val (acc2, _) = promoted(Fail("blank", 2))
        failovers.clear()
        val rec2 = recognizer(acc2)
        val (quiet, _, _) = take(rec2, chunks = 20)
        assertTrue(quiet.all { it.backend == Backend.NPU })
        assertTrue(failovers.isEmpty())
        rec.close()
        rec2.close()
    }
}
