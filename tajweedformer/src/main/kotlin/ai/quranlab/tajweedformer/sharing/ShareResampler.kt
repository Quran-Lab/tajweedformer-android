package ai.quranlab.tajweedformer.sharing

/**
 * Mono float PCM at any rate (8 to 384 kHz) to 48 kHz for the sharing encoder: a windowed-sinc
 * interpolator (Kaiser window, beta 9, 32 zero crossings each side, cutoff 0.95 of the lower
 * Nyquist rate), in pure Kotlin. Zero phase and aligned with the input: output sample k is the
 * band-limited input at time k * inputRate / 48000, the input taken as silence before its first
 * sample and after its last, so the whole output is ceil(n * 48000 / inputRate) samples once
 * flushed. Streaming, with reused buffers. Only the shared copy goes through it; recognition
 * resamples the original input with the engine's own resampler.
 *
 * (The Swift SDK uses AVAudioConverter at its mastering quality; the two are not byte-identical,
 * which only matters for non-48 kHz input. At 48 kHz neither resamples.)
 */
class ShareResampler(val inputRate: Int) {
    private val outRate = OpusSettings.CODED_RATE
    /** Cutoff as a fraction of the input's Nyquist rate. */
    private val fc = 0.95 * minOf(1.0, outRate.toDouble() / inputRate)
    /** Half width of the kernel, in input samples. */
    private val half = ZERO_CROSSINGS / fc
    private val reach = Math.ceil(half).toInt()
    private val table: FloatArray
    private val tableScale: Double

    /** Input not yet consumed; buf[0] is absolute input index [base]. */
    private var buf = FloatArray(4096)
    private var len = 0
    private var base = 0L
    private var received = 0L
    /** The next output sample's index. */
    private var k = 0L
    private var out = FloatArray(2048)
    private var ended = false

    init {
        if (inputRate < 8_000 || inputRate > 384_000) throw SharingException("unsupported_audio", "cannot resample $inputRate Hz to 48 kHz")
        val n = (half * STEPS).toInt() + 2
        tableScale = STEPS.toDouble()
        table = FloatArray(n) { i ->
            val x = i / tableScale
            if (x >= half) 0f else (fc * sinc(fc * x) * kaiser(x / half)).toFloat()
        }
    }

    /** Resamples [length] samples and hands the 48 kHz output to [sink] (in one or more pieces). */
    fun process(samples: FloatArray, offset: Int, length: Int, sink: (FloatArray, Int, Int) -> Unit) {
        if (ended || length <= 0) return
        if (len + length > buf.size) {
            val nb = FloatArray(maxOf(buf.size * 2, len + length))
            buf.copyInto(nb, 0, 0, len)
            buf = nb
        }
        samples.copyInto(buf, len, offset, offset + length)
        len += length
        received += length
        run(final = false, sink)
    }

    /** The tail. The resampler cannot be used afterwards. */
    fun flush(sink: (FloatArray, Int, Int) -> Unit) {
        if (ended) return
        ended = true
        run(final = true, sink)
    }

    private fun run(final: Boolean, sink: (FloatArray, Int, Int) -> Unit) {
        val total = if (final) (received * outRate + inputRate - 1) / inputRate else Long.MAX_VALUE
        var n = 0
        while (k < total) {
            // t = k * inputRate / outRate, as an integer part and a fraction
            val num = k * inputRate
            val ti = num / outRate
            val frac = (num % outRate).toDouble() / outRate
            if (!final && ti + reach >= received) break
            var acc = 0.0
            val lo = ti - reach + 1
            val hi = ti + reach
            var j = lo
            while (j <= hi) {
                val idx = j - base
                if (j >= 0 && j < received && idx >= 0 && idx < len) {
                    val d = Math.abs((ti - j) + frac)
                    val p = d * tableScale
                    val pi = p.toInt()
                    if (pi + 1 < table.size) {
                        val w = table[pi] + (table[pi + 1] - table[pi]) * (p - pi).toFloat()
                        acc += buf[idx.toInt()] * w
                    }
                }
                j++
            }
            if (n == out.size) { sink(out, 0, n); n = 0 }
            out[n++] = acc.toFloat()
            k++
        }
        if (n > 0) sink(out, 0, n)
        // drop input no later output needs
        val keepFrom = (k * inputRate) / outRate - reach
        val drop = (keepFrom - base).coerceIn(0, len.toLong()).toInt()
        if (drop > 0) {
            buf.copyInto(buf, 0, drop, len)
            len -= drop
            base += drop
        }
    }

    private companion object {
        const val ZERO_CROSSINGS = 32
        const val STEPS = 512
        const val BETA = 9.0

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else Math.sin(Math.PI * x) / (Math.PI * x)

        fun kaiser(r: Double): Double {
            if (r >= 1) return 0.0
            return bessel0(BETA * Math.sqrt(1 - r * r)) / bessel0(BETA)
        }

        fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 50) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}
