package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.AudioCapture
import ai.quranlab.tajweedformer.AudioInput
import ai.quranlab.tajweedformer.AudioInputListener
import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.MicPermission
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.Recognizer
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineFactory
import ai.quranlab.tajweedformer.TajweedEngineStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A stand-in for the engine that counts: one chunk of 8 frames (320 ms) for every 320 ms of input at the rate it was
 * opened for, a token in every chunk. It knows how many samples it was given and how many of its streams are open.
 */
internal class CountingEngine(val sampleRate: Int = 16_000) : TajweedEngine {
    val samples = AtomicLong()
    val streamsMade = AtomicInteger()
    val streamsClosed = AtomicInteger()

    /** Streams made and not closed. */
    val openStreams: Int get() = streamsMade.get() - streamsClosed.get()

    private val chunkSamples = 320 * sampleRate / 1000
    override val chunkMs: Int get() = 320

    override fun createStream(): TajweedEngineStream {
        streamsMade.incrementAndGet()
        return object : TajweedEngineStream {
            private var pending = 0
            private var closed = false

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                this@CountingEngine.samples.addAndGet(length.toLong())
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= chunkSamples) {
                    pending -= chunkSamples
                    out.add(chunk())
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(chunk())
            override fun reset() { pending = 0 }

            override fun close() {
                if (!closed) {
                    closed = true
                    streamsClosed.incrementAndGet()
                }
            }
        }
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

/** The engines a Client or a Model opened, in order, each with the rate it was opened for. */
internal class CountingEngines : TajweedEngineFactory {
    val opened = CopyOnWriteArrayList<CountingEngine>()

    override fun open(model: ModelFolder, context: String, options: Recognizer.Options): TajweedEngine =
        CountingEngine(options.sampleRate).also { opened.add(it) }

    /** The rate of each engine opened. */
    val rates: List<Int> get() = opened.map { it.sampleRate }

    /** Engine streams made and not closed, over every engine. */
    val openStreams: Int get() = opened.sumOf { it.openStreams }

    /** Samples given to the engines opened for [rate]. */
    fun samplesAt(rate: Int): Long = opened.filter { it.sampleRate == rate }.sumOf { it.samples.get() }
}

/**
 * A microphone the test drives. It gives 20 ms buffers at the rate of its capture while one is open (faster than a
 * real one, so a test does not take seconds), keeps what the SDK asked of it, and lets the test play the device:
 * refuse the permission, interrupt and resume, change the route, fail to come back, come back at another rate.
 */
internal class ScriptedMicrophone(
    /** The device's own rate: what a capture runs at when the SDK asks for none. */
    @Volatile var deviceRate: Int = 16_000,
) : AudioInput {
    /** The permission now. */
    @Volatile var state: MicPermission = MicPermission.GRANTED

    /** What the permission is after the learner was asked. */
    @Volatile var answer: MicPermission = MicPermission.GRANTED

    /** Times the learner was asked. */
    val asked = AtomicInteger()

    /** Every open: the rate the SDK asked for (null: the device's own) and the rate of the capture it got. */
    val opens = CopyOnWriteArrayList<Pair<Int?, Int>>()
    val closes = AtomicInteger()

    /** Samples handed out by `read()`. */
    val given = AtomicLong()

    /** Set: the device is gone, and an open fails with this text. */
    @Volatile var gone: String? = null

    /** Set: a capture opened again runs at this rate, whatever was asked. */
    @Volatile var comesBackAt: Int? = null

    @Volatile private var listener: AudioInputListener? = null

    /** Captures open now. */
    val open: Int get() = opens.size - closes.get()

    override fun permission(): MicPermission = state

    override suspend fun requestPermission(): Boolean {
        asked.incrementAndGet()
        state = answer
        return state == MicPermission.GRANTED
    }

    override fun open(listener: AudioInputListener, sampleRate: Int?): AudioCapture {
        gone?.let { throw RecognizerException.MicrophoneUnavailable(it) }
        this.listener = listener
        val rate = if (sampleRate == null) deviceRate else (comesBackAt ?: sampleRate)
        opens.add(sampleRate to rate)
        return object : AudioCapture {
            @Volatile private var isOpen = true
            override val sampleRate: Int = rate

            override fun read(): FloatArray? {
                if (!isOpen) return null
                Thread.sleep(1)
                val n = rate / 50
                given.addAndGet(n.toLong())
                return FloatArray(n)
            }

            override fun close() {
                if (isOpen) {
                    isOpen = false
                    closes.incrementAndGet()
                }
            }
        }
    }

    /** Another app took the audio. */
    fun interrupt() = listener!!.interrupted()

    /** The other app gave it back. */
    fun resume() = listener!!.resumed()

    /** Headphones went in or out. */
    fun changeRoute() = listener!!.routeChanged()
}
