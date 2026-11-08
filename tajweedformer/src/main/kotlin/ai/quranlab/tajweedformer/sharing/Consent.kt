package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import java.io.File

/**
 * The consent state machine of recitation sharing, the same in every SDK: asked only after a completed session with feedback
 * (never on first launch), "Not now" re-asked at most twice, each at least 7 sessions after the
 * previous ask, then never again unless the learner opens the setting. Persisted as
 * `{"v":1,"consent","asks","lastAskSession","sessions","thanked","decidedAt","consentVersion","guardian"}`.
 */
class ConsentState internal constructor(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Consent(val key: String) { UNKNOWN("unknown"), GRANTED("granted"), DECLINED("declined"), FINAL("final") }

    /** Which sheet to show: the first ask, a re-ask after "Not now", or a new deck version for someone who said yes. */
    enum class Variant { FIRST, REASK, UPDATE }

    var consent = Consent.UNKNOWN; private set
    var asks = 0; private set
    var lastAskSession = 0; private set
    var sessions = 0; private set
    var thanked = false; private set
    var decidedAt: Long? = null; private set
    var consentVersion: String? = null; private set
    var guardian = false; private set
    /** The deck version a granted learner was last asked about again (the UPDATE ask happens once per version). */
    private var updateAsked: String? = null

    init { load() }

    val granted: Boolean get() = consent == Consent.GRANTED

    @Synchronized
    private fun load() {
        val o = ShareFiles.readJson(file) ?: return
        consent = Consent.values().firstOrNull { it.key == o["consent"] } ?: Consent.UNKNOWN
        asks = (o["asks"] as? Double)?.toInt() ?: 0
        lastAskSession = (o["lastAskSession"] as? Double)?.toInt() ?: 0
        sessions = (o["sessions"] as? Double)?.toInt() ?: 0
        thanked = o["thanked"] as? Boolean ?: false
        decidedAt = (o["decidedAt"] as? Double)?.toLong()
        consentVersion = o["consentVersion"] as? String
        guardian = o["guardian"] as? Boolean ?: false
        updateAsked = o["updateAsked"] as? String
    }

    @Synchronized
    private fun save() {
        file.parentFile?.mkdirs()
        ShareFiles.writeJson(file, linkedMapOf("v" to 1, "consent" to consent.key, "asks" to asks, "lastAskSession" to lastAskSession,
            "sessions" to sessions, "thanked" to thanked, "decidedAt" to decidedAt, "consentVersion" to consentVersion,
            "guardian" to guardian, "updateAsked" to updateAsked))
    }

    /**
     * After every completed session. [enabled]: the app's `contributions` option and the signed
     * config allow sharing here. Returns the sheet to show now, or null. An ask is counted before
     * it is shown.
     */
    @Synchronized
    fun sessionCompleted(feedback: Boolean, enabled: Boolean, consentVersionNow: String): Variant? {
        sessions++
        var v: Variant? = null
        if (enabled && feedback) {
            v = when {
                consent == Consent.UNKNOWN -> Variant.FIRST
                consent == Consent.DECLINED && asks < 3 && sessions - lastAskSession >= 7 -> Variant.REASK
                consent == Consent.GRANTED && consentVersion != null && consentVersion != consentVersionNow && updateAsked != consentVersionNow -> Variant.UPDATE
                else -> null
            }
            if (v != null) {
                if (v == Variant.UPDATE) updateAsked = consentVersionNow else { asks++; lastAskSession = sessions }
            }
        }
        save()
        return v
    }

    /** The app's own timing (`askNow`): which sheet, or null when the learner must not be asked now. */
    @Synchronized
    fun askNow(consentVersionNow: String): Variant? {
        val v = when {
            consent == Consent.UNKNOWN -> Variant.FIRST
            consent == Consent.DECLINED && asks < 3 -> Variant.REASK
            consent == Consent.GRANTED && consentVersion != consentVersionNow && updateAsked != consentVersionNow -> Variant.UPDATE
            else -> null
        } ?: return null
        if (v == Variant.UPDATE) updateAsked = consentVersionNow else { asks++; lastAskSession = sessions }
        save()
        return v
    }

    /** The primary button: shared from now on, under [consentVersionNow]. */
    @Synchronized
    fun grant(consentVersionNow: String, byGuardian: Boolean) {
        consent = Consent.GRANTED
        decidedAt = clock() / 1000
        consentVersion = consentVersionNow
        guardian = byGuardian
        save()
    }

    /** "Not now" or the sheet dismissed: declined, or final after the last re-ask. */
    @Synchronized
    fun decline() {
        if (consent == Consent.GRANTED) return save() // a dismissed UPDATE sheet keeps the earlier yes
        consent = if (asks >= 3) Consent.FINAL else Consent.DECLINED
        decidedAt = clock() / 1000
        save()
    }

    /** The settings switch: on = granted, off = final (never asked again unless turned on there). */
    @Synchronized
    fun setFromSettings(on: Boolean, consentVersionNow: String, byGuardian: Boolean = guardian) {
        if (on) grant(consentVersionNow, byGuardian) else {
            consent = Consent.FINAL
            decidedAt = clock() / 1000
            save()
        }
    }

    /** The thank-you shows once, after the first session uploaded while granted. Returns true the one time it should. */
    @Synchronized
    fun takeThanks(): Boolean {
        if (thanked || consent != Consent.GRANTED) return false
        thanked = true
        save()
        return true
    }

    /** After "Delete what I shared": off, and a new contributor if the learner shares again. */
    @Synchronized
    fun afterDeletion() {
        consent = Consent.FINAL
        decidedAt = clock() / 1000
        thanked = false
        save()
    }
}

/**
 * The copy deck (`quranlab.sharing-copy/1`, the copy deck schema): every
 * string the sharing sheet, the thank-you, the settings and the deletion show, per language, bundled
 * byte for byte in the SDK (`sharing_copy/<lang>.json`). Apps may override the timing, never the
 * disclosure lines. [consentVersion] is sent with every shared session; [deleteDays] fills `{days}`.
 */
class SharingCopy private constructor(
    val lang: String,
    val version: String,
    val consentVersion: String,
    val deleteDays: Int,
    /** `rtl` for Arabic and Urdu: lay the sheet out in this direction. */
    val rtl: Boolean,
    private val strings: Map<String, String>,
    private val minutes: Map<String, String>,
    /** The hadith line's source was verified (sunnah.com, Sahih Muslim 1631). */
    val hadithVerified: Boolean,
) {
    /** A string of the deck; [ENGLISH]'s when this language lacks it. */
    operator fun get(key: String): String = strings[key] ?: (if (this !== ENGLISH) ENGLISH.strings[key] else null) ?: ""

    /** The hadith line, or null when the deck has no verified wording. */
    val hadith: String? get() = if (hadithVerified) strings["sheet.hadith"]?.takeIf { it.isNotEmpty() } else null

    val hadithRef: String? get() = if (hadith != null) strings["sheet.hadith_ref"] else null

    /** `delete.confirm` and `delete.done` with `{days}` filled. */
    fun deletion(key: String): String = get(key).replace("{days}", deleteDays.toString())

    /** `impact.minutes` for [n] whole minutes, in this language's plural form. */
    fun impactMinutes(n: Long): String {
        val form = minutes[plural(lang, n)] ?: minutes["other"] ?: ENGLISH.minutes["other"] ?: ""
        return form.replace("{minutes}", java.text.NumberFormat.getIntegerInstance(java.util.Locale(lang)).format(n))
    }

    companion object {
        const val FORMAT = "quranlab.sharing-copy/1"

        /** The languages the deck ships. */
        val LANGUAGES = listOf("en", "ar", "id", "ur", "tr", "ms", "fr", "bn")

        /** CLDR cardinal plural category of an integer for the deck's languages. */
        @JvmStatic
        fun plural(lang: String, n: Long): String = when (lang) {
            "ar" -> when {
                n == 0L -> "zero"
                n == 1L -> "one"
                n == 2L -> "two"
                n % 100 in 3..10 -> "few"
                n % 100 in 11..99 -> "many"
                else -> "other"
            }
            "fr" -> when {
                n == 0L || n == 1L -> "one"
                n != 0L && n % 1_000_000 == 0L -> "many"
                else -> "other"
            }
            "bn" -> if (n == 0L || n == 1L) "one" else "other"
            "id", "ms" -> "other"
            else -> if (n == 1L) "one" else "other"
        }

        /** Parses a deck file; throws when it is not `quranlab.sharing-copy/1`. */
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun parse(json: String): SharingCopy {
            val o = Json.parse(json) as Map<String, Any?>
            require(o["format"] == FORMAT) { "not a $FORMAT deck" }
            val s = o["strings"] as Map<String, Any?>
            val flat = LinkedHashMap<String, String>()
            for ((k, v) in s) if (v is String) flat[k] = v
            val minutes = (s["impact.minutes"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
            val src = o["hadith_source"] as? Map<String, Any?>
            return SharingCopy(
                lang = o["lang"] as String,
                version = o["version"] as String,
                consentVersion = o["consent_version"] as String,
                deleteDays = (o["delete_days"] as Double).toInt(),
                rtl = o["dir"] == "rtl",
                strings = flat,
                minutes = minutes,
                hadithVerified = src?.get("verified_at") is String && src["url"] is String,
            )
        }

        private fun bundled(lang: String): SharingCopy? =
            SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/$lang.json")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { try { parse(it) } catch (_: Exception) { null } }

        /** The bundled deck for a locale (`ar-SA`, `ms_MY` -> `ar`, `ms`), English when the SDK has none. */
        @JvmStatic
        fun forLanguage(locale: String?): SharingCopy {
            val l = (locale ?: "en").lowercase().substringBefore('-').substringBefore('_').let { if (it == "in") "id" else it }
            if (l == "en" || l !in LANGUAGES) return ENGLISH
            return bundled(l) ?: ENGLISH
        }

        /** The English source deck. */
        @JvmStatic
        val ENGLISH: SharingCopy by lazy { bundled("en") ?: error("the SDK's English sharing deck is missing") }
    }
}
