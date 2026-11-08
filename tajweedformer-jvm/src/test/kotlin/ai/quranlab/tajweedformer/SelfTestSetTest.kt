package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.engine.TajweedJni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The accelerator self-test set (LAYOUT.md "Self-test set").
 *
 * [generate] is the producer: with `TJ_SELFTEST_OUT=<dir>` it writes `selftest.json`, the four
 * `<clip>.s16` files and `expected_<ctx>.json` for every context, from the exact engine on this host
 * (the JNI library must be an engine with zf_tajweed.h API 3, for the word readout), the decoded
 * model folder `TJ_SELFTEST_MODEL` and the parity float32 audio `ZF_V4_AUDIO_DIR`:
 *
 *     TAJWEEDFORMER_LIBRARY=build/host-p2i/tajweedformer_jni.dll ZF_V4_AUDIO_DIR=<engine>/data/audio \
 *     TJ_SELFTEST_OUT=<folder> \
 *     ./gradlew :tajweedformer-jvm:test --tests '*SelfTestSetTest.generate'
 *
 * [cpuEnginePassesItsOwnSelfTest] reads the set back (`TJ_SELFTEST_DIR`, default the same folder) and
 * runs the exact CPU engine through [SelfTestRunner]: its decisions must equal the expected files on all
 * four clips at c16_l64 (and the other contexts when present).
 */
class SelfTestSetTest {
    private fun env(n: String): String? = TestKit.env(n)
    private val model = File(env("TJ_SELFTEST_MODEL") ?: "")

    companion object {
        const val PLANTED = "Husary_112001_vsub2"
        // filled in from the engine's result below (pos convention of the expected files); checked per context
        var PLANTED_POS = -1
        var PLANTED_EXTRA_POS = -1
    }

    /** The provenance's cross-backend agreement, summarised: per context the int8 / fp32 / fp16-weights
     *  agreement, and the NPU proxies' (Tensor CPU kernels, Exynos 2600 emulator) flags at c16_l64. */
    private fun crossBackend(prov: Map<String, Any?>): Map<String, Any?> {
        val cb = prov["cross_backend"] as? Map<*, *> ?: return emptyMap()
        val per = (cb["per_context"] as? Map<*, *>).orEmpty().mapKeys { it.key as String }.mapValues { (_, v) -> (v as Map<*, *>)["agree"] }
        val npu = (cb["npu_c16_l64"] as? Map<*, *>)?.get("results") as? Map<*, *>
        val npuFlags = npu.orEmpty().mapKeys { it.key as String }.mapValues { (_, v) ->
            val m = v as Map<*, *>
            mapOf("sites" to m["sites"], "words" to m["words"])
        }
        return linkedMapOf("method" to cb["method"], "agree_per_context" to per, "npu_c16_l64_flags" to npuFlags,
            "npu_method" to (cb["npu_c16_l64"] as? Map<*, *>)?.get("method"))
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun generate() {
        val out = env("TJ_SELFTEST_OUT")?.let(::File)
        val audio = env("ZF_V4_AUDIO_DIR")?.let(::File)
        Assume.assumeTrue("TJ_SELFTEST_OUT and ZF_V4_AUDIO_DIR are needed", out != null && audio != null && audio.isDirectory)
        Assume.assumeTrue("an API 3 engine (word readout) is needed", TajweedJni.available && TajweedJni.apiVersion() >= 3)
        out!!.mkdirs()
        val calFile = File(model, "calibration.json")
        val cal = Calibration.read(calFile)
        val clips = ArrayList<Map<String, Any?>>()
        for ((id, sa, must) in SelfTestSet.CLIPS) {
            val fb = ByteBuffer.wrap(File(audio, "$id.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val x = FloatArray(fb.remaining()).also { fb.get(it) }
            val q = SelfTestSet.toS16(x)
            val bb = ByteBuffer.allocate(q.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            q.forEach { bb.putShort(it) }
            File(out, "$id.s16").writeBytes(bb.array())
            clips += linkedMapOf("id" to id, "kind" to SelfTestSet.CLEAN, "surah" to sa.first, "ayah" to sa.second, "file" to "$id.s16",
                "samples" to q.size, "sample_rate" to 16000, "must_decode" to must)
        }
        // the planted mistake: a vowel substitution spliced in from a donor, so
        // a missed flag fails the self-test; its own provenance file says how it was made
        val plant = File(env("TJ_SELFTEST_PLANT") ?: "")
        val pf = File(plant, "$PLANTED.s16")
        if (pf.isFile) {
            pf.copyTo(File(out, "$PLANTED.s16"), overwrite = true)
            @Suppress("UNCHECKED_CAST")
            val prov = Json.parse(File(plant, "$PLANTED.json").readText(Charsets.UTF_8)) as Map<String, Any?>
            fun o(k: String) = prov[k] as Map<*, *>
            val splice = o("splice")
            val qc = o("qc")
            clips += linkedMapOf("id" to PLANTED, "kind" to SelfTestSet.PLANTED, "surah" to 112, "ayah" to 1, "file" to "$PLANTED.s16", "samples" to pf.length() / 2,
                "sample_rate" to 16000, "must_decode" to false,
                "planted" to linkedMapOf(
                    // three position conventions of the same unit: the engine result's site index (the CLI),
                    // the tj3 position counting the '|' tokens, and "pos" as expected_<ctx>.json records it
                    // (ZfTajweedSite.pos: the index into the scored path's tokens, '|' left out)
                    "site_i" to 3, "tj3_pos" to 5, "pos" to PLANTED_POS, "class" to "vowel_sub", "word" to 1, "from" to "u", "to" to "i",
                    "donor" to "Husary_001002",
                    // word 1's score comes from the extra cons_sub: the planted vowel_sub's own fused LLR is below the word tau
                    "word_flag_from" to "cons_sub at 6",
                    "extra_flags" to listOf(mapOf("site_i" to 5, "tj3_pos" to 6, "pos" to PLANTED_EXTRA_POS, "class" to "cons_sub")),
                    "provenance" to linkedMapOf(
                        "file_sha256" to prov["sha256"],
                        "source" to mapOf("clip" to o("source")["clip"], "sha256" to o("source")["sha256"]),
                        "donor" to mapOf("clip" to o("donor")["clip"], "sha256" to o("donor")["sha256"]),
                        "splice" to mapOf("source_samples" to splice["source_samples"], "donor_samples" to splice["donor_samples"],
                            "crossfade_ms" to splice["crossfade_ms"], "gain_ramp" to splice["gain_ramp"]),
                        "seam_qc" to mapOf("seams" to qc["seams"], "seams_ok" to qc["seams_ok"], "seam_rule" to qc["seam_rule"],
                            "seam_score" to qc["seam_score"], "spectral_cos" to qc["spectral_cos"], "judge_ok" to qc["judge_ok"]),
                        "method" to "splice of the donor's vowel with a 10 ms crossfade and gain ramps; " + splice["pitch"],
                        "record" to "delivery/plant/$PLANTED.json",
                        "cross_backend" to crossBackend(prov),
                    ),
                ))
        }
        val rules = linkedMapOf<String, Any?>(
            "tokens" to "identical greedy tj3 tokens on the clean clips (planted clips: flags only)",
            "flagged_sites" to "identical set at the default site preset, every clip",
            "flagged_words" to "identical set at the word block's default preset, every clip", "finite" to "no NaN or inf in any head",
            "latency_p90_max_fraction_of_chunk_ms" to 0.5, "latency_median_below_cpu" to true,
            "on_failure" to "not promoted; retried only for a new model revision or OS build",
        )
        File(out, "selftest.json").writeText(Json.write(linkedMapOf("format" to SelfTestSet.FORMAT, "clips" to clips, "rules" to rules)) + "\n")
        val set = SelfTestSet.read(out)
        for (ctx in cal.contexts.keys) {
            val engine = ExactTajweedEngine(model, ctx, true)
            try {
                val obs = LinkedHashMap<String, Any?>()
                val config = TajweedConfig(app = TajweedOptions(context = ctx), backend = TajweedBackend.INT8)
                val settings = config.resolve(cal)
                for (c in set.clips) {
                    val samples = SelfTestSet.samples(File(out, c.file))
                    val chunks = ArrayList<TajweedHeads>()
                    engine.createStream().use { s ->
                        chunks += s.pushAudio(samples, 0, samples.size)
                        chunks += s.finish()
                    }
                    val heads = TajweedHeads.concat(chunks)
                    val r = engine.score(heads, config, settings, c.surah, c.ayah)
                    assertTrue("$ctx ${c.id}: the engine gave no words (API 3?)", r.wordsFromEngine)
                    obs[c.id] = linkedMapOf(
                        "chunks" to chunks.size,
                        "tokens" to heads.greedy().toList(),
                        "flagged_sites" to r.sites.filter { it.flagged }.map { listOf(it.position, it.siteClass.key) },
                        "flagged_words" to r.words.filter { it.flagged }.map { it.word },
                        "word_scores" to r.words.map { if (it.hasScore) it.score else null },
                    )
                    if (c.id == PLANTED) {
                        val v = r.sites.first { it.flagged && it.word == 1 && it.siteClass == SiteClass.VOWEL_SUB }.position
                        val x = r.sites.firstOrNull { it.flagged && it.siteClass == SiteClass.CONS_SUB }?.position ?: -1
                        if (PLANTED_POS < 0) { PLANTED_POS = v; PLANTED_EXTRA_POS = x }
                        assertEquals("$ctx: planted pos", PLANTED_POS, v)
                        assertEquals("$ctx: extra pos", PLANTED_EXTRA_POS, x)
                        assertTrue("$ctx: the planted vowel_sub is not flagged", r.sites.any { it.flagged && it.word == 1 && it.siteClass == SiteClass.VOWEL_SUB })
                        assertTrue("$ctx: the planted word is not flagged", r.words.any { it.flagged && it.word == 1 })
                    }
                    println("selftest $ctx ${c.id}: ${chunks.size} chunks, ${heads.greedy().size} tokens, " +
                        "${r.flaggedSites.size} sites and ${r.flaggedWords.size} words flagged")
                }
                val word = WordCalibration.of(cal, ctx)
                File(out, "expected_$ctx.json").writeText(Json.write(linkedMapOf(
                    "format" to SelfTestSet.EXPECTED_FORMAT, "context" to ctx, "calibration_sha256" to sha(calFile),
                    "site_preset" to cal.defaultPreset.key, "word_preset" to word.defaultLevel.key,
                    "engine" to "tajweedformer-sdk ${TajweedJni.version()} (zf_tajweed.h API ${TajweedJni.apiVersion()}), host build, exact",
                    "clips" to obs,
                )) + "\n")
            } finally {
                engine.close()
            }
        }
        if (PLANTED_POS >= 0) {
            val text = File(out, "selftest.json").readText()
                .replace("\"pos\":-1,\"class\":\"vowel_sub\"", "\"pos\":$PLANTED_POS,\"class\":\"vowel_sub\"")
                .replace("\"pos\":-1,\"class\":\"cons_sub\"", "\"pos\":$PLANTED_EXTRA_POS,\"class\":\"cons_sub\"")
            File(out, "selftest.json").writeText(text)
            println("planted: vowel_sub at pos $PLANTED_POS, extra cons_sub at pos $PLANTED_EXTRA_POS (ZfTajweedSite.pos)")
        }
    }

    @Test
    fun cpuEnginePassesItsOwnSelfTest() {
        val dir = File(env("TJ_SELFTEST_DIR") ?: File(model, SelfTestSet.DIR).path)
        Assume.assumeTrue("no self-test set at $dir", File(dir, "selftest.json").isFile)
        Assume.assumeTrue("the JNI library is not loadable", TajweedJni.available)
        val set = SelfTestSet.read(dir)
        assertEquals(5, set.clips.size)
        assertEquals(3, set.clips.count { it.mustDecode })
        assertEquals(listOf(PLANTED), set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id })
        val cal = Calibration.read(File(model, "calibration.json"))
        for (ctx in cal.contexts.keys) {
            if (!File(dir, "expected_$ctx.json").isFile) continue
            val expected = SelfTestSet.readExpected(dir, ctx)
            assertEquals(sha(File(model, "calibration.json")), expected.calibrationSha256)
            // the set can catch a missed flag: at least one flagged site and one flagged word (the planted clip)
            assertTrue("$ctx: no flagged site in the set", expected.clips.values.any { it.flaggedSites.isNotEmpty() })
            assertTrue("$ctx: no flagged word in the set", expected.clips.values.any { it.flaggedWords.isNotEmpty() })
            assertEquals(setOf("vowel_sub", "cons_sub"), expected.clips.getValue(PLANTED).flaggedSites.map { it.second }.toSet())
            assertEquals(setOf(1), expected.clips.getValue(PLANTED).flaggedWords)
            val engine = ExactTajweedEngine(model, ctx, true)
            try {
                val obs = SelfTestRunner.run(engine, engine, cal, ctx, set, dir)
                val planted = set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id }.toSet()
                val v = SelfTestSet.judge(expected, obs, emptyList(), TajweedModel.chunkMsOf(ctx), checkLatency = false, planted = planted)
                val pass = obs.count { (id, o) -> SelfTestSet.judge(SelfTestSet.Expected(ctx, "", "", "", mapOf(id to expected.clips.getValue(id))),
                    mapOf(id to o), emptyList(), 320, checkLatency = false).passed }
                println("CPU self-test $ctx: $pass/${set.clips.size} clips identical (${v.detail}); chunk ms median " +
                    String.format(java.util.Locale.ROOT, "%.1f", SelfTestSet.percentile(obs.values.flatMap { it.chunkMs }, 0.5)))
                assertTrue("$ctx: ${v.reason} ${v.detail}", v.passed)
                assertEquals(5, pass)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun theRuleCatchesEveryKindOfMismatch() {
        val e = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", mapOf(
            "a" to SelfTestSet.ClipExpected(3, listOf(1, 2, 3), setOf(4 to "cons_sub"), setOf(1), listOf(1.0, 6.0))))
        fun obs(tokens: List<Int> = listOf(1, 2, 3), sites: Set<Pair<Int, String>> = setOf(4 to "cons_sub"), words: Set<Int> = setOf(1),
                finite: Boolean = true, ms: List<Double> = listOf(10.0, 12.0, 11.0)) =
            mapOf("a" to SelfTestSet.ClipObserved(3, tokens, sites, words, finite, ms))
        val cpu = listOf(40.0, 42.0, 41.0)
        assertEquals("ok", SelfTestSet.judge(e, obs(), cpu, 320).reason)
        assertEquals("tokens", SelfTestSet.judge(e, obs(tokens = listOf(1, 2)), cpu, 320).reason)
        assertEquals("site_flags", SelfTestSet.judge(e, obs(sites = emptySet()), cpu, 320).reason)
        assertEquals("word_flags", SelfTestSet.judge(e, obs(words = setOf(0, 1)), cpu, 320).reason)
        assertEquals("non_finite", SelfTestSet.judge(e, obs(finite = false), cpu, 320).reason)
        assertEquals("latency_p90", SelfTestSet.judge(e, obs(ms = listOf(10.0, 200.0, 170.0)), cpu, 320).reason)
        assertEquals("latency_median", SelfTestSet.judge(e, obs(ms = listOf(50.0, 50.0, 50.0)), cpu, 320).reason)
        assertEquals("missing_clip", SelfTestSet.judge(e, emptyMap(), cpu, 320).reason)
        // a planted clip: other tokens with the same flags pass; a missing site or word flag fails; a clean
        // clip's token mismatch still fails
        val two = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", mapOf(
            "clean" to SelfTestSet.ClipExpected(3, listOf(1, 2, 3), emptySet(), emptySet(), listOf(-3.0)),
            "plant" to SelfTestSet.ClipExpected(3, listOf(22, 33, 24), setOf(5 to "vowel_sub", 6 to "cons_sub"), setOf(1), listOf(6.4))))
        fun both(plantTokens: List<Int> = listOf(22, 33, 24, 7), plantSites: Set<Pair<Int, String>> = setOf(5 to "vowel_sub", 6 to "cons_sub"),
                 plantWords: Set<Int> = setOf(1), cleanTokens: List<Int> = listOf(1, 2, 3)) = mapOf(
            "clean" to SelfTestSet.ClipObserved(3, cleanTokens, emptySet(), emptySet(), true, listOf(10.0, 11.0)),
            "plant" to SelfTestSet.ClipObserved(3, plantTokens, plantSites, plantWords, true, listOf(10.0, 12.0)))
        val p = setOf("plant")
        assertEquals("ok", SelfTestSet.judge(two, both(), cpu, 320, planted = p).reason)
        assertEquals("tokens", SelfTestSet.judge(two, both(), cpu, 320).reason)
        assertEquals("site_flags", SelfTestSet.judge(two, both(plantSites = setOf(6 to "cons_sub")), cpu, 320, planted = p).reason)
        assertEquals("word_flags", SelfTestSet.judge(two, both(plantWords = emptySet()), cpu, 320, planted = p).reason)
        assertEquals("tokens", SelfTestSet.judge(two, both(cleanTokens = listOf(1, 2)), cpu, 320, planted = p).reason)
        // the producer's int16 rounding: clamp(round(x * 32767)), half to even
        assertEquals(listOf<Short>(32767, -32768, 0, -16384), SelfTestSet.toS16(floatArrayOf(1.5f, -1.5f, 0f, -0.5f)).toList())
    }
}
