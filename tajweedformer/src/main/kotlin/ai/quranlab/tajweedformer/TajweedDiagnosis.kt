package ai.quranlab.tajweedformer

/**
 * How the SDK stands on this device, from `client.diagnose()`: facts for a setup check or a bug report. It holds no
 * audio, no text and no identifier of the learner, the device or the install, so [toJson] is safe to paste into an
 * issue.
 */
public class TajweedDiagnosis internal constructor(
    /** This SDK's version. */
    public val sdkVersion: String,
    /** The newest SDK version Quran Lab publishes, when the signed config names one; null when unknown. */
    public val sdkLatest: String?,
    /** Whether Quran Lab accepted the key: true, false, or null while no answer from the server was seen. */
    public val keyAccepted: Boolean?,
    /** Null unless the key was refused; then `invalid_key`, `sdk_too_old` or `server_rejected`. */
    public val keyReason: String?,
    /** The signed config: `fresh` (a verified config that has not expired is in force), `stale` (the stored one is
     *  past its expiry) or `none`. */
    public val config: String,
    /** The model on this device: its version and build, and whether it is open and verified. Null when there is none. */
    public val modelVersion: String?,
    public val modelBuild: String?,
    public val modelVerified: Boolean,
    /** The context the next Session uses when the app chooses none; null without an open model. */
    public val context: String?,
    /** What runs now and where the accelerator's promotion stands; null without an open model. */
    public val backend: TajweedBackend?,
    /** The microphone permission: `granted`, `denied`, `notAsked`, or `none` (no microphone on this platform). */
    public val microphone: String,
    /** What the SDK holds on disk (`client.storage`). */
    public val storage: Map<String, Any>,
    /** The code of the last error a client call raised; null when none did. */
    public val lastError: String?,
    /** Chunks dropped since the Client was made because the app did not collect a Session's `chunks` (at most 256
     *  wait). 0 in an app that reads them. */
    public val droppedChunks: Long,
    /** The connection now: `unmetered` (Wi-Fi, Ethernet), `metered` (mobile data, a metered hotspot, data saver),
     *  `none`, or `unknown` where the platform cannot tell (the desktop JVM). `prefetch(unmeteredOnly = true)` waits
     *  on `metered` and `none`. */
    public val connection: String,
    /** The protocol the last request for a model file ran on in this process: `h3`, `h2` or `http/1.1`; null while this
     *  Client has made no model request. The SDK's HTTP stack on Android and on the desktop JVM (`HttpURLConnection`)
     *  speaks HTTP/1.1 only, so here it is `http/1.1` or null. Kept in memory: it is in no statistics and is never
     *  written to the device. An optional field of the report: a reader must not expect it from every SDK. */
    public val downloadProtocol: String? = null,
) {
    /** The report as a plain map, in the layout the four SDKs share. */
    public fun toMap(): Map<String, Any?> = linkedMapOf(
        "sdk" to linkedMapOf("version" to sdkVersion, "latest" to sdkLatest),
        "key" to linkedMapOf("accepted" to keyAccepted, "reason" to keyReason),
        "config" to config,
        "model" to (if (modelVersion == null || modelBuild == null) null else linkedMapOf("version" to modelVersion, "build" to modelBuild, "verified" to modelVerified)),
        "context" to context,
        "backend" to backend?.let { b -> linkedMapOf("kind" to b.kind.name.lowercase(), "name" to b.name, "stage" to b.stage.name.lowercase(), "reason" to b.reason) },
        "microphone" to microphone,
        "storage" to storage,
        "lastError" to lastError,
        "droppedChunks" to droppedChunks,
        "connection" to connection,
        "downloadProtocol" to downloadProtocol,
    )

    /** The report as JSON. */
    public fun toJson(): String = Json.write(toMap())

    override fun toString(): String = toJson()
}
