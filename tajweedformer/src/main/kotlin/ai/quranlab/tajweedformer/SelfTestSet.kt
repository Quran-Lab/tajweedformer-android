package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.Divergence
import ai.quranlab.tajweedformer.accel.Reasons
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The self-test set (the golden set), shipped in the model folder under `selftest/` (role `selftest`): five clips as
 * 16 kHz int16 (the three Basmala must-decode clips, Husary_112001, and Husary_112001_vsub2, a clip with a spliced
 * vowel that int8 and float builds hear differently) and per context the greedy tokens of the exact CPU engine.
 * An accelerator is promoted only when its RAW outputs on every clip stay within the build's signed tolerance of the
 * CPU engine's ([judgeGolden]); its speed is measured but never decides. The same clips are the model's sample clips.
 */
internal class SelfTestSet(val clips: List<Clip>, val rules: Map<String, Any?>) {
    data class Clip(
        val id: String, val file: String, val samples: Int, val sampleRate: Int, val mustDecode: Boolean,
        /** `clean` (a real recitation: its tokens must be reproduced) or `planted` (a spliced clip: a known int8
         *  against float difference, so its tokens are not compared; it enters the log-prob distances). */
        val kind: String = CLEAN,
    )

    /** One context's expected tokens (`expected_<ctx>.json`; only `tokens` of each clip is read). */
    class Expected(val context: String, val clips: Map<String, ClipExpected>)

    data class ClipExpected(val tokens: List<Int>)

    /** What a backend produced on one clip: the raw heads of the whole clip and the chunk times. */
    class ClipObserved(
        val chunks: Int,
        val heads: HeadValues,
        /** Compute time of each chunk, ms. */
        val chunkMs: List<Double> = emptyList(),
        /** The WARM chunks' times (not the clip's first chunk, not the flush); null: [chunkMs] without its first. */
        val warmChunkMs: List<Double>? = null,
    ) {
        /** Greedy tokens of log_probs (collapsed, blank removed). */
        val tokens: List<Int> by lazy { heads.greedy().toList() }
        val finite: Boolean by lazy { SelfTest.allFinite(heads) }

        /** The chunk times the limits are computed from (warm chunks only). */
        fun warmMs(): List<Double> = warmChunkMs ?: chunkMs.drop(1)
    }

    /** The raw measures of one CTC head over the set (SPEC section 5), and the frames they were taken over. */
    data class HeadMeasure(val tokenEdits: Int, val lpMean: Double, val lpP99: Double, val frames: Int = 0)

    /** Every measure of a golden run: per compared CTC head, the attribute heads' pooled mean probability difference
     *  (null when the build returns none) over [values] values, and the compared heads whose shape differed. */
    data class Measures(val heads: Map<String, HeadMeasure>, val pMean: Double?, val shape: List<String> = emptyList(), val values: Long = 0)
    companion object {
        const val FORMAT = "quranlab.selftest/1"
        const val EXPECTED_FORMAT = "quranlab.selftest-expected/1"
        const val DIR = "selftest"
        const val CLEAN = "clean"
        const val PLANTED = "planted"

        /** The clean clips (id, must decode), from the engine's parity audio. */
        @JvmField
        val CLIPS = listOf("Husary_001001" to true, "Abdul_001001" to true, "Saood_001001" to true, "Husary_112001" to false)

        /** The spliced clip (a vowel substitution spliced into Husary 112:1). */
        const val PLANTED_CLIP = "Husary_112001_vsub2"

        /** Every clip a golden run must decode: the clean ones and the planted one. An expected file without one of
         *  them fails the golden stage (`missing_clip`). */
        @JvmField
        val REQUIRED = CLIPS.map { it.first } + PLANTED_CLIP

        /** The CTC heads a golden run compares, when the accelerator build returns them. */
        @JvmField
        val COMPARED = listOf(HeadId.LOG_PROBS, HeadId.INTER1_LOG_PROBS, HeadId.TIMING_LOG_PROBS)

        @JvmStatic
        fun read(dir: File): SelfTestSet {
            val m = J.obj(Json.parse(File(dir, "selftest.json").readText()), "selftest.json")
            if (m["format"] != FORMAT) throw RecognizerException.ManifestInvalid("selftest.json format ${m["format"]}")
            val clips = J.list(m, "clips").map { v ->
                val o = J.obj(v, "clips")
                Clip(J.str(o, "id"), J.str(o, "file"), J.int(o, "samples"), J.int(o, "sample_rate"), o["must_decode"] == true,
                    (o["kind"] as? String) ?: if (o["planted"] != null) PLANTED else CLEAN)
            }
            @Suppress("UNCHECKED_CAST")
            return SelfTestSet(clips, (m["rules"] as? Map<String, Any?>) ?: emptyMap())
        }

        @JvmStatic
        fun readExpected(dir: File, context: String): Expected {
            val m = J.obj(Json.parse(File(dir, "expected_$context.json").readText()), "expected_$context.json")
            if (m["format"] != EXPECTED_FORMAT) throw RecognizerException.ManifestInvalid("expected_$context.json format ${m["format"]}")
            val clips = J.obj(m["clips"], "clips").mapValues { (id, v) ->
                ClipExpected(J.list(J.obj(v, id), "tokens").map { (it as Double).toInt() })
            }
            return Expected(J.str(m, "context"), clips)
        }

        /** A clip's int16 file as the float samples the SDK pushes (q / 32768). */
        @JvmStatic
        fun samples(file: File): FloatArray {
            val sb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        }

        /** float to int16 as the producer does: clamp(round(x * 32767)) (round half to even). */
        @JvmStatic
        fun toS16(x: FloatArray): ShortArray = ShortArray(x.size) { Math.rint(x[it] * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort() }

        /** The golden stage's verdict: [reason] a pipeline reason (`ok`, `cpu_mismatch`, `golden_mismatch`, `non_finite`),
         *  the clip that failed, how far it diverged, what was measured and which measures were out of tolerance. */
        data class GoldenVerdict(val passed: Boolean, val reason: String, val detail: String, val clip: String? = null,
                                 val divergence: Divergence? = null, val measures: Measures? = null, val failed: List<String> = emptyList())

        /**
         * One head of one clip as plain numbers: [frames] rows of [width] float32 values. The golden measures are
         * defined on these, whatever model they come from.
         */
        class RawHead(val frames: Int, val width: Int, val values: FloatArray) {
            /** Greedy tokens: per frame the largest value (the lowest id on ties), runs collapsed, blank removed. */
            fun greedy(blank: Int = ModelFolder.BLANK): IntArray {
                val out = ArrayList<Int>()
                var prev = -1
                for (f in 0 until frames) {
                    var best = 0
                    for (k in 1 until width) if (values[f * width + k] > values[f * width + best]) best = k
                    if (best != blank && best != prev) out.add(best)
                    prev = best
                }
                return out.toIntArray()
            }
        }

        /** The heads of [h] by name, as [RawHead]. */
        internal fun raw(h: HeadValues): Map<String, RawHead> =
            h.available.associate { id -> id.key to RawHead(h.frames(id), id.width, h[id]!!) }

        /** The attribute heads whose probabilities a golden run compares. */
        @JvmField
        val COMPARED_ATTR = HeadId.entries.filter { it.kind == HeadKind.PROBS }.map { it.key }

        /**
         * The raw measures of the accelerator's outputs against the CPU engine's over [ids] (the clips, in order; SPEC
         * section 5). Values are float32, the arithmetic float64. Per compared CTC head H the accelerator returns: the
         * greedy token edit distance summed over the CLEAN clips; per frame d(f) = max over tokens of
         * |clip(a) - clip(c)| with clip(x) = min(0, max(-30, x)); `lp_mean` the sum of d over every frame of every clip
         * divided by the frames; `lp_p99` the value at index (99 n + 99) / 100 - 1 of the ascending d. For the
         * attribute heads it returns (probabilities: `gem`, `attr_*`), pooled: `p_mean`, the sum of |a - c| over every
         * value divided by the values. A head whose frame count or width differs between the two runs on a clip has
         * no measures and is listed in [Measures.shape].
         */
        @JvmStatic
        fun measureRaw(ids: List<String>, cpu: Map<String, Map<String, RawHead>>, accelerator: Map<String, Map<String, RawHead>>,
                       planted: Set<String>): Measures {
            val heads = LinkedHashMap<String, HeadMeasure>()
            val shape = ArrayList<String>()
            fun returned(h: String) = ids.isNotEmpty() && ids.all { accelerator.getValue(it).containsKey(h) && cpu.getValue(it).containsKey(h) }
            fun sameShape(h: String) = ids.all {
                val a = accelerator.getValue(it).getValue(h)
                val c = cpu.getValue(it).getValue(h)
                a.frames == c.frames && a.width == c.width
            }
            for (h in COMPARED.map { it.key }) {
                if (!returned(h)) continue
                if (!sameShape(h)) { shape += h; continue }
                var edits = 0
                val d = ArrayList<Double>()
                var sum = 0.0
                for (id in ids) {
                    val c = cpu.getValue(id).getValue(h)
                    val a = accelerator.getValue(id).getValue(h)
                    if (id !in planted) edits += SelfTest.editDistance(c.greedy(), a.greedy())
                    val w = c.width
                    for (f in 0 until c.frames) {
                        var m = 0.0
                        for (t in 0 until w) {
                            val p = minOf(0.0, maxOf(-30.0, c.values[f * w + t].toDouble()))
                            val q = minOf(0.0, maxOf(-30.0, a.values[f * w + t].toDouble()))
                            val e = abs(q - p)
                            if (e > m) m = e
                        }
                        d.add(m)
                        sum += m
                    }
                }
                val n = d.size
                d.sort()
                heads[h] = HeadMeasure(edits, if (n == 0) 0.0 else sum / n, if (n == 0) 0.0 else d[(99 * n + 99) / 100 - 1], n)
            }
            var sum = 0.0
            var n = 0L
            for (h in COMPARED_ATTR) {
                if (!returned(h)) continue
                if (!sameShape(h)) { shape += h; continue }
                for (id in ids) {
                    val x = cpu.getValue(id).getValue(h).values
                    val y = accelerator.getValue(id).getValue(h).values
                    for (i in x.indices) {
                        sum += abs(y[i].toDouble() - x[i].toDouble())
                        n++
                    }
                }
            }
            return Measures(heads, if (n > 0) sum / n else null, shape, n)
        }

        /**
         * The pass rule on the raw outputs of the clips of [expected] (their greedy tokens, in order). `cpu_mismatch`
         * is decided first and wins: the CPU run's greedy tokens of `log_probs` differ from a clip's expected tokens,
         * clean or planted. Else `golden_mismatch` when a compared head differs in shape or a measure is out of
         * tolerance: with a tolerance entry for a CTC head, `token_edits`, `lp_mean` and `lp_p99` each at most the
         * entry's; without one, `token_edits` 0 and the distances recorded only; `p_mean` at most the `attr` entry's
         * when the tolerance has one. [GoldenVerdict.failed] lists what was out of tolerance, shape failures first.
         */
        @JvmStatic
        fun judgeRaw(expected: Map<String, List<Int>>, cpu: Map<String, Map<String, RawHead>>, accelerator: Map<String, Map<String, RawHead>>,
                     planted: Set<String>, tolerance: ModelManifest.RawTolerance?): GoldenVerdict {
            val ids = expected.keys.toList()
            val m = measureRaw(ids, cpu, accelerator, planted)
            val failed = ArrayList<String>()
            for (h in m.shape) failed += "$h.shape"
            for ((name, x) in m.heads) {
                val t = tolerance?.heads?.get(name)
                if (x.tokenEdits > (t?.tokenEdits ?: 0)) failed += "$name.token_edits"
                if (t != null && x.lpMean > t.lpMean) failed += "$name.lp_mean"
                if (t != null && x.lpP99 > t.lpP99) failed += "$name.lp_p99"
            }
            val pm = m.pMean
            val pt = tolerance?.pMean
            if (pm != null && pt != null && pm > pt) failed += "attr.p_mean"
            val lp = HeadId.LOG_PROBS.key
            var tokensExpected = 0
            var first: String? = null
            for (id in ids) {
                if (id in planted) continue
                val c = cpu.getValue(id)[lp]?.greedy() ?: IntArray(0)
                tokensExpected += c.size
                if (first == null && !c.contentEquals(accelerator.getValue(id)[lp]?.greedy() ?: IntArray(0))) first = id
            }
            val text = m.heads.entries.joinToString("; ") { (k, x) -> "$k ${x.tokenEdits} edits, lp_mean ${fmt(x.lpMean)}, lp_p99 ${fmt(x.lpP99)}" } +
                (pm?.let { "; attr p_mean ${fmt(it)}" } ?: "")
            val d = divergence(tokensExpected, m, tolerance, failed)
            // the CPU engine in this run against the shipped expected tokens: exact on every clip, or nothing is promoted
            for ((id, want) in expected) {
                val got = (cpu.getValue(id)[lp]?.greedy() ?: IntArray(0)).toList()
                if (got != want) {
                    return GoldenVerdict(false, Reasons.CPU_MISMATCH, "CPU vs expected: tokens $id: ${got.size} tokens vs ${want.size}", id, d, m, failed)
                }
            }
            if (failed.isNotEmpty()) {
                return GoldenVerdict(false, Reasons.GOLDEN_MISMATCH, "accelerator vs CPU: ${failed.joinToString(", ")} out of tolerance ($text)", first, d, m, failed)
            }
            return GoldenVerdict(true, "ok", "${ids.size} clips; $text", first, d, m)
        }

        /**
         * The golden stage's verdict on a run of the self-test set: the five [required] clips must be in the expected
         * file and decoded by both runs, every value must be finite (`non_finite` for the accelerator), then
         * [judgeRaw] decides.
         */
        @JvmStatic
        @JvmOverloads
        fun judgeGolden(
            expected: Expected, cpu: Map<String, ClipObserved>, accelerator: Map<String, ClipObserved>, planted: Set<String>,
            /** The build's signed allowance (the manifest's `golden_raw_tolerance`); null: tokens only. */
            tolerance: ModelManifest.RawTolerance? = null,
            /** The clips that must be in the expected file (the golden stage: [REQUIRED]). */
            required: List<String> = REQUIRED,
        ): GoldenVerdict {
            for (id in required) {
                if (id !in expected.clips) return GoldenVerdict(false, Reasons.CPU_MISMATCH, "CPU vs expected: missing_clip $id: not in expected_${expected.context}.json", id)
            }
            val ids = expected.clips.keys.toList()
            for (id in ids) {
                if (cpu[id] == null) return GoldenVerdict(false, Reasons.CPU_MISMATCH, "CPU vs expected: missing_clip $id: not decoded", id)
                if (!cpu.getValue(id).finite) return GoldenVerdict(false, Reasons.CPU_MISMATCH, "CPU vs expected: non_finite $id", id)
                if (accelerator[id] == null) return GoldenVerdict(false, Reasons.GOLDEN_MISMATCH, "accelerator: $id not decoded", id)
                if (!accelerator.getValue(id).finite) return GoldenVerdict(false, Reasons.NON_FINITE, "accelerator: $id has NaN or inf", id)
            }
            return judgeRaw(expected.clips.mapValues { it.value.tokens }, ids.associateWith { raw(cpu.getValue(it).heads) },
                ids.associateWith { raw(accelerator.getValue(it).heads) }, planted, tolerance)
        }
        private fun divergence(tokensExpected: Int, m: Measures, tolerance: ModelManifest.RawTolerance?, failed: List<String>): Divergence {
            val heads = LinkedHashMap<String, HeadMeasure?>(m.heads)
            for (h in m.shape) heads[h] = null
            return Divergence(tokensExpected, heads, m.pMean, m.values, tolerance, failed, m.heads[HeadId.LOG_PROBS.key]?.tokenEdits ?: 0)
        }

        /** The nearest-rank percentile: the value at rank ceil(q * n) of the sorted values. */
        internal fun percentile(v: List<Double>, q: Double): Double {
            if (v.isEmpty()) return Double.NaN
            val s = v.sorted()
            return s[(ceil(q * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
        }

        private fun fmt(x: Double) = String.format(java.util.Locale.ROOT, "%.4f", x)
    }
}

/** Runs the self-test set through an engine and keeps each clip's raw heads and chunk times. */
internal object SelfTestRunner {
    /** Audio pushed per call: one c16 chunk's advance at 16 kHz (the golden run's abort checks fall between pieces). */
    const val PIECE = 5120

    /** Every clip of [set] through [engine] (one stream per clip, pushed in 320 ms pieces). */
    @JvmStatic
    fun run(engine: TajweedEngine, set: SelfTestSet, dir: File): Map<String, SelfTestSet.ClipObserved> =
        run({ engine.createStream() }, set, dir) { false }

    /**
     * The same through streams [open] makes (the accelerator path a session uses), stopping with
     * [ai.quranlab.tajweedformer.accel.StageAborted] between pieces when [abort] says so (the stream is closed first).
     */
    @JvmStatic
    fun run(open: () -> TajweedEngineStream, set: SelfTestSet, dir: File, abort: () -> Boolean): Map<String, SelfTestSet.ClipObserved> {
        val out = LinkedHashMap<String, SelfTestSet.ClipObserved>()
        for (c in set.clips) {
            val samples = SelfTestSet.samples(File(dir, c.file))
            val times = ArrayList<Double>()
            val warm = ArrayList<Double>()
            val chunks = ArrayList<HeadValues>()
            if (abort()) throw ai.quranlab.tajweedformer.accel.StageAborted()
            open().use { s ->
                var at = 0
                while (at < samples.size) {
                    if (abort()) throw ai.quranlab.tajweedformer.accel.StageAborted()
                    val k = minOf(PIECE, samples.size - at)
                    val t0 = System.nanoTime()
                    val got = s.pushAudio(samples, at, k)
                    val dt = (System.nanoTime() - t0) / 1e6
                    // a call that ran the clip's first chunk is cold (the stream's first network call)
                    val firstCall = chunks.isEmpty()
                    for (g in got) {
                        times.add(dt / got.size)
                        if (!firstCall) warm.add(dt / got.size)
                    }
                    chunks += got
                    at += k
                }
                val t0 = System.nanoTime()
                val got = s.finish()
                val dt = (System.nanoTime() - t0) / 1e6
                for (g in got) times.add(dt / got.size)
                val take = s.takeHeads()
                if (take != null) {
                    chunks.clear()
                    chunks += take
                } else {
                    chunks += got
                }
            }
            out[c.id] = SelfTestSet.ClipObserved(chunks.size, HeadValues.concat(chunks), times, warm)
        }
        return out
    }
}
