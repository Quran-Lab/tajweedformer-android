package ai.quranlab.tajweedformer.cloud

import java.io.File

/**
 * Play Integrity for the attested token (PLATFORM_CLIENT.md section 2): given the request hash
 * (base64url SHA-256 of the server's challenge, 43 characters), a STANDARD integrity token request
 * with Quran Lab's Cloud project number, which the signed config gives (`flags.play_integrity_project_number`,
 * SPEC 15.4). Blocking; throws when Play cannot answer. The Android implementation is `PlayIntegrity` (the AAR
 * depends on `com.google.android.play:integrity` since 3.1.0); tests pass their own.
 */
internal fun interface IntegrityProvider {
    fun token(requestHash: String, cloudProjectNumber: Long): String
}

/**
 * The app token: cached on disk (`<storage>/cloud/token.json`), renewed when under an hour is left
 * or on 401 `invalid_token`. An attested token (Play Integrity, `via: pi`) is asked for only when
 * [token] is called with `attested = true` (recitation sharing, model requests) and the signed config gives the
 * Cloud project number ([projectNumber]); without the number Play is never asked. A refusal pauses attestation for
 * this launch and the SDK continues unattested.
 */
internal class TokenProvider(
    private val api: QuranLabApi,
    val key: QuranLabKey,
    private val app: AppIdentity,
    dir: File,
    private val integrity: IntegrityProvider?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Quran Lab's Cloud project number for Play Integrity, from the signed config in force; null: none. */
    private val projectNumber: () -> Long? = { null },
    /** The server issued a token: it accepted the key. */
    private val issued: () -> Unit = {},
) {
    private val file = File(dir, "token.json")
    private var cached: AppToken? = null
    @Volatile var attestationPaused = false
        private set

    /** Why attestation is paused (`attestation_failed`, `integrity_unavailable`, `no_provider`), or null. */
    @Volatile var attestationProblem: String? = null
        private set

    init {
        cached = try {
            val t = file.readText().trim()
            if (t.isNotEmpty()) AppToken.parse(t) else null
        } catch (_: Exception) {
            null
        }
    }

    /** True when an attested token can be had this launch (or is cached). */
    fun canAttest(): Boolean = cached?.let { it.attested && it.fresh(clock() / 1000) } == true ||
        (integrity != null && projectNumber() != null && !attestationPaused)

    /** The cached token while it is fresh, without asking the server; null otherwise. */
    @Synchronized
    fun cachedFresh(): AppToken? = cached?.takeIf { it.fresh(clock() / 1000) }

    /** A fresh token; attested when asked (throws `attestation_required` when none can be had). */
    @Synchronized
    fun token(attested: Boolean = false): AppToken {
        val now = clock() / 1000
        cached?.let { if (it.fresh(now) && (!attested || it.attested)) return it }
        val t = if (attested) attestedToken() else api.attest(key, app)
        save(t)
        try { issued() } catch (_: Exception) {}
        return t
    }

    private fun attestedToken(): AppToken {
        val provider = integrity
        if (provider == null) {
            attestationProblem = "no_provider"
            throw CloudException(ApiError(403, "attestation_required"), "no Play Integrity provider in this app")
        }
        val project = projectNumber() ?: run {
            attestationProblem = "no_project"
            throw CloudException(ApiError(403, "attestation_required"), "the signed config gives no Play Integrity project number")
        }
        if (attestationPaused) throw CloudException(ApiError(403, "attestation_required"), "attestation paused this launch: $attestationProblem")
        var tries = 0
        while (true) {
            tries++
            val challenge = api.challenge(key)
            val hash = b64url(sha256(challenge.toByteArray(Charsets.UTF_8)))
            val integrityToken = try {
                provider.token(hash, project)
            } catch (e: Exception) {
                pause("integrity_unavailable")
                throw CloudException(ApiError(403, "attestation_required"), "Play Integrity: ${e.message}")
            }
            try {
                return api.attestPlayIntegrity(key, app, challenge, integrityToken)
            } catch (e: CloudException) {
                val a = e.api
                when {
                    a?.code == "challenge_invalid" && tries < 2 -> continue
                    a?.action == ErrorAction.CONTINUE_UNATTESTED || a?.status == 503 || a?.code == "challenge_invalid" -> {
                        pause(a?.code ?: "attestation_failed")
                        throw CloudException(ApiError(403, "attestation_required"), "attestation refused: ${a?.code}")
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun pause(why: String) {
        attestationPaused = true
        attestationProblem = why
    }

    /** After a 401 `invalid_token`: the next [token] call fetches a new one. */
    @Synchronized
    fun invalidate() {
        cached = null
        file.delete()
    }

    private fun save(t: AppToken) {
        cached = t
        try {
            file.parentFile?.mkdirs()
            file.writeText(t.token)
        } catch (_: Exception) {
        }
    }
}
