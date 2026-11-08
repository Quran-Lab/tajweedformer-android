package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Ed25519
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ManifestVerifier
import java.io.File

/**
 * qlab-api's signed config (PLATFORM_CLIENT.md section 3), parsed after its signature checked out.
 * Unknown fields and flags are ignored. [tajweedJson] is the `tajweed` block as JSON text, for the
 * SDK's settings (`TajweedConfig.remoteJson`), which parse it leniently field by field.
 */
class RemoteConfig internal constructor(val raw: Map<String, Any?>, val body: ByteArray) {
    val app: String? get() = raw["app"] as? String
    val channel: String? get() = raw["channel"] as? String
    val issuedAt: Long get() = (raw["issued_at"] as? Double)?.toLong() ?: 0
    val expiresAt: Long get() = (raw["expires_at"] as? Double)?.toLong() ?: 0
    val flags: Flags = Flags.parse(raw["flags"])

    /** The model to load: `{model, model_id, folder, revision, source}`, or null. */
    @Suppress("UNCHECKED_CAST")
    val recommended: Map<String, Any?>? get() = raw["recommended"] as? Map<String, Any?>

    val recommendedRevision: String? get() = recommended?.get("revision") as? String

    /** The `tajweed` block, or null when the config has none. */
    val tajweedJson: String? get() = raw["tajweed"]?.takeIf { it is Map<*, *> }?.let { Json.write(it) }

    @Suppress("UNCHECKED_CAST")
    val denylist: List<Map<String, Any?>> get() = (raw["denylist"] as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    /** The deny-list row matching this install, whose `backend_forced` the SDK must use. */
    fun denyRule(platform: String, osVersion: String, deviceClass: String, sdkVersion: String, modelRevision: String?): Map<String, Any?>? =
        denylist.firstOrNull { r ->
            match(r["platform"], platform) && match(r["os_version"], osVersion) && match(r["device_class"], deviceClass) &&
                match(r["sdk_version"], sdkVersion) && match(r["model_revision"], modelRevision ?: "")
        }

    private fun match(pattern: Any?, value: String): Boolean {
        val p = pattern as? String ?: return true
        return when {
            p == "*" -> true
            p.endsWith("*") -> value.startsWith(p.dropLast(1))
            else -> p == value
        }
    }

    fun expired(nowSec: Long): Boolean = expiresAt <= nowSec

    /** `flags`: booleans default to true when absent, `upload_trust` per platform. */
    class Flags(
        val analytics: Boolean = true,
        val issues: Boolean = true,
        /** Absent: the server's default (production: false, nobody uploads). */
        val contributions: Boolean? = null,
        val uploadTrust: Map<String, String> = emptyMap(),
        val cappedLimits: Map<String, Any?> = emptyMap(),
    ) {
        /** The tier for a platform: the flag, else the documented default (`attested` for android). */
        fun trust(platform: String): String = uploadTrust[platform] ?: when (platform) {
            "web" -> "off"
            else -> "attested"
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun parse(v: Any?): Flags {
                val m = v as? Map<String, Any?> ?: return Flags()
                val trust = (m["upload_trust"] as? Map<String, Any?>)?.mapNotNull { (k, x) ->
                    (x as? String)?.takeIf { it in setOf("off", "capped", "attested") }?.let { k to it }
                }?.toMap() ?: emptyMap()
                return Flags(
                    analytics = m["analytics"] as? Boolean ?: true,
                    issues = m["issues"] as? Boolean ?: true,
                    contributions = m["contributions"] as? Boolean,
                    uploadTrust = trust,
                    cappedLimits = m["capped_limits"] as? Map<String, Any?> ?: emptyMap(),
                )
            }
        }
    }

    companion object {
        /** The key id of a raw 32-byte Ed25519 public key: the first 8 bytes of its SHA-256, hex. */
        @JvmStatic
        fun keyId(publicKey: ByteArray): String = sha256(publicKey).copyOf(8).hex()

        /**
         * Verifies a config answer in the spec's order: the key id is pinned, the Ed25519 signature
         * covers the exact body bytes, `v` is 1 and `expires_at` is in the future. Throws
         * [ConfigRejected] naming the first failure.
         */
        @JvmStatic
        fun verify(body: ByteArray, signatureHeader: String?, keyIdHeader: String?, trustedKeys: List<ByteArray>, nowSec: Long): RemoteConfig {
            val key = trustedKeys.firstOrNull { keyId(it) == keyIdHeader } ?: throw ConfigRejected("unknown key id $keyIdHeader")
            val sig = signatureHeader?.removePrefix("ed25519=")?.takeIf { signatureHeader.startsWith("ed25519=") }
                ?: throw ConfigRejected("no ed25519 signature")
            val raw = try { b64urlDecode(sig) } catch (_: IllegalArgumentException) { throw ConfigRejected("signature is not base64url") }
            if (raw.size != 64 || !Ed25519.verify(key, body, raw)) throw ConfigRejected("signature does not verify")
            @Suppress("UNCHECKED_CAST")
            val o = try { Json.parse(String(body, Charsets.UTF_8)) as? Map<String, Any?> } catch (_: Exception) { null }
                ?: throw ConfigRejected("body is not a JSON object")
            if (o["v"] != 1.0) throw ConfigRejected("v is not 1")
            val c = RemoteConfig(o, body)
            if (c.expired(nowSec)) throw ConfigRejected("expired")
            return c
        }
    }
}

class ConfigRejected(message: String) : Exception(message)

/**
 * The last good signed config on disk (`<storage>/cloud/config.*`): the exact body, its signature,
 * key id and ETag, re-verified at every read. Refresh per section 3: conditional with the stored
 * ETag (304 = still current); without it when the stored body is within an hour of expiry.
 */
class ConfigStore(
    private val dir: File,
    val trustedKeys: List<ByteArray> = listOf(ManifestVerifier.QURAN_LAB_PUBLIC_KEY),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val bodyFile = File(dir, "config.body")
    private val metaFile = File(dir, "config.meta.json")
    @Volatile private var cached: RemoteConfig? = null

    /** The verified, unexpired config, or null (then calibration defaults and the bundled model apply). */
    val current: RemoteConfig?
        get() {
            val now = clock() / 1000
            cached?.let { if (!it.expired(now)) return it }
            val c = load(now)
            cached = c
            return c
        }

    private fun meta(): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Json.parse(metaFile.readText()) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    private fun load(now: Long): RemoteConfig? {
        val m = meta() ?: return null
        val body = try { bodyFile.readBytes() } catch (_: Exception) { return null }
        return try {
            RemoteConfig.verify(body, m["signature"] as? String, m["kid"] as? String, trustedKeys, now)
        } catch (_: ConfigRejected) {
            null
        }
    }

    /** The ETag to send, or null when the stored body must be replaced by a freshly signed one. */
    fun etagToSend(): String? {
        val c = current ?: return null
        if (c.expiresAt - clock() / 1000 < 3600) return null
        return meta()?.get("etag") as? String
    }

    /**
     * Applies a `/v1/config` answer. 304 keeps the stored body; 200 is verified and stored. Returns
     * the config now current; throws [ConfigRejected] when a 200 body fails verification (the last
     * good one stays).
     */
    fun apply(r: HttpResponse): RemoteConfig? {
        if (r.status == 304) return current
        val now = clock() / 1000
        val c = RemoteConfig.verify(r.body, r.header("x-qlab-signature"), r.header("x-qlab-key-id"), trustedKeys, now)
        dir.mkdirs()
        val tmp = File(dir, "config.body.tmp")
        tmp.writeBytes(r.body)
        val meta = linkedMapOf<String, Any?>("signature" to r.header("x-qlab-signature"), "kid" to r.header("x-qlab-key-id"),
            "etag" to r.header("etag"))
        if (!tmp.renameTo(bodyFile)) { bodyFile.delete(); tmp.renameTo(bodyFile) }
        val mt = File(dir, "config.meta.json.tmp")
        mt.writeText(Json.write(meta))
        if (!mt.renameTo(metaFile)) { metaFile.delete(); mt.renameTo(metaFile) }
        cached = c
        return c
    }
}
