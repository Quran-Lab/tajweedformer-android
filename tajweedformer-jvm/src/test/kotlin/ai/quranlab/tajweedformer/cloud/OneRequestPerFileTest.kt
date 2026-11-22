package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelProfile
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.RangePlan
import ai.quranlab.tajweedformer.RetryPlan
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.UrlConnectionTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract SPEC 16.7 (SDK 4.0.2): one request per model file. A store made as the Client makes it (its own plan for
 * large files) fetches the CPU set of the default context from a stand-in model host whose weights are 38 MiB; every
 * request of the host is counted with its `Range` and `If-Range`. Then the stream is cut once in the weights: one
 * resume, from the cut, with `If-Range`. Then what 4.0.1 left on the device (a part written in 8 MiB ranges with its
 * map) is completed, never thrown away.
 *
 * The same class was run on the 4.0.1 code (the sources of 4f30872, which are 4.0.1's): see `before` in each test.
 */
class OneRequestPerFileTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val served = ArrayList<ServedModel>()

    @After
    fun down() {
        served.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-onereq-").toFile().also { dirs += it }

    private val files: List<TestKit.F> by lazy {
        TestKit.v4Files().map { f ->
            if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(38 shl 20).also { java.util.Random(17).nextBytes(it) }, "weights") else f
        }
    }

    private fun host(): ServedModel = ServedModel.of(files).also { served += it }

    /** A store as the Client makes one: the plan for large files is the store's own (the Client passes the same). */
    private fun store(s: ServedModel, root: File, ranges: RangePlan? = null): ModelStore {
        val base = "${s.url}/${ServedModel.REPO}/${s.build}/${ServedModel.FOLDER}/"
        return if (ranges == null) ModelStore(root, base, UrlConnectionTransport(), null, TestKit.verifier, expectBuild = s.build, retry = RetryPlan(longArrayOf(50, 50, 50)))
        else ModelStore(root, base, UrlConnectionTransport(), null, TestKit.verifier, expectBuild = s.build, ranges = ranges, retry = RetryPlan.NONE)
    }

    private fun cpu(st: ModelStore): List<ModelManifest.FileEntry> {
        val m = st.manifest(true)
        return ModelProfile.cpu(m, m.defaultContext)
    }

    private fun fileRequests(s: ServedModel) = synchronized(s.requests) { s.requests.filter { it.path != ModelManifest.FILE_NAME }.toList() }

    @Test
    fun aFullInstallAsksOncePerFile() {
        val s = host()
        val st = store(s, temp())
        val want = cpu(st)
        st.fetch(want)
        val asked = fileRequests(s)
        println("SPEC 16.7 count: ${want.size} files, ${asked.size} model-file requests, ranges ${asked.map { it.range }}")
        // 4.0.1: the 38 MiB weights in five 8 MiB ranges four at a time, every other file in one request
        assertEquals("one request per file: $asked", want.size, asked.size)
        assertTrue("a Range on a fresh download: $asked", asked.all { it.range == null })
        for (e in want) assertTrue("${e.path} in place", st.downloader.isInPlace(e))
    }

    @Test
    fun oneCutInTheLargestFileIsOneResumeWithIfRange() {
        val s = host()
        val big = files.first { it.path == "layers.zfw" }
        val stored = big.path + ".gz"
        val n = AtomicInteger()
        s.cut = { p -> if (p == stored && n.incrementAndGet() == 1) (5L shl 20) + 123 else null }
        val st = store(s, temp())
        st.fetch(cpu(st))
        val weights = fileRequests(s).filter { it.path == stored }
        println("SPEC 16.7 cut: ${weights.map { "${it.range} ${it.ifRange} ${it.status}" }}")
        assertEquals("the weights: one request and one resume: $weights", 2, weights.size)
        assertEquals(null, weights[0].range)
        assertEquals("the resume from the cut", "bytes=${(5L shl 20) + 123}-", weights[1].range)
        assertTrue("the resume carries If-Range", weights[1].ifRange != null && weights[1].status == 206)
    }

    @Test
    fun whatFourZeroOneLeftInRangesIsCompleted() {
        val s = host()
        val root = temp()
        val big = files.first { it.path == "layers.zfw" }
        val stored = big.path + ".gz"
        // 4.0.1's plan (8 MiB ranges), cut in its third range: two ranges complete, with their map
        val n = AtomicInteger()
        s.rangeCut = { p, r, _ -> if (p == stored && r != null && r.startsWith("bytes=${16L shl 20}-") && n.incrementAndGet() == 1) 1000L else null }
        val old = store(s, root, RangePlan(8L shl 20, 8L shl 20, 1))
        val want = cpu(old)
        try { old.fetch(want) } catch (_: Exception) {}
        val part = File(root, "$stored.part")
        val map = File(root, "$stored.part.ranges")
        assertTrue("4.0.1 left a part and its map", part.isFile && map.isFile)
        s.rangeCut = null
        synchronized(s.requests) { s.requests.clear() }
        // 4.0.2's store on the same folder: the two complete ranges are kept; only what is missing is asked for
        val st = store(s, root)
        st.fetch(want)
        val weights = fileRequests(s).filter { it.path == stored }
        println("SPEC 16.7 401 part: ${weights.map { "${it.range} ${it.status} ${it.sent}" }}")
        assertTrue("the complete ranges asked again: $weights", weights.none { it.range == null || it.range!!.startsWith("bytes=0-") })
        assertTrue("a request without If-Range: $weights", weights.all { it.ifRange != null })
        assertTrue("bytes sent again that were on the device: ${weights.sumOf { it.sent }}", weights.sumOf { it.sent } <= big.stored.size - (16L shl 20))
        assertArrayEquals(big.bytes, File(root, big.path).readBytes())
    }
}
