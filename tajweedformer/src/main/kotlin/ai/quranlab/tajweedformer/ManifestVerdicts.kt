package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.StateVault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The verdict of the check that decides what is downloaded and installed ([ModelManifest.accept]: the signature, the
 * version, the structure), remembered for the exact bytes that passed it (4.0.1). A model open read the manifest from
 * the device and checked its Ed25519 signature in Kotlin every time: about 300 ms on the Alcatel 1, at every launch.
 *
 * - **Keyed by the bytes.** The key is the SHA-256 of the exact manifest bytes that passed, and the verifier that passed
 *   them ([ManifestVerifier.id]: its trusted keys and rules). The bytes are hashed again at every read, and only bytes
 *   identical to ones that passed skip the check; any other bytes are checked in full. The checks that are not the
 *   signature's (the config's build and version) run every time.
 * - **Where it lives.** In this process's memory, and beside the build as `.manifest.verdict`, sealed by the install's
 *   state vault ([StateVault]: on Android an AES-GCM key of the Android Keystore that never leaves it), so it is not
 *   made by writing a file next to the manifest: a file that does not open under that key, or that names other bytes
 *   or another verifier, is no verdict and the manifest is checked in full. (On the desktop JVM the vault's key is a
 *   file of the install, so there it only binds as strongly as the install's other files.)
 * - **What clears it.** A stop or a refusal that removes the models, and `removeModels()`, empty the memory ([forget]);
 *   the sealed file goes with the build's folder. A new manifest's bytes make a new verdict when they pass.
 * - **What it never replaces.** The Core checks the manifest's signature with its pinned keys at every `qlc_install` and
 *   every `qlc_model_open`, whatever the SDK remembered.
 */
internal class ManifestVerdicts(private val vault: StateVault?) {
    /** What the check decided about the bytes beside passing them: which tolerances are signed. */
    data class Flags(val tolerance: Boolean, val raw: Boolean)

    /** The verdict for [sha] (the SHA-256 of the bytes, lower case hex) under [verifier], or null: check in full. */
    fun known(dir: File?, sha: String, verifier: ManifestVerifier): Flags? {
        val k = "$sha ${verifier.id}"
        MEMORY[k]?.let { return it }
        if (dir == null || vault == null) return null
        val f = File(dir, FILE_NAME)
        val text = try {
            if (f.isFile && f.length() in 1..MAX_BYTES) vault.open(f.readBytes()) else null
        } catch (_: Exception) {
            null
        } ?: return null
        val lines = String(text, Charsets.US_ASCII).split('\n')
        if (lines.size < 5 || lines[0] != MAGIC || lines[1] != sha || lines[2] != verifier.id) return null
        val flags = Flags(lines[3] == "1", lines[4] == "1")
        MEMORY[k] = flags
        return flags
    }

    /**
     * The bytes [sha] passed [verifier]'s full check: remembered in memory, and sealed beside the build when [dir] is a
     * folder already (never made here: once a stop removed the model, nothing of it comes back). Never throws.
     */
    fun passed(dir: File?, sha: String, verifier: ManifestVerifier, flags: Flags) {
        MEMORY["$sha ${verifier.id}"] = flags
        if (dir == null || vault == null || !dir.isDirectory) return
        try {
            val sealed = vault.seal("$MAGIC\n$sha\n${verifier.id}\n${if (flags.tolerance) 1 else 0}\n${if (flags.raw) 1 else 0}\n"
                .toByteArray(Charsets.US_ASCII)) ?: return
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeBytes(sealed)
            try {
                Files.move(tmp.toPath(), File(dir, FILE_NAME).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), File(dir, FILE_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // not kept: the next process checks the manifest in full, as before 4.0.1
        }
    }

    companion object {
        /** The sealed verdict, beside the build's manifest. */
        const val FILE_NAME = ".manifest.verdict"
        private const val MAGIC = "quranlab-manifest-verdict 1"
        private const val MAX_BYTES = 4096L
        private val MEMORY = ConcurrentHashMap<String, Flags>()

        /** A stop, a refusal that removes the models, or removeModels(): nothing is remembered in this process any more. */
        @JvmStatic
        fun forget() = MEMORY.clear()
    }
}
