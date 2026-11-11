package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The signed config's `accel_deny` (platform client doc, section 3): every field matching and not, prefix patterns,
 * absent optional fields, `until` past and future, malformed rules skipped one by one, the accelerator kind (a rule for
 * `gpu` leaves the NPU alone). The controller's side (no promotion, the next session on the CPU, a running one not
 * touched, counted once, no incident) is in AcceleratorTest.
 */
class AccelDenyTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var now = 1_762_700_000_000L

    @Before fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-deny").toFile()
    }

    @After fun down() {
        stub.close()
        dir.deleteRecursively()
    }

    private val base = mapOf("platform" to "android", "os_version" to "16", "device_class" to "Pixel10", "accelerator" to "npu",
        "model_revision" to "v4.0-2026-11-09", "sdk_version" to SdkInfo.VERSION, "reason" to "Tensor G5 dispatch bug")

    /** The values of this device (the stub's device profile: Pixel10, Android 16). */
    private fun denied(vararg rules: Map<String, Any?>, accelerator: String = "npu:tensor_g5", osBuild: String = "16/BD1A.250101.001/12345678",
                       model: String = "Pixel 10", chip: String = "tensor_g5", delegate: String = "LiteRtDispatch_GoogleTensor/2.2.0"): String? {
        stub.extra = mapOf("accel_deny" to rules.toList())
        val c = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            stub.environment(File(dir, "c${System.nanoTime()}"), clock = { now }, device = DeviceProfile("Pixel10", "tensor_g5", 16, "16", "8_16")))
        c.refreshConfig()
        return c.accelDenied(accelerator, "v4.0-2026-11-09", osBuild, model, chip, delegate)
    }

    @Test fun everyRequiredFieldMatchesOrNot() {
        assertEquals("Tensor G5 dispatch bug", denied(base))
        for ((k, other) in listOf("platform" to "apple", "os_version" to "15", "device_class" to "Pixel9", "accelerator" to "ane",
            "model_revision" to "v4.0-2026-11-01", "sdk_version" to "2.0.0")) {
            assertNull("$k = $other", denied(base + (k to other)))
        }
    }

    @Test fun optionalFieldsMatchOrNotAndAbsentMatchesAnything() {
        for ((k, yes, no) in listOf(Triple("os_build", "16/BD1A*", "15/*"), Triple("device_model", "Pixel_10", "Pixel_9"),
            Triple("chip", "tensor_g5", "tensor_g4"), Triple("delegate", "LiteRtDispatch_GoogleTensor/2.2.0", "LiteRtDispatch_GoogleTensor/2.3*"))) {
            assertNotNull("$k = $yes", denied(base + (k to yes)))
            assertNull("$k = $no", denied(base + (k to no)))
        }
    }

    @Test fun prefixAndStarPatterns() {
        assertNotNull(denied(base + ("os_version" to "1*") + ("device_class" to "Pixel*") + ("model_revision" to "v4.0-*")))
        assertNotNull(denied(base + ("sdk_version" to "3.0.*") + ("accelerator" to "*")))
        assertNull(denied(base + ("device_class" to "SM-*")))
        assertNull(denied(base + ("os_version" to "1")))
    }

    @Test fun untilInThePastIsIgnoredInTheFutureApplies() {
        assertNull(denied(base + ("until" to now / 1000 - 1)))
        assertNotNull(denied(base + ("until" to now / 1000 + 3600)))
    }

    @Test fun malformedRulesAreSkippedOneByOne() {
        val good = base + ("reason" to "good rule")
        assertEquals("good rule", denied(base - "platform", base + ("chip" to 5), base + ("until" to "tomorrow"), base + ("os_version" to listOf("16")), good))
        assertNull(denied(base - "accelerator"))
    }

    @Test fun aRuleForAnotherAcceleratorKindLeavesTheNpuAlone() {
        assertNull(denied(base + ("accelerator" to "gpu")))
        assertNull(denied(base + ("accelerator" to "ane")))
        assertNotNull(denied(base + ("accelerator" to "npu")))
        assertNull("no config field: nothing denied", denied())
    }
}
