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
 * [errorCause] (SDK 4.0.2) says where the failure's cause is, in tokens an app can log, show or send in a support
 * request: `needs_connection` with `needs == "model"` means the model's files are not on the device yet (connect to
 * download), `needs == "lease"` that the model is on the device and needs Quran Lab's answer to open.
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

    /** Where the cause of this failure is, set where it is made ([at]); the error report reads it. */
    @Volatile internal var cause40: ai.quranlab.tajweedformer.cloud.ErrorCause? = null

    /** 24.13 item 3, on `needs_connection`: `model` (files the call needs are not on the device) or `lease` (they are,
     *  and the model needs Quran Lab's answer to open), set where it is made ([needing]). Unset: `lease`, the code of a
     *  call that needs no file it lacks (a refusal of the Core on the device, a request of no model file). */
    @Volatile internal var needs40: String? = null

    /**
     * SDK 4.0.2: where the cause of this failure is, the same tokens as the SDK's error report of it:
     * the stage and reason, and when known the HTTP status of the answer that failed, the system error's class and its
     * code; on `needs_connection`, [TajweedErrorCause.needs]. Never a message, a path, a URL, a host name or an
     * identifier. Null for the codes that are the app's own (`invalid_argument`, `client_exists`, `session_open`,
     * `session_closed`, `cancelled`). Kotlin's own [cause] stays the underlying failure (a Core status, an I/O error).
     * It does not depend on the reports: Quran Lab's config turning issue reports off stops the report, not this.
     */
    public val errorCause: TajweedErrorCause? by lazy { TajweedErrorCause.of(this) }

    /** Counted once as a failure that reached the app (24.1: one occurrence per error, however it is delivered). */
    internal val reachedApp = java.util.concurrent.atomic.AtomicBoolean(false)

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
            // contract 24.4 rule 4: a device that runs out of memory is `engine_failed`, "not enough memory"
            is OutOfMemoryError -> TajweedError("engine_failed", mapOf("detail" to "not enough memory"), e)
            else -> TajweedError("engine_failed", mapOf("detail" to (e.message ?: e.javaClass.simpleName)), e)
        }

        /** A server answer (or no answer) as a catalogue error. */
        fun ofCloud(e: ai.quranlab.tajweedformer.cloud.CloudException): TajweedError {
            val a = e.api ?: return if (e.notPermitted) RecognizerException.NetworkNotPermitted(e) else RecognizerException.NeedsConnection(e)
            // a stop reason gives its error only in a 401 or a 403; under another status the call failed, no more
            val known = if (a.stopOutOfPlace) null else ofServerCode(a.refusal, a.minSdk, refused = a.status == 403)
            return known ?: TajweedError("download_failed", mapOf("detail" to a.toString()), e)
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
 * 24.13 item 3: on `needs_connection`, what the call waits for: `model` when files it needs are not on the device
 * ([onDevice] `none` or `partial`, [ai.quranlab.tajweedformer.cloud.ErrorRules.needs]), `lease` when they all are. The
 * place nearest the failure names it.
 */
internal fun <T : TajweedError> T.needing(onDevice: String): T {
    if (needs40 == null && code == "needs_connection") needs40 = ai.quranlab.tajweedformer.cloud.ErrorRules.needs(onDevice)
    return this
}

/**
 * Names where the cause of this failure is, unless a cause was named
 * already: the place nearest the failure names it. The platform error's class is read from the error's chain.
 */
internal fun <T : TajweedError> T.at(stage: String, reason: String, status: Int? = null, errorCode: String? = null, retries: Int? = null): T {
    if (cause40 == null) {
        cause40 = ai.quranlab.tajweedformer.cloud.ErrorCause(stage, reason, status?.takeIf { it in 100..599 },
            ai.quranlab.tajweedformer.cloud.ErrorRules.platformName(this),
            ai.quranlab.tajweedformer.cloud.ErrorRules.token(errorCode, ai.quranlab.tajweedformer.cloud.ErrorRules.ERROR_CODE), retries)
    }
    return this
}

/** SDK 4.0.1: the cause named is a request to the hosts of [role], asked of more than one host when
 *  [fallback]. A cause that names its role already keeps it; call it after [at]. */
internal fun <T : TajweedError> T.via(role: String, fallback: Boolean): T {
    cause40?.let { c -> if (c.hostRole == null) cause40 = c.copy(hostRole = role, fallback = fallback) }
    return this
}

/** [at] with a cause made elsewhere (a request's, [ai.quranlab.tajweedformer.cloud.ErrorCause.ofCloud]). */
internal fun <T : TajweedError> T.at(cause: ai.quranlab.tajweedformer.cloud.ErrorCause): T {
    if (cause40 == null) cause40 = cause
    return this
}

/**
 * A failure that reaches the app (24.1 item 1): counted once per error as an error report of the process's Quran Lab
 * cloud, with the stage of its cause. Never throws and never waits.
 */
internal fun reachedApp(e: Throwable?) {
    val t = e as? TajweedError ?: return
    if (!t.reachedApp.compareAndSet(false, true)) return
    // 24.13: the app's cause is made here, from the occurrence the report is made from
    try { t.errorCause } catch (_: Throwable) {}
    try { ai.quranlab.tajweedformer.cloud.QuranLabCloud.current?.errorReachedApp(t) } catch (_: Throwable) {}
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

/**
 * SDK 4.0.2: where the cause of a [TajweedError] is, as tokens: what the SDK's error report of the
 * same failure says, so an app can log it, show the right words, or send it in a support request.
 *
 * ```
 * catch (e: TajweedError) {
 *     if (e.code == "needs_connection" && e.errorCause?.needs == "model") showConnectToDownload() else showConnectToContinue()
 *     log("tajweed ${e.code} ${e.errorCause}")
 * }
 * ```
 */
public data class TajweedErrorCause(
    /** Where the cause is: `config`, `register`, `attest`, `lease`, `manifest`, `download`, `verify`, `storage`,
     *  `runtime`, `model_open`, `accelerator`, `audio`, `session`, `sharing` or `events`. */
    public val stage: String,
    /** The stage's reason, a lower-case token (`network`, `timeout`, `http`, `rate_limited`, `refused`, `hash`,
     *  `quota`, `memory`, ...); `other` when none fits. */
    public val reason: String,
    /** The HTTP status of the answer that failed, when there was one (100 to 599). */
    public val httpStatus: Int? = null,
    /** The system error's class (a Java or Kotlin exception class), when known. Never its message. */
    public val errorName: String? = null,
    /** Its code: the server's error of a 4xx answer (`model_limit`, `app_blocked`), a Core status, an errno. */
    public val errorCode: String? = null,
    /** On `needs_connection` only: `model` (the model's files are not on the device yet: connect to download) or
     *  `lease` (the model is on the device and needs Quran Lab's answer to open). */
    public val needs: String? = null,
) {
    internal companion object {
        /** The cause [e] carries: its report's cause ([ai.quranlab.tajweedformer.cloud.ErrorCause.of], the function the
         *  report uses), or none for the codes no report is made for. */
        fun of(e: TajweedError): TajweedErrorCause? {
            if (e.code in ai.quranlab.tajweedformer.cloud.ErrorRules.CODES_NOT_REPORTED) return null
            val c = try { ai.quranlab.tajweedformer.cloud.ErrorCause.of(e) } catch (_: Exception) { return null }
            return of(e.code, c, e.needs40)
        }

        /** [c] filtered by 24.2's patterns (a value that is not a token of its field is left out, never cut into one),
         *  and [needs] on `needs_connection` (`lease` when the error names none). */
        fun of(code: String, c: ai.quranlab.tajweedformer.cloud.ErrorCause, needs: String?): TajweedErrorCause {
            val r = ai.quranlab.tajweedformer.cloud.ErrorRules
            return TajweedErrorCause(
                stage = c.stage,
                reason = r.token(c.reason, r.TOKEN) ?: "other",
                httpStatus = c.httpStatus?.takeIf { it in 100..599 },
                errorName = r.token(c.errorName, r.ERROR_NAME),
                errorCode = r.token(c.errorCode, r.ERROR_CODE),
                needs = if (code == "needs_connection") needs ?: "lease" else null,
            )
        }
    }
}
