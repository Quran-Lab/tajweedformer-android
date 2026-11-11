package ai.quranlab.tajweedformer

import java.io.File

/**
 * A verified TajweedFormer V4 model folder (`sdk/v4.0`, manifest format 3, engine format 3; layout v2), as
 * [ModelStore] keeps it on disk:
 *
 *     manifest.json, tokens.txt, calibration.json   (calibration.json is data: the folder opens without it)
 *     layers.zfw, frontend.zfw                      shared by every context
 *     <context>/model_engine2.onnx.zfg, .zfg.bin, frames.zfw
 *     selftest/...                                  the sample clips, with an accelerator's promotion
 *     reference/...                                 the expected units of each ayah, only when the app fetched it
 *     npu/<soc>/<context>/...                       optional, the NPU backend only
 *
 * Opening checks the manifest (signature, version, format, `min_sdk_version`, engine format), then the CPU
 * profile's files ([ModelProfile.cpu]): every shared required file and the three files of each
 * context that is present, size and SHA-256 (hashes are remembered per file, see [ModelDownloader]).
 * A context is usable ([availableContexts]) when all its files are present and verified; a context
 * that is partly present is an error. Files outside the CPU profile are not opened here. The contexts and the
 * default context come from the manifest.
 */
internal class ModelFolder private constructor(
    val dir: File,
    val manifest: ModelManifest,
    /** calibration.json as shipped (read-only data, never applied); null when the folder has none. */
    val calibration: TajweedCalibration?,
    val vocabulary: Vocabulary,
    /** The contexts whose files are on disk and verified, in the manifest's order. */
    val availableContexts: List<String>,
) {
    /** The model's id ("v4.0"). */
    val modelId: String get() = manifest.modelId

    /** Every shipped context ("c16_l64", "c8_l64", "c24_l72", ...), the manifest's order. */
    val contexts: List<String> get() = manifest.contexts

    val defaultContext: String get() = manifest.defaultContext

    /** The exact engine's files of one context (the shared files are at the folder's root). */
    class ContextFiles(val context: String, val dir: File) {
        val graph: File get() = File(dir, CONTEXT_FILES[0])
        val all: List<File> get() = CONTEXT_FILES.map { File(dir, it) }
    }

    /** [context]'s files. Throws [RecognizerException.InvalidAppOption] for a context the model does
     *  not ship and [RecognizerException.ModelMissing] for one not downloaded. */
    fun contextFiles(context: String = defaultContext): ContextFiles {
        if (context !in manifest.contexts) throw RecognizerException.InvalidAppOption("context", "$context is not shipped ($contexts)")
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

        /** Per context folder (layout v2). */
        @JvmField
        val CONTEXT_FILES = listOf("model_engine2.onnx.zfg", "model_engine2.onnx.zfg.bin", "frames.zfw")

        /** Shared, at the folder's root: what the engine cannot run without. */
        @JvmField
        val SHARED_FILES = listOf("tokens.txt", "layers.zfw", "frontend.zfw")

        internal fun chunkMsOf(context: String): Int =
            Regex("^c(\\d+)_l\\d+$").find(context)?.groupValues?.get(1)?.toInt()?.times(20) ?: 0

        /** Opens and verifies the V4 folder [dir]; [require] are contexts that must be present. */
        @JvmStatic
        @JvmOverloads
        fun open(dir: File, verifier: ManifestVerifier = ManifestVerifier(), require: List<String> = emptyList()): ModelFolder =
            open(dir, verifier, ModelManifest.read(dir), require)

        internal fun open(dir: File, verifier: ManifestVerifier, m: ModelManifest, require: List<String>): ModelFolder {
            verifier.verify(m)
            val stamps = VerifiedStamps(File(dir, VerifiedStamps.FILE_NAME))
            fun ok(e: ModelManifest.FileEntry) = stamps.verified(File(dir, e.localPath), e.localSize, e.localSha256)
            // the shared files first: every required file of context "*" in the CPU profile (never the 2.x expected
            // table or the reference data, whatever the manifest says of them)
            val shared = m.files.filter {
                it.need == ModelManifest.Need.REQUIRED && it.forPlatform(SdkInfo.PLATFORM) && it.context == "*" &&
                    it.role !in ModelProfile.ACCELERATOR_ROLES && it.role !in ModelProfile.DATA_ROLES
            }
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

            // the calibration is data: handed over as shipped when the manifest lists it and it verified above, never
            // parsed for the engine's sake and never a reason to refuse the folder
            val calEntry = shared.firstOrNull { it.role == "calibration" } ?: m.file(TajweedCalibration.FILE_NAME)
            val calFile = File(dir, calEntry?.localPath ?: TajweedCalibration.FILE_NAME)
            val calibration = if (calEntry != null && calFile.isFile && ok(calEntry)) TajweedCalibration(calFile.readBytes(), calEntry.localSha256) else null
            val vocabulary = Vocabulary.read(File(dir, m.tokens), blank = BLANK)
            if (vocabulary.size != VOCAB_SIZE) {
                throw RecognizerException.ModelLoadFailed("${m.tokens} has ${vocabulary.size} tokens, V4 has $VOCAB_SIZE")
            }
            return ModelFolder(dir, m, calibration, vocabulary, available)
        }
    }
}
