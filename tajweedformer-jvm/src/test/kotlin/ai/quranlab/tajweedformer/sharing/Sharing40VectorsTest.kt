package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.ProtectedKit
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.cloud.DeviceModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * SHARING_40.md (section 19) against copies of `vectors/sharing40/`: the 75 s session at 16 kHz
 * through the SDK's FLAC encoder and muxer ([OggFlacWriter]) byte for byte, segment by segment (fed as 16-bit integers
 * and as floats, in uneven pieces); every case of `flac_cases.json` by size and SHA-256 (and its bytes when small),
 * frames per segment and frame kinds; `pcm16.json` through [Pcm16]; `device_model.json` through [DeviceModel]; and
 * the bodies of `upload_start.json`'s `accepted` that this SDK sends, rebuilt by [ShareCapture.sessionJson].
 */
class Sharing40VectorsTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-flac").toFile().also { dirs += it }

    @After fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun resource(name: String): ByteArray =
        Sharing40VectorsTest::class.java.getResourceAsStream("/vectors40/sharing40/$name")!!.use { it.readBytes() }

    @Suppress("UNCHECKED_CAST")
    private fun json(name: String) = Json.parse(String(resource(name), Charsets.UTF_8)) as Map<String, Any?>

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun pcmSha(pcm: IntArray): String {
        val b = ByteArray(pcm.size * 2)
        for ((i, v) in pcm.withIndex()) { b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        return sha(b)
    }

    /** One session through the writer: its segment files in order and the segments it reported. */
    private fun encode(pcm: IntArray, rate: Int, serial: Int, vendor: String, floats: Boolean = false): Pair<List<ByteArray>, List<OggSegment>> {
        val dir = temp()
        val closed = ArrayList<OggSegment>()
        val w = OggFlacWriter({ File(dir, ShareFiles.name(it) + ShareFiles.FLAC) }, rate, serial, vendor) { closed += it }
        var at = 0
        var k = 0
        // uneven pieces: the encoder's blocks never line up with what the capture hands over
        val sizes = intArrayOf(1600, 1, 4095, 4097, 333, 8192, 2048, 7)
        while (at < pcm.size) {
            val n = minOf(sizes[k++ % sizes.size], pcm.size - at)
            if (floats) w.append(FloatArray(n) { pcm[at + it] / 32768f }, 0, n) else w.appendPcm(pcm, at, n)
            at += n
        }
        w.finish()
        return closed.map { it.file.readBytes() } to closed
    }

    /** The pages of an Ogg stream: each page's packet (one a page here). */
    private fun packets(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < b.size) {
            val nseg = b[at + 26].toInt() and 0xff
            var len = 0
            for (i in 0 until nseg) len += b[at + 27 + i].toInt() and 0xff
            out += b.copyOfRange(at + 27 + nseg, at + 27 + nseg + len)
            at += 27 + nseg + len
        }
        return out
    }

    /** A frame's subframe kind as the vectors name it: constant, verbatim, fixed<order>. */
    private fun kind(f: ByteArray): String {
        val lead = f[4].toInt() and 0xff
        val numberBytes = when {
            lead < 0x80 -> 1
            lead < 0xE0 -> 2
            lead < 0xF0 -> 3
            lead < 0xF8 -> 4
            lead < 0xFC -> 5
            else -> 6
        }
        val blockCode = (f[2].toInt() ushr 4) and 0xf
        val rateCode = f[2].toInt() and 0xf
        val extra = (if (blockCode == 6) 1 else if (blockCode == 7) 2 else 0) + (if (rateCode == 12) 1 else if (rateCode == 13 || rateCode == 14) 2 else 0)
        val type = ((f[4 + numberBytes + extra + 1].toInt() and 0xff) ushr 1) and 0x3f
        return when {
            type == 0 -> "constant"
            type == 1 -> "verbatim"
            type in 8..12 -> "fixed${type - 8}"
            else -> "type$type"
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun theSixteenKilohertzSessionByteForByte() {
        val e = json("flac16k/expected.json")
        val input = e["input"] as Map<String, Any?>
        val rate = (input["rate"] as Double).toInt()
        val pcm = testSignal((input["samples"] as Double).toInt(), rate, (input["seed"] as Double).toLong())
        assertEquals(input["pcm_sha256"], pcmSha(pcm))
        assertEquals((input["frames_per_segment"] as List<Double>).map { it.toInt() }, (1..4).map { OggFlacWriter.framesIn(it, rate) })
        val want = (e["segments"] as List<Map<String, Any?>>)
        for (floats in listOf(false, true)) {
            val (files, closed) = encode(pcm, rate, (input["serial"] as Double).toLong().toInt(), input["vendor"] as String, floats)
            assertEquals("floats $floats", want.size, files.size)
            for ((i, s) in want.withIndex()) {
                val name = s["file"] as String
                assertArrayEquals("$name (floats $floats)", resource("flac16k/$name"), files[i])
                assertEquals(s["sha256"], sha(files[i]))
                assertEquals(i + 1, closed[i].n)
                assertEquals("only the last segment ends the stream", i == want.size - 1, closed[i].last)
                assertEquals((s["pages"] as List<Map<String, Any?>>).last()["granule"], closed[i].granule.toDouble())
            }
            assertEquals(e["whole_sha256"], sha(files.reduce { a, b -> a + b }))
            assertEquals(e["final_granule"], closed.last().granule.toDouble())
        }
        // the frames' kinds, and the two header pages of 19.4.1
        val (files, _) = encode(pcm, rate, (input["serial"] as Double).toLong().toInt(), input["vendor"] as String)
        val all = packets(files.reduce { a, b -> a + b })
        assertEquals(79, 27 + 1 + all[0].size)
        val kinds = all.drop(2).groupingBy { kind(it) }.eachCount().mapValues { it.value.toDouble() }
        assertEquals((e["frames"] as Map<String, Any?>)["kinds"], kinds.toSortedMap())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun everyCaseOfFlacCases() {
        val cases = json("flac_cases.json")["cases"] as List<Map<String, Any?>>
        assertEquals(10, cases.size)
        for (c in cases) {
            val name = c["name"] as String
            val rate = (c["rate"] as Double).toInt()
            val n = (c["samples"] as Double).toInt()
            val ts = (c["input"] as Map<String, Any?>)["test_signal"] as Map<String, Any?>?
            val pcm = if (ts != null) testSignal(n, rate, (ts["seed"] as Double).toLong()) else casePcm(name, n)
            assertEquals(name, c["pcm_sha256"], pcmSha(pcm))
            val (files, _) = encode(pcm, rate, (c["serial"] as Double).toLong().toInt(), c["vendor"] as String)
            val whole = files.reduce { a, b -> a + b }
            assertEquals(name, (c["bytes"] as Double).toInt(), whole.size)
            assertEquals(name, c["sha256"], sha(whole))
            (c["ogg_flac_base64"] as String?)?.let { assertArrayEquals(name, Base64.getDecoder().decode(it), whole) }
            assertEquals(name, (c["segments"] as List<Double>).map { it.toInt() }, files.mapIndexed { i, f -> packets(f).size - if (i == 0) 2 else 0 })
            assertEquals(name, c["frame_kinds"], packets(whole).drop(2).map { kind(it) })
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun pcm16() {
        val cases = json("pcm16.json")["cases"] as List<Map<String, Any?>>
        assertEquals(31, cases.size)
        for (c in cases) {
            val x = Float.fromBits((c["f32"] as String).toLong(16).toInt())
            assertEquals("${c["f32"]} ${c["value"]}", (c["int16"] as Double).toInt(), Pcm16.of(x))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun deviceModel() {
        val v = json("device_model.json")
        val pattern = Regex(v["pattern"] as String)
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(17, cases.size)
        for (c in cases) {
            val parts = (c["parts"] as List<String>).toTypedArray()
            val got = DeviceModel.of(*parts)
            assertEquals("${c["what"]}", c["device_model"], got)
            got?.let { assertTrue(it, pattern.matches(it)) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun theStartBodiesThisSdkSendsAreAccepted() {
        val accepted = (json("upload_start.json")["accepted"] as List<Map<String, Any?>>).associate { it["name"] as String to it["body"] as Map<String, Any?> }
        fun body(name: String, rate: Int, flac: Boolean, deviceClass: String, osMajor: Int, model: String?): Map<String, Any?> {
            val want = accepted.getValue(name)
            val decode = want["decode"] as Map<String, Any?>
            val b = ShareCapture.sessionJson(decode["model_revision"] as String, decode["backend"] as String, null, want["consent_version"] as String,
                want["contributor"] as String, null, null, want["day"] as String, deviceClass, osMajor, rate, "built_in", flac = flac, deviceModel = model)
            return Json.parse(String(b, Charsets.UTF_8)) as Map<String, Any?>
        }
        // the vectors' SDK version is this one
        assertEquals("4.0.0", SdkInfo.VERSION)
        val cases = listOf(
            listOf("flac 16 kHz from iOS 4.0", 16_000, true, "A17", 26, "iPhone16,1"),
            listOf("flac 44.1 kHz from Android 4.0", 44_100, true, "sm8650", 15, "samsung SM-S921B"),
            listOf("opus 48 kHz from 4.0 with a model", 48_000, false, "A17", 26, "iPhone16,1"),
            listOf("opus from web 4.0: no device_model", 48_000, false, "web", 26, null),
        )
        for (c in cases) {
            val name = c[0] as String
            assertEquals(name, accepted.getValue(name), body(name, c[1] as Int, c[2] as Boolean, c[3] as String, c[4] as Int, c[5] as String?))
        }
    }

    /** A FLAC recording the app was killed in: the open segment is cut back to its whole pages, its time at the capture rate. */
    @Test fun aKilledFlacRecordingIsRecoveredAtItsRate() {
        val dir = temp()
        File(dir, ShareFiles.SESSION).writeText("""{"audio":{"codec":"flac","container":"ogg","sample_rate":16000,"channels":1,"bits_per_sample":16}}""")
        ShareFiles.writeCapture(dir, 0, emptyList(), 0, flacRate = 16_000)
        val w = OggFlacWriter({ File(dir, ShareFiles.name(it) + ShareFiles.FLAC) }, 16_000, 7) { ShareFiles.mark(dir, it.n, it.granule) }
        val pcm = testSignal(16_000 * 15, 16_000, 3)
        w.appendPcm(pcm)
        // killed: segment 2 open, its last page cut in half
        val open = File(dir, ShareFiles.name(2) + ShareFiles.FLAC)
        val bytes = open.readBytes()
        open.writeBytes(bytes.copyOf(bytes.size - 10))
        assertEquals(listOf(1), ShareFiles.markers(dir).map { it.n })
        assertTrue(ShareFiles.recover(dir))
        val m = ShareFiles.markers(dir)
        assertEquals(listOf(1, 2), m.map { it.n })
        // 58 frames fully written of 15 s (the 59th held, the last cut): 40 in segment 1, 16 whole in segment 2
        assertEquals(40L * 4096, m[0].granule)
        assertEquals(56L * 4096, m[1].granule)
        assertEquals((56L * 4096 * 1000 / 16_000).toInt(), ShareFiles.end(dir)!!.durationMs)
        assertEquals(56L * 4096 / 16_000.0, ShareFiles.seconds(m, ShareFiles.granuleRate(dir)), 1e-9)
    }

    @Test fun aFortyEightKilohertzCaptureIsNeverFlac() {
        try {
            OggFlacWriter({ File(temp(), "x.oga") }, 48_000)
            throw AssertionError("a FLAC writer at 48 kHz")
        } catch (e: SharingException) {
            assertEquals("unsupported_audio", e.code)
        }
    }

    companion object {
        /** `tools/sharing40.py` `test_signal`: integer-only test audio, the same in every language. */
        fun testSignal(n: Int, @Suppress("UNUSED_PARAMETER") rate: Int, seed: Long): IntArray {
            val out = IntArray(n)
            var s = seed and 0xFFFFFFFFL
            var walk = 0L
            var a = 20000L
            var b = 0L
            var c = 30000L
            var d = 0L
            for (i in 0 until n) {
                s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
                val noise = (s shr 16) - 32768
                walk = maxOf(-30000L, minOf(30000L, walk + (noise shr 11)))
                a -= (b * 1029) shr 16
                b += (a * 1029) shr 16
                c -= (d * 6863) shr 16
                d += (c * 6863) shr 16
                val t = (i % 160).toLong()
                val tri = 150L * (if (t < 40) t else if (t < 120) 80 - t else t - 160)
                val v = when (((i / 4096) + seed).mod(16L).toInt()) {
                    0 -> 0L
                    1 -> -1234L
                    2 -> noise
                    3 -> noise shr 9
                    4 -> walk
                    5, 9 -> tri + (noise shr 13)
                    6, 10 -> a
                    7, 11 -> c
                    8 -> if ((i / 32) % 2 == 0) 8192L else -8192L
                    else -> noise shr 14
                }
                out[i] = v.coerceIn(-32768L, 32767L).toInt()
            }
            return out
        }

        /** `case_pcm`'s two formulas. */
        fun casePcm(name: String, n: Int): IntArray = when (name) {
            "r88200_wasted" -> IntArray(n) { i -> if (i < 6000) ((i * 12) % 4000 - 2000) * 4 else -4096 }
            "r24000_extremes" -> IntArray(n) { i ->
                when {
                    i < 2000 -> if ((i / 50) % 2 != 0) 32767 else -32768
                    i < 3000 -> if (i % 2 != 0) 32767 else -32768
                    else -> (i % 7) * 9000 - 27000
                }
            }
            else -> throw IllegalArgumentException(name)
        }
    }
}
