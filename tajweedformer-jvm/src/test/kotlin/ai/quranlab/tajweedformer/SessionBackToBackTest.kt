package ai.quranlab.tajweedformer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage B (a device lab, Pixel 10, 11-18): the 35 conformance clips as back-to-back takes through `model.listen(clip)`,
 * each one's chunks read to the end and then `finish()`, failed at the second take with `session_open`. A clip take
 * ends by itself at the end of its audio, on its own coroutine, which completed the take's result before it let go of
 * the Model, so `finish()` could return while the take still held it. `finish()` now returns only once the Model is
 * free: the next take started right after it always starts.
 */
class SessionBackToBackTest : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    /** One chunk of 8 frames (320 ms) per 5120 input samples, and one more at the end. */
    private class Engine : TajweedEngine {
        override val chunkMs: Int get() = 320

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= 5120) {
                    pending -= 5120
                    out.add(heads())
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(heads())
            override fun reset() { pending = 0 }
            override fun close() {}
        }

        fun heads(): HeadValues {
            val m = HashMap<HeadId, FloatArray>()
            for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
            return HeadValues(8, m)
        }

        override fun close() {}
    }

    @Test
    fun theNextTakeStartsRightAfterFinish() = runBlocking {
        val dir = Files.createTempDirectory("tj-b2b-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        val m = TajweedModel.of(ModelFolder.open(dir, TestKit.verifier), ModelHost(verifier = TestKit.verifier, engine = { _, _, _ -> Engine() }))
        try {
            withTimeout(120_000) {
                repeat(300) { i ->
                    val s = m.listen(FloatArray(16_000 + 160 * (i % 7)), 16_000, TajweedSessionOptions(pace = TajweedSessionOptions.Pace.FAST))
                    val chunks = s.chunks.toList()
                    s.finish()
                    assertEquals("take $i", 4, chunks.size)
                }
            }
        } finally {
            m.close()
        }
    }
}
