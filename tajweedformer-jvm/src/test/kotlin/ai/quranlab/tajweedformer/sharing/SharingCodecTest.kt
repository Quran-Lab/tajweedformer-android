package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.sharing.OggTestKit.decode
import ai.quranlab.tajweedformer.sharing.OggTestKit.feed
import ai.quranlab.tajweedformer.sharing.OggTestKit.noise
import ai.quranlab.tajweedformer.sharing.OggTestKit.pages
import ai.quranlab.tajweedformer.sharing.OggTestKit.temp
import ai.quranlab.tajweedformer.sharing.OggTestKit.tempDir
import ai.quranlab.tajweedformer.sharing.OggTestKit.tone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Swift SDK's SharingCodecTests and the Ogg tests of SharingTests, on the JVM with the host libopus build. */
class SharingCodecTest : OpenChecked() {
    @Before fun opus() = OggTestKit.requireOpus()

    /** The defaults are the measured choice, and libopus reads every one of them back. */
    @Test fun encoderSettingsAreApplied() {
        val s = OpusSettings.SHARED
        assertEquals(OpusSettings.Application.AUDIO, s.application)
        assertEquals(192_000, s.bitrate)
        assertEquals(10, s.complexity)
        assertTrue(s.vbr)
        assertFalse(s.constrainedVbr)
        assertEquals(OpusSettings.Bandwidth.FULLBAND, s.bandwidth)
        assertEquals(OpusSettings.Signal.AUTO, s.signal)
        assertEquals(20.0, s.frameMs, 0.0)
        assertEquals(50, s.pagePackets)
        assertEquals("libopus 1.6.1", OpusVersion.string)
        LibOpusEncoder(48_000, 1, s).use { e ->
            val packets = e.encode(noise(48_000))
            assertEquals(50, packets.size)
            assertEquals(2049, e.get(OpusNative.GET_APPLICATION))
            assertEquals(192_000, e.get(OpusNative.GET_BITRATE))
            assertEquals(10, e.get(OpusNative.GET_COMPLEXITY))
            assertEquals(1, e.get(OpusNative.GET_VBR))
            assertEquals(0, e.get(OpusNative.GET_VBR_CONSTRAINT))
            assertEquals(1105, e.get(OpusNative.GET_BANDWIDTH))
            assertEquals(-1000, e.get(OpusNative.GET_SIGNAL))
            assertEquals(48_000, e.get(OpusNative.GET_SAMPLE_RATE))
            assertEquals(24, e.get(OpusNative.GET_LSB_DEPTH))
            assertEquals(0, e.get(OpusNative.GET_DTX))
            assertEquals(0, e.get(OpusNative.GET_INBAND_FEC))
            assertEquals(312, e.get(OpusNative.GET_LOOKAHEAD))
            assertEquals("OpusHead pre-skip from the encoder", 312, e.preSkip)
            // every packet: CELT-only, fullband, 20 ms, one frame (TOC config 31, code 0), mono
            for (p in packets) assertEquals(31 shl 3, p[0].toInt() and 0xff)
            assertTrue("VBR spends about the target on noise", packets.sumOf { it.size } * 8 > 150_000)
        }
    }

    @Test fun oggOpusFollowsRfc7845() {
        for (rate in listOf(16_000, 44_100, 48_000)) {
            val f = temp()
            try {
                val w = OggOpusWriter(f, rate)
                val n = (rate * 3.37).toInt()
                feed(w, tone(n, rate.toDouble()), 1, 5_000)
                assertEquals(3370, w.finish())
                val ps = pages(f.readBytes())
                assertEquals(ps.indices.toList(), ps.map { it.sequence })
                assertEquals(0x02, ps[0].flags)
                val head = ps[0].packets[0]
                assertEquals("OpusHead", String(head, 0, 8))
                assertEquals("mono", 1, head[9].toInt())
                assertEquals(312, (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8))
                assertEquals("OpusHead: the true input rate", rate, java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
                assertEquals("OpusTags", String(ps[1].packets[0], 0, 8))
                assertEquals(0x04, ps.last().flags)
                var packets = 0
                var last = 0L
                for (p in ps.drop(2)) {
                    packets += p.packets.size
                    assertTrue(p.granule >= last)
                    last = p.granule
                    if (p.flags != 0x04) {
                        assertEquals(packets * 960L, p.granule)
                        assertEquals("a page is a second of packets", 50, p.packets.size)
                    }
                }
                assertEquals("decoded length", (3.37 * 48_000).toInt(), decode(f.readBytes()).size)
                assertEquals("the last granule trims to the input length", (3.37 * 48_000).toLong(), ps.last().granule - 312)
            } finally { f.delete() }
        }
    }

    /** Pages hold a second of packets, never more than 255 lacing values, and a segment is exactly its seconds of packets. */
    @Test fun pagePackingAndSegmentBoundaries() {
        for ((bitrate, frameMs) in listOf(192_000 to 20.0, 510_000 to 10.0)) {
            val settings = OpusSettings.SHARED.copy(bitrate = bitrate, frameMs = frameMs)
            val perSecond = (1000 / frameMs).toInt()
            val dir = tempDir()
            try {
                val closed = ArrayList<OggSegment>()
                val w = OggOpusWriter({ File(dir, "seg-%05d.opus".format(it)) }, listOf(3, 5), 48_000, settings) { closed.add(it) }
                feed(w, noise(48_000 * 14, gain = 0.9f), 480, 9)
                w.finish()
                assertEquals(listOf(1, 2, 3, 4), closed.map { it.n })
                val ps = closed.map { pages(it.file.readBytes()) }
                val audio = ps.mapIndexed { i, p -> if (i == 0) p.drop(2) else p }
                val total = (14 * 48_000 + 312 + settings.packetFrames - 1) / settings.packetFrames
                assertEquals(listOf(3, 5, 5).map { it * perSecond } + (total - 13 * perSecond), audio.map { s -> s.sumOf { it.packets.size } })
                var lacingMax = 0
                for (seg in closed) {
                    val b = seg.file.readBytes()
                    var o = 0
                    while (o < b.size) {
                        val n = b[o + 26].toInt() and 0xff
                        lacingMax = maxOf(lacingMax, n)
                        var len = 0
                        for (i in 0 until n) len += b[o + 27 + i].toInt() and 0xff
                        o += 27 + n + len
                    }
                }
                assertTrue(lacingMax <= 255)
                val perPage = audio.flatMap { s -> s.dropLast(1).map { it.packets.size } }
                if (frameMs == 20.0) {
                    assertEquals("one-second pages", setOf(50), perPage.toSet())
                } else {
                    assertTrue("$perPage", perPage.all { it in 60..100 })
                    assertTrue("the lacing limit cut pages short", perPage.any { it < 100 })
                }
                val whole = closed.map { it.file.readBytes() }.reduce { a, b -> a + b }
                assertEquals(48_000 * 14, decode(whole).size)
            } finally { dir.deleteRecursively() }
        }
    }

    /** Other rates are resampled to 48 kHz first: the decoded stream is the input's length at 48 kHz and in time with it. */
    @Test fun otherRatesAreResampledTo48kHz() {
        for (rate in listOf(44_100, 16_000, 22_050)) {
            val f = temp()
            try {
                val n = rate * 4
                val w = OggOpusWriter(f, rate)
                var at = 0
                val rnd = java.util.Random(3)
                while (at < n) {
                    val k = minOf(rate / 100 * (1 + rnd.nextInt(4)), n - at)
                    w.append(tone(k, rate.toDouble(), from = at))
                    at += k
                }
                assertEquals(4_000, w.finish())
                val y = decode(f.readBytes())
                assertEquals("$rate", 4 * 48_000, y.size)
                val ref = tone(y.size, 48_000.0)
                var sig = 0.0
                var err = 0.0
                for (i in 4_800 until y.size - 4_800) {
                    sig += ref[i].toDouble() * ref[i]
                    err += (y[i] - ref[i]).toDouble() * (y[i] - ref[i])
                }
                val snr = 10 * Math.log10(sig / err)
                println("resampled %d Hz: SNR %.1f dB".format(rate, snr))
                assertTrue("$rate Hz: $snr dB", snr > 30)
            } finally { f.delete() }
        }
    }

    /** The resampler alone: a tone in band comes out as the same tone at 48 kHz, well above the codec's own error. */
    @Test fun resamplerAccuracy() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 96_000)) {
            val r = ShareResampler(rate)
            val x = tone(rate * 2, rate.toDouble(), hz = 1000.0)
            val y = ArrayList<Float>()
            var at = 0
            while (at < x.size) { val k = minOf(777, x.size - at); r.process(x, at, k) { b, o, n -> for (i in o until o + n) y.add(b[i]) }; at += k }
            r.flush { b, o, n -> for (i in o until o + n) y.add(b[i]) }
            assertEquals("$rate", 96_000, y.size)
            val ref = tone(y.size, 48_000.0, hz = 1000.0)
            var sig = 0.0
            var err = 0.0
            for (i in 4_800 until y.size - 4_800) { sig += ref[i].toDouble() * ref[i]; err += (y[i] - ref[i]).toDouble() * (y[i] - ref[i]) }
            val snr = 10 * Math.log10(sig / err)
            println("resampler %d Hz: SNR %.1f dB".format(rate, snr))
            assertTrue("$rate Hz: $snr dB", snr > 70)
        }
    }

    /** NaN and infinity are coded as silence: byte for byte the stream with zeros in their place. */
    @Test fun nonFiniteInputIsSilence() {
        for (rate in listOf(48_000, 44_100)) {
            val bad = noise(rate * 3)
            val good = bad.copyOf()
            var i = 17
            while (i < bad.size) {
                bad[i] = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)[i % 3]
                good[i] = 0f
                i += 997
            }
            val a = temp()
            val b = temp()
            try {
                val wa = OggOpusWriter(a, rate, serial = 7)
                val wb = OggOpusWriter(b, rate, serial = 7)
                var c = 0
                while (c < bad.size) {
                    val k = minOf(4_410, bad.size - c)
                    wa.append(bad, c, k)
                    wb.append(good, c, k)
                    c += k
                }
                wa.finish()
                wb.finish()
                assertEquals(((bad.size - 17 + 996) / 997).toLong(), wa.nonFiniteSamples)
                assertArrayEquals(a.readBytes(), b.readBytes())
                assertTrue(decode(a.readBytes()).all { it.isFinite() })
            } finally { a.delete(); b.delete() }
        }
    }

    /** A crash at any moment loses at most about a second, and recovery keeps exactly the whole pages of a torn file. */
    @Test fun crashLosesAtMostASecondAndRecoveryCutsAtTheLastWholePage() {
        val f = temp()
        try {
            val rate = 44_100
            val w = OggOpusWriter(f, rate)
            val x = noise(rate * 9)
            var at = 0
            val rnd = java.util.Random(5)
            while (at < x.size) {
                val k = minOf(441 * (1 + rnd.nextInt(20)), x.size - at)
                w.append(x, at, k)
                at += k
                val input48 = at.toDouble() * 48_000 / rate
                assertTrue("at $at", input48 - maxOf(0L, w.lastGranule) <= 1.1 * 48_000)
            }
            val d = f.readBytes()
            val ps = pages(d)
            val ends = ArrayList<Int>()
            var o = 0
            while (o < d.size) {
                val n = d[o + 26].toInt() and 0xff
                var len = 0
                for (i in 0 until n) len += d[o + 27 + i].toInt() and 0xff
                o += 27 + n + len
                ends.add(o)
            }
            for (cut in (1 until d.size step 997) + ends.map { it - 1 }) {
                val (keep, granule) = ShareFiles.wholePages(d.copyOf(cut))
                val whole = ends.filter { it <= cut }
                assertEquals("cut at $cut", whole.lastOrNull() ?: 0, keep)
                assertEquals(if (whole.isEmpty()) 0L else ps[whole.size - 1].granule, granule)
            }
            w.abandon()
        } finally { f.delete() }
    }

    /** Segments of 10, 20, 40, then 60 s: concatenated they are byte for byte the one-file encode, and a valid stream. */
    @Test fun segmentsConcatenateToTheOneFileByteForByte() {
        val rate = 16_000
        val input = FloatArray((rate * 157.3).toInt()) { i ->
            val t = (Math.sin(i * 0.013) * 0.4).toFloat()
            val n = ((i * 7919) % 97) / 970f
            t + n - 0.05f
        }
        val one = temp()
        val dir = tempDir()
        try {
            val a = OggOpusWriter(one, rate, serial = 42)
            val closed = ArrayList<OggSegment>()
            val b = OggOpusWriter({ File(dir, "seg-%05d.opus".format(it)) }, SegmentPlan.SECONDS, rate, serial = 42) { closed.add(it) }
            var at = 0
            val rnd = java.util.Random(11)
            while (at < input.size) {
                val k = minOf(100 + rnd.nextInt(3_901), input.size - at)
                a.append(input, at, k)
                b.append(input, at, k)
                at += k
            }
            assertEquals(a.finish(), b.finish())
            assertEquals(listOf(1, 2, 3, 4, 5), closed.map { it.n })
            assertEquals(listOf(false, false, false, false, true), closed.map { it.last })
            val parts = closed.map { it.file.readBytes() }
            assertArrayEquals("the segments are the one file, byte for byte", one.readBytes(), parts.reduce { x, y -> x + y })
            val ps = parts.map { pages(it) }
            val audio = ps.map { p -> p.filter { pg -> pg.packets.firstOrNull()?.let { String(it, 0, minOf(4, it.size)) } != "Opus" } }
            assertEquals(listOf(500, 1_000, 2_000, 3_000, 1_366), audio.map { s -> s.sumOf { it.packets.size } })
            assertEquals("one-second pages", listOf(10, 20, 40, 60, 28), audio.map { it.size })
            assertEquals("OpusHead", String(ps[0][0].packets[0], 0, 8))
            for (p in ps.drop(1)) assertFalse(String(p[0].packets[0], 0, 4) == "Opus")
            assertEquals(0x04, ps.last().last().flags)
            assertEquals(312 + (157.3 * 48_000).toLong(), closed.last().granule)
        } finally { one.delete(); dir.deleteRecursively() }
    }

    /**
     * SDK 2.0.1: the bitrate changes between segments and the stream stays one valid Ogg Opus file. Real
     * libopus on the self-test clip repeated to 155 s (5 segments) at 16 kHz, the ladder driven so that the
     * segments are coded at 192, 96, 32, 64 and 192 kbit/s: one serial, pages in sequence from 0, BOS only on
     * page 0, OpusHead only on page 0 and OpusTags only on page 1, every CRC, granules 960 x packets, EOS only
     * on the last page whose granule is the encoder's final position, the decode exactly the input's length,
     * each segment's bitrate as recorded, and its bytes per second following the rung. Where ffmpeg is on
     * PATH: ffprobe's duration within 20 ms, ffmpeg's native and libopus decoders exactly the input's length.
     */
    @Test fun bitrateStepsBetweenSegmentsInOneValidStream() {
        val raw = SharingCodecTest::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/selftest_16k.f32")!!.use { it.readBytes() }
        val fb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val clip = FloatArray(fb.remaining()).also { fb.get(it) }
        val rate = 16_000
        val seconds = 155
        val input = FloatArray(rate * seconds) { clip[it % clip.size] }
        val ladder = BitrateLadder()
        val asked = ArrayList<Int>()
        val dir = tempDir()
        try {
            val closed = ArrayList<OggSegment>()
            val w = OggOpusWriter({ File(dir, ShareFiles.name(it) + ".opus") }, SegmentPlan.SECONDS, rate, serial = 0x5eed,
                bitrateFor = { n ->
                    // what the outbox would have reported before segment n opens
                    when (n) {
                        2 -> ladder.uploadFailed()
                        3 -> repeat(2) { ladder.uploadFailed() }
                        4 -> repeat(3) { ladder.uploadOk(0.0) }
                        5 -> repeat(6) { ladder.uploadOk(0.0) }
                    }
                    asked.add(n)
                    ladder.bitrate
                }) { closed.add(it) }
            feed(w, input, 160, 40)
            w.finish()
            val want = listOf(192_000, 96_000, 32_000, 64_000, 192_000)
            assertEquals("asked once per segment, when it opens", (1..5).toList(), asked)
            assertEquals(want, closed.map { it.bitrate })
            assertEquals(want, w.segmentBitrates)
            assertEquals(listOf(false, false, false, false, true), closed.map { it.last })

            val parts = closed.map { it.file.readBytes() }
            val whole = parts.reduce { a, b -> a + b }
            val ps = pages(whole) // every CRC is checked here
            assertEquals("page sequence from 0", ps.indices.toList(), ps.map { it.sequence })
            assertEquals("one serial", setOf(0x5eed), ps.map { it.serial }.toSet())
            assertEquals("BOS only on page 0", listOf(0), ps.indices.filter { ps[it].flags and 0x02 != 0 })
            assertEquals("EOS only on the last page", listOf(ps.size - 1), ps.indices.filter { ps[it].flags and 0x04 != 0 })
            fun starts(p: OggTestKit.Page, tag: String) = p.packets.any { it.size >= 8 && String(it, 0, 8, Charsets.US_ASCII) == tag }
            assertEquals("OpusHead only on page 0", listOf(0), ps.indices.filter { starts(ps[it], "OpusHead") })
            assertEquals("OpusTags only on page 1", listOf(1), ps.indices.filter { starts(ps[it], "OpusTags") })
            var packets = 0L
            for (p in ps.drop(2)) {
                packets += p.packets.size
                if (p.flags and 0x04 == 0) assertEquals("granule = 960 x packets", packets * 960, p.granule)
            }
            val input48 = seconds.toLong() * 48_000
            assertEquals("the last granule is the encoder's final position", w.preSkip + input48, ps.last().granule)
            val decoded = decode(whole)
            assertEquals("libopus decodes exactly the input", input48, decoded.size.toLong())

            // bytes per second of each segment's audio follow the rungs
            val kbps = closed.indices.map { i ->
                val audio = pages(parts[i]).filter { !starts(it, "OpusHead") && !starts(it, "OpusTags") }
                val n = audio.sumOf { it.packets.size }
                val bytes = audio.sumOf { pg -> pg.packets.sumOf { it.size } }
                Triple(n, parts[i].size, bytes * 8.0 / (n * 0.020) / 1000)
            }
            for ((i, k) in kbps.withIndex()) {
                println("bitrate stream: segment %d at %d kbit/s: %d packets (%.2f s), %d bytes, %.1f kbit/s measured".format(java.util.Locale.ROOT,
                    i + 1, want[i] / 1000, k.first, k.first * 0.020, k.second, k.third))
            }
            val m = kbps.map { it.third }
            assertTrue("32k < 64k < 96k < 192k: $m", m[2] < m[3] && m[3] < m[1] && m[1] < m[0] && m[1] < m[4])
            assertTrue("a 32k segment is clearly smaller than a 192k one: $m", m[2] * 3 < m[0] && m[2] * 3 < m[4])
            // unconstrained VBR spends somewhat above the target on recitation, more so at the low rungs
            for (i in m.indices) assertTrue("segment ${i + 1}: ${m[i]} kbit/s for ${want[i] / 1000}", m[i] > want[i] / 1000.0 * 0.6 && m[i] < want[i] / 1000.0 * 1.6)
            println("bitrate stream: %d pages, %d bytes, %d packets, %d samples decoded (input %d at 48 kHz), last granule %d, pre-skip %d".format(java.util.Locale.ROOT,
                ps.size, whole.size, packets, decoded.size, input48, ps.last().granule, w.preSkip))

            val f = File(dir, "whole.opus").also { it.writeBytes(whole) }
            val probe = OggTestKit.run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path)
            if (probe == null) {
                println("bitrate stream: ffprobe/ffmpeg not on PATH, their checks skipped")
            } else {
                val duration = String(probe.second, Charsets.US_ASCII).trim().toDouble()
                println("bitrate stream: ffprobe duration %.6f s (input %d s)".format(java.util.Locale.ROOT, duration, seconds))
                assertEquals("ffprobe exit", 0, probe.first)
                assertEquals("ffprobe duration within 20 ms", seconds.toDouble(), duration, 0.020)
                for (dec in listOf("opus", "libopus")) {
                    val r = OggTestKit.run("ffmpeg", "-v", "error", "-c:a", dec, "-i", f.path, "-f", "f32le", "-ac", "1", "-")!!
                    assertEquals("ffmpeg $dec exit", 0, r.first)
                    println("bitrate stream: ffmpeg %s decoder: %d samples".format(dec, r.second.size / 4))
                    assertEquals("ffmpeg $dec decodes exactly the input", input48, r.second.size / 4L)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    /** Memory stays flat over a long session: 10 minutes (60 with TJ_LONG=1) at 44.1 kHz. */
    @Test fun memoryIsFlatOverALongSession() {
        val minutes = if (System.getenv("TJ_LONG") == "1") 60 else 10
        val f = temp()
        try {
            val w = OggOpusWriter(f, 44_100)
            val chunk = noise(441)
            val rt = Runtime.getRuntime()
            val marks = ArrayList<Long>()
            val t0 = System.nanoTime()
            for (m in 0 until minutes) {
                repeat(60 * 100) { w.append(chunk) }
                if (m == 1 || m == minutes - 1) { System.gc(); Thread.sleep(50); marks.add(rt.totalMemory() - rt.freeMemory()) }
            }
            w.finish()
            val cpu = (System.nanoTime() - t0) / 1e9
            println("sharing memory: %d min, heap %.1f MB after minute 2, growth %.2f MB, %.1f s to encode (%.1f%% of real time)".format(
                minutes, marks[0] / 1e6, (marks[1] - marks[0]) / 1e6, cpu, cpu / (minutes * 60) * 100))
            assertTrue(marks[1] - marks[0] < 4_000_000)
            assertEquals(minutes * 60 * 48_000, decode(f.readBytes()).size)
        } finally { f.delete() }
    }
}
