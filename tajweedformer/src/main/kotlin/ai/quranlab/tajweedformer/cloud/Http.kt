package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ends the requests of one thread's work from another thread (SPEC 11.7: sharing turned off during an upload).
 * The sharing outbox binds one to the thread of an upload pass ([bind]); every [HttpRequest] made on that thread
 * while it is bound carries it, the token's requests too. [abort] ends the request that is in flight: the transport
 * stops reading a body file at once and closes the connection, so a write, or a wait for the answer, on the other
 * thread fails with an IOException, like a connection that dropped. Once aborted it stays so: a later request that
 * carries it fails before anything is sent, and an answer that still came is not given out.
 */
internal class HttpAbort {
    @Volatile var aborted = false
        private set
    private var how: (() -> Unit)? = null

    /** The transport: how the request it has in flight is ended (null: none is). Called at once when [abort] came first. */
    fun inFlight(how: (() -> Unit)?) {
        val now = synchronized(this) {
            this.how = how
            aborted
        }
        if (now && how != null) try { how() } catch (_: Exception) {}
    }

    fun abort() {
        val h = synchronized(this) {
            aborted = true
            how
        }
        try { h?.invoke() } catch (_: Exception) {}
    }

    companion object {
        private val bound = ThreadLocal<HttpAbort?>()

        /** The one bound to this thread, or null. */
        fun current(): HttpAbort? = bound.get()

        /** Binds [abort] to this thread (null: none). Returns the one that was bound before, to put back. */
        fun bind(abort: HttpAbort?): HttpAbort? {
            val before = bound.get()
            if (abort == null) bound.remove() else bound.set(abort)
            return before
        }
    }
}

/**
 * One HTTP request. A body is bytes or a file (PUTs of segments stream from disk). [abort]: the handle bound to the
 * thread that makes the request, if any ([HttpAbort]).
 */
internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyFile: File? = null,
    val abort: HttpAbort? = HttpAbort.current(),
)

/** One HTTP response; header names lower case. */
internal class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299

    fun header(name: String): String? = headers[name.lowercase()]

    /** The body as a JSON object, or null. */
    fun json(): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Json.parse(String(body, Charsets.UTF_8)) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    /** qlab-api's `{"error","message"}`, or `http_<status>`. */
    val error: ApiError get() {
        val o = json()
        return ApiError(status, o?.get("error") as? String ?: "http_$status", o?.get("min_sdk") as? String, o?.get("reason") as? String)
    }
}

/** The network, replaceable in tests. Throws [IOException] when no response came. */
internal fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpResponse
}

/**
 * [HttpURLConnection], available on Android and the JVM without a dependency. Never sends an
 * `Origin` header (an `apps` key with one is refused), never follows redirects, never caches.
 */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val c = URL(request.url).openConnection() as HttpURLConnection
        val abort = request.abort
        try {
            if (abort != null) {
                if (abort.aborted) throw IOException(ENDED)
                // closing the connection ends a write, or a wait for the answer, on this thread
                abort.inFlight { c.disconnect() }
            }
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = request.method
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            val file = request.bodyFile
            val bytes = request.body
            if (file != null) {
                // segments stream from disk with their length (never chunked: R2 refuses that)
                c.doOutput = true
                c.setFixedLengthStreamingMode(file.length())
                connected(c, abort)
                c.outputStream.use { o ->
                    file.inputStream().use { input ->
                        // in small blocks: an ended request hands the system no more than the block it is in, and
                        // lets go of the file at once
                        val block = ByteArray(BLOCK)
                        while (true) {
                            if (abort?.aborted == true) throw IOException(ENDED)
                            val n = input.read(block)
                            if (n < 0) break
                            o.write(block, 0, n)
                        }
                    }
                }
            } else if (bytes != null) {
                // small JSON bodies are buffered: in streaming mode a 401's body (its error code) is lost
                c.doOutput = true
                connected(c, abort)
                c.outputStream.use { it.write(bytes) }
            } else {
                connected(c, abort)
            }
            if (abort?.aborted == true) throw IOException(ENDED)
            val status = c.responseCode
            val stream: InputStream? = if (status >= 400) c.errorStream else try { c.inputStream } catch (_: IOException) { c.errorStream }
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
            // an answer that still came after the request was ended is not given out
            if (abort?.aborted == true) throw IOException(ENDED)
            return HttpResponse(status, headers, body)
        } finally {
            abort?.inFlight(null)
            c.disconnect()
        }
    }

    /**
     * A request that can be ended connects first: closing a connection that does not exist yet ends nothing, so an
     * end that came while it was connecting is seen here, before anything of the request is sent.
     */
    private fun connected(c: HttpURLConnection, abort: HttpAbort?) {
        if (abort == null) return
        c.connect()
        if (abort.aborted) throw IOException(ENDED)
    }

    private companion object {
        const val ENDED = "the request was ended"

        /** A body file is sent in blocks of this size. */
        const val BLOCK = 16 * 1024
    }
}

/** What the SDK does about an answer (PLATFORM_CLIENT.md section 8, the error table). */
internal enum class ErrorAction {
    /** A bug in the SDK: drop the request, report `other`/`bad_request:<route>` once a day. */
    DROP_AND_REPORT,
    /** Revoked key: no network calls until the next launch. */
    STOP_UNTIL_NEXT_LAUNCH,
    /** Renew the token once and retry once. */
    RENEW_TOKEN,
    /** SDK bug, missing credential: stop. */
    STOP,
    /** Fetch a new challenge, retry once. */
    NEW_CHALLENGE,
    /** Continue with an unattested token; sharing paused this launch. */
    CONTINUE_UNATTESTED,
    /** Stop network calls this launch; recognition keeps working offline. */
    STOP_THIS_LAUNCH,
    /** Token for another app: get a new one. */
    NEW_TOKEN,
    /** Sharing needs an attested token here. */
    NEED_ATTESTATION,
    /** Sharing switched off: pause, keep the local queue 7 days. */
    PAUSE_SHARING,
    /** Capped tier: stop this session's upload after the last allowed segment. */
    SEGMENT_CAP,
    /** The session is unknown or gone on the server: drop it locally. */
    DROP_LOCAL,
    /** Stop that call (new session id for id_conflict). */
    CONFLICT,
    /** Drop that payload. */
    DROP,
    /** Back off (1 min, 10 min, next launch). */
    BACKOFF,
    /** Keep it, retry at the next launch. */
    RETRY_NEXT_LAUNCH,
}

/** An error answer: HTTP status and qlab-api's code. */
internal data class ApiError(
    val status: Int,
    val code: String,
    /** With 403 `sdk_too_old`: the SDK version the server asks for (`min_sdk`), when the answer names it. */
    val minSdk: String? = null,
    /** The body's `reason`: why the server gave this answer, when it says. A key that belongs to another app, a
     *  key of the wrong type and an origin that is not on the key are answered as a bad key (401 `invalid_key`)
     *  with the reason `app_not_allowed`, `key_type_mismatch` or `origin_not_allowed`; a made-up or revoked key
     *  has none. */
    val reason: String? = null,
) {
    /**
     * What the app was refused for: the body's [reason] when the error catalogue has words for it (the `reasons` of
     * `server_rejected`), else the error [code]. The reason is read first; what the SDK does to its network calls
     * ([action]) follows the code as before.
     */
    val refusal: String get() = reason?.takeIf { it in ai.quranlab.tajweedformer.ErrorCatalogue.reasons("server_rejected") } ?: code

    val action: ErrorAction get() = when {
        status == 400 -> ErrorAction.DROP_AND_REPORT
        status == 401 && code == "invalid_key" -> ErrorAction.STOP_UNTIL_NEXT_LAUNCH
        status == 401 && code == "invalid_token" -> ErrorAction.RENEW_TOKEN
        status == 401 && code == "challenge_invalid" -> ErrorAction.NEW_CHALLENGE
        status == 401 && (code == "attestation_failed" || code == "assertion_failed") -> ErrorAction.CONTINUE_UNATTESTED
        status == 401 -> ErrorAction.STOP
        status == 403 && code == "app_mismatch" -> ErrorAction.NEW_TOKEN
        status == 403 && code == "attestation_required" -> ErrorAction.NEED_ATTESTATION
        status == 403 && code == "contributions_disabled" -> ErrorAction.PAUSE_SHARING
        status == 403 && code == "segment_cap" -> ErrorAction.SEGMENT_CAP
        status == 403 && code == "contributor_revoked" -> ErrorAction.DROP_LOCAL
        status == 403 -> ErrorAction.STOP_THIS_LAUNCH
        status == 404 -> ErrorAction.DROP_LOCAL
        status == 409 -> ErrorAction.CONFLICT
        status == 410 -> ErrorAction.DROP_LOCAL
        status == 413 -> ErrorAction.DROP
        status == 429 -> ErrorAction.BACKOFF
        status == 503 -> ErrorAction.RETRY_NEXT_LAUNCH
        status >= 500 -> ErrorAction.BACKOFF
        else -> ErrorAction.DROP
    }

    override fun toString(): String = "HTTP $status $code"
}

/** A request that did not succeed: an error answer, or no answer ([api] null: offline). */
internal class CloudException(val api: ApiError?, message: String, cause: Throwable? = null) : Exception(message, cause) {
    val offline: Boolean get() = api == null

    /** No answer because the system does not let the app use the network (no INTERNET permission). */
    val notPermitted: Boolean get() = cause is ai.quranlab.tajweedformer.NetworkNotPermitted
    /** Worth retrying later. */
    val retryable: Boolean get() = api == null || api.action in setOf(ErrorAction.BACKOFF, ErrorAction.RETRY_NEXT_LAUNCH)
}
