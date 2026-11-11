package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The 2.x `golden_tolerance` in the signed manifest, which a 3.0 manifest may still carry and sign (3.0 never applies
 * it: its golden test is on raw outputs, GoldenRawTest): the signed text gains one line per well-formed build after
 * the header lines; a manifest signed with them has its tolerance signed; one signed without them (the field present)
 * still verifies, its tolerance ignored; a tampered tolerance refuses the manifest; a malformed entry has no line and
 * is exact; without the field the signed text is 2.0.0's byte for byte.
 */
class GoldenToleranceTest : OpenChecked() {
    private val files = TestKit.v4Files()
    private val base = TestKit.manifest(files)
    private val tol = linkedMapOf<String, Any?>(
        "npu:exynos2500:c16_l64" to mapOf("token_diffs" to 0, "site_flag_diffs" to 1, "word_flag_diffs" to 1),
        "coreml" to mapOf("token_diffs" to 0, "site_flag_diffs" to 2, "word_flag_diffs" to 1),
        "npu:tensor_g5:c16_l64" to mapOf("token_diffs" to -1, "site_flag_diffs" to 1, "word_flag_diffs" to 1),
    )

    private fun reparse(m: ModelManifest) = ModelManifest.parse(m.toJson())

    private fun signed(m: ModelManifest, withLines: Boolean, signer: (ByteArray) -> ByteArray = TestKit.key.second) =
        reparse(m.withSignature(Base64.getEncoder().encodeToString(signer(m.signedMessage(withLines)))))

    @Test fun theSignedTextGainsOneSortedLinePerBuild() {
        val m = reparse(base.withTolerance(tol))
        val text = String(m.signedMessage(true))
        val lines = text.lines().filter { it.startsWith("golden_tolerance ") }
        // one line per WELL-FORMED entry (SDK 3.0, the rule the four SDKs share): the entry with a negative value has none
        assertEquals(listOf("golden_tolerance coreml 0 2 1", "golden_tolerance npu:exynos2500:c16_l64 0 1 1"), lines)
        // after the header lines, before the first file line
        assertTrue(text.indexOf("contexts ") < text.indexOf("golden_tolerance ") && text.indexOf("golden_tolerance ") < text.indexOf("\nfile "))
        // without the field: the 2.0.0 text byte for byte
        assertTrue(base.signedMessage(true).contentEquals(base.signedMessage(false)))
        assertFalse(String(base.signedMessage(true)).contains("golden_tolerance"))
    }

    @Test fun signedWithTheLinesTheToleranceIsInForceAndMalformedIsExact() {
        val m = signed(reparse(base.withTolerance(tol)), withLines = true)
        TestKit.verifier.verify(m)
        assertEquals(ModelManifest.GoldenTolerance(0, 1, 1), m.toleranceFor("npu:exynos2500:c16_l64"))
        assertEquals(ModelManifest.GoldenTolerance(0, 2, 1), m.toleranceFor("coreml"))
        assertEquals("a negative value is exact", ModelManifest.GoldenTolerance.EXACT, m.toleranceFor("npu:tensor_g5:c16_l64"))
        assertEquals("an absent build is exact", ModelManifest.GoldenTolerance.EXACT, m.toleranceFor("npu:exynos2600:c16_l64"))
        for (bad in listOf(mapOf("token_diffs" to 0, "site_flag_diffs" to 1.5, "word_flag_diffs" to 1), mapOf("token_diffs" to 0, "site_flag_diffs" to 1),
            mapOf("token_diffs" to "0", "site_flag_diffs" to 1, "word_flag_diffs" to 1), "0 1 1")) {
            val b = signed(reparse(base.withTolerance(mapOf("npu:exynos2500:c16_l64" to bad))), withLines = true)
            TestKit.verifier.verify(b)
            assertEquals("$bad", ModelManifest.GoldenTolerance.EXACT, b.toleranceFor("npu:exynos2500:c16_l64"))
        }
    }

    @Test fun signedWithoutTheLinesTheToleranceIsIgnoredTamperedTheManifestIsRefused() {
        // an older publisher signed the 2.0.0 text although the field is there: it verifies, the tolerance is not in force
        val old = signed(reparse(base.withTolerance(tol)), withLines = false)
        TestKit.verifier.verify(old)
        assertEquals(ModelManifest.GoldenTolerance.EXACT, old.toleranceFor("npu:exynos2500:c16_l64"))
        // signed with 0 1 1, then the JSON changed to 0 9 9: it verifies neither with the lines nor without them (it was
        // signed with them): the manifest is refused as a whole
        val good = signed(reparse(base.withTolerance(tol)), withLines = true)
        @Suppress("UNCHECKED_CAST")
        val o = LinkedHashMap(Json.parse(good.toJson()) as Map<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        val t = LinkedHashMap(o["golden_tolerance"] as Map<String, Any?>)
        t["npu:exynos2500:c16_l64"] = mapOf("token_diffs" to 0, "site_flag_diffs" to 9, "word_flag_diffs" to 9)
        o["golden_tolerance"] = t
        val json = Json.write(o)
        assertTrue("the tampered JSON differs", json != good.toJson())
        val tampered = ModelManifest.parse(json)
        try {
            TestKit.verifier.verify(tampered)
            fail("a tampered tolerance verified")
        } catch (e: RecognizerException.SignatureInvalid) {
        }
        assertEquals(ModelManifest.GoldenTolerance.EXACT, tampered.toleranceFor("npu:exynos2500:c16_l64"))
        // a manifest whose files were changed does not verify either way
        val other = TestKit.keyPair()
        try {
            TestKit.verifier.verify(signed(reparse(base.withTolerance(tol)), withLines = true, signer = other.second))
            fail("a foreign key verified")
        } catch (e: RecognizerException.SignatureInvalid) {
        }
        // unsigned (a folder staged by hand): never in force
        val unsigned = reparse(base.withTolerance(tol))
        ManifestVerifier(listOf(TestKit.key.first), requireSignature = false).verify(unsigned)
        assertEquals(ModelManifest.GoldenTolerance.EXACT, unsigned.toleranceFor("npu:exynos2500:c16_l64"))
        assertNull(base.goldenTolerance)
    }
}
