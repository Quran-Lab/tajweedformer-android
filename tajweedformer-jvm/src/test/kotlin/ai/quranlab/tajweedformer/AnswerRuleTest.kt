package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.DownloadRuleServer.Do
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Which answers a model download accepts (SDK 3.1.3, the rule of every Quran Lab SDK), through the SDK's real HTTP
 * code (`HttpURLConnection`) against a local server that gives a 2xx which is not the answer asked for:
 *
 * - a 2xx is the file only as a 200 with the manifest's length (a Content-Length, when given, equal to the file's size;
 *   a body that runs past that size is cut off and the attempt failed), or as a 206 whose `Content-Range` starts where
 *   the request did, ends where a bounded request did, and names the manifest's size as the total. A 204, a 200 of
 *   another length, an empty 206, a 206 with another start or total, and a 200 with an error body are failed attempts;
 * - a failed attempt never shrinks what the device holds: the part of a file and the complete ranges that were there
 *   before the attempt are there after it, byte for byte;
 * - a failed attempt counts against the file's five resumes like a connection that ended, also when it brought no
 *   byte, so nothing loops: each call here makes six requests (the pauses are 10 ms in this class) and ends with the
 *   error.
 *
 * What removes stored bytes, and that nothing else does (SPEC 16.1), is the second half of the class. A one-stream part
 * goes on a 416 (its bytes run past the server's file), when it is longer than the file at the start of an attempt, and
 * (DownloaderTest) when it has the full size and a wrong hash. Stored ranges go only as a complete set that fails its
 * hash: never on a 416, and not for a missing validator.
 *
 * Each case runs for a file in one stream (3 MiB, with 1 MiB of it on the device from an earlier call) and for a file in
 * ranges (20 MiB, with its first two 8 MiB ranges complete on the device). After the failed call the server answers
 * as it should, and the next call finishes the file from exactly what was kept.
 *
 * Before the rule this class ran, without its short pauses and with "at most six requests", on the code as it was
 * (the ranges before this rule, and the released 3.1.2): there a 200 that was short or carried an error body replaced the stored
 * bytes, a 200 that was too long deleted them, a 206 with a wrong total was taken in one stream and wiped the complete
 * ranges of a file in ranges, and so did a 206 with a wrong start; nothing looped.
 */
class AnswerRuleTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private lateinit var server: DownloadRuleServer
    private lateinit var served: File

    private fun dir() = Files.createTempDirectory("tj-answer-").toFile().also { dirs.add(it) }

    @Before
    fun start() {
        served = dir()
        server = DownloadRuleServer(served)
    }

    @After
    fun stop() {
        server.close()
        SdkEvents.clear()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun publish(files: List<TestKit.F>) = TestKit.writeTransport(served, files, TestKit.sign(TestKit.manifest(files)))

    /** The retry rule with its five resumes, after pauses of 10 ms instead of 1 to 16 s. */
    private fun store(root: File) = ModelStore(root, server.base, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier,
        retry = RetryPlan(longArrayOf(10, 10, 10, 10, 10)))

    /** The answers that are not the file, by name. */
    private val wrong: List<Pair<String, (Long) -> Do>> = listOf(
        "a 204" to { _ -> Do.Status(204) },
        "a 200 shorter than the file" to { size -> Do.ShortOk(size / 2) },
        "a 200 longer than the file" to { _ -> Do.LongOk(1000) },
        "an empty 206" to { _ -> Do.Empty206 },
        "a 206 with a wrong total" to { _ -> Do.WrongTotal206(4096) },
        "a 206 with a wrong start" to { _ -> Do.WrongStart206(1) },
        "a 200 with a JSON error body" to { _ -> Do.Status(200, """{"error":"unavailable","message":"try again later"}""") },
    )

    /** Every case runs, also after one failed: the test then fails with what each of them did. */
    private fun all(cases: List<Pair<String, () -> Unit>>) {
        val failed = ArrayList<String>()
        for ((name, run) in cases) {
            try {
                run()
            } catch (e: Throwable) {
                failed.add((e.message ?: "$name: ${e.javaClass.simpleName}").lineSequence().first().take(300))
            }
        }
        assertTrue("${failed.size} of ${cases.size} cases: " + failed.joinToString(" || "), failed.isEmpty())
    }

    /**
     * One case: [prepare] puts an earlier call's bytes on the device and returns the files that must still be there,
     * unchanged, after the failed call; the server answers every request for the large file with [answer]; then it
     * answers as it should and [finishes] checks the requests of the call that finishes the file.
     */
    private fun case(name: String, files: List<TestKit.F>, answer: Do, prepare: (File, ByteArray) -> List<File>, finishes: (List<DownloadRuleServer.Req>, DownloadResult) -> Unit) {
        val root = dir()
        val s = store(root)
        val entry = s.manifest(true).files.first { it.path == BIG }
        val all = files.first { it.storedPath == BIG }.stored
        val kept = prepare(root, all)
        val before = kept.map { it.readBytes() }
        server.plan = { p, _, _ -> if (p == BIG) answer else Do.Serve }
        server.requests.clear()
        val t = server.now()
        val error = try {
            s.downloader.fetch(entry)
            null
        } catch (e: TajweedError) {
            e
        }
        val asked = server.of(BIG)
        assertTrue("$name: the call succeeded", error != null)
        assertEquals("$name: the call's error", "download_failed", error!!.code)
        assertEquals("$name: the first request and five more, each a failed attempt that counts: $asked", 6, asked.size)
        assertTrue("$name: the call took ${server.now() - t} ms", server.now() - t < 60_000)
        for ((i, f) in kept.withIndex()) {
            assertTrue("$name: ${f.name} is gone", f.isFile)
            val now = f.readBytes()
            assertTrue("$name: ${f.name} shrank from ${before[i].size} to ${now.size} bytes", now.size >= before[i].size)
            assertArrayEquals("$name: the bytes ${f.name} held were changed", before[i], now.copyOf(before[i].size))
        }
        // the server answers as it should: the next call finishes the file from what was kept
        server.plan = { _, _, _ -> Do.Serve }
        server.requests.clear()
        val r = s.downloader.fetch(entry)
        assertTrue("$name: the next call", r.ok)
        finishes(server.of(BIG), r)
        assertArrayEquals("$name: the file on the device is not the model's", files.first { it.storedPath == BIG }.bytes, File(root, "layers.zfw").readBytes())
    }

    @Test
    fun aWrongAnswerToAResumeInOneStreamLosesNothing() {
        publish(small)
        val have = 1 shl 20
        all(wrong.map { (name, answer) -> name to {
            case("one stream, $name", small, answer(small.first { it.storedPath == BIG }.stored.size.toLong()), { root, all ->
                // what an earlier call left: the file's first MiB and the validator of the answer it came with
                val part = File(root, "$BIG.part").apply { writeBytes(all.copyOf(have)) }
                val v = File(root, "$BIG.part.v").apply { writeText("\"v1\"") }
                listOf(part, v)
            }) { asked, r ->
                assertEquals("one stream, $name: the next call goes on from the byte that was kept", listOf<String?>("bytes=$have-"), asked.map { it.range })
                assertEquals(have.toLong(), r.resumedFrom)
            }
        } })
    }

    @Test
    fun aWrongAnswerToARangeLosesNoCompleteRange() {
        publish(large)
        val all = large.first { it.storedPath == BIG }.stored
        val size = all.size.toLong()
        all(wrong.map { (name, answer) -> name to {
            case("ranges, $name", large, answer(size), { root, bytes ->
                // what an earlier call left: the first two ranges complete, their map, and the validator
                val part = File(root, "$BIG.part").apply { writeBytes(bytes.copyOf((2 * R).toInt())) }
                val v = File(root, "$BIG.part.v").apply { writeText("\"v1\"") }
                val map = File(root, "$BIG.part.ranges").apply { writeText("""{"v":1,"size":$size,"range":$R,"done":[0,1]}""") }
                listOf(part, v, map)
            }) { asked, r ->
                assertEquals("ranges, $name: the next call asks for the one range that was missing", listOf<String?>("bytes=${2 * R}-${size - 1}"), asked.map { it.range })
                assertEquals(2 * R, r.resumedFrom)
            }
        } })
    }

    // ---- what removes stored bytes (SPEC 16.1) ----

    private fun entry(s: ModelStore) = s.manifest(true).files.first { it.path == BIG }

    private fun bytesOf(files: List<TestKit.F>) = files.first { it.storedPath == BIG }.stored

    private fun fails(s: ModelStore, e: ModelManifest.FileEntry): TajweedError? = try {
        s.downloader.fetch(e)
        null
    } catch (x: TajweedError) {
        x
    }

    private fun right(files: List<TestKit.F>, root: File) =
        assertArrayEquals("the file on the device is not the model's", files.first { it.storedPath == BIG }.bytes, File(root, "layers.zfw").readBytes())

    @Test
    fun a416ToAOneStreamResumeRemovesThePartAndEndsTheCall() {
        publish(small)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        val part = File(root, "$BIG.part").apply { writeBytes(bytesOf(small).copyOf(1 shl 20)) }
        val v = File(root, "$BIG.part.v").apply { writeText("\"v1\"") }
        server.plan = { p, _, _ -> if (p == BIG) Do.Status(416) else Do.Serve }
        server.requests.clear()
        assertEquals("download_failed", fails(s, e)?.code)
        assertEquals("a 416 is asked once: ${server.of(BIG)}", 1, server.of(BIG).size)
        assertFalse("the part whose bytes run past the server's file is still there", part.exists())
        assertFalse("its validator is still there", v.exists())
        // the next call starts the file again
        server.plan = { _, _, _ -> Do.Serve }
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        assertEquals(listOf<String?>(null), server.of(BIG).map { it.range })
        assertEquals(0L, r.resumedFrom)
        right(small, root)
    }

    @Test
    fun aPartLongerThanTheFileIsRemovedBeforeTheRequest() {
        publish(small)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        // it cannot be a prefix of the file
        File(root, "$BIG.part").writeBytes(ByteArray(e.size.toInt() + 100) { 0x33 })
        File(root, "$BIG.part.v").writeText("\"v1\"")
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        assertEquals("the whole file, asked for without a Range: ${server.of(BIG)}", listOf<String?>(null), server.of(BIG).map { it.range })
        assertEquals(0L, r.resumedFrom)
        right(small, root)
    }

    @Test
    fun aPartWithoutAValidatorIsResumedWithTheRangeAlone() {
        publish(small)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        val have = 1 shl 20
        // the answer that started this part gave no strong ETag: nothing to send as If-Range
        File(root, "$BIG.part").writeBytes(bytesOf(small).copyOf(have))
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        val q = server.of(BIG)
        assertEquals("one request, from the byte the part ends at: $q", listOf<String?>("bytes=$have-"), q.map { it.range })
        assertEquals("no validator was sent, for there is none", null, q[0].ifRange)
        assertEquals(206, q[0].status)
        assertEquals(have.toLong(), r.resumedFrom)
        right(small, root)
    }

    /**
     * The same for the file that comes in ranges (the weights), across two calls: a server that gives no ETag is used
     * in one stream; the stream is cut in the first call, and the next call must go on from that prefix with the Range
     * alone. Before the fix the next call asked for the whole file again, from byte 0, and so did every call after it.
     */
    @Test
    fun aOneStreamPrefixOfTheLargeFileWithoutAValidatorIsResumedByTheNextCall() {
        publish(large)
        server.etag = ""
        val root = dir()
        val size = bytesOf(large).size.toLong()
        // call one, without resumes: the first range's answer has no ETag, so the file is asked for whole, and that
        // stream ends after 9 MiB and some bytes
        val one = ModelStore(root, server.base, UrlConnectionTransport(), { mapOf("x-qlab-key" to "k1") }, TestKit.verifier, retry = RetryPlan.NONE)
        server.plan = { p, range, _ -> if (p == BIG && range == null) Do.Cut((9L shl 20) + 123) else Do.Serve }
        assertEquals("download_failed", fails(one, entry(one))?.code)
        val part = File(root, "$BIG.part")
        val have = part.length()
        assertTrue("the first call left a prefix of the file: $have of $size", have in 1 until size)
        assertFalse("a validator was stored though the server gave none", File(root, "$BIG.part.v").exists())
        assertFalse("a map was stored for a file that came in one stream", File(root, "$BIG.part.ranges").exists())
        assertArrayEquals("the part is not a prefix of the file", bytesOf(large).copyOf(have.toInt()), part.readBytes())
        // call two: the rest, from the byte the prefix ends at
        server.plan = { _, _, _ -> Do.Serve }
        server.requests.clear()
        val s = store(root)
        val r = s.downloader.fetch(entry(s))
        assertTrue(r.ok)
        val q = server.of(BIG)
        assertEquals("one request, from the byte the prefix ends at: $q", listOf<String?>("bytes=$have-"), q.map { it.range })
        assertEquals("no validator was sent, for there is none", null, q[0].ifRange)
        assertEquals(206, q[0].status)
        assertEquals(have, r.resumedFrom)
        assertEquals("bytes fetched by the second call", size - have, r.networkBytes)
        right(large, root)
    }

    /** Two complete 8 MiB ranges of the large file, their map and (with [validator]) the validator, as an earlier call left them. */
    private fun ranges(root: File, bytes: ByteArray, validator: Boolean = true): List<File> {
        val part = File(root, "$BIG.part").apply { writeBytes(bytes.copyOf((2 * R).toInt())) }
        val map = File(root, "$BIG.part.ranges").apply { writeText("""{"v":1,"size":${bytes.size},"range":$R,"done":[0,1]}""") }
        return if (validator) listOf(part, map, File(root, "$BIG.part.v").apply { writeText("\"v1\"") }) else listOf(part, map)
    }

    @Test
    fun a416ToARangeEndsTheCallAndRemovesNothing() {
        publish(large)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        val kept = ranges(root, bytesOf(large))
        val before = kept.map { it.readBytes() }
        server.plan = { p, _, _ -> if (p == BIG) Do.Status(416) else Do.Serve }
        server.requests.clear()
        assertEquals("download_failed", fails(s, e)?.code)
        assertEquals("a 416 is asked once: ${server.of(BIG)}", 1, server.of(BIG).size)
        for ((i, f) in kept.withIndex()) assertArrayEquals("${f.name} was changed or removed by a 416", before[i], f.takeIf { it.isFile }?.readBytes())
        // each stored range was held to the manifest's total when it arrived: the next call needs the missing one only
        server.plan = { _, _, _ -> Do.Serve }
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        assertEquals(listOf<String?>("bytes=${2 * R}-${e.size - 1}"), server.of(BIG).map { it.range })
        right(large, root)
    }

    @Test
    fun aCompleteSetOfRangesThatFailsItsHashIsRemoved() {
        publish(large)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        // two "complete" ranges that are not the file's bytes
        ranges(root, ByteArray(bytesOf(large).size) { 0x44 })
        server.requests.clear()
        assertEquals("a file whose stored ranges are not the file's passed", "download_failed", fails(s, e)?.code)
        assertEquals("only the missing range was asked for: ${server.of(BIG)}", 1, server.of(BIG).size)
        assertEquals("the set that failed its hash is still on the device", emptyList<String>(),
            root.walk().filter { it.isFile && it.name.contains(".part") }.map { it.name }.toList())
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        assertEquals("every range again", 3, server.of(BIG).size)
        assertEquals(0L, r.resumedFrom)
        right(large, root)
    }

    @Test
    fun storedRangesWithoutTheirValidatorAreReplacedOnlyByAWholeFile() {
        publish(large)
        val root = dir()
        val s = store(root)
        val e = entry(s)
        val kept = ranges(root, bytesOf(large), validator = false)
        val before = kept.map { it.readBytes() }
        // no range can be asked for under a validator that is not there: the whole file is asked for, and while that
        // fails, the ranges stay
        server.plan = { p, _, _ -> if (p == BIG) Do.Status(204) else Do.Serve }
        server.requests.clear()
        assertEquals("download_failed", fails(s, e)?.code)
        assertEquals("the first request and five more, each for the whole file: ${server.of(BIG)}", List<String?>(6) { null }, server.of(BIG).map { it.range })
        for ((i, f) in kept.withIndex()) assertArrayEquals("${f.name} was changed or removed", before[i], f.takeIf { it.isFile }?.readBytes())
        server.plan = { _, _, _ -> Do.Serve }
        server.requests.clear()
        val r = s.downloader.fetch(e)
        assertTrue(r.ok)
        assertEquals("the whole file in one answer", listOf(200), server.of(BIG).map { it.status })
        assertEquals("nothing is left beside the file", emptyList<String>(), root.walk().filter { it.isFile && it.name.contains(".part") }.map { it.name }.toList())
        right(large, root)
    }

    companion object {
        const val BIG = "layers.zfw.gz"
        const val R = 8L shl 20

        private fun withWeights(bytes: Int, seed: Long): List<TestKit.F> = TestKit.v4Files(contexts = listOf("c16_l64")).map { f ->
            if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(bytes).also { java.util.Random(seed).nextBytes(it) }, "weights") else f
        }

        /** The test kit's model with a 3 MiB weights file (random, so its gzip is as large): one request. */
        private val small: List<TestKit.F> by lazy { withWeights(3 shl 20, 21) }

        /** The same with 20 MiB: three ranges of 8 MiB. */
        private val large: List<TestKit.F> by lazy { withWeights(20 shl 20, 22) }
    }
}
