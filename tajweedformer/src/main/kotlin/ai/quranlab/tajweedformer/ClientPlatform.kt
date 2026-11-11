package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.cloud.CloudEnvironment
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuRunner
import ai.quranlab.tajweedformer.sharing.Sharing
import java.io.File

/** The microphone permission as `diagnose()` reports it. */
internal enum class MicPermission(val key: String) { GRANTED("granted"), DENIED("denied"), NOT_ASKED("notAsked"), NONE("none") }

/** What an open microphone tells the take that listens to it. */
internal interface AudioInputListener {
    /** Another app took the audio (a call, the system silenced the recording): no audio comes until [resumed]. */
    fun interrupted()

    /** The interruption ended. */
    fun resumed()

    /** An input device was added or removed: the capture should be opened again. */
    fun routeChanged()
}

/** An open capture: mono float buffers at [sampleRate] until closed. */
internal interface AudioCapture : AutoCloseable {
    val sampleRate: Int

    /** The next buffer (blocking, at most a few tens of ms of audio); null once the capture is closed. Throws
     *  [RecognizerException.MicrophoneUnavailable] when the device stops giving audio. */
    fun read(): FloatArray?
}

/** The platform's microphone (Android: AudioRecord behind the public `Microphone`; tests: a fake). */
internal interface AudioInput {
    fun permission(): MicPermission

    /** Asks the learner when the platform allows asking; returns whether the permission is granted now. */
    suspend fun requestPermission(): Boolean

    /** Opens the microphone, at [sampleRate] when given (a reopened capture keeps the take's rate), else at the device's
     *  own rate. Throws [RecognizerException.MicrophoneUnavailable]. */
    fun open(listener: AudioInputListener, sampleRate: Int? = null): AudioCapture
}

/**
 * What a [TajweedClient] needs from the platform it runs on (Android: built from the Context; the desktop JVM: from
 * the storage folder; tests: fakes). Everything the two platforms do differently is here, so the client, the model
 * and the session are the same code on both.
 */
internal class ClientPlatform(
    /** The cloud side's environment: storage, app identity, device, keys, scheduler, HTTP, clock. */
    val cloud: CloudEnvironment,
    val apiUrl: String = QuranLabApi.DEFAULT_API,
    /** The Ed25519 keys the signed config and the model manifests may be signed with. */
    val trustedKeys: List<ByteArray> = listOf(ManifestVerifier.QURAN_LAB_PUBLIC_KEY),
    /** Where model files are served when the signed config names no base. */
    val modelsUrl: String = ModelStore.MODELS_URL,
    /** The transport of model downloads. */
    val modelHttp: HttpTransport = UrlConnectionTransport(),
    /** The device's SoC id ([SocIds]), null when unknown. */
    val soc: String? = null,
    /** Whether the network is unmetered now (accelerator files and `prefetch(unmeteredOnly = true)` wait for it). */
    val network: NetworkPolicy = NetworkPolicy.UNKNOWN,
    /** Loads an NPU model; null when no NPU runtime is in the app. */
    val npuRunner: ((File, NpuContract) -> NpuRunner)? = null,
    /** Where the accelerator is prepared; null: the CPU only. */
    val accelerator: AcceleratorEnvironment? = null,
    /** The microphone; null on a platform without one (`listen()` then fails with `microphone_unavailable`). */
    val microphone: AudioInput? = null,
    /** The rate the engine is warmed for at `client.model()`: the microphone's (48000 on most phones), so the first
     *  `listen` take starts at once; 16000 where there is no microphone. */
    val warmRate: Int = 16_000,
    val engine: TajweedEngineFactory = TajweedEngineFactory.EXACT,
    /** Called once with the client: registers what calls `client.background()` and `client.foreground()` by itself
     *  (Android: the activity lifecycle). Returns what undoes it at `close()`. */
    val lifecycle: ((TajweedClient) -> (() -> Unit))? = null,
    /** Shows the SDK's own sharing sheets (Android: dialogs over the resumed activity); null: none can be shown. */
    val sheets: ((Sharing, Sharing.SheetRequest) -> Unit)? = null,
    /** Free bytes where the models are kept; null when unknown. */
    val freeSpace: ((File) -> Long?)? = { f -> try { generateSequence(f) { it.parentFile }.first { it.exists() }.usableSpace } catch (_: Exception) { null } },
    /** More observers of the cloud events (Android: the thermal listener's target). */
    val extraObserver: ((TajweedObserver) -> (() -> Unit))? = null,
)
