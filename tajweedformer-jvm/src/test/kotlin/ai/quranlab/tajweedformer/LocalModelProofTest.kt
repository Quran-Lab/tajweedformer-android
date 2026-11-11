package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The proof that needs a model: a real model folder through the PUBLIC API of SDK 3.0 on the desktop JVM, with the
 * exact engine through JNI. No public runner has a model, so it runs where one is at hand and skips everywhere else.
 *
 * ```
 * ./gradlew :tajweedformer-jvm:localProof -PproofModel=<a decoded 3.0 model folder> -PproofClips=<the folder of .pcm clips> \
 *     [-PproofKey=<the folder's public key, hex>] [-Pnative]
 * ```
 *
 * 1. The 35 conformance clips: `TajweedModel.open`, `model.session()`, `push` in pieces, the chunks, `finish()`. For
 *    every clip the chunk count, the take's greedy tokens, the cumulative FNV-1a of `log_probs` and the FNV-1a of
 *    each of the 12 outputs of every chunk are compared with the engine's expected rows
 *    (`conformance/v4_<context>_audio_expected.jsonl`, the fields `bench/v4_conformance_check.py` compares), for the
 *    contexts that have audio rows (c16_l64 and c8_l64). One bit of difference in one value changes a fingerprint.
 * 2. Every clip of `model.sampleClips` through `model.listen(clip)`, in every context: the take's tokens are the
 *    `tokens` of the folder's `selftest/expected_<context>.json`.
 *
 * It prints a summary (clips, chunks, values compared, mismatches, wall time, peak memory) and writes it to
 * `build/local-proof.txt`. Bounded: one take at a time, a 2 GB heap and a time limit on the task, a time limit here.
 */
class LocalModelProofTest : OpenChecked() {
    private fun prop(name: String): String? = (System.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

    private fun fnv(h0: Long, v: FloatArray): Long {
        var h = h0
        for (x in v) {
            val bits = java.lang.Float.floatToRawIntBits(x)
            for (k in 0 until 4) {
                h = h xor ((bits ushr (8 * k)) and 0xff).toLong()
                h *= 1099511628211L
            }
        }
        return h
    }

    private fun hex(h: Long) = String.format("%016x", h)

    private fun pcm(f: File): FloatArray {
        val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** The most memory the process held so far: the JVM heap in use, and the memory the process has committed. */
    private class Peak {
        @Volatile var heap = 0L
        @Volatile var committed = 0L
        @Volatile private var run = true
        private val t = Thread {
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            while (run) {
                val rt = Runtime.getRuntime()
                heap = maxOf(heap, rt.totalMemory() - rt.freeMemory())
                os?.let { committed = maxOf(committed, it.committedVirtualMemorySize) }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }

        fun stop() { run = false; t.interrupt() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theConformanceClipsAndTheSampleClipsThroughThePublicApi() = runBlocking {
        val folder = prop("TJ_PROOF_MODEL")?.let(::File)
        val clips = prop("TJ_PROOF_CLIPS")?.let(::File)
        val conformance = prop("ZF_V4_CONFORMANCE_DIR")?.let(::File)
        Assume.assumeTrue("the local proof needs TJ_PROOF_MODEL (a decoded 3.0 model folder) and TJ_PROOF_CLIPS (the conformance clips)",
            folder?.isDirectory == true && clips?.isDirectory == true && conformance?.isDirectory == true)
        Assume.assumeTrue("the local proof needs the host JNI library (scripts/build_host.sh, or -Pnative)", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        folder!!; clips!!; conformance!!
        // a folder signed with a test key is trusted through this seam only; without it the pinned key decides
        val verifier = prop("TJ_TEST_PUBLIC_KEY")?.let { ManifestVerifier(listOf(TestKit.hex(it))) } ?: ManifestVerifier()
        val peak = Peak()
        val t0 = System.nanoTime()
        val model = TajweedModel.open(folder, ModelHost(verifier = verifier))
        val lines = ArrayList<String>()
        fun say(s: String) { lines.add(s); println(s) }
        say("local proof: SDK ${SdkInfo.VERSION}, model ${model.version} build ${model.build}, contexts ${model.contexts}, engine through JNI on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        var mismatches = 0
        try {
            // 1. the conformance clips through model.session(), push, chunks, finish
            val pieces = intArrayOf(320, 1234, 16_000, 4_321)
            for (context in model.contexts) {
                val rows = File(conformance, "v4_${context}_audio_expected.jsonl").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
                    ?.map { Json.parse(it) as Map<String, Any?> } ?: continue
                assertEquals("$context: the conformance set is 35 clips", 35, rows.size)
                var chunkCount = 0
                var values = 0L
                var outputs = 0L
                val bad = ArrayList<String>()
                for ((n, e) in rows.withIndex()) withTimeout(120_000) {
                    val clip = e["clip"] as String
                    val samples = pcm(File(clips, "$clip.pcm"))
                    val session = model.session(TajweedSessionOptions(context = context, heads = listOf("all"), backend = TajweedSessionOptions.Backend.CPU))
                    val collected = async { session.chunks.toList() }
                    val piece = pieces[n % pieces.size]
                    var at = 0
                    while (at < samples.size) {
                        val k = minOf(piece, samples.size - at)
                        session.push(samples.copyOfRange(at, at + k))
                        at += k
                    }
                    val take = session.finish()
                    // a chunk without a frame only carries the final decoded layer: the engine gave no output for it
                    val got = collected.await().filter { it.endMs > it.startMs }
                    var logp = 1469598103934665603L
                    val out = HashMap<String, ArrayList<String>>()
                    for (c in got) {
                        for (name in c.heads.names) {
                            val v = c.heads[name]!!.values
                            out.getOrPut(name) { ArrayList() }.add(hex(fnv(1469598103934665603L, v)))
                            values += v.size
                            outputs++
                        }
                        logp = fnv(logp, c.heads["log_probs"]!!.values)
                    }
                    chunkCount += got.size
                    val want = e["out_fnv"] as Map<String, List<String>>
                    val why = ArrayList<String>()
                    if (got.size != (e["chunks"] as Double).toInt()) why.add("chunks ${got.size} != ${e["chunks"]}")
                    if (take.greedy().map { it.id } != (e["tokens"] as List<Double>).map { it.toInt() }) why.add("tokens")
                    if (hex(logp) != e["logp_fnv"]) why.add("log_probs over the take")
                    for ((head, fingerprints) in want) {
                        val have = out[head] ?: emptyList<String>()
                        val differ = fingerprints.indices.count { have.getOrNull(it) != fingerprints[it] } + maxOf(0, have.size - fingerprints.size)
                        if (differ > 0) why.add("$head in $differ chunks")
                    }
                    assertEquals("$clip: the heads the Session gives are the 12 the engine's rows have", want.keys, out.keys)
                    // the take's Heads are the chunks' Heads, joined, and the last chunk has the whole take decoded
                    assertEquals(got.sumOf { it.heads["log_probs"]!!.frames }, take["log_probs"]!!.frames)
                    assertTrue(collected.await().last().decoded.isFinal)
                    if (why.isNotEmpty()) bad.add("$clip (${why.joinToString()})")
                }
                mismatches += bad.size
                say("conformance $context: ${rows.size} clips, $chunkCount chunks, $outputs chunk outputs, $values values compared by fingerprint, " +
                    "${bad.size} clips differ" + if (bad.isEmpty()) "" else ": ${bad.joinToString()}")
            }

            // 2. every sample clip through model.listen(clip), in every context
            val sample = model.sampleClips
            assertTrue("the folder has no sample clips", sample.isNotEmpty())
            var takes = 0
            val wrong = ArrayList<String>()
            for (context in model.contexts) {
                val expected = SelfTestSet.readExpected(File(folder, "selftest"), context)
                for (clip in sample) withTimeout(120_000) {
                    val session = model.listen(clip, TajweedSessionOptions(context = context, pace = TajweedSessionOptions.Pace.FAST))
                    val ended = async { session.events.toList().filterIsInstance<TajweedEvent.Ended>().map { it.reason } }
                    val chunks = session.chunks.toList()
                    val take = session.finish()
                    takes++
                    assertEquals("$context ${clip.name}: the clip ran out", listOf(TajweedEvent.Ended.Reason.SOURCE_END), ended.await())
                    assertTrue(chunks.last().decoded.isFinal)
                    val tokens = take.greedy().map { it.id }
                    assertEquals("the last chunk's decoded tokens are the take's", tokens, chunks.last().decoded.tokens.map { it.id })
                    val want = expected.clips[clip.name]?.tokens
                    if (want == null || tokens != want) wrong.add("$context ${clip.name}: ${tokens.size} tokens, expected ${want?.size}")
                }
            }
            mismatches += wrong.size
            say("sample clips through listen: ${sample.size} clips (${sample.joinToString { it.name }}) in ${model.contexts.size} contexts, $takes takes, " +
                "${wrong.size} differ from selftest/expected_<context>.json" + if (wrong.isEmpty()) "" else ": ${wrong.joinToString()}")
        } finally {
            model.close()
            peak.stop()
        }
        say("mismatches: $mismatches; wall time ${(System.nanoTime() - t0) / 1_000_000_000.0} s; peak JVM heap in use ${peak.heap / (1 shl 20)} MB, " +
            "peak memory committed by the process ${peak.committed / (1 shl 20)} MB")
        prop("TJ_PROOF_OUT")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n") + "\n") }
        assertEquals(lines.joinToString("\n"), 0, mismatches)
    }
}
