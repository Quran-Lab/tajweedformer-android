package ai.quranlab.tajweedformer

/**
 * Wall time per stage of a take on the CPU path, for the speed checks (the device bench app). Off by
 * default: then each stage costs one volatile read. Not thread-safe across sessions: one take at a
 * time (the SDK runs one stream per process anyway).
 */
internal object StageClock {
    /** The stages, in [ns] and [calls] order. */
    @JvmField val NAMES = arrayOf(
        "audio_observers",   // append: the copy and SdkObserver.onAudio (telemetry)
        "engine_dispatch",   // append/finish: the hop onto the recognizer's engine thread and back
        "engine_forward",    // zf_tajweed_stream_push_audio / finish (fbank + layers + heads; no resample at 16 kHz)
        "jni_copy_out",      // the chunk's heads and top-2 out of the engine into Kotlin arrays
        "decoded_topk",      // the observers' decoded layer (Decoder.decode, top-K from the engine)
        "chunk_observers",   // SdkObserver.onChunk (telemetry)
        "deliver_other",     // the rest of deliver: the chunk flow, accelerator bookkeeping
        "stream_open_close", // zf_tajweed_stream_create at a take's first audio, destroy at its end
    )
    const val AUDIO_OBSERVERS = 0
    const val DISPATCH = 1
    const val FORWARD = 2
    const val COPY_OUT = 3
    const val DECODE = 4
    const val CHUNK_OBSERVERS = 5
    const val DELIVER_OTHER = 6
    const val STREAM_OPEN_CLOSE = 7

    @JvmField @Volatile var on = false
    /** Engine streams created in this process (counted whether or not the clock is on). */
    @JvmField val streamCreates = java.util.concurrent.atomic.AtomicInteger()
    /** The engine threads taking part at the end of each take since [reset] (zf_stream_threads_active):
     *  the smallest and largest seen; -1 before the first. */
    @JvmField @Volatile var threadsMin = -1
    @JvmField @Volatile var threadsMax = -1

    @JvmStatic fun threads(n: Int) {
        if (n < 0) return
        threadsMin = if (threadsMin < 0) n else minOf(threadsMin, n)
        threadsMax = maxOf(threadsMax, n)
    }
    @JvmField val ns = LongArray(NAMES.size)
    @JvmField val calls = LongArray(NAMES.size)

    @JvmStatic fun reset() {
        ns.fill(0)
        calls.fill(0)
        threadsMin = -1
        threadsMax = -1
    }

    /** Adds the time since [t0] (System.nanoTime) to [stage]; returns now. */
    @JvmStatic fun lap(stage: Int, t0: Long): Long {
        val t = System.nanoTime()
        ns[stage] += t - t0
        calls[stage]++
        return t
    }
}
