package ai.quranlab.tajweedformer.engine

import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.Recognizer
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.StageClock
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import java.util.concurrent.atomic.AtomicBoolean

/** native/tj_jni.c: the engine's zf_tajweed.h (the inference API) one call to one call. Handles are the C API's pointers. */
internal object TajweedJni {
    @JvmStatic external fun apiVersion(): Int
    @JvmStatic external fun version(): String
    @JvmStatic external fun modelOpen(dir: String, context: String?, threads: Int, sampleRate: Int, batch: Int, noPin: Int): Long
    @JvmStatic external fun modelClose(model: Long)
    @JvmStatic external fun modelChunkMs(model: Long): Int
    @JvmStatic external fun modelContext(model: Long): String
    @JvmStatic external fun streamCreate(model: Long): Long
    @JvmStatic external fun streamDestroy(stream: Long)
    @JvmStatic external fun streamReset(stream: Long)
    @JvmStatic external fun streamPush(stream: Long, samples: FloatArray, offset: Int, length: Int): Int
    @JvmStatic external fun streamFinish(stream: Long): Int
    @JvmStatic external fun streamFrames(stream: Long): Int
    @JvmStatic external fun streamHeads(stream: Long, from: Int, to: Int): Array<FloatArray?>

    // which heads a stream computes, and the decoded layer's top-2 ([token, runner_up, lp, lp2, margin] per frame)
    @JvmStatic external fun streamSetHeads(stream: Long, heads: Int): Int
    @JvmStatic external fun streamTop2(stream: Long, from: Int, to: Int): FloatArray
    @JvmStatic external fun top2(logProbs: FloatArray, frames: Int, vocab: Int): FloatArray

    // top-K: [id, value, margin] per (frame, rank)
    @JvmStatic external fun topk(values: FloatArray, frames: Int, cols: Int, k: Int): FloatArray
    @JvmStatic external fun streamTopk(stream: Long, head: Int, k: Int): FloatArray

    /** Diagnostics: the engine threads taking part in the stream's regions now (the calling thread and
     *  the active workers; zf_stream_threads_active). */
    @JvmStatic external fun streamThreadsActive(stream: Long): Int

    // the engine's resampler + fbank (tj_jni.c "frontend"), for the NPU backend
    @JvmStatic external fun frontendCreate(sampleRate: Int): Long
    @JvmStatic external fun frontendDestroy(frontend: Long)
    @JvmStatic external fun frontendReset(frontend: Long)
    @JvmStatic external fun frontendPush(frontend: Long, samples: FloatArray, offset: Int, length: Int): FloatArray
    @JvmStatic external fun frontendFinish(frontend: Long): FloatArray

    // the reference data (the expected units of each ayah, read-only): a table opened from its file, looked up by
    // surah and ayah. [referenceUnits] gives [unit ids..., then one word index per unit], or null for an ayah the
    // table does not have.
    @JvmStatic external fun referenceOpen(path: String): Long
    @JvmStatic external fun referenceClose(reference: Long)
    @JvmStatic external fun referenceAyahCount(reference: Long, surah: Int): Int
    @JvmStatic external fun referenceUnits(reference: Long, surah: Int, ayah: Int): IntArray?
    @JvmStatic external fun referenceTokensSha256(reference: Long): String?

    /** The zf_tajweed.h API this binding is written against (the inference-only API of SDK 3.0.0). */
    const val API_VERSION = 4

    /** Why the native library did not load (it is not built for this CPU, or it is not on the library path); null
     *  when it loaded or was not asked for yet. */
    @Volatile var loadError: String? = null
        private set

    /** Whether libtajweedformer_jni carries the engine's zf_tajweed at [API_VERSION] or later. */
    val available: Boolean by lazy {
        try {
            NativeLibrary.load()
            apiVersion() >= API_VERSION
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "the library did not load"
            false
        }
    }

    /** The error of a call that needs the engine while it is not [available]: `runtime_unsupported` when the library
     *  did not load on this device, else `engine_failed` (a library built without the engine: [what] needs it). */
    fun unavailable(what: String): ai.quranlab.tajweedformer.RecognizerException {
        val why = loadError ?: return ai.quranlab.tajweedformer.RecognizerException.EngineNotAvailable(what)
        return ai.quranlab.tajweedformer.RecognizerException.RuntimeUnsupported(
            "the native library tajweedformer_jni did not load on this CPU (${System.getProperty("os.arch") ?: "unknown"}): $why")
    }
}

/**
 * The exact V4 engine through JNI. The C API allows one live stream per process; this engine holds a
 * process-wide flag while a stream is open so a second one fails with a clear error.
 */
internal class ExactTajweedEngine(
    dir: java.io.File,
    context: String,
    threads: Int = 0,
    sampleRate: Int = 16_000,
    batch: Int = 1,
    /** null: the engine's platform default (pinned on Android). */
    noPin: Boolean? = null,
) : TajweedEngine {
    constructor(model: ModelFolder, context: String, options: Recognizer.Options) :
        this(model.dir, context, options.threads, options.sampleRate, options.batch, options.noPin)

    private var handle: Long = TajweedJni.modelOpen(dir.invariantSeparatorsPath, context, ai.quranlab.tajweedformer.CoreTopology.resolve(threads),
        sampleRate, batch, when (noPin) { null -> -1; true -> 1; false -> 0 })

    override val chunkMs: Int = TajweedJni.modelChunkMs(handle)

    private fun h(): Long = if (handle != 0L) handle else throw IllegalStateException("the engine is closed")

    /** A stream for a one-off run (the self-tests): destroyed at close, its thread unpinned. */
    override fun createStream(): TajweedEngineStream = open(HeadId.BASE_HEADS, keep = false)

    /**
     * A stream computing [heads] (and the base set): the engine computes only what is asked for.
     *
     * A take's engine stream is kept at close and reset for the next take on the same thread, rather
     * than destroyed and created again (as zf_tajweed_check runs its clips): a new stream starts and
     * pins a thread pool, about 100 ms a take on the Alcatel 1, and reset gives the same bits. The
     * process has at most one engine stream (the C API's rule): a kept one that does not fit (another
     * model or thread, or a one-off run) is destroyed first.
     */
    override fun createStream(heads: Set<HeadId>): TajweedEngineStream = open(heads + HeadId.BASE_HEADS, keep = true)

    private fun open(heads: Set<HeadId>, keep: Boolean): TajweedEngineStream {
        if (!liveStream.compareAndSet(false, true)) {
            throw RecognizerException.SessionActive()
        }
        return try {
            val m = h()
            val me = Thread.currentThread()
            val k = synchronized(idleLock) { idle.also { idle = null } }
            val kept = if (k != null && keep && k.model == m && k.thread === me) k.stream else {
                k?.let { TajweedJni.streamDestroy(it.stream) }
                0L
            }
            if (kept != 0L) {
                TajweedJni.streamReset(kept)
            } else {
                streamsCreated.incrementAndGet()
                StageClock.streamCreates.incrementAndGet()
            }
            Stream(if (kept != 0L) kept else TajweedJni.streamCreate(m), mask(heads), keep).also { liveHandle = it.streamPtr }
        } catch (e: Throwable) {
            liveStream.set(false)
            throw e
        }
    }

    private inner class Stream(private var s: Long, private val heads: Int, private val keep: Boolean) : TajweedEngineStream {
        private var seen = 0
        /** The engine stream (not the model: [handle] is the engine's). */
        val streamPtr: Long get() = s

        init {
            if (TajweedJni.streamSetHeads(s, heads) != 0) throw RecognizerException.EngineFailed("zf_tajweed_stream_set_heads refused")
        }

        private fun take(): List<HeadValues> {
            val n = TajweedJni.streamFrames(s)
            if (n <= seen) return emptyList()
            val arrays = TajweedJni.streamHeads(s, seen, n)
            val top2 = TajweedJni.streamTop2(s, seen, n)
            val step = chunkFrames(n - seen)
            val out = ArrayList<HeadValues>()
            // one HeadValues per engine chunk (8 frames at c16, 12 at c24)
            var at = 0
            while (at < n - seen) {
                val k = minOf(step, n - seen - at)
                val m = HashMap<HeadId, FloatArray>()
                for ((i, head) in HeadId.entries.withIndex()) {
                    val a = arrays[i] ?: continue
                    val per = (if (head.frameMs == 40) 1 else 2) * head.width
                    m[head] = a.copyOfRange(at * per, (at + k) * per)
                }
                out.add(HeadValues(k, m, top2.copyOfRange(at * 5, (at + k) * 5)))
                at += k
            }
            seen = n
            return out
        }

        private fun chunkFrames(total: Int): Int = if (chunkMs > 0) chunkMs / 40 else total

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
            if (!StageClock.on) {
                TajweedJni.streamPush(s, samples, offset, length)
                return take()
            }
            val t0 = System.nanoTime()
            TajweedJni.streamPush(s, samples, offset, length)
            val t1 = StageClock.lap(StageClock.FORWARD, t0)
            return take().also { StageClock.lap(StageClock.COPY_OUT, t1) }
        }

        override fun finish(): List<HeadValues> {
            if (!StageClock.on) {
                TajweedJni.streamFinish(s)
                return take()
            }
            val t0 = System.nanoTime()
            TajweedJni.streamFinish(s)
            val t1 = StageClock.lap(StageClock.FORWARD, t0)
            StageClock.threads(TajweedJni.streamThreadsActive(s))
            return take().also { StageClock.lap(StageClock.COPY_OUT, t1) }
        }

        override fun reset() {
            TajweedJni.streamReset(s)
            TajweedJni.streamSetHeads(s, heads)
            seen = 0
        }

        override fun close() {
            if (s != 0L) {
                // kept for the next take while the engine is open (see createStream)
                val kept = keep && synchronized(idleLock) {
                    if (handle != 0L && idle == null) {
                        idle = Idle(handle, s, Thread.currentThread())
                        true
                    } else {
                        false
                    }
                }
                liveHandle = 0L
                if (!kept) TajweedJni.streamDestroy(s)
                s = 0
                liveStream.set(false)
            }
        }
    }

    override fun close() {
        if (handle != 0L) {
            val kept = synchronized(idleLock) { idle?.takeIf { it.model == handle }?.also { idle = null } }
            kept?.let { TajweedJni.streamDestroy(it.stream) }
            TajweedJni.modelClose(handle)
            handle = 0
        }
    }

    /** Diagnostics: the engine threads of this engine's live or kept stream (-1: none). */
    fun threadsActive(): Int {
        val live = liveHandle
        if (live != 0L) return TajweedJni.streamThreadsActive(live)
        val k = synchronized(idleLock) { idle?.takeIf { it.model == handle }?.stream } ?: return -1
        return TajweedJni.streamThreadsActive(k)
    }

    @Volatile private var liveHandle = 0L

    /** An engine stream between takes: its model, its handle, the thread that ran it (its pool's pin). */
    private class Idle(val model: Long, val stream: Long, val thread: Thread)

    companion object {
        private val liveStream = AtomicBoolean(false)

        /** Whether the process's one engine stream is taken now (a take is running, or something left it open). */
        internal val streamLive: Boolean get() = liveStream.get()
        /** Engine streams created in this process (the tests check that takes reuse one). */
        internal val streamsCreated = java.util.concurrent.atomic.AtomicInteger()
        private val idleLock = Any()
        /** The process's one kept engine stream (none while a take runs). */
        private var idle: Idle? = null
    }
}

/**
 * The exact engine's front end alone (resampler to 16 kHz and kaldi fbank, with the engine's flush at
 * the end of a take): the NPU backend's input, the same bits the CPU engine computes.
 */
internal class EngineFrontEnd(sampleRate: Int) : AutoCloseable {
    private var h: Long = TajweedJni.frontendCreate(sampleRate)

    private fun h(): Long = if (h != 0L) h else throw IllegalStateException("the front end is closed")

    /** Samples in; fbank frames out, `[n, 80]`. */
    fun push(samples: FloatArray, offset: Int, length: Int): FloatArray = TajweedJni.frontendPush(h(), samples, offset, length)

    /** The end of the take: the flush frames. Call [reset] before the next take. */
    fun finish(): FloatArray = TajweedJni.frontendFinish(h())

    fun reset() = TajweedJni.frontendReset(h())

    override fun close() {
        if (h != 0L) {
            TajweedJni.frontendDestroy(h)
            h = 0
        }
    }

    companion object {
        const val BINS = 80
    }
}

/** The reference table of a model build through the engine's lookup (zf_tajweed_reference_*): read-only data. */
internal class EngineReference(file: java.io.File) : AutoCloseable {
    private var h: Long = TajweedJni.referenceOpen(file.invariantSeparatorsPath)

    /** The SHA-256 (64 lower-case hex) of the tokens.txt the table's ids refer to. */
    @Synchronized
    fun tokensSha256(): String? = if (h != 0L) TajweedJni.referenceTokensSha256(h) else null

    /** Ayahs of [surah] in the table (0 for a surah it does not have). */
    @Synchronized
    fun ayahCount(surah: Int): Int = if (h != 0L) TajweedJni.referenceAyahCount(h, surah) else 0

    /** The ayah's unit ids and, per unit, its word (0-based); null for an ayah the table does not have. */
    @Synchronized
    fun units(surah: Int, ayah: Int): Pair<IntArray, IntArray>? {
        if (h == 0L) return null
        val r = TajweedJni.referenceUnits(h, surah, ayah) ?: return null
        val n = r.size / 2
        return r.copyOfRange(0, n) to r.copyOfRange(n, 2 * n)
    }

    @Synchronized
    override fun close() {
        if (h != 0L) {
            TajweedJni.referenceClose(h)
            h = 0
        }
    }
}

/** ZF_TAJWEED_HEAD_* bits of [heads] (HeadId order is the bit order). */
internal fun mask(heads: Set<HeadId>): Int = heads.fold(0) { m, h -> m or (1 shl h.ordinal) }
