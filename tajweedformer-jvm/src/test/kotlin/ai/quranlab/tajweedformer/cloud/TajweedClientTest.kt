package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.HeadValues
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
import ai.quranlab.tajweedformer.TajweedBackend
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedDiagnosis
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TestKit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The public Client against a stub of Quran Lab's server: one Client per process, the errors a first run can meet
 * (no connection, no model build for this SDK yet, a version that is not one), and `diagnose()`, whose JSON is
 * checked against the shared schema `vectors30/diagnosis.schema.json` in every state. The schema's own examples
 * (valid ones and invalid ones, each named by what is wrong with it) prove the checker used here.
 */
class TajweedClientTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private var open: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-client-").toFile()
    }

    @After
    fun down() {
        open?.close()
        stub.close()
        dir.deleteRecursively()
    }

    /** A Client that talks to the stub; the launch sequence is not run by itself, so each test decides what the server saw. */
    private fun client(key: String = stub.key, api: String = stub.base): TajweedClient =
        TajweedClient(key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir), apiUrl = api, trustedKeys = listOf(stub.publicKey)),
            autoLaunch = false).also { open = it }

    private fun code(body: () -> Unit): String? = try {
        body()
        null
    } catch (e: TajweedError) {
        assertTrue("${e.code} has no fix", e.fix.isNotBlank())
        assertEquals("https://sdk.quranlab.ai/docs/errors#${e.code}", e.docs)
        e.code
    }

    // ---- the schema of diagnose() ----

    @Suppress("UNCHECKED_CAST")
    private val schema: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors30/diagnosis.schema.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    /** The first reason [v] does not fit [s], or null. The JSON Schema keywords this schema uses, no more. */
    @Suppress("UNCHECKED_CAST")
    private fun wrong(v: Any?, s: Map<String, Any?>, at: String = "$"): String? {
        fun isType(t: String): Boolean = when (t) {
            "null" -> v == null
            "string" -> v is String
            "boolean" -> v is Boolean
            "object" -> v is Map<*, *>
            "array" -> v is List<*>
            "integer" -> v is Number && v.toDouble() == Math.rint(v.toDouble())
            "number" -> v is Number
            else -> false
        }
        fun same(a: Any?, b: Any?) = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
        s["type"]?.let { t -> if (!(if (t is List<*>) t.any { isType(it as String) } else isType(t as String))) return "$at is not $t" }
        (s["enum"] as? List<Any?>)?.let { e -> if (e.none { same(it, v) }) return "$at: $v is not one of $e" }
        if (s.containsKey("const") && !same(s["const"], v)) return "$at is not ${s["const"]}"
        (s["pattern"] as? String)?.let { p -> if (v is String && !Regex(p).containsMatchIn(v)) return "$at: $v does not match $p" }
        (s["minimum"] as? Number)?.let { m -> if (v is Number && v.toDouble() < m.toDouble()) return "$at: $v is below $m" }
        (s["anyOf"] as? List<Map<String, Any?>>)?.let { any -> if (any.all { wrong(v, it, at) != null }) return "$at fits none of its forms" }
        if (v is Map<*, *>) {
            val props = s["properties"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            for (r in s["required"] as? List<String> ?: emptyList()) if (!v.containsKey(r)) return "$at lacks $r"
            if (s["additionalProperties"] == false) v.keys.firstOrNull { it !in props }?.let { return "$at has the unknown field $it" }
            for ((k, p) in props) if (v.containsKey(k)) wrong(v[k], p, "$at.$k")?.let { return it }
            (s["if"] as? Map<String, Any?>)?.let { cond ->
                // a property the `if` names and the value lacks does not fail the condition
                val branch = (if (wrong(v, cond, at) == null) s["then"] else s["else"]) as? Map<String, Any?>
                branch?.let { b -> wrong(v, b, at)?.let { return it } }
            }
        }
        if (v is List<*>) (s["items"] as? Map<String, Any?>)?.let { item -> v.forEachIndexed { i, x -> wrong(x, item, "$at[$i]")?.let { return it } } }
        return null
    }

    /** The report as its JSON says it (what an issue would carry), checked against the schema. */
    private fun valid(d: TajweedDiagnosis): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val report = Json.parse(d.toJson()) as Map<String, Any?>
        assertNull("diagnose() does not fit the schema: ${d.toJson()}", wrong(report, schema))
        assertEquals("the eleven fields in their order", (schema["required"] as List<*>), report.keys.toList())
        return report
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theSchemaAndItsExamples() {
        val examples = Json.parse(javaClass.getResourceAsStream("/vectors30/diagnosis_examples.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        val good = examples["valid"] as List<Map<String, Any?>>
        val bad = examples["invalid"] as List<Map<String, Any?>>
        assertEquals(7, good.size)
        assertEquals(17, bad.size)
        for (e in good) assertNull("${e["name"]}", wrong(e["report"], schema))
        for (e in bad) assertTrue("${e["name"]} passed the schema", wrong(e["report"], schema) != null)
        // reports of this SDK's own type in the states a device can be in
        val storage = linkedMapOf<String, Any>("models" to listOf(linkedMapOf("version" to "4.0.0", "build" to "a".repeat(40), "bytes" to 143_482_211L)), "sharingQueueBytes" to 0L)
        val promoted = TajweedDiagnosis("3.0.0", "3.0.1", true, null, "fresh", "4.0.0", "a".repeat(40), true, "c16_l64",
            TajweedBackend(TajweedBackend.Kind.ACCELERATOR, "npu:tensor_g3", TajweedBackend.Stage.PROMOTED), "granted", storage, null, 0, "unmetered")
        assertEquals("promoted", (valid(promoted)["backend"] as Map<String, Any?>)["stage"])
        val refused = TajweedDiagnosis("3.0.0", null, false, "invalid_key", "none", null, null, false, null, null, "notAsked",
            linkedMapOf("models" to emptyList<Any>(), "sharingQueueBytes" to 0L), "invalid_key", 12, "none")
        assertEquals(12.0, valid(refused)["droppedChunks"])
        val demoted = TajweedDiagnosis("3.0.0", null, true, null, "stale", "4.0.0", "a".repeat(40), true, "c8_l64",
            TajweedBackend(TajweedBackend.Kind.CPU, "cpu", TajweedBackend.Stage.DEMOTED, "failover"), "denied", storage, "microphone_denied", 0, "metered")
        valid(demoted)
    }

    @Test
    fun oneClientPerProcess() {
        val first = client()
        assertEquals("client_exists", code { client() })
        // a key that is not one is refused before anything else and leaves no trace
        first.close()
        assertEquals("invalid_argument", code { client(key = "not a key") })
        val second = client()
        assertEquals("client_exists", code { client() })
        second.close()
        // close() twice is harmless
        second.close()
        client().close()
    }

    @Test
    fun diagnoseBeforeTheServerAnswered() = runBlocking {
        val c = client()
        val report = valid(c.diagnose())
        assertEquals(mapOf("version" to SdkInfo.VERSION, "latest" to null), report["sdk"])
        assertEquals(mapOf("accepted" to null, "reason" to null), report["key"])
        assertEquals("none", report["config"])
        assertNull(report["model"])
        assertNull(report["context"])
        assertNull(report["backend"])
        assertEquals("the desktop JVM has no microphone the SDK opens", "none", report["microphone"])
        assertEquals(mapOf("models" to emptyList<Any>(), "sharingQueueBytes" to 0.0), report["storage"])
        assertEquals(report["storage"], Json.parse(Json.write(c.storage)))
        assertNull(report["lastError"])
        assertEquals(0.0, report["droppedChunks"])
        assertEquals("a platform that cannot tell what its network is (the desktop JVM)", "unknown", report["connection"])
        assertTrue("diagnose() sent something", stub.seen.isEmpty())
    }

    /** The server offers this SDK no model build (true of production until a 3.0 build is published). */
    @Test
    fun noModelBuildForThisSdkYet() = runBlocking {
        stub.recommended = null
        val c = client()
        assertEquals("model_unavailable", code { runBlocking { c.model() } })
        assertEquals("model_unavailable", code { runBlocking { c.prefetch() } })
        val report = valid(c.diagnose())
        assertEquals(mapOf("accepted" to true, "reason" to null), report["key"])
        assertEquals("fresh", report["config"])
        assertNull(report["model"])
        assertEquals("model_unavailable", report["lastError"])
        // nothing was retried in a loop: a config request per call, no model request
        assertTrue("config requests: ${stub.seen("/v1/config").size}", stub.seen("/v1/config").size <= 3)
    }

    @Test
    fun aFirstRunWithoutAConnection() = runBlocking {
        stub.down = true
        val c = client()
        assertEquals("needs_connection", code { runBlocking { c.model() } })
        val report = valid(c.diagnose())
        assertEquals(mapOf("accepted" to null, "reason" to null), report["key"])
        assertEquals("none", report["config"])
        assertEquals("needs_connection", report["lastError"])
        // the connection is back and the server has no build for this SDK: the error changes, the client goes on
        stub.down = false
        stub.recommended = null
        assertEquals("model_unavailable", code { runBlocking { c.model() } })
        assertEquals("model_unavailable", valid(c.diagnose())["lastError"])
    }

    @Test
    fun aVersionThatIsNotOneIsRefusedAndNeverSent() = runBlocking {
        val c = client()
        for (bad in listOf("4.0", "v4.0.0", "latest", "4.0.0-rc1", "")) {
            assertEquals(bad, "invalid_argument", code { runBlocking { c.model(bad) } })
        }
        assertTrue("a pin that is not a version reached the server", stub.seen.none { it.q("model_version") != null })
        assertEquals("invalid_argument", valid(c.diagnose())["lastError"])
        // the app's own counts are validated whether or not anything is sent
        assertEquals("invalid_argument", code { c.analytics.reportChecks("Madd", 1, 0) })
        assertNull(code { c.analytics.reportChecks("madd", 12, 3) })
        c.close()
        assertEquals("invalid_argument", code { runBlocking { c.model() } })
        try {
            c.diagnose()
        } catch (e: Throwable) {
            fail("diagnose() threw after close: $e")
        }
        Unit
    }

    /**
     * A key the server refused, in the three ways a server refuses: a key it does not know (`invalid_key`), an app it
     * blocked (`server_rejected`) and an SDK it no longer serves (`sdk_too_old`). The call that needed the server
     * fails with that error, `diagnose()` says `key.accepted` false with the reason and the same `lastError`, the JSON
     * fits the schema, and a refused client asks the server nothing more in this launch.
     */
    @Test
    fun diagnoseNamesAKeyTheServerRefused() = runBlocking {
        // a well formed key the server does not know
        val unknown = client(key = "qlk_v1_otherkey9_" + "B".repeat(22))
        assertEquals("invalid_key", code { runBlocking { unknown.model() } })
        var report = valid(unknown.diagnose())
        assertEquals(mapOf("accepted" to false, "reason" to "invalid_key"), report["key"])
        assertEquals("invalid_key", report["lastError"])
        assertEquals("none", report["config"])
        assertNull(report["model"])
        assertNull(report["backend"])
        assertTrue("the server was never asked", stub.seen.isNotEmpty())
        val asked = stub.seen.size
        assertEquals("invalid_key", code { runBlocking { unknown.prefetch() } })
        assertEquals("invalid_key", code { runBlocking { unknown.model() } })
        assertEquals("a refused client went on asking the server", asked, stub.seen.size)
        assertEquals(mapOf("accepted" to false, "reason" to "invalid_key"), valid(unknown.diagnose())["key"])
        unknown.close()

        // the key is good, the server refuses the app
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(403, "app_blocked") }
        val blocked = client()
        assertEquals("server_rejected", code { runBlocking { blocked.model() } })
        report = valid(blocked.diagnose())
        assertEquals(mapOf("accepted" to false, "reason" to "server_rejected"), report["key"])
        assertEquals("server_rejected", report["lastError"])
        assertEquals("none", report["config"])
        blocked.close()

        // the server no longer serves this SDK version, and says which one it wants
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.json(403, mapOf("error" to "sdk_too_old", "message" to "sdk_too_old", "min_sdk" to "3.1.0")) }
        val old = client()
        try {
            old.model()
            fail("a refused SDK version got a model")
        } catch (e: TajweedError) {
            assertEquals("sdk_too_old", e.code)
            assertTrue("the error does not name the version the server asks for: ${e.message} ${e.fix}", (e.message + e.fix).contains("3.1.0"))
        }
        report = valid(old.diagnose())
        assertEquals(mapOf("accepted" to false, "reason" to "sdk_too_old"), report["key"])
        assertEquals("sdk_too_old", report["lastError"])
        old.close()

        // the refusal was the server's, not the device's: with the refusal gone a new client is accepted
        stub.overrides.clear()
        stub.recommended = null
        val accepted = client()
        assertEquals("model_unavailable", code { runBlocking { accepted.model() } })
        assertEquals(mapOf("accepted" to true, "reason" to null), valid(accepted.diagnose())["key"])
    }

    /**
     * The app pins a model version Quran Lab has no build of for this SDK. The config request carries the pin; the
     * server answers its normal choice and marks the pin `unavailable`; the Client opens that choice, says so in the
     * debug log, and `diagnose()` reports the model that is really open, with no error. The same pin again is the
     * Model that is open. A pin the server has is answered with that version.
     */
    @Test
    fun aPinnedVersionTheServerDoesNotHaveGetsItsNormalChoice() = runBlocking {
        ServedModel.of().use { served ->
            stub.recommended = served.recommended()
            stub.inference = null
            stub.versions = setOf("4.0.0")
            val log = java.util.Collections.synchronizedList(ArrayList<String>())
            var engines = 0
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir, debugBuild = true, log = { log.add(it) }),
                apiUrl = stub.base, trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = served.url,
                engine = { _, _, _ -> engines++; Engine() }), autoLaunch = false).also { open = it }

            // the model is on the device from a call without a pin
            c.prefetch()
            assertTrue("a call without a pin sent one", stub.seen("/v1/config").none { it.q("model_version") != null })

            val model = c.model("4.1.0")
            assertEquals("the config was asked again, with the pin", "4.1.0", stub.seen("/v1/config").last().q("model_version"))
            assertEquals("4.1.0", c.cloud.currentConfig?.pinUnavailable)
            assertEquals("the server's normal choice was opened", listOf("4.0.0", served.build), listOf(model.version, model.build))
            assertTrue("the debug log does not say that the pin is not available: $log",
                log.any { it.contains("4.1.0") && it.contains("not available") && it.contains("4.0.0") })

            val report = valid(c.diagnose())
            assertEquals(mapOf("version" to "4.0.0", "build" to served.build, "verified" to true), report["model"])
            assertEquals(mapOf("accepted" to true, "reason" to null), report["key"])
            assertEquals("fresh", report["config"])
            assertNull("a pin that is not available is not an error", report["lastError"])

            // the same pin again, and no pin: the Model that is open, not a new one
            assertEquals(1, engines)
            assertTrue(c.model("4.1.0") === model)
            assertTrue(c.model() === model)
            assertEquals("the engine was opened again for a Model that was open", 1, engines)

            // a pin the server has: that version, marked as the pin's answer, and still the same Model
            assertTrue(c.model("4.0.0") === model)
            c.removeModels()
            val pinned = c.model("4.0.0")
            assertEquals("4.0.0", stub.seen("/v1/config").last().q("model_version"))
            assertNull(c.cloud.currentConfig?.pinUnavailable)
            assertEquals("pin", c.cloud.currentConfig?.recommended?.get("source"))
            assertEquals("4.0.0", pinned.version)
            assertEquals(mapOf("version" to "4.0.0", "build" to served.build, "verified" to true), valid(c.diagnose())["model"])
        }
    }

    /**
     * `prefetch` after a cut, through the Client. The server drops the connection in the middle of the largest file:
     * the call fails with `download_failed` and what arrived stays on the device. The second call asks for the rest
     * of that file only (a Range from the bytes on disk, with the validator of the first answer) and for nothing that
     * was complete. The progress the app sees is right both times: one total (the bytes of the CPU set), never
     * backwards, never past what arrived, inside the large file never back before its part, and the total at the end.
     */
    @Test
    fun prefetchContinuesAfterACutFromTheBytesOnDisk() = runBlocking {
        // weights large enough for progress inside the file (the downloader reports every 256 KiB)
        val weights = ByteArray(1_500_000).also { java.util.Random(7).nextBytes(it) }
        val files = TestKit.v4Files().map { if (it.path == "layers.zfw") TestKit.F("layers.zfw", weights, "weights") else it }
        ServedModel.of(files).use { served ->
            stub.recommended = served.recommended()
            stub.inference = null
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = served.url, engine = { _, _, _ -> Engine() }),
                autoLaunch = false).also { open = it }
            val big = "layers.zfw.gz"
            val bigSize = files.first { it.path == "layers.zfw" }.stored.size.toLong()
            val cutAt = 700_000L
            val cuts = java.util.concurrent.atomic.AtomicInteger()
            served.cut = { path -> if (path == big && cuts.getAndIncrement() == 0) cutAt else null }
            fun rising(p: List<Pair<Long, Long>>) = p.zipWithNext().all { (a, b) -> b.first >= a.first }

            // the first call: cut in the middle of the large file
            val first = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
            assertEquals("download_failed", code { runBlocking { c.prefetch { done, total -> first.add(done to total) } } })
            assertEquals("download_failed", valid(c.diagnose())["lastError"])
            val cpuSet = served.paths().filter { it != "manifest.json" }
            assertEquals("each file of the CPU set was asked for once: ${served.requests}", cpuSet.size, cpuSet.distinct().size)
            assertEquals("the largest file comes last", big, cpuSet.last())
            assertEquals("the connection was cut after $cutAt bytes", cutAt, served.requests.last().sent)
            val total = cpuSet.sumOf { File(served.root, it).length() }
            val small = total - bigSize
            val store = File(dir, "models/v4.0/${served.build}")
            val part = File(store, "$big.part")
            assertTrue("nothing of the cut file is on the device", part.isFile)
            val onDisk = part.length()
            assertTrue("the part holds what arrived before the cut: $onDisk of $cutAt sent", onDisk in 1..cutAt)
            assertFalse("the cut file was put in place", File(store, "layers.zfw").exists())
            assertTrue("no progress in the first call", first.isNotEmpty())
            assertTrue("one total, the bytes of the CPU set ($total): $first", first.all { it.second == total })
            assertTrue("progress went back: $first", rising(first))
            assertTrue("progress did not reach into the large file: $first", first.last().first > small)
            assertTrue("progress ran past what arrived (${small + onDisk}): ${first.last()}", first.last().first <= small + onDisk)

            // the second call: the rest of that file, and nothing else
            val before = served.requests.size
            val second = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
            c.prefetch { done, total -> second.add(done to total) }
            val asked = served.requests.drop(before)
            assertEquals("the second call asked for the cut file only: $asked", listOf(big), asked.map { it.path })
            val rest = asked.single()
            assertEquals("it continued from the bytes on disk", "bytes=$onDisk-", rest.range)
            assertTrue("with the validator of the first answer", rest.ifRange != null && rest.ifRange.startsWith("\""))
            assertEquals(206, rest.status)
            assertEquals("only the rest of the file was sent", bigSize - onDisk, rest.sent)
            assertTrue("no progress in the second call", second.isNotEmpty())
            assertTrue("one total, the same as before ($total): $second", second.all { it.second == total })
            assertTrue("progress went back: $second", rising(second))
            assertEquals("progress ends at the total", total, second.last().first)
            val inside = second.filter { it.first > small }
            assertTrue("no progress inside the large file: $second", inside.isNotEmpty())
            assertTrue("inside the large file progress started again before its part ($onDisk): $second", inside.all { it.first >= small + onDisk })

            // the file is whole and verified, the part is gone, and the model opens from the device with no request
            assertFalse(part.exists())
            assertEquals(TestKit.sha(weights), TestKit.sha(File(store, "layers.zfw").readBytes()))
            val requests = served.requests.size
            c.prefetch()
            val model = c.model()
            assertEquals("a complete model was asked for again", requests, served.requests.size)
            assertEquals(served.build, model.build)
        }
    }

    /**
     * A refusal says what to do. For each of the seven refusals the server names, the stub answers 403 with that
     * code: the error is `server_rejected` with exactly the catalogue's message and fix for that reason,
     * and the server's code is in the message. A 403 code nobody knows is `server_rejected` too, with the entry's own
     * words and the code as the detail. No message text is written here: the expected words are read from the
     * embedded catalogue.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun aRefusalSaysWhichOneItWasAndWhatToDo() = runBlocking {
        val catalogue = Json.parse(javaClass.getResourceAsStream("/ai/quranlab/tajweedformer/errors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        val entry = (catalogue["errors"] as List<Map<String, Any?>>).single { it["code"] == "server_rejected" }
        val reasons = entry["reasons"] as Map<String, Map<String, String>>
        // the seven refusals the server answers a 403 with, by name (the catalogue may hold more reasons: the web's
        // origin check has one no 403 carries, which this SDK holds and never raises)
        val served = listOf("developer_not_approved", "not_a_member", "key_type_mismatch", "origin_not_allowed", "app_not_allowed", "app_blocked",
            "environment_mismatch")
        assertEquals("server refusals the catalogue has no words for", emptyList<String>(), served.filter { it !in reasons })
        for (reason in served) {
            val words = reasons.getValue(reason)
            stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(403, reason) }
            val c = client()
            val e = try {
                c.model()
                null
            } catch (e: TajweedError) {
                e
            }
            assertEquals(reason, "server_rejected", e?.code)
            // an SDK has no page origin: `{origin}` is the app id it registered with
            assertEquals(reason, words.getValue("message").replace("{origin}", "org.example.recite"), e!!.message)
            assertEquals(reason, words.getValue("fix"), e.fix)
            // a key belongs to the first app that used it: this one line is pinned word for word
            if (reason == "app_not_allowed") {
                assertEquals("This key belongs to another app (app_not_allowed).", e.message)
                assertEquals("A key works for the first app that used it. Make a key for this app at https://sdk.quranlab.ai/keys, " +
                    "or allow this app's identifier on the key's page.", e.fix)
            }
            assertTrue("$reason: the server's code is not in the message: ${e.message}", e.message.contains("($reason)"))
            assertTrue("$reason: a placeholder was left: ${e.message} ${e.fix}", !Regex("\\{[a-z_]+\\}").containsMatchIn(e.message + e.fix))
            assertEquals("https://sdk.quranlab.ai/docs/errors#server_rejected", e.docs)
            // the same words from every call of this client, and in diagnose()
            assertEquals(e.message, (try { c.prefetch(); null } catch (again: TajweedError) { again })?.message)
            val report = valid(c.diagnose())
            assertEquals(mapOf("accepted" to false, "reason" to "server_rejected"), report["key"])
            assertEquals("server_rejected", report["lastError"])
            c.close()
        }
        // a refusal this SDK has never heard of: still a refusal, with the entry's own words and the code as the detail
        stub.overrides["/v1/config"] = { _, _ -> StubQlab.Reply.error(403, "some_new_refusal") }
        val c = client()
        try {
            c.model()
            fail("a refused app got a model")
        } catch (e: TajweedError) {
            assertEquals("server_rejected", e.code)
            assertEquals((entry["message"] as String).replace("{detail}", "some_new_refusal"), e.message)
            assertEquals(entry["fix"], e.fix)
        }
        assertEquals(mapOf("accepted" to false, "reason" to "server_rejected"), valid(c.diagnose())["key"])
        Unit
    }

    /**
     * An app the system does not let use the network (Android: the INTERNET permission was taken out of its
     * manifest): every request ends in the platform's SecurityException. The calls that need the network say
     * `network_not_permitted`, with a fix that names the permission; nothing reaches a server; the background work
     * (the launch sequence, a delivery) treats it as no connection and raises nothing; a model that is on the device
     * still opens; and with the network allowed again the same client goes on.
     */
    @Test
    fun withoutTheNetworkPermissionTheCallsThatNeedItSayNetworkNotPermitted() = runBlocking {
        ServedModel.of().use { served ->
            stub.recommended = served.recommended()
            stub.inference = null
            val refuse = java.util.concurrent.atomic.AtomicBoolean(true)
            val real = ai.quranlab.tajweedformer.UrlConnectionTransport()
            val modelHttp = ai.quranlab.tajweedformer.HttpTransport { url, headers ->
                if (refuse.get()) throw SecurityException("Permission denied (missing INTERNET permission?)") else real.get(url, headers)
            }
            stub.notPermitted = true
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = served.url, modelHttp = modelHttp,
                engine = { _, _, _ -> Engine() }), autoLaunch = false).also { open = it }

            for (call in listOf<suspend () -> Unit>({ c.model() }, { c.prefetch() }, { c.prefetch(unmeteredOnly = true) })) {
                try {
                    call()
                    fail("a call that needs the network returned without it")
                } catch (e: TajweedError) {
                    assertEquals("network_not_permitted", e.code)
                    assertTrue("the fix does not name the permission: ${e.fix}", e.fix.contains("INTERNET"))
                    assertEquals("https://sdk.quranlab.ai/docs/errors#network_not_permitted", e.docs)
                }
            }
            assertTrue("a request reached a server: ${stub.seen.map { it.path }} ${served.paths()}", stub.seen.isEmpty() && served.requests.isEmpty())
            var report = valid(c.diagnose())
            assertEquals("network_not_permitted", report["lastError"])
            assertEquals("not a refusal of the key: the server was never asked", mapOf("accepted" to null, "reason" to null), report["key"])
            assertEquals("none", report["config"])
            // the background work raises nothing: it is as with no connection
            c.cloud.launch(foreground = true)
            c.cloud.deliver()
            c.cloud.refreshConfig()
            assertTrue(stub.seen.isEmpty())

            // the config is on the device, the model is not, and the model server cannot be reached for the same reason
            stub.notPermitted = false
            c.cloud.refreshConfig()
            assertEquals("network_not_permitted", code { runBlocking { c.model() } })
            assertTrue("a model request went out: ${served.paths()}", served.requests.isEmpty())

            // the network is allowed again: the same client downloads and opens the model
            refuse.set(false)
            val model = c.model()
            assertEquals(served.build, model.build)
            // the model is on the device: without the network it still opens, and only what needs the network says so
            c.removeModels()
            c.prefetch()
            stub.notPermitted = true
            refuse.set(true)
            val again = c.model()
            assertEquals(served.build, again.build)
            assertEquals("network_not_permitted", code { runBlocking { again.reference!!.fetch() } })
            report = valid(c.diagnose())
            assertEquals(mapOf("version" to "4.0.0", "build" to served.build, "verified" to true), report["model"])
        }
    }

    /**
     * Which code a refusal has: the answer's `reason` first (`vectors30/refusals.json`, the contract's file byte for
     * byte). The server answers a key that belongs to another app, a key of the wrong type and an origin that is not
     * on the key as a bad key (401 `invalid_key`) with the `reason` in the body. Each case is the answer every API
     * request of a fresh Client gets; `model()` then raises exactly the case's code, message and fix, `diagnose()`
     * says the key is not accepted with that code, and the refused client asks the server nothing more.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun theRefusalVectors() = runBlocking {
        val vectors = Json.parse(javaClass.getResourceAsStream("/vectors30/refusals.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        assertEquals("quranlab.sdk-refusal-vectors/1", vectors["format"])
        assertEquals(SdkInfo.VERSION, vectors["sdk"])
        val cases = vectors["cases"] as List<Map<String, Any?>>
        assertEquals(11, cases.size)
        stub.appId = vectors["app"] as String
        var passed = 0
        for (case in cases) {
            val name = case["name"] as String
            val status = (case["status"] as Double).toInt()
            val body = case["body"] as Map<String, Any?>
            val expect = case["expect"] as Map<String, Any?>
            stub.seen.clear()
            stub.everything = { _ -> StubQlab.Reply.json(status, body) }
            val c = client()
            val e = try {
                c.model()
                null
            } catch (e: TajweedError) {
                e
            }
            assertEquals("$name: code", expect["code"], e?.code)
            assertEquals("$name: message", expect["message"], e!!.message)
            assertEquals("$name: fix", expect["fix"], e.fix)
            val report = valid(c.diagnose())
            assertEquals("$name: diagnose", mapOf("accepted" to false, "reason" to expect["code"]), report["key"])
            assertEquals("$name: lastError", expect["code"], report["lastError"])
            // the network follows the status and `error` as before: a refused client asks nothing more in this launch
            val asked = stub.seen.size
            assertTrue("$name: the server was never asked", asked > 0)
            val again = try { c.prefetch(); null } catch (x: TajweedError) { x }
            assertEquals("$name: the same words again", listOf(expect["code"], expect["message"], expect["fix"]), listOf(again?.code, again?.message, again?.fix))
            assertEquals("$name: a refused client went on asking the server", asked, stub.seen.size)
            c.close()
            passed++
        }
        println("refusal vectors: $passed of ${cases.size} cases give exactly the expected code, message and fix")
        assertEquals(11, passed)
    }

    /** One chunk of 8 frames per 5120 samples, a token in every chunk. */
    private class Engine : TajweedEngine {
        override val chunkMs: Int get() = 320

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            var pending = 0

            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> {
                pending += length
                val out = ArrayList<HeadValues>()
                while (pending >= 5120) {
                    pending -= 5120
                    out.add(chunk())
                }
                return out
            }

            override fun finish(): List<HeadValues> = listOf(chunk())
            override fun reset() { pending = 0 }
            override fun close() {}
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

    /**
     * `prefetch(unmeteredOnly = true)` with a network whose kind the test sets: on a metered network nothing is asked
     * of any server and nothing is on the device; on an unmetered one the CPU set of the model is downloaded and
     * verified, with progress up to the total; a second call does nothing. Then `model()` opens what is on the device
     * (no download), a take runs, `diagnose()` names the model, and `removeModels()` empties the device.
     */
    @Test
    fun prefetchWaitsForAnUnmeteredNetworkAndTheModelOpensFromTheDevice() = runBlocking {
        // the files of the model behind a local server, at the address the build in the signed config gives
        val served = Files.createTempDirectory("tj-served-").toFile()
        val files = TestKit.v4Files()
        TestKit.writeTransport(served, files, TestKit.sign(TestKit.manifest(files)))
        val build = java.security.MessageDigest.getInstance("SHA-256").digest(File(served, "manifest.json").readBytes())
            .joinToString("") { "%02x".format(it) }.take(40)
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            asked.add(path)
            val prefix = "/Quran-Lab/tajweedformer-v4/$build/sdk/v4.0/"
            val f = if (path.startsWith(prefix)) File(served, path.removePrefix(prefix)) else null
            if (f == null || !f.isFile) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, f.length())
                ex.responseBody.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
            ex.close()
        }
        server.start()
        try {
            stub.recommended = mapOf("model" to "Quran-Lab/tajweedformer-v4", "model_id" to "v4.0", "folder" to "sdk/v4.0",
                "revision" to build, "version" to "4.0.0", "source" to "cdn")
            stub.inference = null
            // the network is a fake whose kind the test sets
            var kind = "metered"
            val network = object : NetworkPolicy {
                override fun isUnmetered(): Boolean = kind == "unmetered" || kind == "unknown"
                override fun connection(): String = kind
            }
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(dir), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey, TestKit.key.first), modelsUrl = "http://127.0.0.1:${server.address.port}",
                network = network, engine = { _, _, _ -> Engine() }), autoLaunch = false).also { open = it }
            val progress = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())

            // a metered network, and no network: the call returns, nothing was asked for or stored, and
            // diagnose() says what the connection is (how an app sees that the download waits)
            for (waits in listOf("metered", "none")) {
                kind = waits
                c.prefetch(unmeteredOnly = true) { done, total -> progress.add(done to total) }
                assertTrue("$waits: something was asked of a server: $asked ${stub.seen.map { it.path }}", asked.isEmpty() && stub.seen.isEmpty())
                assertTrue(progress.isEmpty())
                assertEquals(emptyList<Any>(), c.storage["models"])
                val report = valid(c.diagnose())
                assertEquals(waits, report["connection"])
                assertNull("waiting is not an error", report["lastError"])
            }

            // an unmetered one: the CPU set of the default context, verified
            kind = "unmetered"
            assertEquals("unmetered", valid(c.diagnose())["connection"])
            c.prefetch(unmeteredOnly = true) { done, total -> progress.add(done to total) }
            assertTrue("the manifest and the seven files of the CPU set: $asked", asked.size == 8 && asked.all { it.contains("/$build/sdk/v4.0/") })
            assertTrue("no progress was reported", progress.isNotEmpty())
            assertTrue("progress went back: $progress", progress.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertEquals("progress ends at the total", progress.last().second, progress.last().first)
            assertTrue(progress.last().second > 300_000)
            @Suppress("UNCHECKED_CAST")
            val stored = (c.storage["models"] as List<Map<String, Any>>).single()
            assertEquals(listOf("4.0.0", build), listOf(stored["version"], stored["build"]))
            assertTrue((stored["bytes"] as Long) > 300_000)

            // everything is on the device: a second call does nothing, also on a metered network
            asked.clear()
            kind = "metered"
            c.prefetch(unmeteredOnly = true)
            c.prefetch()
            assertTrue("a model that is on the device was asked for again: $asked", asked.isEmpty())
            // where the platform cannot tell, the option cannot be honoured: the download runs
            c.removeModels()
            kind = "unknown"
            c.prefetch(unmeteredOnly = true)
            assertEquals("unknown: the CPU set was downloaded again", 8, asked.size)
            assertEquals("unknown", valid(c.diagnose())["connection"])
            asked.clear()

            // model() opens what is on the device and a take runs
            val model = c.model()
            assertTrue("opening downloaded something: $asked", asked.isEmpty())
            assertEquals(listOf("4.0.0", build), listOf(model.version, model.build))
            assertEquals("c16_l64", model.defaultContext)
            val session = model.session()
            session.push(FloatArray(5120 * 2))
            val heads = session.finish()
            assertEquals(24, heads.decoded().frames.size)
            assertEquals(3, heads.decoded().tokens.size)
            // a second Session while one is open is refused, and a second model() gives the same Model
            val other = model.session()
            assertEquals("session_open", code { runBlocking { model.session() } })
            other.cancel()
            assertTrue(c.model() === model)
            assertTrue("the version that is open, asked for by name", c.model("4.0.0") === model)

            val report = valid(c.diagnose())
            assertEquals(mapOf("version" to "4.0.0", "build" to build, "verified" to true), report["model"])
            assertEquals("c16_l64", report["context"])
            assertEquals(mapOf("kind" to "cpu", "name" to "cpu", "stage" to "cpu", "reason" to null), report["backend"])
            assertEquals(mapOf("accepted" to true, "reason" to null), report["key"])
            assertEquals("fresh", report["config"])

            c.removeModels()
            assertEquals(emptyList<Any>(), c.storage["models"])
            assertNull(valid(c.diagnose())["model"])
        } finally {
            server.stop(0)
            served.deleteRecursively()
        }
    }
}
