package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.engine.TajweedJni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The self-test set of a model folder (`selftest/`: five clips and, per context, the exact CPU engine's greedy
 * tokens). [cpuEnginePassesItsOwnSelfTest] reads the set of a decoded model folder (`TJ_SELFTEST_MODEL`, with
 * `TJ_SELFTEST_DIR` for a set kept elsewhere) and runs the exact CPU engine through [SelfTestRunner]: on every clip
 * of every context its tokens must equal the expected file's, which is the CPU half of the promotion's golden test.
 * Only `tokens` of an expected file is read, so the 2.x files (with flagged sites and word scores) and the 3.0 files
 * (tokens only) both load.
 */
class SelfTestSetTest : OpenChecked() {
    private fun env(n: String): String? = TestKit.env(n)
    private val model = File(env("TJ_SELFTEST_MODEL") ?: "")

    companion object {
        const val PLANTED = "Husary_112001_vsub2"
    }

    @Test
    fun cpuEnginePassesItsOwnSelfTest() {
        val dir = File(env("TJ_SELFTEST_DIR") ?: File(model, SelfTestSet.DIR).path)
        Assume.assumeTrue("no self-test set at $dir", File(dir, "selftest.json").isFile)
        Assume.assumeTrue("the JNI library is not loadable", TajweedJni.available)
        val set = SelfTestSet.read(dir)
        assertEquals(5, set.clips.size)
        assertEquals(3, set.clips.count { it.mustDecode })
        assertEquals(listOf(PLANTED), set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id })
        val planted = setOf(PLANTED)
        val contexts = (dir.listFiles() ?: emptyArray()).mapNotNull { Regex("^expected_(c\\d+_l\\d+)\\.json$").matchEntire(it.name)?.groupValues?.get(1) }.sorted()
        assertTrue("no expected file in $dir", contexts.isNotEmpty())
        for (ctx in contexts) {
            if (!File(model, "$ctx/frames.zfw").isFile) continue
            val expected = SelfTestSet.readExpected(dir, ctx)
            assertEquals(SelfTestSet.REQUIRED.toSet(), expected.clips.keys)
            val engine = ExactTajweedEngine(model, ctx)
            try {
                val obs = SelfTestRunner.run(engine, set, dir)
                // the CPU against the shipped tokens, and against itself as the "accelerator": the golden rule passes
                val v = SelfTestSet.judgeGolden(expected, obs, obs, planted)
                val same = obs.count { (id, o) -> o.tokens == expected.clips.getValue(id).tokens }
                println("CPU self-test $ctx: $same/${set.clips.size} clips identical (${v.detail}); chunk ms median " +
                    String.format(java.util.Locale.ROOT, "%.1f", SelfTestSet.percentile(obs.values.flatMap { it.chunkMs }, 0.5)))
                assertTrue("$ctx: ${v.reason} ${v.detail}", v.passed)
                assertEquals(5, same)
                assertEquals(0, v.measures!!.heads.getValue("log_probs").tokenEdits)
            } finally {
                engine.close()
            }
        }
    }
}
