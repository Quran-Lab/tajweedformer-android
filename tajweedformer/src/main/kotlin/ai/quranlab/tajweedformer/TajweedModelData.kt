package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.EngineReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/** The model's vocabulary (`tokens.txt`): ids and names, read only. V4 has 38 tokens. */
public class TajweedTokens internal constructor(private val vocabulary: Vocabulary) {
    /** How many tokens the model has. */
    public val count: Int get() = vocabulary.size

    /** The CTC blank's id (0). */
    public val blank: Int get() = vocabulary.blank

    /** The id of the word bar `|`, the token the model writes between words. */
    public val wordBar: Int get() = ModelFolder.WORD_BOUNDARY

    /** The name of a token; an id outside the vocabulary is `invalid_argument`. */
    public fun name(id: Int): String {
        if (id !in 0 until vocabulary.size) throw RecognizerException.InvalidAppOption("token id", "$id is not 0..${vocabulary.size - 1}")
        return vocabulary.symbol(id)
    }

    /** The id of a token name, or null when the model has no such token. */
    public fun id(name: String): Int? = vocabulary.names.indexOf(name).takeIf { it >= 0 }

    /** Every name, by id. */
    public val names: List<String> get() = vocabulary.names
}

/**
 * One of the sample clips a model folder carries (its self-test clips): a short real recitation, for examples, tests
 * and simulators. `model.listen(from = clip)` runs it through the same path as the microphone.
 */
public class TajweedClip internal constructor(
    /** The clip's name, for example `Husary_001001`. */
    public val name: String,
    /** `clean` (a plain recitation) or `planted` (a recitation with one spliced sound, used by the accelerator test). */
    public val kind: String,
    /** Its length in ms. */
    public val durationMs: Long,
    internal val file: File,
    internal val sampleRate: Int,
) {
    override fun toString(): String = "TajweedClip($name, $kind, $durationMs ms)"
}

/**
 * The reference data of a model build: for each ayah, the units (token ids) a correct recitation is expected to give,
 * and where its words end. Read-only data, like the calibration: the SDK never aligns it with a take, never compares
 * and never reads it itself. It is not part of the first download: call [fetch] once (with a connection) before
 * [units].
 *
 * ```
 * val reference = model.reference ?: return          // this build has none
 * reference.fetch()
 * val units = reference.units(surah = 1, ayah = 2)   // token ids, the word bar between words
 * ```
 */
public class TajweedReference internal constructor(
    private val files: List<ModelManifest.FileEntry>,
    private val dir: File,
    private val store: ModelStore?,
    /** SHA-256 of the model's tokens.txt: the table must have been built for the same vocabulary. */
    private val tokensSha256: String,
) {
    private var table: EngineReference? = null
    private val lock = Any()

    private fun inPlace(): Boolean = files.all { e ->
        val f = File(dir, e.localPath)
        if (store != null) store.downloader.isInPlace(e) else f.isFile && f.length() == e.localSize
    }

    /** Whether the reference data is on this device. */
    public val isDownloaded: Boolean get() = inPlace()

    /**
     * Downloads and verifies the reference data, once; a no-op when it is on the device. Without a connection, or
     * when the download fails, the error is `reference_missing`.
     */
    public suspend fun fetch() {
        if (inPlace()) return
        val s = store ?: throw RecognizerException.ReferenceMissing()
        try {
            runInterruptible(Dispatchers.IO) { for (e in files) s.downloader.fetch(e) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the system does not let the app use the network: that, not a missing file, is what to fix
            if (e.cause is NetworkNotPermitted) throw RecognizerException.NetworkNotPermitted(e)
            // Quran Lab refused the key or the app (SPEC 15.3, 15.4): that refusal, with its fix
            if (e is TajweedError && (e.code == "invalid_key" || e.code == "server_rejected")) throw e
            throw RecognizerException.ReferenceMissing(e)
        }
    }

    private fun open(): EngineReference = synchronized(lock) {
        table ?: run {
            if (!inPlace()) throw RecognizerException.ReferenceMissing()
            val main = files.firstOrNull { it.localPath.endsWith(".zfx") } ?: files.first()
            // the lookup is the engine's: one reader of the table for the four SDKs
            if (!ai.quranlab.tajweedformer.engine.TajweedJni.available) throw ai.quranlab.tajweedformer.engine.TajweedJni.unavailable("the reference lookup")
            val t = EngineReference(File(dir, main.localPath))
            val made = t.tokensSha256()
            if (made != null && !made.equals(tokensSha256, ignoreCase = true)) {
                t.close()
                throw RecognizerException.ManifestInvalid("the reference data was built for another vocabulary than this model's tokens")
            }
            t.also { table = it }
        }
    }

    /**
     * The expected units of an ayah, exactly as the table has them: the token ids in reading order, with the word
     * bar (`model.tokens.wordBar`) between words, in the continuing form (the first word as at the start of a
     * recitation, the last in its pausal form). Names: `model.tokens.name(id)`. Before [fetch] the error is
     * `reference_missing`; a surah or ayah the Quran does not have is `invalid_argument`.
     */
    public fun units(surah: Int, ayah: Int): IntArray {
        Quran.check(surah, ayah, ayah)
        return open().units(surah, ayah)?.first
            ?: throw RecognizerException.InvalidAppOption("surah, ayah", "$surah:$ayah is not in the reference data")
    }

    /** The word (0-based) of each unit of [units], as the table has it (a word bar belongs to the word before it). */
    internal fun wordOfUnit(surah: Int, ayah: Int): IntArray? = open().units(surah, ayah)?.second

    internal fun close() = synchronized(lock) {
        table?.close()
        table = null
    }
}

/** The ayah counts of the 114 surahs, for validating a surah and ayah range an app passes in. */
internal object Quran {
    val AYAHS = intArrayOf(
        7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69,
        60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11,
        11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19,
        5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
    )

    /** Throws `invalid_argument` unless 1 <= surah <= 114 and 1 <= ayahFrom <= ayahTo <= the surah's ayah count. */
    fun check(surah: Int, ayahFrom: Int, ayahTo: Int) {
        if (surah !in 1..114) throw RecognizerException.InvalidAppOption("surah", "$surah is not 1..114")
        val n = AYAHS[surah - 1]
        if (ayahFrom < 1 || ayahTo < ayahFrom || ayahTo > n) {
            throw RecognizerException.InvalidAppOption("ayah", "$ayahFrom..$ayahTo is not inside surah $surah (1..$n)")
        }
    }
}
