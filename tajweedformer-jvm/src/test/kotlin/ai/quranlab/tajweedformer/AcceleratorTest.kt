package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.ChunkLimits
import ai.quranlab.tajweedformer.accel.Conditions
import ai.quranlab.tajweedformer.accel.CpuStandby
import ai.quranlab.tajweedformer.accel.Demotion
import ai.quranlab.tajweedformer.accel.Failover
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.Telemetry
import ai.quranlab.tajweedformer.cloud.TelemetryContext
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The promotion state machine and the in-session failover with fakes for the network, the files, the compiler, the
 * engines and the clock (no JNI): every stage succeeding, every failure at every stage with its reason, restarts in
 * the middle of each stage and the crash sentinel, OS build and revision changes, backoff and its cap, deterministic
 * failures never retried, conditions that wait without counting, a session aborting the golden stage, a slower build
 * promoted, the order (first install, sessions running at promotion, a later launch), and failovers at the first, a
 * middle and the last chunk for every cause.
 */
class AcceleratorTest : OpenChecked() {
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

    private val model: ModelFolder by lazy {
        val d = dir()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(d, files, TestKit.sign(TestKit.manifest(files)))
        ModelFolder.open(d, TestKit.verifier)
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
    private fun chunk(tok: Int, nan: Boolean = false, range: Boolean = false): HeadValues {
        val m = HashMap<HeadId, FloatArray>()
        for (h in HeadId.BASE_HEADS + HeadId.CONF) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
        for (h in listOf(HeadId.LOG_PROBS, HeadId.INTER1_LOG_PROBS, HeadId.TIMING_LOG_PROBS)) {
            val lp = m.getValue(h)
            val frames = lp.size / 38
            for (t in 0 until frames) for (k in 0 until 38) lp[t * 38 + k] = if (k == tok) -0.01f else -8f
        }
        for (h in listOf(HeadId.GEM, HeadId.ATTR_QALQALAH)) { val v = m.getValue(h); for (i in v.indices step h.width) v[i] = 1f }
        if (nan) m.getValue(HeadId.CONF)[3] = Float.NaN
        if (range) m.getValue(HeadId.LOG_PROBS)[5] = 3f
        return HeadValues(8, m)
    }

    /** A stream that makes a chunk per 5120 samples with [tok] on even chunks; [fail] injects a failure at its chunk;
     *  [delay] is how long its chunk i takes; [b] (the accelerator's) adds its cold start to the first prediction after
     *  [Behaviour.cold] was set. */
    private inner class FakeStream(val backendKind: Backend, val tok: Int, val fail: Fail? = null, val hang: CountDownLatch? = null,
                                   val delay: (Int) -> Long = { 0L }, val b: Behaviour? = null) : TajweedEngineStream {
        var pending = 0
        var made = 0
        override val backend: Backend get() = backendKind
        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
            pending += length
            val out = ArrayList<HeadValues>()
            while (pending >= 5120) {
                pending -= 5120
                out.add(next())
            }
            return out
        }
        private fun next(): HeadValues {
            val i = made++
            b?.let { if (it.cold.getAndSet(false)) Thread.sleep(it.coldMs) }
            delay(i).takeIf { it > 0 }?.let { Thread.sleep(it) }
            b?.predictions?.incrementAndGet()
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
        override fun finish(): List<HeadValues> = if (pending > 0) listOf(next()).also { pending = 0 } else emptyList()
        override fun reset() { pending = 0; made = 0 }
        override fun close() {}
    }

    data class Fail(val mode: String, val at: Int)

    /** The CPU engine (tok 32). */
    private inner class CpuFake : TajweedEngine {
        override val chunkMs = 320
        var streams = 0
        override fun createStream(): TajweedEngineStream { streams++; return FakeStream(Backend.CPU, 32) }
        override fun close() {}
    }

    private val clips = SelfTestSet.REQUIRED
    private val expected = SelfTestSet.Expected("c16_l64", clips.associateWith { SelfTestSet.ClipExpected(listOf(3, 32, 13)) })

    private fun obs(tokens: List<Int> = listOf(3, 32, 13), finite: Boolean = true, ms: Double = 10.0) =
        clips.associateWith { SelfTestSet.ClipObserved(4, TestKit.headsOf(tokens, finite), List(4) { ms }) }

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
        /** How long chunk i of every accelerator stream takes (the warm-up's scratch streams too). */
        var delay: (Int) -> Long = { 0L }
        /** The accelerator powered down: its next prediction takes [coldMs]. */
        val cold = java.util.concurrent.atomic.AtomicBoolean(false)
        var coldMs = 0L
        val predictions = java.util.concurrent.atomic.AtomicInteger(0)
        val streams = java.util.concurrent.atomic.AtomicInteger(0)
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
                override fun stream(heads: Set<HeadId>, sampleRate: Int): TajweedEngineStream {
                    b.streams.incrementAndGet()
                    return FakeStream(Backend.NPU, 31, b.fail, b.hang, b.delay, b)
                }
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
        Recognizer.open(model, Recognizer.Options(accelerator = acc, soc = "tensor_g5", engine = { _, _, _ -> cpu }))

    /** A take of [chunks] chunks of audio at [amp] (energy for the blank rule); [between] runs after the first append. */
    private fun take(rec: Recognizer, chunks: Int = 3, amp: Float = 0f, between: (() -> Unit)? = null): Triple<List<EngineChunk>, HeadValues, HeadValues> = runBlocking {
        val s = rec.startSession()
        val got = async { s.chunks.toList() }
        repeat(chunks) { i ->
            s.append(FloatArray(5120) { amp * (if (it % 2 == 0) 1 else -1) })
            if (i == 0) between?.invoke()
        }
        val r = s.finish()
        val c = got.await()
        Triple(c, HeadValues.concat(emptyList<HeadValues>()), r)
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
        val (chunks, _, _) = take(rec)
        assertTrue(chunks.all { it.backend == Backend.NPU })
        assertEquals(Backend.NPU, rec.backend)
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
            Case(Stages.GOLDEN, Reasons.GOLDEN_MISMATCH) { golden = { GoldenRun(obs(tokens = listOf(3, 32)), obs()) } },
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
                // the first clean clip whose tokens differ (a planted clip's tokens are not compared)
                assertEquals(clips.first(), e.incident!!.clip)
                assertEquals(1, e.incident!!.divergence!!.tokensDiffer.coerceAtMost(1))
                assertEquals(listOf("log_probs.token_edits"), e.incident!!.divergence!!.failed)
            }
            // nothing else is scheduled in this launch
            env.now += 48 * 3_600_000L
            env.runDue()
            assertTrue(acc.state.value is AcceleratorState.Disabled)
        }
    }

    @Test
    fun theFiveGoldenClipsAreRequiredThePlantedOneIncluded() {
        val partial = SelfTestSet.Expected("c16_l64", expected.clips - SelfTestSet.PLANTED_CLIP)
        val v = SelfTestSet.judgeGolden(partial, obs(), obs(), setOf(SelfTestSet.PLANTED_CLIP))
        assertEquals(Reasons.CPU_MISMATCH, v.reason)
        assertTrue(v.detail, v.detail.contains("missing_clip"))
        assertEquals(5, SelfTestSet.REQUIRED.size)
        assertTrue(SelfTestSet.PLANTED_CLIP in SelfTestSet.REQUIRED)
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

    /** A stop at the golden test or at a session's first call ends the key (SDK 3.1.5: a stop at compile, load or
     *  warm-up takes the retry budget instead, PreparationStopTest). */
    @Test
    fun theCrashSentinelRecordsACrashAtItsStageNeverRetried() {
        for (stage in listOf(Stages.GOLDEN, Stages.SESSION)) {
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
            when (mode) {
                "exception" -> {
                    // the 2.0.1 rule: a retryable failover at least 24 h later, the next session on the CPU
                    val d = acc.state.value as AcceleratorState.Disabled
                    assertEquals(Reasons.FAILOVER, d.reason)
                    assertFalse(d.persistent)
                    assertEquals(env.now + 24 * 3_600_000L, d.retryAtMs)
                    assertEquals(Demotion.RETRY, f.failover.demotion)
                    assertEquals(PromotionRecord.FAILED, record(store)!!.status)
                }
                "nan", "range" -> {
                    // a numeric fault: off for good for this key
                    val d = acc.state.value as AcceleratorState.Disabled
                    assertTrue(tag, d.persistent)
                    assertEquals(Demotion.OFF, f.failover.demotion)
                    assertEquals(PromotionRecord.FINAL, record(store)!!.status)
                    assertEquals(cause, record(store)!!.detail)
                }
                "hang" -> {
                    // one missed limit demotes nothing: still promoted, one session counted
                    assertTrue(tag, acc.state.value is AcceleratorState.Ready)
                    assertEquals(Demotion.NONE, f.failover.demotion)
                    assertEquals(PromotionRecord.PROMOTED, record(store)!!.status)
                    assertEquals(1, record(store)!!.missSessionsAtMs.size)
                }
            }
            if (mode == "hang") {
                // the abandoned call still holds the accelerator's thread: the next take runs on the CPU, nothing closed
                assertTrue(take(rec).first.all { it.backend == Backend.CPU })
                assertEquals(Reasons.BUSY, rec.backendChoice.reason)
                assertEquals(0, b.closes)
                b.hang.countDown()
                Thread.sleep(100)
                // it returned: the accelerator serves the next take again
                assertTrue(tag, take(rec).first.all { it.backend == Backend.NPU })
            } else {
                assertTrue(take(rec).first.all { it.backend == Backend.CPU })
                assertEquals(Reasons.FAILOVER, rec.backendChoice.reason)
            }
            rec.close()
        }
    }

    /** A late call is abandoned: the accelerator is closed only after the call returns. */
    @Test
    fun aLateCallKeepsTheAcceleratorOpenUntilItReturns() {
        val (acc, b) = promoted(null)
        b.delay = { i -> if (i == 3) 400L else 0L }
        val rec = recognizer(acc)
        // chunk 3 misses (a single miss: served by the CPU, nothing demoted)
        take(rec, chunks = 6, amp = 0.1f)
        assertTrue(acc.state.value is AcceleratorState.Ready)
        // the controller closed while the late call runs: closed once it returned
        acc.close()
        assertEquals(0, b.closes)
        val until = System.currentTimeMillis() + 5_000
        while (b.closes == 0 && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals(1, b.closes)
        rec.close()
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

    // ------------------------------------------------------------------------------------------------- 2.0.2: blank

    private fun loud(n: Int = 5120) = FloatArray(n) { if (it % 2 == 0) 0.1f else -0.1f }

    /** The CPU standby as the recognizer's slot is: its weights released while the accelerator serves, loaded by a
     *  stream, released again on request. Its streams write [tok] on even chunks and nothing from chunk [blankFrom]
     *  (relative to the stream's start); [pushMs] is how long a push takes. */
    private inner class Standby(val tok: Int = 32, val blankFrom: Int = Int.MAX_VALUE, val pushMs: Long = 0) : CpuStandby {
        override val chunkMs = 320
        @Volatile override var warm = false
        val streams = AtomicInteger(0)
        val releases = AtomicInteger(0)
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
        override fun createStream(): TajweedEngineStream = createStream(HeadId.BASE_HEADS)
        override fun createStream(heads: Set<HeadId>): TajweedEngineStream {
            warm = true
            streams.incrementAndGet()
            val inner = FakeStream(Backend.CPU, tok, if (blankFrom == Int.MAX_VALUE) null else Fail("blank", blankFrom))
            return object : TajweedEngineStream by inner {
                override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                    threads.add(Thread.currentThread().name)
                    if (pushMs > 0) Thread.sleep(pushMs)
                    return inner.pushAudio(samples, offset, length)
                }
            }
        }
        override fun release(): Boolean { releases.incrementAndGet(); warm = false; return true }
        override fun close() {}
    }

    private val blanks = Collections.synchronizedList(ArrayList<BlankConfirmedEvent>())
    private val blankObserver = object : SdkObserver {
        override fun onBlankConfirmed(event: BlankConfirmedEvent) { blanks.add(event) }
    }

    /**
     * A held vowel or a long madd: the accelerator writes nothing over 4 s of audio with energy and the CPU, checking the
     * same window off the audio path, writes nothing either. No failover, no demotion, no incident; `blank_confirmed_ok`
     * counted; the CPU engine closed again; the take never waited for the check; at most one check per 10 s of audio.
     */
    @Test
    fun aBlankStretchTheCpuConfirmsIsNoFailure() {
        SdkEvents.add(blankObserver)
        val telemetry = Telemetry(dir())
        val feed = object : SdkObserver {
            override fun onBlankConfirmed(event: BlankConfirmedEvent) = telemetry.blankConfirmed(event)
            override fun onFailover(event: AcceleratorFailoverEvent) = telemetry.failover(event)
        }
        SdkEvents.add(feed)
        try {
            val store = dir()
            val (acc, _) = promoted(Fail("blank", 2), store)
            // the CPU is blank there too (its replay starts at chunk 0: blank from chunk 2), and slow: 250 ms a push
            val cpu = Standby(blankFrom = 2, pushMs = 250)
            val e = acc.engineForNextSession(cpu, 16_000)!!
            val s = e.createStream()
            val times = ArrayList<Double>()
            var chunks = 0
            repeat(60) {
                val t0 = System.nanoTime()
                chunks += s.pushAudio(loud(), 0, 5120).size
                times += (System.nanoTime() - t0) / 1e6
                // audio arrives over time (here 16 x faster than real time): the first check is done before the second run
                Thread.sleep(20)
            }
            chunks += s.finish().size
            assertEquals(60, chunks)
            assertEquals(Backend.NPU, s.backend)
            assertTrue(failovers.isEmpty())
            // the check ran on its own thread: no push waited for the CPU's 250 ms
            assertTrue("the slowest push took ${times.max()} ms", times.max() < 200)
            assertEquals(setOf("tajweedformer-blank-check"), cpu.threads.toSet())
            // two checks in 60 chunks (19.2 s): the runs at chunks 1..13 and 40..52; the run at 27..39 came under 10 s after the first
            val until = System.currentTimeMillis() + 5_000
            while (blanks.size < 2 && System.currentTimeMillis() < until) Thread.sleep(5)
            assertEquals(2, blanks.size)
            assertEquals(listOf(1, 40), blanks.map { it.check.fromChunk })
            assertTrue(blanks.all { it.check.agreed && it.check.tokenDiffs == 0 })
            assertEquals("npu:tensor_g5", blanks[0].backend)
            // the CPU engine opened for each check and closed again after it
            assertEquals(2, cpu.streams.get())
            assertEquals(2, cpu.releases.get())
            assertFalse(cpu.warm)
            // no demotion: still promoted, nothing recorded against the key
            assertTrue(acc.state.value is AcceleratorState.Ready)
            val r = record(store)!!
            assertEquals(PromotionRecord.PROMOTED, r.status)
            assertEquals(0, r.blankDisagreements)
            assertEquals(0L, r.pausedUntilMs)
            // the telemetry: blank_confirmed_ok 2, no failover, no demotion
            telemetry.seal(TelemetryContext(AppIdentity("app.test", "1.0"), DeviceProfile("Pixel10", "tensor_g5", 16, "16")))
            @Suppress("UNCHECKED_CAST")
            val p = ((Json.parse(telemetry.pendingReports().single().readText()) as Map<String, Any?>)["promotions"] as List<Map<String, Any?>>).single()
            assertEquals(2.0, p["blank_confirmed_ok"])
            assertFalse(p.containsKey("failovers"))
            assertFalse(p.containsKey("demoted"))
            s.close()
        } finally {
            SdkEvents.remove(blankObserver)
            SdkEvents.remove(feed)
        }
    }

    /**
     * The accelerator writes nothing where the CPU writes tokens: a confirmed disagreement. The take continues on the CPU
     * stream the check rebuilt, the chunks from the first suspicious one are delivered again (nothing lost), the incident
     * has cause `blank` and the divergence, the key pauses 6 h (then serves again without a golden test); a second
     * confirmed disagreement for the key turns it off for good.
     */
    @Test
    fun aBlankTheCpuContradictsSwitchesFromTheFirstSuspiciousChunkAndTwiceIsOffForGood() {
        val env = FakeEnv()
        val store = dir()
        val (acc, b) = promoted(Fail("blank", 2), store, env)
        val rec = recognizer(acc)
        fun blankTake(): Pair<List<EngineChunk>, HeadValues> {
            val s = rec.startSession()
            return runBlocking {
                val g = async { s.chunks.toList() }
                repeat(20) { s.append(loud()) }
                val h = s.finish()
                g.await() to h
            }
        }
        val (got, heads) = blankTake()
        val f = failovers.single()
        assertEquals(Failover.BLANK, f.failover.cause)
        // the fake's odd chunks carry no token: the energetic blank run starts at chunk 1, and the CPU takes over from it
        assertEquals(1, f.failover.chunk)
        assertEquals(Demotion.PAUSED, f.failover.demotion)
        val div = f.failover.divergence!!
        assertTrue("${div.tokensDiffer} of ${div.tokensExpected}", div.tokensDiffer > 0 && div.tokensExpected > 0)
        assertEquals("session:blank", f.incident.code)
        assertEquals(div, f.incident.divergence)
        // nothing lost: every chunk index delivered, the redone ones flagged as replacements from chunk 1, from the CPU
        assertEquals((0 until 20).toSet(), got.map { it.index }.toSet())
        val redo = got.indexOfFirst { it.replaces }
        assertTrue(redo > 0)
        assertEquals(1, got[redo].index)
        assertTrue(got.drop(redo).all { it.backend == Backend.CPU })
        assertEquals((1 until 20).toList(), got.drop(redo).map { it.index })
        // the take's heads: the accelerator's chunk 0 (token 31), then the CPU's tokens (32 on even chunks 2..18)
        assertEquals(listOf(31) + List(9) { 32 }, heads.greedy().toList())
        assertEquals(160, heads.n40)
        // a transient demotion: 6 h, not off; the build stays verified
        val d = acc.state.value as AcceleratorState.Disabled
        assertFalse(d.persistent)
        assertEquals(env.now + 6 * 3_600_000L, d.retryAtMs)
        val r = record(store)!!
        assertEquals(PromotionRecord.PROMOTED, r.status)
        assertEquals(1, r.blankDisagreements)
        assertTrue(take(rec).first.all { it.backend == Backend.CPU })
        // after the pause: loaded and warmed again, no golden test
        b.calls.clear()
        env.now += 6 * 3_600_000L
        env.runDue()
        assertEquals(listOf(Stages.COMPILE, Stages.WARMUP), b.calls.toList())
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertEquals(0L, record(store)!!.pausedUntilMs)
        // the second confirmed disagreement for the key: off for good
        failovers.clear()
        blankTake()
        assertEquals(Demotion.OFF, failovers.single().failover.demotion)
        assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
        assertEquals(PromotionRecord.FINAL, record(store)!!.status)
        assertEquals(2, record(store)!!.blankDisagreements)
        rec.close()
    }

    @Test
    fun silenceIsNeverBlankOutput() {
        val (acc, _) = promoted(Fail("blank", 2))
        val rec = recognizer(acc)
        val (quiet, _, _) = take(rec, chunks = 20)
        assertTrue(quiet.all { it.backend == Backend.NPU })
        assertTrue(failovers.isEmpty())
        rec.close()
    }

    // ------------------------------------------------------------------------------------------------- 2.0.2: limits

    /** The golden run measures 10 ms a warm chunk: warm limit max(4 x 10, 2 x 10, 50) = 50 ms, first chunk 1000 ms. */
    @Test
    fun aColdFirstChunkWithinTheFirstChunkLimitIsServedByTheAccelerator() {
        val store = dir()
        val (acc, b) = promoted(null, store)
        assertEquals(50.0, acc.deadlineMs, 0.0)
        assertEquals(1000.0, acc.firstChunkMs, 0.0)
        // every stream's first chunk takes 150 ms: 3 x the warm limit
        b.delay = { i -> if (i == 0) 150L else 0L }
        val rec = recognizer(acc)
        val (chunks, _, _) = take(rec, chunks = 5, amp = 0.1f)
        assertTrue(chunks.all { it.backend == Backend.NPU })
        assertTrue(failovers.isEmpty())
        assertTrue(record(store)!!.missSessionsAtMs.isEmpty())
        rec.close()
    }

    @Test
    fun aFirstChunkPastTheFirstChunkLimitIsServedByTheCpuWithoutADemotion() {
        val store = dir()
        val (acc, b) = promoted(null, store)
        b.delay = { i -> if (i == 0) 1200L else 0L }
        val rec = recognizer(acc)
        val (chunks, _, _) = take(rec, chunks = 5, amp = 0.1f)
        assertTrue(chunks.all { it.backend == Backend.CPU })
        val f = failovers.single().failover
        assertEquals(Failover.DEADLINE, f.cause)
        assertEquals(0, f.chunk)
        assertEquals(1000.0, f.deadlineMs, 0.0)
        assertEquals(Demotion.NONE, f.demotion)
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertEquals(PromotionRecord.PROMOTED, record(store)!!.status)
        assertEquals(1, record(store)!!.missSessionsAtMs.size)
        rec.close()
    }

    /** One miss per session: three sessions within 24 h pause the key 6 h (never off for good); two, or three spread
     *  over more than 24 h, demote nothing. */
    @Test
    fun missesInThreeSessionsWithinADayPauseTheKeyNeverOffForGood() {
        fun missingTake(rec: Recognizer) {
            failovers.clear()
            val (chunks, _, _) = take(rec, chunks = 6, amp = 0.1f)
            assertEquals(Failover.DEADLINE, failovers.single().failover.cause)
            assertTrue(chunks.drop(3).all { it.backend == Backend.CPU })
            // the late call returns before the next session starts
            Thread.sleep(250)
        }
        // two sessions: nothing
        run {
            val env = FakeEnv()
            val (acc, b) = promoted(null, dir(), env)
            b.delay = { i -> if (i == 3) 150L else 0L }
            val rec = recognizer(acc)
            missingTake(rec)
            env.now += 3_600_000L
            missingTake(rec)
            assertTrue(acc.state.value is AcceleratorState.Ready)
            assertEquals(Demotion.NONE, failovers.single().failover.demotion)
            rec.close()
        }
        // three over more than 24 h: nothing
        run {
            val env = FakeEnv()
            val store = dir()
            val (acc, b) = promoted(null, store, env)
            b.delay = { i -> if (i == 3) 150L else 0L }
            val rec = recognizer(acc)
            missingTake(rec)
            env.now += 13 * 3_600_000L
            missingTake(rec)
            env.now += 12 * 3_600_000L
            missingTake(rec)
            assertTrue(acc.state.value is AcceleratorState.Ready)
            assertEquals(Demotion.NONE, failovers.single().failover.demotion)
            assertEquals(2, record(store)!!.missSessionsAtMs.size)
            rec.close()
        }
        // three within 24 h: paused 6 h, then the same build again; round after round never turns it off
        run {
            val env = FakeEnv()
            val store = dir()
            val (acc, b) = promoted(null, store, env)
            b.delay = { i -> if (i == 3) 150L else 0L }
            val rec = recognizer(acc)
            repeat(4) { round ->
                missingTake(rec)
                env.now += 3_600_000L
                missingTake(rec)
                env.now += 3_600_000L
                missingTake(rec)
                assertEquals(Demotion.PAUSED, failovers.single().failover.demotion)
                val d = acc.state.value as AcceleratorState.Disabled
                assertFalse("round $round", d.persistent)
                assertEquals(env.now + 6 * 3_600_000L, d.retryAtMs)
                assertTrue(take(rec).first.all { it.backend == Backend.CPU })
                b.calls.clear()
                env.now += 6 * 3_600_000L
                env.runDue()
                assertEquals(listOf(Stages.COMPILE, Stages.WARMUP), b.calls.toList())
                assertTrue(acc.state.value is AcceleratorState.Ready)
            }
            assertEquals(PromotionRecord.PROMOTED, record(store)!!.status)
            rec.close()
        }
    }

    private fun obsTimes(ms: List<Double>) = clips.associateWith {
        SelfTestSet.ClipObserved(4, TestKit.headsOf(listOf(3, 32, 13)), ms)
    }

    /** The limits come from warm chunks only: neither the golden run's first chunks nor a slow first chunk of a take
     *  raise the later limit. */
    @Test
    fun theLimitsComeFromWarmChunksOnly() {
        // the golden run's first chunk of every clip took 500 ms, the others 10 ms: warm limit 50 ms, not 300
        val env = FakeEnv()
        val p = FakePreparer().also { it.b.golden = { GoldenRun(obsTimes(listOf(500.0, 10.0, 10.0, 10.0)), obsTimes(listOf(900.0, 40.0, 40.0, 40.0))) } }
        val acc = controller(env, p, dir())
        acc.start()
        env.runDue()
        assertEquals(50.0, acc.deadlineMs, 0.0)
        assertEquals(1000.0, acc.firstChunkMs, 0.0)
        assertEquals(4.0, (acc.state.value as AcceleratorState.Ready).speedup, 1e-9)
        // takes whose first chunk takes 400 ms (within its limit): only the warm chunks are measured
        p.b.delay = { i -> if (i == 0) 400L else 0L }
        val rec = recognizer(acc)
        repeat(2) { take(rec, chunks = 10, amp = 0.1f) }
        assertTrue(failovers.isEmpty())
        // 2 takes x 9 warm chunks (the first of each and the flush never counted)
        assertEquals(18, acc.chunkLimits.samples)
        assertTrue("p90 ${acc.chunkLimits.stats().second}", acc.chunkLimits.stats().second < 100)
        assertEquals(50.0, acc.deadlineMs, 0.0)
        rec.close()
        // the limits themselves
        val l = ChunkLimits(Double.NaN, Double.NaN)
        assertEquals(300.0, l.warmMs, 0.0)
        assertEquals(1000.0, l.firstChunkMs, 0.0)
        repeat(20) { l.add(30.0) }
        assertEquals(120.0, l.warmMs, 0.0)
        assertEquals(1000.0, l.firstChunkMs, 0.0)
        repeat(256) { l.add(150.0) }
        assertEquals(300.0, l.warmMs, 0.0)
        assertEquals(1500.0, l.firstChunkMs, 0.0)
    }

    /**
     * The warm-up: one prediction of silence on the accelerator's thread when a take starts and when the app becomes
     * active; it does not delay the session start or the first chunk (which needs a chunk of audio first), and it
     * absorbs the accelerator's cold start (the first prediction after idle, here 600 ms).
     */
    @Test
    fun theWarmUpRunsAtTakeStartAndOnActivationOffTheAudioPath() {
        val (acc, b) = promoted(null)
        val rec = recognizer(acc)
        b.coldMs = 600
        b.cold.set(true)
        val before = b.predictions.get()
        val t0 = System.nanoTime()
        val s = rec.startSession()
        val startMs = (System.nanoTime() - t0) / 1e6
        assertTrue("session start $startMs ms", startMs < 100)
        val got = runBlocking {
            val g = async { s.chunks.toList() }
            // the first chunk's audio takes 320 ms to arrive; the warm-up wakes the accelerator meanwhile (600 ms)
            Thread.sleep(700)
            assertEquals("the warm-up ran before any audio", before + 1, b.predictions.get())
            val c0 = System.nanoTime()
            s.append(loud())
            val firstMs = (System.nanoTime() - c0) / 1e6
            assertTrue("the first chunk took $firstMs ms", firstMs < 100)
            repeat(3) { s.append(loud()) }
            s.finish()
            g.await()
        }
        assertTrue(got.all { it.backend == Backend.NPU })
        assertTrue(failovers.isEmpty())
        // the app comes back from the background during a take: a warm-up, and the next chunk gets the first-chunk limit
        val s2 = rec.startSession()
        runBlocking {
            val g = async { s2.chunks.toList() }
            repeat(2) { s2.append(loud()) }
            b.cold.set(true)
            b.coldMs = 300
            val n = b.predictions.get()
            acc.appActivated()
            // the warm-up runs on the accelerator's thread; the next chunk waits behind it, under the first-chunk limit
            s2.append(loud())
            assertTrue(b.predictions.get() >= n + 2)
            repeat(2) { s2.append(loud()) }
            s2.finish()
            assertTrue(g.await().all { it.backend == Backend.NPU })
        }
        assertTrue("a 300 ms wake-up after activation is no miss", failovers.isEmpty())
        rec.close()
    }

    // ------------------------------------------------------------------------------------------------- 2.0.2: upgrade

    /**
     * The first launch of 2.0.2 over 2.0.1's records: a key demoted for blank output and one off for good after 4
     * deadline failovers go back to the golden stage (tested again in the background, then promoted); one off for good
     * for NaN stays off. It runs once: a record written afterwards is left alone.
     */
    @Test
    fun theUpgradeFrom201ClearsBlankAndDeadlineDemotionsOnce() {
        val store = dir()
        val now = 1_000_000_000L
        val blank = key(rev = "r-blank")
        val deadline = key(rev = "r-deadline")
        val nan = key(rev = "r-nan")
        fun rec(status: String, failures: Int, reason: String, detail: String) = PromotionRecord(status = status,
            attempt = failures, failures = failures, reason = reason, failedStage = "session", detail = detail, nextAttemptAtMs = now + 86_400_000L,
            launch = "old", accMedianMs = 10.0, accP90Ms = 12.0, cpuMedianMs = 40.0)
        val old = linkedMapOf(
            blank.id to rec(PromotionRecord.FAILED, 1, Reasons.FAILOVER, Failover.BLANK),
            deadline.id to rec(PromotionRecord.FINAL, 4, Reasons.FAILOVER, Failover.DEADLINE).copy(nextAttemptAtMs = 0),
            nan.id to rec(PromotionRecord.FINAL, 4, Reasons.FAILOVER, Failover.NON_FINITE).copy(nextAttemptAtMs = 0),
        )
        // the 2.0.1 file: no upgrades mark
        File(store, "accelerator.json").writeText(Json.write(linkedMapOf("format" to AcceleratorStore.FORMAT, "install" to mapOf("session_started" to true),
            "keys" to old.mapValues { it.value.toJson() })))
        val s = AcceleratorStore(store)
        for (k in listOf(blank, deadline)) {
            val r = s.record(k)!!
            assertEquals(PromotionRecord.PENDING, r.status)
            assertEquals(Stages.WARMUP, r.stage)
            assertEquals(0, r.failures)
            assertEquals("", r.reason)
        }
        assertEquals(PromotionRecord.FINAL, s.record(nan)!!.status)
        assertEquals(Failover.NON_FINITE, s.record(nan)!!.detail)
        // the cleared keys run the golden test again in the background, then promote as usual
        for (k in listOf(blank, deadline)) {
            promotions.clear()
            val env = FakeEnv()
            val p = FakePreparer()
            val acc = AcceleratorController(env, AcceleratorStore(store), p, k, fast, "new")
            acc.start()
            assertEquals(AcceleratorState.Preparing(Stages.GOLDEN), acc.state.value)
            env.runDue()
            assertEquals(listOf(Stages.COMPILE, Stages.WARMUP, Stages.GOLDEN), p.b.calls.toList())
            assertTrue(acc.state.value is AcceleratorState.Ready)
            assertTrue(promotions.single().promoted)
            acc.close()
        }
        val off = AcceleratorController(FakeEnv(), AcceleratorStore(store), FakePreparer(), nan, fast, "new").also { it.start() }
        assertTrue((off.state.value as AcceleratorState.Disabled).persistent)
        // once: a 2.0.2 record of the same kind stays as it is
        AcceleratorStore(store).put(blank, rec(PromotionRecord.FINAL, 2, Reasons.FAILOVER, Failover.BLANK))
        assertEquals(PromotionRecord.FINAL, AcceleratorStore(store).record(blank)!!.status)
        // a fresh install's file carries the mark from its first write
        val fresh = dir()
        AcceleratorStore(fresh).put(blank, rec(PromotionRecord.FAILED, 1, Reasons.FAILOVER, Failover.BLANK))
        assertEquals(PromotionRecord.FAILED, AcceleratorStore(fresh).record(blank)!!.status)
    }
}
