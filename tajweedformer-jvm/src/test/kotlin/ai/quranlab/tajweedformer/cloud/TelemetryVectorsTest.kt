package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.ModelHost
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.Quran
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.TajweedAnalytics
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import ai.quranlab.tajweedformer.TakeCounts
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The shared vectors `vectors30/telemetry_30.json` and `ayah_counts.json`: the 6.2 counters of takes with known
 * outputs, the two app-reported calls with every refusal, the `reported` list, and the shape of the body. The
 * reports of a take run through real Sessions (a report before the end, after it, on a cancelled or failed take).
 */
class TelemetryVectorsTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val ctx = TelemetryContext(AppIdentity("org.example.recite", "3.2"), DeviceProfile("Pixel8", "tensor_g3", 15, "15", "8_16"))
    /** 2026-11-11 12:00 UTC: every count of a test falls on one day. */
    private val noon = 1_794_398_400_000L

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-telemetry-").toFile().also { dirs.add(it) }

    @Suppress("UNCHECKED_CAST")
    private fun vector(name: String): Map<String, Any?> =
        Json.parse(javaClass.getResourceAsStream("/vectors30/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

    private fun ints(v: Any?): IntArray = (v as List<*>).map { (it as Double).toInt() }.toIntArray()

    private val six = listOf("ayahs", "surah_ayahs", "coverage", "words", "rule_checks", "rule_flags")

    /** The day's app-reported fields and the `reported` list, as the body has them. */
    private fun reported(t: Telemetry): Map<String, Any?> = (t.peek(ctx) ?: emptyMap()).filterKeys { it in six || it == "reported" }

    private fun same(name: String, want: Any?, got: Any?) {
        when (want) {
            is Double -> assertEquals(name, want, (got as? Double) ?: Double.NaN, 1e-9)
            is Map<*, *> -> {
                assertEquals("$name keys", want.keys, (got as? Map<*, *>)?.keys)
                for ((k, x) in want) same("$name.$k", x, (got as Map<*, *>)[k])
            }
            else -> assertEquals(name, want, got)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theCountersOfTakesWithKnownOutputs() {
        val v = vector("telemetry_30.json")
        assertEquals(v["ctc_heads"], Telemetry.TOKEN_HEADS)
        val t = Telemetry(temp()) { noon }
        for (take in v["takes"] as List<Map<String, Any?>>) {
            val ids = (take["heads"] as Map<String, Any?>).map { (k, x) -> HeadId.fromKey(k)!! to ints(x) }.toMap()
            val n40 = ids.getValue(HeadId.LOG_PROBS).size
            val heads = HeadValues(n40, ids.mapValues { (h, top) ->
                FloatArray(top.size * h.width).also { for (f in top.indices) for (k in 0 until h.width) it[f * h.width + k] = if (k == top[f]) 0f else -10f }
            })
            val selected = (take["selected"] as List<String>).map { HeadId.fromKey(it)!! }.toSet()
            val counts = TakeCounts.of(heads, selected)
            val samples = (take["samples"] as Double).toLong()
            val rate = (take["sample_rate"] as Double).toInt()
            val end = take["end"] as String
            // a take counts when it ended with its Heads: finish(), or `ended` with any reason but an error
            val counted = end == "finish" || (end.startsWith("ended:") && end != "ended:error")
            t.sessionStart(TajweedObserver.SessionStart(v["version"] as String, take["context"] as String, take["backend"] as String,
                sampleRate = rate, build = v["build"] as String))
            t.sessionEnd(TajweedObserver.SessionEnd(samples.toDouble() / rate, completed = counted, audioMs = samples * 1000 / rate,
                computeUs = ints(take["chunk_compute_us"]).sumOf { it.toLong() }, speechFrames = counts.speechFrames,
                silenceFrames = counts.silenceFrames, tokens = counts.tokens))
        }
        val cohorts = t.peek(ctx)!!["cohorts"] as List<Map<String, Any?>>
        val want = v["cohorts"] as List<Map<String, Any?>>
        assertEquals(want.size, cohorts.size)
        for ((i, c) in want.withIndex()) {
            for ((k, x) in c) same("cohort $i $k", x, cohorts[i][k])
            for (gone in v["cohort_fields_removed"] as List<String>) assertTrue("cohort $i still has $gone", gone !in cohorts[i])
        }
        // the whole body has no field the server's closed schema does not know (the example is one whole delta)
        val example = v["example_payload"] as Map<String, Any?>
        val body = t.peek(ctx)!!
        assertEquals(emptySet<String>(), body.keys - example.keys)
        assertEquals(emptySet<String>(), cohorts.flatMap { it.keys }.toSet() - (example["cohorts"] as List<Map<String, Any?>>)[0].keys)
        // the example is the vector's own version's; the body names this SDK's (the next line)
        assertTrue(ai.quranlab.tajweedformer.ModelManifest.versionAtLeast(SdkInfo.VERSION, example["sdk_version"] as String))
        assertEquals(SdkInfo.VERSION, body["sdk_version"])
        assertEquals(2.0, body["v"])
        // a day on which the app called neither API: none of the six fields and no `reported`
        assertEquals((v["nothing_reported"] as Map<String, Any?>)["fields"], reported(t))
        assertEquals(v["reported_order"], six)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun reportChecks() {
        val r = vector("telemetry_30.json")["report_checks"] as Map<String, Any?>
        assertEquals(r["rule_pattern"], Telemetry.RULE_KEY.pattern)
        assertEquals((r["checked_max"] as Double).toInt(), TajweedAnalytics.MAX_CHECKED)
        assertEquals((r["rules_per_day"] as Double).toInt(), Telemetry.MAX_RULES)
        fun call(t: Telemetry?, c: List<Any?>): String = try {
            val (rule, checked, flagged) = Triple(c[0] as String, (c[1] as Double).toInt(), (c[2] as Double).toInt())
            TajweedAnalytics.check(rule, checked, flagged)
            t?.checks(rule, checked, flagged)
            "ok"
        } catch (e: TajweedError) {
            e.code
        }
        for (c in r["valid"] as List<List<Any?>>) assertEquals("$c", "ok", call(null, c))
        for (c in r["refused"] as List<List<Any?>>) assertEquals("$c", r["refused_code"], call(null, c))
        // one day's calls in order
        val seq = r["sequence"] as Map<String, Any?>
        val t = Telemetry(temp()) { noon }
        for (c in seq["calls"] as List<Map<String, Any?>>) assertEquals("${c["call"]}", c["result"], call(t, c["call"] as List<Any?>))
        same("the day after the sequence", seq["day_after"], reported(t))
        // 32 rules a day: the 33rd is dropped and the call still returns normally
        val many = r["rules_a_day"] as Map<String, Any?>
        val u = Telemetry(temp()) { noon }
        assertEquals(many["results"], (many["calls"] as List<List<Any?>>).map { call(u, it) })
        same("the day after 33 rules", many["day_after"], reported(u))
        // the rules are kept with the day, across its deltas: after a send point the 33rd is still dropped
        assertEquals(1, u.seal(ctx))
        assertEquals("ok", call(u, listOf("rule_40", 3.0, 1.0)))
        assertEquals("ok", call(u, listOf("rule_02", 4.0, 0.0)))
        assertEquals(mapOf("rule_checks" to mapOf("rule_02" to 4.0), "reported" to listOf("rule_checks")), reported(u))
    }

    /** A chunk of 8 frames per 5120 samples: a token in the take's first chunk, blank after it. */
    private class Engine : TajweedEngine {
        @Volatile var fail = false
        override val chunkMs: Int get() = 320

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0
            var made = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                if (fail) throw IllegalStateException("the engine failed (as the test asked)")
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= 5120) {
                    pending -= 5120
                    out.add(chunk(made++))
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(chunk(made++))
            override fun reset() { pending = 0; made = 0 }
            override fun close() {}
        }

        private fun chunk(i: Int): HeadValues {
            val m = HashMap<HeadId, FloatArray>()
            for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
            val lp = m.getValue(HeadId.LOG_PROBS)
            for (f in 0 until 8) for (k in 0 until 38) lp[f * 38 + k] = if (k == (if (i == 0 && f == 1) 5 else ModelFolder.BLANK)) 0f else -10f
            return HeadValues(8, m)
        }

        override fun close() {}
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun reportRecited() = runBlocking {
        val r = vector("telemetry_30.json")["report_recited"] as Map<String, Any?>
        val counts = vector("ayah_counts.json")
        assertEquals(ints(counts["counts"]).toList(), Quran.AYAHS.toList())
        assertEquals((counts["total"] as Double).toInt(), Quran.AYAHS.sum())
        fun check(c: List<Any?>): String = try {
            Quran.check((c[0] as Double).toInt(), (c[1] as Double).toInt(), (c[2] as Double).toInt())
            "ok"
        } catch (e: TajweedError) {
            e.code
        }
        for (c in r["valid"] as List<List<Any?>>) assertEquals("$c", "ok", check(c))
        for (c in r["refused"] as List<List<Any?>>) assertEquals("$c", r["refused_code"], check(c))

        // one day of takes: what each take's reports leave in the day's counts
        val t = Telemetry(temp()) { noon }
        val dir = temp()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        val engine = Engine()
        val model = TajweedModel.of(ModelFolder.open(dir, TestKit.verifier), ModelHost(verifier = TestKit.verifier, engine = { _, _, _ -> engine },
            recited = { x -> t.recited(x.first, x.second, x.third) }))
        val piece = FloatArray(5120)
        for (take in r["takes"] as List<Map<String, Any?>>) withTimeout(60_000) {
            val name = take["name"] as String
            val steps = take["steps"] as List<List<Any?>>
            val how = steps.first { it[0] == "end" }[1] as String
            engine.fail = false
            val session: TajweedSession = when (how) {
                "ended:silence" -> model.session(TajweedSessionOptions(endOnSilenceMs = 500))
                // half a second at the speed it was recorded: the take is still running when the reports before its end come
                "ended:sourceEnd" -> model.listen(FloatArray(8_000), 16_000)
                else -> model.session()
            }
            val events = async { session.events.toList() }
            val results = ArrayList<String>()
            for (s in steps) {
                if (s[0] == "recited") {
                    results.add(try {
                        session.reportRecited((s[1] as Double).toInt(), (s[2] as Double).toInt(), (s[3] as Double).toInt())
                        "ok"
                    } catch (e: TajweedError) {
                        e.code
                    })
                    continue
                }
                when (how) {
                    "finish" -> { session.push(piece); session.finish() }
                    "cancel" -> session.cancel()
                    "ended:silence" -> try { repeat(8) { session.push(piece) } } catch (e: TajweedError) { assertEquals("session_closed", e.code) }
                    "ended:sourceEnd" -> {}
                    "ended:error" -> {
                        engine.fail = true
                        try { session.push(piece); fail("$name: the engine did not fail") } catch (e: TajweedError) { assertEquals("engine_failed", e.code) }
                    }
                    else -> fail("$name: an end this test does not know: $how")
                }
                // the take has ended, the way the vector says
                val ended = events.await().filterIsInstance<ai.quranlab.tajweedformer.TajweedEvent.Ended>().map { "ended:" + it.reason.name.lowercase().replace("source_end", "sourceEnd") }
                assertEquals("$name: how the take ended", if (how.startsWith("ended:")) listOf(how) else emptyList(), ended)
            }
            assertEquals("$name results", take["results"], results)
            same("$name: the day after", take["day_after"], reported(t))
        }
        model.close()
    }
}
