package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.NpuPreparer
import ai.quranlab.tajweedformer.accel.Reasons
import ai.quranlab.tajweedformer.npu.NpuChainRunner
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import ai.quranlab.tajweedformer.npu.NpuStreamer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * Chained NPU builds (io.json `parts`, the Exynos 2500's 9 LiteRT models) on the JVM: io.json parsing and its
 * refusals, the name-keyed pool of [NpuChainRunner] with fake parts, profiles and downloads of `npu-part` files,
 * part failures, the c16_l64-only policy (no build at c24_l72 = CPU), and, where the 11-08 board data is on this
 * machine, the whole chain on one real clip against a replay of the board runner's loop.
 *
 * The real parts cannot run here: each is one DISPATCH_OP holding a Samsung NPU program (LiteCore 0.7, NCP 28),
 * which only the vendor dispatch library on an Exynos 2500 executes; there is no host LiteRT in this repository
 * either. So the golden test checks the WIRING: every byte each part is fed, on every chunk.
 */
class NpuChainTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun dir() = Files.createTempDirectory("tj-chain-").toFile().also { dirs.add(it) }

    @After
    fun tearDown() = dirs.forEach { it.deleteRecursively() }

    // ---------------------------------------------------------------------------------------- a tiny chain

    private fun t(name: String, shape: List<Int>, scale: Double = 0.5) =
        mapOf("name" to name, "shape" to shape, "dtype" to "int16", "scale" to scale, "zero_point" to 0)

    private fun p(name: String, shape: List<Int>) = mapOf("name" to name, "shape" to shape)

    private fun codes(vararg v: Int): String {
        val b = ByteBuffer.allocate(2 * v.size).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putShort(x.toShort())
        return Base64.getEncoder().encodeToString(b.array())
    }

    /**
     * Graph: x [1,1,5,2], state s0 [1,3] (ratio 0.5), masks zf_keep0 / zf_fill0 [2], constant zf_ck0 [1,1,4];
     * outputs new_s0 and logits [1,1,1,38] (the log_probs head at one 40 ms frame).
     * P0: x, zf_ck0 -> mid [2,5];  P1: mid, s0, zf_keep0, zf_fill0 -> new_s0, h1 [38];  P2: h1 -> logits.
     */
    private fun tinyIo(): MutableMap<String, Any?> = linkedMapOf(
        "soc" to "exynos2500", "context" to "c16_l64",
        "geometry" to mapOf("window_frames" to 5, "shift_frames" to 3, "chunk_ms" to 40, "left_frames" to 2, "mask_chunk_sets" to 2),
        "inputs" to listOf(t("x", listOf(1, 1, 5, 2), 0.01), t("s0", listOf(1, 3), 1.0), t("zf_keep0", listOf(2), 1.0),
            t("zf_fill0", listOf(2), 1.0), t("zf_ck0", listOf(1, 1, 4), 1.0)),
        "outputs" to listOf(t("new_s0", listOf(1, 3), 2.0), t("logits", listOf(1, 1, 1, 38), 0.1)),
        "states" to listOf(mapOf("input" to "s0", "output" to "new_s0", "requant_ratio" to 0.5)),
        "heads" to mapOf("logits" to mapOf("export_output" to "log_probs", "host_op" to "log_softmax")),
        "other_inputs" to listOf("zf_keep0", "zf_fill0", "zf_ck0"),
        "constant_codes" to mapOf("encoding" to "int16le-base64", "inputs" to mapOf("zf_ck0" to codes(100, 200, 300, 400))),
        "parts" to listOf(
            linkedMapOf("index" to 0, "file" to "m.P0.tflite", "inputs" to listOf(p("x", listOf(10)), p("zf_ck0", listOf(1, 1, 1, 4))),
                "outputs" to listOf(p("mid", listOf(2, 5)))),
            linkedMapOf("index" to 1, "file" to "m.P1.tflite",
                "inputs" to listOf(p("mid", listOf(1, 1, 1, 10)), p("s0", listOf(3)), p("zf_keep0", listOf(1, 2)), p("zf_fill0", listOf(2))),
                "outputs" to listOf(p("new_s0", listOf(3)), p("h1", listOf(38)))),
            linkedMapOf("index" to 2, "file" to "m.P2.tflite", "inputs" to listOf(p("h1", listOf(1, 38))),
                "outputs" to listOf(p("logits", listOf(1, 1, 1, 38)))),
        ),
    )

    private fun parse(io: Map<String, Any?>) = NpuContract.parse(Json.write(io))

    /** Records every feed, computes simple functions of its inputs, can throw on a chosen call. */
    private class FakePart(val k: Int, val c: NpuContract, val log: MutableList<String>, val failAt: Int = -1) : NpuRunner {
        val feeds = ArrayList<Map<String, ShortArray>>()
        var closed = false
        override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
            if (feeds.size == failAt) throw IllegalStateException("part $k fell over")
            assertEquals("part $k is fed its own inputs in its own order", c.inputs.keys.toList(), inputs.keys.toList())
            feeds.add(inputs.mapValues { it.value.copyOf() })
            log.add("P$k")
            fun i(n: String) = inputs.getValue(n)
            return when (k) {
                0 -> mapOf("mid" to ShortArray(10) { (i("x")[it] + i("zf_ck0")[it % 4]).toShort() })
                1 -> mapOf("new_s0" to ShortArray(3) { (i("s0")[it] + i("mid")[0] + 10 * i("zf_keep0")[0]).toShort() },
                    "h1" to ShortArray(38) { if (it == 7) 30000 else 0 })
                else -> mapOf("logits" to i("h1").copyOf())
            }
        }
        override fun close() { closed = true }
    }

    @Test
    fun theChainRunsPartsInOrderOverOneNamePool() {
        val c = parse(tinyIo())
        assertEquals(3, c.parts.size)
        assertEquals(listOf("x", "zf_ck0"), c.parts[0].inputs.keys.toList())
        assertTrue(c.parts[0].outputs.getValue("mid").scale.isNaN())   // an intermediate: no graph scale
        assertEquals(1.0, c.parts[1].inputs.getValue("s0").scale, 0.0) // a graph input keeps its scale
        assertArrayEquals(shortArrayOf(100, 200, 300, 400), c.constantCodes.getValue("zf_ck0"))
        assertTrue("zf_ck0 is a code constant, not a zf_cv_ one", c.constants.isEmpty())
        // each part's own contract: its tensors only
        val pc = c.partContract(1)
        assertEquals(listOf("mid", "s0", "zf_keep0", "zf_fill0"), pc.inputs.keys.toList())
        assertEquals(10, pc.inputs.getValue("mid").size)
        assertTrue(pc.states.isEmpty() && pc.heads.isEmpty() && pc.parts.isEmpty())

        val log = ArrayList<String>()
        val parts = (0 until 3).map { FakePart(it, c.partContract(it), log) }
        val masks = NpuMasks(listOf(
            mapOf("zf_keep0" to shortArrayOf(0, 1), "zf_fill0" to shortArrayOf(-30, 0)),
            mapOf("zf_keep0" to shortArrayOf(1, 1), "zf_fill0" to shortArrayOf(0, 0)),
        ))
        val chain = NpuChainRunner.open(c) { k, _ -> parts[k] }
        val s = NpuStreamer(c, masks, chain)
        // 11 frames of 2 bins, value frame / 100: windows of 5 every 3 frames -> 3 chunks
        val heads = s.push(FloatArray(22) { (it / 2) / 100f })
        assertEquals(3, heads.size)
        assertEquals(listOf("P0", "P1", "P2", "P0", "P1", "P2", "P0", "P1", "P2"), log)
        for (k in 0 until 3) {
            val f0 = parts[0].feeds[k]
            for (i in 0 until 5) assertEquals((3 * k + i).toShort(), f0.getValue("x")[2 * i])
            assertArrayEquals(shortArrayOf(100, 200, 300, 400), f0.getValue("zf_ck0"))
            // P1 reads P0's output from the pool, the same values whatever its shape
            val mid = ShortArray(10) { (f0.getValue("x")[it] + 100 * (it % 4 + 1)).toShort() }
            assertArrayEquals(mid, parts[1].feeds[k].getValue("mid"))
            assertArrayEquals(if (k == 0) shortArrayOf(0, 1) else shortArrayOf(1, 1), parts[1].feeds[k].getValue("zf_keep0"))
            assertEquals(30000.toShort(), parts[2].feeds[k].getValue("h1")[7])
        }
        // the state: new_s0 of chunk k (a P1 output), requantised by 0.5, is chunk k + 1's s0
        assertArrayEquals(shortArrayOf(0, 0, 0), parts[1].feeds[0].getValue("s0"))
        val n0 = (0 + parts[1].feeds[0].getValue("mid")[0] + 0).toDouble()
        assertEquals(Math.rint(n0 * 0.5).toInt().toShort(), parts[1].feeds[1].getValue("s0")[0])
        // the head: logits from P2, finished by log-softmax, token 7 on top
        val lp = heads[0][HeadId.LOG_PROBS]!!
        assertEquals(7, lp.indices.maxByOrNull { lp[it] })
        chain.close()
        assertTrue(parts.all { it.closed })
    }

    @Test
    fun aPartThatThrowsStopsTheChunkAndOpeningClosesWhatItOpened() {
        val c = parse(tinyIo())
        val log = ArrayList<String>()
        val parts = (0 until 3).map { FakePart(it, c.partContract(it), log, failAt = if (it == 1) 1 else -1) }
        val chain = NpuChainRunner.open(c) { k, _ -> parts[k] }
        val masks = NpuMasks(listOf(mapOf("zf_keep0" to shortArrayOf(1, 1), "zf_fill0" to shortArrayOf(0, 0))))
        val s = NpuStreamer(c, masks, chain)
        assertEquals(1, s.push(FloatArray(10)).size)
        try {
            s.push(FloatArray(6))   // chunk 1: part 1 throws, part 2 is not run
            fail("the part's failure did not reach the engine")
        } catch (e: IllegalStateException) {
            assertEquals("part 1 fell over", e.message)
        }
        assertEquals(listOf("P0", "P1", "P2", "P0"), log)
        // a part that cannot be opened: the ones opened before it are closed, the error is thrown (load failure)
        val opened = ArrayList<FakePart>()
        try {
            NpuChainRunner.open(c) { k, pc ->
                if (k == 2) throw RecognizerException.BackendUnavailable("no dispatch library")
                FakePart(k, pc, log).also { opened.add(it) }
            }
            fail()
        } catch (e: RecognizerException.BackendUnavailable) {
        }
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.closed })
        // a part runner that returns too little is an error too (the board runner would have fed zeros)
        val short = NpuChainRunner(c, listOf(
            parts[0],
            object : NpuRunner {
                override fun run(inputs: Map<String, ShortArray>) = mapOf("new_s0" to ShortArray(3))
                override fun close() {}
            },
            parts[2]))
        try {
            NpuStreamer(c, masks, short).push(FloatArray(10))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("gave no h1"))
        }
    }

    @Test
    fun parsingRefusesAChainTheBoardRunnerWouldNotHaveRunAsVerified() {
        fun refused(why: String, edit: (MutableMap<String, Any?>) -> Unit) {
            val io = tinyIo()
            edit(io)
            try {
                parse(io)
                fail("accepted: $why")
            } catch (e: RecognizerException.BackendUnavailable) {
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun part(io: MutableMap<String, Any?>, k: Int) = (io["parts"] as List<MutableMap<String, Any?>>)[k]
        refused("an input nothing makes") { part(it, 1)["inputs"] = listOf(p("ghost", listOf(10))) }
        refused("an input made by a LATER part") { part(it, 0)["inputs"] = listOf(p("x", listOf(10)), p("h1", listOf(38))) }
        refused("a size that differs from its source") { part(it, 1)["inputs"] = listOf(p("mid", listOf(9))) }
        refused("an output re-using a graph input's name") { part(it, 2)["outputs"] = listOf(p("logits", listOf(38)), p("s0", listOf(3))) }
        refused("a graph output no part makes") { part(it, 2)["outputs"] = listOf(p("other", listOf(38))) }
        refused("indices out of order") { part(it, 1)["index"] = 2 }
        refused("a file name with a path") { part(it, 0)["file"] = "../m.P0.tflite" }
        refused("the same file twice") { part(it, 1)["file"] = "m.P0.tflite" }
        refused("an int8 part tensor") { part(it, 0)["outputs"] = listOf(p("mid", listOf(2, 5)) + ("dtype" to "int8")) }
        refused("a constant of the wrong size") {
            it["constant_codes"] = mapOf("encoding" to "int16le-base64", "inputs" to mapOf("zf_ck0" to codes(1, 2, 3)))
        }
        refused("a constant that is not an input") {
            it["constant_codes"] = mapOf("encoding" to "int16le-base64", "inputs" to mapOf("zf_ck0" to codes(1, 2, 3, 4), "zf_ck9" to codes(1)))
        }
        refused("another encoding") { it["constant_codes"] = mapOf("encoding" to "int16be-hex", "inputs" to emptyMap<String, Any>()) }
        refused("a constant input without a value") { it.remove("constant_codes") }
        // a one-model io.json (no parts, no constant_codes) still parses as before
        val one = tinyIo().also { it.remove("parts"); it.remove("constant_codes") }
        one["inputs"] = (one["inputs"] as List<*>).filter { (it as Map<*, *>)["name"] != "zf_ck0" }
        one["other_inputs"] = listOf("zf_keep0", "zf_fill0")
        assertTrue(parse(one).parts.isEmpty())
    }

    // ----------------------------------------------------------------------- manifest, download, policy

    @Test
    fun aChainedBuildIsItsOwnRoleAndOlderSdksSeeNoBuild() {
        val files = TestKit.v4Files(listOf("tensor_g5"), chainSocs = listOf("exynos2500"))
        val m = TestKit.sign(TestKit.manifest(files))
        val parsed = ModelManifest.parse(m.toJson())
        TestKit.verifier.verify(parsed)
        val npu = ModelProfile.npu(parsed, "exynos2500", "c16_l64")
        assertEquals(listOf("npu-io", "npu-masks", "npu-part", "npu-part", "npu-part"), npu.map { it.role }.sorted())
        assertEquals(setOf("tensor_g5", "exynos2500"), ModelProfile.npuSocs(parsed, "c16_l64"))
        // the one-model build next to it is unchanged
        assertEquals(setOf("npu-model", "npu-io", "npu-masks"), ModelProfile.npu(parsed, "tensor_g5", "c16_l64").map { it.role }.toSet())
        // c16_l64 only: no NPU files at another context, so no build there
        assertTrue(ModelProfile.npu(parsed, "exynos2500", "c24_l72").isEmpty())
        // the profile rule of the SDKs before npu-part (exactly one of npu-model, npu-io, npu-masks): no build
        val old = parsed.files.filter { it.role in ModelProfile.NPU_ROLES && it.soc == "exynos2500" && it.context == "c16_l64" }
        assertTrue(!ModelProfile.NPU_ROLES.all { r -> old.count { it.role == r } == 1 })
        // a SoC with both a model and parts, or parts without masks, has no build
        val both = files + TestKit.F("npu/exynos2500/c16_l64/x.tflite", ByteArray(10), "npu-model", "c16_l64", "optional", listOf("android"), "exynos2500")
        assertTrue(ModelProfile.npu(TestKit.manifest(both), "exynos2500", "c16_l64").isEmpty())
        val noMasks = files.filter { !(it.soc == "exynos2500" && it.role == "npu-masks") }
        assertTrue(ModelProfile.npu(TestKit.manifest(noMasks), "exynos2500", "c16_l64").isEmpty())
        // the SoC id of an Exynos 2500 (E9955)
        assertEquals("exynos2500", SocIds.from(null, null, roSocModel = "s5e9955"))
        assertEquals("exynos2500", SocIds.from("Samsung", "Exynos 2500"))
        assertEquals("exynos2600", SocIds.from("Samsung", "s5e9965"))
        assertNull("Exynos 2400 (E9945) has no shipped build", SocIds.from("Samsung", "s5e9945"))
    }

    @Test
    fun npuPreparerMatchesPartsByFileNameOnly() {
        val c = parse(tinyIo())
        val d = dir()
        val files = listOf("m.P2.tflite", "m.P0.tflite", "m.P1.tflite").map { File(d, it).also { f -> f.writeBytes(ByteArray(4)) } }
        val opened = ArrayList<String>()
        val r = NpuPreparer.chain(NpuFiles(files[0], File(d, "io.json"), File(d, "masks.json"), files), c) { f, pc ->
            opened.add(f.name)
            object : NpuRunner {
                override fun run(inputs: Map<String, ShortArray>) = pc.outputs.mapValues { (_, t) -> ShortArray(t.size) }
                override fun close() {}
            }
        }
        assertEquals("io.json's order, not the files'", listOf("m.P0.tflite", "m.P1.tflite", "m.P2.tflite"), opened)
        assertTrue(r is NpuChainRunner)
        // the app's LiteRT factory opens the whole chain itself (one shared environment): never part by part
        val chained = ArrayList<String>()
        val opener = object : (File, NpuContract) -> NpuRunner, ai.quranlab.tajweedformer.npu.NpuChainOpener {
            override fun invoke(f: File, pc: NpuContract): NpuRunner = error("a chained build must not open part by part")
            override fun openChain(contract: NpuContract, parts: List<File>): NpuRunner {
                assertSame(c, contract)
                chained.addAll(parts.map { it.name })
                return NpuChainRunner(contract, contract.parts.indices.map { k ->
                    object : NpuRunner {
                        override fun run(inputs: Map<String, ShortArray>) = contract.partContract(k).outputs.mapValues { (_, t) -> ShortArray(t.size) }
                        override fun close() {}
                    }
                })
            }
        }
        NpuPreparer.chain(NpuFiles(files[0], File(d, "io.json"), File(d, "masks.json"), files), c, opener)
        assertEquals(listOf("m.P0.tflite", "m.P1.tflite", "m.P2.tflite"), chained)
        for (bad in listOf(files.take(2), files + File(d, "m.P3.tflite"))) {
            try {
                NpuPreparer.chain(NpuFiles(bad[0], File(d, "io.json"), File(d, "masks.json"), bad), c) { _, _ -> error("not reached") }
                fail()
            } catch (e: RecognizerException.BackendUnavailable) {
            }
        }
    }

    @Test
    fun noNpuFilesAtTheContextMeansNoBuildAndTheCpu() {
        // the Exynos 2500 ships c16_l64 only; at c24_l72 the preparer says no_npu_build before any download
        val files = TestKit.v4Files(chainSocs = listOf("exynos2500")) +
            TestKit.F("selftest/expected_c16_l64.json", "{}".toByteArray(), "selftest", "c16_l64", "optional") +
            TestKit.F("selftest/expected_c24_l72.json", "{}".toByteArray(), "selftest", "c24_l72", "optional")
        val m = TestKit.sign(TestKit.manifest(files))
        val root = dir()
        TestKit.writeDecoded(root, files, m)
        val store = ModelStore(root, "http://127.0.0.1:9/never/", verifier = TestKit.verifier)
        val model = ModelFolder.open(root, TestKit.verifier)
        val factory: (File, NpuContract) -> NpuRunner = { _, _ -> error("no runner may load without a build") }
        val prep = NpuPreparer(store, model, NetworkPolicy.ALWAYS_UNMETERED, factory)
        fun key(ctx: String) = AcceleratorKey("r", "b", "Galaxy Z Flip7", "exynos2500", ctx)
        assertEquals(Reasons.NO_BUILD, prep.unavailable(key("c24_l72")))
        assertNull("c16_l64 has a build", prep.unavailable(key("c16_l64")))
        assertTrue(store.npuFiles("exynos2500", "c24_l72").isEmpty())
        assertEquals("no_npu_build", store.ensureNpu("exynos2500", "c24_l72", NetworkPolicy.ALWAYS_UNMETERED, true).reason)
        // the controller stays on the CPU with that reason and never loads anything
        val env = object : AcceleratorEnvironment {
            override val osBuild = "b"
            override val deviceModel = "Galaxy Z Flip7"
            override val soc = "exynos2500"
            override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) = work()
        }
        val acc = AcceleratorController(env, AcceleratorStore(dir()), prep, key("c24_l72"))
        acc.start()
        assertEquals(AcceleratorState.Cpu(Reasons.NO_BUILD), acc.state.value)
    }

    // ------------------------------------------------------------------- the real build, one golden clip

    private val npuWork = File(TestKit.env("TJ_NPU_WORK") ?: "")
    private val golden = File(TestKit.env("TJ_NPU_2500_GOLDEN") ?: "")
    private val base = "npu/exynos2500/c16_l64/tajweedformer_v4_exynos2500_c16_l64"

    private fun sha(v: ShortArray): String {
        val b = ByteBuffer.allocate(2 * v.size).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putShort(x)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun shorts(f: File): ShortArray {
        val sb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(sb.remaining()).also { sb.get(it) }
    }

    /**
     * The staged Exynos 2500 build (io.json with the constants folded in by the publisher's npu_chain.py, masks.json
     * by npu_masks.py) on one DEV clip, panel_consonants_124 (513 fbank frames, 15 chunks), fed by the SDK's own
     * host loop ([NpuStreamer]: x quantised from the float features, mask sets, constants, states) through
     * [NpuChainRunner], against a replay of the board runner's loop (run_model RunStreamMulti re-done in Python by
     * npu_work/b2500/sdk_golden/golden25.py over the board's pack with the Exynos 2500 emulator and the board's
     * own tables; its logits equal the board's on all 15 chunks).
     *
     * Each fake part checks the SHA-256 of every input it is fed against what the board loop fed that part on that
     * chunk (all 9 parts x 15 chunks x 298 part inputs), then returns that part's recorded outputs. At the end the
     * chain's graph outputs (logits and the 11 other heads, int16 codes) are compared with the BOARD's stream
     * output files byte for byte.
     */
    @Test
    fun theExynos2500ChainFeedsEveryPartWhatTheBoardRunnerFed() {
        val io = File(golden, "$base.io.json")
        val maskFile = File(golden, "$base.masks.json")
        val clip = File(golden, "panel_consonants_124")
        val feats = File(npuWork, "feats/eval/panel_consonants_124.f32")
        val board = File(npuWork, "b2500/board/o25/out/stream")
        Assume.assumeTrue("the 11-08 Exynos 2500 golden data is not on this machine",
            io.isFile && maskFile.isFile && File(clip, "c000/P0.in.sha").isFile && feats.isFile && File(board, "panel_consonants_124.raw").isFile)
        val c = NpuContract.read(io)
        assertEquals(9, c.parts.size)
        assertEquals(126, c.constantCodes.size)
        assertEquals(97, c.states.size)
        assertEquals(12, c.heads.size)
        assertEquals(298, c.parts.sumOf { it.inputs.size })
        val masks = NpuMasks.read(maskFile, c)

        val chunkOf = IntArray(9)
        var checked = 0
        val parts = (0 until 9).map { k ->
            val pc = c.partContract(k)
            object : NpuRunner {
                override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
                    val ch = chunkOf[k]++
                    val d = File(clip, "c%03d".format(ch))
                    val want = File(d, "P$k.in.sha").readLines().filter { it.isNotBlank() }.map { it.substringBeforeLast(' ') to it.substringAfterLast(' ') }
                    assertEquals("chunk $ch part $k: input names and order", want.map { it.first }, inputs.keys.toList())
                    val bad = want.filter { (n, h) -> sha(inputs.getValue(n)) != h }.map { it.first }
                    assertTrue("chunk $ch part $k: ${bad.size} inputs differ from the board loop's: ${bad.take(5)}", bad.isEmpty())
                    checked += want.size
                    return pc.outputs.keys.withIndex().associate { (j, n) -> n to shorts(File(d, "P$k/o%03d.raw".format(j))) }
                }
                override fun close() {}
            }
        }
        val got = HashMap<String, ArrayList<ShortArray>>()
        val chain = NpuChainRunner.open(c) { k, _ -> parts[k] }
        val recorder = object : NpuRunner {
            override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> =
                chain.run(inputs).also { o -> for ((n, v) in o) if (!n.startsWith("new_")) got.getOrPut(n) { ArrayList() }.add(v) }
            override fun close() = chain.close()
        }
        val fb = ByteBuffer.wrap(feats.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val f = FloatArray(fb.remaining()).also { fb.get(it) }
        val s = NpuStreamer(c, masks, recorder)
        val heads = ArrayList<HeadValues>()
        var at = 0
        while (at < f.size) {   // pushed in uneven pieces, as audio arrives
            val n = minOf(80 * 37, f.size - at)
            heads += s.push(f.copyOfRange(at, at + n))
            at += n
        }
        assertEquals(15, heads.size)
        assertTrue(chunkOf.all { it == 15 })
        assertEquals(15 * 298, checked)
        // the graph outputs, int16 codes, against the board's own stream files
        val names = c.heads.map { it.output }
        assertEquals(12, names.size)
        for (n in names) {
            val boardFile = File(board, if (n == "logits") "panel_consonants_124.raw" else "panel_consonants_124.$n.raw")
            val all = got.getValue(n).fold(ShortArray(0)) { a, b -> a + b }
            assertArrayEquals("head $n: chain output vs the board", shorts(boardFile), all)
        }
        // every head finished on the host (log-softmax rows sum to 1)
        val lp = heads[3][HeadId.LOG_PROBS]!!
        assertEquals(1.0, (0 until 38).sumOf { Math.exp(lp[it].toDouble()) }, 1e-4)
        println("exynos2500 chain: 15 chunks x 9 parts, ${15 * 298} part inputs identical to the board loop's; 12 heads = board")
    }
}
