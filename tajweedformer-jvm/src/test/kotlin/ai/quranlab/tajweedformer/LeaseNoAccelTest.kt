package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.AcceleratorHost
import ai.quranlab.tajweedformer.core.AcceleratorPlan
import ai.quranlab.tajweedformer.core.AcceleratorRecords
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
 * Contract 21.6 (SDK 4.0.2): a lease that does not allow accelerator programs (flags bit 3 clear: `qlc_accel_allowed`
 * answers 0, or the Core refuses a prepare with QLC_E_LEASE and QLC_D_LEASE_NO_ACCEL) is not a failure. Through
 * [Recognizer] on a fake engine that is an [AcceleratorHost], as AcceleratorSpeedTest.
 *
 * What Android 4.0.1 did (its code, run once to make `accel_401_lease_no_accel.json`): never `failed`, never held, no
 * backoff (Recognizer.promote ended any status but QLC_E_ENGINE as `waiting`); but each refusal was reported as a
 * promotion attempt (`compile`, `other`, detail `lease_no_accel`), and a promoted key lost its pass (`waiting` replaced
 * `passed`), so the next lease that allowed programs ran the golden test again, and a build with no self-test set could
 * never be promoted again.
 */
class LeaseNoAccelTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-noaccel-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private class Host(val prepare: () -> String = { "npu:tensor_g4" }) : TajweedEngine, AcceleratorHost {
        val prepares = AtomicInteger()
        val goldens = AtomicInteger()
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

        override fun goldenRuns(clips: List<Pair<String, FloatArray>>): GoldenRuns {
            goldens.incrementAndGet()
            return GoldenRuns(clips.map { (id, _) -> Triple(id, listOf(heads()), listOf(heads())) }, List(20) { 5.0 }, List(20) { 6.0 })
        }

        override fun promote() { promoted = true }
        override fun releaseAccelerator() { promoted = false }
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

    private val accelFile: File by lazy { File(temp(), "accel.json") }
    private val records: AcceleratorRecords by lazy { AcceleratorRecords(accelFile) }
    private val reports = LinkedBlockingQueue<PromotionInfo>()
    private val noAccel = AtomicInteger()
    @Volatile private var allowed = true

    private fun plan() = AcceleratorPlan("tensor_g4", null, null, clips = { listOf("Husary_001001" to FloatArray(16_000)) }, records = records,
        report = { reports.add(it) }, sdk = "4.0.2", os = "frankel:16/BD1A.250702.001", clock = { 1_800_000_000_000L },
        allowed = { allowed }, leaseNoAccel = { noAccel.incrementAndGet() })

    private fun open(host: Host) = Recognizer.open(folder, Recognizer.Options(accelerator = plan(), soc = "tensor_g4", engine = { _, _, _ -> host }))

    private fun key(rec: Recognizer) = "${folder.manifest.build}:npu:tensor_g4:${rec.context}"

    /** Waits until [n] times [what] happened (the promotion runs on a thread of its own). */
    private fun until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test
    fun aLeaseWithoutProgramsThenOneWithThem() {
        allowed = false
        val host = Host()
        val rec = open(host)
        try {
            until { noAccel.get() > 0 }
            assertEquals("said once, as a denial", 1, noAccel.get())
            assertEquals("no attempt: no prepare", 0, host.prepares.get())
            assertNull("no promotion report (no failure, no attempt)", reports.poll(300, TimeUnit.MILLISECONDS))
            assertNull("nothing stored for the key: pending", records.entry(key(rec)))
            assertEquals(Backend.CPU, rec.backend)
            // a lease with bit 3: the key goes on from where it stood, the attempt runs
            allowed = true
            rec.promoteAgain()
            val p = reports.poll(10, TimeUnit.SECONDS)!!
            assertTrue("$p", p.promoted)
            assertEquals(AcceleratorRecords.PASSED, records.entry(key(rec))!!["state"])
            until { rec.backend == Backend.NPU }
            assertEquals(Backend.NPU, rec.backend)
        } finally {
            rec.close()
        }
    }

    @Test
    fun theSameRefusalTenTimesNeverFailsTheKey() {
        // the race of 21.6: the lease said yes when the attempt was asked for, the Core's prepare refuses for the lease
        val refusing = Host { throw CoreException(Core.E_LEASE, Core.D_LEASE_NO_ACCEL, "qlc_accel_prepare") }
        repeat(10) { i ->
            val rec = open(refusing)
            try {
                until { noAccel.get() > i }
                assertEquals(i + 1, refusing.prepares.get())
                assertFalse(records.blocked(key(rec)))
                assertFalse(plan().held(key(rec)))
                assertNull("the key as it stood: never tried", records.entry(key(rec)))
                assertEquals(Backend.CPU, rec.backend)
            } finally {
                rec.close()
            }
        }
        assertEquals(10, noAccel.get())
        assertNull("no attempt counted under any reason", reports.poll(300, TimeUnit.MILLISECONDS))
        // and the key then promotes as one never tried
        val rec = open(Host())
        try {
            assertTrue(reports.poll(10, TimeUnit.SECONDS)!!.promoted)
        } finally {
            rec.close()
        }
    }

    @Test
    fun aPromotedKeyStaysPromotedUnderALeaseWithoutPrograms() {
        val first = Host()
        open(first).let { rec ->
            try {
                assertTrue(reports.poll(10, TimeUnit.SECONDS)!!.promoted)
                assertEquals(1, first.goldens.get())
            } finally {
                rec.close()
            }
        }
        val refusing = Host { throw CoreException(Core.E_LEASE, Core.D_LEASE_NO_ACCEL, "qlc_accel_prepare") }
        open(refusing).let { rec ->
            try {
                until { noAccel.get() > 0 }
                assertEquals(Backend.CPU, rec.backend)
                // 4.0.1 wrote `waiting` here (accel_401_lease_no_accel.json)
                assertEquals(AcceleratorRecords.PASSED, records.entry(key(rec))!!["state"])
                assertNull(reports.poll(300, TimeUnit.MILLISECONDS))
            } finally {
                rec.close()
            }
        }
        // a lease that allows programs: the build loads again with no golden test
        val again = Host()
        open(again).let { rec ->
            try {
                assertTrue(reports.poll(10, TimeUnit.SECONDS)!!.promoted)
                assertEquals("no golden test for a promoted key", 0, again.goldens.get())
                assertEquals(1, again.prepares.get())
            } finally {
                rec.close()
            }
        }
    }

    /** One attempt per engine: an engine opened again for a take at another rate starts its own attempt while the old
     *  engine's prepare has not answered (as 4.0.1), and the old one ends without promoting anything. */
    @Test
    fun anEngineOpenedForAnotherRateStartsItsOwnAttempt() {
        val held = java.util.concurrent.CountDownLatch(1)
        val first = Host { held.await(20, TimeUnit.SECONDS); "npu:tensor_g4" }
        val second = Host()
        val hosts = java.util.concurrent.ConcurrentLinkedQueue(listOf(first, second))
        val rec = Recognizer.open(folder, Recognizer.Options(accelerator = plan(), soc = "tensor_g4", engine = { _, _, _ -> hosts.poll()!! }))
        try {
            until { first.prepares.get() > 0 }
            rec.startSession(48_000).cancel()
            val p = reports.poll(10, TimeUnit.SECONDS)!!
            assertTrue("$p", p.promoted)
            assertEquals("the new engine's own attempt", 1, second.prepares.get())
            assertEquals(Backend.NPU, second.backend)
        } finally {
            held.countDown()
            rec.close()
        }
    }

    /**
     * 21.6 item 4 on 4.0.1's own file: `accel_401_lease_no_accel.json` is what 4.0.1's code wrote (run once on its
     * sources, 3fe2f10 plus the 4.0.1 NoWaitTest commit 4f30872, through Recognizer.promote with a host whose prepare
     * throws QLC_E_LEASE / QLC_D_LEASE_NO_ACCEL): a key never tried, and a key that had passed, both left `waiting`. After
     * the migration both are pending (no entry) and a verdict of another reason stays.
     */
    @Test
    fun the401RecordsOfTheRefusalReadAsPending() {
        val src = javaClass.getResourceAsStream("/accel_401_lease_no_accel.json")!!.use { it.readBytes() }
        accelFile.parentFile.mkdirs()
        accelFile.writeBytes(src)
        @Suppress("UNCHECKED_CAST")
        val stored = Json.parse(String(src, Charsets.UTF_8)) as Map<String, Map<String, Any?>>
        assertEquals(2, stored.size)
        assertTrue(stored.values.all { it["state"] == "waiting" && (it["why"] as String).endsWith("(lease_no_accel)") })
        records.hold("other:npu:tensor_g4:c16_l64", AcceleratorRecords.UNSUPPORTED, "golden run: engine", "4.0.1", "os", 1_800_000_000_000L)
        assertEquals(2, records.migrateLeaseNoAccel())
        for (k in stored.keys) assertNull(k, records.entry(k))
        assertEquals(AcceleratorRecords.UNSUPPORTED, records.entry("other:npu:tensor_g4:c16_l64")!!["state"])
        assertEquals("once", 0, records.migrateLeaseNoAccel())
    }
}
