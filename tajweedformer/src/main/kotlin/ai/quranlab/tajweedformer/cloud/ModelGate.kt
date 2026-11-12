package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ModelAuth
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedError
import java.io.File

/**
 * SPEC 15.3: which answers of Quran Lab's server stop a model on the device. An answer stops a model exactly when its
 * JSON body carries a `reason` that is in [STOP_REASONS]; an answer without a reason never does, whatever its status
 * and its `error`. The list is the server's (vector `model_gate.json`, `stop_reasons`): this constant is the one place.
 */
internal object ModelGate {
    /** The reasons that stop a model on the device. */
    val STOP_REASONS: Set<String> = setOf("key_revoked", "key_unknown", "app_not_allowed", "origin_not_allowed", "key_type_mismatch",
        "app_blocked", "developer_not_approved")

    /** The stop reasons whose error is `invalid_key` (the others are `server_rejected` with the reason's words). */
    val KEY_REASONS: Set<String> = setOf("key_revoked", "key_unknown")

    /** Whether an answer whose body carries [reason] stops a model. */
    fun stops(reason: String?): Boolean = reason != null && reason in STOP_REASONS

    /** The error `client.model()` and `prefetch` fail with while a refusal for [reason] holds. */
    fun error(reason: String, appId: String?): TajweedError =
        if (reason in KEY_REASONS) RecognizerException.InvalidKey() else RecognizerException.ServerRejected(reason, appId)

    /**
     * The signed config's flag with Quran Lab's Google Cloud project number for Play Integrity (SPEC 15.4): a string of
     * decimal digits. Without it the SDK asks Play for no integrity token at all.
     */
    const val PLAY_INTEGRITY_FLAG = "play_integrity_project_number"
}

/**
 * The refusal that stops a model, remembered on the device (`<storage>/cloud/refused.json`), so a launch without a
 * network after a refusal does not run the model (SPEC 15.3). [from] says where it came from: `api` (a config,
 * register, token or other API answer) or `cdn` (a model request).
 *
 * - A refusal of the API holds until a later answer of the API accepts the key (a config, register or token answer
 *   that succeeds), which clears it.
 * - A refusal of a model request is not cleared by an API answer (it would be gone at the next registration). Once
 *   the API has accepted the key with a fresh config, [askCdnAgain] is set: the next model call asks the CDN again,
 *   and a model request the CDN serves clears it.
 *
 * Never on a network failure, a timeout, a 5xx, a 429 or an answer that does not verify: none of them reaches here.
 *
 * Every stop also removes the models from the device: [onStop] runs in the call that received the
 * refusal, before that call returns the refusal's error.
 */
internal class Refusals(private val dir: File) {
    class Stop(val reason: String, val from: String)

    /** Removes every model build from the device (the Client's); called at every stop, never throws. */
    @Volatile var onStop: (() -> Unit)? = null

    private val file get() = File(dir, "refused.json")
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var stop: Stop? = null

    /** Set when the API accepted the key with a fresh config after a refusal of a model request. */
    @Volatile var askCdnAgain = false
        private set

    /** The refusal in force, or null. */
    val current: Stop?
        get() {
            if (!loaded) synchronized(lock) {
                if (!loaded) {
                    stop = try {
                        val o = Json.parse(file.readText()) as? Map<*, *>
                        val r = o?.get("reason") as? String
                        val f = o?.get("from") as? String
                        if (r != null && ModelGate.stops(r) && (f == API || f == CDN)) Stop(r, f) else null
                    } catch (_: Exception) {
                        null
                    }
                    loaded = true
                }
            }
            return stop
        }

    /**
     * An answer carried a stop reason: it is remembered (also when the file cannot be written: then for this process),
     * and the models are removed from the device ([onStop]).
     */
    fun stop(reason: String, from: String) {
        synchronized(lock) {
            stop = Stop(reason, from)
            loaded = true
            askCdnAgain = false
            try {
                dir.mkdirs()
                val tmp = File(dir, "refused.json.tmp")
                tmp.writeText(Json.write(linkedMapOf("reason" to reason, "from" to from)))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            } catch (_: Exception) {
            }
        }
        try { onStop?.invoke() } catch (_: Exception) {}
    }

    /** An answer of the API accepted the key: a refusal of the API is cleared; [freshConfig]: a verified config. */
    fun apiAccepted(freshConfig: Boolean) = synchronized(lock) {
        val s = current ?: return@synchronized
        if (s.from == API) clear() else if (freshConfig) askCdnAgain = true
    }

    /** The CDN served a model request: a refusal of a model request is cleared. */
    fun cdnServed() = synchronized(lock) {
        if (current?.from == CDN) clear()
    }

    private fun clear() {
        stop = null
        askCdnAgain = false
        try { file.delete() } catch (_: Exception) {}
    }

    companion object {
        const val API = "api"
        const val CDN = "cdn"
    }
}

/**
 * Every request for a model (the build's manifest.json, each file, each Range request) goes out with the headers made
 * HERE and nowhere else (SPEC 15.4): `x-qlab-key: <key>` and `authorization: Bearer <app token>`. The token is the one
 * the SDK gets from `POST /v1/attest` for its other calls; it is had before the first model request and renewed when
 * under an hour is left ([TokenProvider]). An answer that is not the file is read here too: the refusals of SPEC 15.3
 * (remembered as a stop of a model request) and the CDN's own answers of 15.4.
 */
internal class ModelRequestHeaders(private val cloud: QuranLabCloud) : ModelAuth {
    override fun headers(url: String): Map<String, String> {
        val key = cloud.key ?: throw RecognizerException.InvalidKey()
        return linkedMapOf("x-qlab-key" to key.value, "authorization" to "Bearer " + cloud.modelToken())
    }

    /** After 401 `invalid_token` without a reason: the next [headers] carries a new token. */
    override fun renew() {
        cloud.tokens?.invalidate()
    }

    override fun served() = cloud.refusals.cdnServed()

    override fun refusal(status: Int, body: ByteArray): TajweedError? {
        val o = try { Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *> } catch (_: Exception) { null }
        val error = o?.get("error") as? String
        val reason = o?.get("reason") as? String
        val appId = cloud.env.app.bundleId
        if (ModelGate.stops(reason)) {
            cloud.refusals.stop(reason!!, Refusals.CDN)
            return ModelGate.error(reason, appId)
        }
        return when {
            // the token was refused again after its renewal: a download that failed
            status == 401 && error == "invalid_token" -> null
            // `missing or invalid x-qlab-key` without a reason: the key, as before 3.1.0; not a stop
            status == 401 -> RecognizerException.InvalidKey()
            status == 403 && (error == "model_attestation_required" || error == "model_not_allowed") -> RecognizerException.ServerRejected(error, appId)
            status == 429 && error == "model_limit" -> RecognizerException.ModelLimit()
            // 503 network_blocked and every other answer: a server failure, try later
            else -> null
        }
    }

    /** Whether an answer is the renewal case: 401 `invalid_token` without a reason. */
    override fun renewal(status: Int, body: ByteArray): Boolean {
        if (status != 401) return false
        val o = try { Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *> } catch (_: Exception) { null }
        return o?.get("error") == "invalid_token" && o["reason"] == null
    }
}
