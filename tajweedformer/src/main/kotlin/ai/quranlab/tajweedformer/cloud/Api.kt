package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.SdkInfo
import java.io.IOException
import java.net.URLEncoder

/** How a request authenticates: the app key (`x-qlab-key`) or an app token (`Bearer qlt_...`). Never both. */
internal sealed class Credential {
    class Key(val key: QuranLabKey) : Credential()
    class Token(val token: String) : Credential()

    internal fun apply(h: MutableMap<String, String>) {
        when (this) {
            is Key -> h["x-qlab-key"] = key.value
            is Token -> h["authorization"] = "Bearer $token"
        }
    }
}

/**
 * The routes of qlab-api an SDK calls (PLATFORM_CLIENT.md), one method each, with exactly the
 * fields of each closed schema (an unknown field is a 400). Blocking; call off the main thread.
 * Every method returns the response or throws [CloudException].
 */
internal class QuranLabApi(
    val apiUrl: String = DEFAULT_API,
    transport: HttpTransport = UrlConnectionTransport(),
) {
    private val base = apiUrl.trimEnd('/')

    /** Every request goes through here: the system's refusal of the network is an IOException like no connection. */
    private val http = HttpTransport { r -> ai.quranlab.tajweedformer.permitted { transport.send(r) } }

    private fun call(method: String, path: String, cred: Credential?, body: Map<String, Any?>? = null,
                     extra: Map<String, String> = emptyMap(), expectOk: Boolean = true): HttpResponse {
        val h = LinkedHashMap<String, String>()
        cred?.apply(h)
        h.putAll(extra)
        val bytes = body?.let { Json.write(it).toByteArray(Charsets.UTF_8) }
        if (bytes != null) h["content-type"] = "application/json"
        val r = try {
            http.send(HttpRequest(method, base + path, h, bytes))
        } catch (e: IOException) {
            throw CloudException(null, "$method $path: ${e.message}", e)
        }
        if (expectOk && !r.ok) throw CloudException(r.error, "$method $path: ${r.error}")
        return r
    }

    // section 2

    /** `GET /v1/attest/challenge` with the key: the challenge string. */
    fun challenge(key: QuranLabKey): String =
        call("GET", "/v1/attest/challenge", Credential.Key(key)).json()?.get("challenge") as? String
            ?: throw CloudException(ApiError(200, "no_challenge"), "no challenge in the answer")

    /** `POST /v1/attest` unattested: `{"platform","bundle_id"}`. */
    fun attest(key: QuranLabKey, app: AppIdentity): AppToken =
        AppToken.from(call("POST", "/v1/attest", Credential.Key(key), linkedMapOf("platform" to app.platform, "bundle_id" to app.bundleId)))

    /** `POST /v1/attest` with a Play Integrity token: `{"platform":"android","bundle_id","challenge","integrity_token"}`. */
    fun attestPlayIntegrity(key: QuranLabKey, app: AppIdentity, challenge: String, integrityToken: String): AppToken =
        AppToken.from(call("POST", "/v1/attest", Credential.Key(key), linkedMapOf(
            "platform" to app.platform, "bundle_id" to app.bundleId, "challenge" to challenge, "integrity_token" to integrityToken)))

    // section 3

    /**
     * `GET /v1/config`. With a key, `platform` and `bundle_id` go in the query too. [etag] makes it
     * conditional (304: no body). [modelVersion] is the app's pin (`model_version=x.y.z`): the server answers that
     * version's build for this SDK, or its normal choice plus `pin.status: unavailable`. Returns the raw response for
     * [ConfigStore] to verify.
     */
    @JvmOverloads
    fun config(cred: Credential, bucket: Int, app: AppIdentity, etag: String?, modelVersion: String? = null): HttpResponse {
        val q = StringBuilder("?bucket=$bucket&sdk=${enc(SdkInfo.VERSION)}")
        if (cred is Credential.Key) q.append("&platform=${enc(app.platform)}&bundle_id=${enc(app.bundleId)}")
        if (modelVersion != null) q.append("&model_version=${enc(modelVersion)}")
        val extra = if (etag != null) mapOf("if-none-match" to etag) else emptyMap()
        val r = call("GET", "/v1/config$q", cred, extra = extra, expectOk = false)
        if (r.status != 200 && r.status != 304) throw CloudException(r.error, "GET /v1/config: ${r.error}")
        return r
    }

    // section 5

    /** `POST /v1/register`: `{"platform","bundle_id","app_name"?,"app_version","sdk_version","model_revision"?,"new_install"}`. */
    fun register(cred: Credential, app: AppIdentity, modelRevision: String?, newInstall: Boolean): Map<String, Any?> {
        val b = LinkedHashMap<String, Any?>()
        b["platform"] = app.platform
        b["bundle_id"] = app.bundleId
        app.name?.takeIf { it.isNotEmpty() }?.let { b["app_name"] = it.take(80) }
        b["app_version"] = app.version
        b["sdk_version"] = SdkInfo.VERSION
        modelRevision?.let { b["model_revision"] = it }
        b["new_install"] = newInstall
        return call("POST", "/v1/register", cred, b).json() ?: emptyMap()
    }

    // sections 6, 7

    /** `POST /v1/events` with a v2 body built by [TelemetryPayload]. */
    fun events(token: String, body: ByteArray): HttpResponse = raw("POST", "/v1/events", Credential.Token(token), body)

    /** `POST /v1/issues`. */
    fun issue(token: String, body: Map<String, Any?>): HttpResponse =
        call("POST", "/v1/issues", Credential.Token(token), body, expectOk = false)

    private fun raw(method: String, path: String, cred: Credential, body: ByteArray): HttpResponse {
        val h = LinkedHashMap<String, String>()
        cred.apply(h)
        h["content-type"] = "application/json"
        return try { http.send(HttpRequest(method, base + path, h, body)) } catch (e: IOException) {
            throw CloudException(null, "$method $path: ${e.message}")
        }
    }

    // section 10

    /** `POST /v1/uploads` with the session JSON; the answer's URLs. */
    fun uploadStart(token: String, id: String, session: ByteArray): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "content-type" to "application/json", "x-qlab-id" to id)
        return try { http.send(HttpRequest("POST", "$base/v1/uploads", h, session)) } catch (e: IOException) {
            throw CloudException(null, "POST /v1/uploads: ${e.message}")
        }
    }

    /** `POST /v1/uploads/{id}/urls?from=n` with `x-qlab-contributor`. */
    fun uploadUrls(token: String, id: String, from: Int, contributor: String): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "x-qlab-contributor" to contributor)
        return try { http.send(HttpRequest("POST", "$base/v1/uploads/$id/urls?from=$from", h)) } catch (e: IOException) {
            throw CloudException(null, "POST /v1/uploads/$id/urls: ${e.message}")
        }
    }

    /**
     * `POST /v1/uploads/{id}/takes` with `x-qlab-contributor` (SDK 2.0.3): the take boundaries of a recording of two or
     * more takes, `{"takes":[{"start_ms","gap_ms"},...]}`, after its last segment. Idempotent: the server replaces the list.
     */
    fun uploadTakes(token: String, id: String, contributor: String, body: ByteArray): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "content-type" to "application/json", "x-qlab-contributor" to contributor)
        return try { http.send(HttpRequest("POST", "$base/v1/uploads/$id/takes", h, body)) } catch (e: IOException) {
            throw CloudException(null, "POST /v1/uploads/$id/takes: ${e.message}")
        }
    }

    /** A plain PUT of one segment to its presigned URL: `content-type: audio/ogg` and nothing else to sign. */
    fun putSegment(url: String, file: java.io.File): HttpResponse = try {
        http.send(HttpRequest("PUT", url, mapOf("content-type" to "audio/ogg"), bodyFile = file))
    } catch (e: IOException) {
        throw CloudException(null, "PUT segment: ${e.message}")
    }

    // section 9: the learner's own routes

    /** `GET /v1/contributions/summary` (token + `x-qlab-contributor`). */
    fun contributionsSummary(token: String, contributor: String): Map<String, Any?> =
        call("GET", "/v1/contributions/summary", Credential.Token(token), extra = mapOf("x-qlab-contributor" to contributor)).json() ?: emptyMap()

    /** `POST /v1/contributions/challenge` `{"app","contributor"}`, no credential: the single-use nonce. */
    internal fun deletionChallenge(appId: String, contributor: String): String =
        call("POST", "/v1/contributions/challenge", null, linkedMapOf("app" to appId, "contributor" to contributor))
            .json()?.get("nonce") as? String ?: throw CloudException(ApiError(200, "no_nonce"), "no nonce in the answer")

    /** `POST /v1/contributions/delete`, no credential: the signed deletion. 202 for every well-formed request. */
    internal fun deleteSigned(body: Map<String, Any?>): HttpResponse =
        call("POST", "/v1/contributions/delete", null, body)

    companion object {
        const val DEFAULT_API = "https://api.quranlab.ai"
        const val DEFAULT_CDN = "https://cdn.quranlab.ai"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

/**
 * An app token `qlt_v1.<payload>.<sig>` (24 h). The payload fields the SDK reads: `app`, `att`,
 * `exp`, `k`, `via` (`pi` for Play Integrity).
 */
internal class AppToken(val token: String, val expiresAt: Long, val attested: Boolean, val via: String?) {
    /** Renewed when under an hour is left. */
    fun fresh(nowSec: Long): Boolean = expiresAt - nowSec > 3600

    companion object {
        /** Reads a token string; the expiry from its payload (or [fallbackExp]). */
        @JvmStatic
        fun parse(token: String, fallbackExp: Long = 0, fallbackAttested: Boolean = false): AppToken {
            var exp = fallbackExp
            var att = fallbackAttested
            var via: String? = null
            try {
                val parts = token.split('.')
                @Suppress("UNCHECKED_CAST")
                val p = Json.parse(String(b64urlDecode(parts[1]), Charsets.UTF_8)) as Map<String, Any?>
                (p["exp"] as? Double)?.let { exp = it.toLong() }
                (p["att"] as? Boolean)?.let { att = it }
                via = p["via"] as? String
            } catch (_: Exception) {
            }
            return AppToken(token, exp, att, via)
        }

        internal fun from(r: HttpResponse): AppToken {
            val o = r.json() ?: throw CloudException(ApiError(r.status, "no_token"), "no token in the answer")
            val t = o["token"] as? String ?: throw CloudException(ApiError(r.status, "no_token"), "no token in the answer")
            val ttl = (o["expires_in"] as? Double)?.toLong() ?: 86_400
            return parse(t, System.currentTimeMillis() / 1000 + ttl, o["attested"] as? Boolean ?: false)
        }
    }
}
