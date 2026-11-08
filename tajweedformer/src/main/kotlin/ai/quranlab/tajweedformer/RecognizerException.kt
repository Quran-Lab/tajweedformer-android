package ai.quranlab.tajweedformer

/** Everything the SDK throws. Every public call that fails throws a subclass of this. */
sealed class RecognizerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** A file the model needs is not there. */
    class ModelMissing(val what: String) : RecognizerException("model missing: $what")

    /** The engine could not be made from the model files (a damaged or mismatched file). */
    class ModelLoadFailed(val reason: String) : RecognizerException("the model could not be loaded: $reason")

    /** A weight file's SHA-256 differs from the one it was made with. The model is refused, never used. */
    class ChecksumMismatch(val file: String) : RecognizerException("checksum mismatch: $file")

    /** Audio the SDK cannot take: a sample rate the resampler does not convert, a rate that changed
     *  within a session, or audio and features mixed in one session. */
    class UnsupportedAudioFormat(val why: String) : RecognizerException("unsupported audio format: $why")

    /** The microphone could not be opened or read (on Android: RECORD_AUDIO not granted, or the
     *  device does not record at the rate asked for). */
    class MicrophoneUnavailable(val why: String) : RecognizerException("microphone unavailable: $why")

    /** A backend that was asked for cannot run here. */
    class BackendUnavailable(val why: String) : RecognizerException("backend unavailable: $why")

    /** The engine returned an error, with its message. */
    class EngineFailed(val detail: String) : RecognizerException("engine failed: $detail")

    /** A model file could not be downloaded ([path]): the network, the server's answer, or a hash
     *  that did not match after the download (the file is deleted then). */
    class DownloadFailed(val path: String, val reason: String, cause: Throwable? = null) :
        RecognizerException("download failed: $path ($reason)", cause)

    /** A recognizer runs one session at a time; finish or cancel the current one first. */
    class SessionActive : RecognizerException("a session is already running on this recognizer")

    /** The session has already finished or failed, or its recognizer was closed. */
    class SessionClosed : RecognizerException("the session is closed")

    /** The session was cancelled. */
    class Cancelled : RecognizerException("the session was cancelled")

    /** A model folder's manifest.json is missing a field, lists a bad path, or does not cover a file
     *  the model needs. The folder is refused, never used. */
    class ManifestInvalid(val reason: String) : RecognizerException("manifest invalid: $reason")

    /** The manifest's Ed25519 signature does not verify against a trusted key, or it is unsigned
     *  where a signature is required. */
    class SignatureInvalid(val reason: String) : RecognizerException("manifest signature invalid: $reason")

    /** The model needs a newer SDK ([minSdkVersion]) or an engine format this SDK does not have. */
    class ModelIncompatible(val reason: String, val minSdkVersion: String? = null) :
        RecognizerException("model incompatible: $reason")

    /** calibration.json is not a `quranlab.tajweed-calibration/1` file this SDK can use. */
    class CalibrationInvalid(val reason: String) : RecognizerException("calibration.json invalid: $reason")

    /**
     * A tajweed option set in app code is invalid (an unknown preset, class, check or option, an
     * override with both or neither of preset and threshold, a threshold that is not finite or
     * outside -20..20, a context the model does not ship). A programming error: app code is never
     * silently dropped. [code] is `invalid_app_option` on every SDK.
     */
    class InvalidAppOption(val option: String, val detail: String = "") :
        RecognizerException("invalid_app_option: $option" + (if (detail.isEmpty()) "" else " ($detail)")) {
        val code: String get() = "invalid_app_option"
    }

    /** The V4 TajweedFormer engine is not in this build yet: [what] needs it. Settings, calibration,
     *  manifests and the API types work without it. */
    class EngineNotAvailable(val what: String) :
        RecognizerException("the TajweedFormer V4 engine is not available in this build yet ($what)")
}
