package ai.quranlab.tajweedformer.engine

import ai.quranlab.tajweedformer.CheckStatus
import ai.quranlab.tajweedformer.ClassOverride
import ai.quranlab.tajweedformer.LetterVerdict
import ai.quranlab.tajweedformer.MaddClass
import ai.quranlab.tajweedformer.MaddVerdict
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.ResolvedTajweedSettings
import ai.quranlab.tajweedformer.SiteClass
import ai.quranlab.tajweedformer.SiteVerdict
import ai.quranlab.tajweedformer.StageClock
import ai.quranlab.tajweedformer.TajweedBackend
import ai.quranlab.tajweedformer.TajweedConfig
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TajweedHead
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedRecognizer
import ai.quranlab.tajweedformer.TajweedResult
import ai.quranlab.tajweedformer.TajweedSummary
import ai.quranlab.tajweedformer.SettingSource
import ai.quranlab.tajweedformer.WordCalibration
import ai.quranlab.tajweedformer.WordEdit
import ai.quranlab.tajweedformer.WordLevel
import ai.quranlab.tajweedformer.WordOption
import ai.quranlab.tajweedformer.WordScores
import ai.quranlab.tajweedformer.WordSettings
import ai.quranlab.tajweedformer.WordVerdict
import java.util.concurrent.atomic.AtomicBoolean

/** native/tj_jni.c: zf_tajweed.h (API version 2) one call to one call. Handles are the C API's pointers. */
internal object TajweedJni {
    @JvmStatic external fun apiVersion(): Int
    @JvmStatic external fun version(): String
    @JvmStatic external fun modelOpen(dir: String, context: String?, loadEngine: Boolean, threads: Int, sampleRate: Int, batch: Int, noPin: Int): Long
    @JvmStatic external fun modelClose(model: Long)
    @JvmStatic external fun modelChunkMs(model: Long): Int
    @JvmStatic external fun modelContext(model: Long): String
    @JvmStatic external fun expectedTokens(model: Long, surah: Int, ayah: Int): IntArray?
    @JvmStatic external fun streamCreate(model: Long): Long
    @JvmStatic external fun streamDestroy(stream: Long)
    @JvmStatic external fun streamReset(stream: Long)
    @JvmStatic external fun streamPush(stream: Long, samples: FloatArray, offset: Int, length: Int): Int
    @JvmStatic external fun streamFinish(stream: Long): Int
    @JvmStatic external fun streamFrames(stream: Long): Int
    @JvmStatic external fun streamHeads(stream: Long, from: Int, to: Int): Array<FloatArray?>
    @JvmStatic external fun configCreate(model: Long): Long
    @JvmStatic external fun configDestroy(config: Long)
    @JvmStatic external fun configSetRemote(config: Long, json: ByteArray?): Int
    @JvmStatic external fun configSetApp(config: Long, kind: Int, a: String?, b: String?, threshold: Double, on: Int): Int
    @JvmStatic external fun configClearApp(config: Long)
    @JvmStatic external fun configSetDebugBuild(config: Long, debug: Boolean)
    @JvmStatic external fun configJson(config: Long): String?
    @JvmStatic external fun score(model: Long, config: Long, heads: Array<FloatArray?>, n40: Int, surah: Int, ayah: Int): Long
    @JvmStatic external fun resultFree(result: Long)
    @JvmStatic external fun resultJson(result: Long): String?
    @JvmStatic external fun resultRecords(result: Long, kind: Int): DoubleArray
    @JvmStatic external fun expectedWords(model: Long, surah: Int, ayah: Int): IntArray?

    // zf_tajweed.h API 3: the per-word score (null / -2 against an older engine)
    @JvmStatic external fun configSetWord(config: Long, kind: Int, preset: String?, threshold: Double): Int
    @JvmStatic external fun configWord(config: Long): DoubleArray?
    @JvmStatic external fun wordRecords(result: Long): DoubleArray?

    // API 3: which heads a stream computes, and the decoded layer's top-2 ([token, runner_up, lp, lp2, margin] per frame)
    @JvmStatic external fun streamSetHeads(stream: Long, heads: Int): Int
    @JvmStatic external fun streamTop2(stream: Long, from: Int, to: Int): FloatArray
    @JvmStatic external fun top2(logProbs: FloatArray, frames: Int, vocab: Int): FloatArray

    // top-K (tajweedformer-sdk d0bb2b3): [id, value, margin] per (frame, rank)
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

    /** The zf_tajweed.h API this binding is written against (API 3, final: words, the heads a stream
     *  computes, top-2, no_pin; tajweedformer-sdk bfc476a). */
    const val API_VERSION = 3

    /** Whether libtajweedformer_jni carries zf_tajweed (the scorer, and the streams when built with
     *  zf_tajweed_engine; see native/CMakeLists.txt) at API 3 or later. */
    val available: Boolean by lazy {
        try {
            NativeLibrary.load()
            apiVersion() >= API_VERSION
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}

/**
 * The exact V4 engine through JNI. The C API allows one live stream per process; this engine holds a
 * process-wide flag while a stream is open so a second one fails with a clear error.
 */
internal class ExactTajweedEngine(
    dir: java.io.File,
    context: String,
    loadEngine: Boolean,
    threads: Int = 0,
    sampleRate: Int = 16_000,
    batch: Int = 1,
    /** null: the engine's platform default (pinned on Android). */
    noPin: Boolean? = null,
) : TajweedEngine {
    constructor(model: TajweedModel, context: String, options: TajweedRecognizer.Options) :
        this(model.dir, context, true, options.threads, options.sampleRate, options.batch, options.noPin)

    private var handle: Long = try {
        TajweedJni.modelOpen(dir.invariantSeparatorsPath, context, loadEngine, ai.quranlab.tajweedformer.CoreTopology.resolve(threads),
            sampleRate, batch, when (noPin) { null -> -1; true -> 1; false -> 0 })
    } catch (e: RecognizerException.ModelLoadFailed) {
        // the scoring-only build (engine_stub.c) says so in its error; anything else is the folder's fault
        if (loadEngine && e.reason.contains("has no engine")) {
            throw RecognizerException.EngineNotAvailable("libtajweedformer_jni has the V4 scorer only, not the exact engine (${e.reason})")
        }
        throw e
    }

    override val chunkMs: Int = TajweedJni.modelChunkMs(handle)

    private fun h(): Long = if (handle != 0L) handle else throw IllegalStateException("the engine is closed")

    /** A stream for a one-off run (the self-tests): destroyed at close, its thread unpinned. */
    override fun createStream(): TajweedEngineStream = open(TajweedHead.SCORER_HEADS, keep = false)

    /**
     * A stream computing [heads] (and the scorer's): API 3 computes only what is asked for.
     *
     * A take's engine stream is kept at close and reset for the next take on the same thread, rather
     * than destroyed and created again (as zf_tajweed_check runs its clips): a new stream starts and
     * pins a thread pool, about 100 ms a take on the Alcatel 1, and reset gives the same bits. The
     * process has at most one engine stream (the C API's rule): a kept one that does not fit (another
     * model or thread, or a one-off run) is destroyed first.
     */
    override fun createStream(heads: Set<TajweedHead>): TajweedEngineStream = open(heads + TajweedHead.SCORER_HEADS, keep = true)

    private fun open(heads: Set<TajweedHead>, keep: Boolean): TajweedEngineStream {
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

        private fun take(): List<TajweedHeads> {
            val n = TajweedJni.streamFrames(s)
            if (n <= seen) return emptyList()
            val arrays = TajweedJni.streamHeads(s, seen, n)
            val top2 = TajweedJni.streamTop2(s, seen, n)
            val step = chunkFrames(n - seen)
            val out = ArrayList<TajweedHeads>()
            // one TajweedHeads per engine chunk (8 frames at c16, 12 at c24)
            var at = 0
            while (at < n - seen) {
                val k = minOf(step, n - seen - at)
                val m = HashMap<TajweedHead, FloatArray>()
                for ((i, head) in TajweedHead.entries.withIndex()) {
                    val a = arrays[i] ?: continue
                    val per = (if (head.frameMs == 40) 1 else 2) * head.width
                    m[head] = a.copyOfRange(at * per, (at + k) * per)
                }
                out.add(TajweedHeads(k, m, top2.copyOfRange(at * 5, (at + k) * 5)))
                at += k
            }
            seen = n
            return out
        }

        private fun chunkFrames(total: Int): Int = if (chunkMs > 0) chunkMs / 40 else total

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            if (!StageClock.on) {
                TajweedJni.streamPush(s, samples, offset, length)
                return take()
            }
            val t0 = System.nanoTime()
            TajweedJni.streamPush(s, samples, offset, length)
            val t1 = StageClock.lap(StageClock.FORWARD, t0)
            return take().also { StageClock.lap(StageClock.COPY_OUT, t1) }
        }

        override fun finish(): List<TajweedHeads> {
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

    /** The engine's config for [config]'s three layers and [backend] (zf_tajweed.h API 2: every
     *  setting the SDK has, the backend explicitly). Destroy with TajweedJni.configDestroy. */
    private fun engineConfig(config: TajweedConfig, backend: TajweedBackend): Long {
        val c = TajweedJni.configCreate(h())
        try {
            TajweedJni.configSetDebugBuild(c, config.debugBuild)
            config.remoteJson?.let { TajweedJni.configSetRemote(c, it.toByteArray(Charsets.UTF_8)) }
            val app = config.app
            fun ok(r: Int, what: String) {
                if (r != 0) throw RecognizerException.InvalidAppOption(what, "the engine refused it")
            }
            ok(TajweedJni.configSetApp(c, 6, backend.key, null, 0.0, 0), "backend")
            app.preset?.let { ok(TajweedJni.configSetApp(c, 0, it.key, null, 0.0, 0), "preset") }
            for ((cls, o) in app.overrides) {
                when (o) {
                    is ClassOverride.Preset -> ok(TajweedJni.configSetApp(c, 1, cls.key, o.preset.key, 0.0, 0), "overrides.${cls.key}")
                    is ClassOverride.Threshold -> ok(TajweedJni.configSetApp(c, 2, cls.key, null, o.threshold, 0), "overrides.${cls.key}")
                }
            }
            for ((f, p) in app.featurePresets) ok(TajweedJni.configSetApp(c, 1, f.key, p.key, 0.0, 0), "overrides.${f.key}")
            for ((check, on) in app.checks) ok(TajweedJni.configSetApp(c, 4, check.key, null, 0.0, if (on) 1 else 0), "checks.${check.key}")
            app.context?.let { ok(TajweedJni.configSetApp(c, 5, it, null, 0.0, 0), "context") }
            when (val w = config.word) {
                is WordOption.Level -> TajweedJni.configSetWord(c, 0, w.level.key, 0.0).let { if (it == -1) ok(it, "word") }
                is WordOption.Threshold -> TajweedJni.configSetWord(c, 1, null, w.threshold).let { if (it == -1) ok(it, "word") }
                null -> {}
            }
            return c
        } catch (e: Throwable) {
            TajweedJni.configDestroy(c)
            throw e
        }
    }

    internal fun configJson(config: TajweedConfig, backend: TajweedBackend): String {
        val c = engineConfig(config, backend)
        try {
            return TajweedJni.configJson(c) ?: throw RecognizerException.EngineFailed("zf_tajweed_config_json failed")
        } finally {
            TajweedJni.configDestroy(c)
        }
    }

    override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult {
        val c = engineConfig(config, settings.backend)
        try {
            val arrays = Array(TajweedHead.entries.size) { heads[TajweedHead.entries[it]] }
            val r = TajweedJni.score(h(), c, arrays, heads.n40, surah, ayah)
            try {
                return result(r, surah, ayah, settings, c)
            } finally {
                TajweedJni.resultFree(r)
            }
        } finally {
            TajweedJni.configDestroy(c)
        }
    }

    private fun result(r: Long, surah: Int, ayah: Int, settings: ResolvedTajweedSettings, wordSettings: Long? = null): TajweedResult {
        fun status(v: Double) = CheckStatus.entries[v.toInt()]
        fun id(v: Double) = v.toInt().takeIf { it >= 0 }
        val s = TajweedJni.resultRecords(r, 0)
        val sites = (0 until s.size / 13).map { i ->
            val f = s.copyOfRange(i * 13, i * 13 + 13)
            SiteVerdict(SiteClass.entries[f[0].toInt()], status(f[1]), f[2].toInt(), f[3].toInt(), id(f[4]), id(f[5]),
                f[6], f[7], f[8], f[9], f[10] != 0.0, f[11].toInt(), f[12].toInt())
        }
        val md = TajweedJni.resultRecords(r, 1)
        val madd = (0 until md.size / 13).map { i ->
            val f = md.copyOfRange(i * 13, i * 13 + 13)
            MaddVerdict(f[0].toInt(), f[1].toInt(), f[2].toInt(), MaddClass.entries[f[3].toInt()], status(f[4]), f[5], f[6].toInt(),
                f[7], f[8].toInt(), f[9].toInt(), f[10] != 0.0, f[11].toInt(), f[12].toInt())
        }
        fun letters(kind: Int): List<LetterVerdict> {
            val l = TajweedJni.resultRecords(r, kind)
            return (0 until l.size / 9).map { i ->
                val f = l.copyOfRange(i * 9, i * 9 + 9)
                LetterVerdict(f[0].toInt(), f[1].toInt(), f[2].toInt(), status(f[3]), f[4], f[5], f[6] != 0.0, f[7].toInt(), f[8].toInt())
            }
        }
        val sm = TajweedJni.resultRecords(r, 4)
        val summary = TajweedSummary(sm[0].toInt(), sm[1].toInt(), sm[2].toInt(), sm[3].toInt(), sm[4].toInt(), sm[5].toInt(),
            sm[6].toInt(), sm[7].toInt(), sm[8], sm[9] != 0.0)
        // API 3: the engine's own per-word readout and the word setting it used
        val ws = wordSettings?.let { TajweedJni.configWord(it) }
        val wr = if (ws != null) TajweedJni.wordRecords(r) else null
        var words: List<WordVerdict> = emptyList()
        var wordSet: WordSettings? = null
        if (ws != null && wr != null && ws[0] != 0.0) {
            val preset = ws[4].toInt()
            wordSet = WordSettings(if (preset in 0..2) WordLevel.entries[preset] else null, ws[6],
                SettingSource.entries.first { it.ordinal == ws[5].toInt() }, true)
            val byWord = sites.groupBy { it.word }
            words = (0 until wr.size / 14).map { i ->
                val f = wr.copyOfRange(i * 14, i * 14 + 14)
                val w = f[0].toInt()
                val s = byWord[w].orEmpty()
                val (lo, hi) = WordScores.frames(s)
                val has = f[3] != 0.0
                WordVerdict(w, if (has) f[4] else -WordCalibration.CLIP, f[5], f[6] != 0.0, has,
                    if (has) SiteClass.entries[f[7].toInt()] else null, if (has) WordEdit.entries[f[8].toInt()] else null,
                    f[9].toInt(), id(f[10]), id(f[11]), f[12], f[13], f[1].toInt(), f[2].toInt(), lo, hi, s)
            }
        }
        return TajweedResult(surah, ayah, settings, sites, madd, letters(2), letters(3), summary, TajweedJni.resultJson(r),
            words, wordSet, wordsFromEngine = wordSet != null)
    }

    override fun expectedTokens(surah: Int, ayah: Int): IntArray =
        TajweedJni.expectedTokens(h(), surah, ayah) ?: throw IllegalArgumentException("no ayah $surah:$ayah in the expected table")

    override fun expectedWords(surah: Int, ayah: Int): IntArray =
        TajweedJni.expectedWords(h(), surah, ayah) ?: throw IllegalArgumentException("no ayah $surah:$ayah in the expected table")

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

/** ZF_TAJWEED_HEAD_* bits of [heads] (TajweedHead order is the bit order). */
internal fun mask(heads: Set<TajweedHead>): Int = heads.fold(0) { m, h -> m or (1 shl h.ordinal) }
