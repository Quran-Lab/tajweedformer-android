package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP request. A body is bytes or a file (PUTs of segments stream from disk). */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyFile: File? = null,
)

/** One HTTP response; header names lower case. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
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
        return ApiError(status, o?.get("error") as? String ?: "http_$status")
    }
}

/** The network, replaceable in tests. Throws [IOException] when no response came. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpResponse
}

/**
 * [HttpURLConnection], available on Android and the JVM without a dependency. Never sends an
 * `Origin` header (an `apps` key with one is refused), never follows redirects, never caches.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val c = URL(request.url).openConnection() as HttpURLConnection
        try {
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
                c.outputStream.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            } else if (bytes != null) {
                // small JSON bodies are buffered: in streaming mode a 401's body (its error code) is lost
                c.doOutput = true
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            val stream: InputStream? = if (status >= 400) c.errorStream else try { c.inputStream } catch (_: IOException) { c.errorStream }
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
            return HttpResponse(status, headers, body)
        } finally {
            c.disconnect()
        }
    }
}

/** What the SDK does about an answer (PLATFORM_CLIENT.md section 8, the error table). */
enum class ErrorAction {
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
data class ApiError(val status: Int, val code: String) {
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
class CloudException(val api: ApiError?, message: String) : Exception(message) {
    val offline: Boolean get() = api == null
    /** Worth retrying later. */
    val retryable: Boolean get() = api == null || api.action in setOf(ErrorAction.BACKOFF, ErrorAction.RETRY_NEXT_LAUNCH)
}
