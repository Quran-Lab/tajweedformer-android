package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.CpuStandby

/**
 * A recognizer's exact CPU engine, kept as the accelerator's standby without resident weights.
 *
 * The engine maps `layers.zfw` (POSIX), but on every CPU with the dot-product instructions (every NPU phone) it
 * rewrites the 63 MB of int8 linear weights into its SDOT layout in place at load, so those pages become private
 * copies: a loaded CPU engine costs about 63 MB of anonymous memory there, which the OS cannot drop. So while the
 * accelerator serves, the engine is closed ([release]); a failover or a CPU session loads it again
 * ([createStream]: the mapping, the layout pass, a stream), which is the cold switch the failover's stall includes.
 */
internal class CpuSlot(
    first: TajweedEngine,
    /** Opens the full engine again (the model's weights). */
    private val reopen: () -> TajweedEngine,
) : CpuStandby {
    @Volatile private var full: TajweedEngine? = first
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

    /** Releases the weights. Returns whether it did. */
    @Synchronized
    override fun release(): Boolean {
        val f = full ?: return false
        if (closed) return false
        full = null
        f.close()
        return true
    }

    override fun createStream(): TajweedEngineStream = engine().createStream()

    override fun createStream(heads: Set<HeadId>): TajweedEngineStream = engine().createStream(heads)

    @Synchronized
    override fun close() {
        closed = true
        full?.close()
        full = null
    }
}
