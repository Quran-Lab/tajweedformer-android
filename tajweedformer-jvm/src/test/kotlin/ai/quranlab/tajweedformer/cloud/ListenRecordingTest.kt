package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.AudioCapture
import ai.quranlab.tajweedformer.AudioInput
import ai.quranlab.tajweedformer.AudioInputListener
import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.MicPermission
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.sharing.JoinTimer
import ai.quranlab.tajweedformer.sharing.OggTestKit
import ai.quranlab.tajweedformer.sharing.ShareRecorder
import ai.quranlab.tajweedformer.sharing.TakeJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * A `listen` take and its shared recording, through the public Client with a microphone the test owns: during the
 * wait after a take (the join window, and the 1500 ms in which a tail may come) the SDK records only audio the app
 * feeds. The microphone is closed when the take ends and is never kept or opened again for a tail, so with no feed
 * the recording holds exactly the take's samples.
 */
class ListenRecordingTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private lateinit var served: File
    private var server: com.sun.net.httpserver.HttpServer? = null
    private var client: TajweedClient? = null

    /** Without the host Opus library the test is skipped before anything is set up (a recording is real Ogg Opus). */
    @Before
    fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-listen-").toFile()
        served = Files.createTempDirectory("tj-listen-served-").toFile()
    }

    @After
    fun down() {
        client?.close()
        server?.stop(0)
        if (::stub.isInitialized) stub.close()
        if (::dir.isInitialized) dir.deleteRecursively()
        if (::served.isInitialized) served.deleteRecursively()
    }

    /** A microphone at 16 kHz that gives 20 ms buffers while it is open, and counts what it gave. */
    private class FakeMicrophone : AudioInput {
        @Volatile var opened = 0
        @Volatile var closed = 0
        /** Samples handed out by `read()`. */
        val given = AtomicLong()

        override fun permission(): MicPermission = MicPermission.GRANTED
        override suspend fun requestPermission(): Boolean = true

        override fun open(listener: AudioInputListener, sampleRate: Int?): AudioCapture {
            opened++
            return object : AudioCapture {
                @Volatile private var open = true
                override val sampleRate: Int = 16_000

                override fun read(): FloatArray? {
                    if (!open) return null
                    // faster than a real microphone, so the test does not take seconds
                    Thread.sleep(1)
                    given.addAndGet(320)
                    return FloatArray(320)
                }

                override fun close() {
                    if (open) {
                        open = false
                        closed++
                    }
                }
            }
        }
    }

    /** One chunk of 8 frames per 5120 samples; counts every sample it is given. */
    private class Engine : TajweedEngine {
        val samples = AtomicLong()
        override val chunkMs: Int get() = 320

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                this@Engine.samples.addAndGet(length.toLong())
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= 5120) {
                    pending -= 5120
                    out.add(chunk())
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(chunk())
            override fun reset() { pending = 0 }
            override fun close() {}
        }

        private fun chunk(): HeadValues {
            val m = HashMap<HeadId, FloatArray>()
            for (h in HeadId.entries) m[h] = FloatArray((if (h.frameMs == 40) 8 else 16) * h.width)
            val lp = m.getValue(HeadId.LOG_PROBS)
            for (f in 0 until 8) for (k in 0 until 38) lp[f * 38 + k] = if (k == (if (f == 3) 7 else 0)) 0f else -10f
            return HeadValues(8, m)
        }

        override fun close() {}
    }

    @Test
    fun aListenTakeRecordsOnlyItsOwnSamplesAndTheMicrophoneIsClosedAtItsEnd() = runBlocking {
        withTimeout(120_000) {
            // the model behind a local server, at the address the build in the signed config gives
            val files = TestKit.v4Files()
            TestKit.writeTransport(served, files, TestKit.sign(TestKit.manifest(files)))
            val build = java.security.MessageDigest.getInstance("SHA-256").digest(File(served, "manifest.json").readBytes())
                .joinToString("") { "%02x".format(it) }.take(40)
            val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/") { ex ->
                val prefix = "/Quran-Lab/tajweedformer-v4/$build/sdk/v4.0/"
                val f = if (ex.requestURI.path.startsWith(prefix)) File(served, ex.requestURI.path.removePrefix(prefix)) else null
                if (f == null || !f.isFile) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    ex.sendResponseHeaders(200, f.length())
                    ex.responseBody.use { o -> f.inputStream().use { it.copyTo(o) } }
                }
                ex.close()
            }
            http.start()
            server = http
            stub.recommended = mapOf("model" to "Quran-Lab/tajweedformer-v4", "model_id" to "v4.0", "folder" to "sdk/v4.0",
                "revision" to build, "version" to "4.0.0", "source" to "cdn")
            stub.inference = null

            var now = System.currentTimeMillis()
            val microphone = FakeMicrophone()
            val engine = Engine()
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(modelRetry = ai.quranlab.tajweedformer.RetryPlan.NONE, cloud = stub.environment(dir, clock = { now }), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = "http://127.0.0.1:${http.address.port}",
                microphone = microphone, engine = { _, _, _ -> engine }), autoLaunch = false).also { client = it }
            // Quran Lab offers sharing to this app and the learner said yes
            c.cloud.refreshConfig()
            c.cloud.sharing.accept()
            assertTrue(c.sharing.isAvailable && c.sharing.isOn)
            val recorder = c.cloud.sharing.recorder
            // the window's timer never runs: the test moves the clock and asks for the check
            recorder.timer = JoinTimer { _, _ -> {} }
            val closed = Collections.synchronizedList(ArrayList<ShareRecorder.Closed>())
            recorder.onClosed = { closed.add(it) }

            val model = c.model()
            assertEquals("the microphone is not opened before listen()", 0, microphone.opened)
            assertEquals("nothing is captured before listen()", 0L, microphone.given.get())
            val before = engine.samples.get()   // the self test's clip went through the engine when the model opened
            val session = model.listen()
            session.chunks.take(10).collect { }
            session.finish()

            // the take is over: the microphone was closed with it, and the recording waits for a next take or a tail
            val take = engine.samples.get() - before
            assertTrue("ten chunks of audio and more: $take", take >= 10 * 5120)
            assertEquals("the microphone was opened once and closed at the end of the take", listOf(1, 1), listOf(microphone.opened, microphone.closed))
            val givenAtEnd = microphone.given.get()
            // the last buffer the microphone gave may have come as the take ended, and is then not part of it
            assertTrue("the take is what the microphone gave: $take of $givenAtEnd", take <= givenAtEnd && givenAtEnd - take <= 320)
            assertTrue("the recording is still open", recorder.current != null)
            assertEquals(0, closed.size)

            // half way through the wait, then past the tail wait and the join window, with no feed from the app
            now += TakeJoin.TAIL_WAIT_MS / 2
            recorder.checkWindow()
            assertTrue("still waiting", recorder.current != null)
            now += TakeJoin.DEFAULT_WINDOW_MS + 1
            recorder.checkWindow()
            assertTrue("the wait ran out: the recording closed", recorder.current == null)
            assertEquals(1, closed.size)
            val recording = closed[0]
            assertEquals(16_000, recording.rate)
            assertEquals("the recording holds exactly the samples of the take", take, recording.samples)
            assertEquals("no pre-roll, the take, no tail", listOf(0L, take, 0L), recording.parts.single().toList())
            assertEquals("the microphone gave nothing after the end of the take", givenAtEnd, microphone.given.get())
            assertEquals("it was never opened again", listOf(1, 1), listOf(microphone.opened, microphone.closed))
            c.cloud.sharing.lastCapture?.await()
            Unit
        }
    }
}
