package ai.quranlab.tajweedformer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End to end on the staged layout-v2 folder: [ModelStore] downloads the android CPU profile from a
 * local HTTP server serving `sdk_v4.0_l3` (signed with the test key), verifies and inflates it,
 * and the V4 conformance runs through this SDK's own stream API: the 35 parity clips' 16 kHz audio pushed through
 * Recognizer sessions in pieces, and for every clip the chunk count, the greedy tokens, the
 * cumulative FNV-1a of log_probs and an FNV-1a of each of the 12 outputs of every chunk compared with
 * the engine's conformance file (conformance/v4_<context>_audio_expected.jsonl, the fields
 * bench/v4_conformance_check.py compares). One bit of difference in one value fails the clip.
 *
 * Needs (skipped when absent): the JNI library (scripts/build_host.sh), `TJ_V4_DIR` = a decoded 3.0 folder
 * already in place (with `TJ_TEST_PUBLIC_KEY` when it is signed with a test key) or `TJ_V4_TRANSPORT_DIR` = a
 * stored 3.0 folder to download from (a 2.x folder is refused by SDK 3.0, and the tests then skip),
 * `ZF_V4_AUDIO_DIR` = the clips' float32 .pcm, and
 * `ZF_V4_CONFORMANCE_DIR` = the engine's conformance folder (default: the engine submodule's).
 * c16_l64 and c8_l64 have audio expected files; the c24 contexts' expected files are feature-level,
 * and this SDK's V4 API takes audio, not features. `TJ_V4_DIR` may name an unsigned decoded folder with
 * `TJ_V4_UNSIGNED=1` (no signature is checked then); the calibration sha256 is checked on both routes.
 */
class V4ConformanceTest : OpenChecked() {
    internal fun env(name: String): String? = System.getenv(name) ?: System.getProperty(name)

    companion object {
        /** the 2.0.0 design (supersedes a5fff30b, 7bdccd94 and 37249b0c, the three-context file of the first V4.0 revision). */
        const val SHIPPING_CALIBRATION_SHA256 = "747b7b4cff040efbd46b2ef709f9344c35771b109a4ca1bee1e52b65a5bd991c"
        private const val FNV0 = 1469598103934665603L
        private const val PRIME = 1099511628211L

        fun fnv(h0: Long, v: FloatArray): Long {
            var h = h0
            for (x in v) {
                val bits = java.lang.Float.floatToRawIntBits(x)
                for (k in 0 until 4) {
                    h = h xor ((bits ushr (8 * k)) and 0xff).toLong()
                    h *= PRIME
                }
            }
            return h
        }

        fun hex(h: Long) = String.format("%016x", h)
    }

    internal fun readPcm(f: File): FloatArray {
        val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** The staged folder through ModelStore (a local server) into build/v4.0-l3, or TJ_V4_DIR. */
    internal fun model(): ModelFolder? {
        if (!ai.quranlab.tajweedformer.engine.TajweedJni.available) return null
        // the key the folder is signed with: TJ_TEST_PUBLIC_KEY (the local 3.0 folders), else the staged folder's
        val key = listOf(TestKit.hex(env("TJ_TEST_PUBLIC_KEY") ?: ManifestTest.STAGED_TEST_KEY))
        env("TJ_V4_DIR")?.let {
            val m = ModelFolder.open(File(it), ManifestVerifier(key, requireSignature = env("TJ_V4_UNSIGNED") == null))
            // the 2.0.0 design: the shipping calibration.json
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(File(it, "calibration.json").readBytes())
            assertEquals(SHIPPING_CALIBRATION_SHA256, sha.joinToString("") { b -> "%02x".format(b) })
            return m
        }
        val staged = ManifestTest.stagedDir() ?: return null
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val f = File(staged, ex.requestURI.path.removePrefix("/"))
            if (!f.isFile || !f.canonicalPath.startsWith(staged.canonicalPath)) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, f.length())
                ex.responseBody.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
            ex.close()
        }
        server.start()
        try {
            val root = File("build/v4.0-l3")
            val store = ModelStore(root, "http://127.0.0.1:${server.address.port}/", verifier = ManifestVerifier(key))
            val t0 = System.nanoTime()
            try {
                store.manifest(refresh = true)   // the staged folder may have been re-signed since the last run
            } catch (e: RecognizerException.ModelIncompatible) {
                // the staged folder of the 2.x days is a 2.x build, which SDK 3.0 refuses (ManifestTest checks that):
                // these tests then need TJ_V4_DIR, a decoded 3.0 folder
                println("the staged folder is not a 3.0 build (${e.message}): set TJ_V4_DIR to a decoded 3.0 folder")
                return null
            }
            val m = store.ensureCpu("c16_l64")
            // the 2.0.0 design: the shipping calibration.json
            assertEquals(SHIPPING_CALIBRATION_SHA256, m.manifest.file("calibration.json")!!.localSha256)
            println("ModelStore: CPU profile c16_l64 in place in ${(System.nanoTime() - t0) / 1_000_000} ms " +
                "(${ModelProfile.bytes(ModelProfile.cpu(m.manifest, "c16_l64"))} bytes stored)")
            return m
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun c16l64AudioIsBitExactThroughTheSdk() = audioIsBitExactThroughTheSdk("c16_l64", 320)

    /** The 160 ms context: same recipe and reference (ONNX Runtime armv7) as c16_l64. Runs on the CPU engine
     *  (`no_npu_build` on a device with an NPU build at c16_l64; `no_accelerator` on this host). */
    @Test
    fun c8l64AudioIsBitExactThroughTheSdk() = audioIsBitExactThroughTheSdk("c8_l64", 160)

    @Suppress("UNCHECKED_CAST")
    private fun audioIsBitExactThroughTheSdk(context: String, chunkMs: Int) = runBlocking {
        val audio = env("ZF_V4_AUDIO_DIR")?.let(::File)
        val conf = (env("ZF_V4_CONFORMANCE_DIR") ?: env("ZF_CONFORMANCE_DIR"))?.let(::File)
        val expectedFile = conf?.let { File(it, "v4_${context}_audio_expected.jsonl") }
        Assume.assumeTrue("ZF_V4_AUDIO_DIR and the V4 conformance file are needed",
            audio != null && audio.isDirectory && expectedFile?.isFile == true)
        val model = model()
        Assume.assumeTrue("no JNI library or no staged folder", model != null)
        model!!
        Assume.assumeTrue("the folder has no $context", context in model.availableContexts)
        val tj = Recognizer.open(model, Recognizer.Options(context = context, threads = 4, selfTest = true, allHeads = true))
        assertTrue("CPU self-test: ${tj.cpuSelfTest}", tj.cpuSelfTest!!.passed)
        assertEquals(Backend.CPU, tj.backend)
        assertEquals("no_accelerator", tj.backendChoice.reason)
        assertEquals(chunkMs, tj.chunkMs)
        val expected = expectedFile!!.readLines().filter { it.isNotBlank() }.map { Json.parse(it) as Map<String, Any?> }
        assertEquals(35, expected.size)
        val bad = ArrayList<String>()
        val pieces = intArrayOf(320, 1234, 16_000, 4_321)
        var outputsChecked = 0L
        try {
            for ((n, e) in expected.withIndex()) {
                val clip = e["clip"] as String
                val samples = readPcm(File(audio, "$clip.pcm"))
                val session = tj.startSession()
                val chunks = async { session.chunks.toList() }
                val piece = pieces[n % pieces.size]
                var at = 0
                while (at < samples.size) {
                    val k = minOf(piece, samples.size - at)
                    session.append(samples, at, k)
                    at += k
                }
                val all = session.finish()
                val got = chunks.await()
                var logp = FNV0
                val out = HashMap<String, ArrayList<String>>()
                for (c in got) {
                    for (h in HeadId.entries) {
                        val v = c.heads[h] ?: continue
                        out.getOrPut(h.key) { ArrayList() }.add(hex(fnv(FNV0, v)))
                    }
                    logp = fnv(logp, c.heads[HeadId.LOG_PROBS]!!)
                }
                val tokens = all.greedy().toList()
                val why = when {
                    got.size != (e["chunks"] as Double).toInt() -> "chunks ${got.size} != ${e["chunks"]}"
                    tokens != (e["tokens"] as List<*>).map { (it as Double).toInt() } -> "tokens"
                    hex(logp) != e["logp_fnv"] -> "log_probs"
                    else -> (e["out_fnv"] as Map<String, List<String>>).entries.firstNotNullOfOrNull { (k, want) ->
                        val have = out[k]
                        if (have == want) null else "$k from chunk ${(have ?: emptyList()).zip(want).indexOfFirst { (a, b) -> a != b }}"
                    }
                }
                outputsChecked += got.size * 12L
                if (why != null) bad.add("$clip ($why)")
                // the take's heads are the chunks' heads, joined
                assertEquals(got.sumOf { it.heads.n40 }, all.n40)
            }
            // the decoded layer of a whole take (Saood_001001 is 1:1): tokens and words from the model's own output
            val s = tj.startSession()
            s.append(readPcm(File(audio, "Saood_001001.pcm")))
            val take = s.finish()
            val d = tj.decode(take)
            println("1:1 decoded: ${d.tokens.size} tokens, ${d.words.size} words: ${d.text}")
            assertTrue(d.words.isNotEmpty())
        } finally {
            tj.close()
        }
        println("V4 $context audio through Recognizer: ${expected.size - bad.size}/${expected.size} clips bit-identical " +
            "($outputsChecked chunk outputs)" + if (bad.isEmpty()) "" else "; differ: ${bad.joinToString()}")
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    /** The self-test clip through the real engine in every context: the CPU self-test's expectations
     *  (SelfTestTokens.kt). c16_l64 must equal the engine's conformance tokens. */
    @Test
    fun selfTestTokensOfEveryContext() {
        val m0 = model()
        Assume.assumeTrue("no JNI library or no staged folder", m0 != null)
        val clip = SelfTestClip.builtIn()
        for (ctx in m0!!.contexts) {
            val model = if (ctx in m0.availableContexts || env("TJ_V4_DIR") != null) m0 else {
                // the other contexts' three files, through the same store
                val staged = ManifestTest.stagedDir()!!
                val root = File("build/v4.0-l3")
                val m = ModelManifest.read(staged)
                for (e in ModelProfile.contextOnly(m, ctx)) {
                    val src = File(staged, e.path)
                    val dst = File(root, e.localPath).also { it.parentFile.mkdirs() }
                    java.util.zip.GZIPInputStream(src.inputStream()).use { i -> dst.outputStream().use { i.copyTo(it) } }
                }
                ModelFolder.open(root, ManifestVerifier(listOf(TestKit.hex(ManifestTest.STAGED_TEST_KEY))), require = listOf(ctx))
            }
            Recognizer.open(model, Recognizer.Options(context = ctx, threads = 4)).use { tj ->
                val heads = kotlinx.coroutines.runBlocking {
                    val s = tj.startSession()
                    s.append(clip.samples)
                    s.finish()
                }
                val tokens = heads.greedy()
                println("self-test tokens $ctx: ${tokens.joinToString(", ")}")
                val want = clip.expectedTokens[ctx]
                if (want != null) assertTrue("$ctx: ${tokens.toList()} != ${want.toList()}", tokens.contentEquals(want))
            }
        }
    }
}
