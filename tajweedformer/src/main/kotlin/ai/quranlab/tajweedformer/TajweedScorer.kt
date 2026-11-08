package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.engine.TajweedJni
import java.io.File

/**
 * The V4 scorer alone (the engine's zf_tajweed opened with load_engine 0): tajweed verdicts for heads
 * that came from elsewhere (CoreML, an NPU, a server, a saved take), with no exact engine loaded.
 * Needs calibration.json, tokens.txt and the expected table of the model folder.
 *
 * ```
 * TajweedScorer.open(model, "c16_l64").use { s -> s.score(heads, TajweedConfig(remoteJson), 1, 2) }
 * ```
 */
class TajweedScorer private constructor(
    val calibration: Calibration,
    /** The context the heads were made at (its thresholds are used). */
    val context: String,
    private val engine: ExactTajweedEngine,
) : AutoCloseable {
    /** Scores a take of surah:ayah under [config]; [config]'s context is replaced by [context], and its
     *  backend defaults to FP32 (heads from CoreML, an NPU or a server; say INT8 for the exact engine's). */
    fun score(heads: TajweedHeads, config: TajweedConfig, surah: Int, ayah: Int): TajweedResult {
        val c = TajweedConfig(config.remoteJson, config.app.copy(context = context), config.debugBuild, config.backend)
        return engine.score(heads, c, c.resolve(calibration, TajweedBackend.FP32), surah, ayah)
    }

    /** The engine's own resolution of [config] (zf_tajweed_config_json, TAJWEED_SETTINGS.md's layout),
     *  for checking it against [TajweedSettings]. */
    internal fun engineSettingsJson(config: TajweedConfig, backend: TajweedBackend): String = engine.configJson(config, backend)

    fun expectedTokens(surah: Int, ayah: Int): IntArray = engine.expectedTokens(surah, ayah)

    override fun close() = engine.close()

    companion object {
        /** A verified model folder's scorer. Throws [RecognizerException.EngineNotAvailable] when the
         *  JNI library has no zf_tajweed. */
        @JvmStatic
        @JvmOverloads
        fun open(model: TajweedModel, context: String = model.defaultContext): TajweedScorer {
            model.contextFiles(context) // throws for a context the model does not ship
            return openUnverified(model.dir, context, model.calibration)
        }

        /** A folder with calibration.json, tokens.txt and expected_tj3.zfx and no manifest (tests,
         *  tooling). Nothing is verified but what the engine itself checks. */
        @JvmStatic
        fun openUnverified(dir: File, context: String, calibration: Calibration = Calibration.read(File(dir, Calibration.FILE_NAME))): TajweedScorer {
            if (!TajweedJni.available) {
                throw RecognizerException.EngineNotAvailable("libtajweedformer_jni was built without the engine's zf_tajweed")
            }
            return TajweedScorer(calibration, context, ExactTajweedEngine(dir, context, loadEngine = false))
        }
    }
}
