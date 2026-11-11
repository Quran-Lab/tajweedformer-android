package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.npu.HostOp
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuQuant
import ai.quranlab.tajweedformer.npu.NpuRunner
import ai.quranlab.tajweedformer.npu.NpuStreamer
import ai.quranlab.tajweedformer.npu.NpuTensor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.exp

/**
 * The NPU backend's host side on the JVM (the NPU itself is not here): io.json and masks.json of the
 * staged builds, quantisation, state requantisation, the host head ops, and the stream loop with a
 * fake runner. Where the device-fed bytes of the 11-07 Pixel runs are on this machine
 * (npu_work/c16_l64/pack_t7), the x codes are compared with them byte for byte.
 */
class NpuHostTest : OpenChecked() {
    private val transport = File(TestKit.env("TJ_V4_TRANSPORT_DIR") ?: "")
    private val npuWork = File(TestKit.env("TJ_NPU_WORK") ?: "")

    private fun gunzip(f: File): String = GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun staged(soc: String, ext: String): File = File(transport, "npu/$soc/c16_l64/tajweedformer_v4_${soc}_c16_l64.$ext.gz")

    @Test
    fun stagedContractsAndMaskTablesParse() {
        val socs = listOf("tensor_g5", "exynos2600").filter { staged(it, "io.json").isFile }
        Assume.assumeTrue("no staged NPU io.json", socs.isNotEmpty())
        for (soc in socs) {
            val c = NpuContract.parse(gunzip(staged(soc, "io.json")))
            assertEquals(soc, c.soc)
            assertEquals("c16_l64", c.context)
            assertEquals(45, c.windowFrames)
            assertEquals(32, c.shiftFrames)
            assertEquals(320, c.chunkMs)
            assertEquals(64, c.leftFrames)
            assertEquals(5, c.maskChunkSets)
            assertEquals(110, c.inputs.size)
            assertEquals(97, c.states.size)
            assertEquals(12, c.heads.size)
            assertEquals(HeadId.entries.toSet(), c.heads.map { it.head }.toSet())
            assertEquals(12, c.maskInputs.size)
            assertEquals(listOf(1, 1, 45, 80), c.x.shape)
            assertEquals(HostOp.LOG_SOFTMAX, c.heads.first { it.head == HeadId.LOG_PROBS }.op)
            assertEquals(HostOp.SOFTMAX, c.heads.first { it.head == HeadId.GEM }.op)
            assertEquals(HostOp.NONE, c.heads.first { it.head == HeadId.CONF }.op)
            val m = NpuMasks.parse(gunzip(staged(soc, "masks.json")), c)
            assertEquals(5, m.sets.size)
            // chunk 0 keeps less of the left context than chunk 4 and later (the same set from 4 on)
            val k0 = m.forChunk(0).getValue("zf_keep0").count { it > 0 }
            val k4 = m.forChunk(4).getValue("zf_keep0").count { it > 0 }
            assertTrue("$k0 < $k4", k0 < k4)
            assertArrayEquals(m.forChunk(4).getValue("zf_keep7"), m.forChunk(99).getValue("zf_keep7"))
            println("$soc: io.json ${c.inputs.size} inputs, ${c.states.size} states (${c.states.count { it.requantRatio != 1.0 }} requantised); masks ok")
        }
    }

    @Test
    fun xQuantisationMatchesTheBytesThePixelsWereFed() {
        val io = File("")
        val feats = File(npuWork, "feats/eval/panel_consonants_124.f32")
        val packed = File(npuWork, "c16_l64/pack_t7/x/panel_consonants_124.raw")
        Assume.assumeTrue("the 11-07 Pixel stream pack is not here", io.isFile && feats.isFile && packed.isFile)
        val c = NpuContract.read(io)
        val fb = ByteBuffer.wrap(feats.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val f = FloatArray(fb.remaining()).also { fb.get(it) }
        val sb = ByteBuffer.wrap(packed.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val want = ShortArray(sb.remaining()).also { sb.get(it) }
        assertArrayEquals(want, NpuQuant.quantize(f, c.x))
        println("x codes of panel_consonants_124: ${want.size} identical to the Pixel stream pack")
    }

    @Test
    fun quantisationRoundsHalfToEvenAndClamps() {
        val t = NpuTensor("x", listOf(4), "int16", 0.5, 0)
        assertEquals(0.toShort(), NpuQuant.quantize(0.25f, t))     // 0.5 -> 0
        assertEquals(2.toShort(), NpuQuant.quantize(0.75f, t))     // 1.5 -> 2
        assertEquals((-2).toShort(), NpuQuant.quantize(-0.75f, t)) // -1.5 -> -2
        assertEquals(32767.toShort(), NpuQuant.quantize(1e6f, t))
        assertEquals((-32768).toShort(), NpuQuant.quantize(-1e6f, t))
        val z = NpuTensor("y", listOf(1), "int16", 0.25, 10)
        assertEquals(14.toShort(), NpuQuant.quantize(1f, z))
        assertEquals(1f, NpuQuant.dequantize(shortArrayOf(14), z)[0], 0f)
        // state requantisation: a copy at ratio 1, else rint(q * r) clamped
        val q = shortArrayOf(3, -3, 5, 32767, -32768)
        assertArrayEquals(q, NpuQuant.requant(q, 1.0))
        assertArrayEquals(shortArrayOf(4, -4, 6, 32767, -32768), NpuQuant.requant(q, 1.1667))
        assertArrayEquals(shortArrayOf(2, -2, 2, 16384, -16384), NpuQuant.requant(q, 0.5))   // 1.5 -> 2, 2.5 -> 2
    }

    @Test
    fun hostOpsFinishTheHeads() {
        val v = floatArrayOf(1f, 2f, 3f, -1f, 0f, 1f)
        val lp = NpuQuant.apply(HostOp.LOG_SOFTMAX, v, 3)
        for (r in 0 until 2) assertEquals(1.0, (0 until 3).sumOf { exp(lp[3 * r + it].toDouble()) }, 1e-6)
        assertEquals(lp[0] - lp[1], -1f, 1e-6f)
        val p = NpuQuant.apply(HostOp.SOFTMAX, v, 3)
        for (k in 0 until 6) assertEquals(exp(lp[k].toDouble()), p[k].toDouble(), 1e-6)
        assertArrayEquals(v, NpuQuant.apply(HostOp.NONE, v, 1), 0f)
    }

    /** A small contract: x [1,1,5,2], one state, two masks of 2 sets, the 12 heads at 1 frame. */
    private fun tinyContract(): NpuContract {
        val ins = linkedMapOf(
            "x" to NpuTensor("x", listOf(1, 1, 5, 2), "int16", 0.01, 0),
            "s0" to NpuTensor("s0", listOf(3), "int16", 1.0, 0),
            "zf_keep0" to NpuTensor("zf_keep0", listOf(2), "int16", 1.0, 0),
            "zf_fill0" to NpuTensor("zf_fill0", listOf(2), "int16", 1.0, 0),
        )
        val outs = linkedMapOf("new_s0" to NpuTensor("new_s0", listOf(3), "int16", 2.0, 0))
        val heads = HeadId.entries.map { h ->
            val frames = if (h.frameMs == 40) 1 else 2
            outs["o_${h.key}"] = NpuTensor("o_${h.key}", listOf(frames, h.width), "int16", 0.1, 0)
            ai.quranlab.tajweedformer.npu.NpuHeadSpec("o_${h.key}", h, if (h.kind == HeadKind.LOG_PROBS) HostOp.LOG_SOFTMAX else if (h.kind == HeadKind.PROBS) HostOp.SOFTMAX else HostOp.NONE)
        }
        return NpuContract("tensor_g5", "c2_l2", 5, 3, 40, 2, 2, ins, outs,
            listOf(ai.quranlab.tajweedformer.npu.NpuState("s0", "new_s0", 0.5)), heads, listOf("zf_keep0", "zf_fill0"), emptyMap())
    }

    @Test
    fun theStreamLoopWindowsFeedsBackStatesAndPicksMaskSets() {
        val c = tinyContract()
        val masks = NpuMasks(listOf(
            mapOf("zf_keep0" to shortArrayOf(0, 1), "zf_fill0" to shortArrayOf(-30, 0)),
            mapOf("zf_keep0" to shortArrayOf(1, 1), "zf_fill0" to shortArrayOf(0, 0)),
        ))
        val seen = ArrayList<Map<String, ShortArray>>()
        val runner = object : NpuRunner {
            override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
                seen.add(inputs.mapValues { it.value.copyOf() })
                val out = HashMap<String, ShortArray>()
                // the state output: input + 7
                out["new_s0"] = ShortArray(3) { (inputs.getValue("s0")[it] + 7).toShort() }
                for ((name, t) in c.outputs) if (name != "new_s0") out[name] = ShortArray(t.size) { (it % 5).toShort() }
                return out
            }
            override fun close() {}
        }
        val s = NpuStreamer(c, masks, runner)
        // 14 frames of 2 bins, value = frame index / 100, pushed in pieces of 1, 6 and 7 frames
        val all = FloatArray(28) { (it / 2) / 100f }
        val heads = ArrayList<HeadValues>()
        heads += s.push(all.copyOfRange(0, 2))
        heads += s.push(all.copyOfRange(2, 14))
        heads += s.push(all.copyOfRange(14, 28))
        // windows of 5 every 3 frames over 14 frames: starts 0, 3, 6, 9 -> 4 chunks
        assertEquals(4, heads.size)
        assertEquals(4, seen.size)
        for ((k, inp) in seen.withIndex()) {
            val x = inp.getValue("x")
            assertEquals(10, x.size)
            for (i in 0 until 5) assertEquals("chunk $k frame $i", (3 * k + i).toShort(), x[2 * i])
            assertArrayEquals(if (k == 0) shortArrayOf(0, 1) else shortArrayOf(1, 1), inp.getValue("zf_keep0"))
        }
        // states: 0 -> (0 + 7) * 0.5 = 3.5 -> 4 (half to even) -> (4 + 7) * 0.5 = 5.5 -> 6 -> 6.5 -> 6
        assertArrayEquals(shortArrayOf(0, 0, 0), seen[0].getValue("s0"))
        assertArrayEquals(shortArrayOf(4, 4, 4), seen[1].getValue("s0"))
        assertArrayEquals(shortArrayOf(6, 6, 6), seen[2].getValue("s0"))
        assertArrayEquals(shortArrayOf(6, 6, 6), seen[3].getValue("s0"))
        // heads: one 40 ms frame each, every head, log-softmax rows that sum to 1
        val h = heads[0]
        assertEquals(1, h.n40)
        assertEquals(HeadId.entries.toSet(), h.available)
        assertEquals(1.0, h[HeadId.LOG_PROBS]!!.sumOf { exp(it.toDouble()) }, 1e-5)
        assertEquals(0.1f, h[HeadId.CONF]!![1], 1e-6f)
        // a new take starts from zero states and set 0
        s.reset()
        seen.clear()
        s.push(all.copyOfRange(0, 10))
        assertArrayEquals(shortArrayOf(0, 0, 0), seen[0].getValue("s0"))
        assertArrayEquals(shortArrayOf(0, 1), seen[0].getValue("zf_keep0"))
    }

    @Test
    fun aLongTakeKeepsTheWindowsRightAfterCompaction() {
        val c = tinyContract()
        val masks = NpuMasks(listOf(mapOf("zf_keep0" to shortArrayOf(1, 1), "zf_fill0" to shortArrayOf(0, 0))).let { it + it })
        var chunks = 0
        val runner = object : NpuRunner {
            override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
                val x = inputs.getValue("x")
                for (i in 0 until 5) assertEquals((3 * chunks + i).toShort(), x[2 * i])
                chunks++
                return c.outputs.mapValues { (_, t) -> ShortArray(t.size) }
            }
            override fun close() {}
        }
        val s = NpuStreamer(c, masks, runner)
        val n = 3000
        val all = FloatArray(2 * n) { ((it / 2) % 30000) / 100f }
        var at = 0
        var k = 1
        while (at < n) {
            val m = minOf(k, n - at)
            s.push(all.copyOfRange(2 * at, 2 * (at + m)))
            at += m
            k = k % 17 + 1
        }
        assertEquals((n - 5) / 3 + 1, chunks)
    }
}
