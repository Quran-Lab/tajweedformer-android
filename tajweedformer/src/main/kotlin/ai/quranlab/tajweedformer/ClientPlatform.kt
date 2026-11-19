package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.CloudEnvironment
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.InstallIdentity
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import ai.quranlab.tajweedformer.core.StateVault
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
    /** The transport of model downloads. */
    val modelHttp: HttpTransport = UrlConnectionTransport(),
    /** How a large model file is fetched (SDK 3.1.3: in 8 MiB ranges, four at a time over HTTP/1.1). */
    val modelRanges: RangePlan = RangePlan.DEFAULT,
    /** How a model request that the network cut is made again inside the call (SDK 3.1.3: five resumes per file, after
     *  1, 2, 4, 8 and 16 s). */
    val modelRetry: RetryPlan = RetryPlan.DEFAULT,
    /** Keeps a model download going when the app leaves the foreground or its process ends (Android: WorkManager);
     *  null: none (the desktop JVM, where the process is the app). */
    val downloads: BackgroundDownloads? = null,
    /** The device's SoC id ([SocIds]), null when unknown. */
    val soc: String? = null,
    /**
     * The vendor's LiteRT dispatch library for an NPU program of a SoC, and the folder the Core's `qlc_accel_prepare`
     * reads it from (R2; [ai.quranlab.tajweedformer.core.DispatchLibrary]). Android: the app's `nativeLibraryDir` when
     * the library is extracted there, else a copy out of the app's APKs. Without one the SDK downloads and prepares no
     * program for that SoC. Asked at most once per SoC in a process.
     */
    val dispatch: (String) -> ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution = {
        ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution(null, "this platform runs no NPU program")
    },
    /** Why this OS cannot run an NPU program of a SoC, when that is known for sure (Android: a Pixel whose system has no
     *  `libedgetpu_litert.so`); null: nothing known against it. No program is downloaded for a SoC it names a reason for. */
    val npuRuntimeMissing: (String) -> String? = { null },
    /** The OS build as the incident reports and `accel_deny`'s `os_build` name it (Android: RELEASE/ID/INCREMENTAL). */
    val osBuildReport: String = "",
    /** The OS build (Android: `Build.FINGERPRINT`): an accelerator verdict that holds until an OS update is keyed by it. */
    val osBuild: String = (System.getProperty("os.name") ?: "") + " " + (System.getProperty("os.version") ?: ""),
    /** Whether the network is unmetered now (`prefetch(unmeteredOnly = true)` waits for it). */
    val network: NetworkPolicy = NetworkPolicy.UNKNOWN,
    /** The microphone; null on a platform without one (`listen()` then fails with `microphone_unavailable`). */
    val microphone: AudioInput? = null,
    /** The rate the engine is warmed for at `client.model()`: the microphone's (48000 on most phones), so the first
     *  `listen` take starts at once; 16000 where there is no microphone. */
    val warmRate: Int = 16_000,
    val engine: TajweedEngineFactory = TajweedEngineFactory.CORE,
    /** Called once with the client: registers what calls `client.background()` and `client.foreground()` by itself
     *  (Android: the activity lifecycle). Returns what undoes it at `close()`. */
    val lifecycle: ((TajweedClient) -> (() -> Unit))? = null,
    /** With a [lifecycle]: the app was already in the foreground when the client was made (Android: an activity had
     *  started, so no callback will say so), and the making of the client is the app's opening. */
    val inForegroundAtStart: Boolean = false,
    /** Shows the SDK's own sharing sheets (Android: dialogs over the resumed activity); null: none can be shown. */
    val sheets: ((Sharing, Sharing.SheetRequest) -> Unit)? = null,
    /** Free bytes where the models are kept; null when unknown. */
    val freeSpace: ((File) -> Long?)? = { f -> try { generateSequence(f) { it.parentFile }.first { it.exists() }.usableSpace } catch (_: Exception) { null } },
    /** More observers of the cloud events (Android: the thermal listener's target). */
    val extraObserver: ((TajweedObserver) -> (() -> Unit))? = null,
    /**
     * SDK 4.0: the install's keys, made in [the folder][File] the SDK keeps the Core's files in.
     * Android: the Keystore (StrongBox when present); the desktop JVM: software keys ([SoftwareIdentity]).
     */
    val identity: (File) -> InstallIdentity = { dir -> SoftwareIdentity(dir) },
    /** The device-bound key that seals the Core's saved state, and the monotonic counter (Android: the Keystore). */
    val stateVault: (File) -> StateVault = { dir -> FileStateVault(dir) },
    /** The boot's id, 32 bytes (Android: from Settings.Global.BOOT_COUNT): the Core's clock tells a reboot by it. */
    val bootId: ByteArray = ByteArray(32),
    /** Risk signals only the platform knows, reported with the lease request, never acted on. */
    val coreSignals: Int = 0,
    /** Tests: the Core's wall clock and boot clock (ms) instead of the system's. */
    val coreClock: Pair<() -> Long, () -> Long>? = null,
    /** Removes a folder and everything in it; false when something could not be removed (tests make it fail). */
    val deleteTree: (File) -> Boolean = { f -> f.deleteRecursively() },
)
