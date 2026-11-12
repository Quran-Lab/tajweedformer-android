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
 * (`HttpURLConnection`, no dependency); an app that has OkHttp or Cronet can pass its own.
 */
internal fun interface HttpTransport {
    /** A GET of [url] with [headers]. Throws IOException for a network failure. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** An HTTP answer: status, headers (names lower case), the body. Close it when done. */
internal class HttpResponse(
    val code: Int,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun close() {
        try { body.close() } catch (_: IOException) {}
        onClose()
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

    /** The error a request fails with for this answer (a refusal is remembered there), or null: a plain HTTP failure. */
    fun refusal(status: Int, body: ByteArray): TajweedError?

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
 * is `download_failed` with the status. No connection is `download_failed` with the IOException as its cause.
 */
internal fun modelRequest(transport: HttpTransport, url: String, path: String, extra: Map<String, String>, headers: HeaderProvider?, ok: Set<Int>): HttpResponse {
    val auth = headers as? ModelAuth
    var renewed = false
    while (true) {
        val h = HashMap<String, String>()
        headers?.headers(url)?.let { h.putAll(it) }
        h.putAll(extra)
        val r = try {
            permitted { transport.get(url, h) }
        } catch (x: IOException) {
            throw RecognizerException.DownloadFailed(path, "network: ${x.message}", x)
        }
        if (r.code in ok) {
            auth?.served()
            return r
        }
        val body = try { readSome(r.body, MAX_ERROR_BODY) } catch (_: IOException) { ByteArray(0) } finally { r.close() }
        if (auth != null && !renewed && auth.renewal(r.code, body)) {
            auth.renew()
            renewed = true
            continue
        }
        auth?.refusal(r.code, body)?.let { throw it }
        throw RecognizerException.DownloadFailed(path, "HTTP ${r.code}")
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
 *  15 s connect and 30 s read timeouts. */
internal class UrlConnectionTransport @JvmOverloads constructor(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val code = c.responseCode
        val h = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) h[k.lowercase()] = v.last()
        val body = if (code >= 400) (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))) else c.inputStream
        return HttpResponse(code, h, body) { c.disconnect() }
    }
}

/** Stops a download from another thread. */
internal class Cancellation {
    private val flag = AtomicBoolean(false)
    val isCancelled: Boolean get() = flag.get()
    fun cancel() = flag.set(true)
}

/**
 * Downloads manifest entries into a folder, resumably and verified (LAYOUT.md "Rules for every SDK"):
 *
 * 1. If the decoded (kept) file is already in place with its size and SHA-256, nothing is fetched.
 * 2. The stored bytes go to `<path>.part` with HTTP Range from what is already there; the server's
 *    validator (ETag, else Last-Modified) is kept in `<path>.part.v` and sent as If-Range, so a part
 *    of another version of the file is never continued (the server then answers 200 and the part
 *    starts over).
 * 3. The part's size and SHA-256 are checked against the entry; a gzip entry is inflated
 *    (java.util.zip) to `<decoded>.tmp` and its size and SHA-256 checked against `decoded`; then the
 *    file is moved into place atomically and the part deleted. Any mismatch deletes the part, the
 *    validator and the inflated file, and fails with [RecognizerException.DownloadFailed].
 *
 * One download per file at a time (other callers for the same file wait and then find it in
 * place); different files can download in parallel. Requests ask for `Accept-Encoding: identity`
 * so the transport never re-encodes the bytes whose hash is checked.
 */
internal class ModelDownloader @JvmOverloads constructor(
    val root: File,
    /** The folder URL the manifest's paths are relative to, ending in `/`. */
    val baseUrl: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val headers: HeaderProvider? = null,
) {
    private val locks = ConcurrentHashMap<String, Any>()
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
        val lock = locks.computeIfAbsent(e.localPath) { Any() }
        synchronized(lock) {
            val t0 = System.nanoTime()
            if (isInPlace(e)) return DownloadResult(e.path, e.role, 0, e.size, 0, true, revision = revision)
            val result = try {
                download(e, cancel, onProgress, t0).copy(revision = revision)
            } catch (x: TajweedError) {
                val why = (x as? RecognizerException.DownloadFailed)?.reason ?: x.code
                val r = DownloadResult(e.path, e.role, 0, e.size, 0, false, why, (System.nanoTime() - t0) / 1_000_000, revision)
                SdkEvents.emit { it.onDownloadFinished(r) }
                throw x
            }
            SdkEvents.emit { it.onDownloadFinished(result) }
            return result
        }
    }

    private fun fail(e: ModelManifest.FileEntry, why: String, cause: Throwable? = null): Nothing =
        throw RecognizerException.DownloadFailed(e.path, why, cause)

    private fun download(e: ModelManifest.FileEntry, cancel: Cancellation?, onProgress: ((DownloadProgress) -> Unit)?, t0: Long): DownloadResult {
        val part = File(root, e.path + ".part")
        val validator = File(root, e.path + ".part.v")
        part.parentFile?.mkdirs()
        var start = if (part.isFile && validator.isFile) part.length() else 0L
        if (start == 0L || start > e.size) {
            part.delete()
            validator.delete()
            start = 0
        }
        val resumedFrom = start
        var network = 0L
        if (start < e.size) {
            val h = HashMap<String, String>()
            h["Accept-Encoding"] = "identity"
            if (start > 0) {
                h["Range"] = "bytes=$start-"
                h["If-Range"] = validator.readText().trim()
            }
            val r = modelRequest(transport, baseUrl + e.path, e.path, h, headers, setOf(200, 206, 416))
            r.use { resp ->
                val append = when (resp.code) {
                    206 -> {
                        val cr = resp.header("content-range") ?: ""
                        val m = Regex("bytes\\s+(\\d+)-").find(cr)
                        if (m == null || m.groupValues[1].toLong() != start) fail(e, "the server resumed at the wrong place ($cr)")
                        true
                    }
                    200 -> {
                        start = 0
                        false
                    }
                    416 -> {
                        // the part may already hold every byte; otherwise start over next time
                        if (start != e.size) {
                            part.delete()
                            validator.delete()
                            fail(e, "HTTP 416 for a resume at $start")
                        }
                        true
                    }
                    else -> fail(e, "HTTP ${resp.code}")
                }
                val v = resp.header("etag") ?: resp.header("last-modified")
                if (v != null && v.isNotBlank()) validator.writeText(v) else validator.delete()
                if (resp.code != 416) {
                    try {
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(64 shl 10)
                            var have = start
                            var lastReport = have
                            while (true) {
                                if (cancel?.isCancelled == true || Thread.currentThread().isInterrupted) fail(e, "cancelled")
                                val n = resp.body.read(buf)
                                if (n < 0) break
                                if (have + n > e.size) {
                                    out.close()
                                    part.delete()
                                    validator.delete()
                                    fail(e, "the server sent more than ${e.size} bytes")
                                }
                                out.write(buf, 0, n)
                                have += n
                                network += n
                                if (have - lastReport >= PROGRESS_STEP) {
                                    lastReport = have
                                    val p = DownloadProgress(e.path, e.role, have, e.size)
                                    onProgress?.invoke(p)
                                    SdkEvents.emit { it.onDownloadProgress(p) }
                                }
                            }
                        }
                    } catch (x: IOException) {
                        // the part stays for a resume
                        fail(e, "network: ${x.message}", x)
                    }
                }
            }
        }
        val p = DownloadProgress(e.path, e.role, part.length(), e.size)
        onProgress?.invoke(p)
        SdkEvents.emit { it.onDownloadProgress(p) }
        if (part.length() < e.size) fail(e, "the connection ended at ${part.length()} of ${e.size} bytes (the part is kept for a resume)")
        val (size, sha) = ModelManifest.digest(part)
        if (size != e.size || sha != e.sha256.lowercase()) {
            part.delete()
            validator.delete()
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
                tmp.delete(); part.delete(); validator.delete()
                fail(e, "gzip: ${x.message}", x)
            }
            val dsha = ModelManifest.hex(md.digest())
            if (n != d.size || dsha != d.sha256.lowercase()) {
                tmp.delete(); part.delete(); validator.delete()
                fail(e, "the decoded file does not match the manifest (size $n, sha256 $dsha)")
            }
            move(tmp, dst)
            part.delete()
        }
        validator.delete()
        stamps.record(dst, e.localSize, e.localSha256)
        return DownloadResult(e.path, e.role, network, e.size, resumedFrom, true, null, (System.nanoTime() - t0) / 1_000_000)
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
