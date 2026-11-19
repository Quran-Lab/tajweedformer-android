package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.AcceleratorRecords
import ai.quranlab.tajweedformer.core.Golden
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The SDK's side of the accelerator (R2; the program itself runs in the Core): the golden test's verdict on the raw
 * outputs of an accelerator take against the CPU's with the build's signed tolerance, and the verdicts kept across
 * starts (a passed build is used at once, a failed one never again, a prepare the process died in twice is a failure).
 */
class AcceleratorTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    /** A take of [frames] frames whose greedy log_probs units are [ids] (one per frame, blank 0 between). */
    private fun take(ids: IntArray, shift: Float = 0f): List<HeadValues> {
        val n = ids.size
        val lp = FloatArray(n * 38) { -10f }
        for (f in 0 until n) lp[f * 38 + ids[f]] = -0.1f + shift
        return listOf(HeadValues(n, mapOf(HeadId.LOG_PROBS to lp)))
    }

    @Test
    fun theGoldenTestComparesWithTheTolerance() {
        val cpu = take(intArrayOf(5, 0, 7, 7, 0, 9))
        // the same outputs: passed
        assertTrue(Golden.judge(listOf(Triple("c1", cpu, cpu)), null).passed)
        // one token another: without a tolerance entry the tokens must match
        val other = take(intArrayOf(5, 0, 8, 8, 0, 9))
        val v = Golden.judge(listOf(Triple("c1", other, cpu)), null)
        assertFalse(v.passed)
        assertTrue(v.detail, v.detail.contains("log_probs.token_edits"))
        // a signed allowance of 1 edit and small distances: passed; the distances over it: not
        val ok = ModelManifest.RawTolerance(mapOf("log_probs" to ModelManifest.HeadTolerance(1, 3.0, 10.0)), null)
        assertTrue(Golden.judge(listOf(Triple("c1", other, cpu)), ok).passed)
        val tight = ModelManifest.RawTolerance(mapOf("log_probs" to ModelManifest.HeadTolerance(1, 0.001, 0.001)), null)
        assertFalse(Golden.judge(listOf(Triple("c1", other, cpu)), tight).passed)
        // not finite, or another frame count: never
        val nan = listOf(HeadValues(6, mapOf(HeadId.LOG_PROBS to FloatArray(6 * 38) { Float.NaN })))
        assertFalse(Golden.judge(listOf(Triple("c1", nan, cpu)), ok).passed)
        assertFalse(Golden.judge(listOf(Triple("c1", take(intArrayOf(5, 0, 7)), cpu)), ok).passed)
        assertFalse("no clip, no verdict", Golden.judge(emptyList(), ok).passed)
    }

    @Test
    fun greedyUnitsAndEdits() {
        assertArrayEquals(intArrayOf(5, 7, 9), Golden.greedy(take(intArrayOf(5, 5, 0, 7, 0, 0, 9))[0][HeadId.LOG_PROBS]!!, 38))
        assertArrayEquals(intArrayOf(7, 7), Golden.greedy(take(intArrayOf(7, 0, 7))[0][HeadId.LOG_PROBS]!!, 38))
        assertEquals(0, Golden.editDistance(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3)))
        assertEquals(1, Golden.editDistance(intArrayOf(1, 2, 3), intArrayOf(1, 3)))
        assertEquals(2, Golden.editDistance(intArrayOf(1, 2), intArrayOf(3, 4)))
        // the clips pushed into a model opened for 48 kHz: three times the samples, the same values at the knots
        val x = floatArrayOf(0f, 1f, 0f, -1f)
        val y = Golden.resample(x, 16_000, 48_000)
        assertEquals(12, y.size)
        assertEquals(1f, y[3], 0f)
        assertEquals(-1f, y[9], 0f)
    }

    @Test
    fun theVerdictsKeptAcrossStarts() {
        val d = Files.createTempDirectory("tj-accel-").toFile().also { dirs += it }
        val r = AcceleratorRecords(File(d, "accel.json"))
        val k = "b:npu:tensor_g4:c16_l64"
        assertTrue(r.begin(k))
        r.end(k, AcceleratorRecords.PASSED)
        assertTrue(AcceleratorRecords(File(d, "accel.json")).passed(k))
        // the process ends during a prepare, twice: the third start does not try again, and the records do not call it a
        // failure of the build (Stage B: the caller holds it as a transient verdict, AcceleratorDispatchTest)
        val k2 = "b:npu:exynos2600:c16_l64"
        assertTrue(r.begin(k2))
        assertTrue(AcceleratorRecords(File(d, "accel.json")).begin(k2))
        assertFalse(AcceleratorRecords(File(d, "accel.json")).begin(k2))
        assertFalse(r.blocked(k2))
        // a build that passed before and died once in its prepare keeps its verdict for the next try
        assertTrue(r.begin(k))
        assertTrue(r.passedBefore(k))
        r.end(k, AcceleratorRecords.FAILED, "golden")
        assertTrue(r.blocked(k))
    }
}
