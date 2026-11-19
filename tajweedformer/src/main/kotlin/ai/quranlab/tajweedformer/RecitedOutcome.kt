package ai.quranlab.tajweedformer

/**
 * How the app's own judge found a recited range or ayah (SPEC 17.9, SDK 4.0), for `session.reportRecited`: correct, or
 * incorrect with an optional count of mistakes and the rules they broke. The SDK never judges a recitation: it only
 * counts what the app reports, into anonymous daily totals for the app's statistics at sdk.quranlab.ai.
 *
 * ```
 * session.reportRecited(surah = 2, ayahFrom = 255, ayahTo = 257,
 *     outcome = RecitedOutcome.incorrect(mistakes = 2, rules = listOf("madd", "qalqalah")),
 *     perAyah = mapOf(255 to RecitedOutcome.correct()))
 * ```
 */
public class RecitedOutcome private constructor(
    /** True for a correct recitation. */
    public val correct: Boolean,
    /** The mistakes the app counted (1 to 1,000), or null when it gave none. Always null for a correct one. */
    public val mistakes: Int?,
    /** The rules the mistakes broke (at most 8 ids, `^[a-z][a-z0-9_]{0,23}$`, the calibration's ids where they apply). */
    public val rules: List<String>,
) {
    override fun equals(other: Any?): Boolean =
        other is RecitedOutcome && other.correct == correct && other.mistakes == mistakes && other.rules == rules

    override fun hashCode(): Int = (if (correct) 1 else 0) * 31 * 31 + (mistakes ?: 0) * 31 + rules.hashCode()

    override fun toString(): String = if (correct) "correct" else "incorrect(mistakes=$mistakes, rules=$rules)"

    public companion object {
        /** A correct recitation. */
        @JvmStatic
        public fun correct(): RecitedOutcome = CORRECT

        /**
         * An incorrect recitation: [mistakes] 1 to 1,000 or null; [rules] at most 8 ids of lower-case letters, digits
         * and underscores starting with a letter, at most 24 long. Anything else is `invalid_argument`, never sent.
         */
        @JvmStatic
        @JvmOverloads
        public fun incorrect(mistakes: Int? = null, rules: List<String> = emptyList()): RecitedOutcome {
            if (mistakes != null && mistakes !in 1..MAX_MISTAKES) throw RecognizerException.InvalidAppOption("mistakes", "$mistakes is not 1..$MAX_MISTAKES")
            if (rules.size > MAX_RULES) throw RecognizerException.InvalidAppOption("rules", "${rules.size} rules: at most $MAX_RULES")
            for (r in rules) if (!RULE.matches(r)) throw RecognizerException.InvalidAppOption("rules", "$r is not a rule id (^[a-z][a-z0-9_]{0,23}$)")
            return RecitedOutcome(false, mistakes, rules.distinct())
        }

        private val CORRECT = RecitedOutcome(true, null, emptyList())
        internal const val MAX_MISTAKES = 1_000
        internal const val MAX_RULES = 8
        internal val RULE = Regex("^[a-z][a-z0-9_]{0,23}$")

        /** The journal's form (17.6: the report is held with its take). */
        internal fun toJson(o: RecitedOutcome): Map<String, Any?> = linkedMapOf("correct" to o.correct, "mistakes" to o.mistakes, "rules" to o.rules)

        internal fun fromJson(v: Any?): RecitedOutcome? {
            val m = v as? Map<*, *> ?: return null
            if (m["correct"] == true) return CORRECT
            return try {
                incorrect((m["mistakes"] as? Double)?.toInt(), (m["rules"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** One app report of a take (SPEC 6.3, 17.9): the range and, when the app judged it, the outcomes. */
internal data class RecitedReport(
    val surah: Int,
    val ayahFrom: Int,
    val ayahTo: Int,
    val outcome: RecitedOutcome? = null,
    val perAyah: Map<Int, RecitedOutcome> = emptyMap(),
) {
    val ayahs: Int get() = ayahTo - ayahFrom + 1

    fun json(): Map<String, Any?> = linkedMapOf("surah" to surah, "from" to ayahFrom, "to" to ayahTo,
        "outcome" to outcome?.let { RecitedOutcome.toJson(it) },
        "per_ayah" to perAyah.entries.associate { (a, o) -> a.toString() to RecitedOutcome.toJson(o) })

    companion object {
        fun of(v: Any?): RecitedReport? {
            val m = v as? Map<*, *> ?: return null
            val s = (m["surah"] as? Double)?.toInt() ?: return null
            val a = (m["from"] as? Double)?.toInt() ?: return null
            val b = (m["to"] as? Double)?.toInt() ?: return null
            val per = (m["per_ayah"] as? Map<*, *>)?.entries?.mapNotNull { (k, x) ->
                val ay = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                RecitedOutcome.fromJson(x)?.let { ay to it }
            }?.toMap() ?: emptyMap()
            return RecitedReport(s, a, b, RecitedOutcome.fromJson(m["outcome"]), per)
        }
    }
}
