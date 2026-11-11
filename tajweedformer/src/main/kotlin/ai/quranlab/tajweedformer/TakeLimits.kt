package ai.quranlab.tajweedformer

/**
 * The bounds of a take (NAMING.md, "Bounds"). Each number is here and nowhere else, so a change is one line. With
 * them nothing a take holds grows without end: its Heads, its decoded layer and the chunks on their way to the
 * Session are bounded by [MAX_TAKE_MS]; what waits for an app that does not collect, by the two buffers.
 */
internal object TakeLimits {
    /** A take holds at most this much audio, counted in input samples. At the limit it ends with `Ended(LIMIT)`. */
    const val MAX_TAKE_MS = 20 * 60 * 1000L

    /** Chunks that wait for the app's collector. When full, the oldest waiting one is dropped. */
    const val CHUNK_BUFFER = 256

    /** Events that wait for the app's collector. When full, the oldest waiting one is dropped. */
    const val EVENT_BUFFER = 64
}
