package ai.quranlab.tajweedformer.cloud

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/** Which app the SDK runs in: what registration and tokens name. On Android, from the Context. */
internal data class AppIdentity(
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
internal data class DeviceProfile(
    val deviceClass: String,
    val soc: String? = null,
    val osMajor: Int,
    /** `14`, `14.1`. */
    val osVersion: String,
    /** `lt2|2_4|4_8|8_16|ge16`. */
    val ramTier: String? = null,
    /** SDK 4.0 (SHARING_40.md 19.2): the model code for a shared recording's start body only, as [DeviceModel] makes
     *  it (Android: `Build.MANUFACTURER` and `Build.MODEL`); null where the platform has none (the JVM). */
    val model: String? = null,
    /** Contract 24.2 (error reports, native only): the model code as `Build.MODEL` says it, spaces as `_`
     *  (`SM-A155F`, `Pixel_8`), never a name or a serial; null where the platform has none (the JVM). */
    val modelCode: String? = null,
    /** Contract 24.2: the process's ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`; the desktop JVM `arm64`, `x86_64`). */
    val abi: String? = null,
) {
    companion object {
        /** [modelCode] from `Build.MODEL`: spaces as `_`; null unless it is a token of 24.2 (never cut into one). */
        @JvmStatic
        fun modelCode(model: String?): String? = model?.trim()?.replace(' ', '_')?.takeIf { ErrorRules.DEVICE_MODEL.matches(it) }

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

/**
 * SHARING_40.md 19.2: `device_model` (`tools/sharing40.py` `device_model`, `vectors/sharing40/device_model.json`): each
 * part trimmed, the non-empty ones joined with one space, every code point outside `A-Z a-z 0-9`, space and
 * `_ . , + ( ) / -` replaced by one `_`, runs of spaces made one, characters up to the first letter or digit dropped,
 * cut to 64 characters, trailing spaces dropped; null when nothing is left. The result always matches
 * `^[A-Za-z0-9][A-Za-z0-9 _.,+()/-]{0,63}$`.
 */
internal object DeviceModel {
    private fun ok(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in " _.,+()/-"
    private fun alnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    @JvmStatic
    fun of(vararg parts: String?): String? {
        // Python's str.strip(): whitespace as Unicode defines it
        val joined = parts.mapNotNull { p -> p?.let { stripped(it) }?.takeIf { it.isNotEmpty() } }.joinToString(" ")
        val b = StringBuilder()
        var i = 0
        while (i < joined.length) {
            val cp = joined.codePointAt(i)
            b.append(if (cp < 0x80 && ok(cp)) cp.toChar() else '_')
            i += Character.charCount(cp)
        }
        var s = b.toString()
        while ("  " in s) s = s.replace("  ", " ")
        val first = s.indexOfFirst { alnum(it) }
        s = if (first < 0) "" else s.substring(first)
        s = s.take(64).trimEnd(' ')
        return s.ifEmpty { null }
    }

    private fun stripped(s: String): String {
        var a = 0
        var z = s.length
        while (a < z && isSpace(s.codePointAt(a))) a += Character.charCount(s.codePointAt(a))
        while (z > a && isSpace(s.codePointBefore(z))) z -= Character.charCount(s.codePointBefore(z))
        return s.substring(a, z)
    }

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp in 0x1C..0x1F || cp == 0x85
}

/** UTC days, the finest time anything the SDK sends carries. */
internal object Day {
    @JvmStatic
    fun of(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** Days between two day strings (b minus a). */
    @JvmStatic
    fun between(a: String, b: String): Long =
        java.time.LocalDate.parse(b).toEpochDay() - java.time.LocalDate.parse(a).toEpochDay()
}

/** A Quran Lab developer key, `qlk_v1_<key_id>_<22 chars>`: shipped in the app, sent as `x-qlab-key`. */
internal class QuranLabKey private constructor(val value: String, val keyId: String) {
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
