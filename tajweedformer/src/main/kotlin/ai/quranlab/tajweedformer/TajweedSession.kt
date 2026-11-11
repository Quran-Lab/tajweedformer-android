package ai.quranlab.tajweedformer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Runs [body]; whatever it throws leaves as a [TajweedError] (a coroutine's cancellation stays what it is). */
internal inline fun <T> guard(body: () -> T): T = try {
    body()
} catch (e: CancellationException) {
    throw e
} catch (e: TajweedError) {
    throw e
} catch (e: Throwable) {
    throw TajweedError.of(e)
}

/**
 * One take: audio in, chunks and events out, the take's Heads at the end. There are two ways to get one:
 * `model.listen()` (the SDK captures the microphone, or plays a clip) and `model.session()` (the app pushes audio).
 * A Model has one open Session at a time; the next take is a new Session.
 *
 * ```
 * val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 1500))
 * session.chunks.collect { chunk -> heard.text = chunk.decoded.text }   // ends when the take ends
 * val heads = session.finish()                                          // every selected head of the take
 * ```
 *
 * [chunks] and [events] are each collected once. They wait for the collector, so nothing is lost when collection
 * starts late; but they do not wait without end: at most 256 chunks and 64 events wait, and when the app does not
 * collect, the oldest waiting one is dropped. An app that reads late still has everything: `chunk.decoded` holds the
 * whole take so far, the last chunk and `Ended` are the newest and are never the ones dropped, and `finish()`
 * returns every Head of the take. `client.diagnose()` counts the chunks dropped.
 *
 * A take holds at most 20 minutes of audio. At the limit it ends by itself, `Ended(LIMIT)`, with its Heads kept for
 * `finish()`; a `push` after it is `session_closed`. Start a new Session to go on.
 */
public class TajweedSession internal constructor(
    private val model: TajweedModel,
    private val recognizer: Recognizer,
    private val inner: RecognizerSession,
    private val options: TajweedSessionOptions,
    /** A `listen` take (the SDK feeds it: the microphone, a clip or samples) or a pushed one. */
    private val listening: Boolean,
) {
    /** The thread that is sending a chunk now: a chunk the channel gives back on it was dropped to make room (one
     *  given back elsewhere was left uncollected when the app stopped collecting, which is not a drop). */
    @Volatile private var chunkSender: Thread? = null
    private val chunkChannel = Channel<TajweedChunk>(TakeLimits.CHUNK_BUFFER, BufferOverflow.DROP_OLDEST) {
        if (Thread.currentThread() === chunkSender) model.host.chunkDropped()
    }
    private val eventChannel = Channel<TajweedEvent>(TakeLimits.EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vocabulary = recognizer.model.vocabulary
    private val select = options.headSet()
    private val decoder = TakeDecoder(vocabulary, options.topK)
    private val speech = SpeechTracker(options.silenceEndMs ?: DEFAULT_SILENCE_MS)
    private val ending = AtomicBoolean(false)
    private val result = CompletableDeferred<TajweedHeads>()
    @Volatile private var lastBackend: TajweedBackend? = null
    @Volatile private var sawLast = false
    @Volatile private var feeder: Job? = null
    /** The recited range the app reported while the take ran (the last one wins); counted when the take ends. */
    private var reported: Triple<Int, Int, Int>? = null

    init {
        OpenObjects.opened(this, if (listening) "Session (listen)" else "Session (push)")
    }

    /** Each chunk as it is ready. Ends when the take ends; after `cancel()` it fails with the error `cancelled`. */
    public val chunks: Flow<TajweedChunk> = chunkChannel.consumeAsFlow()

    /** What happened to the take: speech started and ended, interruptions, a backend change, the end. */
    public val events: Flow<TajweedEvent> = eventChannel.consumeAsFlow()

    /** How many of the engine's chunks the Session has taken (decoded, its events raised); the largest Int once the
     *  pump has stopped, so nobody waits for a chunk that will not be taken. */
    private val taken = MutableStateFlow(0)

    private val pump: Job = scope.launch {
        try {
            inner.chunks.collect { c ->
                deliver(c)
                taken.value = taken.value + 1
            }
            if (!sawLast) closingChunk()
            chunkChannel.close()
        } catch (e: CancellationException) {
            chunkChannel.close(RecognizerException.Cancelled())
        } catch (e: Throwable) {
            chunkChannel.close(TajweedError.of(e))
        } finally {
            taken.value = Int.MAX_VALUE
        }
    }

    /** The take ended on silence at this many chunks: what the engine gives after them is not part of the take. */
    @Volatile private var silencedAt = -1

    private fun deliver(c: EngineChunk) {
        if (silencedAt >= 0) return
        val rewound = decoder.add(c.full, c.index, c.firstFrame)
        if (rewound) speech.rewind(c.firstFrame)
        val raised = ArrayList<TajweedEvent>()
        val b = TajweedBackend.ofChunk(c.backend, recognizer.options.soc)
        val prev = lastBackend
        if (rewound || (prev != null && prev.kind != b.kind)) raised.add(TajweedEvent.BackendChanged(b, c.index))
        lastBackend = b
        val hasSpeech = speech.add(SpeechFrames.active(c.full, vocabulary.blank), raised)
        // with endOnSilenceMs the chunk that carries the frame of `speechEnded` is delivered whole and is the last
        val silence = options.silenceEndMs != null && raised.any { it is TajweedEvent.SpeechEnded }
        val last = c.last || silence
        if (last) sawLast = true
        val decoded = decoder.snapshot(final = last)
        if (last) finalDecoded = decoded
        if (silence) {
            silencedAt = c.index + 1
            inner.stopAfter(silencedAt)
        }
        sendChunk(TajweedChunk(c.index, c.firstFrame * 40L, (c.firstFrame + c.full.n40) * 40L, c.firstFrame, b, hasSpeech,
            TajweedHeads(c.heads, vocabulary, c.firstFrame, options.topK), decoded))
        for (e in raised) eventChannel.trySend(e)
        // The end is decided here, with the chunk that carries the frame: from now on a push is `session_closed`, and an
        // app's finish() that comes at the same moment waits for this end and gets its Heads (the take ended on
        // silence, whoever asked next). The end itself runs outside the pump, which it waits for.
        if (silence && ending.compareAndSet(false, true)) scope.launch { end(TajweedEvent.Ended.Reason.SILENCE, null, claimed = true) }
    }

    @Volatile private var finalDecoded: TajweedDecoded? = null

    /** The end of a take whose last engine call gave no chunk: one closing chunk with no new frames carries the final
     *  decoded layer, so the last chunk of every finished take has `isFinal`. */
    private fun closingChunk() {
        val decoded = decoder.snapshot(final = true)
        finalDecoded = decoded
        val at = decoder.frameCount
        sendChunk(TajweedChunk(decoder.chunks, at * 40L, at * 40L, at, lastBackend ?: backend, false,
            TajweedHeads(HeadValues(0, emptyMap()), vocabulary, at, options.topK), decoded))
    }

    /** A chunk for the app. When [TakeLimits.CHUNK_BUFFER] chunks already wait, the oldest of them is dropped. */
    private fun sendChunk(chunk: TajweedChunk) {
        chunkSender = Thread.currentThread()
        try { chunkChannel.trySend(chunk) } finally { chunkSender = null }
    }

    /**
     * Audio in: mono float samples in -1..1 at the Session's `sampleRate`, any amount at a time. Only on a Session
     * made with `model.session()`; on a `listen` Session, or after the take ended, the error is `session_closed`.
     * Returns once the engine has run every chunk the samples complete and the Session has taken them: their chunks
     * are in [chunks], their events in [events], and an end they bring (silence, the limit) is decided, so the next
     * `push` is `session_closed`. When the engine fails, the error is thrown and the take is over (`Ended(ERROR)`).
     */
    public suspend fun push(samples: FloatArray) {
        if (listening || ending.get()) throw RecognizerException.SessionClosed()
        try {
            guard { inner.append(samples) }
        } catch (e: TajweedError) {
            // the engine failed under this take: the take is over (`Ended(ERROR)`), and the caller gets the error
            if (e.code != "session_closed") endBy(TajweedEvent.Ended.Reason.ERROR, e)
            throw e
        }
        val want = inner.emitted
        taken.first { it >= want }
        if (inner.atLimit) endBy(TajweedEvent.Ended.Reason.LIMIT)
    }

    /**
     * Ends the take and returns its Heads: every selected head over the whole take, and its final decoded layer. On a
     * `listen` Session it also stops the microphone. After the take ended by itself (silence, background, the end of
     * a clip) it returns the Heads of that take. After `cancel()` the error is `cancelled`.
     */
    public suspend fun finish(): TajweedHeads {
        end(null, null)
        return guard { result.await() }
    }

    /** Ends the take and keeps nothing: [chunks] fails with `cancelled`, the microphone stops. */
    public fun cancel() {
        if (!ending.compareAndSet(false, true)) return
        stop()
        inner.cancel()
        settle(counts = false)
        result.completeExceptionally(RecognizerException.Cancelled())
        eventChannel.close()
        closed()
    }

    /** The Backend running this take now. */
    public val backend: TajweedBackend get() = lastBackend ?: model.backendFor(inner.backend)

    /**
     * App-reported, for the app's own statistics at sdk.quranlab.ai: the learner recited ayahs [ayahFrom] to [ayahTo]
     * of [surah] in this take. Counts only (ayahs per surah per day): never text, audio or times, and never part of a
     * shared recording. One report per take: a second one replaces the first. May be called during the take or after
     * it. A surah or ayah the Quran does not have is `invalid_argument`. Nothing is sent when analytics are off.
     */
    public fun reportRecited(surah: Int, ayahFrom: Int, ayahTo: Int) {
        Quran.check(surah, ayahFrom, ayahTo)
        val now = Triple(surah, ayahFrom, ayahTo)
        synchronized(reportLock) {
            when (outcome) {
                // the take is running: the report waits for its end, and a later one replaces it
                Outcome.OPEN -> reported = now
                // the take ended with its Heads: the first report counts at once, later ones count nothing
                Outcome.COUNTS -> if (!counted) { counted = true; model.host.recited(now) }
                // a cancelled or failed take counts no report
                Outcome.DROPPED -> {}
            }
        }
    }

    private enum class Outcome { OPEN, COUNTS, DROPPED }

    private val reportLock = Any()
    private var outcome = Outcome.OPEN
    private var counted = false

    /** The take ended: with its Heads ([counts]) the report made during it is counted now. */
    private fun settle(counts: Boolean) = synchronized(reportLock) {
        if (outcome != Outcome.OPEN) return@synchronized
        outcome = if (counts) Outcome.COUNTS else Outcome.DROPPED
        val r = reported
        if (counts && r != null) { counted = true; model.host.recited(r) }
    }

    // ---- the SDK's own feeds (listen) ----

    /** Feeds the take from the microphone until it ends: an interruption pauses it, a route change opens the capture again. */
    internal fun feedFrom(input: AudioInput, first: AudioCapture, listenerRef: MicListener) {
        val rate = first.sampleRate
        var capture: AudioCapture? = first
        listenerRef.session = this
        micListener = listenerRef
        // a read gives about 20 ms of audio, so the loop sees a pause, a route change or the end within that time
        // The capture opened again for this take, at the take's rate: the rate the Session was told when it started
        // is the rate of every sample it gets. A device that gives another rate than it was asked for is not used.
        fun reopen(): AudioCapture {
            val c = input.open(listenerRef, rate)
            if (c.sampleRate != rate) {
                try { c.close() } catch (_: Exception) {}
                throw RecognizerException.MicrophoneUnavailable("the microphone came back at ${c.sampleRate} Hz, the take runs at $rate Hz")
            }
            return c
        }
        feeder = scope.launch(Dispatchers.IO) {
            // an interruption ended and the microphone is being opened again: a failure here is the interruption's
            var resuming = false
            try {
                while (isActive && !ending.get()) {
                    if (listenerRef.paused) {
                        try { capture?.close() } catch (_: Exception) {}
                        capture = null
                        listenerRef.resume.receive()
                        if (ending.get()) break
                        resuming = true
                        capture = reopen()
                        resuming = false
                        eventChannel.trySend(TajweedEvent.Resumed())
                        continue
                    }
                    if (listenerRef.reopen.compareAndSet(true, false)) {
                        try { capture?.close() } catch (_: Exception) {}
                        capture = null
                        capture = reopen()
                        eventChannel.trySend(TajweedEvent.Interrupted(TajweedEvent.Interrupted.Cause.ROUTE_CHANGE))
                    }
                    val buffer = capture?.read() ?: throw RecognizerException.MicrophoneUnavailable("the recording stopped")
                    // what the microphone gave while another app held the audio is not take audio
                    if (!listenerRef.paused && !ending.get()) inner.append(buffer)
                    if (inner.atLimit) {
                        // the take holds all it may: the microphone stops here (the capture closes below)
                        scope.launch { endBy(TajweedEvent.Ended.Reason.LIMIT) }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!ending.get()) {
                    // the take was paused, or its interruption had just ended and the microphone did not come back:
                    // the interruption could not be resumed
                    val wasPaused = listenerRef.paused || resuming
                    val error = if (wasPaused) RecognizerException.Interrupted(e.message ?: "the microphone did not come back") else TajweedError.of(e)
                    scope.launch { endBy(if (wasPaused) TajweedEvent.Ended.Reason.INTERRUPTED else TajweedEvent.Ended.Reason.ERROR, error) }
                }
            } finally {
                try { capture?.close() } catch (_: Exception) {}
            }
        }
    }

    /** The microphone's signals for one take. */
    internal class MicListener : AudioInputListener {
        @Volatile var session: TajweedSession? = null
        @Volatile var paused = false
        @Volatile var stopped = false
        val reopen = AtomicBoolean(false)
        val resume = Channel<Unit>(Channel.CONFLATED)

        override fun interrupted() {
            if (stopped || paused) return
            paused = true
            session?.eventChannel?.trySend(TajweedEvent.Interrupted(TajweedEvent.Interrupted.Cause.INTERRUPTION))
        }

        override fun resumed() {
            if (stopped || !paused) return
            paused = false
            resume.trySend(Unit)
        }

        override fun routeChanged() {
            if (stopped || paused) return
            reopen.set(true)
        }
    }

    @Volatile private var micListener: MicListener? = null

    /** Feeds the take from samples the SDK already has (a sample clip, or the app's own array), then ends it. */
    internal fun feedFrom(samples: FloatArray, sampleRate: Int) {
        val fast = options.pace == TajweedSessionOptions.Pace.FAST
        // real time: 20 ms pieces on the clock; fast: one chunk's audio a piece, as quickly as the engine takes them
        val piece = if (fast) maxOf(1, sampleRate * maxOf(recognizer.chunkMs, 160) / 1000) else maxOf(1, sampleRate / 50)
        feeder = scope.launch(Dispatchers.Default) {
            try {
                val t0 = System.nanoTime()
                var at = 0
                while (at < samples.size && isActive && !ending.get() && !inner.atLimit) {
                    val n = minOf(piece, samples.size - at)
                    inner.append(samples, at, n)
                    at += n
                    if (!fast) {
                        val due = t0 + at * 1_000_000_000L / sampleRate
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) delay(wait)
                    }
                }
                if (!ending.get()) endBy(if (inner.atLimit) TajweedEvent.Ended.Reason.LIMIT else TajweedEvent.Ended.Reason.SOURCE_END)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!ending.get()) endBy(TajweedEvent.Ended.Reason.ERROR, TajweedError.of(e))
            }
        }
    }

    /** The app left the foreground: a `listen` take ends (the SDK never holds the microphone in the background). */
    internal fun background() {
        if (listening && !ending.get()) scope.launch { endBy(TajweedEvent.Ended.Reason.BACKGROUND) }
    }

    /** Stops the SDK's own feed (the caller has set [ending]): the feeder leaves its loop and closes the capture. */
    private fun stop() {
        micListener?.let { it.stopped = true; it.resume.trySend(Unit) }
    }

    /** Waits until the feeder has left its loop, unless this IS the feeder (a clip that ran out ends its own take). */
    private suspend fun feederDone() {
        val f = feeder ?: return
        if (kotlin.coroutines.coroutineContext[Job] !== f) f.join()
    }

    /** The take ends without the app's `finish`: [reason] goes out as the `Ended` event after the last chunk. */
    private suspend fun endBy(reason: TajweedEvent.Ended.Reason, error: TajweedError? = null) = end(reason, error)

    /** [claimed]: the caller has already set [ending] for this end. */
    private suspend fun end(reason: TajweedEvent.Ended.Reason?, error: TajweedError?, claimed: Boolean = false) {
        if (!claimed && !ending.compareAndSet(false, true)) return
        stop()
        try {
            feederDone()
            val heads = inner.finish(failed = reason == TajweedEvent.Ended.Reason.ERROR)
            pump.join()
            result.complete(TajweedHeads(heads.only(select), vocabulary, 0, options.topK, finalDecoded))
            settle(counts = reason != TajweedEvent.Ended.Reason.ERROR)
            if (reason != null) eventChannel.trySend(TajweedEvent.Ended(reason, error))
        } catch (e: CancellationException) {
            inner.cancel()
            settle(counts = false)
            result.completeExceptionally(RecognizerException.Cancelled())
            throw e
        } catch (e: Throwable) {
            val failure = error ?: TajweedError.of(e)
            inner.cancel()
            settle(counts = false)
            result.completeExceptionally(failure)
            eventChannel.trySend(TajweedEvent.Ended(TajweedEvent.Ended.Reason.ERROR, failure))
        } finally {
            eventChannel.close()
            closed()
        }
    }

    private fun closed() {
        model.sessionEnded(this)
        OpenObjects.closed(this)
        // the pump and the feeder end by themselves; nothing else runs in this scope afterwards
        scope.launch { pump.join(); scope.cancel() }
    }

    internal companion object {
        /** `speechEnded` comes after this much silence when `endOnSilenceMs` is not set. */
        const val DEFAULT_SILENCE_MS = 1500
    }
}
