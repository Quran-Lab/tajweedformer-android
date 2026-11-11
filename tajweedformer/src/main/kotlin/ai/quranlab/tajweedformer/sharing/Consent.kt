package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The consent state machine of recitation sharing, the same in every SDK: asked only after a completed
 * session with feedback (never on first launch); the sheet is shown at most 3 times per install, ever
 * ([asks], the count of sheets shown or handed to the app's own UI): "Not now" may lead to a re-ask at
 * least 7 sessions after the previous ask until the third showing; a yes ends asking for good, whatever
 * deck, wording or SDK version comes later, and so does turning sharing off in the settings. Afterwards
 * only the settings switch turns sharing on or off.
 *
 * A grant keeps the consent version (and for the app's own wording its text hash) in force at the moment
 * of agreement; every shared session carries that version. Persisted as
 * `{"v":1,"consent","asks","lastAskSession","sessions","thanked","decidedAt","consentVersion","consentTextSha256","guardian"}`.
 * The 2.0.0 file is the same without `consentTextSha256` and with `updateAsked` (read and dropped); its
 * `asks` carries over, and a 2.0.0 grant without a version reads as [LEGACY_CONSENT_VERSION].
 */
internal class ConsentState internal constructor(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Consent(val key: String) { UNKNOWN("unknown"), GRANTED("granted"), DECLINED("declined"), FINAL("final") }

    /** Which sheet to show: the first ask, or a re-ask after "Not now". */
    enum class Variant {
        FIRST,
        REASK,
        /** Never returned since 2.0.1: a learner who said yes is not asked again when the deck or the wording changes. */
        @Deprecated("Never returned since 2.0.1: a yes persists across decks, wording and SDK upgrades.")
        UPDATE,
    }

    var consent = Consent.UNKNOWN; private set
    var asks = 0; private set
    var lastAskSession = 0; private set
    var sessions = 0; private set
    var thanked = false; private set
    var decidedAt: Long? = null; private set
    var consentVersion: String? = null; private set
    /** The SHA-256 of the app's own wording the learner agreed to ([SharingCopy.consentTextSha256]); null for the deck's. */
    var consentTextSha256: String? = null; private set
    var guardian = false; private set

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
        consentTextSha256 = o["consentTextSha256"] as? String
        guardian = o["guardian"] as? Boolean ?: false
        // 2.0.0 recorded the deck version at every grant; a grant without one is read as 2.0.0's bundled deck
        if (consent == Consent.GRANTED && consentVersion == null) consentVersion = LEGACY_CONSENT_VERSION
    }

    @Synchronized
    private fun save() {
        file.parentFile?.mkdirs()
        ShareFiles.writeJson(file, linkedMapOf("v" to 1, "consent" to consent.key, "asks" to asks, "lastAskSession" to lastAskSession,
            "sessions" to sessions, "thanked" to thanked, "decidedAt" to decidedAt, "consentVersion" to consentVersion,
            "consentTextSha256" to consentTextSha256, "guardian" to guardian))
    }

    /** The sheet that may be shown now under the cap, or null: a yes, an off switch, or [MAX_ASKS] showings end asking. */
    private fun next(spaced: Boolean): Variant? = when {
        asks >= MAX_ASKS -> null
        consent == Consent.UNKNOWN -> Variant.FIRST
        consent == Consent.DECLINED && spaced -> Variant.REASK
        else -> null
    }

    /**
     * After every completed session. [enabled]: the signed config offers sharing here and a sheet can be
     * shown. Returns the sheet to show now, or null. An ask is counted before it is shown.
     */
    @Synchronized
    fun sessionCompleted(feedback: Boolean, enabled: Boolean): Variant? {
        sessions++
        var v: Variant? = null
        if (enabled && feedback) {
            v = next(spaced = sessions - lastAskSession >= 7)
            if (v != null) { asks++; lastAskSession = sessions }
        }
        save()
        return v
    }

    /** 2.0.0's signature; the version no longer matters (a yes is never asked again). */
    @Deprecated("The consent version no longer decides an ask.", ReplaceWith("sessionCompleted(feedback, enabled)"))
    fun sessionCompleted(feedback: Boolean, enabled: Boolean, @Suppress("UNUSED_PARAMETER") consentVersionNow: String): Variant? =
        sessionCompleted(feedback, enabled)

    /** The app's own timing (`askNow`): which sheet, or null when the learner must not be asked now. Counted as a showing. */
    @Synchronized
    fun askNow(): Variant? {
        val v = next(spaced = true) ?: return null
        asks++
        lastAskSession = sessions
        save()
        return v
    }

    /** 2.0.0's signature; the version no longer matters (a yes is never asked again). */
    @Deprecated("The consent version no longer decides an ask.", ReplaceWith("askNow()"))
    fun askNow(@Suppress("UNUSED_PARAMETER") consentVersionNow: String): Variant? = askNow()

    /**
     * The primary button: shared from now on, under [consentVersionNow] (a `custom-` version with the
     * [textSha256] of the app's own wording), the version every shared session carries from then on, whatever
     * deck or wording comes later.
     */
    @Synchronized
    @JvmOverloads
    fun grant(consentVersionNow: String, byGuardian: Boolean, textSha256: String? = null) {
        consent = Consent.GRANTED
        decidedAt = clock() / 1000
        consentVersion = consentVersionNow
        consentTextSha256 = textSha256
        guardian = byGuardian
        save()
    }

    /** "Not now" or the sheet dismissed: declined, or final after the last re-ask. */
    @Synchronized
    fun decline() {
        if (consent == Consent.GRANTED) return save() // a "Not now" from the app's own UI after a yes keeps the yes
        consent = if (asks >= 3) Consent.FINAL else Consent.DECLINED
        decidedAt = clock() / 1000
        save()
    }

    /**
     * The settings switch: on = granted under the version in force now (already on: the version agreed to
     * stays), off = final (never asked again; only the switch turns it on again).
     */
    @Synchronized
    @JvmOverloads
    fun setFromSettings(on: Boolean, consentVersionNow: String, byGuardian: Boolean = guardian, textSha256: String? = null) {
        if (on && consent == Consent.GRANTED) return save()
        if (on) grant(consentVersionNow, byGuardian, textSha256) else {
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

    companion object {
        /** The sheet is shown at most this many times per install, ever. */
        const val MAX_ASKS = 3

        /** The consent version of the deck SDK 2.0.0 bundled, for a 2.0.0 grant stored without one. */
        const val LEGACY_CONSENT_VERSION = "share-2026-11-07"
    }
}

/**
 * The copy deck (`quranlab.sharing-copy/1`): every string the sharing sheet, the thank-you, the settings
 * and the deletion show, per language, bundled byte for byte in the SDK (`sharing_copy/<lang>.json`).
 * [consentVersion] is sent with every shared session.
 *
 * With the app's own wording ([Sharing.setWording], [withWording]) the copy shows the app's strings, and
 * [consentVersion] is `custom-<24 hex>`, derived from the wording ([ConsentWording]); [consentTextSha256]
 * and [consentText] are its hash and canonical text.
 */
internal class SharingCopy private constructor(
    val lang: String,
    val version: String,
    /** The version the learner agrees to: the deck's, or `custom-<24 hex>` under the app's own wording. */
    val consentVersion: String,
    /** 0 since deck 2026-11-07.2 (deletion is immediate); older decks filled `{days}` with it. */
    val deleteDays: Int,
    /** `rtl` for Arabic and Urdu: lay the sheet out in this direction. */
    val rtl: Boolean,
    private val strings: Map<String, String>,
    private val minutes: Map<String, String>,
    /** The hadith line's source was verified (sunnah.com, Sahih Muslim 1631). */
    val hadithVerified: Boolean,
    /** `immediate` (the deck's `delete_timing`), or null for a deck without it. */
    val deleteTiming: String?,
    /** The deck's own consent version (the base a custom wording's version is derived from). */
    val deckConsentVersion: String,
    /** The app's own wording applied to this copy (key to text), empty for the deck's. */
    val wording: Map<String, String>,
) {
    /** A string of the deck (or the app's wording for it); [ENGLISH]'s when this language lacks it. */
    operator fun get(key: String): String = strings[key] ?: (if (this !== ENGLISH) ENGLISH.strings[key] else null) ?: ""

    /** The hadith line, or null when the deck has no verified wording. */
    val hadith: String? get() = if (hadithVerified) strings["sheet.hadith"]?.takeIf { it.isNotEmpty() } else null

    val hadithRef: String? get() = if (hadith != null) strings["sheet.hadith_ref"] else null

    /** `delete.confirm` and `delete.done`; decks before 2026-11-07.2 had a `{days}` placeholder, filled here. */
    fun deletion(key: String): String = get(key).replace("{days}", deleteDays.toString())

    /** `impact.minutes` for [n] whole minutes, in this language's plural form. */
    fun impactMinutes(n: Long): String {
        val form = minutes[plural(lang, n)] ?: minutes["other"] ?: ENGLISH.minutes["other"] ?: ""
        return form.replace("{minutes}", java.text.NumberFormat.getIntegerInstance(java.util.Locale(lang)).format(n))
    }

    /** SHA-256 (lower-case hex) of the canonical text of the app's wording, or null for the deck's own. */
    val consentTextSha256: String? by lazy { if (wording.isEmpty()) null else ConsentWording.sha256(deckConsentVersion, wording) }

    /** The canonical text the hash is computed over (to send to Quran Lab for review), or null for the deck's own. */
    val consentText: String? get() = if (wording.isEmpty()) null else ConsentWording.canonical(deckConsentVersion, wording)

    /**
     * This copy with the app's own wording (key to text; empty: the deck's own). Keys are the deck's
     * ([ConsentWording.KEYS]); an unknown key or an empty or blank text throws [IllegalArgumentException].
     */
    fun withWording(overrides: Map<String, String>): SharingCopy {
        val w = ConsentWording.validate(overrides)
        if (w.isEmpty()) return if (wording.isEmpty()) this else bundledOrEnglish(lang)
        val s = LinkedHashMap(baseStrings())
        val m = LinkedHashMap(baseMinutes())
        for ((k, v) in w) {
            if (k.startsWith(ConsentWording.MINUTES)) m[k.removePrefix(ConsentWording.MINUTES)] = v else s[k] = v
        }
        return SharingCopy(lang, version, ConsentWording.version(deckConsentVersion, w), deleteDays, rtl, s, m, hadithVerified, deleteTiming,
            deckConsentVersion, w)
    }

    private fun baseStrings(): Map<String, String> = if (wording.isEmpty()) strings else bundledOrEnglish(lang).strings
    private fun baseMinutes(): Map<String, String> = if (wording.isEmpty()) minutes else bundledOrEnglish(lang).minutes

    /** Every string key of the deck (not the plural lines). */
    val keys: Set<String> get() = strings.keys

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

        /**
         * Parses a deck file; throws when it is not `quranlab.sharing-copy/1`. Tolerates decks with or
         * without `delete_days` and `delete_timing`, and with or without the `recovery.*` and
         * `delete.failed.*` keys (a missing key reads as English's).
         */
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
            val cv = o["consent_version"] as String
            return SharingCopy(
                lang = o["lang"] as String,
                version = o["version"] as String,
                consentVersion = cv,
                deleteDays = (o["delete_days"] as? Double)?.toInt() ?: 0,
                rtl = o["dir"] == "rtl",
                strings = flat,
                minutes = minutes,
                hadithVerified = src?.get("verified_at") is String && src["url"] is String,
                deleteTiming = o["delete_timing"] as? String,
                deckConsentVersion = cv,
                wording = emptyMap(),
            )
        }

        private val cache = ConcurrentHashMap<String, SharingCopy>()

        private fun bundled(lang: String): SharingCopy? = cache[lang] ?: run {
            val c = SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/$lang.json")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { try { parse(it) } catch (_: Exception) { null } }
            c?.also { cache[lang] = it }
        }

        private fun bundledOrEnglish(lang: String): SharingCopy = if (lang == "en") ENGLISH else bundled(lang) ?: ENGLISH

        /** The bundled deck for a locale (`ar-SA`, `ms_MY` -> `ar`, `ms`), English when the SDK has none. */
        @JvmStatic
        fun forLanguage(locale: String?): SharingCopy {
            val l = (locale ?: "en").lowercase().substringBefore('-').substringBefore('_').let { if (it == "in") "id" else it }
            if (l == "en" || l !in LANGUAGES) return ENGLISH
            return bundled(l) ?: ENGLISH
        }

        /** The English source deck. */
        @JvmStatic
        val ENGLISH: SharingCopy by lazy {
            SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/en.json")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { parse(it) } ?: error("the SDK's English sharing deck is missing")
        }
    }
}

/**
 * The app's own consent wording (SDK 2.0.1), the same in every SDK and checked against the shared vectors:
 *
 * ```
 * canonical  = "quranlab.consent-text/1\n" + "base " + <deck consent_version> + "\n"
 *              + for each replaced key, sorted by its UTF-8 bytes: key + "\t" + escaped text + "\n"
 * escaped    = the text with \ -> \\, newline -> \n, carriage return -> \r, tab -> \t (nothing else)
 * consent_text_sha256 = SHA-256 of the canonical UTF-8 bytes, lower-case hex
 * consent_version     = "custom-" + its first 24 hex digits
 * ```
 *
 * The base version is in the text, so a grant under a new deck records a new version even when the app's
 * wording stayed the same (a learner who agreed earlier keeps the version recorded then). Tried and rejected: hashing the whole merged deck (a string the app never shows would change its
 * version); the language in the text (the deck's translations are one meaning).
 */
internal object ConsentWording {
    const val HEADER = "quranlab.consent-text/1\n"
    const val PREFIX = "custom-"
    /** A plural line of `impact.minutes` is replaced by category: `impact.minutes.one`, `impact.minutes.other`, ... */
    const val MINUTES = "impact.minutes."
    val CATEGORIES = listOf("zero", "one", "two", "few", "many", "other")

    /** Every key an app may replace: the deck's strings and the plural lines of `impact.minutes`. */
    @JvmStatic
    val KEYS: Set<String> by lazy { SharingCopy.ENGLISH.keys + CATEGORIES.map { MINUTES + it } }

    /** The overrides, checked: an unknown key or an empty or blank text throws [IllegalArgumentException]. */
    @JvmStatic
    fun validate(overrides: Map<String, String>): Map<String, String> {
        for ((k, v) in overrides) {
            require(k in KEYS) { "unknown copy key: $k" }
            require(v.isNotBlank()) { "empty text for $k" }
        }
        return LinkedHashMap(overrides)
    }

    private fun utf8Order(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** The canonical text of [overrides] over the deck version [base]. */
    @JvmStatic
    fun canonical(base: String, overrides: Map<String, String>): String {
        val b = StringBuilder(HEADER).append("base ").append(base).append('\n')
        for (k in overrides.keys.sortedWith(::utf8Order)) b.append(k).append('\t').append(escape(overrides.getValue(k))).append('\n')
        return b.toString()
    }

    /** The lower-case hex SHA-256 of the canonical text. */
    @JvmStatic
    fun sha256(base: String, overrides: Map<String, String>): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(base, overrides).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The consent version: [base] without overrides, else `custom-` and the hash's first 24 hex digits. */
    @JvmStatic
    fun version(base: String, overrides: Map<String, String>): String =
        if (overrides.isEmpty()) base else PREFIX + sha256(base, overrides).take(24)
}
