package ai.quranlab.tajweedformer

/**
 * `calibration.json` of a model build, exactly as shipped (format `quranlab.tajweed-calibration/1`): read-only
 * data for an app's own judge. The SDK never applies it: nothing here changes what the model outputs.
 *
 * What it holds (see the README, "The calibration"): the classes and presets Quran Lab measured, and per context the
 * thresholds (in nats, on a fused log-likelihood ratio computed from `log_probs` and `inter1_log_probs`), the fusion
 * weights, the madd cuts and the taus of the attribute heads. [json] is the whole file; the accessors below read the
 * fields most judges need, and [value] reads any other.
 */
public class TajweedCalibration internal constructor(bytes: ByteArray, sha256: String) {
    /** The file as shipped, as text. */
    public val json: String = String(bytes, Charsets.UTF_8)

    /** SHA-256 of the file (lower-case hex), as the signed manifest lists it. */
    public val sha256: String = sha256.lowercase()

    private val root: Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        (Json.parse(json) as? Map<String, Any?>) ?: emptyMap()
    } catch (_: JsonException) {
        emptyMap()
    }

    /** The file's format, `quranlab.tajweed-calibration/1`; empty when the file does not say. */
    public val format: String get() = root["format"] as? String ?: ""

    /** The model the numbers were measured on (`v4.0`). */
    public val modelId: String? get() = root["model_id"] as? String

    /** SHA-256 of the tokens file the numbers belong to. */
    public val tokensSha256: String? get() = root["tokens_sha256"] as? String

    /** The context the file calls its default. The Model's own default comes from the manifest. */
    public val defaultContext: String? get() = root["default_context"] as? String

    /** The preset the file calls its default (`balanced`). */
    public val defaultPreset: String? get() = root["default_preset"] as? String

    /** The site classes, in the file's order (`vowel_sub`, `vowel_del`, ...). */
    public val classes: List<String> get() = (root["classes"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    /** The preset names, in the file's order (`strict`, `balanced`, `sensitive`, `very_sensitive`). */
    public val presets: List<String> get() = (root["presets"] as? Map<*, *>)?.keys?.filterIsInstance<String>() ?: emptyList()

    /** The contexts the file has numbers for. */
    public val contexts: List<String> get() = (root["contexts"] as? Map<*, *>)?.keys?.filterIsInstance<String>() ?: emptyList()

    /**
     * The threshold of a site class at a preset in a context (`contexts.<context>.sites.<siteClass>.thresholds.<preset>`),
     * in nats on the fused log-likelihood ratio; null when the file has none.
     */
    public fun threshold(context: String, siteClass: String, preset: String): Double? =
        value("contexts", context, "sites", siteClass, "thresholds", preset) as? Double

    /** The status of a site class in a context: `validated`, `experimental` or `off`; null when the file has none. */
    public fun status(context: String, siteClass: String): String? = value("contexts", context, "sites", siteClass, "status") as? String

    /**
     * The fusion weight `a` of a site class in a context (`llr = a * llr(log_probs) + (1 - a) * llr(inter1_log_probs)`):
     * the class's own, else the context's `default`; null when the file has neither.
     */
    public fun fusionWeight(context: String, siteClass: String): Double? =
        (value("contexts", context, "fusion", "a", siteClass) ?: value("contexts", context, "fusion", "a", "default")) as? Double

    /**
     * Any field by its path, for example `value("contexts", "c16_l64", "madd", "cuts")`. Objects are maps with string
     * keys, arrays are lists, numbers are Double; null when the path does not exist.
     */
    public fun value(vararg path: String): Any? {
        var v: Any? = root
        for (k in path) v = (v as? Map<*, *>)?.get(k) ?: return null
        return v
    }

    internal companion object {
        const val FORMAT = "quranlab.tajweed-calibration/1"
        const val FILE_NAME = "calibration.json"
    }
}
