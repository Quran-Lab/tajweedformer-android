package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.SdkInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random

/** What a shared recording is written with: Ogg Opus ([OggOpusWriter]) or, SDK 4.0, Ogg FLAC ([OggFlacWriter]). */
internal interface ShareWriter : AutoCloseable {
    /** Audio seconds written so far (at the input rate). */
    val duration: Double

    /** Opus' pre-skip (48 kHz samples); 0 for FLAC. */
    val preSkip: Int

    /** Mono samples at the input rate. */
    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset)

    /** Writes the last pages and closes the last segment. Returns the duration in ms. */
    fun finish(): Int

    /** Stops without finishing (the caller removes the files). */
    fun abandon()
}

/**
 * SHARING_40.md 19.3: a float sample to the 16-bit integer the FLAC encoder takes: v = x * 32768 (exact), NaN gives 0,
 * v at or above 32767 gives 32767, at or below -32768 gives -32768, otherwise v rounded to the nearest integer, a tie to
 * the even one (`vectors/sharing40/pcm16.json`).
 */
internal object Pcm16 {
    @JvmStatic
    fun of(x: Float): Int {
        if (x.isNaN()) return 0
        val v = x.toDouble() * 32768.0
        if (v >= 32767.0) return 32767
        if (v <= -32768.0) return -32768
        return Math.rint(v).toInt()
    }
}

/**
 * SHARING_40.md 19.4.3: one FLAC frame of a mono 16-bit block (1 to 4096 samples), integer arithmetic only, byte for
 * byte as libFLAC 1.5.0 writes it with block size 4096, no LPC, residual partition orders 0 to 4, no escapes and no
 * exhaustive search (its fixed-predictor path). A direct port of `tools/sharing40.py` `encode_frame`.
 */
internal object FlacFrame {
    const val BLOCK = 4096
    const val BPS = 16
    const val MAX_PARTITION_ORDER = 4
    private const val UINT32_MAX = 0xFFFFFFFFL

    private fun table(poly: Int, width: Int): IntArray {
        val top = 1 shl (width - 1)
        val mask = (1 shl width) - 1
        return IntArray(256) { i ->
            var r = i shl (width - 8)
            repeat(8) { r = if (r and top != 0) (r shl 1) xor poly else r shl 1 }
            r and mask
        }
    }

    private val CRC8 = table(0x07, 8)
    private val CRC16 = table(0x8005, 16)

    fun crc8(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var c = 0
        for (i in from until to) c = CRC8[c xor (b[i].toInt() and 0xff)]
        return c
    }

    fun crc16(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var c = 0
        for (i in from until to) c = ((c shl 8) and 0xffff) xor CRC16[(c ushr 8) xor (b[i].toInt() and 0xff)]
        return c
    }

    /** An MSB-first bit writer. */
    private class Bits(size: Int = 64) {
        var out = ByteArray(size)
        var len = 0
        private var acc = 0L
        private var n = 0

        fun put(v: Long, bits: Int) {
            if (bits == 0) return
            acc = (acc shl bits) or (v and ((1L shl bits) - 1))
            n += bits
            while (n >= 8) {
                n -= 8
                if (len == out.size) out = out.copyOf(out.size * 2)
                out[len++] = (acc ushr n).toByte()
            }
            acc = acc and ((1L shl n) - 1)
        }

        fun put(v: Int, bits: Int) = put(v.toLong(), bits)

        /** q zero bits, then a one. */
        fun unary(q: Long) {
            var r = q
            while (r >= 32) {
                put(0L, 32)
                r -= 32
            }
            put(1L, r.toInt() + 1)
        }

        fun pad() { if (n != 0) put(0L, 8 - n) }

        fun bytes(): ByteArray {
            check(n == 0)
            return out.copyOf(len)
        }
    }

    private val BLOCK_CODES = mapOf(192 to 1, 576 to 2, 1152 to 3, 2304 to 4, 4608 to 5, 256 to 8, 512 to 9, 1024 to 10, 2048 to 11,
        4096 to 12, 8192 to 13, 16384 to 14, 32768 to 15)
    private val RATE_CODES = mapOf(88200 to 1, 176400 to 2, 192000 to 3, 8000 to 4, 16000 to 5, 22050 to 6, 24000 to 7, 32000 to 8,
        44100 to 9, 48000 to 10, 96000 to 11)

    /** The frame header's sample rate code and its trailing field (value, bits), libFLAC's choice. */
    private fun rateCode(rate: Int): Pair<Int, Pair<Int, Int>?> {
        RATE_CODES[rate]?.let { return it to null }
        if (rate <= 255_000 && rate % 1000 == 0) return 12 to (rate / 1000 to 8)
        if (rate <= 655_350 && rate % 10 == 0) return 14 to (rate / 10 to 16)
        if (rate <= 0xFFFF) return 13 to (rate to 16)
        throw IllegalArgumentException("sample rate out of range")
    }

    /** RFC 9639 9.1.5: the frame number coded like UTF-8. */
    private fun utf8Number(v: Long, b: Bits) {
        if (v < 0x80) {
            b.put(v, 8)
            return
        }
        val forms = listOf(Triple(2, 0xC0, 0x800L), Triple(3, 0xE0, 0x10000L), Triple(4, 0xF0, 0x200000L), Triple(5, 0xF8, 0x4000000L),
            Triple(6, 0xFC, 0x80000000L))
        for ((bytes, lead, limit) in forms) {
            if (v < limit) {
                b.put(lead.toLong() or (v ushr (6 * (bytes - 1))), 8)
                for (i in bytes - 2 downTo 0) b.put(0x80L or ((v ushr (6 * i)) and 0x3F), 8)
                return
            }
        }
        throw IllegalArgumentException("frame number out of range")
    }

    private fun header(n: Int, number: Long, rate: Int): ByteArray {
        val b = Bits(16)
        b.put(0x3FFE, 14)
        b.put(0, 1)
        b.put(0, 1)
        var code = BLOCK_CODES[n]
        var hint: Pair<Int, Int>? = null
        if (code == null) {
            if (n <= 256) { code = 6; hint = n - 1 to 8 } else { code = 7; hint = n - 1 to 16 }
        }
        b.put(code, 4)
        val (rc, rhint) = rateCode(rate)
        b.put(rc, 4)
        b.put(0, 4)
        b.put(4, 3)
        b.put(0, 1)
        utf8Number(number, b)
        for (f in listOf(hint, rhint)) if (f != null) b.put(f.first, f.second)
        b.put(0, 8)
        val head = b.bytes()
        head[head.size - 1] = crc8(head, 0, head.size - 1).toByte()
        return head
    }

    /** libFLAC get_wasted_bits_: the trailing zero bits every sample shares (0 for an all-zero block). */
    private fun wastedBits(x: IntArray, n: Int): Int {
        var acc = 0
        for (i in 0 until n) {
            acc = acc or x[i]
            if (acc and 1 != 0) break
        }
        if (acc == 0) return 0
        return minOf(Integer.numberOfTrailingZeros(acc), BPS)
    }

    private fun residual(x: IntArray, n: Int, order: Int): LongArray = LongArray(n - order) { j ->
        val i = j + order
        when (order) {
            0 -> x[i].toLong()
            1 -> x[i].toLong() - x[i - 1]
            2 -> x[i].toLong() - 2L * x[i - 1] + x[i - 2]
            3 -> x[i].toLong() - 3L * x[i - 1] + 3L * x[i - 2] - x[i - 3]
            else -> x[i].toLong() - 4L * x[i - 1] + 6L * x[i - 2] - 4L * x[i - 3] + x[i - 4]
        }
    }

    private class Rice(val bits: Long, val order: Int, val params: IntArray)

    /** libFLAC set_partitioned_rice_: each partition's parameter from its sum of |residual|, and the estimated bits. */
    private fun riceEstimate(sums: LongArray, n: Int, order: Int, po: Int): Rice {
        var bits = 2L + 4L
        val base = n shr po
        val divBase = 0x40000L / base
        val params = IntArray(sums.size)
        for (p in sums.indices) {
            val s = sums[p]
            val cnt: Long
            val div: Long
            if (p == 0) {
                cnt = (base - order).toLong()
                div = 0x40000L / cnt
            } else {
                cnt = base.toLong()
                div = divBase
            }
            val q = if (s >= 2) ((s - 1) * div) shr 18 else 0L
            var k = if (q == 0L) 0 else 64 - java.lang.Long.numberOfLeadingZeros(q)
            if (k >= 15) k = 14
            var pb = 4 + (1 + k) * cnt + (if (k > 0) s shr (k - 1) else s shl 1) - (cnt shr 1)
            pb = minOf(pb, UINT32_MAX)
            bits = if (pb < UINT32_MAX - bits) bits + pb else UINT32_MAX
            params[p] = k
        }
        return Rice(bits, po, params)
    }

    /** libFLAC find_best_partition_order_: the highest allowed order down to 0, the smallest estimate (a tie keeps the higher). */
    private fun bestPartition(res: LongArray, n: Int, order: Int, limit: Int): Rice {
        var maxPo = limit
        while (maxPo > 0 && (n shr maxPo) <= order) maxPo--
        val parts = 1 shl maxPo
        val dps = n shr maxPo
        val top = LongArray(parts)
        var at = 0
        for (p in 0 until parts) {
            val cnt = if (p == 0) dps - order else dps
            var s = 0L
            for (i in at until at + cnt) s += Math.abs(res[i])
            top[p] = s
            at += cnt
        }
        val levels = arrayOfNulls<LongArray>(maxPo + 1)
        levels[maxPo] = top
        for (po in maxPo - 1 downTo 0) {
            val up = levels[po + 1]!!
            levels[po] = LongArray(1 shl po) { i -> up[2 * i] + up[2 * i + 1] }
        }
        var best: Rice? = null
        for (po in maxPo downTo 0) {
            val r = riceEstimate(levels[po]!!, n, order, po)
            if (best == null || r.bits < best.bits) best = r
        }
        return best!!
    }

    /** The frame of the first [n] samples of [samples] (16-bit values), frame number [number], at [rate]. */
    fun encode(samples: IntArray, n: Int, number: Long, rate: Int): ByteArray {
        require(n in 1..BLOCK)
        val w = wastedBits(samples, n)
        val x = IntArray(n) { samples[it] shr w }
        val sbps = BPS - w
        val limit = minOf(minOf(Integer.numberOfTrailingZeros(n), 15), MAX_PARTITION_ORDER)
        // 0 verbatim, 1 constant, 2 fixed
        var kind = 0
        val cost = 8L + w + n.toLong() * sbps
        var order = 0
        var res: LongArray? = null
        var rice: Rice? = null
        if (n > 4) {
            var t0 = 0L
            var t1 = 0L
            var t2 = 0L
            var t3 = 0L
            var t4 = 0L
            for (i in 4 until n) {
                val a = x[i].toLong()
                val b = x[i - 1].toLong()
                val c = x[i - 2].toLong()
                val d = x[i - 3].toLong()
                val e = x[i - 4].toLong()
                t0 += Math.abs(a)
                t1 += Math.abs(a - b)
                t2 += Math.abs(a - 2 * b + c)
                t3 += Math.abs(a - 3 * b + 3 * c - d)
                t4 += Math.abs(a - 4 * b + 6 * c - 4 * d + e)
            }
            order = when {
                t0 <= minOf(minOf(t1, t2), minOf(t3, t4)) -> 0
                t1 <= minOf(t2, minOf(t3, t4)) -> 1
                t2 <= minOf(t3, t4) -> 2
                t3 <= t4 -> 3
                else -> 4
            }
            if (t1 == 0L && (0 until n).all { x[it] == x[0] }) {
                val bits = 8L + w + sbps
                if (bits < cost) kind = 1
            } else {
                val r = residual(x, n, order)
                val best = bestPartition(r, n, order, limit)
                var est = 8L + w + order.toLong() * sbps
                est = if (best.bits < UINT32_MAX - est) est + best.bits else UINT32_MAX
                if (est < cost) {
                    kind = 2
                    res = r
                    rice = best
                }
            }
        }
        val b = Bits(maxOf(64, n * 3))
        val flag = if (w > 0) 1 else 0
        when (kind) {
            1 -> {
                b.put(0x00 or flag, 8)
                if (w > 0) b.unary((w - 1).toLong())
                b.put(x[0], sbps)
            }
            0 -> {
                b.put(0x02 or flag, 8)
                if (w > 0) b.unary((w - 1).toLong())
                for (i in 0 until n) b.put(x[i], sbps)
            }
            else -> {
                val r = res!!
                val p = rice!!
                b.put(0x10 or (order shl 1) or flag, 8)
                if (w > 0) b.unary((w - 1).toLong())
                for (i in 0 until order) b.put(x[i], sbps)
                b.put(0, 2)
                b.put(p.order, 4)
                val base = n shr p.order
                var at = 0
                for ((part, k) in p.params.withIndex()) {
                    val cnt = if (part == 0) base - order else base
                    b.put(k, 4)
                    for (i in at until at + cnt) {
                        val v = r[i]
                        val u = if (v >= 0) v shl 1 else ((-v) shl 1) - 1
                        b.unary(u ushr k)
                        b.put(u and ((1L shl k) - 1), k)
                    }
                    at += cnt
                }
            }
        }
        b.pad()
        val head = header(n, number, rate)
        val body = b.bytes()
        val frame = ByteArray(head.size + body.size + 2)
        head.copyInto(frame)
        body.copyInto(frame, head.size)
        val crc = crc16(frame, 0, head.size + body.size)
        frame[frame.size - 2] = (crc ushr 8).toByte()
        frame[frame.size - 1] = crc.toByte()
        return frame
    }
}

/**
 * SDK 4.0 (SHARING_40.md 19.4): one shared recording as Ogg FLAC at its capture rate, mono, 16-bit, block size 4096,
 * one frame per page (RFC 9639 10.1, the mapping 1.0), written while it runs, byte for byte as `tools/sharing40.py`
 * writes it. Segment 1 starts with the mapping page (79 bytes) and the comment page (vendor `QuranLab SDK <version>`);
 * segment k holds F(k) = (T * rate + 4095) / 4096 frames, T = 10, 20, 40, then 60 seconds. The page of the newest frame
 * is held until the next frame is encoded or the stop (then with the end-of-stream flag): a segment closes once it has
 * its frames and the frame after them exists, so no segment is ever empty and the last always ends the stream.
 * Sequence numbers and granule positions (samples through the page's frame) run on across segments.
 */
internal class OggFlacWriter @JvmOverloads constructor(
    private val segmentFile: (Int) -> File,
    val inputRate: Int,
    private val serial: Int = Random.nextInt(),
    /** The comment's vendor; null: `QuranLab SDK <version>` (the cross-SDK vector names its own). */
    vendor: String? = null,
    private val onSegment: (OggSegment) -> Unit = {},
) : ShareWriter {
    private val block = IntArray(FlacFrame.BLOCK)
    private var filled = 0
    private var frames = 0L
    private var held: ByteArray? = null
    private var heldSamples = 0
    private var granule = 0L
    private var sequence = 0
    private var segment = 0
    private var segmentFrames = 0
    private var out: FileOutputStream? = null
    private var ended = false

    /** Input frames given to the writer. */
    var inputFrames = 0L
        private set

    /** Non-finite input samples (NaN written as 0, an infinity at full scale). */
    var nonFiniteSamples = 0L
        private set

    override val duration: Double get() = inputFrames.toDouble() / inputRate
    override val preSkip: Int get() = 0

    init {
        if (inputRate < 8_000 || inputRate > 192_000 || inputRate == OggOpusWriter.RATE) throw SharingException("unsupported_audio", "$inputRate Hz")
        page(mappingPacket(inputRate), 0, 0x02, audio = false)
        page(commentPacket(vendor ?: vendor()), 0, 0, audio = false)
    }

    override fun append(samples: FloatArray, offset: Int, length: Int) {
        if (length <= 0 || ended) return
        inputFrames += length
        for (i in 0 until length) {
            val v = samples[offset + i]
            if (!v.isFinite()) nonFiniteSamples++
            push(Pcm16.of(v))
        }
    }

    /** 16-bit samples as they are (a capture that is already integers, and the vectors). */
    fun appendPcm(samples: IntArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0 || ended) return
        inputFrames += length
        for (i in 0 until length) push(samples[offset + i].coerceIn(-32768, 32767))
    }

    private fun push(v: Int) {
        block[filled++] = v
        if (filled == FlacFrame.BLOCK) encodeBlock()
    }

    private fun encodeBlock() {
        val f = FlacFrame.encode(block, filled, frames++, inputRate)
        val n = filled
        filled = 0
        // the hold: the page before this one is written now that it is known not to be the last
        held?.let { audioPage(it, heldSamples, last = false) }
        held = f
        heldSamples = n
    }

    override fun finish(): Int {
        if (!ended) {
            ended = true
            if (filled > 0) encodeBlock()
            held?.let { audioPage(it, heldSamples, last = true) }
            held = null
            closeSegment(last = true)
        }
        return Math.round(duration * 1000).toInt()
    }

    override fun abandon() {
        ended = true
        try { out?.close() } catch (_: IOException) {}
        out = null
    }

    override fun close() { if (!ended) abandon() }

    private fun audioPage(packet: ByteArray, samples: Int, last: Boolean) {
        granule += samples
        page(packet, granule, if (last) 0x04 else 0, audio = true)
    }

    private fun closeSegment(last: Boolean) {
        val o = out ?: return
        try { o.close() } catch (_: IOException) {}
        out = null
        onSegment(OggSegment(segment, segmentFile(segment), granule, last, 0))
    }

    private fun page(packet: ByteArray, granule: Long, flags: Int, audio: Boolean) {
        val d = OggOpusWriter.pageBytes(listOf(packet), granule, flags, serial, sequence)
        if (out == null) {
            segment++
            segmentFrames = 0
            val f = segmentFile(segment)
            out = try { FileOutputStream(f) } catch (e: IOException) {
                throw SharingException("storage", "cannot create ${f.name}: ${e.message}")
            }
        }
        try {
            out!!.write(d)
        } catch (e: IOException) {
            throw SharingException("storage", "cannot write ${segmentFile(segment).name}: ${e.message}")
        }
        sequence++
        if (audio) {
            segmentFrames++
            if (flags and 0x04 == 0 && segmentFrames >= framesIn(segment, inputRate)) closeSegment(last = false)
        }
    }

    companion object {
        /** Frames in segment [k] (1, 2, ...) at [rate]: (T * rate + 4095) / 4096 with T = 10, 20, 40, then 60 s. */
        @JvmStatic
        fun framesIn(k: Int, rate: Int): Int = ((SegmentPlan.seconds(k).toLong() * rate + FlacFrame.BLOCK - 1) / FlacFrame.BLOCK).toInt()

        /** The comment's vendor: "QuranLab SDK 4.0.0". */
        @JvmStatic
        fun vendor(): String = "QuranLab SDK ${SdkInfo.VERSION}"

        /** 19.4.1: 0x7F FLAC, mapping 1.0, one header packet follows, fLaC, STREAMINFO (51 bytes). */
        @JvmStatic
        fun mappingPacket(rate: Int): ByteArray {
            val d = ByteArray(51)
            byteArrayOf(0x7F, 'F'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 1, 0, 0, 1).copyInto(d)
            "fLaC".toByteArray().copyInto(d, 9)
            byteArrayOf(0, 0, 0, 34).copyInto(d, 13)
            // STREAMINFO: block sizes 4096 and 4096, frame sizes unknown, then rate, channels - 1, bits - 1, total 0; MD5 0
            val si = 17
            d[si] = 0x10; d[si + 1] = 0; d[si + 2] = 0x10; d[si + 3] = 0
            val packed = (rate.toLong() shl 44) or (0L shl 41) or ((FlacFrame.BPS - 1).toLong() shl 36)
            for (i in 0 until 8) d[si + 10 + i] = (packed ushr (56 - 8 * i)).toByte()
            return d
        }

        /** 19.4.1: the last metadata block, VORBIS_COMMENT, with [vendor] and no comments. */
        @JvmStatic
        fun commentPacket(vendor: String): ByteArray {
            val v = vendor.toByteArray(Charsets.UTF_8)
            val len = 8 + v.size
            val d = ByteArray(4 + len)
            d[0] = 0x84.toByte()
            d[1] = (len ushr 16).toByte(); d[2] = (len ushr 8).toByte(); d[3] = len.toByte()
            for (i in 0 until 4) d[4 + i] = (v.size ushr (8 * i)).toByte()
            v.copyInto(d, 8)
            return d
        }
    }
}
