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
    /** SDK 4.0.1: the `api` hosts every request goes through, in their failover order. */
    val endpoints: Endpoints = Endpoints.of(apiUrl),
    /** SDK 4.0.2: each route's `Retry-After` hold, kept across restarts; null: none kept. */
    val holds: RouteHolds? = null,
    /** Sees every answer of every route (the refusals that stop a model, SPEC 15.3, are remembered there). */
    private val observe: (HttpResponse) -> Unit = {},
) {
    /** Every request goes through here: the system's refusal of the network is an IOException like no connection. */
    private val http = HttpTransport { r ->
        ai.quranlab.tajweedformer.permitted { transport.send(r) }.also { a -> try { observe(a) } catch (_: Exception) {} }
    }

    private fun call(method: String, path: String, cred: Credential?, body: Map<String, Any?>? = null,
                     extra: Map<String, String> = emptyMap(), expectOk: Boolean = true, stage: String? = ErrorRules.stageOfPath(path),
                     check: (HttpResponse) -> Unit = JSON): HttpResponse {
        val h = LinkedHashMap<String, String>()
        cred?.apply(h)
        h.putAll(extra)
        val bytes = body?.let { Json.write(it).toByteArray(Charsets.UTF_8) }
        if (bytes != null) h["content-type"] = "application/json"
        val r = send(method, path, h, bytes, stage = stage, check = check)
        if (expectOk && !r.ok) throw CloudException(r.error, "$method $path: ${r.error}", path = path)
        return r
    }

    /**
     * One request through the `api` hosts (28.3): each host is asked with the same method, path, headers and body until
     * one answers with what the route asks for ([check], on a 2xx), a refusal (a 4xx other than 429 with a JSON `error`
     * string) or a slow down (a 429, never taken to another host). No answer, a 5xx, a redirect, a page that is not the
     * API's, or a 2xx [check] refuses ([NotTaken]) is unreachable, and the next host is asked at once. When every host
     * was unreachable the first host's answer is the request's (an [IOException] as a [CloudException]).
     */
    private fun send(method: String, path: String, h: Map<String, String>, bytes: ByteArray?, bodyFile: java.io.File? = null,
                     stage: String? = ErrorRules.stageOfPath(path), check: (HttpResponse) -> Unit = JSON): HttpResponse {
        lastFallback.set(false)
        // 28.11 item 2: a route a 429 (or a 503 with a Retry-After) held answers as it did, without a request, until the
        // hold ends; no host is asked
        val route = RouteHolds.routeOf(path)
        route?.let { holds?.held(it) }?.let { held ->
            val leftS = maxOf(1L, (held.untilMs - holds!!.now() + 999) / 1000)
            return HttpResponse(held.status, held.headers + ("retry-after" to leftS.toString()), held.body).also { r ->
                r.hostRole = ROLE
                r.fallback = false
            }
        }
        val got = endpoints.request(endpoints.api, stage) { _, origin ->
            val r = try {
                http.send(HttpRequest(method, origin + path, h, bytes, bodyFile))
            } catch (e: IOException) {
                // the system's refusal of the network, or a request the app's thread ended, is the same on every host
                val ended = e is ai.quranlab.tajweedformer.NetworkNotPermitted || HttpAbort.current()?.aborted == true
                if (ended) throw CloudException(null, "$method $path: ${e.message}", e, path).also { it.hostRole = ROLE; it.fallback = false }
                return@request HostAnswer.unreachable(null, e, if (ErrorRules.timedOut(e)) "timeout" else "network", null)
            }
            classify(r, check)
        }
        lastFallback.set(got.fallback)
        val a = got.answer
        a.value?.let { r ->
            r.hostRole = ROLE
            r.fallback = got.fallback
            if (got.by == null) r.retryAfterMax = got.retryAfterMax
            route?.let { try { holds?.answered(it, r) } catch (_: Exception) {} }
            return r
        }
        val e = a.failure!!
        if (e is NotTaken) throw e.failure ?: e
        throw CloudException(null, "$method $path: ${e.message}", e, path).also {
            it.hostRole = ROLE
            it.fallback = got.fallback
            it.retryAfterMax = got.retryAfterMax
        }
    }

    /** Whether the last request of this thread asked more than one host (28.7 `fallback`), also when it ended in a
     *  failure that is no request's (a lease the Core refused on every host it was asked of). */
    private val lastFallback = ThreadLocal<Boolean>()

    fun lastRequestFallback(): Boolean = lastFallback.get() == true

    /** One host's answer read by 28.3's table (18.4.7.1's). */
    private fun classify(r: HttpResponse, check: (HttpResponse) -> Unit): HostAnswer<HttpResponse> = when {
        r.status == 429 -> HostAnswer.ends(AnswerClass.SLOW_DOWN, r)
        r.status in 200..299 || r.status == 304 -> try {
            check(r)
            HostAnswer.ends(AnswerClass.TAKEN, r)
        } catch (x: NotTaken) {
            HostAnswer.unreachable(if (x.failure == null) r else null, x.failure?.let { x }, if (r.status == 304) "http" else "invalid_answer", r.status)
        }
        r.status in 300..399 -> HostAnswer.unreachable(r, null, "redirect", r.status)
        r.status in 400..499 && r.error.apiRefusal -> HostAnswer.ends(AnswerClass.REFUSED, r)
        else -> HostAnswer.unreachable(r, null, "http", r.status, r.retryAfter)
    }

    // section 2

    /** `GET /v1/attest/challenge` with the key: the challenge string. */
    fun challenge(key: QuranLabKey): String =
        call("GET", "/v1/attest/challenge", Credential.Key(key), check = has("challenge")).json()?.get("challenge") as? String
            ?: throw CloudException(ApiError(200, "no_challenge"), "no challenge in the answer")

    /** `POST /v1/attest` unattested: `{"platform","bundle_id"}`. */
    fun attest(key: QuranLabKey, app: AppIdentity): AppToken =
        AppToken.from(call("POST", "/v1/attest", Credential.Key(key), linkedMapOf("platform" to app.platform, "bundle_id" to app.bundleId), check = has("token")))

    /** `POST /v1/attest` with a Play Integrity token: `{"platform":"android","bundle_id","challenge","integrity_token"}`. */
    fun attestPlayIntegrity(key: QuranLabKey, app: AppIdentity, challenge: String, integrityToken: String): AppToken =
        AppToken.from(call("POST", "/v1/attest", Credential.Key(key), linkedMapOf(
            "platform" to app.platform, "bundle_id" to app.bundleId, "challenge" to challenge, "integrity_token" to integrityToken), check = has("token")))

    // SDK 4.0: the protected model

    /** `GET /v1/attest/challenge` with the app token: a challenge bound to the token's key (the lease's, 18.4.1). */
    fun challenge(token: String): String =
        call("GET", "/v1/attest/challenge", Credential.Token(token), stage = "lease", check = has("challenge")).json()?.get("challenge") as? String
            ?: throw CloudException(ApiError(200, "no_challenge"), "no challenge in the answer")

    /**
     * `POST /v1/attest` with the install keys (18.4.3): `platform`, `bundle_id`, `challenge`, `install_key`,
     * `install_sign_key`, `install_sig`, `install_hw`, and on Android `key_attestation`, `previous_cert`,
     * `integrity_token` when given. The raw answer: 200 carries `token` and `install_cert`.
     */
    fun attestInstall(key: QuranLabKey, body: Map<String, Any?>): HttpResponse =
        call("POST", "/v1/attest", Credential.Key(key), body, expectOk = false, check = has("token"))

    /**
     * `POST /v1/model-key` (18.4.4): the raw answer, a lease (200) or a refusal of 18.4.10. [accept] takes a 200 (a lease
     * the Core accepts, 28.3) or throws [NotTaken] for one that is not, which makes that host unreachable.
     */
    fun modelKey(token: String, body: Map<String, Any?>, accept: (HttpResponse) -> Unit = JSON): HttpResponse =
        call("POST", "/v1/model-key", Credential.Token(token), body, expectOk = false, check = { r -> if (r.status != 200) throw NotTaken() else accept(r) })

    // section 3

    /**
     * `GET /v1/config`. With a key, `platform` and `bundle_id` go in the query too. [etag] makes it
     * conditional (304: no body). [modelVersion] is the app's pin (`model_version=x.y.z`): the server answers that
     * version's build for this SDK, or its normal choice plus `pin.status: unavailable`. Returns the raw response for
     * [ConfigStore] to verify.
     */
    @JvmOverloads
    fun config(cred: Credential, bucket: Int, app: AppIdentity, etag: String?, modelVersion: String? = null,
               verify: (HttpResponse) -> Unit = JSON): HttpResponse {
        val q = StringBuilder("?bucket=$bucket&sdk=${enc(SdkInfo.VERSION)}")
        if (cred is Credential.Key) q.append("&platform=${enc(app.platform)}&bundle_id=${enc(app.bundleId)}")
        if (modelVersion != null) q.append("&model_version=${enc(modelVersion)}")
        // SDK 4.0.1: the app's version and build, read once per Client, left out when unknown
        app.configVersion?.let { q.append("&app_version=${enc(it)}") }
        app.configBuild?.let { q.append("&app_build=${enc(it)}") }
        val extra = if (etag != null) mapOf("if-none-match" to etag) else emptyMap()
        // taken (28.3): a 200 config that verifies ([verify] throws NotTaken for one that does not), or a 304 to a request
        // with If-None-Match
        val r = call("GET", "/v1/config$q", cred, extra = extra, expectOk = false,
            check = { a -> if (a.status == 304) { if (etag == null) throw NotTaken() } else if (a.status != 200) throw NotTaken() else verify(a) })
        if (r.status != 200 && r.status != 304) throw CloudException(r.error, "GET /v1/config: ${r.error}", path = "/v1/config")
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
        return send(method, path, h, body)
    }

    // section 10

    /** `POST /v1/uploads` with the session JSON; the answer's URLs. */
    fun uploadStart(token: String, id: String, session: ByteArray): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "content-type" to "application/json", "x-qlab-id" to id)
        return send("POST", "/v1/uploads", h, session)
    }

    /** `POST /v1/uploads/{id}/urls?from=n` with `x-qlab-contributor`. */
    fun uploadUrls(token: String, id: String, from: Int, contributor: String): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "x-qlab-contributor" to contributor)
        return send("POST", "/v1/uploads/$id/urls?from=$from", h, null)
    }

    /**
     * `POST /v1/uploads/{id}/takes` with `x-qlab-contributor` (SDK 2.0.3): the take boundaries of a recording of two or
     * more takes, `{"takes":[{"start_ms","gap_ms"},...]}`, after its last segment. Idempotent: the server replaces the list.
     */
    fun uploadTakes(token: String, id: String, contributor: String, body: ByteArray): HttpResponse {
        val h = linkedMapOf("authorization" to "Bearer $token", "content-type" to "application/json", "x-qlab-contributor" to contributor)
        return send("POST", "/v1/uploads/$id/takes", h, body)
    }

    /** A plain PUT of one segment to its presigned URL: `content-type: audio/ogg` and nothing else to sign. The URL is the
     *  storage's, no host of section 28: asked there only. */
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
        /** The first `api` host's origin (28.1); a test build passes a stand-in's URL instead. */
        const val DEFAULT_API = "https://api.quranlab.ai"

        /** The role of every request of this class (28.7 `host_role`). */
        const val ROLE = "api"

        /** Taken (28.3): a 2xx whose body is a JSON object, or empty; a 304 answers no request of these routes. */
        val JSON: (HttpResponse) -> Unit = { r -> if (r.status == 304 || (r.body.isNotEmpty() && r.json() == null)) throw NotTaken() }

        /** Taken: a 2xx whose JSON object carries the string [field] (a challenge, a token). */
        fun has(field: String): (HttpResponse) -> Unit = { r -> if (r.status == 304 || r.json()?.get(field) !is String) throw NotTaken() }

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

        @JvmStatic
        internal fun from(r: HttpResponse): AppToken {
            val o = r.json() ?: throw CloudException(ApiError(r.status, "no_token"), "no token in the answer")
            val t = o["token"] as? String ?: throw CloudException(ApiError(r.status, "no_token"), "no token in the answer")
            val ttl = (o["expires_in"] as? Double)?.toLong() ?: 86_400
            return parse(t, System.currentTimeMillis() / 1000 + ttl, o["attested"] as? Boolean ?: false)
        }
    }
}
