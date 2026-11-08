package ai.quranlab.tajweedformer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The settings vectors (config_vectors.json, 39 cases) through the ENGINE's config, built by this
 * SDK's binding from the same three layers (remote JSON, typed app options, debug build, backend):
 * zf_tajweed_config_json must equal the reference, as TajweedSettings does (TajweedSettingsTest).
 * Together they hold the SDK and the engine to one resolution.
 *
 * Needs (skipped when absent): `TAJWEEDFORMER_LIBRARY` with zf_tajweed, and `TJ_V4_DIR` or
 * `TJ_SCORER_TABLE` (for a valid expected_tj3.zfx and tokens.txt beside the fixture calibration).
 */
class EngineSettingsTest {
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/tajweed/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun diff(path: String, want: Any?, have: Any?): String? {
        when {
            want is Map<*, *> && have is Map<*, *> -> {
                if (want.keys != have.keys) return "$path: keys ${want.keys} != ${have.keys}"
                for (k in want.keys) diff("$path.$k", want[k], have[k])?.let { return it }
                return null
            }
            want is List<*> && have is List<*> -> {
                if (want.size != have.size) return "$path: $want != $have"
                for (i in want.indices) diff("$path[$i]", want[i], have[i])?.let { return it }
                return null
            }
            want is Double && have is Double -> return if (Math.abs(want - have) <= 1e-9) null else "$path: $want != $have"
            else -> return if (want == have) null else "$path: $want != $have"
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun everyCaseThroughTheEnginesConfig() {
        val source = listOfNotNull(System.getenv("TJ_V4_DIR"), System.getenv("TJ_SCORER_TABLE")).map(::File)
            .firstOrNull { File(it, "expected_tj3.zfx").isFile && File(it, "tokens.txt").isFile }
        Assume.assumeTrue("TJ_V4_DIR or TJ_SCORER_TABLE is needed", source != null)
        Assume.assumeTrue("the JNI library has no zf_tajweed", ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val dir = Files.createTempDirectory("tj-settings-").toFile().also { dirs.add(it) }
        File(source, "tokens.txt").copyTo(File(dir, "tokens.txt"))
        // the engine checks calibration's tokens_sha256 against tokens.txt; the fixture says "fixture"
        // (settings do not depend on it), so the copy carries the real hash
        val tsha = ModelManifest.digest(File(dir, "tokens.txt")).second
        val fixture = resource("calibration_fixture.json")
        require(fixture.contains("\"tokens_sha256\": \"fixture\""))
        File(dir, "calibration.json").writeText(fixture.replace("\"tokens_sha256\": \"fixture\"", "\"tokens_sha256\": \"$tsha\""))
        File(source, "expected_tj3.zfx").copyTo(File(dir, "expected_tj3.zfx"))
        val cal = Calibration.read(File(dir, "calibration.json"))
        val vectors = Json.parse(resource("config_vectors.json")) as Map<String, Any?>
        val cases = vectors["cases"] as List<Map<String, Any?>>
        assertEquals(39, cases.size)
        val failures = ArrayList<String>()
        TajweedScorer.openUnverified(dir, cal.defaultContext, cal).use { scorer ->
            for (c in cases) {
                val name = c["name"] as String
                val remote = if (c.containsKey("remote") && c["remote"] != null) Json.write(c["remote"]) else null
                val app = TajweedOptions.fromMap(c["app"])
                val backend = TajweedBackend.fromKey(c["backend"] as String)!!
                val config = TajweedConfig(remote, app, c["debug"] as Boolean, backend)
                // zf_tajweed_config_json adds what the layers were (checks, debug_build) and the model's
                // own context next to the reference layout; those three keys are not compared
                val engine = (Json.parse(scorer.engineSettingsJson(config, backend)) as Map<String, Any?>)
                    .filterKeys { it !in setOf("checks", "debug_build", "model_context") }
                diff("$name (engine)", c["expect"], engine)?.let { failures.add(it) }
                // and the SDK's own resolution of the same layers
                val sdk = Json.parse(config.resolve(cal).toJson())
                diff("$name (sdk)", c["expect"], sdk)?.let { failures.add(it) }
            }
        }
        println("settings vectors through the engine's config: ${cases.size - failures.count { it.contains("(engine)") }}/${cases.size}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
