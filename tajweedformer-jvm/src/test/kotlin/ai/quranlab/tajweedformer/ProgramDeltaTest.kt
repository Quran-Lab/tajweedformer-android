package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.ProgramDelta
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * QLPD1 program deltas ([ProgramDelta]): the shared cross-SDK vectors byte for byte (every tensor kind and layout:
 * int8 rowmajor per row, transposed colblock8 flipped and negated, colblock4 per column on an odd shape, Tcolblock8,
 * units8x4 strided runs from the graph data, the front end with two tensors, fp16 planes), the refusal of CPU files that
 * are not the delta's, a damaged delta, and nothing left behind on a failure. With `TJ_PROGDELTA_STAGED` (a folder with
 * `deltas.json` and the staged `.qlpd.gz` files) and `TJ_PROGDELTA_CPU` (the decoded CPU files of that revision), every
 * staged NPU delta is rebuilt and checked against its target SHA-256, with times and memory printed (local only: the
 * deltas are 279 MB).
 */
/** The rebuild in a process of its own (the memory bound): args delta, CPU folder, output, SHA-256, size. */
object ProgramDeltaRebuildMain {
    @JvmStatic
    fun main(a: Array<String>) {
        val r = ProgramDelta.rebuild(File(a[0]), ProgramDelta.CpuFiles.of(File(a[1]), "c16_l64"), File(a[2]), a[3], a[4].toLong())
        val rt = Runtime.getRuntime()
        println(String.format(Locale.ROOT, "%d bytes, SHA-256 matches, %d ms, heap at the end %.1f MB of a %.0f MB maximum", r.size, r.millis,
            (rt.totalMemory() - rt.freeMemory()) / 1e6, rt.maxMemory() / 1e6))
    }
}

class ProgramDeltaTest {
    private val dirs = ArrayList<File>()
    private fun dir() = Files.createTempDirectory("tj-qlpd-").toFile().also { dirs.add(it) }

    @After fun tearDown() = dirs.forEach { it.deleteRecursively() }

    /** The vectors from the test resources, copied to a folder (the rebuild reads files). */
    private val vectors: File by lazy {
        val d = dir()
        val base = "/progdelta_vectors/"
        val names = listOf("vectors.json") + listOf("layers.zfw", "frontend.zfw", "model_engine2.onnx.zfg", "model_engine2.onnx.zfg.bin")
            .flatMap { listOf("cpu/$it", "cpu_wrong/$it") }
        for (n in names) File(d, n).also { it.parentFile.mkdirs() }.writeBytes(res(base + n))
        @Suppress("UNCHECKED_CAST")
        val v = Json.parse(File(d, "vectors.json").readText()) as Map<String, Any?>
        for (c in v["cases"] as List<Map<String, Any?>>) for (k in listOf("delta", "target")) {
            val n = c[k] as String
            File(d, n).also { it.parentFile.mkdirs() }.writeBytes(res(base + n))
        }
        d
    }

    private fun res(name: String): ByteArray = javaClass.getResourceAsStream(name)?.use { it.readBytes() } ?: error("no resource $name")

    @Suppress("UNCHECKED_CAST")
    private val cases: List<Map<String, Any?>> by lazy { (Json.parse(File(vectors, "vectors.json").readText()) as Map<String, Any?>)["cases"] as List<Map<String, Any?>> }

    @Test fun everyVectorRebuildsByteForByte() {
        val cpu = ProgramDelta.CpuFiles.of(File(vectors, "cpu"))
        val kinds = HashSet<String>()
        for (c in cases) {
            val out = File(dir(), "out.bin")
            val r = ProgramDelta.rebuild(File(vectors, c["delta"] as String), cpu, out)
            val want = File(vectors, c["target"] as String).readBytes()
            assertArrayEquals(c["name"] as String, want, out.readBytes())
            assertEquals(c["sha256"], r.sha256)
            assertEquals((c["size"] as Double).toLong(), r.size)
            @Suppress("UNCHECKED_CAST")
            for (t in c["tensors"] as List<Map<String, Any?>>) kinds += "${t["kind"]}:${t["layout"] ?: "-"}"
            println("qlpd vector ${c["name"]}: ${r.size} bytes, ${r.tensors} tensors, ${r.predictedBytes} bytes predicted, identical")
        }
        assertEquals(7, cases.size)
        assertTrue(kinds.toString(), kinds.containsAll(listOf("int8:rowmajor", "int8:colblock8", "int8:colblock4", "int8:Tcolblock8", "int8:units8x4")))
        assertTrue(kinds.toString(), kinds.any { it.startsWith("fp16") })
    }

    @Test fun cpuFilesThatAreNotTheDeltasAreRefusedAndTheTargetHashCatchesThemToo() {
        val c = cases.first { it["name"] == "int8_rowmajor_per_row" }
        val out = File(dir(), "out.bin")
        val wrong = ProgramDelta.CpuFiles.of(File(vectors, "cpu_wrong"))
        try {
            ProgramDelta.rebuild(File(vectors, c["delta"] as String), wrong, out)
            fail("rebuilt against the wrong CPU files")
        } catch (e: ProgramDelta.Refused) {
            assertTrue(e.message, e.message!!.contains("layers.zfw"))
        }
        // without the CPU check, the rebuilt bytes do not match the target: refused by the hash
        try {
            ProgramDelta.rebuild(File(vectors, c["delta"] as String), wrong, out, null, null, checkCpu = false)
            fail("the target hash let a wrong rebuild through")
        } catch (e: ProgramDelta.Refused) {
            assertTrue(e.message, e.message!!.contains("SHA-256"))
        }
        assertFalse(out.exists())
        assertFalse(File(out.path + ".part").exists())
    }

    @Test fun aDamagedDeltaOrAnotherProgramIsRefusedAndLeavesNothing() {
        val c = cases.first { it["name"] == "int8_units8x4_zfg_strided" }
        val raw = GZIPInputStream(File(vectors, c["delta"] as String).inputStream()).use { it.readBytes() }
        // one body byte changed (the last one)
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        val bad = File(dir(), "bad.qlpd").also { f -> f.writeBytes(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()) }
        val out = File(dir(), "out.bin")
        val cpu = ProgramDelta.CpuFiles.of(File(vectors, "cpu"))
        try {
            ProgramDelta.rebuild(bad, cpu, out)
            fail("a damaged delta rebuilt")
        } catch (e: ProgramDelta.Refused) {
        }
        assertFalse(out.exists())
        // the manifest lists another program than the delta's target
        try {
            ProgramDelta.rebuild(File(vectors, c["delta"] as String), cpu, out, expectSha256 = "0".repeat(64))
            fail("a delta of another program was used")
        } catch (e: ProgramDelta.Refused) {
        }
        // a truncated body
        val short = File(dir(), "short.qlpd").also { it.writeBytes(raw.copyOf(raw.size - 10)) }
        try {
            ProgramDelta.rebuild(short, cpu, out)
            fail("a truncated delta rebuilt")
        } catch (e: java.io.IOException) {
        }
        assertFalse(out.exists())
    }

    @Test fun halfPrecisionRoundsToNearestEven() {
        fun h(f: Float) = ProgramDelta.toHalf(f).toInt() and 0xffff
        assertEquals(0x3c00, h(1f))
        assertEquals(0xc000, h(-2f))
        assertEquals(0x7bff, h(65504f))
        assertEquals(0x7c00, h(65520f))
        assertEquals(0x0001, h(5.9604645e-8f))
        assertEquals(0x0000, h(2.9802322e-8f)) // a tie at half the smallest subnormal: to even (0)
        assertEquals(0x3c00, h(1.00048828125f)) // 1 + 2^-11: a tie, to even
        assertEquals(0x3c02, h(1.00146484375f)) // 1 + 3 x 2^-11: a tie, to even (up)
    }

    /** Every staged NPU delta of the revision rebuilt against its CPU files (local only). */
    @Test fun theStagedNpuDeltasRebuildToTheirTargets() {
        val staged = TestKit.env("TJ_PROGDELTA_STAGED")?.let(::File)
        val cpuDir = TestKit.env("TJ_PROGDELTA_CPU")?.let(::File)
        Assume.assumeTrue("TJ_PROGDELTA_STAGED and TJ_PROGDELTA_CPU name the staged deltas and the CPU files",
            staged != null && cpuDir != null && File(staged, "deltas.json").isFile)
        @Suppress("UNCHECKED_CAST")
        val all = ((Json.parse(File(staged!!, "deltas.json").readText()) as Map<String, Any?>)["deltas"] as List<Map<String, Any?>>)
            .filter { it["staged"] != null }
        val cpu = ProgramDelta.CpuFiles.of(cpuDir!!, "c16_l64")
        val out = dir()
        var total = 0L
        for (d in all) {
            @Suppress("UNCHECKED_CAST")
            val target = d["target"] as Map<String, Any?>
            val f = File(out, target["name"] as String)
            val r = ProgramDelta.rebuild(File(staged, d["staged"] as String), cpu, f, target["sha256"] as String, (target["size"] as Double).toLong())
            assertEquals(target["sha256"], r.sha256)
            total += r.millis
            println(String.format(Locale.ROOT, "staged %s: %d bytes, %d tensors, %.1f MB predicted, %d ms, SHA-256 matches", target["name"], r.size, r.tensors,
                r.predictedBytes / 1e6, r.millis))
            f.delete()
        }
        println(String.format(Locale.ROOT, "staged NPU deltas: %d of %d rebuilt and verified in %.1f s", all.size, all.size, total / 1000.0))
        assertEquals(13, all.size)
        // bounded memory: the largest program rebuilt in a child JVM whose heap is capped at 64 MB (the CPU files are
        // mapped, the body streamed to disk, one tensor's prediction and an 8 MB span in memory at a time)
        @Suppress("UNCHECKED_CAST")
        val big = all.maxBy { ((it["target"] as Map<String, Any?>)["size"] as Double) }
        @Suppress("UNCHECKED_CAST")
        val bt = big["target"] as Map<String, Any?>
        val java = ProcessHandle.current().info().command().orElse("java")
        val f = File(out, bt["name"] as String)
        val proc = ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"), ProgramDeltaRebuildMain::class.java.name,
            File(staged, big["staged"] as String).path, cpuDir.path, f.path, bt["sha256"] as String, (bt["size"] as Double).toLong().toString())
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(text, 0, proc.waitFor())
        println("staged ${bt["name"]} in a child JVM with -Xmx64m: $text")
        f.delete()
    }
}
