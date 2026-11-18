package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract section 24 (`sdk_4.0.0/ERRORS_40.md`), the vectors `tools/errors40.py` makes (`vectors40/errors_40.json`, the
 * contract's file byte for byte at f209443; theCopyIsTheContracts compares it when the contract is on the
 * machine): every `codes` case (the code the app gets, and whether it is reported), every `queue` sequence on the fake
 * clock (the reports each send point makes and their fields), each `telemetry` reason's `failure_reasons` name, and the
 * `signatures`. The body this SDK builds is held to the accepted Android body of `bodies`.
 */
@Suppress("UNCHECKED_CAST")
class ErrorReports40VectorsTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val v: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors40/errors_40.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    private fun list(key: String) = v[key] as List<Map<String, Any?>>
    private fun int(x: Any?): Int? = (x as? Number)?.toInt()

    /** The copy is the contract's file byte for byte, when the contract is on this machine (TJ_VECTORS40_DIR =
     *  sdk_4.0.0/vectors at f209443). */
    @Test
    fun theCopyIsTheContracts() {
        val dir = System.getProperty("TJ_VECTORS40_DIR") ?: System.getenv("TJ_VECTORS40_DIR") ?: return
        val mine = javaClass.getResourceAsStream("/vectors40/errors_40.json")!!.use { it.readBytes() }
        org.junit.Assert.assertArrayEquals(File(dir, "errors_40.json").readBytes(), mine)
    }

    @Test
    fun theStagesAndLimitsAreTheContracts() {
        assertEquals(v["stages"], ErrorRules.STAGES)
        assertEquals((v["codes_not_reported"] as List<String>).toSet(), ErrorRules.CODES_NOT_REPORTED)
        val l = v["limits"] as Map<String, Any?>
        assertEquals(listOf(ErrorRules.DAILY_SIGNATURES, ErrorRules.QUEUE_MAX, ErrorRules.REPORTED_MAX, ErrorRules.FIRST_AGAIN_DAYS,
            ErrorRules.KEEP_DAYS, ErrorRules.COUNT_MAX),
            listOf("daily_signatures", "queue_max", "reported_max", "first_again_days", "keep_days", "count_max").map { int(l[it]) })
    }

    /** Every `codes` case (f209443: 61, the 12 after the 49 for the token and the lease); this SDK has no lease (4.0's
     *  protected model), so its cases are not met here. */
    @Test
    fun everyCodeCase() {
        val cases = list("codes")
        assertEquals(61, cases.size)
        for (c in cases.filter { it["stage"] != "lease" }) {
            val stage = c["stage"] as String
            val reason = c["reason"] as String
            val got = ErrorRules.codeFor(stage, reason, int(c["http_status"]), c["on_device"] as String, c["error_code"] as String?)
            assertEquals("$c", c["code"], got)
            assertEquals("$c", c["reported"], ErrorRules.reported(stage, reason, got))
        }
    }

    @Test
    fun everySignature() {
        for (s in list("signatures")) {
            val args = listOf(s["stage"] as String, s["reason"] as String, s["code"] as String)
            assertEquals(s["string"], ErrorRules.signature(args[0], args[1], args[2], int(s["http_status"])))
            assertEquals(s["hex"], ErrorRules.signatureHex(args[0], args[1], args[2], int(s["http_status"])))
        }
    }

    @Test
    fun everyTelemetryReason() {
        val cases = list("telemetry")
        assertEquals(13, cases.size)
        for (t in cases) assertEquals("$t", t["failure_reason"], ErrorRules.telemetryReason(t["reason"] as String))
        // the names are the server's (qlab-api events2.js DOWNLOAD_FAILURES), in its order
        assertEquals(listOf("network", "http", "hash_mismatch", "signature", "storage", "other"), DownloadCounts.FAILURE_REASONS)
    }

    @Test
    fun theStorageBuckets() {
        val web = v["web"] as Map<String, Any?>
        for (s in web["storage_free"] as List<Map<String, Any?>>) {
            assertEquals("$s", s["storage_free"], ErrorRules.storageFree((s["bytes"] as Number).toLong()))
        }
    }

    /** Every `queue` sequence: occurrences, send points with their answers in order, `flags.issues` off. */
    @Test
    fun everyQueueSequence() {
        val cases = list("queue")
        assertEquals(15, cases.size)
        for (case in cases) replay(case, ErrorReporter(null))
    }

    /** The same sequences with the state kept on disk and read again by a new reporter before every step. */
    @Test
    fun everyQueueSequenceAcrossRestarts() {
        for (case in list("queue")) {
            val dir = Files.createTempDirectory("tj-errors-").toFile()
            try {
                replay(case, null, dir)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun replay(case: Map<String, Any?>, inMemory: ErrorReporter?, dir: File? = null) {
        val name = case["name"]
        var off = false
        for ((i, step) in (case["steps"] as List<Map<String, Any?>>).withIndex()) {
            val r = inMemory ?: ErrorReporter(dir).also { if (off) it.off() }
            val expect = step["expect"] as Map<String, Any?>
            when (step["op"]) {
                "occur" -> {
                    val made = r.occur(step["day"] as String, step["stage"] as String, step["reason"] as String, step["code"] as String,
                        int(step["http_status"]), step["first_launch"] == true)
                    assertEquals("$name, step $i", expect["made"], made)
                }
                "send" -> {
                    val answers = (step["answers"] as List<Any?>).map { int(it) }
                    var k = 0
                    val made = r.send(step["day"] as String, answers.size) { answers[k++] }
                    val got = made.map { (q, a) ->
                        mapOf("day" to q.day, "stage" to q.stage, "reason" to q.reason, "code" to q.code, "http_status" to q.httpStatus,
                            "count" to q.count, "first_report" to q.firstReport, "first_launch" to q.firstLaunch, "answer" to a)
                    }
                    val want = (expect["requests"] as List<Map<String, Any?>>).map { m -> m.mapValues { (_, x) -> if (x is Double) x.toInt() else x } }
                    assertEquals("$name, step $i", want, got)
                }
                "issues_off" -> {
                    r.off()
                    off = true
                }
                else -> throw AssertionError("unknown op ${step["op"]}")
            }
        }
    }

    /** The body this SDK sends is the accepted Android body of the vectors, with the count and first_report it always has. */
    @Test
    fun theAndroidBodyIsOneTheServerAccepts() {
        val body = list("bodies").first { it["name"] == "Android, native fields" }
        assertEquals(true, body["accepted"])
        val want = body["body"] as Map<String, Any?>
        val e = want["error"] as Map<String, Any?>
        val r = ErrorReporter(null)
        assertTrue(r.occur("2026-11-18", "download", "quota", "storage_full", null, false, linkedMapOf("error_name" to "java.io.IOException",
            "revision" to "4.0.0", "device_model" to "SM-A155F", "ram_tier" to "2_4", "storage_free" to "lt_100m", "abi" to "armeabi-v7a")))
        val got = r.body(r.queued().single(), AppIdentity("org.example.recite", "2.4.0"), DeviceProfile("phone", null, 14, "14", "2_4"), "3.1.6")
        val gotError = got["error"] as Map<String, Any?>
        for ((k, x) in want) if (k != "error") assertEquals(k, x, got[k])
        assertEquals(want.keys, got.keys)
        for ((k, x) in e) assertEquals("error.$k", x, gotError[k])
        assertEquals(e.keys + setOf("count", "first_report"), gotError.keys)
        assertEquals(1, gotError["count"])
        assertEquals(true, gotError["first_report"])
        // never a message, frames or detail on kind error, and no value of an identifier, a path or a URL
        for (k in listOf("message", "frames", "detail")) assertTrue(k, k !in got)
        assertTrue(Json.write(got).let { !it.contains("://") && !it.contains("\\/") })
    }

    /** A value that is not a token is left out, never cut into one (24.2). */
    @Test
    fun aValueThatIsNotATokenIsLeftOut() {
        val r = ErrorReporter(null)
        r.occur("2026-11-18", "runtime", "memory", "engine_failed", null, false, mapOf("error_name" to "could not allocate",
            "error_code" to "/data/x", "device_model" to "Ali's phone", "build" to "abc", "abi" to "mips"))
        val e = r.body(r.queued().single(), AppIdentity("a.b", "1.0"), DeviceProfile("phone", null, 14, "14"), "4.0.0")["error"] as Map<String, Any?>
        assertEquals(setOf("stage", "reason", "count", "first_launch", "first_report"), e.keys)
    }
}
