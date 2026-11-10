package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.npu.NpuChainRunner
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * The NPU's in-session failover with the REAL host loop (the exact engine's front end through JNI, the staged
 * tensor_g5 io.json and masks.json, [NpuTajweedEngine] under the failover engine) over a fake runner that throws at
 * its third chunk: the take continues on the CPU from the failed chunk, is scored as CPU, the key is demoted and the
 * next takes run on the CPU. Needs the JNI library (build/host) and the staged folder.
 */
class BackendFallbackTest {
    private val dirs = ArrayList<File>()
    private val choices = Collections.synchronizedList(ArrayList<BackendChoice>())
    private val tests = Collections.synchronizedList(ArrayList<SelfTestResult>())
    private val observer = object : SdkObserver {
        override fun onBackendSelected(choice: BackendChoice) { choices.add(choice) }
        override fun onSelfTest(result: SelfTestResult) { tests.add(result) }
    }
    private lateinit var npuFiles: NpuFiles
    private lateinit var model: TajweedModel

    private fun dir() = Files.createTempDirectory("tj-fb-").toFile().also { dirs.add(it) }

    @Before
    fun setUp() {
        val transport = File(TestKit.env("TJ_V4_TRANSPORT_DIR") ?: "")
        val io = File(transport, "npu/tensor_g5/c16_l64/tajweedformer_v4_tensor_g5_c16_l64.io.json.gz")
        val masks = File(transport, "npu/tensor_g5/c16_l64/tajweedformer_v4_tensor_g5_c16_l64.masks.json.gz")
        Assume.assumeTrue("the staged tensor_g5 io.json / masks.json are not here", io.isFile && masks.isFile)
        Assume.assumeTrue("the JNI library is not loadable", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val d = dir()
        fun unzip(f: File, name: String) = File(d, name).also { o -> GZIPInputStream(f.inputStream()).use { i -> o.writeBytes(i.readBytes()) } }
        npuFiles = NpuFiles(File(d, "model.tflite").also { it.writeBytes(ByteArray(8)) }, unzip(io, "io.json"), unzip(masks, "masks.json"))
        val md = dir()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(md, files, TestKit.sign(TestKit.manifest(files)))
        model = TajweedModel.open(md, TestKit.verifier)
        SelfTest.clear()
        SdkEvents.add(observer)
    }

    @After
    fun tearDown() {
        SdkEvents.remove(observer)
        dirs.forEach { it.deleteRecursively() }
    }

    companion object {
        /** The self-test clip's c16_l64 tokens, one every other 40 ms frame from frame 2, of 64 frames. */
        val TOKENS = intArrayOf(2, 31, 24, 34, 27, 33, 15, 31, 25, 31, 9)
        const val FRAMES = 64

        fun topAt(frame: Int): Int {
            val k = frame - 2
            return if (k >= 0 && k % 2 == 0 && k / 2 < TOKENS.size) TOKENS[k / 2] else 0
        }
    }

    /** CPU fake: 8 frames per 5120 samples, the flush's frames at finish up to 64 in all. */
    private class CpuFake : TajweedEngine {
        override val chunkMs: Int = 320
        var scoredBackend: TajweedBackend? = null
        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0
            var made = 0
            fun chunk(): TajweedHeads {
                val m = HashMap<TajweedHead, FloatArray>()
                for (h in TajweedHead.entries) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
                val lp = m.getValue(TajweedHead.LOG_PROBS)
                for (t in 0 until 8) for (k in 0 until 38) lp[t * 38 + k] = if (k == topAt(made * 8 + t)) -0.01f else -8f
                made++
                return TajweedHeads(8, m)
            }
            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<TajweedHeads> {
                pending += length
                val out = ArrayList<TajweedHeads>()
                while (pending >= 5120 && made < 7) { pending -= 5120; out.add(chunk()) }
                return out
            }
            override fun finish(): List<TajweedHeads> = (made until FRAMES / 8).map { chunk() }
            override fun reset() { pending = 0; made = 0 }
            override fun close() {}
        }
        override fun score(heads: TajweedHeads, config: TajweedConfig, settings: ResolvedTajweedSettings, surah: Int, ayah: Int): TajweedResult {
            scoredBackend = settings.backend
            return TajweedResult(surah, ayah, settings, emptyList(), emptyList(), emptyList(), emptyList(),
                TajweedSummary(0, 0, 0, 0, 0, 0, 0, 0, 0.0, false))
        }
        override fun expectedTokens(surah: Int, ayah: Int) = TOKENS
        override fun expectedWords(surah: Int, ayah: Int) = IntArray(TOKENS.size) { it / 4 }
        override fun close() {}
    }

    /** NPU fake: zero states; log_probs logits peaked on the schedule; [failAt] throws on that run. */
    private class NpuFake(val c: NpuContract, val failAt: Int = -1, val wrong: Boolean = false) : NpuRunner {
        var runs = 0
        override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
            if (runs == failAt) throw IllegalStateException("the NPU went away")
            val chunk = runs++
            val out = HashMap<String, ShortArray>()
            for ((name, t) in c.outputs) out[name] = ShortArray(t.size)
            val head = c.heads.first { it.head == TajweedHead.LOG_PROBS }.output
            val lp = out.getValue(head)
            for (f in 0 until 8) {
                val tok = if (wrong) 5 else topAt(chunk * 8 + f)
                lp[f * 38 + tok] = 20000
            }
            return out
        }
        override fun close() {}
    }

    @Test
    fun aMidTakeNpuFailureMovesTheTakeToTheCpu() {
        var fake: NpuFake? = null
        val contract = NpuContract.read(npuFiles.io)
        // the runner's first run is the warm-up at take start (silence, off the audio path); it throws on the take's third chunk
        fallbackTake(contract, NpuMasks.read(npuFiles.masks, contract), "tensor_g5") { NpuFake(contract, failAt = 3).also { fake = it } }
        assertEquals(3, fake!!.runs)
    }

    /**
     * The same with the Exynos 2500's 9-part chain (the staged io.json with folded constants and its masks.json):
     * part 4 of 9 throws on the take's third chunk (the chain's fourth run: the warm-up at take start is its first), after
     * parts 0-3 ran; the take moves to the CPU like a one-model failure.
     */
    @Test
    fun aPartFailureInTheExynos2500ChainMovesTheTakeToTheCpu() {
        val g = File(TestKit.env("TJ_NPU_2500_GOLDEN") ?: "")
        val io = File(g, "npu/exynos2500/c16_l64/tajweedformer_v4_exynos2500_c16_l64.io.json")
        val mf = File(g, "npu/exynos2500/c16_l64/tajweedformer_v4_exynos2500_c16_l64.masks.json")
        Assume.assumeTrue("the staged Exynos 2500 io.json / masks.json are not here", io.isFile && mf.isFile)
        val contract = NpuContract.read(io)
        val runs = IntArray(contract.parts.size)
        val parts = contract.parts.indices.map { k ->
            val pc = contract.partContract(k)
            object : NpuRunner {
                override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
                    if (k == 4 && runs[k] == 3) throw IllegalStateException("part 4 of 9 went away")
                    val chunk = runs[k]++
                    val out = pc.outputs.mapValues { (_, t) -> ShortArray(t.size) }
                    out["logits"]?.let { lp -> for (f in 0 until 8) lp[f * 38 + topAt(chunk * 8 + f)] = 20000 }
                    return out
                }
                override fun close() {}
            }
        }
        fallbackTake(contract, NpuMasks.read(mf, contract), "exynos2500") { NpuChainRunner(contract, parts) }
        assertEquals(listOf(4, 4, 4, 4, 3, 3, 3, 3, 3), runs.toList())
    }

    private fun fallbackTake(contract: NpuContract, masks: NpuMasks, soc: String, runner: () -> NpuRunner) = runBlocking {
        val cpu = CpuFake()
        val clips = SelfTestSet.REQUIRED.associateWith { SelfTestSet.ClipExpected(1, listOf(1), emptySet(), emptySet(), emptyList()) }
        val same = SelfTestSet.REQUIRED.associateWith { SelfTestSet.ClipObserved(1, listOf(1), emptySet(), emptySet(), true, listOf(5.0)) }
        val r = runner()
        val prepared = object : ai.quranlab.tajweedformer.accel.PreparedAccelerator {
            override val expected = SelfTestSet.Expected("c16_l64", "x", "balanced", "general", clips)
            override val chunkMs = 320
            override fun warmup() {}
            override fun golden(abort: () -> Boolean) = ai.quranlab.tajweedformer.accel.GoldenRun(same, same)
            override fun stream(heads: Set<TajweedHead>, sampleRate: Int): TajweedEngineStream =
                NpuTajweedEngine(contract, masks, r, sampleRate).createStream(heads)
            override fun close() {}
        }
        val env = object : ai.quranlab.tajweedformer.accel.AcceleratorEnvironment {
            override val osBuild = "b"
            override val deviceModel = "m"
            override val soc = soc
            override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) = work()
        }
        val prep = object : ai.quranlab.tajweedformer.accel.AcceleratorPreparer {
            override fun unavailable(key: ai.quranlab.tajweedformer.accel.AcceleratorKey): String? = null
            override fun download(key: ai.quranlab.tajweedformer.accel.AcceleratorKey) {}
            override fun compile(key: ai.quranlab.tajweedformer.accel.AcceleratorKey) = prepared
        }
        val store = ai.quranlab.tajweedformer.accel.AcceleratorStore(dir()).also { it.sessionStarted = true }
        val acc = ai.quranlab.tajweedformer.accel.AcceleratorController(env, store, prep,
            ai.quranlab.tajweedformer.accel.AcceleratorKey("r", "b", "m", soc, "c16_l64"))
        acc.start()
        assertTrue(acc.state.value is ai.quranlab.tajweedformer.accel.AcceleratorState.Ready)
        val rec = TajweedRecognizer.open(model, TajweedRecognizer.Options(accelerator = acc, soc = soc, engine = { _, _, _ -> cpu }))
        val s = rec.startSession()
        val chunks = async { s.chunks.toList() }
        val clip = SelfTestClip.builtIn().samples
        var at = 0
        while (at < clip.size) {
            val k = minOf(3000, clip.size - at)
            s.append(clip, at, k)
            at += k
        }
        val res = s.finishAndScore(1, 1)
        val got = chunks.await()
        // the first two chunks came from the NPU, the failed third and the rest from the CPU (the take so far fits in the
        // replay window, so the CPU replayed it from its first sample); scored as CPU (int8): most chunks are the CPU's
        assertEquals(listOf(Backend.NPU, Backend.NPU), got.take(2).map { it.backend })
        assertTrue(got.drop(2).all { it.backend == Backend.CPU })
        assertEquals(8, got.size)
        assertEquals((0 until 8).toList(), got.map { it.index })
        assertEquals(TajweedBackend.INT8, cpu.scoredBackend)
        assertEquals(Backend.CPU, res.backend)
        assertEquals("failover_exception", rec.backendChoice.reason)
        assertEquals(ai.quranlab.tajweedformer.accel.Reasons.FAILOVER, (acc.state.value as ai.quranlab.tajweedformer.accel.AcceleratorState.Disabled).reason)
        // the next take runs on the CPU from the start
        val s2 = rec.startSession()
        s2.append(FloatArray(6000))
        s2.cancel()
        assertEquals(Backend.CPU, rec.backend)
        rec.close()
    }
}
