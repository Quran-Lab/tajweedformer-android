package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorPreparer
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.GoldenRun
import ai.quranlab.tajweedformer.accel.PreparedAccelerator
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
 * The golden stage's self-test event carries the measures as the record a device would report (the server notes,
 * "The golden measures a device would report"): exactly those fields, with those names, in those units, in the
 * number form of the contract. Nothing sends it; the text is what a bench run prints.
 *
 * The run is a real golden stage of the accelerator pipeline over observations made here, small enough that every
 * measure is worked out by hand in the test.
 */
class GoldenMeasuresTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val selfTests = Collections.synchronizedList(ArrayList<SelfTestResult>())
    private val observer = object : SdkObserver {
        override fun onSelfTest(result: SelfTestResult) { selfTests.add(result) }
    }

    @Before
    fun setUp() = SdkEvents.add(observer)

    @After
    fun tearDown() {
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir() = Files.createTempDirectory("tj-golden-").toFile().also { dirs.add(it) }

    private val env = object : AcceleratorEnvironment {
        override val osBuild = "google/frankel/frankel:16/BD1A.250101.001/12345678:user/release-keys"
        override val deviceModel = "Google Pixel 10"
        override val deviceModelCode = "Pixel 10"
        override val osBuildReport = "16/BD1A.250101.001/12345678"
        override val osVersion = "16"
        override val soc = "tensor_g5"
        override val delegate = "LiteRtDispatch_GoogleTensor litert 2.2.0"
        override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) = work()
    }

    private val clips = SelfTestSet.REQUIRED
    private val planted = setOf(SelfTestSet.PLANTED_CLIP)
    private val expected = SelfTestSet.Expected("c16_l64", clips.associateWith { SelfTestSet.ClipExpected(listOf(3, 32, 13)) })

    /** A clip's heads: 16 frames of `log_probs` reading [tokens], the other base heads zero. [change] alters a copy. */
    private fun heads(tokens: List<Int> = listOf(3, 32, 13), change: (Map<HeadId, FloatArray>) -> Unit = {}): HeadValues {
        val h = TestKit.headsOf(tokens)
        val m = HashMap<HeadId, FloatArray>()
        for (id in h.available) m[id] = h[id]!!.copyOf()
        change(m)
        return HeadValues(h.n40, m)
    }

    private fun observed(of: (String) -> HeadValues) = clips.associateWith { SelfTestSet.ClipObserved(4, of(it), List(4) { 10.0 }) }

    /** One golden stage over [accelerator] against the CPU's plain heads; the self-test event it raised, and its controller. */
    private fun golden(key: AcceleratorKey, signed: ModelManifest.RawTolerance? = null,
                       accelerator: (String) -> HeadValues): Pair<SelfTestResult, AcceleratorController> {
        val prep = object : AcceleratorPreparer {
            override fun unavailable(key: AcceleratorKey): String? = null
            override fun download(key: AcceleratorKey) {}
            override fun compile(key: AcceleratorKey): PreparedAccelerator = object : PreparedAccelerator {
                override val chunkMs = 320
                override val expected = this@GoldenMeasuresTest.expected
                override val planted = this@GoldenMeasuresTest.planted
                override val tolerance = signed
                override fun warmup() {}
                override fun golden(abort: () -> Boolean) = GoldenRun(observed(accelerator), observed { heads() })
                override fun stream(heads: Set<HeadId>, sampleRate: Int): TajweedEngineStream = throw IllegalStateException("no take runs here")
                override fun close() {}
            }
        }
        val store = dir()
        AcceleratorStore(store).sessionStarted = true
        selfTests.clear()
        val acc = AcceleratorController(env, AcceleratorStore(store), prep, key)
        acc.start()
        return selfTests.single { it.backend == Backend.NPU } to acc
    }

    /** The key of a build the SDK knows the version of (a verified manifest was noted). */
    private fun knownKey(): AcceleratorKey {
        val files = TestKit.v4Files()
        val manifest = TestKit.sign(TestKit.manifest(files))
        val d = dir()
        TestKit.writeDecoded(d, files, manifest)
        val folder = ModelFolder.open(d, TestKit.verifier)
        return AcceleratorKey(folder.manifest.build, env.osBuild, env.deviceModel, env.soc, "c16_l64")
    }

    @Suppress("UNCHECKED_CAST")
    private fun record(text: String): Map<String, Any?> {
        val json = GoldenMeasures.find(text) ?: throw AssertionError("the text carries no record: $text")
        // outside the strings (a build id is hex and may read like one)
        assertFalse("a number is written with an exponent: $json", Regex("[0-9][eE][-+0-9]").containsMatchIn(json.replace(Regex("\"[^\"]*\""), "\"\"")))
        return Json.parse(json) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aPassedRunCarriesExactlyTheFieldsOfTheRecord() {
        val key = knownKey()
        // the accelerator: on every clip one frame of `log_probs` is off by 0.5 nat on one token, and on the first clip
        // one probability of `gem` is off by 0.4; the tokens are the CPU's
        val (event, acc) = golden(key) { clip ->
            heads { m ->
                m.getValue(HeadId.LOG_PROBS)[1 * 38 + 5] += 0.5f
                if (clip == clips.first()) m.getValue(HeadId.GEM)[7] += 0.4f
            }
        }
        try {
            assertTrue(acc.state.value.toString(), acc.state.value is AcceleratorState.Ready)
            assertTrue(event.passed)
            assertTrue("the text still starts with what it always said: ${event.detail}", event.detail.startsWith("ok: 5 clips; log_probs 0 edits"))
            assertTrue("the record is the last part of the text", event.detail.contains(" | " + GoldenMeasures.MARK + "{") && event.detail.endsWith("}"))
            val r = record(event.detail)

            // exactly the fields of the record, by name, then the device fields of an incident, and nothing else
            assertEquals(listOf("build", "context", "model_version", "model_build", "clips", "clean_clips", "tokens_expected", "heads", "passed",
                "platform", "os_version", "os_build", "device_model", "chip", "delegate", "sdk_version"), r.keys.toList())
            assertEquals("the manifest's build key", "npu:tensor_g5:c16_l64", r["build"])
            assertEquals("c16_l64", r["context"])
            assertEquals("4.0.0", r["model_version"])
            assertEquals(key.revision, r["model_build"])
            assertTrue(Regex("^[0-9a-f]{40}$").matches(r["model_build"] as String))
            assertEquals(5.0, r["clips"])
            assertEquals(4.0, r["clean_clips"])
            assertEquals("the CPU's tokens over the clean clips: 4 clips of 3", 12.0, r["tokens_expected"])
            assertEquals(true, r["passed"])

            // the measures, worked out by hand: 5 clips of 16 frames (the timing head has 32 a clip)
            val heads = r["heads"] as Map<String, Map<String, Any?>>
            assertEquals(listOf("log_probs", "inter1_log_probs", "timing_log_probs", "attr"), heads.keys.toList())
            // one frame of 16 a clip is off by 0.5: the mean over 80 frames is 2.5 / 80, and the 99th percentile
            // (index (99 * 80 + 99) / 100 - 1 = 79 of the ascending list) is 0.5; in nats
            assertEquals(mapOf("token_edits" to 0.0, "lp_mean" to 0.03125, "lp_p99" to 0.5, "frames" to 80.0), heads["log_probs"])
            assertEquals(mapOf("token_edits" to 0.0, "lp_mean" to 0.0, "lp_p99" to 0.0, "frames" to 80.0), heads["inter1_log_probs"])
            assertEquals(mapOf("token_edits" to 0.0, "lp_mean" to 0.0, "lp_p99" to 0.0, "frames" to 160.0), heads["timing_log_probs"])
            // the probabilities of `gem` (2 a frame) and `attr_qalqalah` (3 a frame) over 32 frames of 5 clips: 800 values,
            // one of them off by 0.4
            assertEquals(mapOf("p_mean" to 0.0005, "values" to 800.0), heads["attr"])
            val json = GoldenMeasures.find(event.detail)!!
            assertTrue("the number form (a dot, no exponent, at most 6 decimals): $json",
                json.contains("\"lp_mean\":0.03125,\"lp_p99\":0.5,\"frames\":80") && json.contains("\"p_mean\":0.0005,\"values\":800"))

            // the device, as an accelerator incident says it
            assertEquals("android", r["platform"])
            assertEquals("16", r["os_version"])
            assertEquals("16/BD1A.250101.001/12345678", r["os_build"])
            assertEquals("Pixel_10", r["device_model"])
            assertEquals("tensor_g5", r["chip"])
            assertEquals("LiteRtDispatch_GoogleTensor litert 2.2.0", r["delegate"])
            assertEquals(SdkInfo.VERSION, r["sdk_version"])
            // nothing that names the app, the key, the install or a file
            assertNull(Regex("qlk_|install|bundle|/data/|\\\\").find(json))

            // `heads` is the `detail.divergence.heads` of an incident report for the same run, made by the same code
            val verdict = SelfTestSet.judgeGolden(expected, observed { heads() }, observed { clip ->
                heads { m ->
                    m.getValue(HeadId.LOG_PROBS)[1 * 38 + 5] += 0.5f
                    if (clip == clips.first()) m.getValue(HeadId.GEM)[7] += 0.4f
                }
            }, planted)
            assertTrue(json.contains("\"heads\":" + GoldenMeasures.json(IssueQueue.divergence(verdict.divergence!!)["heads"]) + ",\"passed\""))
        } finally {
            acc.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aFailedRunCarriesTheSameRecordWithPassedFalse() {
        // the accelerator reads another last token on the first clean clip: one token edit, and that frame is 10 nats off
        val (event, acc) = golden(AcceleratorKey("r1", env.osBuild, env.deviceModel, env.soc, "c16_l64")) { clip ->
            if (clip == clips.first()) heads(listOf(3, 32, 14)) else heads()
        }
        try {
            assertTrue(acc.state.value.toString(), acc.state.value is AcceleratorState.Disabled)
            assertFalse(event.passed)
            assertTrue(event.detail, event.detail.startsWith("golden_mismatch: "))
            val r = record(event.detail)
            assertEquals(false, r["passed"])
            assertEquals(12.0, r["tokens_expected"])
            val heads = r["heads"] as Map<String, Map<String, Any?>>
            // 10 nats on one of 80 frames
            assertEquals(mapOf("token_edits" to 1.0, "lp_mean" to 0.125, "lp_p99" to 10.0, "frames" to 80.0), heads["log_probs"])
            // a build the SDK does not know the version of says what it has, and no build id
            assertEquals("r1", r["model_version"])
            assertEquals("", r["model_build"])
        } finally {
            acc.close()
        }
    }

    /**
     * The stage judges by the tolerance the build signs (SPEC section 5): the same run that fails with no tolerance
     * (one token edit on a clean clip) is promoted when the build's tolerance allows that edit and those distances,
     * and fails again when the tolerance allows the edit but not the distance, or the distance but not the edit.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun aSignedToleranceThatAllowsATokenEditPromotes() {
        val key = AcceleratorKey("r1", env.osBuild, env.deviceModel, env.soc, "c16_l64")
        val run: (String) -> HeadValues = { clip -> if (clip == clips.first()) heads(listOf(3, 32, 14)) else heads() }
        fun entry(edits: Double, mean: Double, p99: Double) = ModelManifest.RawTolerance.parse(mapOf(
            "log_probs" to mapOf("token_edits" to edits, "lp_mean" to mean, "lp_p99" to p99)))!!

        // no tolerance: identical tokens are required
        golden(key, null, run).let { (event, acc) ->
            assertFalse(event.passed)
            assertTrue(acc.state.value is AcceleratorState.Disabled)
            acc.close()
        }
        // the measured run is 1 edit, lp_mean 0.125, lp_p99 10: a tolerance that covers it promotes
        golden(key, entry(1.0, 0.2, 10.0), run).let { (event, acc) ->
            assertTrue(event.detail, event.passed)
            assertTrue(acc.state.value.toString(), acc.state.value is AcceleratorState.Ready)
            val heads = record(event.detail)["heads"] as Map<String, Map<String, Any?>>
            assertEquals("the edit is still measured and reported", 1.0, heads.getValue("log_probs")["token_edits"])
            assertEquals(true, record(event.detail)["passed"])
            acc.close()
        }
        // each measure is held to its own number: the edit allowed, the mean distance not
        golden(key, entry(1.0, 0.1, 10.0), run).let { (event, acc) ->
            assertFalse(event.passed)
            assertTrue(event.detail, event.detail.startsWith("golden_mismatch: ") && event.detail.contains("log_probs.lp_mean"))
            acc.close()
        }
        // and no edit beyond what is allowed
        golden(key, entry(0.0, 0.2, 10.0), run).let { (event, acc) ->
            assertFalse(event.passed)
            assertTrue(event.detail, event.detail.contains("log_probs.token_edits"))
            acc.close()
        }
    }

    @Test
    fun theNumberFormOfTheRecord() {
        assertEquals("0", GoldenMeasures.number(0.0))
        assertEquals("0.5", GoldenMeasures.number(0.5))
        assertEquals("12", GoldenMeasures.number(12.0))
        assertEquals("0.03125", GoldenMeasures.number(0.03125))
        assertEquals("at most 6 decimals", "0.123457", GoldenMeasures.number(0.123456789))
        assertEquals("no exponent for a small value", "0.00001", GoldenMeasures.number(1e-5))
        assertEquals("below the sixth decimal", "0", GoldenMeasures.number(1e-9))
        assertEquals("no exponent for a large value", "123456789", GoldenMeasures.number(123456789.0))
        assertEquals("29.999999", GoldenMeasures.number(29.9999991))
        assertEquals("0", GoldenMeasures.number(Double.NaN))
        assertEquals("{\"a\":1,\"b\":0.25,\"c\":[true,null,\"x\"]}", GoldenMeasures.json(linkedMapOf("a" to 1, "b" to 0.25, "c" to listOf(true, null, "x"))))
        // a text without a record
        assertNull(GoldenMeasures.find("ok: 5 clips; log_probs 0 edits"))
        assertEquals("ok", GoldenMeasures.text("ok", null))
    }
}
