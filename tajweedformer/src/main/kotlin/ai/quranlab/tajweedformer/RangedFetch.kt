package ai.quranlab.tajweedformer

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The stored bytes of one large model file in ranges (SDK 3.1.3), written into `<path>.part` at their offsets.
 *
 * - **The ranges.** `bytes=<a>-<b>` of [RangePlan.rangeSize] bytes each; up to [parallel] at once, each over a
 *   connection of its own; one buffer of [ModelDownloader.BUFFER] bytes per stream, so no range is ever held in memory
 *   whole.
 * - **The map** `<path>.part.ranges` says which ranges are complete. A range is entered there only after its bytes were
 *   written and forced to the storage; the map is written (atomically) before any byte of a ranged download, so a part
 *   without a map is always one stream's, whose bytes are the file's first ones (3.1.2 left such parts: their complete
 *   ranges are kept). A crash in the middle of a range leaves that range out of the map: it is asked for again whole.
 *   Within one call, a range whose request the network cut is asked for again from where it stopped (the retry rule,
 *   below).
 * - **The validator.** The first range not complete is asked for first, on the calling thread; its answer's strong ETag
 *   is kept in `<path>.part.v` and every other range carries it as If-Range. An answer of another
 *   validator, or a 200 to a range with If-Range (RFC 9110: the file changed), means the file changed: it is then
 *   fetched whole in one stream ([single]), and two versions are never mixed. A first answer without a validator: the
 *   file is fetched in one request as well, as 3.1.2 did.
 * - **A server without ranges** answers the first range with the whole file (200): that answer is the download, in one
 *   stream ([single]).
 * - **Which answer is a range** ([AnswerRule], SDK 3.1.3): a 206 whose Content-Range is exactly the bytes asked for, of
 *   a file of the manifest's size; a 200 only with the manifest's length. Anything else (a 204, a 200 of another
 *   length or with an error body, an empty 206, a 206 of another start, end or total) is a failed attempt: it counts
 *   against the file's five resumes like a connection that ended, with the rule's pauses, so nothing loops. A failed
 *   attempt never shrinks what the device holds: the complete ranges, their map and the validator are as they were,
 *   and a whole file that replaces them is written beside the part and takes its place only when every byte of it is
 *   there. A 416 ends the call and changes nothing either: stored ranges are removed only as a complete set that fails
 *   its size or its hash (SPEC 16.1), since each was held to the manifest's total when it arrived.
 * - **The key and the token** (SPEC 15.4): every range request goes through [modelRequest] with the Client's headers.
 *   The token is renewed at most once for the whole download, also when several ranges are refused at once. An answer
 *   that is not a range and not a server failure (a refusal, the daily limit, any status below 500, and a 5xx that
 *   carries a stop reason) first ends every other range and closes the part, then is read as any model request's
 *   answer is: so a stop (15.3) removes the model with no file of it held open, before its error returns; a range
 *   served after it (they were in flight together) does not clear it.
 * - **The network: the retry rule** ([RetryPlan], SDK 3.1.3). A range request that got no answer, whose connection
 *   ended or stalled (no byte for 30 s), or that the server failed (5xx) is made again for the rest of its range, from
 *   the byte where it stopped, after the rule's pause (1, 2, 4, 8, 16 s; a cancelled call ends the pause at once). The
 *   file has five such resumes in a call ([Resumes]): ranges that one failure of the link cut together count as one.
 *   When they are used up, the range has failed for good: no other range starts, the streams finish the ranges they
 *   have, which the map keeps, and the call fails as a download that was cut does. Any other answer of the server (a
 *   refusal, 401, 403, 429, 404) is never asked for again.
 *
 * The SHA-256 of the whole file is checked by the caller afterwards ([ModelDownloader]), whatever happened here.
 */
internal class RangedFetch(
    private val e: ModelManifest.FileEntry,
    private val part: File,
    private val validatorFile: File,
    private val mapFile: File,
    private val transport: HttpTransport,
    headers: HeaderProvider?,
    private val url: String,
    private val plan: RangePlan,
    private val parallel: Int,
    private val cancel: Cancellation?,
    private val progress: FileProgress,
    private val stats: FetchStats,
    /** The file's resumes in this call (the retry rule), shared with [single]. */
    private val resumes: Resumes,
    /** Fetches the file in one request; given the answer when the server answered a range with the whole file. */
    private val single: (HttpResponse?, Boolean) -> Unit,
) {
    private val size = e.size
    private val count = ((size + plan.rangeSize - 1) / plan.rangeSize).toInt()
    @Volatile private var attempt: Attempt? = null
    private val auth: HeaderProvider? = (headers as? ModelAuth)?.let { OneDownload(it, { x -> attempt?.refused(x) }) { attempt?.halt() } } ?: headers

    private fun first(k: Int) = k * plan.rangeSize
    private fun last(k: Int) = minOf(size, (k + 1) * plan.rangeSize) - 1

    private fun fail(why: String, cause: Throwable? = null): Nothing = throw RecognizerException.DownloadFailed(e.path, why, cause)

    /** An answer that is not the file ([AnswerRule]): a failed attempt, with nothing on the device changed. */
    private fun wrong(what: String): Nothing = throw AnswerRule.failed(e.path, what)

    private sealed class Outcome {
        object Done : Outcome()
        /** What the device holds is not what its map says (the device's own state, not an answer): it goes, and the
         *  file starts over. */
        object Again : Outcome()
        /** The file is to be fetched whole in one stream: the first answer carried no validator, or the server's file is
         *  another one now. What the device holds stays until the whole file is there. */
        object Anew : Outcome()
        /** The server answered a range with the whole file. */
        class Whole(val response: HttpResponse) : Outcome()
        /** The device holds a prefix that one stream wrote, without a validator: it is continued in one stream, with
         *  the Range alone (SPEC 16.2), as a file below the threshold is. */
        object Prefix : Outcome()
    }

    fun run() {
        // a cancel (the Client was closed) ends every range now, also one that waits for its answer
        val cancelling = cancel?.whenCancelled { attempt?.halt() }
        try {
            ranges()
        } finally {
            cancelling?.close()
        }
    }

    private fun ranges() {
        stats.ranged = true
        var starts = 0
        while (true) {
            val a = Attempt()
            attempt = a
            when (val o = a.run()) {
                Outcome.Done -> return
                Outcome.Again -> {
                    wipe(part, validatorFile, mapFile)
                    if (++starts > 1) fail("the part of the file on the device does not match its map")
                }
                Outcome.Anew -> {
                    stats.ranged = false
                    single(null, true)
                    return
                }
                is Outcome.Whole -> {
                    stats.ranged = false
                    single(o.response, true)
                    return
                }
                Outcome.Prefix -> {
                    stats.ranged = false
                    single(null, false)
                    return
                }
            }
        }
    }

    /** Thrown inside a range when the download was ended elsewhere: not a failure of its own. */
    private class Halted : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** One pass over the file's ranges, from what the device holds. */
    private inner class Attempt {
        private val lock = Any()
        private val done = BooleanArray(count)
        @Volatile private var validator: String? = null
        @Volatile private var channel: FileChannel? = null
        private val halted = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>(null)
        @Volatile private var changed = false
        /** Ends this attempt's requests that wait for their answer ([halt]). */
        private val ending = Cancellation()
        /** The device holds bytes of the file without the validator they came with: no range can be asked for under it. */
        private var orphaned = false
        /** Those bytes are a prefix that one stream wrote (no map): the stream goes on from its end ([Outcome.Prefix]). */
        private var prefix = false
        /** The threads of the ranges that read a refusal: each does the refusal's work on the device before it ends. */
        private val refusing = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        private val inFlight: MutableSet<HttpResponse> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

        fun run(): Outcome {
            load()
            // a server that gave no validator was used in one stream: what that left is continued the same way
            if (prefix && part.length() < size) return Outcome.Prefix
            val kept = (0 until count).filter { done[it] }.sumOf { last(it) - first(it) + 1 }
            stats.resumedFrom = kept
            progress.start(kept)
            val pending = (0 until count).filter { !done[it] }
            if (pending.isEmpty()) return if (part.length() == size) Outcome.Done else Outcome.Again
            // stored bytes without their validator: the whole file in one stream, which replaces them once it is whole
            if (orphaned) return Outcome.Anew
            // the first range that is not complete, asked for here: its answer says how the rest is fetched
            val p = pending.first()
            // an answer that is the file by [AnswerRule]: the range asked for (206), or the whole file (200: the server
            // does not answer ranges, or, with If-Range, its file is another one now; it replaces what the device holds
            // once it is whole)
            val probe = firstAnswer(first(p), last(p))
            if (probe.code == 200) return Outcome.Whole(probe)
            val v = AnswerRule.validator(probe)
            if (v == null || (validator != null && v != validator)) {
                // no validator to ask the other ranges with, or the file is another one now: whole, in one stream
                probe.close()
                return Outcome.Anew
            }
            try {
                // the map before the validator and before any byte (see the class)
                writeMap()
                if (validator == null) validatorFile.writeText(v)
                validator = v
                channel = FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (x: IOException) {
                probe.close()
                fail("cannot write ${part.name}: ${x.message}", x)
            }
            if (Thread.currentThread().isInterrupted || cancel?.isCancelled == true) {
                probe.close()
                fail("cancelled")
            }
            val queue = ConcurrentLinkedQueue(pending.drop(1))
            val streams = minOf(parallel, pending.size)
            val ended = CountDownLatch(streams)
            for (w in 0 until streams) {
                val mine = if (w == 0) p else null
                val given = if (w == 0) probe else null
                Thread({
                    try {
                        val buf = ByteArray(ModelDownloader.BUFFER)
                        var k = mine
                        var r = given
                        // once a range failed for good no new one starts: the streams finish what they have
                        while (!halted.get() && failure.get() == null) {
                            val range = k ?: queue.poll() ?: break
                            k = null
                            val answer = r
                            r = null
                            fetchRange(range, answer, buf)
                        }
                        r?.close()
                    } catch (_: Halted) {
                    } catch (x: Throwable) {
                        when {
                            // a refusal (the Client's error for an answer) decides the call, whatever else failed first
                            x is TajweedError && x !is RecognizerException.DownloadFailed -> failure.set(x)
                            // what a request or a read that the end of the attempt cut off fails with, when it is no
                            // error of the SDK (a connection ended from another thread): no failure of its own
                            halted.get() && x !is TajweedError -> {}
                            else -> failure.compareAndSet(null, x)
                        }
                        // a network that is gone for one range lets the others finish (the map keeps them); anything
                        // else (an answer of the server, the storage, a broken answer) ends every range now
                        if (!dropped(x)) halt()
                    } finally {
                        ended.countDown()
                    }
                }, "tajweedformer-range-$w").apply { isDaemon = true }.start()
            }
            try {
                while (!ended.await(100, TimeUnit.MILLISECONDS)) {
                    if (halted.get()) {
                        awaitQuietly(ended)
                        break
                    }
                }
            } catch (x: InterruptedException) {
                // the call was cancelled (the coroutine's thread is interrupted): every range ends, the map keeps
                halt()
                awaitQuietly(ended)
                Thread.currentThread().interrupt()
                fail("cancelled")
            } finally {
                synchronized(lock) { closeChannel() }
            }
            // a range that read a refusal recorded it before it ended the others, and then does the refusal's work (the
            // stop remembered, the model's files removed): the call returns when that is done, however long it takes, so
            // that nothing of the model is left when it returns (SPEC 15.3). No timer: the work bounds itself
            awaitRefusals()
            if (cancel?.isCancelled == true) fail("cancelled")
            failure.get()?.let { throw it }
            if (changed) return Outcome.Anew
            if (!done.all { it }) fail("not every range of the file arrived (the part and its map are kept for a resume)")
            return if (part.length() == size) Outcome.Done else Outcome.Again
        }

        /**
         * Ends every range now: answers in flight are ended, the part is closed, the map is not written any more.
         * Never throws. The answers are taken from the concurrent set itself: a copy made with `toList()` can throw
         * when another thread takes the set's one element out between its size and its first element.
         */
        fun halt() {
            halted.set(true)
            try { ending.cancel() } catch (_: Exception) {}
            try { for (r in inFlight) r.abort() } catch (_: Exception) {}
            synchronized(lock) { closeChannel() }
        }

        /**
         * A range read a refusal ([error], the Client's error for the answer): it is the attempt's failure from now on,
         * recorded before the other ranges are ended and before the refusal's work, so the call fails with it whatever
         * the calling thread decides and whenever. The thread is kept: the call waits for its work ([awaitRefusals]).
         */
        fun refused(error: TajweedError) {
            failure.set(error)
            refusing.addIfAbsent(Thread.currentThread())
        }

        /** Waits, without a timer, until every range that read a refusal has done the refusal's work. */
        private fun awaitRefusals() {
            for (t in refusing) {
                if (t === Thread.currentThread()) continue
                try {
                    t.join()
                } catch (_: InterruptedException) {
                    // the call was cancelled while it waited: it ends as cancelled, the work goes on to its end
                    Thread.currentThread().interrupt()
                    fail("cancelled")
                }
            }
        }

        private fun closeChannel() {
            try { channel?.close() } catch (_: IOException) {}
            channel = null
        }

        private fun awaitQuietly(latch: CountDownLatch) {
            // the streams end at once (their requests and answers were ended); one that a transport cannot end writes
            // nothing once halted, so the call does not wait for its timeout
            try { latch.await(HALT_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }

        private fun stopIfHalted() {
            if (cancel?.isCancelled == true) halt()
            if (halted.get()) throw Halted()
        }

        /**
         * What the device holds of the file: the map's complete ranges, or a prefix one stream wrote. Stored ranges are
         * removed only as a complete set that fails its size or its hash (SPEC 16.1); what goes here is what cannot be
         * read as ranges at all (a map that is not one, or of another file or range size), and a prefix without a map
         * that is longer than the file. Stored ranges whose validator file is missing stay: the file is then fetched
         * whole beside them ([orphaned]). A prefix without a map and without a validator is what one stream left when
         * the server gave no strong ETag (this SDK's own stream, or 3.1.2's): it is not ranges, and goes on as a stream
         * ([prefix]).
         */
        private fun load() {
            if (!part.isFile) {
                wipe(part, validatorFile, mapFile)
                return
            }
            if (mapFile.exists()) {
                val m = readMap()
                if (m == null || m.first != size || m.second != plan.rangeSize) {
                    wipe(part, validatorFile, mapFile)
                    return
                }
                for (k in m.third) if (k in 0 until count) done[k] = true
            } else {
                val have = part.length()
                if (have > size) {
                    wipe(part, validatorFile, mapFile)
                    return
                }
                for (k in 0 until count) if (last(k) < have) done[k] = true
            }
            val v = try { validatorFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } } catch (_: IOException) { null }
            if (v == null) {
                orphaned = true
                prefix = !mapFile.exists() && part.length() > 0
            }
            validator = v
        }

        private fun request(a: Long, b: Long): HttpResponse {
            val h = HashMap<String, String>()
            h["Accept-Encoding"] = "identity"
            h["Range"] = "bytes=$a-$b"
            validator?.let { h["If-Range"] = it }
            val r = modelRequest(transport, url, e.path, h, auth, setOf(200, 206, 416), ending)
            stats.answer(r)
            return r
        }

        /**
         * The answer to the first range not complete, asked for on the calling thread, by the retry rule: a 206 that is
         * that range or a 200 of the manifest's length. An answer that is neither is a failed attempt and counts
         * against the file's resumes; a 416 ends the call.
         */
        private fun firstAnswer(a: Long, b: Long): HttpResponse {
            while (true) {
                val g = resumes.generation
                try {
                    if (cancel?.isCancelled == true) fail("cancelled")
                    val r = request(a, b)
                    val bad = when (r.code) {
                        200 -> AnswerRule.whole(r, size)
                        206 -> AnswerRule.range(r, a, b, size)
                        else -> {
                            r.close()
                            fail("HTTP ${r.code} for bytes $a-$b of $size; nothing on the device was changed")
                        }
                    } ?: return r
                    r.close()
                    wrong(bad)
                } catch (x: RecognizerException.DownloadFailed) {
                    if (cancel?.isCancelled == true) fail("cancelled")
                    if (!RetryPlan.resumable(x)) throw x
                    val more = try {
                        resumes.again(g) { if (cancel?.isCancelled == true || Thread.currentThread().isInterrupted) fail("cancelled") }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        fail("cancelled")
                    }
                    if (!more) throw x
                }
            }
        }

        /** Range [k] from where it is to its end, the first answer [given] when there is one. */
        private fun fetchRange(k: Int, given: HttpResponse?, buf: ByteArray) {
            val b = last(k)
            var pos = first(k)
            var r = given
            while (true) {
                if (cancel?.isCancelled == true) halt()
                if (halted.get()) {
                    r?.close()
                    throw Halted()
                }
                // the file's resume this request belongs to: when it fails with others of the same one, they count once
                val g = resumes.generation
                val resp = r ?: try {
                    request(pos, b)
                } catch (x: RecognizerException.DownloadFailed) {
                    // a request that the end of the attempt cut off (it was waiting for its answer) is no failure of its own
                    if (halted.get() && x.cause is IOException) throw Halted()
                    if (!halted.get() && dropped(x) && resumes.again(g) { stopIfHalted() }) continue
                    throw x
                }
                r = null
                var cut: IOException? = null
                // what is wrong with an answer that is not this range ([AnswerRule]): a failed attempt, nothing written
                var bad: String? = null
                try {
                    inFlight.add(resp)
                    stopIfHalted()
                    when (resp.code) {
                        206 -> {
                            bad = AnswerRule.range(resp, pos, b, size)
                            if (bad == null && AnswerRule.validator(resp) != validator) {
                                changed = true
                                halt()
                                throw Halted()
                            }
                        }
                        200 -> {
                            // a range with If-Range answered with a whole file: it is another file now, or no file at
                            // all; the whole file is asked for in one stream, which takes only a 200 of the file's length
                            changed = true
                            halt()
                            throw Halted()
                        }
                        else -> {
                            halt()
                            fail("HTTP ${resp.code} for bytes $pos-$b of $size; nothing on the device was changed")
                        }
                    }
                    while (bad == null && pos <= b) {
                        stopIfHalted()
                        val n = try {
                            resp.body.read(buf, 0, minOf(buf.size.toLong(), b - pos + 1).toInt())
                        } catch (x: IOException) {
                            if (halted.get()) throw Halted()
                            cut = x
                            break
                        }
                        if (n < 0) break
                        write(buf, n, pos)
                        pos += n
                        stats.bytes(resp, n)
                        progress.add(n.toLong())
                    }
                } finally {
                    inFlight.remove(resp)
                    resp.close()
                }
                if (pos > b) {
                    complete(k)
                    return
                }
                // the answer was not this range: asked for again while the file has resumes left, then the call's failure
                val what = bad
                if (what != null) {
                    if (!resumes.again(g) { stopIfHalted() }) wrong(what)
                    continue
                }
                // the answer ended early, its connection dropped or stalled: the rest of this range, from where it stopped,
                // while the file has resumes left
                if (!resumes.again(g) { stopIfHalted() }) {
                    fail("network: the connection ended at byte $pos of the range $k (${first(k)}-$b); the part and its map are kept for a resume",
                        cut ?: IOException("the answer ended at byte $pos"))
                }
            }
        }

        private fun write(buf: ByteArray, n: Int, at: Long) {
            val ch = channel ?: throw Halted()
            val bb = ByteBuffer.wrap(buf, 0, n)
            var p = at
            try {
                while (bb.hasRemaining()) p += ch.write(bb, p)
            } catch (x: IOException) {
                if (halted.get()) throw Halted()
                halt()
                fail("cannot write ${part.name}: ${x.message}", x)
            }
        }

        /** Range [k] is complete: its bytes are forced to the storage, then the map says so. */
        private fun complete(k: Int) = synchronized(lock) {
            if (halted.get()) return@synchronized
            try {
                channel?.force(false)
                done[k] = true
                writeMap()
            } catch (x: IOException) {
                if (halted.get()) return@synchronized
                halt()
                fail("cannot keep the map of ${part.name}: ${x.message}", x)
            }
        }

        private fun writeMap() {
            val tmp = File(mapFile.path + ".tmp")
            // never makes a folder: once the model was removed (a stop), nothing of it comes back
            tmp.writeText(Json.write(linkedMapOf("v" to 1, "size" to size, "range" to plan.rangeSize,
                "done" to (0 until count).filter { done[it] })))
            try {
                Files.move(tmp.toPath(), mapFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), mapFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** (size, range size, complete ranges) of the map on the device, or null when it is not one. */
    private fun readMap(): Triple<Long, Long, List<Int>>? = try {
        val o = Json.parse(mapFile.readText()) as Map<*, *>
        if ((o["v"] as? Double)?.toInt() != 1) null
        else Triple((o["size"] as Double).toLong(), (o["range"] as Double).toLong(), (o["done"] as List<*>).map { (it as Double).toInt() })
    } catch (_: Exception) {
        null
    }

    /** A failure the retry rule asks again: no answer came, the answer's connection dropped or stalled, or the server
     *  failed (5xx); not the system's refusal of the network, not any other answer of the server. */
    private fun dropped(x: Throwable): Boolean = RetryPlan.resumable(x)

    /**
     * The Client's model-request rules for one download in ranges: the token is renewed at most once for the whole
     * download (two ranges refused at once renew it once; the other repeats with the new token), and an answer that is
     * not the file first ends every other range ([halt]) and then is read as the Client reads it.
     */
    private class OneDownload(private val inner: ModelAuth, private val record: (TajweedError) -> Unit, private val halt: () -> Unit) : ModelAuth {
        private var renewed = false
        /** An answer that was not the file came: a range served after it does not clear a refusal. */
        @Volatile private var notTheFile = false

        override fun headers(url: String): Map<String, String> = inner.headers(url)
        override fun renewal(status: Int, body: ByteArray): Boolean = inner.renewal(status, body)

        /** Inside the lock: a range that was refused at the same time waits here until the token is renewed, so that
         *  it is repeated with the new one and not with the one that was refused. */
        override fun renew() {
            synchronized(this) {
                if (renewed) return
                renewed = true
                inner.renew()
            }
        }

        /**
         * In this order: the answer is read (no side effect) and, when it is a refusal, recorded as the download's
         * failure; then every other range is ended (the part is closed before anything is removed). The refusal's work
         * comes after, in [refuse].
         */
        override fun refused(status: Int, body: ByteArray): TajweedError? {
            notTheFile = true
            val error = inner.refused(status, body)
            if (error != null) record(error)
            halt()
            return error
        }

        override fun refuse(status: Int, body: ByteArray) = inner.refuse(status, body)

        /** A range served after another was refused (they were in flight together) does not clear that refusal. */
        override fun served() {
            if (!notTheFile) inner.served()
        }
    }

    companion object {
        /** The map of a part's complete ranges, beside it. */
        const val MAP_SUFFIX = ".part.ranges"

        /** How long a call that ended its ranges waits for their streams. */
        private const val HALT_WAIT_MS = 5_000L

        /** Deletes what a download left of a file: the validator first (without it nothing is continued), then the part,
         *  then the map. */
        fun wipe(part: File, validator: File, map: File) {
            validator.delete()
            part.delete()
            map.delete()
            File(map.path + ".tmp").delete()
            File(part.path + ModelDownloader.NEW_SUFFIX).delete()
        }
    }
}
