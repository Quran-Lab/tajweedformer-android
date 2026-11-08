package ai.quranlab.tajweedformer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * The accelerator self-test set (LAYOUT.md "Self-test set"), shipped in the model
 * folder under `selftest/` (role `selftest`): four clips (the three Basmala must-decode clips and
 * Husary_112001) as 16 kHz int16, and per context the exact CPU engine's decisions on them at the
 * default settings. An accelerator is promoted only when it reproduces those decisions.
 */
class SelfTestSet(val clips: List<Clip>, val rules: Map<String, Any?>) {
    data class Clip(
        val id: String, val surah: Int, val ayah: Int, val file: String, val samples: Int, val sampleRate: Int, val mustDecode: Boolean,
        /** `clean` (a real recitation: its tokens must be reproduced) or `planted` (a mistake spliced in:
         *  only its flags count, its tokens may differ between exact int8 and an fp16 / int16 accelerator). */
        val kind: String = CLEAN,
    )

    /** One context's expected decisions (`expected_<ctx>.json`). */
    class Expected(
        val context: String,
        val calibrationSha256: String,
        val sitePreset: String,
        val wordPreset: String,
        val clips: Map<String, ClipExpected>,
    )

    data class ClipExpected(
        val chunks: Int,
        val tokens: List<Int>,
        /** (expected-token position, class key) of every flagged site. */
        val flaggedSites: Set<Pair<Int, String>>,
        val flaggedWords: Set<Int>,
        val wordScores: List<Double>,
    )

    /** What a backend produced on one clip. */
    data class ClipObserved(
        val chunks: Int,
        val tokens: List<Int>,
        val flaggedSites: Set<Pair<Int, String>>,
        val flaggedWords: Set<Int>,
        val finite: Boolean,
        /** Compute time of each chunk, ms. */
        val chunkMs: List<Double>,
    )

    /** The pass rule's verdict: [passed], and the first reason it failed (a stable code + detail). */
    data class Verdict(val passed: Boolean, val reason: String, val detail: String, val p90Ms: Double, val medianMs: Double, val cpuMedianMs: Double)

    companion object {
        const val FORMAT = "quranlab.selftest/1"
        const val EXPECTED_FORMAT = "quranlab.selftest-expected/1"
        const val DIR = "selftest"
        const val CLEAN = "clean"
        const val PLANTED = "planted"

        /** The four clips of LAYOUT.md (id, surah, ayah, must decode). */
        @JvmField
        val CLIPS = listOf(
            Triple("Husary_001001", 1 to 1, true), Triple("Abdul_001001", 1 to 1, true),
            Triple("Saood_001001", 1 to 1, true), Triple("Husary_112001", 112 to 1, false),
        )

        @JvmStatic
        fun read(dir: File): SelfTestSet {
            val m = J.obj(Json.parse(File(dir, "selftest.json").readText()), "selftest.json")
            if (m["format"] != FORMAT) throw RecognizerException.ManifestInvalid("selftest.json format ${m["format"]}")
            val clips = J.list(m, "clips").map { v ->
                val o = J.obj(v, "clips")
                Clip(J.str(o, "id"), J.int(o, "surah"), J.int(o, "ayah"), J.str(o, "file"), J.int(o, "samples"),
                    J.int(o, "sample_rate"), o["must_decode"] == true, (o["kind"] as? String) ?: if (o["planted"] != null) PLANTED else CLEAN)
            }
            @Suppress("UNCHECKED_CAST")
            return SelfTestSet(clips, (m["rules"] as? Map<String, Any?>) ?: emptyMap())
        }

        @JvmStatic
        fun readExpected(dir: File, context: String): Expected {
            val m = J.obj(Json.parse(File(dir, "expected_$context.json").readText()), "expected_$context.json")
            if (m["format"] != EXPECTED_FORMAT) throw RecognizerException.ManifestInvalid("expected_$context.json format ${m["format"]}")
            val clips = J.obj(m["clips"], "clips").mapValues { (id, v) ->
                val o = J.obj(v, id)
                ClipExpected(
                    J.int(o, "chunks"),
                    J.list(o, "tokens").map { (it as Double).toInt() },
                    J.list(o, "flagged_sites").map { val p = it as List<*>; (p[0] as Double).toInt() to (p[1] as String) }.toSet(),
                    J.list(o, "flagged_words").map { (it as Double).toInt() }.toSet(),
                    J.list(o, "word_scores").map { it as Double },
                )
            }
            return Expected(J.str(m, "context"), J.str(m, "calibration_sha256"), J.str(m, "site_preset"), J.str(m, "word_preset"), clips)
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

        /**
         * The pass rule (LAYOUT.md "Self-test set"): greedy tokens identical on the CLEAN clips (a
         * `planted` clip in [planted] is judged on its flags only), flagged sites and flagged words the same
         * sets on every clip, no NaN or inf; per-chunk compute p90 <= [maxP90Fraction] x [chunkMs] and the
         * median below the CPU engine's median measured in the same self-test ([cpuChunkMs]). Reason codes: `tokens`, `site_flags`,
         * `word_flags`, `non_finite`, `missing_clip`, `latency_p90`, `latency_median`, `ok`.
         */
        @JvmStatic
        @JvmOverloads
        fun judge(
            expected: Expected, observed: Map<String, ClipObserved>, cpuChunkMs: List<Double>, chunkMs: Int,
            maxP90Fraction: Double = 0.5,
            /** False for the CPU engine's own check (decisions only). */
            checkLatency: Boolean = true,
            /** Ids of the planted clips (selftest.json kind `planted`): their tokens are not compared. */
            planted: Set<String> = emptySet(),
        ): Verdict {
            val all = observed.values.flatMap { it.chunkMs }
            val p90 = percentile(all, 0.9)
            val med = percentile(all, 0.5)
            val cpuMed = percentile(cpuChunkMs, 0.5)
            fun fail(reason: String, detail: String) = Verdict(false, reason, detail, p90, med, cpuMed)
            for ((id, e) in expected.clips) {
                val o = observed[id] ?: return fail("missing_clip", id)
                if (!o.finite) return fail("non_finite", id)
                if (id !in planted && o.tokens != e.tokens) return fail("tokens", "$id: ${o.tokens.size} tokens vs ${e.tokens.size}")
                if (o.flaggedSites != e.flaggedSites) return fail("site_flags", "$id: ${o.flaggedSites} vs ${e.flaggedSites}")
                if (o.flaggedWords != e.flaggedWords) return fail("word_flags", "$id: ${o.flaggedWords} vs ${e.flaggedWords}")
            }
            if (!checkLatency) return Verdict(true, "ok", "${expected.clips.size} clips, decisions identical", p90, med, cpuMed)
            if (all.isEmpty() || p90 > maxP90Fraction * chunkMs) return fail("latency_p90", "p90 ${fmt(p90)} ms > ${fmt(maxP90Fraction * chunkMs)} ms")
            if (cpuChunkMs.isNotEmpty() && med >= cpuMed) return fail("latency_median", "median ${fmt(med)} ms >= CPU ${fmt(cpuMed)} ms")
            return Verdict(true, "ok", "${expected.clips.size} clips; p90 ${fmt(p90)} ms, median ${fmt(med)} ms (CPU ${fmt(cpuMed)} ms)", p90, med, cpuMed)
        }

        internal fun percentile(v: List<Double>, q: Double): Double {
            if (v.isEmpty()) return Double.NaN
            val s = v.sorted()
            return s[(ceil(q * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
        }

        private fun fmt(x: Double) = String.format(java.util.Locale.ROOT, "%.2f", x)
    }
}

/** Runs the self-test set through an engine and scores it with the default settings. */
object SelfTestRunner {
    /**
     * Every clip of [set] through [engine] (one stream per clip, pushed in 320 ms pieces), scored by
     * [scorer] (the CPU engine's scorer) with the calibration defaults; also each chunk's compute time.
     */
    @JvmStatic
    fun run(engine: TajweedEngine, scorer: TajweedEngine, calibration: Calibration, context: String, set: SelfTestSet, dir: File,
            backend: TajweedBackend = TajweedBackend.INT8): Map<String, SelfTestSet.ClipObserved> {
        val out = LinkedHashMap<String, SelfTestSet.ClipObserved>()
        val config = TajweedConfig(app = TajweedOptions(context = context), backend = backend)
        val settings = config.resolve(calibration)
        for (c in set.clips) {
            val samples = SelfTestSet.samples(File(dir, c.file))
            val times = ArrayList<Double>()
            val chunks = ArrayList<TajweedHeads>()
            engine.createStream().use { s ->
                var at = 0
                val piece = 5120
                while (at < samples.size) {
                    val k = minOf(piece, samples.size - at)
                    val t0 = System.nanoTime()
                    val got = s.pushAudio(samples, at, k)
                    val dt = (System.nanoTime() - t0) / 1e6
                    for (g in got) times.add(dt / got.size)
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
            val heads = TajweedHeads.concat(chunks)
            val r = scorer.score(heads, config, settings, c.surah, c.ayah)
            val finite = SelfTest.allFinite(heads)
            out[c.id] = SelfTestSet.ClipObserved(chunks.size, heads.greedy().toList(),
                r.sites.filter { it.flagged }.map { it.position to it.siteClass.key }.toSet(),
                r.words.filter { it.flagged }.map { it.word }.toSet(), finite, times)
        }
        return out
    }
}
