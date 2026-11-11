package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.TestKit.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Manifest format 3: the signed text (LAYOUT.md), the staged sdk_v4.0_l3 manifest (the test
 * key) and tampered copies, structure checks, compatibility, and Ed25519.
 */
class ManifestTest : OpenChecked() {
    companion object {
        /** the TEST key for the staged folder (sdk_v4.0_l3.READY); never a release key. */
        const val STAGED_TEST_KEY = "ebea3bcf5c3e3a62e7d72cd837ca77bc0825ab484007978658914c4963a7aa6b"

        fun stagedDir(): File? {
            val d = File(TestKit.env("TJ_V4_TRANSPORT_DIR") ?: "")
            return d.takeIf { File(it, ModelManifest.FILE_NAME).isFile }
        }
    }

    /** c8_l64 has no NPU build by design: a manifest cannot make it pick the NPU, and no file of it is downloaded. */
    @Test
    fun c8IsNeverAnNpuContext() {
        val soc = "tensor_g5"
        val d = "npu/$soc/c8_l64/tajweedformer_v4_${soc}_c8_l64"
        val files = TestKit.v4Files(npuSocs = listOf(soc), contexts = listOf("c16_l64", "c8_l64")) + listOf(
            TestKit.F("$d.tflite", ByteArray(5000) { 3 }, "npu-model", "c8_l64", "optional", listOf("android"), soc),
            TestKit.F("$d.io.json", "{}".toByteArray(), "npu-io", "c8_l64", "optional", listOf("android"), soc),
            TestKit.F("$d.masks.json", "{}".toByteArray(), "npu-masks", "c8_l64", "optional", listOf("android"), soc),
        )
        val m = TestKit.manifest(files, listOf("c16_l64", "c8_l64"))
        assertTrue(ModelProfile.npu(m, soc, "c16_l64").isNotEmpty())
        assertTrue(ModelProfile.npu(m, soc, "c8_l64").isEmpty())
    }

    @Test
    fun signedTextFollowsTheLayout() {
        val files = listOf(
            TestKit.F("tokens.txt", "a 0\n".toByteArray(), "tokens", gz = false),
            TestKit.F("layers.zfw", ByteArray(10), "weights"),
            TestKit.F("c16_l64/model_engine2.onnx", ByteArray(3), "ort-graph", "c16_l64", "optional", listOf("native", "android")),
        )
        val m = TestKit.manifest(files, listOf("c16_l64"))
        val t = m.signedMessage.toString(Charsets.UTF_8)
        val lines = t.trimEnd('\n').split("\n")
        assertEquals(listOf(
            "quranlab-model-manifest 3", "model_id v4.0", "family tajweedformer", "revision v4.0-test.1", "version 4.0.0", "min_sdk_version 3.0.0",
            "engine_format 3", "coreml_format 9", "tokens tokens.txt", "tokens_sha256 ${TestKit.sha(files[0].bytes)}",
            "default_context c16_l64", "contexts c16_l64",
        ), lines.take(12))
        // without the version (a 2.x build's manifest) the text is 2.0.0's: no such line
        assertEquals(lines.filter { !it.startsWith("version ") }, m.withVersion(null).signedMessage.toString(Charsets.UTF_8).trimEnd('\n').split("\n"))
        // files sorted by path bytes; platforms in the fixed order; identity files end in "- - -"
        assertEquals("file ${files[2].entry().sha256} ${files[2].stored.size} c16_l64/model_engine2.onnx.gz android,native * c16_l64 " +
            "ort-graph optional gzip ${TestKit.sha(files[2].bytes)} 3 c16_l64/model_engine2.onnx", lines[12])
        assertTrue(lines[13], lines[13].startsWith("file ") && lines[13].contains(" layers.zfw.gz * * * weights required gzip "))
        assertEquals("file ${TestKit.sha(files[0].bytes)} ${files[0].bytes.size} tokens.txt * * * tokens required identity - - -", lines[14])
        // JSON round trip keeps the text; a signature by a trusted key verifies
        val again = ModelManifest.parse(TestKit.sign(m).toJson())
        assertEquals(t, again.signedMessage.toString(Charsets.UTF_8))
        TestKit.verifier.verify(again)
        assertEquals(listOf("*"), again.files.first { it.path == "tokens.txt" }.platforms)
    }

    @Test
    fun theStagedManifestVerifiesAndTamperedCopiesDoNot() {
        val d = stagedDir()
        Assume.assumeTrue("the staged sdk_v4.0_l3 folder is not on this machine", d != null)
        val text = File(d, ModelManifest.FILE_NAME).readText()
        val m = ModelManifest.parse(text)
        // the staged folder is a 2.x build (no version): its signature and structure are checked as before, and SDK 3.0
        // itself refuses it as a build for another SDK generation
        val v = ManifestVerifier(listOf(hex(STAGED_TEST_KEY)), requireVersion = false)
        v.verify(m)
        try {
            ManifestVerifier(listOf(hex(STAGED_TEST_KEY))).verify(m)
            fail("a 2.x build verified for SDK 3.0")
        } catch (e: RecognizerException.ModelIncompatible) {
            assertEquals("model_incompatible", e.code)
        }
        assertEquals("v4.0", m.modelId)
        assertEquals(listOf("c16_l64", "c24_l72", "c24_l256"), m.contexts)
        assertEquals("c16_l64", m.defaultContext)
        val cpu = ModelProfile.cpu(m, "c16_l64")
        assertEquals(7, cpu.size)   // tokens, calibration, layers, frontend + the context's graph, data, frames (never the 2.x expected table)
        assertTrue(cpu.none { it.role == "expected" })
        println("staged android CPU profile c16_l64: ${cpu.size} files, ${ModelProfile.bytes(cpu)} bytes stored, " +
            "${cpu.sumOf { it.localSize }} decoded; NPU builds at c16_l64: ${ModelProfile.npuSocs(m, "c16_l64").sorted()}")
        // 56.5 MB with the 2.x expected table (1.0 MB), which SDK 3.0 never downloads
        assertTrue(ModelProfile.bytes(cpu) in 55_000_000L..56_000_000L)
        try {
            ManifestVerifier().verify(m)
            fail("the staged manifest verified against the release key")
        } catch (e: RecognizerException.SignatureInvalid) {
        }
        // one changed hex digit of a hash, a changed revision, a changed tag: refused
        val sha = m.files.first { it.path == "layers.zfw.gz" }.sha256
        val flipped = sha.substring(0, 63) + (if (sha.last() == '0') '1' else '0')
        for (tampered in listOf(
            text.replace(sha, flipped),
            text.replace("\"${m.revision}\"", "\"${m.revision}x\""),
            text.replaceFirst("\"optional\"", "\"required\""),
        )) {
            assertTrue(tampered != text)
            try {
                v.verify(ModelManifest.parse(tampered))
                fail("a tampered manifest verified")
            } catch (e: RecognizerException.SignatureInvalid) {
            }
        }
        val unsigned = m.withSignature(null)
        try {
            v.verify(unsigned)
            fail("an unsigned manifest verified")
        } catch (e: RecognizerException.SignatureInvalid) {
        }
        ManifestVerifier(listOf(hex(STAGED_TEST_KEY)), requireSignature = false, requireVersion = false).verify(unsigned)
    }

    @Test
    fun oldFormatsAndIncompatibleModelsAreRefused() {
        for (f in listOf(1, 2)) {
            try {
                ModelManifest.parse("{\"format\":$f,\"files\":[]}")
                fail()
            } catch (e: RecognizerException.ManifestInvalid) {
                assertTrue(e.message!!.contains("format $f"))
            }
        }
        val files = TestKit.v4Files()
        for ((m, why) in listOf(
            TestKit.manifest(files, minSdk = "3.1.0") to "needs SDK 3.1.0",
            TestKit.manifest(files, version = null) to "has no version",
            TestKit.manifest(files, engineFormat = 2) to "engine format 2",
        )) {
            try {
                TestKit.verifier.verify(TestKit.sign(m))
                fail(why)
            } catch (e: RecognizerException.ModelIncompatible) {
                assertEquals("model_incompatible", e.code)
                assertTrue(e.reason, e.reason.contains(why))
                assertTrue(e.message!!, e.message!!.contains(why))
            }
        }
        fun refused(m: ModelManifest, contains: String) {
            try {
                TestKit.verifier.verify(TestKit.sign(m))
                fail(contains)
            } catch (e: RecognizerException.ManifestInvalid) {
                assertTrue(e.message!!, e.message!!.contains(contains))
            }
        }
        refused(TestKit.manifest(files + TestKit.F("../x", ByteArray(1), "weights")), "bad path")
        refused(TestKit.manifest(files + files[3]), "listed twice")
        refused(TestKit.manifest(files + TestKit.F("c9_l9/frames.zfw", ByteArray(1), "frames", "c9_l9")), "context c9_l9")
        val base = TestKit.manifest(files)
        refused(ModelManifest(3, "v4.0", "tajweedformer", "r", "2.0.0", 3, 9, "tokens.txt", "00".repeat(32), "c16_l64",
            listOf("c16_l64", "c24_l72"), base.files).withVersion("4.0.0"), "tokens_sha256")
    }

    @Test
    fun ed25519Rfc8032TestVectors() {
        // RFC 8032 7.1, tests 1 and 2
        assertTrue(Ed25519.verify(
            hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), ByteArray(0),
            hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        ))
        assertTrue(Ed25519.verify(
            hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), hex("72"),
            hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        ))
        assertFalse(Ed25519.verify(
            hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), hex("73"),
            hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        ))
    }

    @Test
    fun ed25519AgreesWithTheJdk() {
        val rnd = java.util.Random(7)
        repeat(20) { i ->
            val (pub, signer) = TestKit.keyPair()
            val msg = ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }
            val sig = signer(msg)
            assertTrue("pair $i", Ed25519.verify(pub, msg, sig))
            val bad = sig.copyOf().also { it[rnd.nextInt(64)] = (it[rnd.nextInt(64)].toInt() xor 1).toByte() }
            if (!bad.contentEquals(sig)) assertFalse("pair $i flipped", Ed25519.verify(pub, msg, bad))
            if (msg.isNotEmpty()) {
                val m2 = msg.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
                assertFalse("pair $i message", Ed25519.verify(pub, m2, sig))
            }
        }
        assertFalse(Ed25519.verify(ByteArray(31), ByteArray(0), ByteArray(64)))
    }

    @Test
    fun versionComparison() {
        assertTrue(ModelManifest.versionAtLeast("1.0.0", "1.0.0"))
        assertTrue(ModelManifest.versionAtLeast("1.10.0", "1.9.3"))
        assertTrue(ModelManifest.versionAtLeast("2.0", "1.99.99"))
        assertFalse(ModelManifest.versionAtLeast("1.0.0", "1.0.1"))
        assertFalse(ModelManifest.versionAtLeast("0.9.9", "1.0.0"))
        assertTrue(ModelManifest.versionAtLeast("1.2.0-beta", "1.2.0"))
        assertFalse(ModelManifest.versionAtLeast("x", "1.0.0"))
        assertTrue(ModelManifest.versionAtLeast(SdkInfo.VERSION, "3.0.0"))
        assertFalse(ModelManifest.versionAtLeast(SdkInfo.VERSION, "3.0.1"))
    }

    @Test
    fun sdkVersionIsTheBuildsVersion() {
        assertEquals(System.getProperty("tajweedformer.version"), SdkInfo.VERSION)
    }

    @Test
    fun productionKeyIsTheSwiftSdks() {
        assertArrayEquals(hex("fc02fbd906608ebc206a371d8e61c1062ea555778b44b1ac834061043aee4a33"), ManifestVerifier.QURAN_LAB_PUBLIC_KEY)
    }
}
