package ai.quranlab.tajweedformer.npu

import ai.quranlab.tajweedformer.TajweedHead
import ai.quranlab.tajweedformer.TajweedHeads
import kotlin.math.exp
import kotlin.math.ln

/**
 * Runs one chunk of the NPU model: every input by name as int16 codes in, every output by name out.
 * [ai.quranlab.tajweedformer.android.LiteRtNpuRunner] is the device one; tests use fakes.
 */
interface NpuRunner : AutoCloseable {
    fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray>
}

/** The integer arithmetic of the host contract, exactly as the device runner was fed. */
object NpuQuant {
    /** `clamp(rint(v / scale) + zeroPoint)` in double, int16 (numpy's round is half to even, as rint). */
    @JvmStatic
    fun quantize(v: Float, t: NpuTensor): Short = clamp16(Math.rint(v.toDouble() / t.scale) + t.zeroPoint)

    @JvmStatic
    fun quantize(v: FloatArray, t: NpuTensor, out: ShortArray = ShortArray(v.size), at: Int = 0): ShortArray {
        for (i in v.indices) out[at + i] = quantize(v[i], t)
        return out
    }

    /** `(q - zeroPoint) * scale`. */
    @JvmStatic
    fun dequantize(q: ShortArray, t: NpuTensor): FloatArray = FloatArray(q.size) { ((q[it] - t.zeroPoint) * t.scale).toFloat() }

    /** A state output for the next chunk's input: a copy when [ratio] is 1, else
     *  `clamp(rint(q * ratio))` in double (run_model's and stream_pack's arithmetic). */
    @JvmStatic
    fun requant(q: ShortArray, ratio: Double): ShortArray =
        if (ratio == 1.0) q.copyOf() else ShortArray(q.size) { clamp16(Math.rint(q[it].toDouble() * ratio)) }

    internal fun clamp16(x: Double): Short = when {
        x <= -32768.0 -> Short.MIN_VALUE
        x >= 32767.0 -> Short.MAX_VALUE
        else -> x.toInt().toShort()
    }

    /** The host op over rows of [width] values, in double, written as float. */
    @JvmStatic
    fun apply(op: HostOp, v: FloatArray, width: Int): FloatArray {
        if (op == HostOp.NONE) return v
        val out = FloatArray(v.size)
        var r = 0
        while (r < v.size) {
            var mx = Double.NEGATIVE_INFINITY
            for (k in 0 until width) mx = maxOf(mx, v[r + k].toDouble())
            var sum = 0.0
            for (k in 0 until width) sum += exp(v[r + k] - mx)
            val lse = mx + ln(sum)
            for (k in 0 until width) {
                val lp = v[r + k] - lse
                out[r + k] = (if (op == HostOp.LOG_SOFTMAX) lp else exp(lp)).toFloat()
            }
            r += width
        }
        return out
    }
}

/**
 * The host side of the NPU stream: fbank frames in (from the exact engine's own front end), the
 * contract's window / shift, the mask table, the state feedback, and each chunk's heads out, finished
 * by their host ops so they mean what the CPU engine's heads mean (same layouts as [TajweedHeads]).
 */
class NpuStreamer(
    val contract: NpuContract,
    private val masks: NpuMasks,
    private val runner: NpuRunner,
) {
    private val bins = contract.x.shape.last()
    private var frames = FloatArray(0)
    private var nFrames = 0
    private var chunk = 0
    private val state = HashMap<String, ShortArray>()
    private val constants: Map<String, ShortArray> = contract.constants.mapValues { (name, v) ->
        val t = contract.inputs.getValue(name)
        ShortArray(t.size) { NpuQuant.quantize(v.toFloat(), t) }
    } + contract.constantCodes  // a chained build's constants, already int16 codes (io.json constant_codes)

    init {
        require(contract.x.size == contract.windowFrames * bins) { "x is ${contract.x.shape}, window ${contract.windowFrames}" }
        reset()
    }

    /** Chunks run so far in this take. */
    val chunks: Int get() = chunk

    /** A new take: states back to zero (each input's zero point). */
    fun reset() {
        state.clear()
        for (s in contract.states) {
            val t = contract.inputs.getValue(s.input)
            state[s.input] = ShortArray(t.size) { t.zeroPoint.toShort() }
        }
        frames = FloatArray(0)
        nFrames = 0
        base = 0
        chunk = 0
    }

    /** [n] more fbank frames (`[n, bins]`, row major); the heads of every chunk they complete. */
    fun push(fbank: FloatArray, n: Int = fbank.size / bins): List<TajweedHeads> {
        require(fbank.size >= n * bins)
        if (n > 0) {
            if (frames.size < (nFrames + n) * bins) frames = frames.copyOf(maxOf((nFrames + n) * bins, frames.size * 2))
            System.arraycopy(fbank, 0, frames, nFrames * bins, n * bins)
            nFrames += n
        }
        val out = ArrayList<TajweedHeads>()
        while (chunk * contract.shiftFrames - base + contract.windowFrames <= nFrames) {
            out.add(runChunk(chunk * contract.shiftFrames - base))
            chunk++
        }
        // drop the frames no later window reads
        val drop = chunk * contract.shiftFrames - base
        if (drop > 0 && drop <= nFrames && drop >= 4 * contract.windowFrames) {
            System.arraycopy(frames, drop * bins, frames, 0, (nFrames - drop) * bins)
            nFrames -= drop
            base += drop
        }
        return out
    }

    /** The absolute fbank frame of frames[0]. */
    private var base = 0

    private fun runChunk(at: Int): TajweedHeads {
        val inputs = HashMap<String, ShortArray>(contract.inputs.size)
        val x = ShortArray(contract.x.size)
        for (i in 0 until contract.windowFrames * bins) x[i] = NpuQuant.quantize(frames[at * bins + i], contract.x)
        inputs["x"] = x
        inputs.putAll(state)
        inputs.putAll(masks.forChunk(chunk))
        inputs.putAll(constants)
        val outs = runner.run(inputs)
        for (s in contract.states) {
            val q = outs[s.output] ?: throw IllegalStateException("the NPU gave no ${s.output}")
            state[s.input] = NpuQuant.requant(q, s.requantRatio)
        }
        return heads(outs)
    }

    /** The heads of one chunk's outputs. */
    fun heads(outs: Map<String, ShortArray>): TajweedHeads {
        val values = HashMap<TajweedHead, FloatArray>()
        var n40 = -1
        for (h in contract.heads) {
            val q = outs[h.output] ?: continue
            val t = contract.outputs.getValue(h.output)
            val v = NpuQuant.apply(h.op, NpuQuant.dequantize(q, t), h.head.width)
            val frames = v.size / h.head.width
            val k = if (h.head.frameMs == 40) frames else frames / 2
            if (n40 < 0) n40 = k else check(n40 == k) { "${h.output} has $frames frames, $n40 40 ms frames expected" }
            values[h.head] = v
        }
        return TajweedHeads(maxOf(n40, 0), values)
    }
}
