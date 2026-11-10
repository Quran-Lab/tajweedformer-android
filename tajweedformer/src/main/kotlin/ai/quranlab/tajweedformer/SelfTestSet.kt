package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.Divergence
import ai.quranlab.tajweedformer.accel.Reasons
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * The accelerator self-test set (the golden set), shipped in the model folder under `selftest/` (role
 * `selftest`): five clips as 16 kHz int16 (the three Basmala must-decode clips, Husary_112001, and the planted
 * mistake Husary_112001_vsub2) and per context the exact CPU engine's decisions on them at the default settings.
 * An accelerator is promoted only when it reproduces the CPU engine's decisions on every clip ([judgeGolden]);
 * its speed is measured but never decides.
 */
class SelfTestSet(val clips: List<Clip>, val rules: Map<String, Any?>) {
    data class Clip(
        val id: String, val surah: Int, val ayah: Int, val file: String, val samples: Int, val sampleRate: Int, val mustDecode: Boolean,
        /** `clean` (a real recitation: its tokens must be reproduced) or `planted` (a mistake spliced in:
         *  only its flags count, its tokens may differ between exact int8 and an fp16 / int16 accelerator). */
        val kind: String = CLEAN,
        /** A planted clip's planted site: its position as the expected files record it (`planted.pos`), when given. */
        val plantedPos: Int? = null,
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

        /** The clean clips (id, surah, ayah, must decode), from the engine's parity audio. */
        @JvmField
        val CLIPS = listOf(
            Triple("Husary_001001", 1 to 1, true), Triple("Abdul_001001", 1 to 1, true),
            Triple("Saood_001001", 1 to 1, true), Triple("Husary_112001", 112 to 1, false),
        )

        /** The planted mistake (a vowel substitution spliced into Husary 112:1): it catches a missed flag. */
        const val PLANTED_CLIP = "Husary_112001_vsub2"

        /** Every clip a golden run must decode: the clean ones and the planted one. An expected file without one of
         *  them fails the golden stage (`missing_clip`). */
        @JvmField
        val REQUIRED = CLIPS.map { it.first } + PLANTED_CLIP

        @JvmStatic
        fun read(dir: File): SelfTestSet {
            val m = J.obj(Json.parse(File(dir, "selftest.json").readText()), "selftest.json")
            if (m["format"] != FORMAT) throw RecognizerException.ManifestInvalid("selftest.json format ${m["format"]}")
            val clips = J.list(m, "clips").map { v ->
                val o = J.obj(v, "clips")
                Clip(J.str(o, "id"), J.int(o, "surah"), J.int(o, "ayah"), J.str(o, "file"), J.int(o, "samples"),
                    J.int(o, "sample_rate"), o["must_decode"] == true, (o["kind"] as? String) ?: if (o["planted"] != null) PLANTED else CLEAN,
                    ((o["planted"] as? Map<*, *>)?.get("pos") as? Double)?.toInt()?.takeIf { it >= 0 })
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
         * The decision rule (LAYOUT.md "Self-test set", without its latency conditions: speed never decides): greedy
         * tokens identical on the CLEAN clips (a `planted` clip in [planted] is judged on its flags only), flagged sites
         * and flagged words the same sets on every clip, no NaN or inf, every clip of [expected] and of [required]
         * observed. Reason codes: `tokens`, `site_flags`, `word_flags`, `non_finite`, `missing_clip`, `ok`. [Verdict]'s
         * times are the observed chunk times (telemetry).
         */
        @JvmStatic
        @JvmOverloads
        fun judge(
            expected: Expected, observed: Map<String, ClipObserved>,
            /** Ids of the planted clips (selftest.json kind `planted`): their tokens are not compared. */
            planted: Set<String> = emptySet(),
            /** Clips that must be there besides those of [expected] (the golden stage passes [REQUIRED]). */
            required: List<String> = emptyList(),
        ): Verdict {
            val all = observed.values.flatMap { it.chunkMs }
            val p90 = percentile(all, 0.9)
            val med = percentile(all, 0.5)
            fun fail(reason: String, detail: String) = Verdict(false, reason, detail, p90, med, Double.NaN)
            for (id in required) if (id !in expected.clips) return fail("missing_clip", "$id: not in expected_${expected.context}.json")
            for ((id, e) in expected.clips) {
                val o = observed[id] ?: return fail("missing_clip", "$id: not decoded")
                if (!o.finite) return fail("non_finite", id)
                if (id !in planted && o.tokens != e.tokens) return fail("tokens", "$id: ${o.tokens.size} tokens vs ${e.tokens.size}")
                if (o.flaggedSites != e.flaggedSites) return fail("site_flags", "$id: ${o.flaggedSites} vs ${e.flaggedSites}")
                if (o.flaggedWords != e.flaggedWords) return fail("word_flags", "$id: ${o.flaggedWords} vs ${e.flaggedWords}")
            }
            return Verdict(true, "ok", "${expected.clips.size} clips, decisions identical", p90, med, Double.NaN)
        }

        /** The golden stage's verdict: [reason] a pipeline reason (`ok`, `cpu_mismatch`, `golden_mismatch`, `non_finite`),
         *  the clip that failed and how far it diverged. */
        data class GoldenVerdict(val passed: Boolean, val reason: String, val detail: String, val clip: String? = null,
                                 val divergence: Divergence? = null)

        /**
         * The golden pass rule: the CPU engine's decisions in this run equal the shipped expected file on every clip
         * (else `cpu_mismatch`: the CPU path itself is wrong here, and nothing is promoted), and the accelerator's
         * equal the CPU's (else `golden_mismatch`, or `non_finite`): tokens on the clean clips, site and word flags on
         * every clip, every head finite. The five [REQUIRED] clips must all be there.
         */
        @JvmStatic
        @JvmOverloads
        fun judgeGolden(
            expected: Expected, cpu: Map<String, ClipObserved>, accelerator: Map<String, ClipObserved>, planted: Set<String>,
            /** The build's signed allowance (the manifest's `golden_tolerance`): totals over the set against the CPU run. */
            tolerance: ModelManifest.GoldenTolerance = ModelManifest.GoldenTolerance.EXACT,
            /** Per planted clip, the planted site(s) the accelerator must flag whatever the tolerance. */
            plantedSites: Map<String, Set<Pair<Int, String>>> = emptyMap(),
        ): GoldenVerdict {
            // the CPU engine in this run against the shipped expected file: exact, or nothing is promoted
            val c = judge(expected, cpu, planted, REQUIRED)
            if (!c.passed) {
                val id = c.detail.substringBefore(':')
                return GoldenVerdict(false, Reasons.CPU_MISMATCH, "CPU vs expected: ${c.reason} ${c.detail}",
                    id.takeIf { it in expected.clips || it in REQUIRED }, divergence(expected.clips[id], cpu[id]))
            }
            // the accelerator against the CPU's decisions in this run (which equal the expected file): both directions,
            // an extra flag and a missed flag are each a difference
            var tokensExpected = 0
            var tokens = 0
            var sites = 0
            var words = 0
            var first: String? = null
            for (id in expected.clips.keys) {
                val o = accelerator[id] ?: return GoldenVerdict(false, Reasons.GOLDEN_MISMATCH, "accelerator: $id not decoded", id)
                if (!o.finite) return GoldenVerdict(false, Reasons.NON_FINITE, "accelerator: $id has NaN or inf", id)
                val r = cpu.getValue(id)
                val t = if (id in planted) 0 else SelfTest.editDistance(r.tokens.toIntArray(), o.tokens.toIntArray())
                val s = (r.flaggedSites - o.flaggedSites).size + (o.flaggedSites - r.flaggedSites).size
                val w = (r.flaggedWords - o.flaggedWords).size + (o.flaggedWords - r.flaggedWords).size
                if (id !in planted) tokensExpected += r.tokens.size
                tokens += t
                sites += s
                words += w
                if (first == null && t + s + w > 0) first = id
            }
            val d = Divergence(tokensExpected, tokens, sites, words)
            // a missed planted mistake always fails
            for ((id, need) in plantedSites) {
                val o = accelerator[id] ?: continue
                if (!o.flaggedSites.containsAll(need)) {
                    return GoldenVerdict(false, Reasons.GOLDEN_MISMATCH, "accelerator: missed the planted site ${need - o.flaggedSites} of $id", id, d)
                }
            }
            if (tokens > tolerance.tokenDiffs || sites > tolerance.siteFlagDiffs || words > tolerance.wordFlagDiffs) {
                return GoldenVerdict(false, Reasons.GOLDEN_MISMATCH,
                    "accelerator vs CPU: $tokens token, $sites site flag, $words word flag differences (allowed $tolerance)", first, d)
            }
            return GoldenVerdict(true, "ok", "${expected.clips.size} clips; $tokens token, $sites site flag, $words word flag differences " +
                "(allowed ${tolerance.tokenDiffs} / ${tolerance.siteFlagDiffs} / ${tolerance.wordFlagDiffs})", first, d)
        }

        /** The planted clips' planted sites: the expected flagged site at each clip's `planted.pos` (every expected flagged
         *  site of the clip when its position is not recorded). */
        @JvmStatic
        fun plantedSites(set: SelfTestSet, expected: Expected): Map<String, Set<Pair<Int, String>>> =
            set.clips.filter { it.kind == PLANTED && it.id in expected.clips }.associate { c ->
                val all = expected.clips.getValue(c.id).flaggedSites
                c.id to (c.plantedPos?.let { p -> all.filter { it.first == p }.toSet().ifEmpty { all } } ?: all)
            }

        private fun divergence(e: ClipExpected?, o: ClipObserved?): Divergence? {
            if (e == null || o == null) return null
            val ed = SelfTest.editDistance(e.tokens.toIntArray(), o.tokens.toIntArray())
            fun <T> diff(a: Set<T>, b: Set<T>) = (a - b).size + (b - a).size
            return Divergence(e.tokens.size, ed, diff(e.flaggedSites, o.flaggedSites), diff(e.flaggedWords, o.flaggedWords))
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
    /** Audio pushed per call: one c16 chunk's advance at 16 kHz (the golden run's abort checks fall between pieces). */
    const val PIECE = 5120

    /**
     * Every clip of [set] through [engine] (one stream per clip, pushed in 320 ms pieces), scored by
     * [scorer] (the CPU engine's scorer) with the calibration defaults; also each chunk's compute time.
     */
    @JvmStatic
    fun run(engine: TajweedEngine, scorer: TajweedEngine, calibration: Calibration, context: String, set: SelfTestSet, dir: File,
            backend: TajweedBackend = TajweedBackend.INT8): Map<String, SelfTestSet.ClipObserved> =
        run({ engine.createStream() }, scorer, calibration, context, set, dir, backend) { false }

    /**
     * The same through streams [open] makes (the accelerator path a session uses), stopping with
     * [ai.quranlab.tajweedformer.accel.StageAborted] between pieces when [abort] says so (the stream is closed first).
     */
    @JvmStatic
    fun run(open: () -> TajweedEngineStream, scorer: TajweedEngine, calibration: Calibration, context: String, set: SelfTestSet, dir: File,
            backend: TajweedBackend, abort: () -> Boolean): Map<String, SelfTestSet.ClipObserved> {
        val out = LinkedHashMap<String, SelfTestSet.ClipObserved>()
        val config = TajweedConfig(app = TajweedOptions(context = context), backend = backend)
        val settings = config.resolve(calibration)
        for (c in set.clips) {
            val samples = SelfTestSet.samples(File(dir, c.file))
            val times = ArrayList<Double>()
            val chunks = ArrayList<TajweedHeads>()
            if (abort()) throw ai.quranlab.tajweedformer.accel.StageAborted()
            open().use { s ->
                var at = 0
                while (at < samples.size) {
                    if (abort()) throw ai.quranlab.tajweedformer.accel.StageAborted()
                    val k = minOf(PIECE, samples.size - at)
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
