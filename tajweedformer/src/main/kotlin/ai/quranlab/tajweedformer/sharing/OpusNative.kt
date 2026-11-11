package ai.quranlab.tajweedformer.sharing

import java.io.File

/** A failure of recitation sharing's local side: the codec, the resampler or the files. */
internal class SharingException(val code: String, message: String) : Exception(message)

/**
 * libtajweedformer_opus: libopus 1.6.1 (float, no DNN) through JNI. Loaded on first use from the
 * `tajweedformer.opus.library` system property (an absolute path, for desktop tests) or by name.
 */
internal object OpusNative {
    @Volatile private var loaded: Throwable? = null
    @Volatile private var ready = false

    /** Loads the library; false (with [loadError]) when it is not there. */
    @Synchronized
    fun load(): Boolean {
        if (ready) return true
        if (loaded != null) return false
        return try {
            val path = System.getProperty("tajweedformer.opus.library")?.takeIf { it.isNotEmpty() }
            if (path != null) System.load(File(path).absolutePath) else System.loadLibrary("tajweedformer_opus")
            ready = true
            true
        } catch (t: Throwable) {
            loaded = t
            false
        }
    }

    val loadError: Throwable? get() = loaded

    fun require() {
        if (!load()) throw SharingException("opus_unavailable", "libtajweedformer_opus is not available: ${loaded?.message}")
    }

    @JvmStatic external fun version(): String
    @JvmStatic external fun encoderCreate(rate: Int, channels: Int, application: Int, out: LongArray): Int
    @JvmStatic external fun encoderSet(h: Long, request: Int, value: Int): Int
    @JvmStatic external fun encoderGet(h: Long, request: Int, out: IntArray): Int
    @JvmStatic external fun encodeFloat(h: Long, pcm: FloatArray, offset: Int, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun encoderDestroy(h: Long)
    @JvmStatic external fun decoderCreate(rate: Int, channels: Int, out: LongArray): Int
    @JvmStatic external fun decodeFloat(h: Long, data: ByteArray, length: Int, pcm: FloatArray, maxFrames: Int): Int
    @JvmStatic external fun decoderDestroy(h: Long)

    // opus_defines.h
    const val OPUS_OK = 0
    const val SET_BITRATE = 4002
    const val GET_BITRATE = 4003
    const val SET_VBR = 4006
    const val GET_VBR = 4007
    const val SET_BANDWIDTH = 4008
    const val GET_BANDWIDTH = 4009
    const val SET_COMPLEXITY = 4010
    const val GET_COMPLEXITY = 4011
    const val SET_INBAND_FEC = 4012
    const val GET_INBAND_FEC = 4013
    const val SET_DTX = 4016
    const val GET_DTX = 4017
    const val SET_VBR_CONSTRAINT = 4020
    const val GET_VBR_CONSTRAINT = 4021
    const val SET_SIGNAL = 4024
    const val GET_SIGNAL = 4025
    const val GET_LOOKAHEAD = 4027
    const val GET_SAMPLE_RATE = 4029
    const val GET_APPLICATION = 4001
    const val SET_LSB_DEPTH = 4036
    const val GET_LSB_DEPTH = 4037
}

/** "libopus 1.6.1". */
internal object OpusVersion {
    val string: String get() { OpusNative.require(); return OpusNative.version() }
}
