package ai.quranlab.tajweedformer.cloud

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/** Which app the SDK runs in: what registration and tokens name. On Android, from the Context. */
data class AppIdentity(
    /** The Gradle `applicationId`. */
    val bundleId: String,
    /** The app's version name (letters, digits, `.+-`, at most 32). */
    val version: String,
    val name: String? = null,
    /** `android` for this SDK. */
    val platform: String = "android",
) {
    /** qlab-api's app id, `android:<applicationId>` (the deletion message names it). */
    val appId: String get() = "$platform:$bundleId"

    companion object {
        /** A version string the server's schema takes. */
        @JvmStatic
        fun cleanVersion(raw: String?): String {
            val v = (raw ?: "").filter { it.isLetterOrDigit() && it.code < 128 || it in ".+-" }.take(32).trimStart('.', '+', '-')
            return v.ifEmpty { "0" }
        }
    }
}

/**
 * Coarse device context for telemetry and issue reports: never an identifier. `deviceClass` is the
 * model family (`Pixel8`, `SM-S921`), `soc` the chip (`tensor_g3`), both `[A-Za-z0-9][A-Za-z0-9_.-]{0,23}`.
 */
data class DeviceProfile(
    val deviceClass: String,
    val soc: String? = null,
    val osMajor: Int,
    /** `14`, `14.1`. */
    val osVersion: String,
    /** `lt2|2_4|4_8|8_16|ge16`. */
    val ramTier: String? = null,
) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,23}$")

        /** A token the schema takes from free text: unsafe characters dropped, at most 24. */
        @JvmStatic
        fun token(raw: String?): String? {
            val t = (raw ?: "").replace(' ', '_').filter { it.code < 128 && (it.isLetterOrDigit() || it in "_.-") }
                .trimStart('_', '.', '-').take(24)
            return t.takeIf { TOKEN.matches(it) }
        }

        /**
         * The device class from Build.MODEL: spaces removed, a Samsung model number cut to its family
         * (`SM-S921B` -> `SM-S921`), so the class names a model line and never a unit.
         */
        @JvmStatic
        fun deviceClass(model: String?): String {
            var m = (model ?: "").trim().replace(" ", "")
            Regex("^(SM-[A-Z]\\d{3})[A-Z0-9]*$").find(m)?.let { m = it.groupValues[1] }
            return token(m) ?: "unknown"
        }

        @JvmStatic
        fun ramTier(bytes: Long): String {
            val gb = bytes / 1e9
            return when {
                gb < 2 -> "lt2"
                gb < 4 -> "2_4"
                gb < 8 -> "4_8"
                gb < 16 -> "8_16"
                else -> "ge16"
            }
        }

        /** The desktop JVM (tests): the CPU architecture as the class. */
        @JvmStatic
        fun jvm(): DeviceProfile {
            val arch = token(System.getProperty("os.arch")) ?: "unknown"
            val v = System.getProperty("os.version") ?: "1"
            val major = v.takeWhile { it.isDigit() }.toIntOrNull()?.coerceIn(1, 99) ?: 1
            return DeviceProfile(arch, arch, major, v.take(32), null)
        }
    }
}

/** UTC days, the finest time anything the SDK sends carries. */
object Day {
    @JvmStatic
    fun of(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** Days between two day strings (b minus a). */
    @JvmStatic
    fun between(a: String, b: String): Long =
        java.time.LocalDate.parse(b).toEpochDay() - java.time.LocalDate.parse(a).toEpochDay()
}

/** A Quran Lab developer key, `qlk_v1_<key_id>_<22 chars>`: shipped in the app, sent as `x-qlab-key`. */
class QuranLabKey private constructor(val value: String, val keyId: String) {
    override fun toString(): String = "qlk_v1_${keyId}_..."

    companion object {
        private val RE = Regex("^qlk_v1_([a-z0-9][a-z0-9-]{1,38}[a-z0-9])_([A-Za-z0-9_-]{22})$")

        /** The key, or null when [s] is not one. */
        @JvmStatic
        fun parse(s: String?): QuranLabKey? {
            val m = RE.matchEntire(s?.trim() ?: return null) ?: return null
            return QuranLabKey(m.value, m.groupValues[1])
        }
    }
}

internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

internal fun ByteArray.hex(): String {
    val sb = StringBuilder(size * 2)
    for (x in this) sb.append("%02x".format(x.toInt() and 0xff))
    return sb.toString()
}

internal fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

internal fun b64urlDecode(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))
