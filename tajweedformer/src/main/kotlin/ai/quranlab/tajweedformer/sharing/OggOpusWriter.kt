package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.TajweedFormer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random

/**
 * How a shared session is cut into segments: 10, 20, 40, then 60 s each (the last entry repeats),
 * cut at Ogg page boundaries. Short first segments reach R2 within seconds; a long session makes
 * one upload a minute.
 */
object SegmentPlan {
    @JvmField val SECONDS = listOf(10, 20, 40, 60)

    /** Seconds in segment [n] (1-based). */
    @JvmStatic fun seconds(n: Int, plan: List<Int> = SECONDS): Int = plan[minOf(n, plan.size) - 1]
}

/**
 * A closed segment of an [OggOpusWriter]: its number, file, last granule position, whether it ends the
 * stream, and the bit rate (bit/s) every packet of it was encoded at.
 */
data class OggSegment(val n: Int, val file: File, val granule: Long, val last: Boolean, val bitrate: Int = 0)

/**
 * Writes one session's audio as Ogg Opus while the session runs, byte for byte as the Swift SDK's
 * `OggOpusWriter`: mono, coded at 48 kHz with [OpusSettings.SHARED], muxed into Ogg per RFC 7845.
 * Input at another rate is resampled to 48 kHz first ([ShareResampler]) and OpusHead records the
 * true input rate. Non-finite samples are written as silence.
 *
 * Pages carry about a second of packets (fewer when 255 lacing values would not hold them) and go
 * to disk as soon as they are full, so a killed process loses at most about a second. The stream is
 * cut into consecutive segment files at page boundaries per [plan] (seconds per segment, counted in
 * packets; empty: one file): segment 1 starts with OpusHead and OpusTags, the last page of the last
 * segment has the end-of-stream flag and the encoder's final granule position, and granule
 * positions and page sequence numbers run on, so the segments concatenated are exactly one file.
 *
 * Bitrate ([bitrateFor], SDK 2.0.1): when a segment opens, the writer asks for its bit rate and sets it on
 * the encoder (`OPUS_SET_BITRATE`) before the segment's first packet is encoded, so every packet of a
 * segment has the same target. Segment 1's is asked for when the writer is created; segment n's after
 * segment n - 1 closed (packet by packet around the boundary, so the closing is seen first). Opus takes a
 * new bitrate on any packet: no new headers, one stream.
 */
class OggOpusWriter @JvmOverloads constructor(
    private val segmentFile: (Int) -> File,
    private val plan: List<Int>,
    val inputRate: Int,
    val settings: OpusSettings = OpusSettings.SHARED,
    private val serial: Int = Random.nextInt(),
    encoder: OpusEncoding? = null,
    /** OpusTags' vendor string; null: [vendor] (the cross-SDK vector names its own). */
    vendor: String? = null,
    /** The bit rate of segment n (1-based), asked when it opens; null: [settings]' bitrate throughout. */
    private val bitrateFor: ((Int) -> Int)? = null,
    private val onSegment: (OggSegment) -> Unit = {},
) : AutoCloseable {
    private val encoder: OpusEncoding
    private val resampler: ShareResampler?
    private var out: FileOutputStream? = null
    private var segment = 0
    private var segmentPackets = 0
    private var sequence = 0
    private var ended = false
    private val packets = ArrayDeque<ByteArray>()
    private var paged = 0L
    private var clean = FloatArray(0)
    /** Packets handed over by the encoder so far. */
    private var encoded = 0L
    /** The segment that opens next and the index of its first packet. */
    private var nextSegment = 2
    private var nextStart = Long.MAX_VALUE
    private val bitrates = ArrayList<Int>()
    private val sink = object : PacketSink {
        override fun beforePacket() {
            if (encoded == nextStart) openNext()
        }

        override fun packet(p: ByteArray) {
            packets.addLast(p)
            encoded++
        }
    }

    /** The bit rate of each segment opened so far, in order (bit/s). */
    val segmentBitrates: List<Int> get() = bitrates.toList()

    /** Input frames given to the writer, at the input rate. */
    var inputFrames = 0L
        private set

    /** Non-finite input samples written as silence. */
    var nonFiniteSamples = 0L
        private set

    var lastGranule = 0L
        private set

    val preSkip: Int get() = encoder.preSkip
    val packetFrames: Int get() = settings.packetFrames
    val pagePackets: Int get() = settings.pagePackets

    /** Audio seconds written so far (at the input rate). */
    val duration: Double get() = inputFrames.toDouble() / inputRate

    /** One file. */
    constructor(file: File, inputRate: Int, settings: OpusSettings = OpusSettings.SHARED, serial: Int = Random.nextInt(),
                encoder: OpusEncoding? = null) : this({ file }, emptyList(), inputRate, settings, serial, encoder)

    init {
        if (inputRate < 8_000 || inputRate > 384_000) throw SharingException("unsupported_audio", "$inputRate Hz")
        resampler = if (inputRate == RATE) null else ShareResampler(inputRate)
        this.encoder = encoder ?: LibOpusEncoder(OpusSettings.CODED_RATE, 1, settings)
        bitrates.add(rateOf(1))
        segmentLimit(1)?.let { nextStart = it.toLong() }
        page(listOf(opusHead(this.encoder.preSkip, inputRate)), 0, 0x02, audio = false)
        page(listOf(opusTags(vendor ?: Companion.vendor())), 0, 0, audio = false)
    }

    /** Segment [n]'s bit rate, set on the encoder now (the settings' when there is no [bitrateFor] or the encoder has none). */
    private fun rateOf(n: Int): Int {
        val want = bitrateFor?.invoke(n) ?: return settings.bitrate
        return if (encoder.setBitrate(want)) want else settings.bitrate
    }

    /** The next packet starts segment [nextSegment]: the current one is closed first, then the new rate is set. */
    private fun openNext() {
        writePages(final = false)
        bitrates.add(rateOf(nextSegment))
        nextStart += segmentLimit(nextSegment)!!
        nextSegment++
    }

    /** Test seam: encoded packets in place of audio (the cross-SDK muxer vector). */
    internal fun appendPackets(p: List<ByteArray>) {
        if (ended) return
        for (x in p) { sink.beforePacket(); sink.packet(x) }
        writePages(final = false)
    }

    /** Encodes mono samples at the input rate. */
    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0 || ended) return
        inputFrames += length
        if (clean.size < length) clean = FloatArray(length)
        var bad = 0
        for (i in 0 until length) {
            val v = samples[offset + i]
            if (v.isFinite()) clean[i] = v else { clean[i] = 0f; bad++ }
        }
        nonFiniteSamples += bad
        val r = resampler
        if (r != null) {
            r.process(clean, 0, length) { x, o, n -> encoder.encodeTo(x, o, n, sink) }
        } else {
            encoder.encodeTo(clean, 0, length, sink)
        }
        writePages(final = false)
    }

    /** Flushes the resampler and the encoder, writes the last pages and closes the last segment. Returns the duration in ms. */
    fun finish(): Int {
        if (!ended) {
            ended = true
            resampler?.flush { x, o, n -> encoder.encodeTo(x, o, n, sink) }
            encoder.flushTo(sink)
            writePages(final = true)
            closeSegment(last = true)
            encoder.close()
        }
        return Math.round(duration * 1000).toInt()
    }

    /** Stops without finishing (the caller removes the files). */
    fun abandon() {
        ended = true
        try { out?.close() } catch (_: IOException) {}
        out = null
        encoder.close()
    }

    override fun close() { if (!ended) abandon() }

    private fun segmentLimit(n: Int): Int? =
        if (plan.isEmpty()) null else Math.round(SegmentPlan.seconds(n, plan) * 1000.0 / settings.frameMs).toInt()

    private fun writePages(final: Boolean) {
        while (packets.isNotEmpty()) {
            val next = if (out == null) segment + 1 else segment
            val used = if (out == null) 0 else segmentPackets
            val room = segmentLimit(next)?.let { it - used } ?: Int.MAX_VALUE
            val want = minOf(pagePackets, room)
            var take = 0
            var lacing = 0
            while (take < minOf(want, packets.size)) {
                val l = packets[take].size / 255 + 1
                if (lacing + l > 255) break
                lacing += l
                take++
            }
            val full = take == want || take < packets.size
            if (!(full || final)) return
            val last = final && take == packets.size
            val chunk = ArrayList<ByteArray>(take)
            repeat(take) { chunk.add(packets.removeFirst()) }
            var granule = (paged + take) * packetFrames
            if (last) {
                // RFC 7845: the last page's granule position trims the encoder's end padding
                granule = maxOf(minOf(encoder.finalGranulePosition, granule), paged * packetFrames)
            }
            page(chunk, granule, if (last) 0x04 else 0)
        }
    }

    private fun closeSegment(last: Boolean) {
        val o = out ?: return
        try { o.close() } catch (_: IOException) {}
        out = null
        onSegment(OggSegment(segment, segmentFile(segment), lastGranule, last, bitrates.getOrElse(segment - 1) { settings.bitrate }))
    }

    private fun page(packets: List<ByteArray>, granule: Long, flags: Int, audio: Boolean = true) {
        val d = pageBytes(packets, granule, flags, serial, sequence)
        if (out == null) {
            segment++
            segmentPackets = 0
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
        lastGranule = granule
        if (audio) {
            paged += packets.size
            segmentPackets += packets.size
            val limit = segmentLimit(segment)
            if (flags and 0x04 == 0 && limit != null && segmentPackets >= limit) closeSegment(last = false)
        }
    }

    companion object {
        const val RATE = OpusSettings.CODED_RATE

        /** One Ogg page (RFC 3533) with its CRC. */
        @JvmStatic
        fun pageBytes(packets: List<ByteArray>, granule: Long, flags: Int, serial: Int, sequence: Int): ByteArray {
            val lacing = ArrayList<Int>()
            for (p in packets) {
                var n = p.size
                while (n >= 255) { lacing.add(255); n -= 255 }
                lacing.add(n)
            }
            check(lacing.size <= 255) { "an Ogg page holds at most 255 segments" }
            val size = 27 + lacing.size + packets.sumOf { it.size }
            val d = ByteArray(size)
            "OggS".toByteArray().copyInto(d)
            d[4] = 0
            d[5] = flags.toByte()
            le(d, 6, granule, 8)
            le(d, 14, serial.toLong(), 4)
            le(d, 18, sequence.toLong(), 4)
            d[26] = lacing.size.toByte()
            var o = 27
            for (l in lacing) d[o++] = l.toByte()
            for (p in packets) { p.copyInto(d, o); o += p.size }
            le(d, 22, crc(d).toLong() and 0xffffffffL, 4)
            return d
        }

        /** RFC 7845 identification header; [inputRate] is the capture rate (informational). */
        @JvmStatic
        fun opusHead(preSkip: Int, inputRate: Int): ByteArray {
            val d = ByteArray(19)
            "OpusHead".toByteArray().copyInto(d)
            d[8] = 1
            d[9] = 1
            le(d, 10, preSkip.toLong(), 2)
            le(d, 12, inputRate.toLong(), 4)
            le(d, 16, 0, 2)
            d[18] = 0
            return d
        }

        /** The vendor string, "QuranLab SDK 2.0.3 libopus 1.6.1" as the Swift SDK writes it. */
        @JvmStatic
        fun vendor(): String = "QuranLab SDK ${TajweedFormer.VERSION} ${OpusVersion.string}"

        @JvmStatic
        fun opusTags(vendor: String = vendor()): ByteArray {
            val v = vendor.toByteArray(Charsets.UTF_8)
            val d = ByteArray(8 + 4 + v.size + 4)
            "OpusTags".toByteArray().copyInto(d)
            le(d, 8, v.size.toLong(), 4)
            v.copyInto(d, 12)
            le(d, 12 + v.size, 0, 4)
            return d
        }

        private fun le(d: ByteArray, at: Int, v: Long, n: Int) {
            for (i in 0 until n) d[at + i] = (v ushr (8 * i)).toByte()
        }

        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        /** The Ogg CRC-32: polynomial 0x04c11db7, no reflection, initial value 0 (CRC field taken as zero). */
        @JvmStatic
        fun crc(d: ByteArray, from: Int = 0, to: Int = d.size): Int {
            var c = 0
            for (i in from until to) {
                val b = if (i - from in 22..25) 0 else d[i].toInt() and 0xff
                c = (c shl 8) xor TABLE[((c ushr 24) and 0xff) xor b]
            }
            return c
        }
    }
}
