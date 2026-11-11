package ai.quranlab.tajweedformer

/**
 * Every failure the SDK raises: one type, with a stable [code] (the same string in the Android, Apple, web and C
 * SDKs), a [message], a [fix] (one sentence a developer can act on) and a [docs] link. The codes and texts come from
 * the catalogue shared by the four SDKs.
 *
 * ```
 * try { client.model() } catch (e: TajweedError) { if (e.code == "needs_connection") showOfflineHint() }
 * ```
 *
 * Codes: `invalid_argument`, `invalid_key`, `sdk_too_old`, `server_rejected`, `client_exists`, `model_unavailable`,
 * `needs_connection`, `network_not_permitted`, `download_failed`, `storage_full`, `storage_failed`, `model_missing`,
 * `model_invalid`, `signature_invalid`,
 * `checksum_mismatch`, `model_incompatible`, `reference_missing`, `session_open`, `session_closed`, `cancelled`,
 * `engine_failed`, `runtime_unsupported`, `microphone_denied`, `microphone_unavailable`, `interrupted`,
 * `sharing_unavailable`.
 */
public open class TajweedError internal constructor(
    /** The stable code of the failure, for example `needs_connection`. */
    public val code: String,
    params: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : Exception(ErrorCatalogue.message(code, params), cause) {
    /** What happened, in one sentence. */
    override val message: String get() = super.message ?: code

    /** What to do about it, in one sentence. */
    public val fix: String = ErrorCatalogue.fix(code, params)

    /** The documentation of this code: `https://sdk.quranlab.ai/docs/errors#<code>`. */
    public val docs: String = ErrorCatalogue.docs(code)

    override fun toString(): String = "TajweedError($code): $message"

    internal companion object {
        /**
         * Any failure as a [TajweedError]: itself when it is one; a refused argument as `invalid_argument`; a server
         * refusal by its meaning; a file error as `storage_failed`; anything else as `engine_failed`.
         */
        fun of(e: Throwable): TajweedError = when (e) {
            is TajweedError -> e
            is ai.quranlab.tajweedformer.cloud.CloudException -> ofCloud(e)
            is IllegalArgumentException, is IndexOutOfBoundsException -> TajweedError("invalid_argument", mapOf("detail" to (e.message ?: e.javaClass.simpleName)), e)
            is java.io.IOException -> TajweedError("storage_failed", mapOf("detail" to (e.message ?: e.javaClass.simpleName)), e)
            is kotlinx.coroutines.CancellationException -> TajweedError("cancelled", cause = e)
            else -> TajweedError("engine_failed", mapOf("detail" to (e.message ?: e.javaClass.simpleName)), e)
        }

        /** A server answer (or no answer) as a catalogue error. */
        fun ofCloud(e: ai.quranlab.tajweedformer.cloud.CloudException): TajweedError {
            val a = e.api ?: return if (e.notPermitted) RecognizerException.NetworkNotPermitted(e) else RecognizerException.NeedsConnection(e)
            return ofServerCode(a.refusal, a.minSdk, refused = a.status == 403) ?: TajweedError("download_failed", mapOf("detail" to a.toString()), e)
        }

        /** The server's codes the SDK knows as a refusal of the app or its key, whatever the status they came with. */
        private val REFUSALS = setOf("developer_not_approved", "not_a_member", "key_type_mismatch", "origin_not_allowed", "app_not_allowed",
            "app_blocked", "environment_mismatch", "app_owned_elsewhere", "unauthorized")

        /**
         * The catalogue error of a server's refusal, else null. `invalid_key` and `sdk_too_old` keep their own codes;
         * every other refusal is `server_rejected`, with the words the catalogue has for the server's code when it
         * has some ([ErrorCatalogue], `reasons`) and the code as the detail otherwise. [refused]: the server answered
         * 403, or the client was stopped by a refusal, so a code the SDK has never heard of is a refusal too.
         * [appId] fills `{origin}` (an SDK has no page origin: it is the app id it registered with).
         *
         * [code] is the answer's refusal ([ai.quranlab.tajweedformer.cloud.ApiError.refusal]): the body's `reason`
         * when the catalogue has words for it, else its `error`. So a key that belongs to another app, which the
         * server answers as 401 `invalid_key` with the reason `app_not_allowed`, is `server_rejected` with that
         * reason's words, and a 401 `invalid_key` with no reason stays `invalid_key`.
         */
        fun ofServerCode(code: String?, minSdk: String? = null, refused: Boolean = false, appId: String? = null): TajweedError? = when {
            code == null -> null
            code == "invalid_key" -> RecognizerException.InvalidKey()
            code == "sdk_too_old" -> RecognizerException.SdkTooOld(minSdk ?: "a newer version")
            code in REFUSALS || code in ErrorCatalogue.reasons("server_rejected") || refused ->
                RecognizerException.ServerRejected(code, appId ?: ai.quranlab.tajweedformer.cloud.QuranLabCloud.current?.env?.app?.bundleId)
            else -> null
        }
    }
}

/**
 * The error catalogue shared by the four SDKs (`errors.json` of the SDK 3.0.0 contract), embedded byte for byte as a
 * resource; a test fails when the copy differs from the contract's file. `message` and `fix` may hold `{placeholders}`.
 */
internal object ErrorCatalogue {
    const val RESOURCE = "/ai/quranlab/tajweedformer/errors.json"

    /** The embedded file's bytes. */
    fun bytes(): ByteArray = ErrorCatalogue::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
        ?: throw IllegalStateException("the error catalogue $RESOURCE is not in the SDK")

    /** An entry's words, and its `reasons`: other words for the same code, by what the error's detail is. */
    private class Entry(val message: String, val fix: String, val reasons: Map<String, Entry> = emptyMap())

    private val docsBase: String
    private val entries: Map<String, Entry>

    init {
        var base = "https://sdk.quranlab.ai/docs/errors#"
        val m = LinkedHashMap<String, Entry>()
        try {
            val root = Json.parse(String(bytes(), Charsets.UTF_8)) as Map<*, *>
            (root["docs_base"] as? String)?.let { base = it }
            for (e in root["errors"] as List<*>) {
                val o = e as Map<*, *>
                val reasons = (o["reasons"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                    val r = v as Map<*, *>
                    k as String to Entry(r["message"] as String, r["fix"] as String)
                } ?: emptyMap()
                m[o["code"] as String] = Entry(o["message"] as String, o["fix"] as String, reasons)
            }
        } catch (_: Exception) {
            // without its catalogue an error still carries its code
        }
        docsBase = base
        entries = m
    }

    /** Every code of the catalogue, in its order. */
    val codes: List<String> get() = entries.keys.toList()

    private fun fill(text: String, params: Map<String, String>): String =
        Regex("\\{([a-z_]+)\\}").replace(text) { m -> params[m.groupValues[1]] ?: "" }

    /**
     * The one lookup: the words of [code], or of one of its `reasons` when the error's `detail` (for a refusal, the
     * server's code) is a key of them. No text of a message is written in code anywhere else.
     */
    private fun words(code: String, params: Map<String, String>): Entry? {
        val e = entries[code] ?: return null
        return params["detail"]?.let { e.reasons[it] } ?: e
    }

    /** The server codes [code] has words of their own for (`reasons`), in the catalogue's order. */
    fun reasons(code: String): List<String> = entries[code]?.reasons?.keys?.toList() ?: emptyList()

    fun message(code: String, params: Map<String, String>): String = words(code, params)?.let { fill(it.message, params) } ?: code

    fun fix(code: String, params: Map<String, String>): String = words(code, params)?.let { fill(it.fix, params) } ?: ""

    fun docs(code: String): String = docsBase + code
}
