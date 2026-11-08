package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.android.DeviceSoc
import ai.quranlab.tajweedformer.android.TajweedFormerAndroid
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * The SDK on a real phone, end to end, against a server on the PC (scripts/serve_model.py through
 * `adb reverse tcp:8765 tcp:8765`, serving the staged sdk_v4.0_l3 folder at /m/, the conformance
 * audio at /audio/ and the expected file at /conformance/):
 *
 * 1. the android CPU profile downloads, with the connection cut once in the middle of layers.zfw.gz
 *    and the download resumed by HTTP Range, then verified (signature with the test key, sizes,
 *    SHA-256 of the stored and the inflated bytes);
 * 2. the recognizer self-tests the CPU engine (exact tokens of the built-in clip), finds no NPU build
 *    for this SoC and says why;
 * 3. the 35 parity clips stream through TajweedRecognizer and every chunk output is compared with the
 *    engine's conformance file (FNV-1a of all 12 heads, greedy tokens, chunk count).
 *
 * Instrumentation arguments: `host` (default http://127.0.0.1:8765), `key` (the manifest test key
 * hex), `clips` (how many of the 35, default all).
 */
@RunWith(AndroidJUnit4::class)
class DeviceEndToEndTest {
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host") ?: "http://127.0.0.1:8765"
    private val key = args.getString("key") ?: "ebea3bcf5c3e3a62e7d72cd837ca77bc0825ab484007978658914c4963a7aa6b"
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.noBackupFilesDir, "tajweedformer-test/v4.0")

    private fun log(s: String) {
        Log.i("TJ_DEVICE", s)
        println(s)
    }

    @After
    fun cleanUp() {
        File(ctx.noBackupFilesDir, "tajweedformer-test").deleteRecursively()
        SdkEvents.clear()
    }

    /** A transport that cuts the first download of [path] after [cutAt] bytes, as a dropped network would. */
    private class CuttingTransport(val path: String, val cutAt: Long) : HttpTransport {
        private val inner = UrlConnectionTransport()
        @Volatile var cut = false

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            val r = inner.get(url, headers)
            if (cut || !url.endsWith(path) || r.code != 200) return r
            cut = true
            val body = object : InputStream() {
                var n = 0L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= cutAt) throw IOException("simulated network cut at $n bytes")
                    val k = r.body.read(b, off, minOf(len.toLong(), cutAt - n).toInt())
                    if (k > 0) n += k
                    return k
                }
            }
            return HttpResponse(r.code, r.headers, body) { r.close() }
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun fetch(path: String): ByteArray = URL("$host/$path").openStream().use { it.readBytes() }

    @Test
    fun downloadResumeSelfTestFallbackAndConformance() = runBlocking {
        dir.deleteRecursively()
        log("device: ${android.os.Build.MODEL} sdk ${android.os.Build.VERSION.SDK_INT} abi ${android.os.Build.SUPPORTED_ABIS.joinToString()}; ${DeviceSoc.describe()} -> soc id ${DeviceSoc.id}")
        val downloads = Collections.synchronizedList(ArrayList<DownloadResult>())
        val choices = Collections.synchronizedList(ArrayList<BackendChoice>())
        val selfTests = Collections.synchronizedList(ArrayList<SelfTestResult>())
        SdkEvents.add(object : SdkObserver {
            override fun onDownloadFinished(result: DownloadResult) { downloads.add(result) }
            override fun onBackendSelected(choice: BackendChoice) { choices.add(choice) }
            override fun onSelfTest(result: SelfTestResult) { selfTests.add(result) }
        })
        val transport = CuttingTransport("layers.zfw.gz", 20L shl 20)
        val client = TajweedFormerAndroid.client(ctx, baseUrl = "$host/m/", transport = transport,
            verifier = ManifestVerifier(listOf(hex(key))), dir = dir)

        // 1. download, cut, resume
        val t0 = System.nanoTime()
        try {
            client.prepare()
            fail("the cut download succeeded")
        } catch (e: RecognizerException.DownloadFailed) {
            log("cut as planned: ${e.message}")
        }
        val part = File(dir, "layers.zfw.gz.part")
        log("part after the cut: ${part.length()} bytes")
        assertTrue(part.length() > 0)
        val model = client.prepare()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val layers = downloads.last { it.path == "layers.zfw.gz" && it.ok }
        log("downloads: ${downloads.count { it.ok }} ok, ${downloads.count { !it.ok }} failed; layers.zfw.gz resumed from ${layers.resumedFrom}, " +
            "${layers.networkBytes} bytes after the resume; all in $ms ms; contexts on disk ${model.availableContexts}")
        assertTrue(layers.resumedFrom >= (20L shl 20) - (1L shl 20))
        assertEquals(layers.totalBytes - layers.resumedFrom, layers.networkBytes)
        assertTrue(!part.exists())
        val kept = dir.walk().filter { it.isFile }.sumOf { it.length() }
        log("on disk: $kept bytes in ${dir.walk().count { it.isFile }} files")

        // 2. recognizer: CPU self-test, NPU absent -> CPU with the reason
        // allHeads=false: only the scorer's heads (and conf), as an app by default; the comparison then covers those
        val allHeads = args.getString("allHeads") != "false"
        log("threads: default ${CoreTopology.resolve(0)} from core speeds ${CoreTopology.readSpeeds()}; heads: ${if (allHeads) "all 12" else "default"}")
        val tj = client.recognizer(TajweedRecognizer.Options(allHeads = allHeads))
        log("backend ${tj.backendChoice}; CPU self-test ${tj.cpuSelfTest}")
        assertEquals(Backend.CPU, tj.backend)
        assertTrue(tj.cpuSelfTest!!.passed)
        assertTrue(tj.backendChoice.reason in setOf("soc_unknown", "no_npu_build", "runtime_missing", "npu_not_downloaded"))

        // 3. the parity clips through the SDK, compared with the engine's conformance
        @Suppress("UNCHECKED_CAST")
        val expected = String(fetch("conformance/v4_c16_l64_audio_expected.jsonl"), Charsets.UTF_8).lines().filter { it.isNotBlank() }
            .map { Json.parse(it) as Map<String, Any?> }
        val n = args.getString("clips")?.toInt() ?: expected.size
        val bad = ArrayList<String>()
        var audioSec = 0.0
        var computeSec = 0.0
        val pieces = intArrayOf(320, 1234, 16_000, 4_321)
        for ((i, e) in expected.take(n).withIndex()) {
            val clip = e["clip"] as String
            val fb = ByteBuffer.wrap(fetch("audio/$clip.pcm")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(fb.remaining()).also { fb.get(it) }
            val s = tj.startSession()
            val chunks = async { s.chunks.toList() }
            val c0 = System.nanoTime()
            var at = 0
            while (at < samples.size) {
                val k = minOf(pieces[i % pieces.size], samples.size - at)
                s.append(samples, at, k)
                at += k
            }
            val all = s.finish()
            computeSec += (System.nanoTime() - c0) / 1e9
            audioSec += samples.size / 16000.0
            val got = chunks.await()
            var logp = FNV0
            val out = HashMap<String, ArrayList<String>>()
            for (c in got) {
                for (h in TajweedHead.entries) c.heads[h]?.let { out.getOrPut(h.key) { ArrayList() }.add(hex(fnv(FNV0, it))) }
                logp = fnv(logp, c.heads[TajweedHead.LOG_PROBS]!!)
            }
            @Suppress("UNCHECKED_CAST")
            val why = when {
                got.size != (e["chunks"] as Double).toInt() -> "chunks ${got.size}"
                all.greedy().toList() != (e["tokens"] as List<*>).map { (it as Double).toInt() } -> "tokens"
                hex(logp) != e["logp_fnv"] -> "log_probs"
                else -> (e["out_fnv"] as Map<String, List<String>>).entries.firstOrNull { (k, want) -> (allHeads || k in out) && out[k] != want }?.key
            }
            if (why != null) bad.add("$clip ($why)")
        }
        tj.close()
        log("V4 c16_l64 on device through TajweedRecognizer (${out0(allHeads)}): ${n - bad.size}/$n clips bit-identical to the engine conformance; " +
            "audio ${String.format(java.util.Locale.ROOT, "%.1f", audioSec)} s, compute ${String.format(java.util.Locale.ROOT, "%.1f", computeSec)} s, RTF ${String.format(java.util.Locale.ROOT, "%.3f", computeSec / audioSec)}" +
            if (bad.isEmpty()) "" else "; differ: ${bad.joinToString()}")
        log("self-tests: ${selfTests.joinToString()}; backend choices: ${choices.joinToString()}")
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    private fun out0(all: Boolean) = if (all) "all 12 heads" else "the default heads"

    companion object {
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
}
