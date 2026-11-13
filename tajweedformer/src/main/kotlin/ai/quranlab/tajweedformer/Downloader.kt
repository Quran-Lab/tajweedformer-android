package ai.quranlab.tajweedformer

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * The system does not let this app use the network (Android: the INTERNET permission, which the SDK's manifest
 * declares, was taken out of the app's manifest). An IOException, so everything that waits for a connection treats it
 * as having none; the calls that need the network name it (`network_not_permitted`).
 */
internal class NetworkNotPermitted(cause: SecurityException) :
    IOException("the system does not let this app use the network: ${cause.message}", cause)

/** [request], where a request to a server is made: the system's refusal of the network leaves as [NetworkNotPermitted]. */
internal fun <T> permitted(request: () -> T): T = try {
    request()
} catch (e: SecurityException) {
    throw NetworkNotPermitted(e)
}

/**
 * The HTTP the model downloader uses: one GET with headers. The default is [UrlConnectionTransport]
 * (`HttpURLConnection`, no dependency). Internal: no app can pass a transport (SPEC 15.1).
 */
internal fun interface HttpTransport {
    /** A GET of [url] with [headers]. Throws IOException for a network failure. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse

    /**
     * A GET that [cancel] can end while it waits for the answer (the Client was closed, or a download in ranges was
     * ended): the request then fails with an IOException at once. A transport that cannot end a waiting request makes
     * the plain request; the answer's own [HttpResponse.abort] ends it once it has come.
     */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse = get(url, headers)

    /**
     * Whether requests made at the same time each have a connection of their own (`HttpURLConnection` over HTTP/1.1:
     * yes). Only then are several ranges of a file asked for at once: ranges over ONE multiplexed HTTP/2 or HTTP/3
     * connection share its congestion window and are not faster, so such a transport fetches them one after another.
     */
    val separateConnections: Boolean get() = true
}

/** An HTTP answer: status, headers (names lower case), the body. Close it when done. */
internal class HttpResponse(
    val code: Int,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: InputStream,
    /** The protocol the answer came over, as negotiated: `http/1.1`, `h2`, `h3`, or `unknown`. */
    val protocol: String = UNKNOWN_PROTOCOL,
    /** Ends the request from another thread: a read that waits for bytes fails at once. Null: [onClose]. */
    private val onAbort: (() -> Unit)? = null,
    /** Last, so that a caller's trailing lambda is this one. */
    private val onClose: () -> Unit = {},
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun close() {
        try { body.close() } catch (_: IOException) {}
        onClose()
    }

    /** Ends the request from any thread, at any time (the thread that reads gets an IOException); never throws. */
    fun abort() {
        try { (onAbort ?: onClose)() } catch (_: Exception) {}
    }

    companion object {
        const val UNKNOWN_PROTOCOL = "unknown"

        /**
         * The protocol of an answer from what `HttpURLConnection` gives: Android's (its OkHttp) names the one ALPN chose
         * in `X-Android-Selected-Protocol`; otherwise the status line says it (`HTTP/1.1 206 Partial Content`).
         */
        fun protocolOf(selected: String?, statusLine: String?): String {
            val s = selected?.trim()?.lowercase()
            if (!s.isNullOrEmpty()) return when (s) {
                "h2", "http/2", "http/2.0" -> "h2"
                "h3", "http/3" -> "h3"
                else -> s
            }
            val v = statusLine?.trim()?.substringBefore(' ')?.uppercase() ?: return UNKNOWN_PROTOCOL
            return when {
                v == "HTTP/1.1" -> "http/1.1"
                v == "HTTP/1.0" -> "http/1.0"
                v.startsWith("HTTP/2") -> "h2"
                v.startsWith("HTTP/3") -> "h3"
                else -> UNKNOWN_PROTOCOL
            }
        }
    }
}

/**
 * Headers added to every model request. The Client's is [ModelAuth] (the key and the install's token, SPEC 15.4); null
 * means none (the device speed check app, which serves a folder of its own).
 */
internal fun interface HeaderProvider {
    fun headers(url: String): Map<String, String>
}

/**
 * The Client's side of a model request (SPEC 15.3, 15.4), made in one place
 * ([ai.quranlab.tajweedformer.cloud.ModelRequestHeaders]): the headers, the token's renewal, and what an answer that is
 * not the file means.
 */
internal interface ModelAuth : HeaderProvider {
    /** Whether the answer asks for a new token (401 `invalid_token` without a stop reason): renewed once, repeated once. */
    fun renewal(status: Int, body: ByteArray): Boolean

    /** The next [headers] carries a new token. */
    fun renew()

    /**
     * The error a request fails with for this answer, or null: a plain HTTP failure. It only reads the answer:
     * nothing is remembered and nothing is removed by it.
     */
    fun refused(status: Int, body: ByteArray): TajweedError?

    /**
     * What the answer does on the device when [refused] gave an error for it: a stop is remembered and the model's
     * files are removed. Called once, after [refused] and before the request fails with that error, on the thread of
     * the request: when it returns, the work is done.
     */
    fun refuse(status: Int, body: ByteArray)

    /** The CDN served a model request. */
    fun served()
}

/** The most of an error answer's body that is read. */
private const val MAX_ERROR_BODY = 64 * 1024

private fun readSome(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * One request for a model's file ([path] inside the build's folder, at [url]): the provider's headers and [extra]. An
 * answer whose status is in [ok] is returned (the caller closes it). Otherwise, with a [ModelAuth]: a 401
 * `invalid_token` renews the token once and repeats the request once; a refusal is the provider's error; anything else
 * is `download_failed` with the status. No connection is `download_failed` with the IOException as its cause; a
 * server failure (5xx) that carries no stop reason is `download_failed` with [ServerFailed] as its cause; a 2xx that
 * is not in [ok] is `download_failed` with [WrongAnswer] as its cause. Those three are what a download asks for again
 * ([RetryPlan]); no other answer ever is.
 */
internal fun modelRequest(transport: HttpTransport, url: String, path: String, extra: Map<String, String>, headers: HeaderProvider?, ok: Set<Int>,
                          cancel: Cancellation? = null): HttpResponse {
    val auth = headers as? ModelAuth
    var renewed = false
    while (true) {
        val h = HashMap<String, String>()
        headers?.headers(url)?.let { h.putAll(it) }
        h.putAll(extra)
        val r = try {
            permitted { transport.get(url, h, cancel) }
        } catch (x: IOException) {
            throw RecognizerException.DownloadFailed(path, "network: ${x.message}", x)
        }
        if (r.code in ok) {
            auth?.served()
            return r
        }
        val body = try { readSome(r.body, MAX_ERROR_BODY) } catch (_: IOException) { ByteArray(0) } finally { r.close() }
        // a success that is not the answer asked for (a 204, say): a failed attempt ([AnswerRule])
        if (r.code in 200..299) throw AnswerRule.failed(path, "HTTP ${r.code}")
        // a redirect is never followed, and it is a failed request whatever its body says (SPEC 15.4): no renewal and
        // no refusal is read from it, so nothing is remembered and nothing is removed; asked once
        if (r.code in 300..399) throw RecognizerException.DownloadFailed(path, "HTTP ${r.code}")
        if (auth != null && !renewed && auth.renewal(r.code, body)) {
            auth.renew()
            renewed = true
            continue
        }
        // a server failure is asked for again by the retry rule, whatever its body says (a stop is read only from a 401
        // or a 403, SPEC 15.3), unless it says that the network the request came from is not served (503
        // `network_blocked`: asked once, SPEC 16.2)
        if (r.code >= 500 && !networkBlocked(r.code, body)) {
            throw RecognizerException.DownloadFailed(path, "HTTP ${r.code}", ServerFailed(r.code))
        }
        // a refusal: read first (a download in ranges records it as the call's error at once), then its work on the device
        auth?.refused(r.code, body)?.let { refusal ->
            auth?.refuse(r.code, body)
            throw refusal
        }
        throw RecognizerException.DownloadFailed(path, "HTTP ${r.code}")
    }
}

/**
 * Which answer to a model request is the file (SDK 3.1.3, the same in every Quran Lab SDK). A 2xx is accepted only as
 *
 * - a 200 with the manifest's length: a Content-Length, when the answer has one, equals the file's size (a body that
 *   runs past that size is cut off by the reader, and the attempt failed), or
 * - a 206 whose Content-Range starts at the byte asked for, ends at the last byte asked for, and names the manifest's
 *   size as the total (never `*`); its Content-Length, when it has one, is the range's length.
 *
 * Anything else (a 204, another 2xx, a 200 of another length or with an error body, an empty 206, a 206 of another
 * start, end or total) is a FAILED ATTEMPT: it counts against the file's five resumes like a connection that ended
 * ([RetryPlan]: the pauses apply, and an attempt that brings no byte counts as any other, so nothing loops), and it
 * never shrinks what the device holds: the part, the complete ranges, their map and the validator are as they were
 * before the attempt. Each check returns what is wrong with the answer, or null when it is the file.
 */
internal object AnswerRule {
    /** The failure of an attempt whose answer was not the file: `download_failed`, with [WrongAnswer] as its cause. */
    fun failed(path: String, what: String): RecognizerException.DownloadFailed =
        RecognizerException.DownloadFailed(path, "the server's answer is not the file ($what); nothing on the device was changed", WrongAnswer(what))

    private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$")

    private fun length(r: HttpResponse): Pair<Boolean, Long?> {
        val h = r.header("content-length")?.trim() ?: return false to null
        return true to h.toLongOrNull()
    }

    /** A 200 for a file of [size] bytes. */
    fun whole(r: HttpResponse, size: Long): String? {
        val (given, n) = length(r)
        return if (given && n != size) "a 200 with Content-Length ${r.header("content-length")} for a file of $size bytes" else null
    }

    /** A 206 for the bytes [from] to [to] of a file of [size] bytes. */
    fun range(r: HttpResponse, from: Long, to: Long, size: Long): String? {
        val cr = r.header("content-range")?.trim()
        val m = cr?.let { CONTENT_RANGE.find(it) } ?: return "a 206 with Content-Range $cr for bytes $from-$to of $size"
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        val total = m.groupValues[3].toLongOrNull()
        if (a != from || b != to || total != size) return "a 206 with Content-Range $cr for bytes $from-$to of $size"
        val (given, n) = length(r)
        if (given && n != to - from + 1) return "a 206 with Content-Length ${r.header("content-length")} for the ${to - from + 1} bytes $from-$to"
        return null
    }

    /** The answer's validator for If-Range: its strong ETag, or null (a weak ETag cannot be one, RFC 9110 13.1.5). */
    fun validator(r: HttpResponse): String? = r.header("etag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
}

/** Whether an answer is the CDN's 503 `network_blocked` (SPEC 15.3): try later, never again in the call. */
private fun networkBlocked(status: Int, body: ByteArray): Boolean {
    if (status != 503) return false
    val o = try { Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *> } catch (_: Exception) { null }
    return o?.get("error") == "network_blocked"
}

/** The cause of a `download_failed` whose answer said success and was not the file ([AnswerRule]): a failed attempt,
 *  asked for again by the retry rule while the file has resumes left. */
internal class WrongAnswer(what: String) : RuntimeException(what) {
    override fun fillInStackTrace(): Throwable = this
}

/** The cause of a `download_failed` whose answer was a server failure (5xx) without a stop reason: asked for again by the
 *  retry rule, and not a missing connection. */
internal class ServerFailed(val status: Int) : RuntimeException("HTTP $status") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The retry rule of a model download (SDK 3.1.3, the same in every Quran Lab SDK).
 *
 * - **A stall ends a request.** No byte for [STALL_MS] (30 s) and the request is ended: the transport's read timeout,
 *   which is a limit on the wait for the next byte and not on the whole request, so a slow link that keeps delivering is
 *   never cut.
 * - **The download resumes inside the call**, at most [pausesMs]`.size` (five) times per file, after pauses of 1, 2, 4,
 *   8 and 16 s that end at once when the call is cancelled. It goes on from the bytes the device holds, with the
 *   validator (If-Range with Range). What is asked for again: a request that got no answer, a connection that ended, a
 *   stall, a server failure (5xx), and an answer that says success and is not the file ([AnswerRule]). The count is
 *   the file's in this call and bytes that arrive in between do not reset it: a link that fails six times within one
 *   file ends the call, with the part kept for the next. An attempt that brings no byte counts like any other.
 * - **Asked once and never again in the call:** a refusal with a stop reason (SPEC 15.3), any 401, 403 or 429 beyond
 *   the one renewal of the token on 401 `invalid_token` (SPEC 15.4), a 503 `network_blocked`, a redirect, and any
 *   other status. The manifest is asked once: the rule is for the files it lists.
 *
 * The rule is SPEC section 16. What Android does in its own way: the limit on connecting is 15 s
 * (the contract names 30), resolving a name has no limit of the SDK's own, and every request has a connection of its
 * own over HTTP/1.1 ([UrlConnectionTransport]).
 */
internal class RetryPlan(val pausesMs: LongArray) {
    companion object {
        /** No byte for this long ends a model request. */
        const val STALL_MS = 30_000

        /** Five resumes per file and call, after 1, 2, 4, 8 and 16 s. */
        @JvmField val DEFAULT = RetryPlan(longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000))

        /** No resume inside the call (3.1.2 and earlier: the next call resumes). */
        @JvmField val NONE = RetryPlan(LongArray(0))

        /** Whether a failed model request is one the rule asks for again: no answer, a connection that ended, a stall
         *  (all three an IOException of the transport), a server failure, or a success that was not the file
         *  ([AnswerRule]). Not the system's refusal of the network. */
        fun resumable(x: Throwable): Boolean = x is RecognizerException.DownloadFailed &&
            ((x.cause is IOException && x.cause !is NetworkNotPermitted) || x.cause is ServerFailed || x.cause is WrongAnswer)
    }
}

/**
 * The resumes of one file in one call ([RetryPlan]). A file in ranges has several requests at once, and one failure of
 * the link ends all of them together: every request notes the [generation] it started in, and the first of them to
 * fail starts the next resume (one of the five, with its pause); the others of the same generation join that resume
 * without counting again.
 */
internal class Resumes(private val plan: RetryPlan) {
    private var count = 0
    private var until = 0L

    /** The resumes so far; a request notes it when it starts. */
    val generation: Int get() = synchronized(this) { count }

    /**
     * A request that started in generation [g] failed in a way the rule asks again. Returns false when the file's
     * resumes are used up (the failure is the call's); else waits out the pause and returns true. [check] is called
     * during the pause (every 50 ms at most) and throws when the call was cancelled or the download ended elsewhere.
     */
    fun again(g: Int, check: () -> Unit): Boolean {
        val wait: Long
        synchronized(this) {
            if (count == g) {
                if (count >= plan.pausesMs.size) return false
                until = System.nanoTime() + plan.pausesMs[count] * 1_000_000
                count++
            }
            wait = until
        }
        while (true) {
            check()
            val left = (wait - System.nanoTime()) / 1_000_000
            if (left <= 0) return true
            Thread.sleep(minOf(50, left))
        }
    }
}

/** Whether the device is on an unmetered network now. Android: [ai.quranlab.tajweedformer.android.AndroidNetwork]. */
internal fun interface NetworkPolicy {
    fun isUnmetered(): Boolean

    /**
     * The connection as `diagnose()` reports it: `unmetered` (Wi-Fi, Ethernet), `metered` (cellular, or marked
     * metered or data saver by the system), `none` (no network), or `unknown` where the platform cannot tell.
     * `prefetch(unmeteredOnly = true)` downloads on `unmetered` and `unknown` and waits on `metered` and `none`.
     */
    fun connection(): String = if (isUnmetered()) "unmetered" else "metered"

    companion object {
        /** Treats every network as unmetered (tests). */
        @JvmField val ALWAYS_UNMETERED = NetworkPolicy { true }

        /** A platform that cannot tell what its network is (the desktop JVM): nothing waits for an unmetered one. */
        @JvmField val UNKNOWN: NetworkPolicy = object : NetworkPolicy {
            override fun isUnmetered(): Boolean = true
            override fun connection(): String = "unknown"
        }
    }
}

/** `HttpURLConnection`, never following a redirect (a model request never leaves the host it was made to, SPEC 15.4),
 *  never through a response cache an app may have installed (`HttpResponseCache`: no byte of a model may be kept in a
 *  second place, SPEC 15). A connection that is not made within 15 s fails; [readTimeoutMs] is the stall limit of the
 *  retry rule ([RetryPlan.STALL_MS]): the socket's wait for the next bytes, of the answer's head and of its body, so
 *  no byte for 30 s ends the request (`SocketTimeoutException`) and a slow link that keeps delivering is never cut.
 *  Each request at a time has its own connection. */
internal class UrlConnectionTransport @JvmOverloads constructor(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = RetryPlan.STALL_MS,
) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = get(url, headers, null)

    override fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false
        c.useCaches = false
        for ((k, v) in headers) c.setRequestProperty(k, v)
        // while the answer's head is waited for, a cancel closes the connection: the wait ends at once
        val h = HashMap<String, String>()
        try {
            val code = (cancel?.whenCancelled { try { c.disconnect() } catch (_: Exception) {} }).let { waiting ->
                try {
                    if (cancel?.isCancelled == true) throw IOException("the request was ended")
                    c.responseCode
                } finally {
                    waiting?.close()
                }
            }
            for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) h[k.lowercase()] = v.last()
            val body = if (code >= 400) (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))) else c.inputStream
            val protocol = HttpResponse.protocolOf(h["x-android-selected-protocol"], c.getHeaderField(0))
            // disconnect() from another thread closes the socket, so a read that waits on it ends at once
            return HttpResponse(code, h, EndableBody(body), protocol, onAbort = { c.disconnect() }) { c.disconnect() }
        } catch (x: SecurityException) {
            // the system refuses the network: the caller's to name
            throw x
        } catch (x: RuntimeException) {
            throw ended(x)
        }
    }

    /**
     * The body of an answer whose connection may be ended from another thread (a cancel, the end of a download in
     * ranges): a read then fails with an IOException, also where the platform's stream fails with something else.
     */
    private class EndableBody(private val inner: InputStream) : InputStream() {
        override fun read(): Int = io { inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = io { inner.read(b, off, len) }
        override fun available(): Int = io { inner.available() }
        override fun close() = io { inner.close() }
        private inline fun <T> io(f: () -> T): T = try {
            f()
        } catch (x: RuntimeException) {
            throw ended(x)
        }
    }

    private companion object {
        /** An `HttpURLConnection` that is disconnected while another thread uses it can fail with an exception that is
         *  no IOException: to the SDK it is a connection that ended. */
        fun ended(x: RuntimeException): IOException = IOException("the connection was ended (${x.javaClass.simpleName})", x)
    }
}

/** Stops a download from another thread. */
internal class Cancellation {
    private val flag = AtomicBoolean(false)
    private val actions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    val isCancelled: Boolean get() = flag.get()

    fun cancel() {
        flag.set(true)
        for (a in actions) try { a() } catch (_: Exception) {}
    }

    /**
     * [action] runs when this is cancelled, on the cancelling thread (at once when it is cancelled already), until the
     * handle returned is closed: what ends a request that is waiting or reading right now.
     */
    fun whenCancelled(action: () -> Unit): AutoCloseable {
        actions.add(action)
        if (flag.get()) try { action() } catch (_: Exception) {}
        return AutoCloseable { actions.remove(action) }
    }
}

/**
 * How the stored bytes of a large file are fetched (SDK 3.1.3): a file of more than [threshold] bytes in ranges of
 * [rangeSize] bytes, with a map beside its part ([RangedFetch]) so that a cut connection or a restart continues from the
 * ranges that are complete. Up to [parallel] ranges at once where each request has a connection of its own
 * ([HttpTransport.separateConnections]), else one after another. A range whose connection dropped is asked for again
 * from where it stopped, by the retry rule of the file ([RetryPlan]).
 */
internal class RangePlan(val threshold: Long, val rangeSize: Long, val parallel: Int) {
    init {
        require(rangeSize > 0 && parallel >= 1)
    }

    companion object {
        /**
         * 8 MiB ranges, four at a time. A CPU install of the V4 model (about 55 MB) has one such file, the 53.6 MB
         * weights (seven ranges); every other file of it is one request.
         */
        @JvmField val DEFAULT = RangePlan(threshold = 8L shl 20, rangeSize = 8L shl 20, parallel = 4)

        /** Every file in one request, as 3.1.2 fetched them. */
        @JvmField val SINGLE = RangePlan(threshold = Long.MAX_VALUE, rangeSize = 8L shl 20, parallel = 1)
    }
}

/** What a download of one file did on the network: bytes, the protocol of each answer, whether it went by ranges. */
internal class FetchStats {
    /** Body bytes received in this call (also of answers that were not used). */
    val network = java.util.concurrent.atomic.AtomicLong()
    /** Bytes of the stored file that were on the device from an earlier call. */
    @Volatile var resumedFrom = 0L
    @Volatile var ranged = false
    /** Body bytes per protocol (`http/1.1`, `h2`, `h3`), with every protocol an answer came over. */
    private val byProtocol = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun answer(r: HttpResponse) {
        byProtocol.computeIfAbsent(r.protocol) { java.util.concurrent.atomic.AtomicLong() }
    }

    fun bytes(r: HttpResponse, n: Int) {
        network.addAndGet(n.toLong())
        byProtocol.computeIfAbsent(r.protocol) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(n.toLong())
    }

    fun protocols(): Map<String, Long> = byProtocol.entries.associate { it.key to it.value.get() }.toSortedMap()
}

/**
 * The progress of one file's stored bytes, reported in steps of [ModelDownloader.PROGRESS_STEP] and never lower than a
 * report before it: ranges arrive from several threads and out of order, and a download that starts over is reported
 * again only once it passes what was reported. Reports never overlap (one at a time).
 */
internal class FileProgress(private val e: ModelManifest.FileEntry, private val onProgress: ((DownloadProgress) -> Unit)?) {
    private val have = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var mark = -1L
    private var reported = -1L

    /** The bytes on the device when a download (or its new start) begins. */
    fun start(bytes: Long) {
        have.set(bytes)
        synchronized(this) { if (mark < 0) mark = bytes }
    }

    fun add(n: Long) {
        val now = have.addAndGet(n)
        if (now - mark >= ModelDownloader.PROGRESS_STEP) report(now, false)
    }

    /** The last report of the file: its bytes on the device now. */
    fun end(bytes: Long) = report(bytes, true)

    @Synchronized
    private fun report(bytes: Long, last: Boolean) {
        if (!last && bytes - mark < ModelDownloader.PROGRESS_STEP) return
        if (bytes < reported || (bytes == reported && !last)) return
        reported = bytes
        mark = bytes
        val p = DownloadProgress(e.path, e.role, bytes, e.size)
        try { onProgress?.invoke(p) } catch (_: Exception) {}
        SdkEvents.emit { it.onDownloadProgress(p) }
    }
}

/**
 * Downloads manifest entries into a folder, resumably and verified (LAYOUT.md "Rules for every SDK"):
 *
 * 1. If the decoded (kept) file is already in place with its size and SHA-256, nothing is fetched.
 * 2. The stored bytes go to `<path>.part`. A file larger than the plan's threshold ([RangePlan]) is fetched in ranges
 *    ([RangedFetch], SDK 3.1.3), with `<path>.part.ranges` saying which are complete; a smaller one in one request, with
 *    HTTP Range from what is already there. The server's validator (its strong ETag) is kept in
 *    `<path>.part.v` and sent as If-Range, so a part of another version of the file is never continued or mixed (the
 *    server then answers 200 and the file starts over: the answer replaces the part, it is never appended to it).
 *    Which answer is the file ([AnswerRule]): a 200 with the manifest's length, or a 206 whose Content-Range is the
 *    range asked for, of a file of the manifest's size. Any other 2xx is a failed attempt, and a failed attempt never
 *    shrinks what the device holds: a 200 that replaces a part is written beside it (`<path>.part.new`) and takes its
 *    place only when it is whole.
 *    A request that the network cut (no answer, a connection that ended, no byte for 30 s) or that the server failed
 *    (5xx) is made again inside the call from what the device holds, at most five times per file ([RetryPlan]).
 * 3. The part's size and SHA-256 are checked against the entry; a gzip entry is inflated
 *    (java.util.zip) to `<decoded>.tmp` and its size and SHA-256 checked against `decoded`; then the
 *    file is moved into place atomically and the part deleted. Any mismatch deletes the part, the
 *    validator, the map and the inflated file, and fails with [RecognizerException.DownloadFailed].
 *
 * One download per file at a time in the process (other callers for the same file wait and then find it in place);
 * different files can download in parallel. Requests ask for `Accept-Encoding: identity` so the transport never
 * re-encodes the bytes whose hash is checked.
 */
internal class ModelDownloader @JvmOverloads constructor(
    val root: File,
    /** The folder URL the manifest's paths are relative to, ending in `/`. */
    val baseUrl: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val headers: HeaderProvider? = null,
    /** How a large file is fetched. */
    val ranges: RangePlan = RangePlan.DEFAULT,
    /** How a download that the network cut goes on inside the call. */
    private val retry: RetryPlan = RetryPlan.DEFAULT,
) {
    private val stamps = VerifiedStamps(File(root, VerifiedStamps.FILE_NAME))

    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end in /" }
    }

    /** The file the SDK keeps for [e]. */
    fun local(e: ModelManifest.FileEntry): File = File(root, e.localPath)

    /** Whether [e]'s kept file is in place with its size and SHA-256 (see [VerifiedStamps]). */
    fun isInPlace(e: ModelManifest.FileEntry): Boolean = stamps.verified(local(e), e.localSize, e.localSha256)

    /** A file the SDK made itself and checked (a program rebuilt from a delta): its size and SHA-256 are kept next to it
     *  (`<file>.target`), so it is known to be in place like a downloaded file. */
    fun recordBuilt(f: File, size: Long, sha256: String) {
        File(f.path + ".target").writeText("$size ${sha256.lowercase()}\n")
        stamps.record(f, size, sha256)
    }

    /** Whether a file [recordBuilt] recorded is still in place with those bytes. */
    fun isBuiltInPlace(f: File): Boolean {
        val t = File(f.path + ".target").takeIf { it.isFile }?.readText()?.trim()?.split(' ') ?: return false
        val size = t.getOrNull(0)?.toLongOrNull() ?: return false
        val sha = t.getOrNull(1) ?: return false
        return stamps.verified(f, size, sha)
    }

    /**
     * Makes sure [e] is in place, downloading what is missing. [onProgress] gets stored bytes so far.
     * Returns the result (also sent to [SdkObserver.onDownloadFinished]). Throws
     * [RecognizerException.DownloadFailed] (also when cancelled) after deleting anything unverified.
     */
    @JvmOverloads
    fun fetch(
        e: ModelManifest.FileEntry,
        cancel: Cancellation? = null,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        revision: String = "",
    ): DownloadResult {
        if (!ManifestVerifier.safePath(e.path) || !ManifestVerifier.safePath(e.localPath)) {
            throw RecognizerException.ManifestInvalid("bad path ${e.path}")
        }
        // one download per file in the process: two stores of one folder (a Client's, a worker's) never write one part
        val lock = LOCKS.computeIfAbsent(File(root, e.localPath).absolutePath) { Any() }
        synchronized(lock) {
            val t0 = System.nanoTime()
            if (isInPlace(e)) return DownloadResult(e.path, e.role, 0, e.size, 0, true, revision = revision)
            val stats = FetchStats()
            val result = try {
                download(e, cancel, onProgress, t0, stats).copy(revision = revision)
            } catch (x: TajweedError) {
                val why = (x as? RecognizerException.DownloadFailed)?.reason ?: x.code
                val r = DownloadResult(e.path, e.role, stats.network.get(), e.size, stats.resumedFrom, false, why,
                    (System.nanoTime() - t0) / 1_000_000, revision, stats.ranged, stats.protocols())
                SdkEvents.emit { it.onDownloadFinished(r) }
                throw x
            }
            SdkEvents.emit { it.onDownloadFinished(result) }
            return result
        }
    }

    private fun fail(e: ModelManifest.FileEntry, why: String, cause: Throwable? = null): Nothing =
        throw RecognizerException.DownloadFailed(e.path, why, cause)

    private fun download(e: ModelManifest.FileEntry, cancel: Cancellation?, onProgress: ((DownloadProgress) -> Unit)?, t0: Long,
                         stats: FetchStats): DownloadResult {
        val part = File(root, e.path + ".part")
        val validator = File(root, e.path + ".part.v")
        val map = File(root, e.path + RangedFetch.MAP_SUFFIX)
        part.parentFile?.mkdirs()
        val progress = FileProgress(e, onProgress)
        // the file's resumes in this call ([RetryPlan]): the ranges and the one request count on the same five
        val resumes = Resumes(retry)
        if (e.size > ranges.threshold) {
            val parallel = if (transport.separateConnections) ranges.parallel else 1
            RangedFetch(e, part, validator, map, transport, headers, baseUrl + e.path, ranges, parallel, cancel, progress, stats, resumes) { given, whole ->
                // one stream instead of ranges. [whole]: the whole file, and what the device holds stays until it is
                // there; else the device holds one stream's prefix without a validator, and the stream goes on from it
                single(e, part, validator, cancel, progress, stats, given, resumes, whole = whole)
            }.run()
        } else {
            // a part written in ranges (another plan, another SDK) is not continued as one stream
            if (map.exists()) RangedFetch.wipe(part, validator, map)
            single(e, part, validator, cancel, progress, stats, null, resumes, whole = false)
        }
        progress.end(part.length())
        if (part.length() < e.size) fail(e, "the connection ended at ${part.length()} of ${e.size} bytes (the part is kept for a resume)")
        val (size, sha) = ModelManifest.digest(part)
        if (size != e.size || sha != e.sha256.lowercase()) {
            RangedFetch.wipe(part, validator, map)
            fail(e, "the stored bytes do not match the manifest (size $size, sha256 $sha)")
        }
        val dst = local(e)
        dst.parentFile?.mkdirs()
        val d = e.decoded
        if (d == null) {
            move(part, dst)
        } else {
            val tmp = File(dst.path + ".tmp")
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            try {
                GZIPInputStream(part.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val k = input.read(buf)
                            if (k < 0) break
                            n += k
                            if (n > d.size) break
                            md.update(buf, 0, k)
                            out.write(buf, 0, k)
                        }
                    }
                }
            } catch (x: IOException) {
                tmp.delete(); RangedFetch.wipe(part, validator, map)
                fail(e, "gzip: ${x.message}", x)
            }
            val dsha = ModelManifest.hex(md.digest())
            if (n != d.size || dsha != d.sha256.lowercase()) {
                tmp.delete(); RangedFetch.wipe(part, validator, map)
                fail(e, "the decoded file does not match the manifest (size $n, sha256 $dsha)")
            }
            move(tmp, dst)
            part.delete()
        }
        validator.delete()
        map.delete()
        stamps.record(dst, e.localSize, e.localSha256)
        return DownloadResult(e.path, e.role, stats.network.get(), e.size, stats.resumedFrom, true, null,
            (System.nanoTime() - t0) / 1_000_000, ranged = stats.ranged, protocols = stats.protocols())
    }

    /**
     * The stored bytes in one stream (3.1.2's way, and a server that does not answer ranges), by the retry rule
     * ([RetryPlan]): when the request gets no answer, its connection ends or stalls, or the server fails, the next
     * request goes on from what the part holds then, after the rule's pause, while the file has resumes left; then the
     * failure is the call's and the part stays for the next call. An answer that is not the file ([AnswerRule]) is
     * such a failed attempt too, and leaves the part as it was.
     *
     * [whole]: the file is asked for whole, without a Range, whatever the device holds of it (the server gave no
     * validator, or the file there is another one now); [given]: the answer of a range request that the server
     * answered with the whole file (200), used instead of asking again.
     */
    private fun single(e: ModelManifest.FileEntry, part: File, validator: File, cancel: Cancellation?, progress: FileProgress,
                       stats: FetchStats, given: HttpResponse?, resumes: Resumes, whole: Boolean) {
        var answer = given
        var first = true
        var anew = whole
        while (true) {
            val g = resumes.generation
            // a whole answer with nothing on the device goes into the part itself: what it leaves is continued by Range
            val fresh = !part.exists()
            val failure = try {
                once(e, part, validator, cancel, progress, stats, answer, first, anew)
                if (part.isFile && part.length() == e.size) return
                // the answer ended before the file did, without an error of the transport
                RecognizerException.DownloadFailed(e.path, "network: the connection ended at ${part.length()} of ${e.size} bytes (the part is kept for a resume)",
                    IOException("the answer ended at byte ${part.length()}"))
            } catch (x: RecognizerException.DownloadFailed) {
                // a request that the cancel ended failed like a connection that dropped: it is the cancel, not the network
                if (cancel?.isCancelled == true) fail(e, "cancelled")
                if (!RetryPlan.resumable(x)) throw x
                x
            } finally {
                answer?.close()
            }
            answer = null
            first = false
            if (fresh) anew = false
            val more = try {
                resumes.again(g) { if (cancel?.isCancelled == true || Thread.currentThread().isInterrupted) fail(e, "cancelled") }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(e, "cancelled")
            }
            if (!more) throw failure
        }
    }

    /**
     * One request for the stored bytes. Without [whole]: Range from what the part holds, with If-Range when the answer
     * that started the part gave a strong ETag (without one, the Range alone; a part longer than the file cannot be a
     * prefix of it and goes before the request). The answer is the file only by [AnswerRule]:
     *
     * - 206: the range asked for, of a file of the manifest's size and of the validator the part came with: appended,
     *   and never more bytes than the range has.
     * - 200 with the manifest's length: the whole file. With nothing on the device it is written into the part. With a
     *   part on the device (the server does not answer ranges, or the file is another one now) it is written beside it
     *   (`<path>.part.new`) and takes the part's place when every byte of it is there; an answer that ends early, or
     *   runs past the file's size, leaves the part and its validator exactly as they were.
     * - 416: the stored bytes run past the server's file. The part and its validator are removed and the call ends.
     * - anything else is a failed attempt: it changes nothing on the device and counts against the file's resumes.
     *
     * [first]: the call's first request for the file (what the part holds then is what an earlier call left).
     */
    private fun once(e: ModelManifest.FileEntry, part: File, validator: File, cancel: Cancellation?, progress: FileProgress,
                     stats: FetchStats, given: HttpResponse?, first: Boolean, whole: Boolean) {
        val anew = whole || given != null
        var start = 0L
        if (!anew) {
            start = if (part.isFile) part.length() else 0L
            if (start > e.size) {
                // more bytes than the file has: it cannot be a prefix of the file (SPEC 16.1), and goes before the request
                part.delete()
                validator.delete()
                start = 0
            }
            if (start == 0L) validator.delete()
            if (first) stats.resumedFrom = start
            progress.start(start)
            if (start >= e.size) return
        }
        // the strong ETag of the answer that started the part; a part without one is resumed with the Range alone
        val stored = if (start > 0) try { validator.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } } catch (_: IOException) { null } else null
        val r = given ?: run {
            val h = HashMap<String, String>()
            h["Accept-Encoding"] = "identity"
            if (start > 0) {
                h["Range"] = "bytes=$start-"
                stored?.let { h["If-Range"] = it }
            }
            modelRequest(transport, baseUrl + e.path, e.path, h, headers, if (start > 0) setOf(200, 206, 416) else setOf(200), cancel)
        }
        stats.answer(r)
        // while the body is read, a cancel ends the answer: the read fails at once
        val reading = cancel?.whenCancelled { r.abort() }
        try {
            answered(e, part, validator, cancel, progress, stats, r, start, stored)
        } finally {
            reading?.close()
        }
    }

    /** What [once] does with the answer [r] to its request (a resume from [start] under the validator [stored]). */
    private fun answered(e: ModelManifest.FileEntry, part: File, validator: File, cancel: Cancellation?, progress: FileProgress,
                         stats: FetchStats, r: HttpResponse, start: Long, stored: String?) {
        r.use { resp ->
            fun wrong(what: String): Nothing = throw AnswerRule.failed(e.path, what)
            val v = AnswerRule.validator(resp)
            when (resp.code) {
                416 -> {
                    // the stored bytes run past the server's file: of what removes a one-stream part (SPEC 16.1), this is
                    // the one that an answer gives. The call ends; the next one starts the file again
                    part.delete()
                    validator.delete()
                    fail(e, "HTTP 416 for a resume at byte $start of ${e.size}: the partial file was removed")
                }
                206 -> {
                    AnswerRule.range(resp, start, e.size - 1, e.size)?.let { wrong(it) }
                    if (v != null && stored != null && v != stored) wrong("a range of another version of the file")
                    try {
                        FileOutputStream(part, true).use { out -> copy(e, resp, out, e.size - start, cancel, progress, stats) }
                    } catch (x: IOException) {
                        // what arrived is the file's: the part stays for a resume
                        fail(e, "network: ${x.message}", x)
                    }
                }
                200 -> {
                    AnswerRule.whole(resp, e.size)?.let { wrong(it) }
                    // beside the part when there is one: it is replaced only by a whole file
                    val beside = part.exists()
                    val target = if (beside) File(part.path + NEW_SUFFIX) else part
                    if (!beside) {
                        if (v != null) validator.writeText(v) else validator.delete()
                    }
                    progress.start(0)
                    var more = false
                    val got = try {
                        FileOutputStream(target, false).use { out ->
                            val n = copy(e, resp, out, e.size, cancel, progress, stats)
                            // a body that runs past the file's size is cut off, and the attempt failed
                            if (n == e.size) more = try { resp.body.read() >= 0 } catch (_: IOException) { false }
                            n
                        }
                    } catch (x: IOException) {
                        if (beside) target.delete()
                        fail(e, "network: ${x.message}", x)
                    } catch (x: TajweedError) {
                        if (beside) target.delete()
                        throw x
                    }
                    if (more) {
                        target.delete()
                        if (!beside) validator.delete()
                        wrong("a 200 with more than the file's ${e.size} bytes")
                    }
                    if (beside) {
                        if (got < e.size) {
                            target.delete()
                            fail(e, "network: the connection ended at $got of ${e.size} bytes", IOException("the answer ended at byte $got"))
                        }
                        // whole: it takes the part's place, then its validator; the old part's map (ranges) goes last
                        move(target, part)
                        if (v != null) validator.writeText(v) else validator.delete()
                        File(part.path + ".ranges").delete()
                        stats.resumedFrom = 0
                    }
                }
                else -> wrong("HTTP ${resp.code}")
            }
            Unit
        }
    }

    /**
     * Copies at most [most] bytes of the answer's body to [out], counting them; returns how many came. Throws
     * IOException when the transport fails, `download_failed` "cancelled" when the call was cancelled.
     */
    private fun copy(e: ModelManifest.FileEntry, resp: HttpResponse, out: FileOutputStream, most: Long, cancel: Cancellation?,
                     progress: FileProgress, stats: FetchStats): Long {
        val buf = ByteArray(BUFFER)
        var have = 0L
        while (have < most) {
            if (cancel?.isCancelled == true || Thread.currentThread().isInterrupted) fail(e, "cancelled")
            val n = resp.body.read(buf, 0, minOf(buf.size.toLong(), most - have).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            stats.bytes(resp, n)
            progress.add(n.toLong())
        }
        return have
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val PROGRESS_STEP = 256L shl 10

        /** The buffer a download reads into: one per stream, the most of a file any stream holds in memory. */
        const val BUFFER = 64 shl 10

        /** A whole answer that replaces a part is written beside it under this suffix, and renamed when it is whole. */
        const val NEW_SUFFIX = ".new"

        /** One lock per kept file in the process. */
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

/**
 * Remembers files whose SHA-256 was checked (path, size, modification time, hash) in one small file,
 * so opening a model does not hash 74 MB again on every start: a file is re-hashed when its size or
 * modification time changed or it has no record. Writes are atomic (temporary file, then rename).
 */
internal class VerifiedStamps(private val file: File) {
    private val lock = Any()

    fun verified(f: File, size: Long, sha256: String): Boolean {
        if (!f.isFile || f.length() != size) return false
        synchronized(lock) {
            val rec = load()[key(f)]
            if (rec != null && rec.size == size && rec.mtime == f.lastModified() && rec.sha == sha256.lowercase()) return true
        }
        val (s, h) = ModelManifest.digest(f)
        if (s != size || h != sha256.lowercase()) return false
        record(f, size, h)
        return true
    }

    fun record(f: File, size: Long, sha256: String) = synchronized(lock) {
        val all = HashMap(load())
        all[key(f)] = Rec(size, f.lastModified(), sha256.lowercase())
        val tmp = File(file.path + ".tmp")
        file.parentFile?.mkdirs()
        tmp.writeText(Json.write(all.mapValues { (_, r) -> mapOf("size" to r.size, "mtime" to r.mtime, "sha256" to r.sha) }.toSortedMap()))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun forget() = synchronized(lock) { file.delete() }

    private data class Rec(val size: Long, val mtime: Long, val sha: String)

    private fun key(f: File) = f.absoluteFile.invariantSeparatorsPath

    private fun load(): Map<String, Rec> {
        if (!file.isFile) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(file.readText()) as Map<String, Any?>
            m.mapValues { (_, v) ->
                val o = v as Map<String, Any?>
                Rec((o["size"] as Double).toLong(), (o["mtime"] as Double).toLong(), o["sha256"] as String)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val FILE_NAME = ".verified.json"
    }
}
