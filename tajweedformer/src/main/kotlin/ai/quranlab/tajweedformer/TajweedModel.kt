package ai.quranlab.tajweedformer

import java.io.File

/**
 * A verified TajweedFormer V4 model folder (`sdk/v4.0`, manifest format 3, engine format 3; LAYOUT.md
 * layout v2), as [ModelStore] keeps it on disk:
 *
 *     manifest.json, tokens.txt, calibration.json, expected_tj3.zfx
 *     layers.zfw, frontend.zfw                      shared by every context
 *     <context>/model_engine2.onnx.zfg, .zfg.bin, frames.zfw
 *     npu/<soc>/<context>/...                       optional, the NPU backend only
 *
 * Opening checks the manifest (signature, format, `min_sdk_version`, engine format), then the CPU
 * profile's files ([ModelProfile.cpu]): every shared required file and the three files of each
 * context that is present, size and SHA-256 (hashes are remembered per file, see [ModelDownloader]).
 * A context is usable ([availableContexts]) when all its files are present and verified; a context
 * that is partly present is an error. Files outside the CPU profile are not opened here.
 */
class TajweedModel private constructor(
    val dir: File,
    val manifest: ModelManifest,
    val calibration: Calibration,
    val vocabulary: Vocabulary,
    /** The contexts whose files are on disk and verified, in the manifest's order. */
    val availableContexts: List<String>,
) {
    /** The model's id ("v4.0"). */
    val modelId: String get() = manifest.modelId

    /** Every shipped context ("c16_l64", "c8_l64", "c24_l72", ...), calibration.json's order. */
    val contexts: List<String> get() = calibration.contexts.keys.toList()

    val defaultContext: String get() = calibration.defaultContext

    /** The expected tj3 sequence of every ayah (the engine reads it). */
    val expectedTable: File get() = File(dir, EXPECTED_TABLE)

    /** The exact engine's files of one context (the shared files are at the folder's root). */
    class ContextFiles(val context: String, val dir: File) {
        val graph: File get() = File(dir, CONTEXT_FILES[0])
        val all: List<File> get() = CONTEXT_FILES.map { File(dir, it) }
    }

    /** [context]'s files. Throws [RecognizerException.InvalidAppOption] for a context the model does
     *  not ship and [RecognizerException.ModelMissing] for one not downloaded. */
    fun contextFiles(context: String = defaultContext): ContextFiles {
        if (context !in calibration.contexts) throw RecognizerException.InvalidAppOption("context", "$context is not shipped ($contexts)")
        if (context !in availableContexts) throw RecognizerException.ModelMissing("context $context is not downloaded")
        return ContextFiles(context, File(dir, context))
    }

    /** Audio per engine chunk for [context] (320 ms for chunk 16, 480 ms for chunk 24), from its name. */
    fun chunkMs(context: String = defaultContext): Int = chunkMsOf(context)

    companion object {
        const val VOCAB_SIZE = 38
        const val BLANK = 0
        /** The tj3 word boundary token ("|"). */
        const val WORD_BOUNDARY = 1
        const val EXPECTED_TABLE = "expected_tj3.zfx"

        /** Per context folder (layout v2). */
        @JvmField
        val CONTEXT_FILES = listOf("model_engine2.onnx.zfg", "model_engine2.onnx.zfg.bin", "frames.zfw")

        /** Shared, at the folder's root. */
        @JvmField
        val SHARED_FILES = listOf("tokens.txt", Calibration.FILE_NAME, EXPECTED_TABLE, "layers.zfw", "frontend.zfw")

        internal fun chunkMsOf(context: String): Int =
            Regex("^c(\\d+)_l\\d+$").find(context)?.groupValues?.get(1)?.toInt()?.times(20) ?: 0

        /** Opens and verifies the V4 folder [dir]; [require] are contexts that must be present. */
        @JvmStatic
        @JvmOverloads
        fun open(dir: File, verifier: ManifestVerifier = ManifestVerifier(), require: List<String> = emptyList()): TajweedModel =
            open(dir, verifier, ModelManifest.read(dir), require)

        internal fun open(dir: File, verifier: ManifestVerifier, m: ModelManifest, require: List<String>): TajweedModel {
            verifier.verify(m)
            val stamps = VerifiedStamps(File(dir, VerifiedStamps.FILE_NAME))
            fun ok(e: ModelManifest.FileEntry) = stamps.verified(File(dir, e.localPath), e.localSize, e.localSha256)
            // the shared files first: every required file of context "*"
            val shared = m.files.filter { it.need == ModelManifest.Need.REQUIRED && it.forPlatform(TajweedFormer.PLATFORM) && it.context == "*" }
            for (name in SHARED_FILES) {
                if (shared.none { it.localPath == name }) throw RecognizerException.ManifestInvalid("$name is not listed as a required file")
            }
            for (e in shared) {
                val f = File(dir, e.localPath)
                if (!f.isFile) throw RecognizerException.ModelMissing(e.localPath)
                if (!ok(e)) throw RecognizerException.ChecksumMismatch(e.localPath)
            }
            val available = ArrayList<String>()
            for (ctx in m.contexts) {
                val files = ModelProfile.contextOnly(m, ctx)
                for (name in CONTEXT_FILES) {
                    if (files.none { it.localPath == "$ctx/$name" }) throw RecognizerException.ManifestInvalid("$ctx/$name is not listed as a required file")
                }
                val present = files.count { File(dir, it.localPath).isFile }
                if (present == 0) continue
                if (present < files.size) {
                    throw RecognizerException.ModelMissing("context $ctx is partly downloaded: " +
                        files.filter { !File(dir, it.localPath).isFile }.joinToString { it.localPath })
                }
                for (e in files) if (!ok(e)) throw RecognizerException.ChecksumMismatch(e.localPath)
                available.add(ctx)
            }
            for (ctx in require) if (ctx !in available) throw RecognizerException.ModelMissing("context $ctx is not downloaded")
            if (available.isEmpty()) throw RecognizerException.ModelMissing("no context is downloaded in ${dir.path}")

            val calibration = Calibration.read(File(dir, Calibration.FILE_NAME))
            if (calibration.modelId != m.modelId) {
                throw RecognizerException.CalibrationInvalid("model_id ${calibration.modelId} is not the manifest's ${m.modelId}")
            }
            val tsha = calibration.tokensSha256
            if (tsha != null && Regex("^[0-9a-fA-F]{64}$").matches(tsha) && tsha.lowercase() != m.tokensSha256.lowercase()) {
                throw RecognizerException.CalibrationInvalid("tokens_sha256 is not the manifest's tokens")
            }
            if (calibration.contexts.keys != m.contexts.toSet() || calibration.defaultContext != m.defaultContext) {
                throw RecognizerException.CalibrationInvalid("contexts ${calibration.contexts.keys} / ${calibration.defaultContext} " +
                    "are not the manifest's ${m.contexts} / ${m.defaultContext}")
            }
            val vocabulary = Vocabulary.read(File(dir, m.tokens), blank = BLANK)
            if (vocabulary.size != VOCAB_SIZE) {
                throw RecognizerException.ModelLoadFailed("${m.tokens} has ${vocabulary.size} tokens, V4 has $VOCAB_SIZE")
            }
            return TajweedModel(dir, m, calibration, vocabulary, available)
        }
    }
}
