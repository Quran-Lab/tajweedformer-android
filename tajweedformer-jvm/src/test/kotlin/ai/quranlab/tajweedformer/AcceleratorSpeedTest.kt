package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.RemoteConfig
import ai.quranlab.tajweedformer.cloud.Telemetry
import ai.quranlab.tajweedformer.cloud.TelemetryContext
import ai.quranlab.tajweedformer.core.AcceleratorHost
import ai.quranlab.tajweedformer.core.AcceleratorPlan
import ai.quranlab.tajweedformer.core.AcceleratorRecords
import ai.quranlab.tajweedformer.core.AcceleratorSpeed
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreException
import ai.quranlab.tajweedformer.core.GoldenRuns
import ai.quranlab.tajweedformer.core.PromotionInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDK 4.0's promotion of the accelerator after its golden test (Quran Lab's decision, with the Apple
 * SDK's names, f1c15e1): the measure stage keeps a build that is slower than the CPU engine on the device off the
 * accelerator (`slower_than_cpu`, the program released, both medians in the telemetry), promotes one that is as fast
 * or faster, takes `flags.accel_min_speedup` with its floor of 1.0; the verdict is transient (another SDK version or OS
 * build tries again at once, else after 30 days); and a prepare the runtime refuses or does not answer (the Pixel 10's
 * launch firmware: its dispatch times out) leaves the CPU engine at once, held until an SDK or OS update.
 *
 * The pipeline runs through [Recognizer] on a fake engine that is an [AcceleratorHost] (the desktop Core has no
 * accelerator runtime); the medians and the verdicts are what the real engine hands it.
 */
class AcceleratorSpeedTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-speed-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    /** An engine with a program to promote: its golden runs match, with the given chunk times. */
    private class Host(
        val acceleratorMs: List<Double>,
        val cpuMs: List<Double>,
        val prepare: () -> String = { "npu:tensor_g5" },
    ) : TajweedEngine, AcceleratorHost {
        val prepares = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile var promoted = false
        override val chunkMs: Int get() = 320
        override val backend: Backend get() = if (promoted) Backend.NPU else Backend.CPU

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> = emptyList()
            override fun finish(): List<HeadValues> = listOf(heads())
            override fun reset() {}
            override fun close() {}
        }

        override fun prepareAccelerator(kind: String, dispatchDir: String?): String {
            prepares.incrementAndGet()
            return prepare()
        }

        override fun goldenRuns(clips: List<Pair<String, FloatArray>>): GoldenRuns =
            GoldenRuns(clips.map { (id, _) -> Triple(id, listOf(heads()), listOf(heads())) }, acceleratorMs, cpuMs)

        override fun promote() { promoted = true }

        override fun releaseAccelerator() {
            promoted = false
            releases.incrementAndGet()
        }

        @Volatile override var onFailover: (String) -> Unit = {}

        override fun close() {}

        private fun heads(): HeadValues {
            val lp = FloatArray(6 * HeadId.LOG_PROBS.width) { -10f }
            for (f in 0 until 6) lp[f * HeadId.LOG_PROBS.width + if (f % 2 == 0) 5 else 0] = -0.1f
            return HeadValues(6, mapOf(HeadId.LOG_PROBS to lp))
        }
    }

    private val folder: ModelFolder by lazy {
        val dir = temp()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        ModelFolder.open(dir, TestKit.verifier)
    }

    private val records: AcceleratorRecords by lazy { AcceleratorRecords(File(temp(), "accel.json")) }
    private var now = 1_800_000_000_000L
    private val reports = LinkedBlockingQueue<PromotionInfo>()

    private fun plan(minSpeedup: Double? = null, sdk: String = "4.0.0", os: String = "frankel:16/BD1A.250702.001", deadlineMs: Long = 5_000) =
        AcceleratorPlan("tensor_g5", null, null, clips = { listOf("Husary_001001" to FloatArray(16_000)) }, records = records,
            report = { reports.add(it) }, minSpeedup = { minSpeedup }, sdk = sdk, os = os, clock = { now }, prepareDeadlineMs = deadlineMs)

    /** A recognizer on [host] promoting by [plan]; returns the promotion's outcome (null: no attempt in [waitMs]). */
    private fun run(host: Host, plan: AcceleratorPlan, waitMs: Long = 10_000): Pair<Recognizer, PromotionInfo?> {
        val rec = Recognizer.open(folder, Recognizer.Options(accelerator = plan, soc = "tensor_g5", engine = { _, _, _ -> host }))
        return rec to reports.poll(waitMs, TimeUnit.MILLISECONDS)
    }

    private fun key(rec: Recognizer) = "${folder.manifest.build}:npu:tensor_g5:${rec.context}"

    /** a device lab's Pixel 10: 61 ms a chunk on the NPU, 6 ms on the CPU engine. */
    private val pixel10Npu = List(30) { 61.0 + (it % 3) }
    private val pixel10Cpu = List(30) { 6.0 + (it % 3) * 0.1 }

    @Test
    fun theRule() {
        assertEquals(1.0, AcceleratorSpeed.minSpeedup(null), 0.0)
        assertEquals("never below 1.0", 1.0, AcceleratorSpeed.minSpeedup(0.5), 0.0)
        assertEquals(1.0, AcceleratorSpeed.minSpeedup(Double.NaN), 0.0)
        assertEquals(1.0, AcceleratorSpeed.minSpeedup(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(1.5, AcceleratorSpeed.minSpeedup(1.5), 0.0)
        assertTrue(AcceleratorSpeed.slower(61.0, 6.0, 1.0))
        assertFalse("exactly as fast: promoted", AcceleratorSpeed.slower(6.0, 6.0, 1.0))
        assertFalse(AcceleratorSpeed.slower(5.0, 6.0, 1.0))
        assertTrue("1.2 x faster, 1.5 asked", AcceleratorSpeed.slower(5.0, 6.0, 1.5))
        assertFalse("nothing measured: the golden test decides", AcceleratorSpeed.slower(0.0, 6.0, 1.0))
        assertEquals(2.0, AcceleratorSpeed.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, AcceleratorSpeed.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(0.0, AcceleratorSpeed.median(emptyList()), 0.0)
        // the signed config's number
        val f = RemoteConfig.Flags.parse(mapOf("accel_min_speedup" to 1.25))
        assertEquals(1.25, f.accelMinSpeedup!!, 0.0)
        assertNull(RemoteConfig.Flags.parse(mapOf("accel_min_speedup" to "2")).accelMinSpeedup)
        assertNull(RemoteConfig.Flags.parse(emptyMap<String, Any?>()).accelMinSpeedup)
    }

    @Test
    fun aTransientVerdictHoldsUntilAnSdkOrOsUpdateOrThirtyDays() {
        val k = "b:npu:tensor_g5:c16_l64"
        records.hold(k, AcceleratorRecords.SLOWER, "measure", "4.0.0", "os1", now)
        assertTrue(records.held(k, "4.0.0", "os1", now))
        assertFalse("not a failure of the build", records.blocked(k))
        assertTrue(records.held(k, "4.0.0", "os1", now + AcceleratorSpeed.RETRY_MS - 1))
        assertFalse("30 days", records.held(k, "4.0.0", "os1", now + AcceleratorSpeed.RETRY_MS))
        assertFalse("another SDK version", records.held(k, "4.0.1", "os1", now))
        assertFalse("an OS update", records.held(k, "4.0.0", "os2", now))
        // a failure for good is not a transient verdict
        records.end(k, AcceleratorRecords.FAILED, "golden")
        assertFalse(records.held(k, "4.0.0", "os1", now))
        assertTrue(records.blocked(k))
    }

    @Test
    fun anAcceleratorSlowerThanTheCpuEngineIsNotPromoted() {
        val host = Host(pixel10Npu, pixel10Cpu)
        val (rec, p) = run(host, plan())
        try {
            p!!
            assertFalse(p.promoted)
            assertEquals("slower_than_cpu", p.reason)
            assertEquals("measure", p.failedAt)
            assertEquals(62.0, p.acceleratorMs!!, 0.0)
            assertEquals(6.1, p.cpuMs!!, 1e-9)
            assertTrue(p.detail!!, p.detail!!.contains("62.0 ms") && p.detail!!.contains("6.1 ms"))
            assertEquals("the program let go", 1, host.releases.get())
            assertFalse(host.promoted)
            assertEquals(Backend.CPU, rec.backend)
            assertEquals("slower_than_cpu", rec.backendChoice.reason)
            val e = records.entry(key(rec))!!
            assertEquals(AcceleratorRecords.SLOWER, e["state"])
            assertEquals(62.0, e["accelerator_ms"])
            assertEquals(6.1, e["cpu_ms"] as Double, 1e-9)
            assertTrue(records.held(key(rec), "4.0.0", "frankel:16/BD1A.250702.001", now))
        } finally {
            rec.close()
        }
        // the next start: no attempt, no prepare (no 200 MB, no seconds)
        val again = Host(pixel10Npu, pixel10Cpu)
        val (r2, p2) = run(again, plan(), waitMs = 500)
        r2.close()
        assertNull(p2)
        assertEquals(0, again.prepares.get())
        // tried again at once by another SDK version, and by an OS update; after 30 days by the same
        for ((what, pl) in listOf("SDK" to plan(sdk = "4.0.1"), "OS" to plan(os = "frankel:17/CP2A.260605.012"))) {
            val h = Host(pixel10Npu, pixel10Cpu)
            val (r, p3) = run(h, pl)
            r.close()
            assertEquals(what, "slower_than_cpu", p3?.reason)
            assertEquals(what, 1, h.prepares.get())
        }
        // the verdicts above are of 4.0.1 and the new OS: the first one again, 30 days on
        records.hold(key(r2), AcceleratorRecords.SLOWER, "measure", "4.0.0", "frankel:16/BD1A.250702.001", now)
        now += AcceleratorSpeed.RETRY_MS
        val late = Host(List(30) { 5.0 }, pixel10Cpu)
        val (r4, p4) = run(late, plan())
        try {
            assertTrue("30 days on, and faster now: promoted", p4!!.promoted)
            assertEquals(Backend.NPU, r4.backend)
        } finally {
            r4.close()
        }
    }

    @Test
    fun anAcceleratorAsFastAsTheCpuEngineIsPromotedAndTheConfigMayAskForMore() {
        val host = Host(List(20) { 6.0 }, List(20) { 6.0 })
        val (rec, p) = run(host, plan())
        try {
            assertTrue(p!!.promoted)
            assertEquals(6.0, p.acceleratorMs!!, 0.0)
            assertEquals(Backend.NPU, rec.backend)
            assertEquals(AcceleratorRecords.PASSED, records.entry(key(rec))!!["state"])
            assertEquals(0, host.releases.get())
        } finally {
            rec.close()
        }
        // 1.5 asked, 1.2 measured: kept off; the config's 0.5 is 1.0
        val other = AcceleratorRecords(File(temp(), "accel.json"))
        val asked = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) }, records = other,
            report = { reports.add(it) }, minSpeedup = { 1.5 }, os = "os", clock = { now })
        val (r2, p2) = run(Host(List(20) { 5.0 }, List(20) { 6.0 }), asked)
        r2.close()
        assertEquals("slower_than_cpu", p2!!.reason)
        val loose = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) },
            records = AcceleratorRecords(File(temp(), "accel.json")), report = { reports.add(it) }, minSpeedup = { 0.5 }, os = "os", clock = { now })
        val (r3, p3) = run(Host(List(20) { 7.0 }, List(20) { 6.0 }), loose)
        r3.close()
        assertEquals("never below 1.0", "slower_than_cpu", p3!!.reason)
    }

    @Test
    fun aRefusedDispatchFallsBackAtOnceAndIsTriedAgainAfterAnOsUpdate() {
        // the launch firmware: the vendor's dispatch times out inside the prepare, the Core says the runtime refused
        val refused = Host(pixel10Cpu, pixel10Cpu, prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_PROGRAM, "qlc_accel_prepare") })
        val (rec, p) = run(refused, plan())
        try {
            assertFalse(p!!.promoted)
            assertEquals("compile", p.failedAt)
            assertEquals("compile_error", p.reason)
            assertEquals(Backend.CPU, rec.backend)
            assertEquals(AcceleratorRecords.UNSUPPORTED, records.entry(key(rec))!!["state"])
            assertFalse("not off for good", records.blocked(key(rec)))
        } finally {
            rec.close()
        }
        val (r2, p2) = run(Host(pixel10Cpu, pixel10Cpu), plan(), waitMs = 500)
        r2.close()
        assertNull("the same firmware: not tried again", p2)
        val fixed = Host(List(20) { 5.0 }, pixel10Cpu)
        val (r3, p3) = run(fixed, plan(os = "frankel:17/CP2A.260605.012"))
        try {
            assertTrue("the OS update runs it", p3!!.promoted)
        } finally {
            r3.close()
        }
        // a program that breaks the host contract is a fault of the build: off for good
        val broken = Host(pixel10Cpu, pixel10Cpu, prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_CONTRACT, "qlc_accel_prepare") })
        val other = AcceleratorRecords(File(temp(), "accel.json"))
        val pl = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) }, records = other,
            report = { reports.add(it) }, os = "os", clock = { now })
        val (r4, p4) = run(broken, pl)
        r4.close()
        assertEquals("load_error", p4!!.reason)
        assertTrue(other.blocked(key(r4)))
    }

    /**
     * Core 69276aa: QLC_D_ACCEL_FIRMWARE (7), the Pixel 10 family's launch firmware refused in milliseconds, is kept for
     * that firmware build: no prepare and no download until the OS updates, whatever the SDK or the time; and
     * QLC_D_ACCEL_DISPATCH (8), a service that did not answer, is the CPU engine now and asked again at the next start.
     */
    @Test
    fun theCoresFirmwareAndDispatchRefusals() {
        val launch = "google/frankel/frankel:16/BD1A.250702.001/13724644:user/release-keys"
        val fw = Host(pixel10Cpu, pixel10Cpu, prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_FIRMWARE, "qlc_accel_prepare") })
        val (rec, p) = run(fw, plan(os = launch))
        val k = key(rec)
        try {
            assertFalse(p!!.promoted)
            assertEquals("compile", p.failedAt)
            assertEquals("firmware", p.reason)
            assertEquals("accel_firmware", p.detail)
            assertEquals(Backend.CPU, rec.backend)
            assertEquals(AcceleratorRecords.FIRMWARE, records.entry(k)!!["state"])
        } finally {
            rec.close()
        }
        assertTrue(records.held(k, "4.0.0", launch, now))
        assertTrue("another SDK version: the firmware is the same", records.held(k, "4.0.1", launch, now))
        assertTrue("a year on: the firmware is the same", records.held(k, "4.0.0", launch, now + 365 * 86_400_000L))
        assertFalse("a system update", records.held(k, "4.0.0", "google/frankel/frankel:17/CP2A.260605.012/15430684:user/release-keys", now))
        val (r2, p2) = run(Host(pixel10Cpu, pixel10Cpu), plan(os = launch, sdk = "4.0.1"), waitMs = 500)
        r2.close()
        assertNull("not asked again on that firmware", p2)
        val updated = Host(List(20) { 5.0 }, pixel10Cpu)
        val (r3, p3) = run(updated, plan(os = "google/frankel/frankel:17/CP2A.260605.012/15430684:user/release-keys"))
        try {
            assertTrue("the OS update: tried again", p3!!.promoted)
            assertEquals(1, updated.prepares.get())
        } finally {
            r3.close()
        }
        // the dispatch's service did not answer: the CPU engine, not remembered past the process
        val other = AcceleratorRecords(File(temp(), "accel.json"))
        fun dispatchPlan() = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) }, records = other,
            report = { reports.add(it) }, os = "os", clock = { now })
        val silent = Host(pixel10Cpu, pixel10Cpu, prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_DISPATCH, "qlc_accel_prepare") })
        val (r4, p4) = run(silent, dispatchPlan())
        r4.close()
        assertEquals("dispatch", p4!!.reason)
        assertEquals("accel_dispatch", p4.detail)
        assertFalse(other.blocked(key(r4)))
        assertFalse(other.held(key(r4), "4.0.0", "os", now))
        val again = Host(pixel10Cpu, pixel10Cpu, prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_DISPATCH, "qlc_accel_prepare") })
        val (r5, p5) = run(again, dispatchPlan())
        r5.close()
        assertEquals("asked again at the next start", 1, again.prepares.get())
        assertEquals("dispatch", p5!!.reason)
        // both are reasons of their own in the telemetry
        val t = Telemetry(File(temp(), "telemetry")) { now }
        t.promotion(p!!)
        t.promotion(p4)
        @Suppress("UNCHECKED_CAST")
        val e = (t.peek(TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel10", "tensor_g5", 16, "16")))!!["promotions"]
            as List<Map<String, Any?>>).single()
        assertEquals(mapOf("firmware" to 1.0, "dispatch" to 1.0), e["reasons"])
        assertEquals(mapOf("compile" to 2.0), e["failed_at"])
    }

    @Test
    fun aDispatchThatDoesNotAnswerIsGivenUpAndItsLateProgramReleased() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val hung = Host(List(20) { 5.0 }, pixel10Cpu, prepare = { gate.await(30, TimeUnit.SECONDS); "npu:tensor_g5" })
        val t0 = System.nanoTime()
        val (rec, p) = run(hung, plan(deadlineMs = 300))
        try {
            val waited = (System.nanoTime() - t0) / 1e6
            assertFalse(p!!.promoted)
            assertEquals("compile_timeout", p.reason)
            assertTrue("given up near its deadline: $waited ms", waited < 5_000)
            assertEquals(Backend.CPU, rec.backend)
            assertEquals(AcceleratorRecords.UNSUPPORTED, records.entry(key(rec))!!["state"])
            // the answer comes late: the program is released, never promoted
            gate.countDown()
            val until = System.currentTimeMillis() + 5_000
            while (hung.releases.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(1, hung.releases.get())
            assertFalse(hung.promoted)
            assertEquals(Backend.CPU, rec.backend)
        } finally {
            rec.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theTelemetryCountsTheAttemptAndListsBothMedians() {
        val ctx = TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel10", "tensor_g5", 16, "16"))
        val dir = File(temp(), "telemetry")
        val t = Telemetry(dir) { now }
        repeat(10) { i ->
            t.promotion(PromotionInfo("tensor_g5", "b".repeat(40), "c16_l64", promoted = false, failedAt = "measure",
                reason = "slower_than_cpu", compileMs = 1498.7, version = "4.0.0", acceleratorMs = 61.04 + i, cpuMs = 6.06))
        }
        t.promotion(PromotionInfo("tensor_g5", "b".repeat(40), "c16_l64", promoted = true, compileMs = 385.9, version = "4.0.0",
            acceleratorMs = 3.0, cpuMs = 6.0))
        val body = t.peek(ctx)!!
        val p = (body["promotions"] as List<Map<String, Any?>>).single()
        assertEquals(11.0, p["attempts"])
        assertEquals(1.0, p["promoted"])
        assertEquals(mapOf("slower_than_cpu" to 10.0), p["reasons"])
        assertFalse("the measure stage is not one of the funnel's stages", p.containsKey("failed_at"))
        assertEquals(mapOf("lt_0_5" to 10.0, "le_2" to 1.0), p["speedup"])
        val slower = p["slower_than_cpu"] as List<Map<String, Any?>>
        assertEquals("at most 8", 8, slower.size)
        assertEquals(mapOf("accelerator_ms" to 61.0, "cpu_ms" to 6.1), slower[0])
        assertEquals(mapOf("accelerator_ms" to 68.0, "cpu_ms" to 6.1), slower[7])
        // kept across a restart of the process (the day's file)
        val again = Telemetry(dir) { now }
        val q = (again.peek(ctx)!!["promotions"] as List<Map<String, Any?>>).single()
        assertEquals(slower, q["slower_than_cpu"])
    }
}
