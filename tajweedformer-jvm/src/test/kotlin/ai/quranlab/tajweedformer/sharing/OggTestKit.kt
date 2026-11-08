package ai.quranlab.tajweedformer.sharing

import org.junit.Assert.assertEquals
import org.junit.Assume
import java.io.File
import java.nio.file.Files

/** Reading Ogg pages back, decoding with libopus, test signals. */
object OggTestKit {
    class Page(val flags: Int, val granule: Long, val serial: Int, val sequence: Int, val packets: List<ByteArray>)

    /** Every Ogg page of [d], checking the capture pattern and each page's CRC. */
    fun pages(d: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var at = 0
        fun le(o: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((d[o + i].toLong() and 0xff) shl (8 * i)); return v }
        while (at < d.size) {
            assertEquals("OggS", String(d, at, 4, Charsets.US_ASCII))
            val n = d[at + 26].toInt() and 0xff
            var o = at + 27 + n
            val packets = ArrayList<ByteArray>()
            var cur = java.io.ByteArrayOutputStream()
            for (i in 0 until n) {
                val l = d[at + 27 + i].toInt() and 0xff
                cur.write(d, o, l)
                o += l
                if (l < 255) { packets.add(cur.toByteArray()); cur = java.io.ByteArrayOutputStream() }
            }
            assertEquals("page CRC", le(at + 22, 4).toInt(), OggOpusWriter.crc(d, at, o))
            out.add(Page(d[at + 5].toInt() and 0xff, le(at + 6, 8), le(at + 14, 4).toInt(), le(at + 18, 4).toInt(), packets))
            at = o
        }
        return out
    }

    /** RFC 7845 decoding with libopus: pre-skip dropped, the end trimmed to the last granule position. */
    fun decode(ogg: ByteArray): FloatArray {
        val pages = pages(ogg)
        val head = pages[0].packets[0]
        val preSkip = (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)
        val out = ArrayList<FloatArray>()
        var total = 0
        LibOpusDecoder().use { dec ->
            for (p in pages.drop(2)) for (pk in p.packets) { val x = dec.decode(pk); out.add(x); total += x.size }
        }
        val all = FloatArray(total)
        var o = 0
        for (x in out) { x.copyInto(all, o); o += x.size }
        val end = pages.last().granule.toInt()
        check(end <= all.size) { "granule $end past the decoded ${all.size}" }
        return all.copyOfRange(preSkip, minOf(end, all.size))
    }

    fun noise(n: Int, seed: Long = 1, gain: Float = 0.3f): FloatArray {
        var s = seed
        return FloatArray(n) {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 40).toFloat() / (1 shl 24).toFloat() * 2 - 1) * gain
        }
    }

    fun tone(n: Int, rate: Double, hz: Double = 440.0, from: Int = 0): FloatArray =
        FloatArray(n) { (Math.sin((from + it) * 2 * Math.PI * hz / rate) * 0.3).toFloat() }

    fun temp(ext: String = "opus"): File = Files.createTempFile("tj-", ".$ext").toFile()

    fun tempDir(): File = Files.createTempDirectory("tj-").toFile()

    /** Skips the test without the host libopus build, unless TJ_REQUIRE_OPUS=1 (then it fails). */
    fun requireOpus() {
        val ok = OpusNative.load()
        if (!ok && System.getProperty("TJ_REQUIRE_OPUS") == "1") throw AssertionError("libtajweedformer_opus: ${OpusNative.loadError}")
        Assume.assumeTrue("no libtajweedformer_opus (scripts/opus/build_opus_host.sh)", ok)
    }

    /** Appends [x] in pieces of random length (multiples of [unit] from 1 to [maxUnits]). */
    fun feed(w: OggOpusWriter, x: FloatArray, unit: Int, maxUnits: Int, rnd: java.util.Random = java.util.Random(7)) {
        var at = 0
        while (at < x.size) {
            val k = minOf(unit * (1 + rnd.nextInt(maxUnits)), x.size - at)
            w.append(x, at, k)
            at += k
        }
    }
}
