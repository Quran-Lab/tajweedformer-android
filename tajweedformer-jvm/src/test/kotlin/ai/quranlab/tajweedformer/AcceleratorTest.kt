package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.PreparedAccelerator
import ai.quranlab.tajweedformer.accel.Reasons
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * the 2.0.0 design with a FAKE NPU (no JNI): CPU first, background preparation, the self-test rule, promotion
 * at the next session only, the crash sentinel across a simulated process death, the load timeout, the
 * per-chunk watchdog, OS build changes, thermal and memory, the denylist, the state API.
 */
class AcceleratorTest {
    private val dirs = ArrayList<File>()
    private fun dir() = Files.createTempDirectory("tj-acc-").toFile().also { dirs.add(it) }
    private val choices = Collections.synchronizedList(ArrayList<BackendChoice>())
    private val observer = object : SdkObserver {
        override fun onBackendSelected(choice: BackendChoice) { choices.add(choice) }
    }

    @Before fun setUp() = SdkEvents.add(observer)

    @After
    fun tearDown() {
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    private lateinit var modelDir: File
    private val model: TajweedModel by lazy {
        modelDir = dir()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(modelDir, files, TestKit.sign(TestKit.manifest(files)))
        TajweedModel.open(modelDir, TestKit.verifier)
    }

    /** The platform: work runs when [runScheduled] is called (or at once with [immediate]). */
    private class FakeEnv(
        override var osBuild: String = "build-1",
        var thermal: Boolean = false,
        var memory: Boolean = false,
        var denied: Boolean = false,
        val immediate: Boolean = false,
    ) : AcceleratorEnvironment {
        override val deviceModel = "Google Pixel 10"
        override val soc = "tensor_g5"
        val scheduled = Collections.synchronizedList(ArrayList<Pair<Boolean, () -> Unit>>())
        override fun thermalSevere() = thermal
        override fun lowMemory() = memory
        override fun forcedBackend(revision: String) = if (denied) "exact" else null
        override fun schedule(constrained: Boolean, work: () -> Unit) {
            if (immediate) work() else scheduled.add(constrained to work)
        }
        fun runScheduled() { val w = ArrayList(scheduled); scheduled.clear(); w.forEach { it.second() } }
    }

    /** One chunk of 8 40 ms frames whose log_probs peak on [tok] (0 = blank); [nan] puts a NaN in conf. */
    private fun chunk(tok: Int, nan: Boolean = false): TajweedHeads {
        val m = HashMap<TajweedHead, FloatArray>()
        for (h in TajweedHead.entries) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
        val lp = m.getValue(TajweedHead.LOG_PROBS)
        for (t in 0 until 8) for (k in 0 until 38) lp[t * 38 + k] = if (k == tok) -0.01f else -8f
        if (nan) m.getValue(TajweedHead.CONF)[3] = Float.NaN
        return TajweedHeads(8, m)
    }

    /** An engine that makes a chunk per 5120 samples; [tok] per chunk, NaN at chunk [nanAt], sleeping [sleepMs]. */
    private open inner class FakeEngine(val backendKind: Backend, val tok: Int = 31, val nanAt: Int = -1, val sleepMs: Long = 0,
                                        val throwAt: Int = -1) : TajweedEngine {
        override val chunkMs = 320
        override val backend: Backend get() = backendKind
        var streams = 0
        override fun createStream(): TajweedEngineStream {
            streams++
            return object : TajweedEngineStream {
                var pending = 0
                var made = 0
                override val backend: Backend get() = backendKind
                override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
                    pending += length
                    val out = ArrayList<TajweedHeads>()
                    while (pending >= 5120) {
                        pending -= 5120
                        if (made == throwAt) throw IllegalStateException("the fake NPU threw")
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                        out.add(chunk(if (made % 2 == 0) tok else 0, made == nanAt))
                        made++
                    }
                    return out
                }
                override fun finish(): List<TajweedHeads> = listOf(chunk(0))
                override fun reset() {}
                override fun close() {}
            }
        }
        override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int) =
            TajweedResult(surah, ayah, settings, emptyList(), emptyList(), emptyList(), emptyList(), TajweedSummary(0, 0, 0, 0, 0, 0, 0, 0, 0.0, false))
        override fun expectedTokens(surah: Int, ayah: Int) = intArrayOf(31)
        override fun close() {}
    }

    private val expected = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", mapOf(
        "Husary_001001" to SelfTestSet.ClipExpected(4, listOf(3, 32, 13), setOf(5 to "vowel_sub"), setOf(2), listOf(1.0)),
        "Husary_112001" to SelfTestSet.ClipExpected(4, listOf(22, 33), emptySet(), emptySet(), listOf(-3.0)),
    ))

    private fun observed(tokens: List<Int> = listOf(3, 32, 13), sites: Set<Pair<Int, String>> = setOf(5 to "vowel_sub"),
                         words: Set<Int> = setOf(2), finite: Boolean = true, ms: Double = 5.0) = mapOf(
        "Husary_001001" to SelfTestSet.ClipObserved(4, tokens, sites, words, finite, List(4) { ms }),
        "Husary_112001" to SelfTestSet.ClipObserved(4, listOf(22, 33), emptySet(), emptySet(), true, List(4) { ms }),
    )

    /** The fake NPU: [load] behaviour, the self-test's observations, the session engine. */
    private inner class FakePreparer(
        var unavailable: String? = null,
        var loadSleepMs: Long = 0,
        var loadBlocks: CountDownLatch? = null,
        var obs: () -> Map<String, SelfTestSet.ClipObserved> = { observed() },
        var npu: () -> TajweedEngine = { FakeEngine(Backend.NPU) },
    ) : AcceleratorPreparer {
        var loads = 0
        override fun unavailable(key: AcceleratorKey) = unavailable
        override fun download(key: AcceleratorKey) {}
        override fun load(key: AcceleratorKey): PreparedAccelerator {
            loads++
            loadBlocks?.await()
            if (loadSleepMs > 0) Thread.sleep(loadSleepMs)
            return object : PreparedAccelerator {
                override val expected = this@AcceleratorTest.expected
                override val chunkMs = 320
                override fun engine(cpu: TajweedEngine, sampleRate: Int, onFallback: (String) -> Unit) = npu()
                override fun selfTest() = obs()
                override fun close() {}
            }
        }
    }

    private fun key(rev: String = "r1", os: String = "build-1") = AcceleratorKey(rev, os, "Google Pixel 10", "tensor_g5", "c16_l64")

    private fun controller(env: FakeEnv, prep: FakePreparer, store: File, k: AcceleratorKey = key(env.osBuild), timeout: Long = 5_000) =
        AcceleratorController(env, AcceleratorStore(store), prep, k, timeout).also { c -> repeat(10) { c.recordCpuChunk(40.0) } }

    private fun recognizer(acc: AcceleratorController?, cpu: TajweedEngine = FakeEngine(Backend.CPU, tok = 32)) =
        TajweedRecognizer.open(model, TajweedRecognizer.Options(accelerator = acc, soc = "tensor_g5", engine = { _, _, _ -> cpu }))

    private fun take(rec: TajweedRecognizer, chunks: Int = 3, between: (() -> Unit)? = null): Pair<List<TajweedChunk>, TajweedResult> = runBlocking {
        val s = rec.startSession()
        val got = async { s.chunks.toList() }
        repeat(chunks) { i ->
            s.append(FloatArray(5120))
            if (i == 0) between?.invoke()
        }
        val r = s.finishAndScore(1, 1)
        got.await() to r
    }

    @Test
    fun cpuServesFirstThenTheNpuFromTheNextSessionOnly() {
        val env = FakeEnv()
        val store = dir()
        val acc = controller(env, FakePreparer(), store)
        val states = Collections.synchronizedList(ArrayList<String>())
        acc.addListener { states.add(it.key) }
        acc.start()
        // preparation waits for its constraints (idle, charging): the CPU serves meanwhile
        assertEquals(AcceleratorState.Preparing, acc.state.value)
        assertTrue(env.scheduled.single().first)
        val rec = recognizer(acc)
        val (first, _) = take(rec) {
            // the NPU gets ready during the take: the take stays on the CPU
            env.runScheduled()
        }
        assertTrue(first.all { it.backend == Backend.CPU })
        assertTrue(acc.state.value is AcceleratorState.Ready)
        val (second, r2) = take(rec)
        assertTrue(second.all { it.backend == Backend.NPU })
        assertEquals(Backend.NPU, r2.backend)
        assertEquals(Backend.NPU, rec.backend)
        assertEquals(listOf("preparing", "ready"), states.toList())
        assertEquals(AcceleratorState.Ready::class, runBlocking { acc.awaitReady(1000) }!!::class)
        rec.close()
        // a new process with the same key: the verdict is kept, the NPU is only loaded again (no constraints, no self-test)
        val env2 = FakeEnv()
        var selfTests = 0
        val p2 = FakePreparer(obs = { selfTests++; observed() })
        val acc2 = controller(env2, p2, store)
        acc2.start()
        assertFalse(env2.scheduled.single().first)
        env2.runScheduled()
        assertTrue(acc2.state.value is AcceleratorState.Ready)
        assertEquals(0, selfTests)
        assertEquals(1, p2.loads)
    }

    @Test
    fun aCrashDuringLoadDisablesTheNpuForThatRevisionAndOsBuildOnly() {
        val store = dir()
        val stuck = CountDownLatch(1)
        val env = FakeEnv()
        val acc = controller(env, FakePreparer(loadBlocks = stuck), store, timeout = 60_000)
        acc.start()
        // the load never returns: the process "dies" here (the thread is abandoned, the sentinel stays)
        val t = Thread { env.runScheduled() }.apply { isDaemon = true; start() }
        val sentinel = File(store, "accelerator.sentinel")
        val until = System.currentTimeMillis() + 5_000
        while (!sentinel.isFile && System.currentTimeMillis() < until) Thread.sleep(5)
        assertTrue("the sentinel is written before the load", sentinel.isFile)
        // a new process: the sentinel is found, the NPU is disabled for that key, persistently
        val again = controller(FakeEnv(), FakePreparer(), store)
        again.start()
        val s = again.state.value as AcceleratorState.Disabled
        assertEquals(Reasons.CRASHED, s.reason)
        assertTrue(s.persistent)
        assertFalse(sentinel.isFile)
        // and stays so in the next process
        controller(FakeEnv(), FakePreparer(), store).also { it.start() }.let { assertEquals(Reasons.CRASHED, (it.state.value as AcceleratorState.Disabled).reason) }
        // an OS update or a new model revision prepares again
        val osEnv = FakeEnv(osBuild = "build-2", immediate = true)
        controller(osEnv, FakePreparer(), store).also { it.start() }.let { assertTrue(it.state.value is AcceleratorState.Ready) }
        controller(FakeEnv(immediate = true), FakePreparer(), store, key("r2")).also { it.start() }.let { assertTrue(it.state.value is AcceleratorState.Ready) }
        stuck.countDown()
        t.join(5_000)
    }

    @Test
    fun theSelfTestRuleRejectsMismatchesNanAndSlowNpus() {
        fun verdict(obs: () -> Map<String, SelfTestSet.ClipObserved>): AcceleratorState {
            val c = controller(FakeEnv(immediate = true), FakePreparer(obs = obs), dir())
            c.start()
            return c.state.value
        }
        assertTrue(verdict { observed() } is AcceleratorState.Ready)
        for ((obs, why) in listOf<Pair<() -> Map<String, SelfTestSet.ClipObserved>, String>>(
            { observed(tokens = listOf(3, 32)) } to "tokens",
            { observed(sites = emptySet()) } to "site_flags",
            { observed(words = setOf(1, 2)) } to "word_flags",
            { observed(finite = false) } to "non_finite",
            { observed(ms = 200.0) } to "latency_p90",
            { observed(ms = 45.0) } to "latency_median",
            { throw IllegalStateException("NPU fault") } to "exception",
        )) {
            val s = verdict(obs) as AcceleratorState.Disabled
            assertEquals(Reasons.SELF_TEST, s.reason)
            assertTrue("$why: ${s.detail}", s.detail.startsWith(why))
            assertTrue(s.persistent)
        }
        // a failed key stays disabled in the next process; the next OS build tries again
        val store = dir()
        controller(FakeEnv(immediate = true), FakePreparer(obs = { observed(tokens = emptyList()) }), store).start()
        controller(FakeEnv(immediate = true), FakePreparer(), store).also { it.start() }.let { assertTrue(it.state.value is AcceleratorState.Disabled) }
        controller(FakeEnv(osBuild = "build-2", immediate = true), FakePreparer(), store).also { it.start() }.let { assertTrue(it.state.value is AcceleratorState.Ready) }
    }

    @Test
    fun aLoadTimeoutAndAMissingRuntime() {
        val c = controller(FakeEnv(immediate = true), FakePreparer(loadSleepMs = 2_000), dir(), timeout = 100)
        c.start()
        assertEquals(Reasons.LOAD_TIMEOUT, (c.state.value as AcceleratorState.Disabled).reason)
        val none = controller(FakeEnv(immediate = true), FakePreparer(unavailable = Reasons.NO_BUILD), dir())
        none.start()
        assertEquals(AcceleratorState.Cpu(Reasons.NO_BUILD), none.state.value)
        assertEquals(AcceleratorState.Cpu(Reasons.NO_BUILD), runBlocking { none.awaitReady(1000) })
    }

    @Test
    fun thermalMemoryDenylistAndForcedBackends() {
        val env = FakeEnv(immediate = true)
        val acc = controller(env, FakePreparer(), dir())
        acc.start()
        val rec = recognizer(acc)
        env.thermal = true
        assertTrue(take(rec).first.all { it.backend == Backend.CPU })
        assertEquals(Reasons.THERMAL, rec.backendChoice.reason)
        env.thermal = false
        env.memory = true
        take(rec)
        assertEquals(Reasons.MEMORY, rec.backendChoice.reason)
        // forced NPU (debugging) ignores thermal and memory; forced CPU keeps it off
        acc.forced = Backend.NPU
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        acc.forced = Backend.CPU
        take(rec)
        assertEquals(Reasons.FORCED_CPU, rec.backendChoice.reason)
        acc.forced = null
        env.memory = false
        assertTrue(take(rec).first.all { it.backend == Backend.NPU })
        // the signed config's denylist: CPU at the next session, and disabled from the start next time
        env.denied = true
        take(rec)
        assertEquals(Reasons.DENYLIST, rec.backendChoice.reason)
        rec.close()
        val c2 = controller(FakeEnv(denied = true, immediate = true), FakePreparer(), dir())
        c2.start()
        assertEquals(Reasons.DENYLIST, (c2.state.value as AcceleratorState.Disabled).reason)
        assertTrue(choices.any { it.reason == Reasons.DENYLIST })
    }

    @Test
    fun theWatchdogDropsToTheCpuFromTheNextTake() {
        for ((engine, why) in listOf<Pair<() -> TajweedEngine, String>>(
            { FakeEngine(Backend.NPU, nanAt = 1) } to "watchdog_non_finite",
            { FakeEngine(Backend.NPU, sleepMs = 400) } to "watchdog_latency",
        )) {
            val acc = controller(FakeEnv(immediate = true), FakePreparer(npu = engine), dir())
            acc.start()
            val rec = recognizer(acc)
            val (a, _) = take(rec)
            // the take in flight finishes on the NPU; the next one runs on the CPU
            assertTrue(a.all { it.backend == Backend.NPU })
            assertEquals(why, (acc.state.value as AcceleratorState.Disabled).reason)
            val (b, _) = take(rec)
            assertTrue(b.all { it.backend == Backend.CPU })
            assertEquals(why, rec.backendChoice.reason)
            rec.close()
        }
        // all blank: an NPU take of 5 s that heard nothing
        val acc = controller(FakeEnv(immediate = true), FakePreparer(npu = { FakeEngine(Backend.NPU, tok = 0) }), dir())
        acc.start()
        val rec = recognizer(acc)
        take(rec, chunks = 16)
        assertEquals("watchdog_all_blank", (acc.state.value as AcceleratorState.Disabled).reason)
        assertFalse((acc.state.value as AcceleratorState.Disabled).persistent)
        rec.close()
    }

    @Test
    fun theStateApiNeedsNoCodeByDefault() {
        // without an accelerator the recognizer is the CPU engine, as in 1.x
        val rec = recognizer(null)
        assertEquals(Backend.CPU, rec.backend)
        assertEquals("no_accelerator", rec.backendChoice.reason)
        assertTrue(take(rec).first.all { it.backend == Backend.CPU })
        rec.close()
        // awaitReady times out (null) while preparation waits for its constraints
        val env = FakeEnv()
        val acc = controller(env, FakePreparer(), dir())
        acc.start()
        assertNull(runBlocking { acc.awaitReady(50) })
        env.runScheduled()
        val ready = runBlocking { acc.awaitReady(1000) } as AcceleratorState.Ready
        assertEquals("tensor_g5", ready.soc)
        assertTrue(ready.timeToReadyMs >= 0)
        assertTrue(acc.state.value is AcceleratorState.Ready)
    }

    @Test
    fun twoWatchdogTripsOnOneKeyAcrossProcessesDisableIt() {
        val store = dir()
        fun proc(os: String = "build-1", rev: String = "r1") =
            controller(FakeEnv(osBuild = os, immediate = true), FakePreparer(), store, key(rev, os)).also { it.start() }
        // process 1: one trip, for this process only; it is recorded
        val p1 = proc()
        assertTrue(p1.state.value is AcceleratorState.Ready)
        p1.watchdog("latency", "chunk 3: 400 ms")
        assertEquals(AcceleratorState.Disabled("watchdog_latency", "chunk 3: 400 ms", persistent = false), p1.state.value)
        assertEquals(listOf("latency"), p1.trips.map { it.reason })
        // process 2: prepared again and Ready; its trip is the second on this key: off for good
        val p2 = proc()
        assertTrue(p2.state.value is AcceleratorState.Ready)
        p2.watchdog("non_finite")
        val d = p2.state.value as AcceleratorState.Disabled
        assertEquals(Reasons.WATCHDOG_REPEATED, d.reason)
        assertTrue(d.persistent)
        assertTrue(d.detail, d.detail.startsWith("2 watchdog trips"))
        assertEquals(listOf("latency", "non_finite"), p2.trips.map { it.reason })
        // process 3: disabled at start
        assertEquals(Reasons.WATCHDOG_REPEATED, (proc().state.value as AcceleratorState.Disabled).reason)
        // a new OS build or a new model revision: Ready again, with no trips counted
        proc(os = "build-2").let { assertTrue(it.state.value is AcceleratorState.Ready); assertTrue(it.trips.isEmpty()) }
        proc(rev = "r2").let { assertTrue(it.state.value is AcceleratorState.Ready); assertTrue(it.trips.isEmpty()) }
        assertTrue(choices.any { it.reason == Reasons.WATCHDOG_REPEATED && it.isFallback })
    }

    @Test
    fun tripCountsSurviveACrashAndStayPerKey() {
        val store = dir()
        fun proc(os: String) = controller(FakeEnv(osBuild = os, immediate = true), FakePreparer(), store, key("r1", os)).also { it.start() }
        // one trip on build-1 and one on build-2: different keys, neither is disabled
        proc("build-1").watchdog("latency")
        proc("build-2").watchdog("latency")
        assertTrue(proc("build-1").state.value is AcceleratorState.Ready)
        assertTrue(proc("build-2").state.value is AcceleratorState.Ready)
        // a process dies during a load on build-1: the crash record keeps the key's trip
        AcceleratorStore(store).armSentinel(key("r1", "build-1"), "load")
        val after = proc("build-1")
        assertEquals(Reasons.CRASHED, (after.state.value as AcceleratorState.Disabled).reason)
        assertEquals(listOf("latency"), AcceleratorStore(store).record(key("r1", "build-1"))!!.trips.map { it.reason })
        assertEquals(1, AcceleratorStore(store).record(key("r1", "build-2"))!!.trips.size)
    }
}
