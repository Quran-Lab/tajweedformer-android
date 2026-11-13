package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.OpenChecked
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections

/**
 * No request of the SDK follows a redirect (SPEC 15.4, contract 1.17.0), on the SDK's real HTTP code
 * (`HttpURLConnection`, [UrlConnectionTransport]) and not on a stand-in for it: a first local server answers every
 * request with a redirect (302, then 307) to a second local server, which must never be asked. The calls: the one
 * that carries the key in a header (attest), calls that carry the token (config, register, telemetry, issues), a
 * sharing start, the URLs and the takes of a recording, and a segment's upload to its storage URL. Each is asked
 * once and fails as that call fails on any refusal.
 *
 * The model's requests have the same test on the model's transport
 * ([ModelFromServerOnlyTest.aModelRequestIsNeverRedirected], through the Client, for a whole file and for a range).
 * Android's API calls never followed a redirect in any release, so this class cannot be shown red on a released
 * version; the model test was red on 3.0.1.
 */
class NoRedirectTest : OpenChecked() {
    private lateinit var first: HttpServer
    private lateinit var second: HttpServer
    private val atFirst = Collections.synchronizedList(ArrayList<String>())
    private val atSecond = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var status = 302
    private lateinit var dir: File

    @Before
    fun up() {
        dir = Files.createTempDirectory("tj-redirect-").toFile()
        second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        second.createContext("/") { ex ->
            // what would have travelled: recorded by name only
            atSecond.add("${ex.requestMethod} ${ex.requestURI.path} headers=${ex.requestHeaders.keys.map { it.lowercase() }.sorted()}")
            ex.requestBody.readBytes()
            val b = "{}".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        second.start()
        first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        first.createContext("/") { ex ->
            atFirst.add("${ex.requestMethod} ${ex.requestURI.path}")
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "http://127.0.0.1:${second.address.port}/elsewhere${ex.requestURI.path}")
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        first.start()
    }

    @After
    fun down() {
        first.stop(0)
        second.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun noCallOfTheApiFollowsARedirect() {
        val base = "http://127.0.0.1:${first.address.port}"
        val api = QuranLabApi(base, UrlConnectionTransport(5_000, 10_000))
        val key = QuranLabKey.parse("qlk_v1_testkey01_" + "A".repeat(22))!!
        val app = AppIdentity("org.example.recite", "3.1", "Recite")
        val token = "qlt_v1.test"
        val segment = File(dir, "segment.ogg").apply { writeBytes(ByteArray(4096) { 7 }) }
        val calls: List<Pair<String, () -> Any?>> = listOf(
            "POST /v1/attest" to { api.attest(key, app) },
            "GET /v1/attest/challenge" to { api.challenge(key) },
            "GET /v1/config" to { api.config(Credential.Token(token), 1, app, null) },
            "POST /v1/register" to { api.register(Credential.Token(token), app, "4.0.0", false) },
            "POST /v1/events" to { api.events(token, "{}".toByteArray()) },
            "POST /v1/issues" to { api.issue(token, mapOf("kind" to "other", "code" to "x")) },
            "POST /v1/uploads" to { api.uploadStart(token, "0123456789abcdef", "{}".toByteArray()) },
            "POST /v1/uploads/0123456789abcdef/urls" to { api.uploadUrls(token, "0123456789abcdef", 0, "c1") },
            "POST /v1/uploads/0123456789abcdef/takes" to { api.uploadTakes(token, "0123456789abcdef", "c1", "{}".toByteArray()) },
            "PUT /storage/segment" to { api.putSegment("$base/storage/segment", segment) },
        )
        for (code in listOf(302, 307)) {
            status = code
            for ((name, call) in calls) {
                atFirst.clear()
                // the call fails with its ordinary failure, or hands back the answer with its status: never a success
                val outcome = try {
                    (call() as? HttpResponse)?.status ?: "returned"
                } catch (e: CloudException) {
                    "failed"
                }
                assertTrue("$code to $name: $outcome", outcome == "failed" || outcome == code)
                assertEquals("$code to $name was asked once", listOf(name), atFirst.toList())
                assertEquals("$code to $name was followed to the second server", emptyList<String>(), atSecond.toList())
            }
        }
    }
}
