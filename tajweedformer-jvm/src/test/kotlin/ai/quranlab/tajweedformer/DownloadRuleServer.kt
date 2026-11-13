package ai.quranlab.tajweedformer

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for the model host in the download rule tests ([RetryRuleTest], [AnswerRuleTest]): a socket server that
 * serves the files of a folder with `Range`, `If-Range` and an ETag, and can do to any request what a network or a
 * faulty server does: end the connection in the middle of a body, stop sending without closing, send slowly, answer a
 * status with a JSON body, or give a 2xx that is not the answer asked for (the wrong length, the wrong
 * `Content-Range`, an error body under 200). Every answer closes its connection, and every request is recorded.
 */
internal class DownloadRuleServer(private val served: File) : AutoCloseable {
    /** One request: the path, its Range and If-Range, when it came (ms since the server started), its number among
     *  those for that path and Range, and the status answered. */
    class Req(val path: String, val range: String?, val ifRange: String?, val at: Long, val n: Int) {
        @Volatile var status = 0
        override fun toString() = "$path [$range if-range=$ifRange] #$n at $at ms -> $status"
    }

    /** What the server does with a request. */
    sealed class Do {
        /** The file, or the range asked for. */
        object Serve : Do()
        /** [after] bytes of the body, then the connection is closed. */
        class Cut(val after: Long) : Do()
        /** [after] bytes of the body, then the connection is closed at the moment when every answer that shares [gate]
         *  has sent its bytes: answers that end together, as when the link fails. */
        class CutTogether(val after: Long, val gate: java.util.concurrent.CyclicBarrier) : Do()
        /** [after] bytes of the body, then nothing for [ms] with the connection open. */
        class Stall(val after: Long, val ms: Long) : Do()
        /** The body in pieces of [chunk] bytes, one every [everyMs]. */
        class Drip(val chunk: Int, val everyMs: Long) : Do()
        /** A status with a JSON body instead of the file. */
        class Status(val code: Int, val body: String = "{}") : Do()
        /** The whole file with 200, whatever Range asked. */
        object Whole : Do()
        /** 200 with only the first [bytes] of the file, and a Content-Length that says so. */
        class ShortOk(val bytes: Long) : Do()
        /** 200 with the file and [extra] bytes after it, and a Content-Length that says so. */
        class LongOk(val extra: Int) : Do()
        /** 206 for the range asked, with its `Content-Range`, a Content-Length of 0 and no body. */
        object Empty206 : Do()
        /** 206 with the bytes asked for, whose `Content-Range` names a total [more] bytes larger than the file. */
        class WrongTotal206(val more: Long) : Do()
        /** 206 whose `Content-Range` (and bytes) start [shift] bytes after the byte asked for. */
        class WrongStart206(val shift: Long) : Do()
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedList(ArrayList<Socket>())
    private val t0 = System.nanoTime()
    private val asked = ConcurrentHashMap<String, Int>()

    val requests: MutableList<Req> = Collections.synchronizedList(ArrayList())
    /** The ETag of every file; empty: a server that gives no ETag at all. */
    @Volatile var etag = "\"v1\""
    /** For a path, its Range and how many times that path with that Range was asked: what to do. */
    @Volatile var plan: (String, String?, Int) -> Do = { _, _, _ -> Do.Serve }

    /** What `ModelStore` is given as the folder's URL. */
    val base: String get() = "http://127.0.0.1:${socket.localPort}/m/"

    /** Milliseconds since the server started (the clock of [Req.at]). */
    fun now() = (System.nanoTime() - t0) / 1_000_000

    /** The requests for [path], in the order they came. */
    fun of(path: String): List<Req> = synchronized(requests) { requests.filter { it.path == path } }

    init {
        Thread({
            while (true) {
                val s = try { socket.accept() } catch (_: IOException) { break }
                sockets.add(s)
                Thread({ try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: IOException) {} } }, "rule-test-conn").apply { isDaemon = true }.start()
            }
        }, "rule-test-server").apply { isDaemon = true }.start()
    }

    override fun close() {
        socket.close()
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: IOException) {} } }
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())
        val first = line(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val path = first.split(' ')[1].removePrefix("/m/")
        val range = headers["range"]
        val ifRange = headers["if-range"]
        val n = asked.merge("$path $range", 1) { a, b -> a + b }!!
        val req = Req(path, range, ifRange, now(), n)
        requests.add(req)
        val out = sock.getOutputStream()
        fun head(status: Int, length: Long, more: String = "") {
            req.status = status
            out.write(("HTTP/1.1 $status X\r\nContent-Length: $length\r\nConnection: close\r\n$more\r\n").toByteArray())
        }
        val act = plan(path, range, n)
        val f = File(served, path)
        if (act is Do.Status) {
            val b = if (act.code == 204) ByteArray(0) else act.body.toByteArray()
            head(act.code, b.size.toLong(), "Content-Type: application/json\r\n")
            out.write(b)
            out.flush()
            return
        }
        if (!f.isFile) {
            head(404, 0)
            out.flush()
            return
        }
        val all = f.readBytes()
        val tag = if (etag.isEmpty()) "" else "ETag: $etag\r\n"
        when (act) {
            is Do.ShortOk -> {
                head(200, act.bytes, tag)
                out.write(all, 0, act.bytes.toInt())
                out.flush()
                return
            }
            is Do.LongOk -> {
                head(200, all.size.toLong() + act.extra, tag)
                out.write(all)
                out.write(ByteArray(act.extra) { 0x5a })
                out.flush()
                return
            }
            else -> {}
        }
        val m = range?.let { Regex("^bytes=(\\d+)-(\\d*)$").find(it) }
        val ok = act !is Do.Whole && m != null && (ifRange == null || ifRange == etag)
        var from = 0
        var to = all.size - 1
        if (ok) {
            from = m!!.groupValues[1].toInt()
            m.groupValues[2].takeIf { it.isNotEmpty() }?.let { to = minOf(it.toInt(), all.size - 1) }
        }
        val length = to - from + 1
        if (ok && act is Do.Empty206) {
            head(206, 0, tag + "Content-Range: bytes $from-$to/${all.size}\r\n")
            out.flush()
            return
        }
        if (ok && act is Do.WrongTotal206) {
            head(206, length.toLong(), tag + "Content-Range: bytes $from-$to/${all.size + act.more}\r\n")
            out.write(all, from, length)
            out.flush()
            return
        }
        if (ok && act is Do.WrongStart206) {
            val s = (from + act.shift).toInt()
            head(206, (to - s + 1).toLong(), tag + "Content-Range: bytes $s-$to/${all.size}\r\n")
            out.write(all, s, to - s + 1)
            out.flush()
            return
        }
        head(if (ok) 206 else 200, length.toLong(), tag + (if (ok) "Content-Range: bytes $from-$to/${all.size}\r\n" else ""))
        when (act) {
            is Do.Cut -> {
                out.write(all, from, minOf(act.after, length.toLong()).toInt())
                out.flush()
            }
            is Do.CutTogether -> {
                out.write(all, from, minOf(act.after, length.toLong()).toInt())
                out.flush()
                // until the others are here too (at most 15 s: a test that does not bring them fails by its own checks)
                try { act.gate.await(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            }
            is Do.Stall -> {
                out.write(all, from, minOf(act.after, length.toLong()).toInt())
                out.flush()
                Thread.sleep(act.ms)
            }
            is Do.Drip -> {
                var sent = 0
                while (sent < length) {
                    val k = minOf(act.chunk, length - sent)
                    out.write(all, from + sent, k)
                    out.flush()
                    sent += k
                    if (sent < length) Thread.sleep(act.everyMs)
                }
            }
            else -> {
                out.write(all, from, length)
                out.flush()
            }
        }
    }
}
