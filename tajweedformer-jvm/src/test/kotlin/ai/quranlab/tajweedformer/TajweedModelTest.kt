package ai.quranlab.tajweedformer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Layout v2 folders as an SDK keeps them: a good folder, one context only, a partial context, a
 *  tampered file, a calibration that disagrees with the manifest, profiles and SoC ids. */
class TajweedModelTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir() = Files.createTempDirectory("tj-v4-").toFile().also { dirs.add(it) }

    private inline fun <reified E : Throwable> refused(dir: File, contains: String = ""): E {
        try {
            ModelFolder.open(dir, TestKit.verifier)
        } catch (e: Throwable) {
            if (e !is E) throw AssertionError("expected ${E::class.simpleName}, got $e", e)
            assertTrue("$e should mention '$contains'", e.message!!.contains(contains))
            return e
        }
        throw AssertionError("the folder opened")
    }

    @Test
    fun aVerifiedFolderOpensWithItsContexts() {
        val files = TestKit.v4Files(listOf("tensor_g5"))
        val m = TestKit.sign(TestKit.manifest(files))
        val d = dir()
        TestKit.writeDecoded(d, files, m)
        val model = ModelFolder.open(d, TestKit.verifier)
        assertEquals("v4.0", model.modelId)
        assertEquals(listOf("c16_l64", "c24_l72"), model.contexts)
        assertEquals(listOf("c16_l64", "c24_l72"), model.availableContexts)
        assertEquals(320, model.chunkMs("c16_l64"))
        assertEquals(480, model.chunkMs("c24_l72"))
        assertEquals(38, model.vocabulary.size)
        assertEquals("|", model.vocabulary.symbol(ModelFolder.WORD_BOUNDARY))
        assertTrue(File(d, VerifiedStamps.FILE_NAME).isFile)
        // a second open uses the stamps (same result); a file changed in place is caught by its size or time
        assertEquals(listOf("c16_l64", "c24_l72"), ModelFolder.open(d, TestKit.verifier).availableContexts)
        val layers = File(d, "layers.zfw")
        val b = layers.readBytes()
        b[10] = (b[10] + 1).toByte()
        layers.writeBytes(b)
        layers.setLastModified(layers.lastModified() + 5_000)
        refused<RecognizerException.ChecksumMismatch>(d, "layers.zfw")
    }

    @Test
    fun oneContextIsEnoughAndAPartialOneIsAnError() {
        val files = TestKit.v4Files()
        val m = TestKit.sign(TestKit.manifest(files))
        val d = dir()
        TestKit.writeDecoded(d, files, m) { it.need == "required" && (it.context == "*" || it.context == "c16_l64") }
        val model = ModelFolder.open(d, TestKit.verifier)
        assertEquals(listOf("c16_l64"), model.availableContexts)
        assertEquals(listOf("c16_l64", "c24_l72"), model.contexts)
        try {
            model.contextFiles("c24_l72")
            fail()
        } catch (e: RecognizerException.ModelMissing) {
        }
        try {
            ModelFolder.open(d, TestKit.verifier, require = listOf("c24_l72"))
            fail()
        } catch (e: RecognizerException.ModelMissing) {
        }
        File(d, "c24_l72").mkdirs()
        File(d, "c24_l72/frames.zfw").writeBytes("frames c24_l72".toByteArray())
        refused<RecognizerException.ModelMissing>(d, "partly downloaded")
    }

    @Test
    fun badFoldersAreRefused() {
        val files = TestKit.v4Files()
        // unsigned
        run {
            val d = dir()
            TestKit.writeDecoded(d, files, TestKit.manifest(files))
            refused<RecognizerException.SignatureInvalid>(d)
        }
        // a shared file missing
        run {
            val d = dir()
            TestKit.writeDecoded(d, files, TestKit.sign(TestKit.manifest(files)))
            File(d, "frontend.zfw").delete()
            refused<RecognizerException.ModelMissing>(d, "frontend.zfw")
        }
        // the calibration is data: one whose contexts are not the manifest's does not refuse the folder (SDK 3.0)
        run {
            val f3 = TestKit.v4Files(contexts = listOf("c16_l64", "c24_l72", "c24_l256"))
            val d = dir()
            TestKit.writeDecoded(d, f3, TestKit.sign(TestKit.manifest(f3, listOf("c16_l64", "c24_l72", "c24_l256"))))
            File(d, "c24_l256").deleteRecursively()
            val m = ModelFolder.open(d, TestKit.verifier)
            assertEquals(listOf("c16_l64", "c24_l72", "c24_l256"), m.contexts)
            assertEquals(listOf("c16_l64", "c24_l72"), m.calibration!!.contexts)
        }
        // a manifest without a required shared role
        run {
            val f = files.filter { it.path != "frontend.zfw" }
            val d = dir()
            TestKit.writeDecoded(d, f, TestKit.sign(TestKit.manifest(f)))
            refused<RecognizerException.ManifestInvalid>(d, "frontend.zfw")
        }
    }

    @Test
    fun profilesSelectByPlatformSocAndContext() {
        val files = TestKit.v4Files(listOf("tensor_g5", "exynos2600"))
        val m = TestKit.manifest(files)
        val cpu = ModelProfile.cpu(m, "c16_l64").map { it.localPath }.toSet()
        assertEquals(setOf("tokens.txt", "calibration.json", "layers.zfw", "frontend.zfw",
            "c16_l64/model_engine2.onnx.zfg", "c16_l64/model_engine2.onnx.zfg.bin", "c16_l64/frames.zfw"), cpu)
        // another context: its own three files; native adds the ORT graph (optional, so not in the CPU profile)
        assertEquals(setOf("c24_l72/model_engine2.onnx.zfg", "c24_l72/model_engine2.onnx.zfg.bin", "c24_l72/frames.zfw"),
            ModelProfile.contextOnly(m, "c24_l72").map { it.localPath }.toSet())
        assertTrue(ModelProfile.cpu(m, "c16_l64", "native").none { it.role == "ort-graph" })
        // NPU: the SoC's three files at the context, nothing for another SoC, context or platform
        assertEquals(setOf("npu-model", "npu-io", "npu-masks"), ModelProfile.npu(m, "tensor_g5", "c16_l64").map { it.role }.toSet())
        assertTrue(ModelProfile.npu(m, "tensor_g5", "c16_l64").all { it.soc == "tensor_g5" })
        assertTrue(ModelProfile.npu(m, "tensor_g3", "c16_l64").isEmpty())
        assertTrue(ModelProfile.npu(m, null, "c16_l64").isEmpty())
        assertTrue(ModelProfile.npu(m, "tensor_g5", "c24_l72").isEmpty())
        assertTrue(ModelProfile.npu(m, "tensor_g5", "c16_l64", "apple").isEmpty())
        assertEquals(setOf("tensor_g5", "exynos2600"), ModelProfile.npuSocs(m, "c16_l64"))
        // an incomplete NPU build (no masks) is no build
        val partial = TestKit.manifest(files.filter { !(it.role == "npu-masks" && it.soc == "exynos2600") })
        assertTrue(ModelProfile.npu(partial, "exynos2600", "c16_l64").isEmpty())
        try {
            ModelProfile.cpu(m, "c9_l9")
            fail()
        } catch (e: RecognizerException.InvalidAppOption) {
        }
    }

    @Test
    fun socIdsFromWhatDevicesReport() {
        // seen on Firebase devices 11-07 (Build.SOC_MANUFACTURER, Build.SOC_MODEL, HARDWARE)
        assertEquals("tensor_g3", SocIds.from("Google", "Tensor G3", hardware = "shiba"))
        assertEquals("tensor_g4", SocIds.from("Google", "Tensor G4", hardware = "tokay"))
        assertEquals("tensor_g5", SocIds.from("Google", "Tensor G5", hardware = "frankel"))
        // Exynos 2600 (E9965): ro.soc.model s5e9965 on the farm board; retail strings guessed
        assertEquals("exynos2600", SocIds.from(null, null, roSocModel = "s5e9965"))
        assertEquals("exynos2600", SocIds.from("Samsung", "s5e9965"))
        assertEquals("exynos2600", SocIds.from("Samsung", "Exynos 2600"))
        // fallbacks: codename or board platform when the SoC fields are empty
        assertEquals("tensor_g4", SocIds.from("", "", hardware = "komodo"))
        assertEquals("tensor_g3", SocIds.from(null, null, boardPlatform = "zuma"))
        assertEquals("tensor_g6", SocIds.from("Google", "Tensor G6"))
        // no NPU build: Snapdragon, MediaTek, the Alcatel 1 (MediaTek MT6739, Android 8.1: no SOC_* fields)
        assertEquals(null, SocIds.from("QTI", "SM8650"))
        assertEquals(null, SocIds.from("Mediatek", "MT6985"))
        assertEquals(null, SocIds.from(null, null, hardware = "mt6739", boardPlatform = "mt6739"))
        assertEquals(null, SocIds.from(null, null))
    }
}
