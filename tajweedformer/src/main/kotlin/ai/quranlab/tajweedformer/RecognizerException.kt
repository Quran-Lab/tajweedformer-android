package ai.quranlab.tajweedformer

/**
 * The SDK's failures as it raises them inside: each one is a [TajweedError] with its catalogue code, so nothing the
 * SDK throws needs translating on its way out. The subclasses keep what the code that catches them reads (a path, a
 * reason); an app sees only [TajweedError].
 */
internal sealed class RecognizerException(code: String, params: Map<String, String> = emptyMap(), cause: Throwable? = null) :
    TajweedError(code, params, cause) {
    /** A file the model needs is not there. */
    class ModelMissing(val what: String) : RecognizerException("model_missing", mapOf("detail" to what))

    /** The engine could not be made from the model files (a damaged or mismatched file). */
    class ModelLoadFailed(val reason: String) : RecognizerException("model_invalid", mapOf("detail" to reason))

    /** A file's size or SHA-256 differs from the manifest. The model is refused, never used. */
    class ChecksumMismatch(val file: String) : RecognizerException("checksum_mismatch", mapOf("file" to file))

    /** Audio the SDK cannot take: a sample rate the resampler does not convert. */
    class UnsupportedAudioFormat(val why: String) : RecognizerException("invalid_argument", mapOf("detail" to "unsupported audio format, $why"))

    /** No input device, the recorder did not start, or another app holds the microphone. */
    class MicrophoneUnavailable(val why: String) : RecognizerException("microphone_unavailable", mapOf("detail" to why))

    /** The learner or the system refused the microphone permission. */
    class MicrophoneDenied : RecognizerException("microphone_denied")

    /** A backend that was asked for cannot run here. */
    class BackendUnavailable(val why: String) : RecognizerException("engine_failed", mapOf("detail" to "backend unavailable, $why"))

    /** The engine returned an error, with its message. */
    class EngineFailed(val detail: String) : RecognizerException("engine_failed", mapOf("detail" to detail))

    /** A model file could not be downloaded ([path]): the network, the server's answer, or a hash
     *  that did not match after the download (the file is deleted then). */
    class DownloadFailed(val path: String, val reason: String, cause: Throwable? = null) :
        RecognizerException("download_failed", mapOf("detail" to "$path ($reason)"), cause)

    /** The model is not on this device and there is no connection to download it with. */
    class NeedsConnection(cause: Throwable? = null) : RecognizerException("needs_connection", cause = cause)

    /** The system does not let the app use the network (Android: no INTERNET permission), and a call needed it. */
    class NetworkNotPermitted(cause: Throwable? = null) : RecognizerException("network_not_permitted", cause = cause)

    /** Not enough free space for the model. */
    class StorageFull(val needed: Long, val available: Long) :
        RecognizerException("storage_full", mapOf("needed" to needed.toString(), "available" to available.toString()))

    /** The SDK's folder cannot be read or written. */
    class StorageFailed(val detail: String, cause: Throwable? = null) : RecognizerException("storage_failed", mapOf("detail" to detail), cause)

    /** A model runs one session at a time; finish or cancel the current one first. */
    class SessionActive : RecognizerException("session_open")

    /** The session has already finished or failed, or its model was closed. */
    class SessionClosed : RecognizerException("session_closed")

    /** The session was cancelled. */
    class Cancelled : RecognizerException("cancelled")

    /** A model folder's manifest.json is missing a field, lists a bad path, or does not cover a file
     *  the model needs. The folder is refused, never used. */
    class ManifestInvalid(val reason: String) : RecognizerException("model_invalid", mapOf("detail" to reason))

    /** The manifest's Ed25519 signature does not verify against a trusted key, or it is unsigned
     *  where a signature is required. */
    class SignatureInvalid(val reason: String) : RecognizerException("signature_invalid")

    /** The model needs a newer SDK ([minSdkVersion]), is a 2.x build, or has an engine format this SDK does not have. */
    class ModelIncompatible(val reason: String, val minSdkVersion: String? = null) :
        RecognizerException("model_incompatible", mapOf("detail" to reason))

    /** A value the API does not accept (an unknown context or head name, a surah or ayah out of range, a rule name).
     *  A programming error: app code is never silently dropped. */
    class InvalidAppOption(val option: String, val detail: String = "") :
        RecognizerException("invalid_argument", mapOf("detail" to option + (if (detail.isEmpty()) "" else " ($detail)")))

    /** The engine is not in this build of the native library: [what] needs it. */
    class EngineNotAvailable(val what: String) :
        RecognizerException("engine_failed", mapOf("detail" to "the TajweedFormer V4 engine is not available in this build ($what)"))

    /** This device cannot run the SDK: the native library is not built for its CPU, or it is not there ([detail]).
     *  The fix names where the SDK runs ([SdkInfo.MINIMUM]); updating the SDK or the model would not help. */
    class RuntimeUnsupported(val detail: String) :
        RecognizerException("runtime_unsupported", mapOf("detail" to detail, "minimum" to SdkInfo.MINIMUM))

    /** A second client while the first is not closed. */
    class ClientExists : RecognizerException("client_exists")

    /** The server does not know the key, or it is revoked. */
    class InvalidKey : RecognizerException("invalid_key")

    /** The server's 403 `sdk_too_old`: this key needs a newer SDK ([version] when the answer names it). */
    class SdkTooOld(val version: String) : RecognizerException("sdk_too_old", mapOf("version" to version, "sdk" to SdkInfo.VERSION))

    /** The server refused this app (blocked, not allowed, wrong key type): [serverCode] is its code. */
    class ServerRejected(val serverCode: String, appId: String? = null) : RecognizerException("server_rejected", mapOf("detail" to serverCode, "origin" to (appId ?: "")))

    /** Quran Lab's signed config names no model build for this SDK version (none is published for it yet). */
    class ModelUnavailable : RecognizerException("model_unavailable", mapOf("sdk" to SdkInfo.VERSION))

    /** `reference.units` before `fetch()`, or the download of the reference data failed. */
    class ReferenceMissing(cause: Throwable? = null) : RecognizerException("reference_missing", cause = cause)

    /** The take ended on an interruption that could not be resumed. */
    class Interrupted(val detail: String) : RecognizerException("interrupted", mapOf("detail" to detail))

    /** A sharing call while Quran Lab's config does not allow sharing for this app. */
    class SharingUnavailable : RecognizerException("sharing_unavailable")
}
