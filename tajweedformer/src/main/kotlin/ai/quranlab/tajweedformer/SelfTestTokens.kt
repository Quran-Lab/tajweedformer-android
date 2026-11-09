package ai.quranlab.tajweedformer

/** The exact engine's greedy tokens of the self-test clip in the contexts other than c16_l64, from
 *  the host build of the pinned engine (V4ConformanceTest.selfTestTokensOfEveryContext prints them
 *  and checks them). Every context gives the same 11 tokens. */
internal object SelfTestTokens {
    val OTHER_CONTEXTS: Map<String, IntArray> = mapOf(
        "c8_l64" to intArrayOf(2, 31, 24, 34, 27, 33, 15, 31, 25, 31, 9),
        "c24_l72" to intArrayOf(2, 31, 24, 34, 27, 33, 15, 31, 25, 31, 9),
        "c24_l256" to intArrayOf(2, 31, 24, 34, 27, 33, 15, 31, 25, 31, 9),
    )
}
