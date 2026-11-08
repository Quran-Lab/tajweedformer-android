package ai.quranlab.tajweedformer

/**
 * The per-word decision level: a word is
 * flagged when its score reaches the level's threshold (`score >= tau`). Higher threshold = fewer flags.
 */
enum class WordLevel(val key: String) {
    STRICT("strict"),
    GENERAL("general"),
    BEGINNER("beginner");

    companion object {
        @JvmStatic fun fromKey(key: String): WordLevel? = entries.firstOrNull { it.key == key }
    }
}

/** The app's per-word choice: a level, or a custom threshold (clamped to the calibration's
 *  `custom_range` and `floor`, as the engine does). */
sealed class WordOption {
    data class Level(val level: WordLevel) : WordOption()
    data class Threshold(val threshold: Double) : WordOption()

    companion object {
        @JvmStatic fun level(level: WordLevel): WordOption = Level(level)
        @JvmStatic fun threshold(threshold: Double): WordOption = Threshold(threshold)
    }
}

/**
 * A context's per-word calibration: calibration.json's `contexts.<ctx>.word` block (CAL; the shipping
 * file 37249b0c has one per context: `default_preset`, `presets.<name>.tau`, `floor`, `custom_range`,
 * `status`, `readout`, `evidence`), else a top-level `word` block, else the contract's fallback table
 * (P2.5: general 4.93, beginner 4.0, strict 6.5, floor 2.0; [fromCalibration] false then).
 */
class WordCalibration(
    val thresholds: Map<WordLevel, Double>,
    val floor: Double,
    val defaultLevel: WordLevel,
    val customLo: Double,
    val customHi: Double,
    val status: CheckStatus,
    val fromCalibration: Boolean,
    /** The block's `readout` text: the exact definition of the score. */
    val readout: String? = null,
) {
    /** A preset's tau, never below [floor]. */
    fun threshold(level: WordLevel): Double = maxOf(thresholds[level] ?: DEFAULT.getValue(level), floor)

    /** A custom threshold clamped to [customLo]..[customHi] and never below [floor]. */
    fun clamp(x: Double): Double = maxOf(x.coerceIn(customLo, customHi), floor)

    companion object {
        const val CLIP = 30.0

        @JvmField
        val DEFAULT: Map<WordLevel, Double> = mapOf(WordLevel.GENERAL to 4.93, WordLevel.BEGINNER to 4.0, WordLevel.STRICT to 6.5)
        const val DEFAULT_FLOOR = 2.0

        private val FALLBACK = WordCalibration(DEFAULT, DEFAULT_FLOOR, WordLevel.GENERAL, DEFAULT_FLOOR, CLIP, CheckStatus.VALIDATED, false)

        /** The word calibration of [context] (null: the default context). */
        @JvmStatic
        @JvmOverloads
        fun of(calibration: Calibration, context: String? = null): WordCalibration {
            val ctx = context ?: calibration.defaultContext
            val block = (calibration.contexts[ctx]?.raw?.get("word") ?: calibration.raw["word"]) as? Map<*, *> ?: return FALLBACK
            val presets = (block["presets"] as? Map<*, *>) ?: block
            val t = HashMap<WordLevel, Double>()
            for (l in WordLevel.entries) {
                val v = presets[l.key]
                val x = (v as? Double) ?: ((v as? Map<*, *>)?.let { (it["tau"] ?: it["threshold"]) as? Double })
                if (x != null && x.isFinite()) t[l] = x
            }
            if (t.isEmpty()) return FALLBACK
            val floor = (block["floor"] as? Double)?.takeIf { it.isFinite() } ?: DEFAULT_FLOOR
            val range = (block["custom_range"] as? List<*>)?.mapNotNull { it as? Double }
            val def = ((block["default_preset"] ?: block["default"]) as? String)?.let { WordLevel.fromKey(it) } ?: WordLevel.GENERAL
            val status = when (block["status"]) {
                "off" -> CheckStatus.OFF
                "experimental" -> CheckStatus.EXPERIMENTAL
                else -> CheckStatus.VALIDATED
            }
            return WordCalibration(t, floor, def, range?.getOrNull(0) ?: floor, range?.getOrNull(1) ?: CLIP, status, true,
                block["readout"] as? String)
        }
    }
}

/** The per-word setting in force and where it came from ([level] null = a custom threshold). */
data class WordSettings(val level: WordLevel?, val threshold: Double, val source: SettingSource, val fromCalibration: Boolean) {
    companion object {
        /**
         * Precedence app > portal > calibration's default preset. [remoteTajweedJson] is the portal's
         * `tajweed` block; its `word` field is `{"preset": "general"}` or `{"threshold": 5.1}` (exactly
         * one; anything else is ignored, never fatal), as the engine's API 3. Custom thresholds (app or
         * portal) are clamped to the calibration's range and floor; an app threshold that is not finite
         * throws [RecognizerException.InvalidAppOption].
         */
        @JvmStatic
        fun resolve(cal: WordCalibration, remoteTajweedJson: String?, app: WordOption?): WordSettings {
            when (app) {
                is WordOption.Level -> return WordSettings(app.level, cal.threshold(app.level), SettingSource.APP, cal.fromCalibration)
                is WordOption.Threshold -> {
                    if (!app.threshold.isFinite()) throw RecognizerException.InvalidAppOption("word.threshold", "${app.threshold} is not finite")
                    return WordSettings(null, cal.clamp(app.threshold), SettingSource.APP, cal.fromCalibration)
                }
                null -> {}
            }
            remote(remoteTajweedJson)?.let { w ->
                val preset = w["preset"]
                val x = w["threshold"]
                if (w.size == 1 && preset is String) {
                    WordLevel.fromKey(preset)?.let { return WordSettings(it, cal.threshold(it), SettingSource.REMOTE, cal.fromCalibration) }
                }
                if (w.size == 1 && x is Double && x.isFinite() && x >= -20 && x <= 20) {
                    return WordSettings(null, cal.clamp(x), SettingSource.REMOTE, cal.fromCalibration)
                }
            }
            return WordSettings(cal.defaultLevel, cal.threshold(cal.defaultLevel), SettingSource.CALIBRATION, cal.fromCalibration)
        }

        private fun remote(json: String?): Map<*, *>? {
            if (json.isNullOrBlank()) return null
            return try {
                (Json.parse(json) as? Map<*, *>)?.get("word") as? Map<*, *>
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** How the best alternative of a word changes it. */
enum class WordEdit { SUB, DEL, INS }

/**
 * One word's score and its evidence: the largest clipped fused LLR over every single-unit edit inside
 * the word (calibration.json `word.readout`; computed by the engine, zf_tajweed.h API 3), flagged when
 * `score >= threshold`. The best edit: [siteClass], [edit] at path index [editPos], [expected] ->
 * [alternative] (null for a deletion), its per-head LLRs. [hasScore] false: the word has no alternative
 * (score -30, never flagged).
 */
data class WordVerdict(
    /** 0-based word of the ayah. */
    val word: Int,
    val score: Double,
    val threshold: Double,
    val flagged: Boolean,
    val hasScore: Boolean = true,
    val siteClass: SiteClass? = null,
    val edit: WordEdit? = null,
    val editPos: Int = -1,
    val expected: Int? = null,
    val alternative: Int? = null,
    /** The best edit's LLR on log_probs and on inter1_log_probs, clipped. */
    val llrFinal: Double = Double.NaN,
    val llrInter1: Double = Double.NaN,
    /** The word's tokens on the scored path: [tokLo, tokHi). */
    val tokLo: Int = -1,
    val tokHi: Int = -1,
    /** 40 ms frames of the word's sites on the best path; -1 when unknown. */
    val frameLo: Int = -1,
    val frameHi: Int = -1,
    /** The word's site verdicts (per-class evidence). */
    val sites: List<SiteVerdict> = emptyList(),
    /** Set only by the site-based fallback ([WordScores.fromSites]): the site that gave the score. */
    val best: SiteVerdict? = null,
) {
    val startMs: Long get() = if (frameLo < 0) -1 else frameLo * CtcFrame.FRAME_MS
    val endMs: Long get() = if (frameHi < 0) -1 else (frameHi + 1) * CtcFrame.FRAME_MS
}

object WordScores {
    /**
     * The fallback when the engine has no word readout (zf_tajweed.h before API 3): per word the max of
     * the clipped fused LLR of the scorer's site verdicts (each the best alternative of its class at its
     * position). This covers the same edits as the readout only when the scorer reports every class's
     * site; the engine's own readout ([TajweedResult.words] from API 3) is the reference.
     */
    @JvmStatic
    fun fromSites(sites: List<SiteVerdict>, words: Int, threshold: Double): List<WordVerdict> {
        val n = maxOf(words, (sites.maxOfOrNull { it.word } ?: -1) + 1)
        val by = sites.groupBy { it.word }
        return (0 until n).map { w ->
            val s = by[w].orEmpty()
            val best = s.maxByOrNull { clip(it.llr) }
            val score = best?.let { clip(it.llr) } ?: -WordCalibration.CLIP
            val (lo, hi) = frames(s)
            WordVerdict(w, score, threshold, best != null && score >= threshold, best != null, best?.siteClass,
                llrFinal = best?.llrFinal?.let { clip(it) } ?: Double.NaN, llrInter1 = best?.llrInter1?.let { clip(it) } ?: Double.NaN,
                expected = best?.expected, alternative = best?.alternative, frameLo = lo, frameHi = hi, sites = s, best = best)
        }
    }

    /** Kept for callers of the first 2.0.0 preview. */
    @JvmStatic
    fun score(sites: List<SiteVerdict>, words: Int, threshold: Double): List<WordVerdict> = fromSites(sites, words, threshold)

    internal fun frames(s: List<SiteVerdict>): Pair<Int, Int> =
        (s.filter { it.frameLo >= 0 }.minOfOrNull { it.frameLo } ?: -1) to (s.filter { it.frameHi >= 0 }.maxOfOrNull { it.frameHi } ?: -1)

    internal fun clip(x: Double): Double = when {
        x.isNaN() -> -WordCalibration.CLIP
        else -> x.coerceIn(-WordCalibration.CLIP, WordCalibration.CLIP)
    }
}
