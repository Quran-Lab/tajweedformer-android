package ai.quranlab.tajweedformer.sharing

/**
 * The Opus settings sessions are shared with, the same in every TajweedFormer SDK (the Swift SDK's
 * `OpusSettings`): libopus 1.6.1, `OPUS_APPLICATION_AUDIO`, 192 kbit/s unconstrained VBR,
 * complexity 10, fullband, automatic signal type, 20 ms frames, one-second Ogg pages.
 */
data class OpusSettings(
    val application: Application = Application.AUDIO,
    val bitrate: Int = 192_000,
    /** 0 (fastest) to 10 (best). */
    val complexity: Int = 10,
    val vbr: Boolean = true,
    val constrainedVbr: Boolean = false,
    val bandwidth: Bandwidth = Bandwidth.FULLBAND,
    val signal: Signal = Signal.AUTO,
    /** 2.5, 5, 10, 20, 40 or 60. */
    val frameMs: Double = 20.0,
) {
    enum class Application(val value: Int) { AUDIO(2049), VOIP(2048), RESTRICTED_LOWDELAY(2051) }
    enum class Bandwidth(val value: Int) { NARROWBAND(1101), MEDIUMBAND(1102), WIDEBAND(1103), SUPERWIDEBAND(1104), FULLBAND(1105) }
    enum class Signal(val value: Int) { AUTO(-1000), VOICE(3001), MUSIC(3002) }

    /** Samples per packet at 48 kHz. */
    val packetFrames: Int get() = (48 * frameMs).toInt()

    /** Packets on a full Ogg page: about one second (50 at 20 ms). */
    val pagePackets: Int get() = maxOf(1, Math.round(1000 / frameMs).toInt())

    companion object {
        /** The rate Opus codes at; the sharing path always encodes at it. */
        const val CODED_RATE = 48_000
        @JvmField val SHARED = OpusSettings()
    }
}

/** An Opus encoder: PCM in, Opus packets out. The Ogg writer depends only on this. */
interface OpusEncoding : AutoCloseable {
    /** The encoder's lookahead in 48 kHz samples (OpusHead pre-skip). */
    val preSkip: Int

    /** Encodes samples; returns the packets completed so far (possibly none). */
    fun encode(pcm: FloatArray, offset: Int = 0, length: Int = pcm.size): List<ByteArray>

    /** Encodes what is buffered and the lookahead, padded with silence to whole packets. */
    fun flush(): List<ByteArray>

    /** The pre-skip plus the input in 48 kHz samples: the last Ogg page's granule position. */
    val finalGranulePosition: Long

    override fun close() {}
}

/**
 * Opus from libopus 1.6.1 through JNI, set up per [OpusSettings]. Non-finite samples are coded as
 * silence. Not thread safe.
 */
class LibOpusEncoder(
    val sampleRate: Int = OpusSettings.CODED_RATE,
    val channels: Int = 1,
    val settings: OpusSettings = OpusSettings.SHARED,
) : OpusEncoding {
    val frameSize: Int
    override val preSkip: Int
    private var handle: Long
    private val frame: FloatArray
    private var filled = 0
    private val packet = ByteArray(4_000)
    private var finished = false

    /** Input samples per channel consumed so far (at the input rate). */
    var samplesIn = 0L
        private set

    /** Non-finite input samples replaced with 0 so far. */
    var nonFiniteSamples = 0L
        private set

    override val finalGranulePosition: Long get() = preSkip.toLong() + samplesIn * 48_000 / sampleRate

    init {
        if (sampleRate !in RATES) throw SharingException("unsupported_audio", "Opus encodes 8, 12, 16, 24 or 48 kHz, not $sampleRate Hz")
        if (channels != 1 && channels != 2) throw SharingException("unsupported_audio", "Opus: 1 or 2 channels, not $channels")
        val size = sampleRate * settings.frameMs / 1000
        if (settings.frameMs !in listOf(2.5, 5.0, 10.0, 20.0, 40.0, 60.0) || size != Math.rint(size)) {
            throw SharingException("unsupported_audio", "Opus frames are 2.5, 5, 10, 20, 40 or 60 ms, not ${settings.frameMs}")
        }
        OpusNative.require()
        val out = LongArray(1)
        val err = OpusNative.encoderCreate(sampleRate, channels, settings.application.value, out)
        if (err != OpusNative.OPUS_OK || out[0] == 0L) throw SharingException("opus", "the Opus encoder is not available ($err)")
        handle = out[0]
        val requests = listOf(
            Triple("bitrate", OpusNative.SET_BITRATE, settings.bitrate),
            Triple("complexity", OpusNative.SET_COMPLEXITY, settings.complexity),
            Triple("vbr", OpusNative.SET_VBR, if (settings.vbr) 1 else 0),
            Triple("vbr constraint", OpusNative.SET_VBR_CONSTRAINT, if (settings.constrainedVbr) 1 else 0),
            Triple("bandwidth", OpusNative.SET_BANDWIDTH, settings.bandwidth.value),
            Triple("signal", OpusNative.SET_SIGNAL, settings.signal.value),
            Triple("lsb depth", OpusNative.SET_LSB_DEPTH, 24),
            Triple("inband fec", OpusNative.SET_INBAND_FEC, 0),
            Triple("dtx", OpusNative.SET_DTX, 0),
        )
        for ((name, request, value) in requests) {
            if (OpusNative.encoderSet(handle, request, value) != OpusNative.OPUS_OK) {
                close()
                throw SharingException("opus", "the Opus encoder refused its $name ($value)")
            }
        }
        val lookahead = get(OpusNative.GET_LOOKAHEAD) ?: run {
            close()
            throw SharingException("opus", "the Opus encoder has no lookahead")
        }
        frameSize = size.toInt()
        frame = FloatArray(frameSize * channels)
        // OpusHead pre-skip is in 48 kHz samples whatever the input rate (312 at 48 kHz)
        preSkip = lookahead * 48_000 / sampleRate
    }

    /** A GET ctl read back from the encoder, for the tests. */
    fun get(request: Int): Int? {
        val out = IntArray(1)
        return if (OpusNative.encoderGet(handle, request, out) == OpusNative.OPUS_OK) out[0] else null
    }

    override fun encode(pcm: FloatArray, offset: Int, length: Int): List<ByteArray> {
        if (finished) throw SharingException("opus", "the Opus encoder was flushed")
        val out = ArrayList<ByteArray>()
        val perFrame = frame.size
        var i = 0
        while (i < length) {
            val n = minOf(perFrame - filled, length - i)
            var bad = 0
            for (k in 0 until n) {
                val v = pcm[offset + i + k]
                if (v.isFinite()) frame[filled + k] = v else { frame[filled + k] = 0f; bad++ }
            }
            nonFiniteSamples += bad
            filled += n
            i += n
            if (filled == perFrame) out.add(encodeFrame())
        }
        samplesIn += (length / channels).toLong()
        return out
    }

    override fun flush(): List<ByteArray> {
        if (finished) throw SharingException("opus", "the Opus encoder was flushed")
        finished = true
        val out = ArrayList<ByteArray>()
        // the samples still owed: what is buffered plus the lookahead held inside the codec
        var owed = filled / channels + preSkip * sampleRate / 48_000
        while (owed > 0) {
            for (k in filled until frame.size) frame[k] = 0f
            filled = frame.size
            out.add(encodeFrame())
            owed -= frameSize
        }
        return out
    }

    private fun encodeFrame(): ByteArray {
        val n = OpusNative.encodeFloat(handle, frame, 0, frameSize, packet)
        filled = 0
        if (n <= 0) throw SharingException("opus", "the Opus encoder failed ($n)")
        return packet.copyOf(n)
    }

    override fun close() {
        if (handle != 0L) {
            OpusNative.encoderDestroy(handle)
            handle = 0
        }
    }

    companion object {
        val RATES = setOf(8000, 12000, 16000, 24000, 48000)
    }
}

/** libopus's decoder, mono at 48 kHz: the checks in tests and the codec probe. */
internal class LibOpusDecoder : AutoCloseable {
    private var handle: Long
    private val pcm = FloatArray(5_760)

    init {
        OpusNative.require()
        val out = LongArray(1)
        val err = OpusNative.decoderCreate(48_000, 1, out)
        if (err != OpusNative.OPUS_OK || out[0] == 0L) throw SharingException("opus", "the Opus decoder is not available ($err)")
        handle = out[0]
    }

    fun decode(packet: ByteArray): FloatArray {
        val n = OpusNative.decodeFloat(handle, packet, packet.size, pcm, pcm.size)
        if (n <= 0) throw SharingException("opus", "the Opus decoder failed ($n)")
        return pcm.copyOf(n)
    }

    override fun close() {
        if (handle != 0L) {
            OpusNative.decoderDestroy(handle)
            handle = 0
        }
    }
}
