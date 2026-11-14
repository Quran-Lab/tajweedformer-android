package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.GoldenRun
import ai.quranlab.tajweedformer.accel.PreparedAccelerator
import ai.quranlab.tajweedformer.accel.PromotionRecord
import ai.quranlab.tajweedformer.accel.PromotionTimeouts
import ai.quranlab.tajweedformer.accel.Reasons
import ai.quranlab.tajweedformer.accel.Stages
import ai.quranlab.tajweedformer.cloud.IssueQueue
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

/**
 * SDK 3.1.5: a stop of the process during the accelerator's preparation is not a fault of the accelerator.
 *
 * - In the foreground, or where it is not known: a stop at a stage where the model has not run on the accelerator in
 *   this attempt (compile, load, warm-up) takes the retry budget (1 h, 6 h, 24 h), and a second one at the same
 *   stage ends the key; at golden, measure or a session's first accelerator call it ends the key, as before. The
 *   download writes no sentinel (a stop there leaves nothing to count, and the attempt goes on), so no test here
 *   writes one.
 * - In the background: a stop counts nothing at any stage, and the attempt goes on at the next launch, until the
 *   third in a row at the same stage, which counts as a stop in the foreground.
 * - The sentinel says whether the app was in the foreground; the platform's hook rewrites it while it is armed.
 * - The report of a stop says it (`detail.foreground`), and leaves it out when it is not known.
 * - Once per records file: a key that 3.1.4 ended for one stop at a preparation stage gets its retry back.
 *
 * The process's end is the sentinel it leaves (`accelerator.sentinel`), written here as the SDK writes it; the next
 * launch is a new controller with its own launch id.
 */
class PreparationStopTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun dir() = Files.createTempDirectory("tj-stop-").toFile().also { dirs.add(it) }
    private val promotions = Collections.synchronizedList(ArrayList<PromotionEvent>())
    private val observer = object : SdkObserver {
        override fun onPromotion(event: PromotionEvent) { promotions.add(event) }
    }

    @Before fun setUp() = SdkEvents.add(observer)

    @After
    fun tearDown() {
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    /** The platform with a clock and the app's foreground ([fg]: null where it cannot tell). */
    private class FakeEnv : AcceleratorEnvironment {
        override val osBuild = "build-1"
        override val deviceModel = "Google Pixel 10"
        override val deviceModelCode = "Pixel 10"
        override val osBuildReport = "16/BD1A.250101.001/12345678"
        override val soc = "tensor_g5"
        var now = 1_000_000_000L
        var fg: Boolean? = true
        private val changes = Collections.synchronizedList(ArrayList<(Boolean) -> Unit>())
        class Job(val at: Long, val work: () -> Unit)
        val jobs = Collections.synchronizedList(ArrayList<Job>())
        override fun nowMs() = now
        override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) { jobs.add(Job(now + delayMs, work)) }
        override fun foreground(): Boolean? = fg
        override fun onForegroundChange(listener: (Boolean) -> Unit) { changes.add(listener) }

        /** The app goes to the background ([v] false) or comes back. */
        fun appForeground(v: Boolean) {
            fg = v
            for (l in changes.toList()) l(v)
        }

        fun runDue(): Int {
            var n = 0
            while (true) {
                val j = synchronized(jobs) { jobs.firstOrNull { it.at <= now }?.also { jobs.remove(it) } } ?: return n
                j.work()
                n++
            }
        }
    }

    private val clips = SelfTestSet.REQUIRED
    private val expected = SelfTestSet.Expected("c16_l64", clips.associateWith { SelfTestSet.ClipExpected(listOf(3, 32, 13)) })

    private fun obs(ms: Double) = clips.associateWith { SelfTestSet.ClipObserved(4, TestKit.headsOf(listOf(3, 32, 13), true), List(4) { ms }) }

    /** A preparer whose stages pass; [compile] runs inside the compile stage. */
    private inner class Preparer(var compile: () -> Unit = {}) : AcceleratorPreparer {
        val calls = Collections.synchronizedList(ArrayList<String>())
        override fun unavailable(key: AcceleratorKey): String? = null
        override fun download(key: AcceleratorKey) { calls.add(Stages.DOWNLOAD) }
        override fun compile(key: AcceleratorKey): PreparedAccelerator {
            calls.add(Stages.COMPILE)
            compile()
            return object : PreparedAccelerator {
                override val chunkMs = 320
                override val expected = this@PreparationStopTest.expected
                override val planted = setOf(SelfTestSet.PLANTED_CLIP)
                override fun warmup() { calls.add(Stages.WARMUP) }
                override fun golden(abort: () -> Boolean): GoldenRun { calls.add(Stages.GOLDEN); return GoldenRun(obs(10.0), obs(40.0)) }
                override fun stream(heads: Set<HeadId>, sampleRate: Int): TajweedEngineStream = throw UnsupportedOperationException()
                override fun close() {}
            }
        }
    }

    private val fast = PromotionTimeouts(compileMs = 3000, warmupMs = 3000, goldenMs = 3000, yieldMs = 1000)

    private fun key(rev: String = "r1") = AcceleratorKey(rev, "build-1", "Google Pixel 10", "tensor_g5", "c16_l64")

    /** A launch of the app: a new controller (its own launch id), started. */
    private fun launch(env: FakeEnv, store: File, n: Int, prep: Preparer = Preparer(), k: AcceleratorKey = key()): AcceleratorController {
        AcceleratorStore(store).sessionStarted = true
        return AcceleratorController(env, AcceleratorStore(store), prep, k, fast, "launch-$n").also { it.start() }
    }

    private fun sentinelFile(store: File) = File(store, "accelerator.sentinel")

    /** What a process that ended at [stage] leaves (the SDK's own sentinel; [fg] null: no field, as 3.1.4 wrote it). */
    private fun ended(store: File, stage: String, fg: Boolean?, k: AcceleratorKey = key()) {
        val m = linkedMapOf<String, Any?>("key" to k.id, "stage" to stage)
        if (fg != null) m["foreground"] = fg
        store.mkdirs()
        sentinelFile(store).writeText(Json.write(m))
    }

    /** A key in its first attempt that completed [done]. */
    private fun inAttempt(store: File, done: String, k: AcceleratorKey = key()) =
        AcceleratorStore(store).put(k, PromotionRecord(stage = done, attempt = 1, launch = "launch-0"))

    private fun record(store: File, k: AcceleratorKey = key()) = AcceleratorStore(store).record(k)!!

    /** The stage done before [stage] in a first attempt. */
    private fun before(stage: String) = when (stage) {
        Stages.COMPILE -> Stages.DOWNLOAD
        "load", Stages.WARMUP -> Stages.COMPILE
        Stages.GOLDEN -> Stages.WARMUP
        else -> Stages.DOWNLOAD
    }

    // ---- in the foreground, or not known ----

    @Test
    fun aStopAtAPreparationStageTakesTheRetryBudget() {
        for (stage in listOf(Stages.COMPILE, "load", Stages.WARMUP)) for (fg in listOf(true, null)) {
            val what = "$stage, foreground $fg"
            val store = dir()
            val env = FakeEnv()
            inAttempt(store, before(stage))
            ended(store, stage, fg)
            val acc = launch(env, store, 1)
            val r = record(store)
            assertEquals("$what: the record's status", PromotionRecord.FAILED, r.status)
            assertEquals("$what: failures counted", 1, r.failures)
            assertEquals("$what: the reason", Reasons.CRASH, r.reason)
            assertEquals("$what: the retry after the first backoff", env.now + 3_600_000L, r.nextAttemptAtMs)
            val d = acc.state.value as AcceleratorState.Disabled
            assertFalse("$what: off for good", d.persistent)
            assertNull("$what: the sentinel is still there", AcceleratorStore(store).leftSentinel())
            // the retry: an hour later, the whole pipeline again, and it promotes
            env.now += 3_600_000L
            val prep = Preparer()
            val next = launch(env, store, 2, prep)
            env.runDue()
            assertTrue("$what: not promoted at the retry: ${next.state.value}", next.state.value is AcceleratorState.Ready)
        }
    }

    @Test
    fun aSecondStopAtTheSameStageEndsTheKey() {
        val store = dir()
        val env = FakeEnv()
        inAttempt(store, Stages.DOWNLOAD)
        ended(store, Stages.COMPILE, true)
        launch(env, store, 1)
        assertEquals(PromotionRecord.FAILED, record(store).status)
        // the retry's attempt ends in the compile stage again
        env.now += 3_600_000L
        ended(store, Stages.COMPILE, true)
        val acc = launch(env, store, 2)
        assertEquals(PromotionRecord.FINAL, record(store).status)
        assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
        env.now += 1000L * 3_600_000L
        val prep = Preparer()
        launch(env, store, 3, prep)
        env.runDue()
        assertTrue("the key ran again: ${prep.calls}", prep.calls.isEmpty())
    }

    @Test
    fun aStopAtGoldenEndsTheKeyAsBefore() {
        for (fg in listOf(true, null)) {
            val store = dir()
            val env = FakeEnv()
            inAttempt(store, Stages.WARMUP)
            ended(store, Stages.GOLDEN, fg)
            val acc = launch(env, store, 1)
            assertEquals("foreground $fg", PromotionRecord.FINAL, record(store).status)
            assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
        }
    }

    @Test
    fun aStopAtASessionsFirstCallEndsItAsBefore() {
        for (fg in listOf(true, null)) {
            val store = dir()
            val env = FakeEnv()
            AcceleratorStore(store).put(key(), PromotionRecord(stage = Stages.PROMOTE, status = PromotionRecord.PROMOTED, attempt = 1, launch = "launch-0"))
            ended(store, Stages.SESSION, fg)
            val acc = launch(env, store, 1)
            assertEquals("foreground $fg", PromotionRecord.FINAL, record(store).status)
            assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
        }
    }

    // ---- in the background ----

    @Test
    fun aStopInTheBackgroundCountsNothingAndTheAttemptGoesOn() {
        for (stage in listOf(Stages.COMPILE, "load", Stages.WARMUP, Stages.GOLDEN)) {
            promotions.clear()
            val store = dir()
            val env = FakeEnv()
            inAttempt(store, before(stage))
            ended(store, stage, false)
            val prep = Preparer()
            val acc = launch(env, store, 1, prep)
            val r = record(store)
            assertEquals("$stage: the record's status", PromotionRecord.PENDING, r.status)
            assertEquals("$stage: failures counted", 0, r.failures)
            assertFalse("$stage: the state says off: ${acc.state.value}", acc.state.value is AcceleratorState.Disabled)
            assertTrue("$stage: counted as an attempt", promotions.none { it.countsAttempt })
            // the attempt goes on at this launch, from the stage its record says
            env.runDue()
            assertTrue("$stage: not promoted: ${acc.state.value}", acc.state.value is AcceleratorState.Ready)
            assertFalse("$stage: the download ran again", Stages.DOWNLOAD in prep.calls)
        }
    }

    /** A session's first accelerator call: a stop there in the background counts nothing until the third in a row,
     *  also when the build is loaded and warmed again at every launch between them; a first call that comes back ends
     *  the row. */
    @Test
    fun aStopInTheBackgroundAtASessionsFirstCallCountsFromTheThirdInARow() {
        val store = dir()
        val env = FakeEnv()
        AcceleratorStore(store).put(key(), PromotionRecord(stage = Stages.PROMOTE, status = PromotionRecord.PROMOTED, attempt = 1, launch = "launch-0"))
        var n = 0
        fun stopAndLaunch(): AcceleratorController {
            ended(store, Stages.SESSION, false)
            return launch(env, store, ++n)
        }
        for (i in 1..2) {
            val acc = stopAndLaunch()
            assertEquals("stop $i: status", PromotionRecord.PROMOTED, record(store).status)
            env.runDue()
            assertTrue("stop $i: not loaded again: ${acc.state.value}", acc.state.value is AcceleratorState.Ready)
        }
        // a launch whose first call comes back: the row ends
        val ok = launch(env, store, ++n)
        env.runDue()
        ok.firstCall(false)
        ok.firstCall(true)
        for (i in 1..2) {
            val acc = stopAndLaunch()
            assertEquals("after the row ended, stop $i: status", PromotionRecord.PROMOTED, record(store).status)
            env.runDue()
            assertTrue(acc.state.value is AcceleratorState.Ready)
        }
        val acc = stopAndLaunch()
        assertEquals("the third stop in a row", PromotionRecord.FINAL, record(store).status)
        assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
    }

    @Test
    fun theSentinelSaysTheForegroundAndTheHookRewritesItWhileItIsArmed() {
        val store = dir()
        val env = FakeEnv()
        val seen = Collections.synchronizedList(ArrayList<Any?>())
        fun now() = (Json.parse(sentinelFile(store).readText()) as Map<*, *>)["foreground"]
        val prep = Preparer(compile = {
            seen.add(now())
            env.appForeground(false)
            seen.add(now())
            env.appForeground(true)
            seen.add(now())
        })
        val acc = launch(env, store, 1, prep)
        env.runDue()
        assertEquals("the sentinel's foreground while the compile ran", listOf<Any?>(true, false, true), seen.toList())
        assertTrue(acc.state.value is AcceleratorState.Ready)
        assertFalse("the sentinel is still there", sentinelFile(store).exists())
        // no sentinel is armed: the hook writes none
        env.appForeground(false)
        assertFalse("the hook wrote a sentinel", sentinelFile(store).exists())
    }

    @Test
    fun threeStopsInTheBackgroundInARowCountAsOneInTheForegroundAndTheNextEndsTheKey() {
        val store = dir()
        val env = FakeEnv()
        inAttempt(store, Stages.DOWNLOAD)
        for (n in 1..2) {
            ended(store, Stages.COMPILE, false)
            launch(env, store, n)
            assertEquals("stop $n: status", PromotionRecord.PENDING, record(store).status)
            assertEquals("stop $n: failures", 0, record(store).failures)
        }
        ended(store, Stages.COMPILE, false)
        launch(env, store, 3)
        assertEquals("the third stop: status", PromotionRecord.FAILED, record(store).status)
        assertEquals("the third stop: failures", 1, record(store).failures)
        assertEquals("the third stop: the first backoff", env.now + 3_600_000L, record(store).nextAttemptAtMs)
        env.now += 3_600_000L
        ended(store, Stages.COMPILE, false)
        val acc = launch(env, store, 4)
        assertEquals("the fourth stop in the same row", PromotionRecord.FINAL, record(store).status)
        assertTrue((acc.state.value as AcceleratorState.Disabled).persistent)
    }

    // ---- the report ----

    @Test
    fun theReportSaysWhetherTheAppWasInTheForeground() {
        for (fg in listOf(true, false, null)) {
            promotions.clear()
            val store = dir()
            val env = FakeEnv()
            inAttempt(store, Stages.DOWNLOAD)
            ended(store, Stages.COMPILE, fg)
            launch(env, store, 1)
            val e = promotions.single { !it.promoted }
            val d = IssueQueue.detail(e.incident!!)
            assertEquals("foreground $fg: the cause", "crash_on_last_run", d["cause"])
            assertEquals("foreground $fg: the stage", Stages.COMPILE, d["stage"])
            assertEquals("foreground $fg: detail.foreground", fg, d["foreground"])
            assertEquals("foreground $fg: the field is there", fg != null, d.containsKey("foreground"))
        }
    }

    /** The reload of the build (`load`) is the compile stage's: the report names `compile`, not a session. */
    @Test
    fun aStopAtTheReloadIsReportedAtTheCompileStage() {
        val store = dir()
        val env = FakeEnv()
        inAttempt(store, before("load"))
        ended(store, "load", true)
        launch(env, store, 1)
        val e = promotions.single { !it.promoted }
        assertEquals("the event's stage", Stages.COMPILE, e.stage)
        assertEquals("the report's stage", Stages.COMPILE, IssueQueue.detail(e.incident!!)["stage"])
    }

    // ---- the upgrade from 3.1.4 ----

    @Test
    fun theUpgradeGivesAStopAtAPreparationStageItsRetryBack() {
        val store = dir()
        store.mkdirs()
        fun rec(reason: String, stage: String, failures: Int = 1) = PromotionRecord(stage = "", status = PromotionRecord.FINAL, attempt = 1,
            failures = failures, reason = reason, failedStage = stage, detail = "the process died during $stage", launch = "launch-0").toJson()
        val back = listOf(key("r1") to Stages.COMPILE, key("r2") to "load", key("r3") to Stages.WARMUP)
        val stay = listOf(key("r4") to rec(Reasons.CRASH, Stages.GOLDEN), key("r5") to rec(Reasons.GOLDEN_MISMATCH, Stages.GOLDEN),
            key("r6") to rec(Reasons.CRASH, Stages.COMPILE, failures = 2), key("r7") to rec(Reasons.CRASH, Stages.SESSION))
        val keys = LinkedHashMap<String, Any?>()
        for ((k, s) in back) keys[k.id] = rec(Reasons.CRASH, s)
        for ((k, j) in stay) keys[k.id] = j
        // the records file as 3.1.4 wrote it
        File(store, "accelerator.json").writeText(Json.write(linkedMapOf("format" to AcceleratorStore.FORMAT,
            "install" to mapOf("session_started" to true), "upgrades" to listOf("2.0.2"), "keys" to keys)))
        val env = FakeEnv()
        for ((k, s) in back) {
            val acc = launch(env, store, 1, k = k)
            assertEquals("$s: the status after the upgrade", PromotionRecord.FAILED, record(store, k).status)
            assertEquals("$s: failures", 1, record(store, k).failures)
            env.runDue()
            assertTrue("$s: no retry at this launch: ${acc.state.value}", acc.state.value is AcceleratorState.Ready)
        }
        for ((k, _) in stay) {
            launch(env, store, 1, k = k)
            env.runDue()
            assertEquals("${k.revision}: stays final", PromotionRecord.FINAL, record(store, k).status)
        }
        // once per records file: a key ended later in the same way stays ended
        val again = key("r8")
        AcceleratorStore(store).put(again, PromotionRecord(status = PromotionRecord.FINAL, attempt = 1, failures = 1, reason = Reasons.CRASH,
            failedStage = Stages.COMPILE, launch = "launch-0"))
        launch(env, store, 2, k = again)
        assertEquals(PromotionRecord.FINAL, record(store, again).status)
    }
}
