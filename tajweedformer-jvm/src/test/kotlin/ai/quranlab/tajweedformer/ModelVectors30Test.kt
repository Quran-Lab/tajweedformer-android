package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.RemoteConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The shared vectors of SDK 3.0 about the model: `manifest_30.json` (the acceptance checks in their order, the signed
 * text with and without the tolerance lines, versions), `golden_raw.json` (the accelerator's golden test on raw
 * outputs) and `inference_context.json` (which context a take runs at). No engine and no model file is needed.
 */
class ModelVectors30Test : OpenChecked() {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun vector(name: String): Map<String, Any?> =
        Json.parse(javaClass.getResourceAsStream("/vectors30/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun manifests() {
        val v = vector("manifest_30.json")
        // the file is for the 3.0 line (vectors README 4.5): `sdk_version` is the version it was built with, and this
        // SDK is that version or a later 3.0.x. The cases are run as this SDK.
        val sdk = v["sdk_version"] as String
        assertTrue("this SDK (${SdkInfo.VERSION}) is not $sdk or a later 3.0.x",
            ModelManifest.versionAtLeast(SdkInfo.VERSION, sdk) && SdkInfo.VERSION.startsWith("3.0.") && sdk.startsWith("3.0."))
        val verifier = ManifestVerifier(listOf(TestKit.hex(v["public_key_hex"] as String)))
        val cases = v["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 30)
        var accepted = 0
        for (c in cases) {
            val name = c["name"] as String
            val bytes = (c["manifest"] as String).toByteArray(Charsets.UTF_8)
            assertEquals("$name sha256", c["sha256"], sha256(bytes))
            val config = c["config"] as? Map<String, Any?>
            val want = c["expect"] as Map<String, Any?>
            val m = try {
                ModelManifest.accept(bytes, verifier, config?.get("revision") as? String, config?.get("version") as? String)
            } catch (e: TajweedError) {
                assertEquals("$name: refused with ${e.code} (${e.message})", "refused", want["outcome"])
                assertEquals("$name code", want["code"], e.code)
                continue
            }
            assertEquals("$name was accepted", "ok", want["outcome"])
            accepted++
            assertEquals("$name version", want["version"], m.version)
            assertEquals("$name build", want["build"], m.build)
            assertEquals("$name build is the start of the SHA-256 of the bytes", sha256(bytes).take(40), m.build)
            assertEquals("$name golden_tolerance_signed", want["golden_tolerance_signed"], m.toleranceSigned)
            assertEquals("$name golden_raw_signed", want["golden_raw_signed"], m.rawToleranceSigned)
            // the tolerance in force: the well-formed builds of a signed field; a malformed build is tokens only
            val inForce = want["golden_raw_tolerance"] as? Map<String, Any?>
            assertEquals("$name builds with a raw tolerance", inForce?.keys ?: emptySet<String>(),
                if (m.rawToleranceSigned) m.rawTolerance.keys else emptySet())
            for ((build, entry) in inForce ?: emptyMap()) {
                val t = m.rawToleranceFor(build)
                assertNotNull("$name $build", t)
                val e = entry as Map<String, Any?>
                assertEquals("$name $build heads", e.keys - "attr", t!!.heads.keys)
                for ((head, x) in e) {
                    val o = x as Map<String, Any?>
                    if (head == "attr") {
                        assertEquals("$name $build attr", o["p_mean"] as Double, t.pMean!!, 0.0)
                    } else {
                        val h = t.heads.getValue(head)
                        assertEquals("$name $build $head", listOf(o["token_edits"], o["lp_mean"], o["lp_p99"]), listOf(h.tokenEdits.toDouble(), h.lpMean, h.lpP99))
                    }
                }
            }
            assertEquals("$name tokens_only_builds", want["tokens_only_builds"], if (m.rawToleranceSigned) m.tokensOnlyBuilds else emptyList<String>())
            assertEquals("$name: the text the signature verified on", c["signed_text"],
                String(m.signedMessage(withTolerance = m.toleranceSigned, withRaw = m.rawToleranceSigned), Charsets.UTF_8))
        }
        assertTrue("some cases are accepted", accepted >= 10)

        // versions: x.y.z without leading zeros or a suffix, compared part by part
        val versions = v["versions"] as Map<String, Any?>
        for (ok in versions["valid"] as List<String>) assertTrue("$ok is a version", ModelManifest.isVersion(ok))
        for (bad in versions["invalid"] as List<String>) assertFalse("'$bad' is not a version", ModelManifest.isVersion(bad))
        for (o in versions["order"] as List<List<Any?>>) {
            val (a, b) = o[0] as String to o[1] as String
            val cmp = (o[2] as Double).toInt()
            assertEquals("$a at least $b", cmp >= 0, ModelManifest.versionAtLeast(a, b))
            assertEquals("$b at least $a", cmp <= 0, ModelManifest.versionAtLeast(b, a))
        }

        // the two production manifests of model V4 (2.x builds): they verify with their key and are refused
        for (r in v["real_manifests"] as List<Map<String, Any?>>) {
            val file = (r["file"] as String).substringAfterLast("/")
            val bytes = javaClass.getResourceAsStream("/manifest_vectors/$file")!!.use { it.readBytes() }
            val production = ManifestVerifier(listOf(TestKit.hex(r["trusted_key"] as String)))
            production.verifySignature(ModelManifest.parse(String(bytes, Charsets.UTF_8)))
            try {
                ModelManifest.accept(bytes, production)
                fail("$file was accepted")
            } catch (e: TajweedError) {
                assertEquals(file, (r["expect"] as Map<String, Any?>)["code"], e.code)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rawHeads(o: Any?): Map<String, SelfTestSet.Companion.RawHead> = (o as Map<String, Any?>).mapValues { (_, rows) ->
        val r = rows as List<List<Double>>
        val width = r.firstOrNull()?.size ?: 0
        // the values name one float32 each: parsed as a double, narrowed
        SelfTestSet.Companion.RawHead(r.size, width, FloatArray(r.size * width) { r[it / width][it % width].toFloat() })
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theRawGoldenTest() {
        val v = vector("golden_raw.json")
        assertEquals(v["ctc_heads"], listOf(HeadId.LOG_PROBS.key, HeadId.INTER1_LOG_PROBS.key, HeadId.TIMING_LOG_PROBS.key))
        assertEquals(v["attr_heads"], SelfTestSet.COMPARED_ATTR)
        val cases = v["cases"] as List<Map<String, Any?>>
        assertEquals(20, cases.size)
        for (c in cases) {
            val name = c["name"] as String
            val clips = c["clips"] as List<Map<String, Any?>>
            val expected = LinkedHashMap<String, List<Int>>()
            for (k in clips) expected[k["id"] as String] = (k["expected_tokens"] as List<Double>).map { it.toInt() }
            val cpu = clips.associate { it["id"] as String to rawHeads(it["cpu"]) }
            val accelerator = clips.associate { it["id"] as String to rawHeads(it["accelerator"]) }
            val planted = clips.filter { it["kind"] != "clean" }.map { it["id"] as String }.toSet()
            val tolerance = c["tolerance_entry"]?.let { ModelManifest.RawTolerance.parse(it) }
            val want = c["expect"] as Map<String, Any?>
            assertEquals("$name: a tolerance is in force", want["tolerance"] != null, tolerance != null)

            val got = SelfTestSet.judgeRaw(expected, cpu, accelerator, planted, tolerance)
            assertEquals("$name pass", want["pass"], got.passed)
            assertEquals("$name reason", want["reason"] ?: "ok", got.reason)
            assertEquals("$name failed", want["failed"], got.failed)
            val measures = want["measures"] as Map<String, Any?>
            val m = got.measures!!
            assertEquals("$name measured heads", measures.keys - "attr", m.heads.keys + m.shape)
            for ((head, x) in measures) {
                if (head == "attr") {
                    val a = x as Map<String, Any?>
                    assertEquals("$name p_mean", a["p_mean"] as Double, m.pMean!!, 1e-9)
                    assertEquals("$name attr values", (a["values"] as Double).toLong(), m.values)
                    continue
                }
                if (x == null) {
                    assertTrue("$name: $head failed on its shape", head in m.shape)
                    continue
                }
                val o = x as Map<String, Any?>
                val h = m.heads.getValue(head)
                assertEquals("$name $head token_edits", (o["token_edits"] as Double).toInt(), h.tokenEdits)
                assertEquals("$name $head lp_mean", o["lp_mean"] as Double, h.lpMean, 1e-9)
                assertEquals("$name $head lp_p99", o["lp_p99"] as Double, h.lpP99, 1e-9)
                assertEquals("$name $head frames", (o["frames"] as Double).toInt(), h.frames)
            }
            if ("attr" !in measures) assertNull("$name: no attribute head was compared", m.pMean)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theContextOfATake() {
        val cases = vector("inference_context.json")["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 19)
        val folders = HashMap<List<String>, ModelFolder>()
        for (c in cases) {
            val name = c["name"] as String
            val manifest = c["manifest"] as Map<String, Any?>
            val default = manifest["default_context"] as String
            // the default first: the test folder's manifest names its first context as the default
            val contexts = listOf(default) + (manifest["contexts"] as List<String>).filter { it != default }
            val folder = folders.getOrPut(contexts) {
                val dir = Files.createTempDirectory("tj-context-").toFile().also { dirs.add(it) }
                val files = TestKit.v4Files(contexts = contexts)
                TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files, contexts = contexts)))
                ModelFolder.open(dir, TestKit.verifier)
            }
            assertEquals(default, folder.defaultContext)
            val app = c["app"] as? String
            // what the SDK reads of the signed config: `inference.context` when it is a string, never `tajweed.context`
            val config = (c["config"] as? Map<String, Any?>)?.let { RemoteConfig(it, ByteArray(0)).inferenceContext }
            val want = c["expect"] as Map<String, Any?>
            val got = try {
                Recognizer.resolveContext(folder, app, config)
            } catch (e: TajweedError) {
                assertEquals("$name: ${e.message}", want["error"], e.code)
                continue
            }
            assertEquals("$name context", want["context"], got)
            when (want["source"]) {
                "app" -> assertEquals(name, app, got)
                "config" -> assertEquals(name, config, got)
                "manifest" -> assertEquals(name, default, got)
                else -> fail("$name: a source this test does not know")
            }
        }
    }
}
