package ai.quranlab.tajweedformer

/**
 * One scored site: the expected unit at [position], the best single-unit alternative of its class,
 * and the per-head and fused LLRs (log P(alternative) - log P(expected), full-sequence CTC forward).
 * `llr = a * llrFinal + (1 - a) * llrInter1`, `a` from calibration.json's fusion for the class.
 * [flagged] = the class is enabled and `llr > threshold`.
 */
data class SiteVerdict(
    val siteClass: SiteClass,
    val status: CheckStatus,
    /** Index into the ayah's expected tokens (an insertion: the unit it follows, -1 at the start). */
    val position: Int,
    /** 0-based word of the ayah. */
    val word: Int,
    /** tj3 id expected at [position]; null for an insertion. */
    val expected: Int?,
    /** tj3 id of the best alternative; null for a deletion. */
    val alternative: Int?,
    /** LLR on the final CTC head (log_probs). */
    val llrFinal: Double,
    /** LLR on the stack-1 InterCTC head (inter1_log_probs). */
    val llrInter1: Double,
    /** The fused LLR the threshold applies to. */
    val llr: Double,
    val threshold: Double,
    val flagged: Boolean,
    /** 40 ms frames the expected unit holds on the best path; -1 when unknown. */
    val frameLo: Int,
    val frameHi: Int,
) {
    /** Per-head LLRs by head. */
    val headLlrs: Map<TajweedHead, Double>
        get() = mapOf(TajweedHead.LOG_PROBS to llrFinal, TajweedHead.INTER1_LOG_PROBS to llrInter1)
}

/** One madd long vowel, counted from the timing head. */
data class MaddVerdict(
    val position: Int,
    val word: Int,
    /** aa, ii or uu (tj3 id). */
    val token: Int,
    val maddClass: MaddClass,
    val status: CheckStatus,
    /** Held frames over the take's median short-vowel frames. */
    val ratio: Double,
    /** The verdict count: 2, 4 or 6, from calibration.json's cuts. */
    val count: Int,
    /** A smooth count for display; never for verdicts. */
    val displayCount: Double,
    /** The counts the rule allows. */
    val allowedLo: Int,
    val allowedHi: Int,
    /** [count] outside [allowedLo]..[allowedHi] and the check enabled. */
    val flagged: Boolean,
    /** 20 ms frames. */
    val frameLo: Int,
    val frameHi: Int,
) {
    /** How long the vowel was held, in ms (20 ms frames); 0 when unknown. */
    val durationMs: Int get() = if (frameLo < 0 || frameHi < frameLo) 0 else (frameHi - frameLo + 1) * 20
}

/** A letter feature: qalqalah (mean P(burst) after the letter) or gemination (mean P(held) over the
 *  letter). [flagged] = the feature is missing (score below threshold) and the check enabled. */
data class LetterVerdict(
    val position: Int,
    val word: Int,
    val token: Int,
    val status: CheckStatus,
    val score: Double,
    val threshold: Double,
    val flagged: Boolean,
    /** 20 ms frames read. */
    val frameLo: Int,
    val frameHi: Int,
) {
    val durationMs: Int get() = if (frameLo < 0 || frameHi < frameLo) 0 else (frameHi - frameLo + 1) * 20
}

data class TajweedSummary(
    val sites: Int,
    val sitesFlagged: Int,
    val madd: Int,
    val maddFlagged: Int,
    val qalqalah: Int,
    val qalqalahFlagged: Int,
    val gemination: Int,
    val geminationFlagged: Int,
    /** The madd unit: median short-vowel frames, 0 when too few. */
    val shortVowelFrames: Double,
    /** Enough short vowels (calibration.json's min_short_vowels) for madd counts. */
    val maddUnitOk: Boolean,
)

/**
 * The tajweed layer of one take of one ayah, under [settings]: per-site verdicts with their evidence
 * (per-head and fused LLR, threshold), madd counts with durations, qalqalah and gemination evidence,
 * and the per-word scores and flags ([words], under [wordSettings]).
 */
class TajweedResult(
    val surah: Int,
    val ayah: Int,
    val settings: ResolvedTajweedSettings,
    val sites: List<SiteVerdict>,
    val madd: List<MaddVerdict>,
    val qalqalah: List<LetterVerdict>,
    val gemination: List<LetterVerdict>,
    val summary: TajweedSummary,
    /** The engine's JSON of the same result (the reference scorer's layout), when it gave one. */
    val json: String? = null,
    /** One verdict per word of the ayah (see [WordVerdict]); empty when not computed. */
    val words: List<WordVerdict> = emptyList(),
    val wordSettings: WordSettings? = null,
    /** The backend whose heads were scored. */
    val backend: Backend = Backend.CPU,
    /** [words] are the engine's own readout (zf_tajweed.h API 3), not the site-based fallback. */
    val wordsFromEngine: Boolean = false,
) {
    val flaggedSites: List<SiteVerdict> get() = sites.filter { it.flagged }
    val flaggedWords: List<WordVerdict> get() = words.filter { it.flagged }

    /** The same result with word verdicts: the engine's when it gave them, else the site-based
     *  fallback over [wordCount] words at [settings]. */
    fun withWords(wordCount: Int, settings: WordSettings, backend: Backend = this.backend): TajweedResult =
        if (wordsFromEngine) {
            TajweedResult(surah, ayah, this.settings, sites, madd, qalqalah, gemination, summary, json, words, wordSettings, backend, true)
        } else {
            TajweedResult(surah, ayah, this.settings, sites, madd, qalqalah, gemination, summary, json,
                WordScores.fromSites(sites, wordCount, settings.threshold), settings, backend, false)
        }
}
