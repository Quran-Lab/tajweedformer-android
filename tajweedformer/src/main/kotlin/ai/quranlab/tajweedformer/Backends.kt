package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.EngineFrontEnd
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import ai.quranlab.tajweedformer.npu.NpuStreamer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Which backend a recognizer should run. */
enum class BackendPreference {
    /** The NPU when this device has a verified build that passes its self-test, else the CPU. */
    AUTO,
    /** The exact CPU engine only. */
    CPU,
}

/**
 * A short built-in recording the backends are checked with before use: 1.72 s of 16 kHz float audio
 * (Saood_112002 of the engine's conformance set) and the greedy tokens the exact engine gives for it
 * in each context (bit-exact on every device, so the CPU check is an exact comparison).
 */
class SelfTestClip(val samples: FloatArray, val expectedTokens: Map<String, IntArray>) {
    companion object {
        private const val RESOURCE = "/ai/quranlab/tajweedformer/selftest_16k.f32"

        /** The tokens of the exact engine (engine conformance v4_c16_l64_audio_expected.jsonl for
         *  c16_l64; the host build of the same engine for the other contexts). */
        private val EXPECTED = mapOf(
            "c16_l64" to intArrayOf(2, 31, 24, 34, 27, 33, 15, 31, 25, 31, 9),
        ) + SelfTestTokens.OTHER_CONTEXTS

        @JvmStatic
        fun builtIn(): SelfTestClip {
            val bytes = SelfTestClip::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
                ?: throw RecognizerException.ModelMissing("the self-test clip $RESOURCE is not in the SDK")
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return SelfTestClip(FloatArray(fb.remaining()).also { fb.get(it) }, EXPECTED)
        }
    }
}

/**
 * The backend checks (contract: the CPU path self-tests before any NPU download; the NPU self-tests
 * against the CPU before use).
 *
 * CPU rule: the clip's greedy tokens equal the expected tokens of the context exactly (the engine is
 * bit-exact on every device); a context with no expected tokens passes when the tokens are not empty
 * and every head value is finite.
 *
 * NPU rule (the NPU is int16 and the CPU engine int8, so their heads differ slightly by design): the
 * same number of 40 ms frames, every head value finite, the greedy tokens within one edit of the
 * CPU's, and the per-frame top-1 token of log_probs equal on at least 90 % of the frames. On the
 * 11-07 device runs (NPU.md) whole clips gave identical greedy tokens to fp32 in 117 to 120 of 120,
 * and frame top-1 agreement was 99.6 % or more, so a working NPU passes with margin while a wrong
 * build, a wrong mask table or wrong I/O bytes do not.
 */
object SelfTest {
    data class Outcome(val result: SelfTestResult, val heads: TajweedHeads?, val chunkMs: List<Double> = emptyList())

    private val cpuCache = ConcurrentHashMap<String, Outcome>()

    /** The heads of [clip] through [engine] (one stream, closed after). */
    internal fun run(engine: TajweedEngine, clip: FloatArray, sampleRate: Int, times: MutableList<Double>? = null): TajweedHeads {
        val samples = if (sampleRate == 16_000) clip else throw IllegalStateException("the self-test runs at 16 kHz")
        engine.createStream().use { s ->
            val out = ArrayList<TajweedHeads>()
            var at = 0
            while (at < samples.size) {
                val k = minOf(5120, samples.size - at)
                val t0 = System.nanoTime()
                val got = s.pushAudio(samples, at, k)
                val dt = (System.nanoTime() - t0) / 1e6
                got.forEach { _ -> times?.add(dt / got.size) }
                out += got
                at += k
            }
            out += s.finish()
            return TajweedHeads.concat(out)
        }
    }

    /** The CPU check, once per model folder, revision and context in a process. */
    @JvmStatic
    fun cpu(engine: TajweedEngine, model: TajweedModel, context: String, clip: SelfTestClip = SelfTestClip.builtIn()): Outcome {
        val key = model.dir.absolutePath + "|" + model.manifest.revision + "|" + context
        cpuCache[key]?.let { return it }
        val t0 = System.nanoTime()
        val outcome = try {
            val times = ArrayList<Double>()
            val heads = run(engine, clip.samples, 16_000, times)
            val tokens = heads.greedy()
            val want = clip.expectedTokens[context]
            val finite = allFinite(heads)
            val passed = finite && if (want != null) tokens.contentEquals(want) else tokens.isNotEmpty()
            val detail = "tokens ${tokens.size}" + (if (want != null) (if (tokens.contentEquals(want)) " = expected" else " != expected ${want.size}") else " (no expected tokens for $context)") +
                (if (finite) "" else ", non-finite values")
            Outcome(SelfTestResult(Backend.CPU, passed, detail, ms(t0), context = context, revision = model.manifest.revision), heads, times)
        } catch (e: Exception) {
            Outcome(SelfTestResult(Backend.CPU, false, "failed: ${e.message}", ms(t0), context = context, revision = model.manifest.revision), null)
        }
        if (outcome.result.passed) cpuCache[key] = outcome
        SdkEvents.emit { it.onSelfTest(outcome.result) }
        return outcome
    }

    /** The NPU check against [cpuHeads] (the CPU's heads of the same clip). */
    @JvmStatic
    fun npu(engine: TajweedEngine, cpuHeads: TajweedHeads, soc: String?, context: String, clip: SelfTestClip = SelfTestClip.builtIn(), revision: String? = null): SelfTestResult {
        val t0 = System.nanoTime()
        val r = try {
            val heads = run(engine, clip.samples, 16_000)
            val v = compare(cpuHeads, heads)
            SelfTestResult(Backend.NPU, v.first, v.second, ms(t0), soc, context, revision)
        } catch (e: Exception) {
            SelfTestResult(Backend.NPU, false, "failed: ${e.javaClass.simpleName}: ${e.message}", ms(t0), soc, context, revision)
        }
        SdkEvents.emit { it.onSelfTest(r) }
        return r
    }

    /** The NPU rule (see the object's doc): (passed, detail). */
    @JvmStatic
    fun compare(cpu: TajweedHeads, npu: TajweedHeads): Pair<Boolean, String> {
        if (npu.n40 != cpu.n40) return false to "frames ${npu.n40} != CPU ${cpu.n40}"
        if (!allFinite(npu)) return false to "non-finite values"
        val missing = TajweedHead.SCORER_HEADS.filter { !npu.has(it) }
        if (missing.isNotEmpty()) return false to "heads missing: ${missing.joinToString { it.key }}"
        val a = cpu.greedy()
        val b = npu.greedy()
        val d = editDistance(a, b)
        val w = TajweedHead.LOG_PROBS.width
        val x = cpu[TajweedHead.LOG_PROBS]!!
        val y = npu[TajweedHead.LOG_PROBS]!!
        var same = 0
        for (t in 0 until cpu.n40) if (argmax(x, t * w, w) == argmax(y, t * w, w)) same++
        val agree = if (cpu.n40 == 0) 0.0 else same.toDouble() / cpu.n40
        val ok = a.isNotEmpty() && d <= MAX_TOKEN_EDITS && agree >= MIN_TOP1_AGREEMENT
        return ok to "tokens ${b.size} vs CPU ${a.size}, $d edits; frame top-1 agreement ${String.format(java.util.Locale.ROOT, "%.3f", agree)}"
    }

    const val MAX_TOKEN_EDITS = 1
    const val MIN_TOP1_AGREEMENT = 0.9

    private fun argmax(v: FloatArray, at: Int, w: Int): Int {
        var b = 0
        for (k in 1 until w) if (v[at + k] > v[at + b]) b = k
        return b
    }

    internal fun allFinite(h: TajweedHeads): Boolean = h.available.all { k -> h[k]!!.all { it.isFinite() } }

    internal fun editDistance(a: IntArray, b: IntArray): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Forgets the CPU results (tests). */
    internal fun clear() = cpuCache.clear()
}

/**
 * The NPU backend: the exact engine's front end (resampler, fbank, flush) on the CPU, the network on
 * the NPU ([NpuStreamer] over an [NpuRunner]), the scorer and the expected table from [cpu]. A run
 * that throws mid-take moves the take to the CPU: the audio of the take so far is replayed through
 * the CPU engine, the take's heads become the CPU's, and the engine stays on the CPU from then on
 * ([failure] is set, [onFallback] called).
 */
internal class NpuTajweedEngine(
    private val cpu: TajweedEngine,
    private val contract: NpuContract,
    private val masks: NpuMasks,
    private val runner: NpuRunner,
    private val sampleRate: Int,
    private val onFallback: (String) -> Unit,
) : TajweedEngine {
    override val chunkMs: Int get() = contract.chunkMs
    override val backend: Backend get() = if (failure == null) Backend.NPU else Backend.CPU

    @Volatile var failure: String? = null
        private set

    /** Off during the self-test: there an NPU error must fail the test, not move to the CPU. */
    @Volatile var fallbackEnabled = false

    override fun createStream(): TajweedEngineStream =
        if (failure != null) cpu.createStream() else NpuStream()

    override fun createStream(heads: Set<TajweedHead>): TajweedEngineStream =
        if (failure != null) cpu.createStream(heads) else NpuStream(heads)

    /** The NPU computes every head; a fallback CPU stream computes [heads]. */
    private inner class NpuStream(private val heads: Set<TajweedHead> = TajweedHead.SCORER_HEADS) : TajweedEngineStream {
        private val fe = EngineFrontEnd(sampleRate)
        private val streamer = NpuStreamer(contract, masks, runner)
        private val audio = FloatList()
        private var delivered = 0
        private var cpuStream: TajweedEngineStream? = null
        private var cpuHeads: ArrayList<TajweedHeads>? = null

        override val backend: Backend get() = if (cpuStream == null) Backend.NPU else Backend.CPU

        private fun fallback(e: Exception, pending: FloatArray?, offset: Int, length: Int, finishing: Boolean): List<TajweedHeads> {
            if (!fallbackEnabled) throw e
            val why = "${e.javaClass.simpleName}: ${e.message}"
            failure = why
            onFallback(why)
            val s = cpu.createStream(heads)
            cpuStream = s
            val all = ArrayList<TajweedHeads>()
            val a = audio.toArray()
            all += s.pushAudio(a, 0, a.size)
            if (pending != null) all += s.pushAudio(pending, offset, length)
            if (finishing) all += s.finish()
            cpuHeads = all
            val fresh = if (all.size > delivered) all.subList(delivered, all.size).toList() else emptyList()
            delivered = all.size
            return fresh
        }

        override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
            cpuStream?.let { s ->
                val out = s.pushAudio(samples, offset, length)
                cpuHeads!!.addAll(out)
                delivered += out.size
                return out
            }
            return try {
                val frames = fe.push(samples, offset, length)
                val out = streamer.push(frames)
                audio.add(samples, offset, length)
                delivered += out.size
                out
            } catch (e: Exception) {
                fallback(e, samples, offset, length, false)
            }
        }

        override fun finish(): List<TajweedHeads> {
            cpuStream?.let { s ->
                val out = s.finish()
                cpuHeads!!.addAll(out)
                delivered += out.size
                return out
            }
            return try {
                val out = streamer.push(fe.finish())
                delivered += out.size
                out
            } catch (e: Exception) {
                fallback(e, null, 0, 0, true)
            }
        }

        override fun takeHeads(): List<TajweedHeads>? = cpuHeads

        override fun reset() {
            cpuStream?.close()
            cpuStream = null
            cpuHeads = null
            fe.reset()
            streamer.reset()
            audio.clear()
            delivered = 0
        }

        override fun close() {
            cpuStream?.close()
            fe.close()
        }
    }

    override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult =
        cpu.score(heads, config, settings, surah, ayah)

    override fun expectedTokens(surah: Int, ayah: Int): IntArray = cpu.expectedTokens(surah, ayah)

    override fun expectedWords(surah: Int, ayah: Int): IntArray = cpu.expectedWords(surah, ayah)

    override fun close() {
        try { runner.close() } catch (_: Exception) {}
        cpu.close()
    }
}

/** A growable float array (the take's audio, kept for a fallback replay). */
internal class FloatList {
    private var a = FloatArray(16_000)
    var size = 0
        private set

    fun add(v: FloatArray, offset: Int, length: Int) {
        if (size + length > a.size) a = a.copyOf(maxOf(size + length, a.size * 2))
        System.arraycopy(v, offset, a, size, length)
        size += length
    }

    fun toArray(): FloatArray = a.copyOf(size)
    fun clear() { size = 0 }
}
