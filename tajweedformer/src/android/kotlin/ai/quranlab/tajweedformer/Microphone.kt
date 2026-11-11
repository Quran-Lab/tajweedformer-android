package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.android.AndroidPlatform
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's microphone as plain samples, for apps that want the audio only. `model.listen()` is the whole path
 * from the microphone to the model's output and needs none of this.
 *
 * ```
 * Microphone.samples().collect { buffer -> session.push(buffer) }   // a Session made with sampleRate = Microphone.sampleRate
 * ```
 *
 * Recording runs while the flow is collected and stops when collection stops. The app must hold RECORD_AUDIO (the
 * SDK's manifest declares it; `model.listen()` asks the learner, this does not): without it the error is
 * `microphone_denied`. A [TajweedClient] must exist: it gives the SDK the application context.
 */
public object Microphone {
    /** The rate [samples] are captured at: the device's own (48000 on most phones). */
    public val sampleRate: Int get() = AndroidMicrophone(context()).nativeRate()

    /** Mono float samples in -1..1 at [sampleRate], about 20 ms a buffer. */
    public fun samples(): Flow<FloatArray> = flow {
        val mic = AndroidMicrophone(context())
        if (mic.permission() != MicPermission.GRANTED) throw RecognizerException.MicrophoneDenied()
        val capture = mic.open(object : AudioInputListener {
            override fun interrupted() {}
            override fun resumed() {}
            override fun routeChanged() {}
        })
        try {
            while (currentCoroutineContext().isActive) emit(capture.read() ?: break)
        } finally {
            capture.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun context(): Context = AndroidPlatform.appContext
        ?: throw RecognizerException.MicrophoneUnavailable("make a TajweedClient first: the microphone needs the application context")
}

/**
 * The Android microphone for `model.listen()`: the RECORD_AUDIO permission (asked through a transparent activity of
 * the SDK, so no Activity is passed in), an AudioRecord at the device's own rate, and the three things a take must
 * hear about: an interruption (audio focus lost, or the system silenced this recording because another app
 * records), its end, and a change of input devices (headphones in or out).
 */
internal class AndroidMicrophone(context: Context) : AudioInput {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The device's output rate, which is the rate its input path runs at without resampling: 48000 on most phones. */
    fun nativeRate(): Int {
        val r = try { audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() } catch (_: Exception) { null }
        return r?.takeIf { it in 8_000..48_000 } ?: 48_000
    }

    override fun permission(): MicPermission = when {
        !app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> MicPermission.NONE
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> MicPermission.GRANTED
        prefs().getBoolean(ASKED, false) -> MicPermission.DENIED
        else -> MicPermission.NOT_ASKED
    }

    private fun prefs() = app.getSharedPreferences("ai.quranlab.tajweedformer.microphone", Context.MODE_PRIVATE)

    /**
     * Asks for RECORD_AUDIO with the system's dialog, shown over the app by [MicrophonePermissionActivity]. Needs the
     * app in the foreground (Android does not let a background app start an activity): otherwise, and when the
     * learner refuses, the answer is false. Android itself stops showing the dialog after two refusals.
     */
    override suspend fun requestPermission(): Boolean {
        if (permission() == MicPermission.GRANTED) return true
        if (permission() == MicPermission.NONE || AndroidPlatform.resumedActivity == null) return false
        val answer = CompletableDeferred<Boolean>()
        MicrophonePermissionActivity.pending = answer
        return try {
            app.startActivity(Intent(app, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            prefs().edit().putBoolean(ASKED, true).apply()
            // the dialog waits for the learner; a dialog that never comes back (the activity was killed) is a refusal
            withTimeoutOrNull(120_000) { answer.await() } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            MicrophonePermissionActivity.pending = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun open(listener: AudioInputListener, sampleRate: Int?): AudioCapture {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw RecognizerException.MicrophoneDenied()
        val rate = sampleRate ?: nativeRate()
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val min = AudioRecord.getMinBufferSize(rate, channel, encoding)
        if (min <= 0) throw RecognizerException.MicrophoneUnavailable("no recording at $rate Hz")
        val frame = rate / 50
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, channel, encoding, maxOf(min, frame * 4 * 4))
        } catch (e: Exception) {
            throw RecognizerException.MicrophoneUnavailable(e.message ?: e.javaClass.simpleName)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw RecognizerException.MicrophoneUnavailable("the recorder did not start (no input device, or another app holds it)")
        }
        return Capture(record, rate, frame, listener)
    }

    private inner class Capture(private val record: AudioRecord, override val sampleRate: Int, private val frame: Int,
                                private val listener: AudioInputListener) : AudioCapture {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var closed = false
        @Volatile private var silenced = false
        @Volatile private var focusLost = false
        private var firstDevices = true

        /** Another app took the audio focus (a call, a player): an interruption; it is back: resumed. */
        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { focusLost = true; signal() }
                AudioManager.AUDIOFOCUS_GAIN -> { focusLost = false; signal() }
            }
        }
        private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener, main).build()

        /** The system silenced this recording (another app records with priority, API 29+), or gave it back. */
        private val recording = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                if (Build.VERSION.SDK_INT < 29) return
                val mine = configs?.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                silenced = mine.isClientSilenced
                signal()
            }
        }

        /** An input device was added or removed (headphones, a Bluetooth or USB microphone). */
        private val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                // the first call lists the devices that were there when the callback was registered
                if (firstDevices) { firstDevices = false; return }
                if (added?.any { it.isSource } == true) listener.routeChanged()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                if (removed?.any { it.isSource } == true) listener.routeChanged()
            }
        }

        private var interrupted = false

        @Synchronized
        private fun signal() {
            if (closed) return
            val now = silenced || focusLost
            if (now == interrupted) return
            interrupted = now
            if (now) listener.interrupted() else listener.resumed()
        }

        init {
            try {
                record.startRecording()
            } catch (e: Exception) {
                record.release()
                throw RecognizerException.MicrophoneUnavailable(e.message ?: "the recorder did not start")
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                throw RecognizerException.MicrophoneUnavailable("another app holds the microphone")
            }
            try { audio.requestAudioFocus(focus) } catch (_: Exception) {}
            try { audio.registerAudioRecordingCallback(recording, main) } catch (_: Exception) {}
            try { audio.registerAudioDeviceCallback(devices, main) } catch (_: Exception) {}
        }

        override fun read(): FloatArray? {
            if (closed) return null
            val buf = FloatArray(frame)
            val n = record.read(buf, 0, frame, AudioRecord.READ_BLOCKING)
            if (closed) return null
            if (n < 0) throw RecognizerException.MicrophoneUnavailable("the recording stopped ($n)")
            return if (n == frame) buf else buf.copyOf(n)
        }

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            try { audio.unregisterAudioDeviceCallback(devices) } catch (_: Exception) {}
            try { audio.unregisterAudioRecordingCallback(recording) } catch (_: Exception) {}
            try { audio.abandonAudioFocusRequest(focus) } catch (_: Exception) {}
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
        }
    }

    private companion object {
        const val ASKED = "asked"
    }
}

/**
 * A transparent activity of the SDK that only shows the system's RECORD_AUDIO dialog and reports the answer, so
 * `model.listen()` can ask for the permission with nothing but the application context.
 */
internal class MicrophonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) {
            pending?.complete(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // destroyed without an answer (not a rotation): the dialog is gone
        if (!isChangingConfigurations && isFinishing) pending?.complete(false)
    }

    companion object {
        private const val REQUEST = 7341

        /** The answer the open `listen()` call waits for. */
        @Volatile var pending: CompletableDeferred<Boolean>? = null
    }
}
