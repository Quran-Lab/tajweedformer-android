package ai.quranlab.tajweedformer.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import ai.quranlab.tajweedformer.RecognizerException

/**
 * The microphone as a flow of mono float buffers, for [ai.quranlab.tajweedformer.TajweedSession.append]:
 *
 * ```
 * Microphone.samples(48_000).collect { session.append(it) }   // recognizer made with sampleRate = 48_000
 * ```
 *
 * Recording runs while the flow is collected and stops when collection stops. Record at the
 * device's native rate (48 kHz on most phones) and let the SDK resample: its resampler is the same
 * bits on every device, where Android's own differs between devices and OS releases. The app must
 * hold RECORD_AUDIO.
 */
object Microphone {
    @SuppressLint("MissingPermission")
    fun samples(sampleRate: Int = 48_000, bufferMillis: Int = 20): Flow<FloatArray> = flow {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val min = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (min <= 0) throw RecognizerException.MicrophoneUnavailable("no recording at $sampleRate Hz")
        val frame = sampleRate * bufferMillis / 1000
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channel, encoding, maxOf(min, frame * 4 * 4))
        } catch (e: SecurityException) {
            throw RecognizerException.MicrophoneUnavailable("RECORD_AUDIO is not granted")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw RecognizerException.MicrophoneUnavailable("the recorder did not initialise (is RECORD_AUDIO granted?)")
        }
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val buf = FloatArray(frame)
                val n = record.read(buf, 0, frame, AudioRecord.READ_BLOCKING)
                if (n < 0) throw RecognizerException.MicrophoneUnavailable("read failed ($n)")
                if (n > 0) emit(if (n == frame) buf else buf.copyOf(n))
            }
        } finally {
            record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
