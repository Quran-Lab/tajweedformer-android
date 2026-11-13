package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.TestKit
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A model build behind a local HTTP server on 127.0.0.1, at the address the build of the signed config gives
 * (`/<repo>/<build>/<folder>/<path>`), for the tests that go through the public Client. The stored folder is served
 * in place, never copied. A download can be continued as on the CDN: every answer has an ETag, and a `Range` request
 * whose `If-Range` is that ETag gets 206 with `Content-Range`. Every request is recorded with the bytes sent for it.
 *
 * Two things a test can do to a download: [cut] sends only the first bytes of an answer and then drops the
 * connection; [replace] serves other bytes for a path (a tampered file; the folder itself is never written to).
 */
internal class ServedModel private constructor(
    /** The stored folder being served (its `manifest.json` and files); a test may point it at another folder of the same build. */
    @Volatile var root: File,
    /** The folder this object made and deletes at [close]; null when it serves a folder that is not its own. */
    private val owned: File?,
) : AutoCloseable {
    /** One request: the path inside the folder, its `Range` and `If-Range`, the status answered, the body bytes sent,
     *  and every header it came with (names in lower case). */
    class Request(val path: String, val range: String?, val ifRange: String?, val status: Int, val sent: Long,
                  val headers: Map<String, String> = emptyMap()) {
        override fun toString(): String = "$path${range?.let { " [$it]" } ?: ""} -> $status, $sent bytes"
    }

    /** An answer a test gives instead of the file (the CDN's refusals, a redirect). */
    class Answer(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())

    /** For a path: how many bytes of the answer to send before dropping the connection; null: the whole answer. */
    @Volatile var cut: ((String) -> Long?)? = null

    /** For a path: the bytes to serve instead of the file's; null: the file. */
    @Volatile var replace: ((String) -> ByteArray?)? = null

    /** For a path and how many times it was asked for so far (1 for the first): the answer to give instead; null: the file. */
    @Volatile var answer: ((String, Int) -> Answer?)? = null

    /** Called when a request for a path arrives, before it is answered. */
    @Volatile var onRequest: ((String) -> Unit)? = null

    /** No connection: every request is dropped before an answer (the client sees an IOException), and not recorded. */
    @Volatile var down = false

    private val asked = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The build id: the first 40 hex of the SHA-256 of the manifest's bytes. */
    val build: String = MessageDigest.getInstance("SHA-256").digest(File(root, ModelManifest.FILE_NAME).readBytes())
        .joinToString("") { "%02x".format(it) }.take(40)

    private val prefix = "/$REPO/$build/$FOLDER/"
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** What `ClientPlatform.modelsUrl` is set to. */
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** The `recommended` entry of a signed config that names this build. */
    fun recommended(version: String = "4.0.0"): Map<String, Any?> = mapOf("model" to REPO, "model_id" to "v4.0", "folder" to FOLDER,
        "revision" to build, "version" to version, "source" to "cdn")

    /** The paths asked for (inside the folder), in order. */
    fun paths(): List<String> = synchronized(requests) { requests.map { it.path } }

    /** Body bytes sent for [path] over every request for it. */
    fun sent(path: String): Long = synchronized(requests) { requests.filter { it.path == path }.sumOf { it.sent } }

    init {
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/") { ex ->
            if (down) {
                ex.close()
                return@createContext
            }
            val path = ex.requestURI.path
            val rel = if (path.startsWith(prefix)) path.removePrefix(prefix) else null
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            var status = 404
            var sent = 0L
            var recorded = false
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.last() }
            try {
                rel?.let { r -> try { onRequest?.invoke(r) } catch (_: Exception) {} }
                val given = rel?.let { r -> answer?.invoke(r, asked.merge(r, 1) { a, b -> a + b }!!) }
                val other = rel?.let { replace?.invoke(it) }
                val file = rel?.let { File(root, it) }
                if (given != null) {
                    status = given.status
                    // recorded before the answer goes out: a client that reads a short answer can return, and its test
                    // read the requests, before this thread would reach the finally block
                    requests.add(Request(rel ?: path, range, ifRange, status, sent, headers))
                    recorded = true
                    val bytes = given.body.toByteArray(Charsets.UTF_8)
                    for ((k, v) in given.headers) ex.responseHeaders.add(k, v)
                    if (bytes.isNotEmpty()) ex.responseHeaders.add("content-type", "application/json")
                    ex.sendResponseHeaders(given.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
                } else if (rel == null || (other == null && file?.isFile != true)) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    val size = other?.size?.toLong() ?: file!!.length()
                    val etag = "\"$build-${rel.hashCode()}-$size\""
                    val asked = range?.let { Regex("^bytes=(\\d+)-$").find(it) }?.groupValues?.get(1)?.toLong()
                    // a range of another version of the file (another validator) is answered with the whole file
                    val from = if (asked != null && ifRange == etag) asked else 0L
                    ex.responseHeaders.add("ETag", etag)
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    if (from >= size && from > 0) {
                        status = 416
                        ex.responseHeaders.add("Content-Range", "bytes */$size")
                        ex.sendResponseHeaders(416, -1)
                    } else {
                        status = if (from > 0) 206 else 200
                        if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${size - 1}/$size")
                        val length = size - from
                        val stop = cut?.invoke(rel)?.coerceIn(0L, length) ?: length
                        ex.sendResponseHeaders(status, if (length == 0L) -1 else length)
                        if (length > 0) {
                            val out = ex.responseBody
                            (other?.inputStream() ?: file!!.inputStream()).use { input ->
                                var skip = from
                                while (skip > 0) skip -= input.skip(skip).also { if (it <= 0) throw IOException("cannot skip") }
                                val buf = ByteArray(32 shl 10)
                                while (sent < stop) {
                                    val n = input.read(buf, 0, minOf(buf.size.toLong(), stop - sent).toInt())
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    sent += n
                                }
                            }
                            out.flush()
                            // a cut: the exchange is closed with the body short, which closes the connection
                            if (stop >= length) out.close()
                        }
                    }
                }
            } catch (_: IOException) {
                // the client went away
            } finally {
                if (!recorded) requests.add(Request(rel ?: path, range, ifRange, status, sent, headers))
                ex.close()
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        owned?.deleteRecursively()
    }

    companion object {
        const val REPO = "Quran-Lab/tajweedformer-v4"
        const val FOLDER = "sdk/v4.0"

        /** A build made of [files] (the test kit's small ones by default), signed with the test kit's key, in a folder of its own. */
        fun of(files: List<TestKit.F> = TestKit.v4Files()): ServedModel {
            val dir = Files.createTempDirectory("tj-served-").toFile()
            TestKit.writeTransport(dir, files, TestKit.sign(TestKit.manifest(files)))
            return ServedModel(dir, dir)
        }

        /** A stored folder that already exists (a local model folder), served in place and left as it is. */
        fun ofFolder(dir: File): ServedModel = ServedModel(dir, null)
    }
}
