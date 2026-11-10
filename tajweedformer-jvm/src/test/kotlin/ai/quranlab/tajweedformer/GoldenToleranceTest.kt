package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * `golden_tolerance` in the signed manifest (SPEC section 11): the signed text gains one line per build after the header
 * lines; a manifest signed with them has its tolerance in force; one signed without them (the field present) still
 * verifies, its tolerance ignored; a tampered tolerance is ignored the same way; a malformed entry is exact; without the
 * field the signed text is 2.0.0's byte for byte. And the golden rule with a tolerance: totals over the set against the
 * CPU run, both directions, and the planted site always.
 */
class GoldenToleranceTest {
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
        assertEquals(listOf("golden_tolerance coreml 0 2 1", "golden_tolerance npu:exynos2500:c16_l64 0 1 1",
            "golden_tolerance npu:tensor_g5:c16_l64 -1 1 1"), lines)
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

    // ----------------------------------------------------------------------------------------- the golden rule

    private val ids = SelfTestSet.REQUIRED
    private val planted = setOf(SelfTestSet.PLANTED_CLIP)
    private val plantedSite = 5 to "vowel_sub"
    private val expected = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", ids.associateWith {
        if (it == SelfTestSet.PLANTED_CLIP) SelfTestSet.ClipExpected(4, listOf(3, 32, 13), setOf(plantedSite, 6 to "cons_sub"), setOf(1), listOf(1.0))
        else SelfTestSet.ClipExpected(4, listOf(3, 32, 13), emptySet(), emptySet(), listOf(1.0))
    })

    private fun obs(change: Map<String, (SelfTestSet.ClipObserved) -> SelfTestSet.ClipObserved> = emptyMap()) = ids.associateWith { id ->
        val e = expected.clips.getValue(id)
        val o = SelfTestSet.ClipObserved(4, e.tokens, e.flaggedSites, e.flaggedWords, true, listOf(10.0))
        change[id]?.invoke(o) ?: o
    }

    private val sites = mapOf(SelfTestSet.PLANTED_CLIP to setOf(plantedSite))

    @Test fun aBuildWithinItsToleranceIsPromotedBeyondItIsNot() {
        val one = ModelManifest.GoldenTolerance(0, 1, 1)
        // one extra site flag on a clean clip and one extra word flag: within 0 / 1 / 1
        val acc = obs(mapOf(ids[0] to { o -> o.copy(flaggedSites = setOf(2 to "madd_short")) }, ids[1] to { o -> o.copy(flaggedWords = setOf(0)) }))
        val ok = SelfTestSet.judgeGolden(expected, obs(), acc, planted, one, sites)
        assertTrue(ok.detail, ok.passed)
        assertEquals(1, ok.divergence!!.sitesDiffer)
        assertEquals(1, ok.divergence!!.wordsDiffer)
        // the same is a mismatch for an exact build
        val exact = SelfTestSet.judgeGolden(expected, obs(), acc, planted, ModelManifest.GoldenTolerance.EXACT, sites)
        assertEquals(Reasons.GOLDEN_MISMATCH, exact.reason)
        assertEquals(ids[0], exact.clip)
        // a missed flag counts as much as an extra one (both directions), and totals add over the clips
        val two = obs(mapOf(ids[0] to { o -> o.copy(flaggedSites = setOf(2 to "madd_short")) },
            SelfTestSet.PLANTED_CLIP to { o -> o.copy(flaggedSites = setOf(plantedSite)) }))
        assertEquals(Reasons.GOLDEN_MISMATCH, SelfTestSet.judgeGolden(expected, obs(), two, planted, one, sites).reason)
        // a token difference on a clean clip is not allowed at 0
        val tok = obs(mapOf(ids[2] to { o -> o.copy(tokens = listOf(3, 32)) }))
        assertEquals(Reasons.GOLDEN_MISMATCH, SelfTestSet.judgeGolden(expected, obs(), tok, planted, one, sites).reason)
        // the planted clip's tokens are never counted
        val ptok = obs(mapOf(SelfTestSet.PLANTED_CLIP to { o -> o.copy(tokens = listOf(3, 32, 13, 7)) }))
        assertTrue(SelfTestSet.judgeGolden(expected, obs(), ptok, planted, ModelManifest.GoldenTolerance.EXACT, sites).passed)
    }

    @Test fun aMissedPlantedSiteAlwaysFails() {
        val huge = ModelManifest.GoldenTolerance(100, 100, 100)
        val missed = obs(mapOf(SelfTestSet.PLANTED_CLIP to { o -> o.copy(flaggedSites = setOf(6 to "cons_sub")) }))
        val v = SelfTestSet.judgeGolden(expected, obs(), missed, planted, huge, sites)
        assertFalse(v.passed)
        assertEquals(Reasons.GOLDEN_MISMATCH, v.reason)
        assertEquals(SelfTestSet.PLANTED_CLIP, v.clip)
        assertTrue(v.detail, v.detail.contains("planted"))
        // the CPU itself must equal the shipped expected file (cpu_mismatch), whatever the tolerance
        val cpuOff = obs(mapOf(ids[0] to { o -> o.copy(flaggedWords = setOf(3)) }))
        assertEquals(Reasons.CPU_MISMATCH, SelfTestSet.judgeGolden(expected, cpuOff, obs(), planted, huge, sites).reason)
    }

    @Test fun thePlantedSiteComesFromSelftestJsonPos() {
        val set = SelfTestSet(ids.map { SelfTestSet.Clip(it, 1, 1, "$it.s16", 1, 16000, false,
            if (it == SelfTestSet.PLANTED_CLIP) SelfTestSet.PLANTED else SelfTestSet.CLEAN, if (it == SelfTestSet.PLANTED_CLIP) 5 else null) }, emptyMap())
        assertEquals(mapOf(SelfTestSet.PLANTED_CLIP to setOf(plantedSite)), SelfTestSet.plantedSites(set, expected))
        val noPos = SelfTestSet(set.clips.map { it.copy(plantedPos = null) }, emptyMap())
        assertEquals(mapOf(SelfTestSet.PLANTED_CLIP to setOf(plantedSite, 6 to "cons_sub")), SelfTestSet.plantedSites(noPos, expected))
    }
}
