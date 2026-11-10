package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.CpuStandby

/**
 * A recognizer's exact CPU engine, kept as the accelerator's standby without resident weights.
 *
 * The engine maps `layers.zfw` (POSIX), but on every CPU with the dot-product instructions (every NPU phone) it
 * rewrites the 63 MB of int8 linear weights into its SDOT layout in place at load, so those pages become private
 * copies: a loaded CPU engine costs about 63 MB of anonymous memory there, which the OS cannot drop. So while the
 * accelerator serves, the weights are released ([release]) and only a scoring handle (no weights) stays; a failover
 * or a CPU session loads them again ([createStream]: the mapping, the layout pass, a stream), which is the cold
 * switch the failover's stall includes.
 */
internal class CpuSlot(
    first: TajweedEngine,
    /** Opens the full engine again (the model's weights). */
    private val reopen: () -> TajweedEngine,
    /** A scoring-only handle (no weights), or null when scoring needs the full engine (then it is never released). */
    private val openScorer: (() -> TajweedEngine)?,
) : CpuStandby {
    @Volatile private var full: TajweedEngine? = first
    private var scorerOnly: TajweedEngine? = null
    private var closed = false

    override val chunkMs: Int = first.chunkMs
    override val backend: Backend get() = Backend.CPU

    override val warm: Boolean get() = full != null

    /** Times the weights were loaded again after a release (the cold switches). */
    @Volatile var reloads = 0
        private set

    /** The full engine, loaded when it was released. */
    @Synchronized
    fun engine(): TajweedEngine {
        check(!closed) { "the CPU engine is closed" }
        return full ?: reopen().also { full = it; reloads++ }
    }

    /** Releases the weights when scoring can go on without them. Returns whether it did. */
    @Synchronized
    fun release(): Boolean {
        val f = full ?: return false
        if (openScorer == null || closed) return false
        if (scorerOnly == null) scorerOnly = openScorer.invoke()
        full = null
        f.close()
        return true
    }

    override fun createStream(): TajweedEngineStream = engine().createStream()

    override fun createStream(heads: Set<TajweedHead>): TajweedEngineStream = engine().createStream(heads)

    @Synchronized
    private fun scoring(): TajweedEngine = full ?: scorerOnly ?: engine()

    override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult =
        scoring().score(heads, config, settings, surah, ayah)

    override fun expectedTokens(surah: Int, ayah: Int): IntArray = scoring().expectedTokens(surah, ayah)

    override fun expectedWords(surah: Int, ayah: Int): IntArray = scoring().expectedWords(surah, ayah)

    @Synchronized
    override fun close() {
        closed = true
        full?.close()
        full = null
        scorerOnly?.close()
        scorerOnly = null
    }
}
