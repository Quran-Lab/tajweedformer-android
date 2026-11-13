package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.MicPermission
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.OpenObjects
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedEvent
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * `model.listen()` through the public Client with a microphone the test drives ([ScriptedMicrophone]): a refused
 * permission, an interruption that ends and one that cannot be resumed, a route change, the app going to the
 * background, and a device whose rate is not 16000. What the app sees is checked (the error, the events in their
 * order, the Heads of `finish()`), and what the SDK did to the device (when it opened and closed the microphone,
 * at which rate, and that no audio was read while it must not be).
 *
 * The engine is a stand-in that counts its samples per rate ([CountingEngines]); no test here needs the real one.
 */
class ListenMicrophoneTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var served: ServedModel? = null
    private var client: TajweedClient? = null
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-mic-").toFile()
    }

    @After
    fun down() {
        client?.close()
        watching.cancel()
        served?.close()
        stub.close()
        dir.deleteRecursively()
    }

    private class Opened(val client: TajweedClient, val model: TajweedModel, val engines: CountingEngines)

    /** A Client with [mic] as its microphone and its Model open (downloaded from a local server, the engine a stand-in). */
    private suspend fun open(mic: ScriptedMicrophone, warmRate: Int = 16_000): Opened {
        val s = ServedModel.of().also { served = it }
        stub.recommended = s.recommended()
        stub.inference = null
        val engines = CountingEngines()
        val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(modelRetry = ai.quranlab.tajweedformer.RetryPlan.NONE, cloud = stub.environment(dir), apiUrl = stub.base,
            trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = s.url, microphone = mic, warmRate = warmRate,
            engine = engines), autoLaunch = false).also { client = it }
        return Opened(c, c.model(), engines)
    }

    /** Collects a Session's events and counts its chunks, off the test's own coroutine. */
    private inner class Watch(session: TajweedSession) {
        val events = CopyOnWriteArrayList<TajweedEvent>()
        val chunks = AtomicInteger()
        private val jobs = listOf(
            watching.launch { session.events.collect { events.add(it) } },
            watching.launch { try { session.chunks.collect { chunks.incrementAndGet() } } catch (_: TajweedError) {} },
        )

        /** The microphone's events and the end, by their contract names, in order. */
        fun names(): List<String> = events.mapNotNull { e ->
            when (e) {
                is TajweedEvent.Interrupted -> if (e.cause == TajweedEvent.Interrupted.Cause.INTERRUPTION) "interrupted(interruption)" else "interrupted(routeChange)"
                is TajweedEvent.Resumed -> "resumed"
                is TajweedEvent.Ended -> "ended(${e.reason.name.lowercase()})"
                else -> null
            }
        }

        suspend fun done() = jobs.forEach { it.join() }
    }

    private suspend fun until(what: String, limitMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.nanoTime() + limitMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("timed out waiting for: $what")
            delay(2)
        }
    }

    private fun sessionsOpen(): Int = OpenObjects.snapshot().values.count { it.startsWith("Session") }

    private suspend fun code(body: suspend () -> Unit): String? = try {
        body()
        null
    } catch (e: TajweedError) {
        e.code
    }

    @Test
    fun aRefusedPermissionIsMicrophoneDeniedAndNoSessionIsLeftOpen() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone().apply { state = MicPermission.NOT_ASKED; answer = MicPermission.DENIED }
            val o = open(mic)
            assertEquals("notAsked", o.client.diagnose().microphone)

            assertEquals("microphone_denied", code { o.model.listen() })
            assertEquals("the learner was asked once", 1, mic.asked.get())
            assertTrue("the microphone was opened without the permission: ${mic.opens}", mic.opens.isEmpty())
            assertEquals(0L, mic.given.get())
            assertEquals("denied", o.client.diagnose().microphone)
            assertEquals("a refused take left a Session", 0, sessionsOpen())
            assertEquals("a refused take left an engine stream open", 0, o.engines.openStreams)

            // the Model is free: a pushed take runs at once (it would be session_open had the refused one kept its place)
            val pushed = o.model.session()
            pushed.push(FloatArray(5120))
            assertEquals(16, pushed.finish().decoded().frames.size)

            // refused again; then the learner allows it and the same call listens
            assertEquals("microphone_denied", code { o.model.listen() })
            assertTrue(mic.opens.isEmpty())
            mic.answer = MicPermission.GRANTED
            val session = o.model.listen()
            val w = Watch(session)
            until("a chunk of the take") { w.chunks.get() >= 1 }
            assertEquals(3, mic.asked.get())
            assertEquals("granted", o.client.diagnose().microphone)
            assertTrue(session.finish().decoded().isFinal)
            until("the microphone is closed after finish()") { mic.open == 0 }
            assertEquals(1, mic.opens.size)
            w.done()
            assertEquals(0, sessionsOpen())
        }
    }

    @Test
    fun anInterruptionPausesTheTakeAndItGoesOnWhenTheAudioComesBack() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone()
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }

            mic.interrupt()
            until("the capture is closed while another app holds the audio") { mic.open == 0 }
            until("the event") { w.names().isNotEmpty() }
            assertEquals(listOf("interrupted(interruption)"), w.names())
            // paused: nothing is read from the microphone and nothing reaches the engine
            val given = mic.given.get()
            val heard = o.engines.samplesAt(16_000)
            delay(150)
            assertEquals("the microphone was read during the interruption", given, mic.given.get())
            assertEquals("audio reached the engine during the interruption", heard, o.engines.samplesAt(16_000))
            val chunksAtPause = w.chunks.get()

            mic.resume()
            until("the take goes on: three more chunks") { w.chunks.get() >= chunksAtPause + 3 }
            assertEquals(listOf("interrupted(interruption)", "resumed"), w.names())
            assertEquals("opened for the take, and again after the interruption at the take's rate",
                listOf<Pair<Int?, Int>>(null to 16_000, 16_000 to 16_000), mic.opens.toList())

            val heads = session.finish()
            w.done()
            assertTrue(heads.decoded().isFinal)
            assertTrue("one take across the interruption: ${heads.decoded().frames.size} frames", heads.decoded().frames.size >= 8 * (chunksAtPause + 3))
            assertEquals("the app finished the take: no `ended` event", listOf("interrupted(interruption)", "resumed"), w.names())
            until("the microphone is closed after finish()") { mic.open == 0 }
            assertEquals(0, sessionsOpen())
        }
    }

    @Test
    fun anInterruptionThatCannotBeResumedEndsTheTakeAsInterrupted() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone()
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }

            mic.interrupt()
            until("the capture is closed") { mic.open == 0 }
            // the other app is done, but the input device does not come back
            mic.gone = "the input device is gone"
            mic.resume()
            until("the take ends") { w.names().lastOrNull()?.startsWith("ended") == true }
            assertEquals("never `resumed`: the microphone did not come back", listOf("interrupted(interruption)", "ended(interrupted)"), w.names())
            val ended = w.events.filterIsInstance<TajweedEvent.Ended>().single()
            assertEquals(TajweedEvent.Ended.Reason.INTERRUPTED, ended.reason)
            assertEquals("interrupted", ended.error?.code)
            assertTrue("the error has no fix", ended.error!!.fix.isNotBlank())

            // what was recited before the interruption is the take: finish() returns its Heads
            val heads = session.finish()
            w.done()
            assertTrue(heads.decoded().isFinal)
            assertTrue(heads.decoded().frames.size >= 24)
            assertEquals("the microphone was opened once and never again", 1, mic.opens.size)
            assertEquals(0, mic.open)
            assertEquals(0, sessionsOpen())
            assertEquals(0, o.engines.openStreams)
            // the Model is free for the next take
            mic.gone = null
            o.model.listen().cancel()
            until("the next take's microphone is closed") { mic.open == 0 }
        }
    }

    @Test
    fun finishDuringAnInterruptionEndsTheTakeWithItsHeads() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone()
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }
            mic.interrupt()
            until("the capture is closed") { mic.open == 0 }

            val heads = session.finish()
            w.done()
            assertTrue(heads.decoded().isFinal)
            assertEquals(listOf("interrupted(interruption)"), w.names())
            assertEquals("the microphone was not opened again to finish", 1, mic.opens.size)
            assertEquals(0, sessionsOpen())
        }
    }

    @Test
    fun aRouteChangeReopensTheMicrophoneAtTheTakesRateAndTheTakeGoesOn() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone(deviceRate = 48_000)
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }

            // headphones go in: the new input has another rate of its own
            mic.deviceRate = 16_000
            mic.changeRoute()
            until("the event") { w.names().isNotEmpty() }
            val chunksAtChange = w.chunks.get()
            until("the take goes on: three more chunks") { w.chunks.get() >= chunksAtChange + 3 }
            assertEquals(listOf("interrupted(routeChange)"), w.names())
            // the first capture was closed and a new one opened, asked for the rate the Session was told at its start
            assertEquals(listOf<Pair<Int?, Int>>(null to 48_000, 48_000 to 48_000), mic.opens.toList())
            assertEquals(1, mic.closes.get())

            val heads = session.finish()
            w.done()
            until("the microphone is closed after finish()") { mic.open == 0 }
            // every sample of the take reached the engine opened for 48000, and its chunks are 320 ms at that rate
            val heard = o.engines.samplesAt(48_000)
            assertTrue("the take is what the microphone gave: $heard of ${mic.given.get()}", heard <= mic.given.get() && mic.given.get() - heard <= 2 * 960)
            assertEquals("the frames of the take at 48000 Hz", 8 * (heard / 15_360 + 1), heads.decoded().frames.size.toLong())
            assertEquals(0, sessionsOpen())
        }
    }

    @Test
    fun aMicrophoneThatComesBackAtAnotherRateIsNotFedToTheTake() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone(deviceRate = 48_000)
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }

            // the new input cannot give the take's rate
            mic.comesBackAt = 44_100
            mic.changeRoute()
            until("the take ends") { w.names().lastOrNull()?.startsWith("ended") == true }
            assertEquals(listOf("ended(error)"), w.names())
            assertEquals("microphone_unavailable", w.events.filterIsInstance<TajweedEvent.Ended>().single().error?.code)
            until("both captures are closed") { mic.open == 0 }
            assertEquals(listOf<Pair<Int?, Int>>(null to 48_000, 48_000 to 44_100), mic.opens.toList())
            // nothing of the 44100 Hz capture was read into the 48000 Hz take
            assertEquals("no engine was opened for another rate", listOf(16_000, 48_000), o.engines.rates)
            val heard = o.engines.samplesAt(48_000)
            assertEquals("samples at 48000 Hz are whole 20 ms buffers", 0L, heard % 960)
            // the take ended with what it had: finish() returns the Heads of the audio before the route change
            val heads = session.finish()
            w.done()
            assertTrue(heads.decoded().isFinal)
            assertEquals("the frames of the take at 48000 Hz", 8 * (heard / 15_360 + 1), heads.decoded().frames.size.toLong())
            assertEquals(0, sessionsOpen())
            assertEquals(0, o.engines.openStreams)
        }
    }

    @Test
    fun theDevicesRateIsCarriedToTheSession() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone(deviceRate = 48_000)
            val o = open(mic)
            assertEquals("the engine is warmed for 16000 Hz on this platform", listOf(16_000), o.engines.rates)
            var opens = 0
            for (rate in listOf(48_000, 44_100, 8_000, 16_000)) {
                mic.deviceRate = rate
                val givenBefore = mic.given.get()
                val heardBefore = o.engines.samplesAt(rate)
                val session = o.model.listen()
                val w = Watch(session)
                until("$rate Hz: four chunks") { w.chunks.get() >= 4 }
                val heads = session.finish()
                w.done()
                until("$rate Hz: the microphone is closed") { mic.open == 0 }
                // the SDK asked for no rate: it took the device's own, and opened the engine for exactly that rate
                assertEquals("$rate Hz", null to rate, mic.opens[opens++])
                assertEquals("$rate Hz: the engine of the take", rate, o.engines.rates.last())
                val given = mic.given.get() - givenBefore
                val heard = o.engines.samplesAt(rate) - heardBefore
                assertTrue("$rate Hz: the take is what the microphone gave ($heard of $given)", heard <= given && given - heard <= 2L * (rate / 50))
                // 320 ms of audio at the device's rate per chunk: a take fed at another rate would have other frames
                val chunkSamples = 320L * rate / 1000
                assertEquals("$rate Hz: the frames of the take", 8 * (heard / chunkSamples + 1), heads.decoded().frames.size.toLong())
            }
            assertEquals("one engine at a time, opened again when the rate changed", listOf(16_000, 48_000, 44_100, 8_000, 16_000), o.engines.rates)
            assertEquals(0, o.engines.openStreams)
        }
    }

    @Test
    fun anEngineWarmedForTheMicrophonesRateIsNotOpenedAgain() = runBlocking {
        withTimeout(60_000) {
            // what the Android platform does: the engine is warmed for the microphone's own rate when the Model opens
            val mic = ScriptedMicrophone(deviceRate = 48_000)
            val o = open(mic, warmRate = 48_000)
            // the Model's engine is the first one opened; the self test ran its 16 kHz clip on an engine of its own,
            // opened for that and closed again
            val atOpen = o.engines.rates
            assertEquals(listOf(48_000, 16_000), atOpen)
            val session = o.model.listen()
            val w = Watch(session)
            until("two chunks") { w.chunks.get() >= 2 }
            session.finish()
            w.done()
            assertEquals("the first listen take opened an engine: it needed a reload", atOpen, o.engines.rates)
            assertTrue("the take did not run on the engine warmed for 48000 Hz", o.engines.opened.first().samples.get() >= 2L * 15_360)
        }
    }

    @Test
    fun theBackgroundEndsAListenTakeAndReleasesTheMicrophone() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone()
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }

            o.client.background()
            until("the take ends") { w.names().lastOrNull()?.startsWith("ended") == true }
            assertEquals(listOf("ended(background)"), w.names())
            assertNull(w.events.filterIsInstance<TajweedEvent.Ended>().single().error)
            until("the microphone is released") { mic.open == 0 }
            // nothing is captured in the background
            val given = mic.given.get()
            delay(150)
            assertEquals(given, mic.given.get())
            assertEquals(1, mic.opens.size)
            // the take is finished, not lost: finish() returns its Heads
            val heads = session.finish()
            w.done()
            assertTrue(heads.decoded().isFinal)
            assertTrue(heads.decoded().frames.size >= 24)
            assertEquals(0, sessionsOpen())

            // a take the app pushes is the app's own: the background does not end it
            o.client.foreground()
            val pushed = o.model.session()
            pushed.push(FloatArray(5120))
            o.client.background()
            delay(100)
            pushed.push(FloatArray(5120))
            assertEquals(24, pushed.finish().decoded().frames.size)
        }
    }

    @Test
    fun closingTheClientDuringATakeLeavesNothingOpen() = runBlocking {
        withTimeout(60_000) {
            val mic = ScriptedMicrophone()
            val o = open(mic)
            val session = o.model.listen()
            val w = Watch(session)
            until("three chunks") { w.chunks.get() >= 3 }
            assertEquals("the take's engine stream", 1, o.engines.openStreams)

            o.client.close()
            w.done()
            until("the microphone is closed") { mic.open == 0 }
            until("the take's engine stream is closed") { o.engines.openStreams == 0 }
            assertEquals("cancelled", code { session.finish() })
            assertTrue("something is still listed as open: ${OpenObjects.snapshot().values}",
                OpenObjects.snapshot().values.none { it.startsWith("Session") || it.startsWith("Model") || it.startsWith("Client") })
        }
    }
}
