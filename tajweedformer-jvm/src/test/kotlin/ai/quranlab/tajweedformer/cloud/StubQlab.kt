package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.sharing.ContributorKeys
import ai.quranlab.tajweedformer.sharing.FileContributorKeyStore
import ai.quranlab.tajweedformer.sharing.PlainSecretBox
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A local stand-in for qlab-api and R2 on 127.0.0.1 (never a production host), following qlab-api's
 * closed schemas (an unknown field is 400 `invalid_body` naming it), its config signing (Ed25519 over
 * the exact body, key id, ETag), its tokens, presigned segment URLs, the device-key deletion and the
 * telemetry v2 schema. Records every request; any request with an `Origin` header is refused as an
 * `apps` key's would be.
 */
class StubQlab : AutoCloseable {
    class Seen(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray) {
        @Suppress("UNCHECKED_CAST")
        val json: Map<String, Any?>? get() = try { Json.parse(String(body, Charsets.UTF_8)) as? Map<String, Any?> } catch (_: Exception) { null }
        fun q(name: String): String? = query.split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }?.getOrNull(1)
    }

    class Reply(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun json(status: Int, o: Any?) = Reply(status, Json.write(o).toByteArray(), mapOf("content-type" to "application/json"))
            fun error(status: Int, code: String) = json(status, mapOf("error" to code, "message" to code))
        }
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())

    // the signing key of the config
    private val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey: ByteArray = ed.public.encoded.copyOfRange(ed.public.encoded.size - 32, ed.public.encoded.size)
    val keyId: String = RemoteConfig.keyId(publicKey)

    val key = "qlk_v1_testkey01_" + "A".repeat(22)

    // what the stub serves
    /** The portal's 2.0.1 change (platform client doc 7.1): incident reports may carry `message` and `detail`. Off, a
     *  2.0.0 server: such a body is a 400. */
    @Volatile var incidentsAccepted = false
    @Volatile var flags: Map<String, Any?> = mapOf("analytics" to true, "issues" to true, "contributions" to true, "upload_trust" to mapOf("android" to "attested"))
    /** More top-level fields of the signed config (`sdk_latest`, `accel_deny`, fields no SDK knows). */
    @Volatile var extra: Map<String, Any?> = emptyMap()
    @Volatile var tajweed: Map<String, Any?>? = mapOf("v" to 1.0, "preset" to "sensitive")
    @Volatile var expiresIn = 86_400L
    @Volatile var tamper = false
    @Volatile var down = false
    /** A request this says true for gets no answer (an IOException), as [down] does for every request. */
    @Volatile var offlineFor: ((HttpRequest) -> Boolean)? = null
    @Volatile var batch = 20
    /** The 2.0.3 server's `POST /v1/uploads/{id}/takes`. Off: a server without the route (404 `not_found`). */
    @Volatile var takesRoute = true
    /** The take list stored per upload id (the last one sent replaces the earlier). */
    val takes: MutableMap<String, List<Map<String, Any?>>> = Collections.synchronizedMap(HashMap())
    /** Uploads deleted on the server (410 `upload_deleted` to a take list). */
    val deletedUploads: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /**
     * Milliseconds added to the clock of the clients made here: a test moves time on (past the join window of a
     * shared recording, say) without sleeping.
     */
    @Volatile var skewMs = 0L
    /** The clock of the clients made here. */
    val clock: () -> Long = { System.currentTimeMillis() + skewMs }
    /** The integrity token Play would give for a request hash (the stub's "Google" accepts only this). */
    fun integrityToken(hash: String) = "pi.$hash"
    @Volatile var integrityRefuses = false
    /** Per-route overrides: return a reply to take over (by request count of that route, 1-based). */
    val overrides: MutableMap<String, (Int, Seen) -> Reply?> = Collections.synchronizedMap(HashMap())

    val objects: MutableMap<String, ByteArray> = Collections.synchronizedMap(sortedMapOf())
    private val bound = Collections.synchronizedMap(HashMap<String, String>())
    val revoked: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val nonces = Collections.synchronizedSet(HashSet<String>())
    private var batches = 0
    private var tokenN = 0
    val attestedTokens: MutableSet<String> = Collections.synchronizedSet(HashSet())

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    override fun close() = server.stop(0)

    fun seen(path: String) = seen.filter { it.path == path }
    val puts: List<Seen> get() = seen.filter { it.method == "PUT" }
    fun starts() = seen.filter { it.method == "POST" && it.path == "/v1/uploads" }
    fun urlCalls(id: String) = seen.filter { it.path == "/v1/uploads/$id/urls" }.mapNotNull { it.q("from")?.toInt() }
    /** Every take list call for an upload, in order. */
    fun takeCalls(id: String) = seen.filter { it.method == "POST" && it.path == "/v1/uploads/$id/takes" }
    fun segments(id: String) = objects.filterKeys { it.startsWith("v1/$id/seg-") }.toSortedMap().values.toList()

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.last() }
        val s = Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery ?: "", headers, body)
        seen.add(s)
        val r = try { route(s) } catch (e: Exception) { Reply.error(500, "stub_error: ${e.message}") }
        for ((k, v) in r.headers) ex.responseHeaders.add(k, v)
        if (r.status == 304) ex.sendResponseHeaders(304, -1) else {
            ex.sendResponseHeaders(r.status, if (r.body.isEmpty()) -1 else r.body.size.toLong())
            if (r.body.isNotEmpty()) ex.responseBody.use { it.write(r.body) }
        }
        ex.close()
    }

    private fun count(path: String) = seen.count { it.path == path }

    private fun route(s: Seen): Reply {
        if (s.headers.containsKey("origin")) return Reply.error(403, "key_type_mismatch")
        overrides[s.path]?.invoke(count(s.path), s)?.let { return it }
        if (s.path.startsWith("/r2/")) return r2(s)
        return when ("${s.method} ${s.path}") {
            "GET /v1/attest/challenge" -> needKey(s) ?: Reply.json(200, mapOf("challenge" to "ch-${count(s.path)}", "expires_in" to 300))
            "POST /v1/attest" -> attest(s)
            "GET /v1/config" -> config(s)
            "POST /v1/register" -> needToken(s) ?: closed(s, setOf("platform", "bundle_id", "app_name", "app_version", "sdk_version", "model_revision", "new_install"))
                ?: Reply.json(200, mapOf("ok" to true, "app_id" to "android:org.example.recite", "store_status" to "unlisted"))
            "POST /v1/events" -> needToken(s) ?: events(s)
            "POST /v1/issues" -> needToken(s) ?: closed(s, setOf("day", "platform", "sdk_version", "app_version", "os_version", "device_class", "backend", "revision", "kind", "code", "frames") +
                (if (incidentsAccepted && s.json?.get("kind") in setOf("accelerator_failover", "promotion_failed")) setOf("message", "detail") else emptySet()))
                ?: Reply.json(202, mapOf("ok" to true))
            "POST /v1/uploads" -> uploadStart(s)
            "POST /v1/contributions/challenge" -> noCredential(s) ?: closed(s, setOf("app", "contributor"))
                ?: Reply.json(200, mapOf("nonce" to "d1.${System.currentTimeMillis() / 1000 + 300}.${"n".repeat(18)}${count(s.path).toString().padStart(4, '0')}.${"s".repeat(22)}", "expires_in" to 300))
            "POST /v1/contributions/delete" -> delete(s)
            "GET /v1/contributions/summary" -> needToken(s) ?: Reply.json(200, mapOf("you" to mapOf("sessions" to 3, "minutes" to 12.4), "app" to null, "all" to mapOf("contributors" to 120, "minutes" to 5000.0)))
            else -> when {
                s.method == "POST" && s.path.matches(Regex("^/v1/uploads/[0-9a-f]{32}/urls$")) -> uploadUrls(s)
                takesRoute && s.method == "POST" && s.path.matches(Regex("^/v1/uploads/[0-9a-f]{32}/takes$")) -> uploadTakes(s)
                else -> Reply.error(404, "not_found")
            }
        }
    }

    private fun needKey(s: Seen): Reply? = if (s.headers["x-qlab-key"] == key) null else Reply.error(401, "invalid_key")

    private fun tokenOf(s: Seen): String? = s.headers["authorization"]?.removePrefix("Bearer ")?.takeIf { it.startsWith("qlt_v1.") }

    private fun needToken(s: Seen): Reply? {
        if (s.headers["x-qlab-key"] != null && s.headers["authorization"] != null) return Reply.error(400, "invalid_request")
        val t = tokenOf(s) ?: return if (s.headers["x-qlab-key"] == key) null else Reply.error(401, "unauthorized")
        return if (t.endsWith(".revoked")) Reply.error(401, "invalid_token") else null
    }

    private fun noCredential(s: Seen): Reply? =
        if (s.headers["x-qlab-key"] != null || s.headers["authorization"] != null) Reply.error(403, "credential_not_accepted") else null

    /** Closed schema: any field outside [allowed] is 400 naming it. */
    private fun closed(s: Seen, allowed: Set<String>): Reply? {
        val o = s.json ?: return Reply.error(400, "invalid_json")
        val bad = o.keys.firstOrNull { it !in allowed } ?: return null
        return Reply.json(400, mapOf("error" to "invalid_body", "message" to "json.$bad: unknown field"))
    }

    private fun token(attested: Boolean): String {
        val payload = linkedMapOf<String, Any?>("app" to "android:org.example.recite", "att" to attested, "exp" to System.currentTimeMillis() / 1000 + 86_400, "k" to "testkey01")
        if (attested) payload["via"] = "pi"
        val t = "qlt_v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(payload).toByteArray()) + ".sig${++tokenN}"
        if (attested) attestedTokens.add(t)
        return t
    }

    private fun attest(s: Seen): Reply {
        needKey(s)?.let { return it }
        val o = s.json ?: return Reply.error(400, "invalid_json")
        closed(s, setOf("platform", "bundle_id", "challenge", "integrity_token", "team_id", "key_id", "attestation"))?.let { return it }
        val it = o["integrity_token"] as? String ?: return Reply.json(200, mapOf("token" to token(false), "expires_in" to 86_400, "attested" to false))
        if (o["platform"] != "android" || o["challenge"] == null) return Reply.error(400, "invalid_body")
        if (integrityRefuses) return Reply.error(401, "attestation_failed")
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256((o["challenge"] as String).toByteArray()))
        if (it != integrityToken(hash)) return Reply.error(401, "attestation_failed")
        return Reply.json(200, mapOf("token" to token(true), "expires_in" to 86_400, "attested" to true))
    }

    private fun configBody(issuedAt: Long): Map<String, Any?> {
        val b = linkedMapOf<String, Any?>("v" to 1, "app" to "android:org.example.recite", "channel" to "stable", "issued_at" to issuedAt,
            "expires_at" to issuedAt + expiresIn, "models" to emptyMap<String, Any?>(),
            "recommended" to mapOf("model" to "Quran-Lab/tajweedformer-v4", "model_id" to "v4.0", "folder" to "sdk/v4.0", "revision" to "v4.0+abc", "source" to "cdn"),
            "denylist" to listOf(mapOf("platform" to "android", "device_class" to "SM-A1*", "backend_forced" to "exact", "reason" to "npu bug")),
            "flags" to flags)
        tajweed?.let { b["tajweed"] = it }
        b.putAll(extra)
        return b
    }

    private fun config(s: Seen): Reply {
        needToken(s)?.let { return it }
        if (s.q("sdk") != ai.quranlab.tajweedformer.TajweedFormer.VERSION) return Reply.error(400, "invalid_query")
        if (tokenOf(s) == null && (s.q("platform") == null || s.q("bundle_id") == null)) return Reply.error(400, "invalid_query")
        val now = System.currentTimeMillis() / 1000
        val content = Json.write(configBody(0).filterKeys { it != "issued_at" && it != "expires_at" })
        val etag = "\"" + sha256(content.toByteArray()).copyOf(16).hex() + "\""
        if (s.headers["if-none-match"] == etag) return Reply(304)
        val body = Json.write(configBody(now)).toByteArray()
        val sig = Signature.getInstance("Ed25519").apply { initSign(ed.private); update(body) }.sign()
        val sent = if (tamper) body.copyOf().also { it[it.size - 2] = '0'.code.toByte() } else body
        return Reply(200, sent, mapOf("content-type" to "application/json", "x-qlab-signature" to "ed25519=" + Base64.getUrlEncoder().withoutPadding().encodeToString(sig),
            "x-qlab-key-id" to keyId, "etag" to etag, "cache-control" to "private, max-age=300"))
    }

    private val hist = mapOf(
        "fallbacks" to setOf("non_finite", "deadline", "ane_unavailable", "npu_unavailable", "model_load", "thermal", "memory", "other"),
        "latency_ms" to setOf("le_5", "le_10", "le_20", "le_40", "le_80", "le_160", "le_320", "gt_320"),
        "rtf" to setOf("le_0_1", "le_0_25", "le_0_5", "le_1", "gt_1"),
        "cold_start_ms" to setOf("le_250", "le_500", "le_1000", "le_2000", "le_4000", "gt_4000"),
        "model_load_ms" to setOf("le_250", "le_500", "le_1000", "le_2000", "le_4000", "gt_4000"),
        "checks_on" to setOf("letters", "vowels", "length", "madd", "gemination", "qalqalah", "lazim"),
        "session_flag_pct" to setOf("le_1", "le_2", "le_5", "le_10", "le_20", "gt_20"),
        "margin" to setOf("le_0_5", "le_1", "le_2", "le_4", "le_8", "gt_8"),
        "confidence" to setOf("le_0_5", "le_0_7", "le_0_8", "le_0_9", "le_0_95", "gt_0_95"),
        "self_test" to setOf("pass", "fail"), "thermal" to setOf("transitions", "throttled_sessions"),
        "madd" to setOf("c2", "c4", "c6", "flagged"), "words" to setOf("scored", "flagged"),
    )

    /** A JSON body as the stub reads it. */
    @Suppress("UNCHECKED_CAST")
    fun parse(b: ByteArray): Map<String, Any?> = Json.parse(String(b, Charsets.UTF_8)) as Map<String, Any?>

    /** The `report_id`s recorded (a repeat is answered 202 duplicate and writes nothing). */
    val reports: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Telemetry v2's closed schema (qlab-api src/events2.js), with the 2.0.1 fields. */
    @Suppress("UNCHECKED_CAST")
    private fun events(s: Seen): Reply {
        if (s.body.size > 32 * 1024) return Reply.error(413, "too_large")
        val o = s.json ?: return Reply.error(400, "invalid_json")
        fun bad(why: String) = Reply.json(400, mapOf("error" to "invalid_body", "message" to why))
        val top = setOf("v", "day", "product", "platform", "sdk_version", "app_version", "device", "cohorts", "coverage", "downloads",
            "report_id", "launches", "ayahs", "surah_ayahs", "words", "rule_checks", "rule_flags", "share_asked", "share_yes", "share_no")
        o.keys.firstOrNull { it !in top }?.let { return bad("json.$it: unknown field") }
        val rid = o["report_id"] as? String
        if (o.containsKey("report_id") && (rid == null || !Regex("^[0-9a-f]{32}$").matches(rid))) return bad("report_id")
        for (k in listOf("launches", "ayahs", "words", "share_asked", "share_yes", "share_no")) {
            if (o.containsKey(k)) { val n = o[k] as? Double ?: return bad(k); if (n < 0 || n > 10_000_000 || n != Math.floor(n)) return bad(k) }
        }
        (o["surah_ayahs"] as? Map<String, Any?>)?.keys?.firstOrNull { (it.toIntOrNull() ?: 0) !in 1..114 }?.let { return bad("surah_ayahs.$it") }
        val checks = (o["rule_checks"] as? Map<String, Any?>) ?: emptyMap()
        val flags = (o["rule_flags"] as? Map<String, Any?>) ?: emptyMap()
        if (checks.size > 32 || flags.size > 32) return bad("rule_checks: at most 32 keys")
        for (k in checks.keys + flags.keys) if (!Regex("^[a-z][a-z0-9_]{0,23}$").matches(k)) return bad("rule key $k")
        for ((k, f) in flags) {
            val c = checks[k] as? Double ?: return bad("rule_flags.$k not in rule_checks")
            if ((f as Double) > c) return bad("rule_flags.$k > rule_checks.$k")
        }
        if (o["v"] != 2.0 || o["product"] != "android" || o["platform"] != "android") return bad("v/product/platform")
        val dev = o["device"] as Map<String, Any?>
        dev.keys.firstOrNull { it !in setOf("class", "soc", "os_major", "ram_tier") }?.let { return bad("json.device.$it") }
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,23}$").matches(dev["class"] as String)) return bad("device.class")
        val cohorts = o["cohorts"] as List<Map<String, Any?>>
        if (cohorts.size > 16) return bad("cohorts: at most 16")
        val ck = setOf("revision", "context", "backend", "preset", "word_level", "word_bucket", "site_overrides", "sessions", "minutes", "chunks",
            "aborted", "classes") + hist.keys
        for (c in cohorts) {
            c.keys.firstOrNull { it !in ck }?.let { return bad("json.cohorts.$it") }
            if (!Regex("^c\\d{1,3}_l\\d{1,4}$").matches(c["context"] as String)) return bad("context")
            if (!Regex("^(cpu|ane|gpu|npu:[A-Za-z0-9][A-Za-z0-9_.-]{0,23})$").matches(c["backend"] as String)) return bad("backend")
            if (c["preset"] !in setOf("strict", "balanced", "sensitive", "very_sensitive", "custom")) return bad("preset")
            if (c["word_level"] !in setOf("strict", "general", "beginner", "custom", "off")) return bad("word_level")
            for ((h, names) in hist) (c[h] as? Map<String, Any?>)?.keys?.firstOrNull { it !in names }?.let { return bad("json.cohorts.$h.$it") }
            (c["classes"] as? Map<String, Any?>)?.keys?.firstOrNull { it !in ai.quranlab.tajweedformer.cloud.Telemetry.CLASSES }?.let { return bad("classes.$it") }
        }
        (o["coverage"] as? Map<String, Any?>)?.keys?.firstOrNull { (it.toIntOrNull() ?: 0) !in 1..114 }?.let { return bad("coverage.$it") }
        if (((o["downloads"] as? List<*>)?.size ?: 0) > 8) return bad("downloads")
        val day = java.time.LocalDate.parse(o["day"] as String)
        if (day.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(7))) return bad("day: older than 7 days")
        if (rid != null && !reports.add(rid)) return Reply.json(202, mapOf("ok" to true, "points" to 0, "duplicate" to true))
        return Reply.json(202, mapOf("ok" to true, "points" to cohorts.size * 7))
    }

    private fun urls(id: String, from: Int, status: Int): Reply {
        val b = synchronized(this) { ++batches }
        val exp = System.currentTimeMillis() / 1000 + 604_800
        val list = (from until from + batch).map { n ->
            mapOf("n" to n, "expires_at" to exp, "url" to "$base/r2/qlab-contributions/v1/$id/seg-%05d.opus?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=604800&X-Amz-SignedHeaders=content-type%%3Bhost&X-Amz-Signature=b$b-$n".format(n))
        }
        return Reply.json(status, mapOf("id" to id, "urls" to list))
    }

    @Suppress("UNCHECKED_CAST")
    private fun uploadStart(s: Seen): Reply {
        needToken(s)?.let { return it }
        val t = tokenOf(s) ?: return Reply.error(401, "unauthorized")
        if (flags["contributions"] == false) return Reply.error(403, "contributions_disabled")
        val trust = (flags["upload_trust"] as? Map<String, Any?>)?.get("android") ?: "attested"
        if (trust == "attested" && t !in attestedTokens) return Reply.error(403, "attestation_required")
        val id = s.headers["x-qlab-id"] ?: ""
        if (!Regex("^[0-9a-f]{32}$").matches(id)) return Reply.error(400, "invalid_body")
        // consent_text_sha256: the 2.0.1 server's field (custom wording), 64 lower-case hex next to a custom- version
        closed(s, setOf("format", "audio", "decode", "consent_version", "contributor", "contributor_key", "recovery_hash", "day", "device_class", "os_major",
            "consent_text_sha256"))?.let { return it }
        val o = s.json!!
        (o["consent_text_sha256"] as? String)?.let { h ->
            if (!Regex("^[0-9a-f]{64}$").matches(h) || o["consent_version"] != "custom-" + h.take(24)) return Reply.error(400, "invalid_body")
        }
        (o["audio"] as Map<String, Any?>).keys.firstOrNull { it !in setOf("codec", "container", "sample_rate", "channels", "input_sample_rate", "input_route") }
            ?.let { return Reply.json(400, mapOf("error" to "invalid_body", "message" to "json.audio.$it")) }
        (o["decode"] as Map<String, Any?>).keys.firstOrNull { it !in setOf("model_revision", "backend", "weights_sha256", "sdk_version") }
            ?.let { return Reply.json(400, mapOf("error" to "invalid_body", "message" to "json.decode.$it")) }
        val c = o["contributor"] as String
        val k = o["contributor_key"] as? String
        if (c in revoked) return Reply.error(403, "contributor_revoked")
        val prev = bound[c]
        if (prev != null && prev != k) return Reply.error(409, "contributor_key_mismatch")
        if (prev == null && k != null) bound[c] = k
        val again = starts().count { it.headers["x-qlab-id"] == id } > 1
        return urls(id, 1, if (again) 200 else 201)
    }

    private fun uploadUrls(s: Seen): Reply {
        needToken(s)?.let { return it }
        if (!Regex("^[0-9a-f]{32}$").matches(s.headers["x-qlab-contributor"] ?: "")) return Reply.error(400, "invalid_body")
        val id = s.path.removePrefix("/v1/uploads/").take(32)
        return urls(id, s.q("from")?.toIntOrNull() ?: 0, 200)
    }

    /** Milliseconds of audio the stub holds for an upload: the last page's granule position less the pre-skip, at 48 kHz. */
    fun receivedMs(id: String): Long {
        val d = segments(id).fold(ByteArray(0)) { a, b -> a + b }
        var at = 0
        var granule = 0L
        var preSkip = 0L
        var page = 0
        while (at + 27 <= d.size) {
            val n = d[at + 26].toInt() and 0xff
            var len = 0
            for (i in 0 until n) len += d[at + 27 + i].toInt() and 0xff
            if (page == 0) preSkip = ((d[at + 27 + n + 10].toInt() and 0xff) or ((d[at + 27 + n + 11].toInt() and 0xff) shl 8)).toLong()
            var g = 0L
            for (i in 0 until 8) g = g or ((d[at + 6 + i].toLong() and 0xff) shl (8 * i))
            granule = g
            at += 27 + n + len
            page++
        }
        return maxOf(0L, granule - preSkip) / 48
    }

    /**
     * `POST /v1/uploads/{id}/takes` (SDK 2.0.3) as the server checks it: the token and `x-qlab-contributor`, the upload
     * known under that contributor (404 `not_found` otherwise), 410 for a deleted upload, a closed body
     * `{"takes":[{"start_ms","gap_ms"}]}` of 2 to 256 entries, the first `{0, 0}`, `start_ms` whole and strictly
     * increasing and no further than the received audio plus 1000 ms, `gap_ms` 0 to 10000 in hundreds. The list
     * replaces any earlier one; 204.
     */
    @Suppress("UNCHECKED_CAST")
    private fun uploadTakes(s: Seen): Reply {
        needToken(s)?.let { return it }
        if (tokenOf(s) == null) return Reply.error(401, "unauthorized")
        val who = s.headers["x-qlab-contributor"] ?: ""
        if (!Regex("^[0-9a-f]{32}$").matches(who)) return Reply.error(400, "invalid_body")
        val id = s.path.removePrefix("/v1/uploads/").take(32)
        if (id in deletedUploads) return Reply.error(410, "upload_deleted")
        val start = starts().firstOrNull { it.headers["x-qlab-id"] == id } ?: return Reply.error(404, "not_found")
        if (start.json?.get("contributor") != who) return Reply.error(404, "not_found")
        if (s.body.size > 16 * 1024) return Reply.error(413, "too_large")
        closed(s, setOf("takes"))?.let { return it }
        fun bad(why: String) = Reply.json(400, mapOf("error" to "invalid_body", "message" to why))
        val list = s.json!!["takes"] as? List<*> ?: return bad("json.takes")
        if (list.size !in 2..256) return bad("json.takes: 2 to 256 entries")
        val received = receivedMs(id)
        var last = -1.0
        for ((i, x) in list.withIndex()) {
            val o = x as? Map<String, Any?> ?: return bad("json.takes[$i]")
            o.keys.firstOrNull { it != "start_ms" && it != "gap_ms" }?.let { return bad("json.takes[$i].$it: unknown field") }
            val at = o["start_ms"] as? Double ?: return bad("json.takes[$i].start_ms")
            val gap = o["gap_ms"] as? Double ?: return bad("json.takes[$i].gap_ms")
            if (at != Math.floor(at) || gap != Math.floor(gap)) return bad("json.takes[$i]: whole numbers")
            if (i == 0 && (at != 0.0 || gap != 0.0)) return bad("json.takes[0]: 0, 0")
            if (i > 0 && at <= last) return bad("json.takes[$i].start_ms: not increasing")
            if (at > received + 1000) return bad("json.takes[$i].start_ms: past the received audio ($received ms)")
            if (gap < 0 || gap > 10_000 || gap % 100 != 0.0) return bad("json.takes[$i].gap_ms")
            last = at
        }
        takes[id] = list as List<Map<String, Any?>>
        return Reply(204)
    }

    private fun r2(s: Seen): Reply {
        if (s.method != "PUT") return Reply.error(405, "method")
        if (s.headers["content-type"] != "audio/ogg") return Reply(403, "<Error><Code>SignatureDoesNotMatch</Code></Error>".toByteArray())
        val key = s.path.removePrefix("/r2/qlab-contributions/")
        objects[key] = s.body
        return Reply(200)
    }

    private fun delete(s: Seen): Reply {
        noCredential(s)?.let { return it }
        closed(s, setOf("app", "contributor", "nonce", "timestamp", "action", "signature"))?.let { return it }
        val o = s.json!!
        val nonce = o["nonce"] as String
        if (!nonces.add(nonce)) return Reply.error(401, "nonce_invalid")
        val msg = ContributorKeys.deletionMessage(o["app"] as String, o["contributor"] as String, nonce, (o["timestamp"] as Double).toLong(), o["action"] as String)
        val k = bound[o["contributor"] as String]
        if (k != null && verify(k, msg.toByteArray(), Base64.getUrlDecoder().decode(o["signature"] as String))) {
            revoked.add(o["contributor"] as String)
            val ids = starts().filter { it.json?.get("contributor") == o["contributor"] }.mapNotNull { it.headers["x-qlab-id"] }.toSet()
            objects.keys.removeIf { key -> ids.any { key.startsWith("v1/$it/") } }
            deletedUploads.addAll(ids)
            deletions++
        }
        return Reply.json(202, mapOf("ok" to true))
    }

    @Volatile var deletions = 0

    private fun verify(pointB64: String, msg: ByteArray, sig: ByteArray): Boolean {
        val p = Base64.getUrlDecoder().decode(pointB64)
        val ap = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val spec = ap.getParameterSpec(ECParameterSpec::class.java)
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, p.copyOfRange(1, 33)), BigInteger(1, p.copyOfRange(33, 65))), spec))
        return try { Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(msg) }.verify(sig) } catch (_: Exception) { false }
    }

    // ---- a configured client against this stub ----

    fun environment(storage: File, scheduler: CloudScheduler = CloudScheduler.Manual, integrity: IntegrityProvider? = IntegrityProvider { integrityToken(it) },
                    clock: () -> Long = this.clock, device: DeviceProfile = DeviceProfile("Pixel8", "tensor_g3", 15, "15", "8_16"),
                    debugBuild: Boolean = false, log: (String) -> Unit = {}) =
        CloudEnvironment(storage, AppIdentity("org.example.recite", "3.1", "Recite"), device, FileContributorKeyStore(File(storage, "keys")),
            PlainSecretBox, integrity, scheduler, transport, clock, { "built_in" }, { "en-US" }, debugBuild, log = log)

    /** The client's network: offline (an IOException, no answer) while [down]. */
    val transport: HttpTransport = UrlConnectionTransport(5_000, 10_000).let { real ->
        HttpTransport { r -> if (down || offlineFor?.invoke(r) == true) throw java.io.IOException("offline") else real.send(r) }
    }

    fun cloud(storage: File, options: CloudOptions = CloudOptions(key, contributions = true, apiUrl = base, trustedKeys = listOf(publicKey)),
              scheduler: CloudScheduler = CloudScheduler.Manual, integrity: IntegrityProvider? = IntegrityProvider { integrityToken(it) },
              clock: () -> Long = this.clock) =
        QuranLabCloud(options, environment(storage, scheduler, integrity, clock))
}
