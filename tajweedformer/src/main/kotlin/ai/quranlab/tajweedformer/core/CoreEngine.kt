package ai.quranlab.tajweedformer.core

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.CoreTopology
import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelProfile
import ai.quranlab.tajweedformer.Recognizer
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.StageClock
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TopK
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One encrypted model build on this device: its signed manifest, its folders, and the lease that
 * opens it. The build's folder holds the plain files (the manifest, `calibration.json`, the sample clips), `cdn/` (the
 * downloaded CDN forms, deleted once installed) and `device/` (the device forms the Core wrote at install). No
 * plaintext of a model file is ever on disk: the Core reads the device forms and decrypts them into its own memory.
 */
internal class ProtectedBuild(
    val dir: File,
    val manifest: ModelManifest,
    val manifestBytes: ByteArray,
    val leases: LeaseClient,
    /** The recited count's feed after each engine call of a take (the Core's journal); null with analytics off. */
    @Volatile var recited: RecitedFeed? = null,
) {
    val cdnDir = File(dir, "cdn")
    val deviceDir = File(dir, "device")

    /** The lease target of this build, its manifest known; [onBase] gets each lease answer's folder. */
    fun target(onBase: (String?) -> Unit = {}): LeaseTarget = LeaseTarget(manifest.modelId, manifest.version ?: "", manifest.build,
        manifest.encryption?.keyId ?: throw RecognizerException.ModelIncompatible("${manifest.modelId} ${manifest.build} is not an encrypted build (SDK 4.0 runs encrypted builds only)"),
    ) { base -> onBase(base); manifestBytes }

    val target: LeaseTarget get() = target()

    fun roots(c: Core) = roots(dir)(c)

    companion object {
        /** The Core's storage roots for the build kept in [dir], before its manifest is on the device. */
        fun roots(dir: File): (Core) -> Unit = { c -> c.roots(dir, File(dir, "cdn"), File(dir, "device")) }
    }

    /** The Core with a valid lease of this build (asked for when there is none). Throws the app's error. */
    fun core(): Core = try {
        leases.ensure(target, ::roots)
    } catch (f: LeaseFailure) {
        throw f.error
    }

    /** Whether [e]'s device form is in place (the Core wrote and committed it). */
    fun installed(e: ModelManifest.FileEntry): Boolean = e.encoding != ModelManifest.Encoding.QLE1 || File(deviceDir, e.localPath).isFile

    /**
     * The device forms of [entries] whose CDN form is on the device: each installed by the Core (its plaintext
     * SHA-256 checked once, the install's mark written) and its CDN form deleted. The Core installs nothing while a
     * model is open, so the caller closes it first.
     */
    fun install(entries: List<ModelManifest.FileEntry>, cdnInPlace: (ModelManifest.FileEntry) -> Boolean, cdnFile: (ModelManifest.FileEntry) -> File) {
        val todo = entries.filter { it.encoding == ModelManifest.Encoding.QLE1 && !installed(it) && cdnInPlace(it) }
        if (todo.isEmpty()) return
        val c = core()
        roots(c)
        try {
            for (e in todo) {
                try {
                    c.install(manifestBytes, e.localPath)
                } catch (x: CoreException) {
                    // a container the Core refused is not kept: the next call downloads it again
                    if (x.status == Core.E_FILE) cdnFile(e).delete()
                    throw x.toError()
                }
                cdnFile(e).delete()
            }
        } finally {
            // beside an open model of another build (Core d128af8): that model's later reads stay on its own folder
            leases.restoreOpenRoots(c)
        }
    }

    /** Opens the model of [context] in the Core (the device forms decrypted into its memory). */
    fun open(context: String, threads: Int, sampleRate: Int, noPin: Int): CoreModel {
        val c = core()
        roots(c)
        val m = try { c.modelOpen(manifestBytes, context, threads, sampleRate, noPin) } catch (x: CoreException) { throw x.toError() }
        leases.modelOpened(::roots)
        return m
    }

    fun closed(m: CoreModel) {
        m.close()
        leases.modelClosed()
    }

    /** The engine's files of [context] in their device form (the CPU set less the plain files). */
    fun contextInstalled(context: String): Boolean =
        ModelProfile.cpu(manifest, context).all { installed(it) }
}

/** The recited count's hook into a take's engine calls (on the engine's thread, between pushes). */
internal fun interface RecitedFeed {
    fun fed(core: Core, stream: Long)
}

/**
 * The Core's engine for one context (the exact V4 engine inside the Core): [TajweedEngine] over qlc_model and
 * qlc_stream. The Core allows one live CPU stream per process; the stream of a take is kept and reset for the next take
 * on the same thread (a new stream starts and pins a thread pool, about 100 ms a take on the Alcatel 1).
 *
 * The accelerator (R2): once [prepareAccelerator] made the program ready in the Core and its golden test passed
 * ([promote]), a take asked for on it runs on `qlc_stream_create_on(m, QLC_BACKEND_ACCEL)`, fed from the recognizer's
 * engine thread (LiteRT runs inside push: never the real-time audio callback). A take whose accelerator stream fails
 * goes on on the CPU (the 2.0.2 failover): a CPU stream from `qlc_stream_create_on(m, QLC_BACKEND_CPU)` gets the take's
 * audio again from its start, its chunks replace the delivered ones ([TajweedEngineStream.takeRedo]), and the engine
 * runs later takes on the CPU. Every stream is destroyed before the model is closed, and the model is never closed
 * while a prepare runs.
 */
internal class CoreTajweedEngine(
    private val build: ProtectedBuild,
    context: String,
    threads: Int = 0,
    sampleRate: Int = 16_000,
    noPin: Boolean? = null,
) : TajweedEngine, AcceleratorHost {
    constructor(build: ProtectedBuild, context: String, options: Recognizer.Options) :
        this(build, context, options.threads, options.sampleRate, options.noPin)

    private var model: CoreModel? = build.open(context, CoreTopology.resolve(threads), sampleRate, when (noPin) { null -> -1; true -> 1; false -> 0 })

    /** The rate the model was opened for (its resampler's input). */
    val sampleRate: Int = sampleRate

    override val chunkMs: Int = model!!.chunkMs

    private fun m(): CoreModel = model ?: throw IllegalStateException("the engine is closed")

    /** The open model (the reference data and the vocabulary are read from it); null once closed. */
    internal val coreModel: CoreModel? get() = model

    // ---------------------------------------------------------------- the accelerator (R2)

    /** Held for a whole prepare: [close] waits for it (the Core's model is never closed under a prepare). */
    private val prepareLock = Any()
    @Volatile private var prepared: String? = null
    @Volatile private var promoted = false
    /** Told when a take falls back to the CPU (telemetry's failovers). */
    @Volatile override var onFailover: (String) -> Unit = {}

    /** The backend new takes asked for on the accelerator run on: the NPU once promoted, else the CPU. */
    override val backend: Backend get() = if (promoted) Backend.NPU else Backend.CPU

    /** The backend's name of the prepared program (`npu:<soc>`), or null. */
    val accelerator: String? get() = prepared

    /**
     * `qlc_accel_prepare` of [kind] on this model, on the calling thread (a background one: it takes seconds, the
     * decryption then the runtime's compile, while CPU takes go on). The backend's name; a [CoreException] (its
     * `QLC_D_ACCEL_*` or `QLC_D_LEASE_NO_ACCEL` detail) when the device runs the CPU engine instead.
     */
    override fun prepareAccelerator(kind: String, dispatchDir: String?): String = synchronized(prepareLock) {
        val mm = model ?: throw IllegalStateException("the engine is closed")
        val c = build.leases.core() ?: throw CoreException(Core.E_NEEDS_CONNECTION, 0, "qlc_accel_prepare")
        build.roots(c)
        c.accelPrepare(mm, kind, dispatchDir).also { prepared = it }
    }

    /** Takes asked for on the accelerator run on the prepared program from now on (its golden test passed). */
    override fun promote() {
        if (prepared != null) promoted = true
    }

    /** The program released (a failed golden test): later takes run on the CPU. On the engine thread. */
    override fun releaseAccelerator() {
        promoted = false
        prepared = null
        model?.let { CoreNative.accelRelease(it.handle) }
    }

    /**
     * The golden clips through a stream on the prepared program and one on the CPU engine, in turn (engine thread):
     * per clip the accelerator's heads and the CPU's, every head of the take, and the compute time of each warm chunk
     * of both (the measure stage: the audio pushed a chunk at a time, as a live take, the first chunk of each take left
     * out). Null when a take is open (try later). The clips are at 16 kHz; the model resamples from the rate it was
     * opened for, so they are brought to it.
     */
    override fun goldenRuns(clips: List<Pair<String, FloatArray>>): GoldenRuns? {
        if (!liveStream.compareAndSet(false, true)) return null
        try {
            synchronized(idleLock) { idle?.also { idle = null } }?.let { CoreNative.streamDestroy(it.stream) }
            val mh = m().handle
            val acc = ArrayList<Double>()
            val cpu = ArrayList<Double>()
            val runs = clips.map { (id, pcm16) ->
                val pcm = Golden.resample(pcm16, 16_000, sampleRate)
                Triple(id, listOf(collect(mh, Core.BACKEND_ACCEL, pcm, acc)), listOf(collect(mh, Core.BACKEND_CPU, pcm, cpu)))
            }
            return GoldenRuns(runs, acc, cpu)
        } finally {
            liveStream.set(false)
        }
    }

    /** One whole take on a stream of [backend]: every head, as one value set; each warm chunk's compute ms into [times]. */
    private fun collect(mh: Long, backend: Int, pcm: FloatArray, times: MutableList<Double>): HeadValues {
        val out = IntArray(2)
        val s = CoreNative.streamCreateOn(mh, backend, out)
        if (s == 0L) throw CoreException(out[0], 0, "qlc_stream_create_on")
        try {
            if (CoreNative.streamSetHeads(s, mask(HeadId.entries.toSet())) < 0) throw CoreException(Core.E_ARG, 0, "qlc_stream_set_heads")
            // a chunk's audio a push, as a live take feeds it: the time of the pushes up to a chunk is that chunk's
            val piece = maxOf(1, chunkMs * sampleRate / 1000)
            val step = maxOf(1, chunkMs / 40)
            var at = 0
            var chunks = 0
            var pending = 0L
            while (at < pcm.size) {
                val k = minOf(piece, pcm.size - at)
                val t0 = System.nanoTime()
                val r = CoreNative.streamPush(s, pcm, at, k)
                pending += System.nanoTime() - t0
                if (r < 0) throw CoreException(r, 0, "qlc_stream_push")
                at += k
                val made = maxOf(0, CoreNative.streamFrames(s)) / step - chunks
                if (made > 0) {
                    val each = pending / 1e6 / made
                    // the first chunk of a take is not warm (lazy initialisation, the first invocation)
                    repeat(made) { i -> if (chunks + i > 0) times.add(each) }
                    chunks += made
                    pending = 0L
                }
            }
            val r = CoreNative.streamFinish(s)
            if (r < 0) throw CoreException(r, 0, "qlc_stream_finish")
            val n = CoreNative.streamFrames(s)
            val arrays = if (n > 0) CoreNative.streamHeads(s, 0, n) else null
            val m = HashMap<HeadId, FloatArray>()
            if (arrays != null) for ((i, h) in HeadId.entries.withIndex()) arrays[i]?.let { m[h] = it }
            return HeadValues(maxOf(n, 0), m)
        } finally {
            CoreNative.streamDestroy(s)
        }
    }

    // ---------------------------------------------------------------- streams

    override fun createStream(): TajweedEngineStream = open(HeadId.BASE_HEADS, keep = false)

    override fun createStream(heads: Set<HeadId>): TajweedEngineStream = open(heads + HeadId.BASE_HEADS, keep = true)

    override fun createStream(heads: Set<HeadId>, accelerator: Boolean): TajweedEngineStream {
        if (!accelerator || !promoted) return createStream(heads)
        if (!liveStream.compareAndSet(false, true)) throw RecognizerException.SessionActive()
        val out = IntArray(2)
        val s = try { CoreNative.streamCreateOn(m().handle, Core.BACKEND_ACCEL, out) } catch (e: Throwable) {
            liveStream.set(false)
            throw e
        }
        if (s == 0L) {
            // the program would not take a stream: this take and the later ones run on the CPU
            liveStream.set(false)
            promoted = false
            try { onFailover("stream_create ${Core.statusName(out[0])}") } catch (_: Exception) {}
            return createStream(heads)
        }
        return try {
            Accel(s, mask(heads + HeadId.BASE_HEADS))
        } catch (e: Throwable) {
            CoreNative.streamDestroy(s)
            liveStream.set(false)
            throw e
        }
    }

    private fun open(heads: Set<HeadId>, keep: Boolean): TajweedEngineStream {
        if (!liveStream.compareAndSet(false, true)) throw RecognizerException.SessionActive()
        return try {
            val mh = m().handle
            val me = Thread.currentThread()
            val k = synchronized(idleLock) { idle.also { idle = null } }
            val kept = if (k != null && keep && k.model == mh && k.thread === me) k.stream else {
                k?.let { CoreNative.streamDestroy(it.stream) }
                0L
            }
            val s = if (kept != 0L) {
                CoreNative.streamReset(kept)
                kept
            } else {
                StageClock.streamCreates.incrementAndGet()
                val out = IntArray(2)
                CoreNative.streamCreate(mh, out).takeIf { it != 0L } ?: throw RecognizerException.EngineFailed("qlc_stream_create: ${Core.statusName(out[0])}")
            }
            Stream(s, mask(heads), keep)
        } catch (e: Throwable) {
            liveStream.set(false)
            throw e
        }
    }

    /** The chunks of frames [from, to) of stream [s]: every head it computes, split at the context's chunk size. */
    private fun chunks(s: Long, from: Int, to: Int): List<HeadValues> {
        if (to <= from) return emptyList()
        val arrays = CoreNative.streamHeads(s, from, to) ?: throw RecognizerException.EngineFailed("qlc_stream_heads failed")
        val lp = arrays[HeadId.LOG_PROBS.ordinal] ?: throw RecognizerException.EngineFailed("the stream has no log_probs")
        val n = to - from
        val top2 = TopK.top2(lp, n, HeadId.LOG_PROBS.width)
        val step = if (chunkMs > 0) chunkMs / 40 else n
        val out = ArrayList<HeadValues>()
        var at = 0
        while (at < n) {
            val k = minOf(step, n - at)
            val m = HashMap<HeadId, FloatArray>()
            for ((i, head) in HeadId.entries.withIndex()) {
                val a = arrays[i] ?: continue
                val per = (if (head.frameMs == 40) 1 else 2) * head.width
                m[head] = a.copyOfRange(at * per, (at + k) * per)
            }
            out.add(HeadValues(k, m, top2.copyOfRange(at * 5, (at + k) * 5)))
            at += k
        }
        return out
    }

    private fun feed(s: Long) {
        val r = build.recited ?: return
        try {
            val c = build.leases.core() ?: return
            r.fed(c, s)
        } catch (_: Exception) {
            // the count never disturbs recognition
        }
    }

    private inner class Stream(private var s: Long, private val heads: Int, private val keep: Boolean) : TajweedEngineStream {
        private var seen = 0

        init {
            if (CoreNative.streamSetHeads(s, heads) < 0) throw RecognizerException.EngineFailed("qlc_stream_set_heads refused")
        }

        private fun take(): List<HeadValues> {
            val n = CoreNative.streamFrames(s)
            if (n < 0) throw RecognizerException.EngineFailed("qlc_stream_heads failed")
            if (n <= seen) return emptyList()
            val out = chunks(s, seen, n)
            seen = n
            return out
        }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
            val t0 = if (StageClock.on) System.nanoTime() else 0L
            if (CoreNative.streamPush(s, samples, offset, length) < 0) throw RecognizerException.EngineFailed("qlc_stream_push failed")
            val t1 = if (StageClock.on) StageClock.lap(StageClock.FORWARD, t0) else 0L
            val out = take()
            if (out.isNotEmpty()) feed(s)
            if (StageClock.on) StageClock.lap(StageClock.COPY_OUT, t1)
            return out
        }

        override fun finish(): List<HeadValues> {
            if (CoreNative.streamFinish(s) < 0) throw RecognizerException.EngineFailed("qlc_stream_finish failed")
            val out = take()
            feed(s)
            return out
        }

        override fun reset() {
            CoreNative.streamReset(s)
            CoreNative.streamSetHeads(s, heads)
            seen = 0
        }

        override fun close() {
            if (s != 0L) {
                val mh = model?.handle ?: 0L
                val kept = keep && mh != 0L && synchronized(idleLock) {
                    if (idle == null) {
                        idle = Idle(mh, s, Thread.currentThread())
                        true
                    } else false
                }
                if (!kept) CoreNative.streamDestroy(s)
                s = 0
                liveStream.set(false)
            }
        }
    }

    /**
     * A take on the accelerator program, with the 2.0.2 failover: the take's audio is kept; when a push or the finish
     * fails, a CPU stream gets all of it again and the take goes on there, its chunks replacing the delivered ones from
     * the first ([takeRedo] 0 once), and the engine runs later takes on the CPU.
     */
    private inner class Accel(private var s: Long, private val heads: Int) : TajweedEngineStream {
        private var seen = 0
        private var onCpu = false
        private var redo = -1
        private var audio = FloatArray(16_000)
        private var audioN = 0

        init {
            if (CoreNative.streamSetHeads(s, heads) < 0) throw RecognizerException.EngineFailed("qlc_stream_set_heads refused")
        }

        override val backend: Backend get() = if (onCpu) Backend.CPU else Backend.NPU

        private fun keepAudio(x: FloatArray, off: Int, n: Int) {
            if (onCpu || n <= 0) return
            if (audioN + n > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, audioN + n))
            System.arraycopy(x, off, audio, audioN, n)
            audioN += n
        }

        private fun take(): List<HeadValues> {
            val n = CoreNative.streamFrames(s)
            if (n < 0) throw RecognizerException.EngineFailed("qlc_stream_heads failed")
            if (n <= seen) return emptyList()
            val out = chunks(s, seen, n)
            seen = n
            return out
        }

        /** The take moves to a CPU stream: its audio so far pushed again there; every chunk so far, to replace. */
        private fun failover(why: String): List<HeadValues> {
            CoreNative.streamDestroy(s)
            s = 0
            promoted = false
            onCpu = true
            try { onFailover(why) } catch (_: Exception) {}
            val out = IntArray(2)
            val cpu = CoreNative.streamCreateOn(m().handle, Core.BACKEND_CPU, out)
            if (cpu == 0L) throw RecognizerException.EngineFailed("the accelerator failed ($why) and no CPU stream could take the take: ${Core.statusName(out[0])}")
            s = cpu
            if (CoreNative.streamSetHeads(s, heads) < 0) throw RecognizerException.EngineFailed("qlc_stream_set_heads refused")
            if (audioN > 0 && CoreNative.streamPush(s, audio, 0, audioN) < 0) throw RecognizerException.EngineFailed("qlc_stream_push failed on the CPU")
            audio = FloatArray(0)
            audioN = 0
            seen = 0
            redo = 0
            return take()
        }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
            keepAudio(samples, offset, length)
            val r = CoreNative.streamPush(s, samples, offset, length)
            if (r < 0) {
                if (onCpu) throw RecognizerException.EngineFailed("qlc_stream_push failed")
                return failover("push ${Core.statusName(r)}").also { if (it.isNotEmpty()) feed(s) }
            }
            val out = take()
            if (out.isNotEmpty()) feed(s)
            return out
        }

        override fun finish(): List<HeadValues> {
            val r = CoreNative.streamFinish(s)
            if (r < 0) {
                if (onCpu) throw RecognizerException.EngineFailed("qlc_stream_finish failed")
                val again = failover("finish ${Core.statusName(r)}")
                if (CoreNative.streamFinish(s) < 0) throw RecognizerException.EngineFailed("qlc_stream_finish failed on the CPU")
                val out = again + take()
                feed(s)
                return out
            }
            val out = take()
            feed(s)
            return out
        }

        override fun takeRedo(): Int = redo.also { redo = -1 }

        override fun reset() {
            CoreNative.streamReset(s)
            CoreNative.streamSetHeads(s, heads)
            seen = 0
            audioN = 0
        }

        override fun close() {
            if (s != 0L) {
                CoreNative.streamDestroy(s)
                s = 0
                liveStream.set(false)
            }
        }
    }

    override fun close() {
        // a prepare in progress ends first: the Core's model is never closed under it
        synchronized(prepareLock) {
            val mm = model ?: return
            model = null
            promoted = false
            prepared = null
            val kept = synchronized(idleLock) { idle?.takeIf { it.model == mm.handle }?.also { idle = null } }
            kept?.let { CoreNative.streamDestroy(it.stream) }
            build.closed(mm)
        }
    }

    /** An engine stream between takes: its model, its handle, the thread that ran it (its pool's pin). */
    private class Idle(val model: Long, val stream: Long, val thread: Thread)

    companion object {
        private val liveStream = AtomicBoolean(false)
        private val idleLock = Any()
        private var idle: Idle? = null

        /** Whether the process's one engine stream is taken now. */
        internal val streamLive: Boolean get() = liveStream.get()

        /** The Core's QLC_HEADS bits of [heads] (HeadId order is the bit order). */
        fun mask(heads: Set<HeadId>): Int = heads.fold(0) { m, h -> m or (1 shl h.ordinal) }
    }
}
