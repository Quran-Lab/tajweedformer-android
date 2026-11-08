package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.EngineFrontEnd
import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.npu.NpuChainRunner
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Host side of the on-device NPU check (bench-app NpuCheckActivity over native/tools/fake_litert.c in replay
 * mode), env-gated; skipped without its data.
 *
 * - [exportFeatures] (TJ_NPU_REPLAY_EXPORT=DIR, TJ_SELFTEST_DIR=the decoded selftest/ folder): the self-test
 *   clips' fbank frames as the SDK's NPU stream makes them (the engine's front end, 5120-sample pushes, the
 *   flush), DIR/<clip>.f32, for the emulator that records the Exynos 2500 replay.
 * - [theReplayedExynos2500PassesTheSelfTest] (TJ_NPU_REPLAY=the replay folder, TJ_V4_DIR=a decoded model
 *   folder with selftest/): the self-test of the P2.15 controller run on the host with the recorded chain
 *   outputs (each part answers only the input bytes it was recorded with, by the fake's key), so the
 *   device run's decisions are known before the phone is used.
 */
class NpuReplayTest {
    private val export = TestKit.env("TJ_NPU_REPLAY_EXPORT")
    private val replay = TestKit.env("TJ_NPU_REPLAY")
    private val v4 = File(TestKit.env("TJ_V4_DIR") ?: "")

    @Test
    fun exportFeatures() {
        Assume.assumeTrue("TJ_NPU_REPLAY_EXPORT is not set", export != null)
        Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val dir = File(v4, SelfTestSet.DIR)
        val set = SelfTestSet.read(dir)
        val out = File(export!!).also { it.mkdirs() }
        for (c in set.clips) {
            val samples = SelfTestSet.samples(File(dir, c.file))
            val frames = ArrayList<FloatArray>()
            EngineFrontEnd(16_000).use { fe ->
                var at = 0
                while (at < samples.size) {
                    val k = minOf(5120, samples.size - at)
                    frames += fe.push(samples, at, k)
                    at += k
                }
                frames += fe.finish()
            }
            val all = frames.fold(FloatArray(0)) { a, b -> a + b }
            val bb = ByteBuffer.allocate(4 * all.size).order(ByteOrder.LITTLE_ENDIAN)
            for (v in all) bb.putFloat(v)
            File(out, "${c.id}.f32").writeBytes(bb.array())
            println("${c.id}: ${all.size / 80} frames")
        }
    }

    /** A part answering from the replay folder: sha256 of its inputs (signature = io.json order) -> outputs. */
    private class ReplayPart(dir: File, private val pc: NpuContract) : NpuRunner {
        private val index = File(dir, "index.txt").readLines().filter { it.isNotBlank() }.associate { it.substringBefore(' ') to File(dir, it.substringAfter(' ')) }
        var misses = 0

        private val crc = index.keys.firstOrNull()?.startsWith("crc32:") == true

        override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
            // the fake's keys: SHA-256 of the inputs' bytes in order, or "crc32:<zlib CRC-32>:<bytes>"
            val md = MessageDigest.getInstance("SHA-256")
            val c = java.util.zip.CRC32()
            var len = 0L
            for ((_, v) in inputs) {
                val b = ByteBuffer.allocate(2 * v.size).order(ByteOrder.LITTLE_ENDIAN)
                for (x in v) b.putShort(x)
                if (crc) c.update(b.array()) else md.update(b.array())
                len += b.capacity()
            }
            val h = if (crc) "crc32:%08x:%d".format(c.value, len) else md.digest().joinToString("") { "%02x".format(it) }
            val f = index[h] ?: run { misses++; throw IllegalStateException("replay miss") }
            val sb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return pc.outputs.mapValues { (_, t) -> ShortArray(t.size).also { sb.get(it) } }
        }

        override fun close() {}
    }

    @Test
    fun theReplayedExynos2500PassesTheSelfTest() {
        Assume.assumeTrue("TJ_NPU_REPLAY is not set", replay != null)
        Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val r = File(replay!!)
        val base = "tajweedformer_v4_exynos2500_c16_l64"
        val contract = NpuContract.read(File(r, "$base.io.json"))
        val masks = NpuMasks.read(File(r, "$base.masks.json"), contract)
        val parts = contract.parts.indices.map { k -> ReplayPart(File(r, contract.parts[k].file), contract.partContract(k)) }
        val pub = TestKit.env("TJ_TEST_PUBLIC_KEY") ?: File("").readText().trim()
        val model = TajweedModel.open(v4, ManifestVerifier(listOf(TestKit.hex(pub)), requireSignature = false))
        val scorer = ExactTajweedEngine(model.dir, "c16_l64", loadEngine = false)
        val dir = File(model.dir, SelfTestSet.DIR)
        val set = SelfTestSet.read(dir)
        val expected = SelfTestSet.readExpected(dir, "c16_l64")
        val e = NpuTajweedEngine(scorer, contract, masks, NpuChainRunner(contract, parts), 16_000) {}
        val observed = SelfTestRunner.run(e, scorer, model.calibration, "c16_l64", set, dir, TajweedBackend.INT8)
        assertEquals(0, parts.sumOf { it.misses })
        for ((id, o) in observed) {
            val x = expected.clips.getValue(id)
            println("$id: tokens ${if (o.tokens == x.tokens) "=" else "DIFFER"}, sites ${o.flaggedSites} vs ${x.flaggedSites}, words ${o.flaggedWords} vs ${x.flaggedWords}")
        }
        val planted = set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id }.toSet()
        val v = SelfTestSet.judge(expected, observed, emptyList(), 320, checkLatency = false, planted = planted)
        println("self-test verdict (decisions; latency is the device's): $v")
        scorer.close()
    }
}
