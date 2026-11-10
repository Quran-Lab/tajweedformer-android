package ai.quranlab.tajweedformer

/**
 * The SDK's identity. [VERSION] is what a model's `min_sdk_version` is compared with: V4 (`sdk/v4.0`)
 * needs 2.0.0, and SDKs before 2.0.0 are never offered it.
 */
object TajweedFormer {
    const val VERSION = "2.0.2"

    /** The manifest format this SDK reads (LAYOUT.md layout v2: tagged files, gzip transport). */
    const val MANIFEST_FORMAT = 3

    /** The engine format this SDK reads: 3 = the V4 TajweedFormer folder of layout v2 (one shared
     *  `layers.zfw` and `frontend.zfw`, per context the graph, its data and `frames.zfw`). SDK 2.0.0
     *  hosts V4 only; v3.2 (engine format 1) stays on the 1.x SDKs. */
    const val ENGINE_FORMAT = 3

    /** CoreML specification versions a manifest may name (the field is checked, the CoreML model
     *  itself is used only on Apple platforms). */
    @JvmField val SUPPORTED_COREML_FORMATS = 1..9

    /** The platform tag this SDK downloads for (manifest `platforms`). */
    const val PLATFORM = "android"

    /**
     * Listeners for what the SDK does (sessions, backend choice and fallbacks, downloads, self-tests,
     * timing, and the audio it receives). See [SdkObserver]. Empty by default; the SDK's cloud
     * module (telemetry, recording sharing) registers itself here.
     */
    @JvmStatic
    val events: SdkEvents get() = SdkEvents
}

/** The four calibrated operating points of a model (calibration.json `presets`). */
enum class TajweedPreset(val key: String) {
    /** Fewest false flags. */
    STRICT("strict"),
    BALANCED("balanced"),
    /** Catch more. */
    SENSITIVE("sensitive"),
    /** Catch the most (teacher review). */
    VERY_SENSITIVE("very_sensitive");

    companion object {
        /** The preset named [key], or null. */
        @JvmStatic fun fromKey(key: String): TajweedPreset? = entries.firstOrNull { it.key == key }

        /** The preset named [key]; app code passing anything else is a programming error. */
        @JvmStatic fun of(key: String): TajweedPreset =
            fromKey(key) ?: throw RecognizerException.InvalidAppOption("preset", "unknown preset $key")
    }
}

/** The switches an app or the portal turns on and off (the `checks` object). */
enum class TajweedCheck(val key: String) {
    /** cons_sub, cons_del, insertion. */
    LETTERS("letters"),
    /** vowel_sub, vowel_del, vowel_ins. */
    VOWELS("vowels"),
    /** short_to_long, long_to_short. */
    LENGTH("length"),
    /** Madd counts from the timing head (fixed2, muttasil, free). */
    MADD("madd"),
    /** Shadda held. */
    GEMINATION("gemination"),
    /** The release burst present. */
    QALQALAH("qalqalah"),
    /** Madd lazim counts (only with [MADD] on too). */
    LAZIM("lazim");

    companion object {
        @JvmStatic fun fromKey(key: String): TajweedCheck? = entries.firstOrNull { it.key == key }
    }
}

/** Site classes: single-unit alternatives scored by CTC forward LLR, in calibration.json's order. */
enum class SiteClass(val key: String, val check: TajweedCheck) {
    /** a / i / u swapped. */
    VOWEL_SUB("vowel_sub", TajweedCheck.VOWELS),
    /** A short vowel dropped (sukun said). */
    VOWEL_DEL("vowel_del", TajweedCheck.VOWELS),
    /** A short vowel added after a sakin letter. */
    VOWEL_INS("vowel_ins", TajweedCheck.VOWELS),
    /** A short vowel said long. */
    SHORT_TO_LONG("short_to_long", TajweedCheck.LENGTH),
    /** A long vowel said short. */
    LONG_TO_SHORT("long_to_short", TajweedCheck.LENGTH),
    /** A consonant swapped for a confusable one. */
    CONS_SUB("cons_sub", TajweedCheck.LETTERS),
    /** A consonant dropped. */
    CONS_DEL("cons_del", TajweedCheck.LETTERS),
    /** A unit added. */
    INSERTION("insertion", TajweedCheck.LETTERS);

    companion object {
        @JvmStatic fun fromKey(key: String): SiteClass? = entries.firstOrNull { it.key == key }
    }
}

/** calibration.json's status of a class or check. */
enum class CheckStatus(val key: String) {
    /** Never reported, unless a debug build's app code turns it on. */
    OFF("off"),
    /** Off by default; the app or the portal may turn it on. */
    EXPERIMENTAL("experimental"),
    /** On by default. */
    VALIDATED("validated");

    companion object {
        @JvmStatic fun fromKey(key: String): CheckStatus? = entries.firstOrNull { it.key == key }
    }
}

/** Where an effective setting came from. Precedence: app code > remote config > calibration. */
enum class SettingSource(val key: String) {
    CALIBRATION("calibration"),
    REMOTE("remote"),
    APP("app"),
}

/** Madd classes of the expected table and calibration.json's `madd.cuts`. */
enum class MaddClass(val key: String) {
    NONE("none"),
    /** Tabee'i, badal, ewad and the other two-count madds. */
    FIXED2("fixed2"),
    MUTTASIL("muttasil"),
    /** Munfasil, aared, silah kubra. */
    FREE("free"),
    LAZIM("lazim");

    companion object {
        @JvmStatic fun fromKey(key: String): MaddClass? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where the heads come from, which picks calibration.json's thresholds: `int8` for the exact engine
 * (the default), `fp32` for CoreML, NPUs and servers (fp16 / fp32 heads), whose thresholds are
 * `contexts[ctx].backend_overrides.fp32` where the file has them.
 */
enum class TajweedBackend(val key: String) {
    INT8("int8"),
    FP32("fp32");

    companion object {
        @JvmStatic fun fromKey(key: String): TajweedBackend? = entries.firstOrNull { it.key == key }
    }
}

/** The non-site checks an override can set a preset for (it picks madd's `preset_cuts` and
 *  qalqalah's / gemination's `preset_tau`); they take no threshold. */
enum class TajweedFeature(val key: String) {
    MADD("madd"),
    QALQALAH("qalqalah"),
    GEMINATION("gemination");

    companion object {
        @JvmStatic fun fromKey(key: String): TajweedFeature? = entries.firstOrNull { it.key == key }
    }
}
