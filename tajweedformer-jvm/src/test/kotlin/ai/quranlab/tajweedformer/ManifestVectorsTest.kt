package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The cross-SDK manifest vectors (`manifest_vectors/`): the two real signed manifests of model V4, verbatim. Both
 * verify with the SDK's pinned key (key id 660a07197c4808da) through the SDK's own verifier; one changed byte in a
 * header value, in a `golden_tolerance` value and in a file line is refused; and the signed text of the build with a
 * `golden_tolerance` field carries its lines in the reference order (after the header lines, before the first `file`
 * line, sorted by the build key's UTF-8 bytes). No engine needed: the public CI runs it, so an SDK whose signed text
 * drifts from the publisher's never ships.
 */
class ManifestVectorsTest : OpenChecked() {
    private fun text(name: String): String =
        javaClass.getResourceAsStream("/manifest_vectors/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private val withTolerance = "manifest_v4.0_build519235d0_signed.json"
    private val without = "manifest_v4.0_build_d4644956_signed.json"

    /** The pinned key only (as a shipped app verifies), signatures required. */
    private val verifier = ManifestVerifier()

    private fun refused(tag: String, json: String) {
        try {
            verifier.verifySignature(ModelManifest.parse(json))
            fail("$tag: verified with one byte changed")
        } catch (_: RecognizerException.SignatureInvalid) {
        }
    }

    /** [json] with the first [old] replaced by [new] (one byte apart). */
    private fun change(json: String, old: String, new: String): String {
        assertEquals("one byte", old.length, new.length)
        assertEquals(1, old.indices.count { old[it] != new[it] })
        val at = json.indexOf(old)
        assertTrue("$old is in the vector", at >= 0)
        return json.substring(0, at) + new + json.substring(at + old.length)
    }

    @Test
    fun bothRealManifestsVerifyWithThePinnedKey() {
        // the two production builds are 2.x builds: their signatures verify with the pinned key, and SDK 3.0 refuses
        // both as builds for another SDK generation (no `version`)
        val a = ModelManifest.parse(text(withTolerance))
        verifier.verifySignature(a)
        for (name in listOf(withTolerance, without)) {
            try {
                verifier.verify(ModelManifest.parse(text(name)))
                fail("$name: a 2.x build verified for SDK 3.0")
            } catch (e: RecognizerException.ModelIncompatible) {
                assertEquals("model_incompatible", e.code)
            }
        }
        assertEquals("v4.0-2026-11-10", a.revision)
        // signed with its golden_tolerance lines: the tolerance is in force
        assertTrue(a.toleranceSigned)
        assertEquals(ModelManifest.GoldenTolerance(0, 1, 1), a.toleranceFor("npu:tensor_g5:c16_l64"))
        val b = ModelManifest.parse(text(without))
        verifier.verifySignature(b)
        assertEquals("v4.0-2026-11-09", b.revision)
        assertEquals(ModelManifest.GoldenTolerance.EXACT, b.toleranceFor("npu:tensor_g5:c16_l64"))
    }

    @Test
    fun oneChangedByteIsRefused() {
        for ((name, rev) in listOf(withTolerance to "v4.0-2026-11-10", without to "v4.0-2026-11-09")) {
            val json = text(name)
            // a header value
            refused("$name revision", change(json, "\"revision\": \"$rev\"", "\"revision\": \"${rev.dropLast(1)}${if (rev.last() == '0') '1' else '0'}\""))
            // a file line: the first file's SHA-256, one hex digit
            val sha = Regex("\"sha256\": \"([0-9a-f]{64})\"").findAll(json).map { it.groupValues[1] }.first()
            val flipped = sha.dropLast(1) + (if (sha.last() == '0') '1' else '0')
            refused("$name file line", change(json, "\"sha256\": \"$sha\"", "\"sha256\": \"$flipped\""))
        }
        // a tolerance value: neither with the lines nor without them (the tolerance cannot be loosened unsigned)
        refused("$withTolerance golden_tolerance", change(text(withTolerance), "\"token_diffs\": 0", "\"token_diffs\": 1"))
    }

    @Test
    fun theSignedTextCarriesTheToleranceLinesInTheReferenceOrder() {
        val lines = ModelManifest.parse(text(withTolerance)).signedMessage.toString(Charsets.UTF_8).split('\n')
        val tol = lines.filter { it.startsWith("golden_tolerance ") }
        assertEquals(listOf(
            "golden_tolerance coreml 0 1 1",
            "golden_tolerance coreml-compact 0 1 1",
            "golden_tolerance npu:exynos2500:c16_l64 0 1 1",
            "golden_tolerance npu:exynos2600:c16_l64 0 1 1",
            "golden_tolerance npu:tensor_g3:c16_l64 0 1 1",
            "golden_tolerance npu:tensor_g4:c16_l64 0 1 1",
            "golden_tolerance npu:tensor_g5:c16_l64 0 1 1",
        ), tol)
        // right after the header lines (the last is `contexts`), right before the first `file` line
        val first = lines.indexOf(tol.first())
        assertTrue(lines[first - 1].startsWith("contexts "))
        assertTrue(lines[first + tol.size].startsWith("file "))
        assertTrue(lines.drop(first + tol.size).none { it.startsWith("golden_tolerance ") })
        // the build without the field: the 2.0.0 text, no such line
        val plain = ModelManifest.parse(text(without)).signedMessage.toString(Charsets.UTF_8)
        assertFalse(plain.contains("golden_tolerance"))
    }
}
