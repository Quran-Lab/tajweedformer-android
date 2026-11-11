package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Accelerator programs rebuilt on the device from the CPU engine's int8 weights plus a small delta (QLPD1, format
 * `quranlab.progdelta/1`; the reference is the publisher's `progdelta.py`, and the shared vectors pin it byte for byte).
 *
 * The CPU files are already on the device (the CPU set comes first), so an NPU program (100 to 185 MB) ships as the
 * residual of every byte against a prediction from the CPU weights. File: `QLPD1\n`, u32 header length, the header (JSON:
 * target name, size and SHA-256, the CPU files' SHA-256, the tensors), the scales (f32), then the body: the target
 * file with every predicted byte replaced by `(target - prediction) mod 256` (fp16 values: their ordered u16 difference,
 * stored as a plane of low bytes then a plane of high bytes).
 *
 * Prediction of an int8 code: `p = clamp(rint(((double)(sign * c) * (double)s_t) / (double)s_ch), -127, 127)` (round
 * half even), `byte = p xor (flip ? 0x80 : 0)`, where `c` is the engine's code (its pairing layout unpacked to [n, k],
 * transposed when the tensor says so), `s_t` its per-tensor scale and `s_ch` the program's scale of the row (or column,
 * or the tensor). fp16: `(fp16)((float) c * s_t)`, round to nearest even. The predicted matrix is laid out as the program
 * stores it (`rowmajor`, `transposed`, `colblock<B>`, `Tcolblock<B>`, `units<H>x<B>`) and its byte stream placed by
 * regions `[target_offset, layout_offset, length]` or strided runs `[target_offset, layout_offset, unit, count, stride]`.
 *
 * [rebuild] streams: the body goes to disk as it is read; each tensor's prediction (at most a few MB) is computed from
 * the memory-mapped CPU files and added over the span of the file it covers; the result's size and SHA-256 must equal
 * the header's target (and [expectSha256] when the manifest lists the program) before it is moved into place.
 */
internal object ProgramDelta {
    private val MAGIC = "QLPD1\n".toByteArray(Charsets.US_ASCII)

    /** The rebuilt program differs from what the delta promises, or the CPU files are not the ones it was made from. */
    class Refused(message: String) : IOException(message)

    /** A delta's header (JSON) and scales. */
    class Header(val json: Map<String, Any?>, val scales: FloatArray) {
        @Suppress("UNCHECKED_CAST")
        private val target = json["target"] as Map<String, Any?>
        val targetSize: Long = (target["size"] as Double).toLong()
        val targetSha256: String = (target["sha256"] as String).lowercase()
        val targetName: String = target["name"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val cpu: Map<String, String> = (json["cpu"] as? Map<String, Any?>).orEmpty().mapValues { (it.value as String).lowercase() }

        @Suppress("UNCHECKED_CAST")
        val tensors: List<Map<String, Any?>> = (json["tensors"] as? List<Any?>).orEmpty().map { it as Map<String, Any?> }
    }

    /** The decoded CPU files of the context: `layers.zfw`, `frontend.zfw`, the graph and its data. */
    class CpuFiles(val layers: File, val frontend: File, val graph: File, val graphData: File) {
        companion object {
            /** A layout-3 model folder (shared files at the root, the graph under `<context>/`) or a flat folder. */
            @JvmStatic
            fun of(dir: File, context: String = "c16_l64"): CpuFiles {
                fun p(name: String) = File(dir, name).takeIf { it.isFile } ?: File(File(dir, context), name)
                return CpuFiles(p("layers.zfw"), p("frontend.zfw"), p("model_engine2.onnx.zfg"), p("model_engine2.onnx.zfg.bin"))
            }
        }
    }

    /** What a rebuild did. */
    data class Result(val size: Long, val sha256: String, val tensors: Int, val predictedBytes: Long, val millis: Long)

    private fun open(delta: File): InputStream {
        val raw = BufferedInputStream(FileInputStream(delta), 1 shl 16)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 1 shl 16), 1 shl 16) else raw
    }

    private fun readHeader(input: InputStream): Header {
        val d = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        d.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw Refused("not a QLPD1 file")
        val lenBytes = ByteArray(4)
        d.readFully(lenBytes)
        val hl = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (hl <= 0 || hl > (64 shl 20)) throw Refused("header length $hl")
        val hb = ByteArray(hl)
        d.readFully(hb)
        @Suppress("UNCHECKED_CAST")
        val json = Json.parse(String(hb, Charsets.UTF_8)) as Map<String, Any?>
        if (json["format"] != "quranlab.progdelta/1") throw Refused("format ${json["format"]}")
        val sb = (json["scales_bytes"] as? Double)?.toInt() ?: 0
        if (sb < 0 || sb % 4 != 0) throw Refused("scales_bytes $sb")
        val s = ByteArray(sb)
        d.readFully(s)
        val fb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Header(json, FloatArray(fb.remaining()).also { fb.get(it) })
    }

    /** The header alone (a quick look: target size and SHA-256). */
    @JvmStatic
    fun header(delta: File): Header = open(delta).use { readHeader(it) }

    /** SHA-256 of a file, lower-case hex. */
    @JvmStatic
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Rebuilds [delta] (gzip or not) against [cpu] into [out], atomically (written to `<out>.part`, checked, then moved).
     * Throws [Refused] when the CPU files are not the delta's (their SHA-256 differs from the header's), or the result's
     * size or SHA-256 is not the header's target (or [expectSha256] / [expectSize] when given); nothing is left behind.
     */
    @JvmStatic
    @JvmOverloads
    fun rebuild(delta: File, cpu: CpuFiles, out: File, expectSha256: String? = null, expectSize: Long? = null): Result =
        rebuild(delta, cpu, out, expectSha256, expectSize, checkCpu = true)

    /** [checkCpu] false only in tests (to show that the target hash alone catches CPU files that differ). */
    internal fun rebuild(delta: File, cpu: CpuFiles, out: File, expectSha256: String?, expectSize: Long?, checkCpu: Boolean): Result {
        val t0 = System.nanoTime()
        val part = File(out.path + ".part")
        part.parentFile?.mkdirs()
        part.delete()
        try {
            val h: Header
            open(delta).use { input ->
                h = readHeader(input)
                if (expectSize != null && expectSize != h.targetSize) throw Refused("the delta makes ${h.targetSize} bytes, the manifest lists $expectSize")
                if (expectSha256 != null && expectSha256.lowercase() != h.targetSha256) throw Refused("the delta's target is not the program the manifest lists")
                // the CPU files must be the ones it was made against (the per-context graph data is covered by the target hash)
                if (checkCpu) for ((name, want) in h.cpu) {
                    val f = when (name) {
                        "layers.zfw" -> cpu.layers
                        "frontend.zfw" -> cpu.frontend
                        else -> continue
                    }
                    if (sha256(f) != want) throw Refused("CPU file $name is not the one this delta was made against")
                }
                // the body: the target with residuals in the predicted ranges, streamed to disk
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(0)
                    val buf = ByteArray(1 shl 20)
                    var left = h.targetSize
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw EOFException("the delta's body ends ${left} bytes early")
                        raf.write(buf, 0, n)
                        left -= n
                    }
                    if (input.read() >= 0) throw Refused("bytes after the delta's body")
                }
            }
            var predicted = 0L
            CpuTensors(cpu).use { e ->
                RandomAccessFile(part, "rw").use { raf ->
                    for (t in h.tensors) predicted += apply(raf, t, h.scales, e)
                }
            }
            val sha = sha256(part)
            if (part.length() != h.targetSize || sha != h.targetSha256) {
                throw Refused("rebuilt ${part.length()} bytes with SHA-256 $sha, the delta promises ${h.targetSize} bytes ${h.targetSha256}")
            }
            try {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return Result(h.targetSize, sha, h.tensors.size, predicted, (System.nanoTime() - t0) / 1_000_000)
        } catch (x: Throwable) {
            part.delete()
            throw x
        }
    }

    // ------------------------------------------------------------------------------------------------- prediction

    private fun num(t: Map<String, Any?>, k: String): Long = (t[k] as? Double)?.toLong() ?: throw Refused("tensor field $k")

    /** One tensor: its predicted byte stream added over every region of [raf]. Returns the bytes predicted. */
    private fun apply(raf: RandomAccessFile, t: Map<String, Any?>, scales: FloatArray, e: CpuTensors): Long {
        val src = t["src"] as? String ?: throw Refused("tensor without src")
        val rows = num(t, "rows").toInt()
        val cols = num(t, "cols").toInt()
        val transposed = t["transposed"] == true
        val w = e.codes(src)
        // the stored matrix [rows, cols]: the engine's [n, k], or its transpose
        val sr = if (transposed) w.k else w.n
        val sc = if (transposed) w.n else w.k
        if (sr != rows || sc != cols) throw Refused("$src is [${w.n}, ${w.k}], the delta stores [$rows, $cols]")
        fun code(r: Int, c: Int): Int = if (transposed) w.codes[c * w.k + r].toInt() else w.codes[r * w.k + c].toInt()
        @Suppress("UNCHECKED_CAST")
        val regions = (t["regions"] as? List<Any?>).orEmpty().map { g -> (g as List<Any?>).map { (it as Double).toLong() } }
        return when (t["kind"]) {
            "int8" -> {
                val sign = num(t, "sign").toDouble()
                val flip = t["flip"] == true
                @Suppress("UNCHECKED_CAST")
                val sp = (t["scales"] as List<Any?>).map { (it as Double).toInt() }
                val first = sp[0]
                val count = sp[1]
                if (first < 0 || count < 1 || first + count > scales.size) throw Refused("$src: scales $first+$count of ${scales.size}")
                val st = w.scale.toDouble()
                val m = ByteArray(rows * cols)
                for (r in 0 until rows) for (c in 0 until cols) {
                    val s = when {
                        count == 1 -> scales[first]
                        count == rows -> scales[first + r]
                        else -> scales[first + c]
                    }.toDouble()
                    val p = Math.rint((sign * code(r, c)) * st / s).coerceIn(-127.0, 127.0).toInt()
                    m[r * cols + c] = (if (flip) (p xor 0x80) else p).toByte()
                }
                val layout = t["layout"] as? String ?: "rowmajor"
                addRegions(raf, layout(m, rows, cols, layout), regions)
            }
            "fp16" -> {
                val st = w.scale
                val v = ShortArray(rows * cols)
                for (r in 0 until rows) for (c in 0 until cols) v[r * cols + c] = toHalf(code(r, c).toFloat() * st)
                addFp16(raf, v, regions)
            }
            else -> throw Refused("$src: kind ${t["kind"]}")
        }
    }

    /** The stored matrix [m] ([rows, cols], row-major) as the program lays it out, as one byte stream. */
    internal fun layout(m: ByteArray, rows: Int, cols: Int, layout: String): ByteArray {
        if (layout == "rowmajor") return m
        if (layout == "transposed") {
            val o = ByteArray(m.size)
            for (r in 0 until rows) for (c in 0 until cols) o[c * rows + r] = m[r * cols + c]
            return o
        }
        if (layout.startsWith("T")) return layout(layout(m, rows, cols, "transposed"), cols, rows, layout.substring(1))
        if (layout.startsWith("units")) {
            val (h, b) = layout.removePrefix("units").split('x').map { it.toInt() }
            if (rows % h != 0 || cols % b != 0) throw Refused("$layout on [$rows, $cols]")
            val o = ByteArray(m.size)
            var i = 0
            for (tr in 0 until rows / h) for (tc in 0 until cols / b) for (y in 0 until h) for (x in 0 until b) o[i++] = m[(tr * h + y) * cols + tc * b + x]
            return o
        }
        if (layout.startsWith("colblock")) {
            val b = layout.removePrefix("colblock").toInt()
            if (b <= 0 || cols % b != 0) throw Refused("$layout on [$rows, $cols]")
            val o = ByteArray(m.size)
            var i = 0
            for (j in 0 until cols / b) for (r in 0 until rows) for (x in 0 until b) o[i++] = m[r * cols + j * b + x]
            return o
        }
        throw Refused("unknown layout $layout")
    }

    /**
     * Adds [p] over its regions (`[offset, layout offset, length]`, or strided runs `[offset, layout offset, unit, count,
     * stride]`). The span of the file the tensor covers is read once, patched in memory and written back when it is at
     * most [SPAN] bytes; otherwise each region (each unit of a run) is read, patched and written on its own.
     */
    private fun addRegions(raf: RandomAccessFile, p: ByteArray, regions: List<List<Long>>): Long {
        if (regions.isEmpty()) return 0
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        val size = raf.length()
        for (g in regions) {
            val (off, from, unit, count, stride) = when (g.size) {
                3 -> listOf(g[0], g[1], g[2], 1L, g[2])
                5 -> listOf(g[0], g[1], g[2], g[3], g[4])
                else -> throw Refused("a region of ${g.size} numbers")
            }
            if (count < 1 || unit < 0 || from < 0 || from + unit * count > p.size) throw Refused("a region outside the tensor")
            val first = off
            val last = off + (count - 1) * stride
            if (minOf(first, last) < 0 || maxOf(first, last) + unit > size) throw Refused("a region outside the file")
            lo = minOf(lo, minOf(first, last))
            hi = maxOf(hi, maxOf(first, last) + unit)
        }
        var n = 0L
        val whole = hi - lo <= SPAN
        val buf = if (whole) ByteArray((hi - lo).toInt()) else ByteArray(0)
        if (whole) {
            raf.seek(lo)
            raf.readFully(buf)
        }
        var scratch = ByteArray(0)
        for (g in regions) {
            val unit = g[2].toInt()
            val count = if (g.size == 5) g[3] else 1L
            val stride = if (g.size == 5) g[4] else g[2]
            var from = g[1].toInt()
            for (i in 0 until count) {
                val at = g[0] + i * stride
                if (whole) {
                    val o = (at - lo).toInt()
                    for (j in 0 until unit) buf[o + j] = (buf[o + j] + p[from + j]).toByte()
                } else {
                    if (scratch.size < unit) scratch = ByteArray(unit)
                    raf.seek(at)
                    raf.readFully(scratch, 0, unit)
                    for (j in 0 until unit) scratch[j] = (scratch[j] + p[from + j]).toByte()
                    raf.seek(at)
                    raf.write(scratch, 0, unit)
                }
                from += unit
                n += unit
            }
        }
        if (whole) {
            raf.seek(lo)
            raf.write(buf)
        }
        return n
    }

    /** fp16 regions `[offset, value offset, values]`: low-byte plane then high-byte plane of the ordered difference. */
    private fun addFp16(raf: RandomAccessFile, v: ShortArray, regions: List<List<Long>>): Long {
        var n = 0L
        for (g in regions) {
            if (g.size != 3) throw Refused("an fp16 region of ${g.size} numbers")
            val off = g[0]
            val lo = g[1].toInt()
            val cnt = g[2].toInt()
            if (off < 0 || lo < 0 || lo + cnt > v.size || off + 2L * cnt > raf.length()) throw Refused("an fp16 region outside the file or the tensor")
            val buf = ByteArray(2 * cnt)
            raf.seek(off)
            raf.readFully(buf)
            // the planes are read whole before the values are written (interleaved) over them
            val res = ByteArray(2 * cnt)
            for (i in 0 until cnt) {
                val d = (buf[i].toInt() and 0xff) or ((buf[cnt + i].toInt() and 0xff) shl 8)
                var o = ordered(v[lo + i].toInt() and 0xffff) + d
                o = ((o + 32768) % 65536 + 65536) % 65536 - 32768
                val u = unordered(o)
                res[2 * i] = (u and 0xff).toByte()
                res[2 * i + 1] = ((u shr 8) and 0xff).toByte()
            }
            raf.seek(off)
            raf.write(res)
            n += 2L * cnt
        }
        return n
    }

    private fun ordered(u: Int): Int = if (u < 0x8000) u else -1 - (u and 0x7fff)

    private fun unordered(o: Int): Int = if (o >= 0) o else 0x8000 or (-1 - o)

    /** float to IEEE half, round to nearest even (numpy's astype(float16)). */
    internal fun toHalf(f: Float): Short {
        val x = java.lang.Float.floatToRawIntBits(f)
        val sign = (x ushr 16) and 0x8000
        val exp = (x ushr 23) and 0xff
        var mant = x and 0x7fffff
        if (exp == 0xff) return (sign or 0x7c00 or (if (mant != 0) 0x200 else 0)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - e
            var h = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (h and 1) == 1)) h++
            return (sign or h).toShort()
        }
        var h = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
        return (sign or h).toShort()
    }

    /** At most this many bytes of a tensor's span are patched in memory at once. */
    private const val SPAN = 8L shl 20

    // ------------------------------------------------------------------------------------------------- CPU tensors

    /** An engine int8 linear: its codes [n, k] row-major and its per-tensor scale. */
    class Codes(val n: Int, val k: Int, val codes: ByteArray, val scale: Float)

    /** The CPU engine's int8 linears, memory-mapped (read only); each unpacked when a tensor asks for it. */
    internal class CpuTensors(private val f: CpuFiles) : AutoCloseable {
        private val channels = ArrayList<FileChannel>()
        private fun map(file: File): MappedByteBuffer {
            val ch = RandomAccessFile(file, "r").channel
            channels.add(ch)
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
        }

        private class Zfw(val buf: ByteBuffer, val index: Map<String, IntArray>) // name -> dtype, offset, bytes

        private val zfw = HashMap<String, Zfw>()
        private var graph: Map<String, Triple<Int, Int, Pair<Int, Int>>>? = null // packed name -> (k, n, (scale offset, packed offset))
        private var graphBytes: Map<String, Int>? = null
        private var graphData: ByteBuffer? = null

        private fun zfw(name: String, file: File): Zfw = zfw.getOrPut(name) {
            val b = map(file)
            val magic = ByteArray(4).also { b.get(0, it) }
            if (String(magic, Charsets.US_ASCII) != "ZFW1") throw Refused("$name is not a zfw file")
            val count = b.getInt(8)
            val idx = HashMap<String, IntArray>()
            for (i in 0 until count) {
                val p = 12 + i * 68
                val nb = ByteArray(40).also { b.get(p, it) }
                val nm = String(nb, Charsets.UTF_8).trimEnd('\u0000')
                idx[nm] = intArrayOf(b.getInt(p + 40), b.getInt(p + 44), b.getInt(p + 48))
            }
            Zfw(b, idx)
        }

        private fun ByteBuffer.get(at: Int, dst: ByteArray) { val d = duplicate(); d.position(at); d.get(dst) }

        fun codes(src: String): Codes {
            val file = src.substringBefore(':')
            val pre = src.substringAfter(':')
            return when (file) {
                "layers.zfw", "frontend.zfw" -> {
                    val z = zfw(file, if (file == "layers.zfw") f.layers else f.frontend)
                    val shape = z.index["$pre.shape"] ?: throw Refused("$src: no shape")
                    val packed = z.index["$pre.packed"] ?: throw Refused("$src: no packed codes")
                    val sc = z.index["$pre.scale"] ?: throw Refused("$src: no scale")
                    val k = z.buf.getInt(shape[1])
                    val n = z.buf.getInt(shape[1] + 4)
                    unpack(z.buf, packed[1], k, n, z.buf.getFloat(sc[1]))
                }
                "zfg" -> {
                    readGraph()
                    val g = graph!![pre] ?: throw Refused("$src: not a ZfLinearS8 weight of the graph")
                    val data = graphData!!
                    unpack(data, g.third.second, g.first, g.second, data.getFloat(g.third.first))
                }
                else -> throw Refused("$src: unknown source")
            }
        }

        /** The engine's pairing layout (16-wide k groups, 4-row n tiles) back to [n, k]. */
        private fun unpack(b: ByteBuffer, at: Int, k: Int, n: Int, scale: Float): Codes {
            val kp = (k + 15) / 16 * 16
            val out = ByteArray(n * k)
            for (r in 0 until n) {
                val br = r / 4
                val rr = r % 4
                for (c in 0 until k) {
                    val idx = ((br * (kp / 16) + c / 16) * 4 + rr) * 16 + c % 16
                    out[r * k + c] = b.get(at + idx)
                }
            }
            return Codes(n, k, out, scale)
        }

        private fun readGraph() {
            if (graph != null) return
            val lines = f.graph.readLines()
            val inits = HashMap<String, Pair<Int, Int>>()
            for (l in lines) if (l.startsWith("init ")) {
                val p = l.trim().split(Regex("\\s+"))
                inits[p[1]] = p[p.size - 2].toInt() to p[p.size - 1].toInt()
            }
            val g = HashMap<String, Triple<Int, Int, Pair<Int, Int>>>()
            for ((i, l) in lines.withIndex()) if (l.contains(" ZfLinearS8 ")) {
                val p = l.trim().split(Regex("\\s+"))
                val packed = p[6]
                val sc = p[8]
                val k = lines[i + 1].trim().split(Regex("\\s+")).last().toInt()
                val n = lines[i + 2].trim().split(Regex("\\s+")).last().toInt()
                val po = inits[packed] ?: continue
                val so = inits[sc] ?: continue
                g[packed] = Triple(k, n, so.first to po.first)
            }
            graph = g
            graphData = map(f.graphData)
            graphBytes = inits.mapValues { it.value.second }
        }

        override fun close() {
            for (c in channels) try { c.close() } catch (_: Exception) {}
            channels.clear()
        }
    }
}
