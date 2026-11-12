package ai.quranlab.tajweedformer

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The device key of the install records (SDK 3.1.0, SPEC 15.2): a key that the app's files cannot carry, so a record
 * made on one device is not valid on another. Android: an HMAC key in the Android Keystore, which computes the MAC and
 * never gives the key out. The desktop JVM has no such store: [MachineInstallKey].
 */
internal interface InstallKeyStore {
    /**
     * HMAC-SHA256 of [message] with the device key, or null when there is no key (none was made yet, or the system
     * lost it). Throws [InstallKeyUnavailable] when the store fails in another way (locked, unavailable).
     */
    fun mac(message: ByteArray): ByteArray?

    /** Makes the device key; called when [mac] said there is none. Throws [InstallKeyUnavailable]. */
    fun create()
}

/** The key store did not answer: nothing is opened, deleted or downloaded, and the next call tries again. */
internal class InstallKeyUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The device key on the desktop JVM, which has no key store of its own (SPEC 15.2, "as Linux, or the nearest thing"):
 * `SHA-256("quranlab-model-install-key 1\n" + machine id + "\n" + user name)`. The machine id is the system's own
 * (Linux: `/etc/machine-id`; Windows: the registry's `MachineGuid`; macOS: the platform UUID). On a machine where none
 * of them can be read (some containers) the key is 32 random bytes in the file [keyFile] of the SDK's storage folder,
 * which a copied storage folder carries with it. So a storage folder copied to another machine or another user is not
 * opened. It is not a secret: a program that runs as the same user on the same machine can compute it.
 */
internal class MachineInstallKey(
    /** Where the key is kept when the machine has no id; null: a machine without an id has a key for this process only. */
    private val keyFile: File? = null,
    private val machineId: () -> String? = { MACHINE_ID },
    private val user: () -> String = { System.getProperty("user.name") ?: "" },
) : InstallKeyStore {
    @Volatile private var processKey: ByteArray? = null

    private fun key(): ByteArray? {
        val id = machineId()
        if (id != null) return MessageDigest.getInstance("SHA-256").digest(("quranlab-model-install-key 1\n$id\n" + user()).toByteArray(Charsets.UTF_8))
        val f = keyFile ?: return processKey
        if (!f.isFile) return null
        return try {
            f.readBytes().takeIf { it.size == 32 } ?: throw InstallKeyUnavailable("${f.path} is not a key")
        } catch (e: java.io.IOException) {
            throw InstallKeyUnavailable("${f.path} cannot be read", e)
        }
    }

    override fun mac(message: ByteArray): ByteArray? {
        val key = key() ?: return null
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
    }

    /** A derived key always exists; the key of a machine without an id is made here. */
    override fun create() {
        if (machineId() != null) return
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val f = keyFile ?: run { processKey = bytes; return }
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            try { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) } catch (_: Exception) {}
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            throw InstallKeyUnavailable("${f.path} cannot be written", e)
        }
    }

    companion object {
        /** Read once per process; null when the machine has no id the SDK can read. */
        private val MACHINE_ID: String? by lazy { read() }

        private fun read(): String? {
            for (path in listOf("/etc/machine-id", "/var/lib/dbus/machine-id")) {
                try { File(path).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) {}
            }
            val os = (System.getProperty("os.name") ?: "").lowercase()
            if (os.startsWith("windows")) {
                run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    ?.let { Regex("MachineGuid\\s+REG_SZ\\s+(\\S+)").find(it)?.groupValues?.get(1) }?.let { return it }
            }
            if (os.startsWith("mac")) {
                run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    ?.let { Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { return it }
            }
            return null
        }

        private fun run(vararg command: String): String? = try {
            val p = ProcessBuilder(*command).redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) out else { p.destroy(); null }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The install records (SDK 3.1.0, SPEC 15.2): the Client opens only a build it downloaded itself. When the Client
 * starts downloading a build it writes a record for it with the state `downloading`, before the first byte of the
 * build is asked for; when every file of the download profile is verified it rewrites it with the state `installed`.
 *
 * A record is bound to the device:
 * `mac = HMAC-SHA256(device key, "quranlab-model-install 1\n" + <build id> + "\n" + <state> + "\n")`, hex. It is kept
 * beside the build's folder (`models/<model id>/<build>.install`), never inside it: it is not a file the manifest
 * lists, and deleting a folder never leaves a record that speaks for what comes next.
 *
 * A build folder without a valid record (none, one of another build, one made with another device's key) is not the
 * Client's: it is never opened, none of its files is reused or resumed, and it is deleted. When the key store says
 * there is no key, no record can be valid. When it fails in another way, every call here fails with
 * `storage_failed` before anything is deleted.
 */
internal class InstallRecords(private val keys: InstallKeyStore) {
    enum class State(val key: String) { DOWNLOADING("downloading"), INSTALLED("installed") }

    /** The record of the build kept in [buildDir] (`models/<model id>/<build>`). */
    fun file(buildDir: File): File = File(buildDir.parentFile, buildDir.name + SUFFIX)

    private fun message(build: String, state: State) = "quranlab-model-install 1\n$build\n${state.key}\n".toByteArray(Charsets.UTF_8)

    private fun mac(build: String, state: State): String? = try {
        keys.mac(message(build, state))?.let { ModelManifest.hex(it) }
    } catch (e: InstallKeyUnavailable) {
        throw RecognizerException.StorageFailed("the device's key store did not answer (${e.message}); nothing was opened, deleted or downloaded", e)
    }

    /**
     * Asks the key store once, before anything is decided: throws `storage_failed` when it fails, so a caller that
     * goes on to delete knows the store answered. Returns whether there is a key.
     */
    fun probe(): Boolean = mac(PROBE, State.INSTALLED) != null

    /**
     * What the valid record of [buildDir] says, or null when the folder has no valid record: a record is valid for
     * the build it names, that build is the one the folder is kept under, and (with [build]) the one asked for.
     * Throws `storage_failed` when the key store fails.
     */
    fun state(buildDir: File, build: String? = null): State? {
        val o = try {
            Json.parse(file(buildDir).readText()) as? Map<*, *>
        } catch (_: Exception) {
            null
        } ?: return null
        val named = o["build"] as? String ?: return null
        if (folderName(named) != buildDir.name || (build != null && named != build)) return null
        val state = State.entries.firstOrNull { it.key == o["state"] } ?: return null
        val given = o["mac"] as? String ?: return null
        val expected = mac(named, state) ?: return null
        return if (MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) state else null
    }

    /** Writes the record of [buildDir] with [state], making the device key when there is none. Throws `storage_failed`. */
    fun write(buildDir: File, build: String, state: State) {
        val mac = mac(build, state) ?: run {
            try {
                keys.create()
            } catch (e: InstallKeyUnavailable) {
                throw RecognizerException.StorageFailed("the device's key store could not make a key (${e.message})", e)
            }
            mac(build, state) ?: throw RecognizerException.StorageFailed("the device's key store has no key after making one")
        }
        val f = file(buildDir)
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Json.write(linkedMapOf("v" to 1, "build" to build, "state" to state.key, "mac" to mac)))
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: java.io.IOException) {
            throw RecognizerException.StorageFailed(f.path, e)
        }
    }

    /** Deletes the build folder and its record: it is not the Client's, or it is no longer served. */
    fun remove(buildDir: File) {
        buildDir.deleteRecursively()
        file(buildDir).delete()
        File(file(buildDir).path + ".tmp").delete()
    }

    /**
     * Every build folder under [modelsDir] that has no valid record is deleted, and every record whose folder is
     * gone. Throws `storage_failed` before anything is deleted when the key store fails.
     */
    fun sweep(modelsDir: File) {
        probe()
        val models = modelsDir.listFiles { f -> f.isDirectory } ?: return
        val folders = models.flatMap { it.listFiles { f -> f.isDirectory }?.toList() ?: emptyList() }
        // every answer of the key store first: an error deletes nothing
        val foreign = folders.filter { state(it) == null }
        for (f in foreign) remove(f)
        for (m in models) {
            for (r in m.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: emptyArray()) {
                if (!File(m, r.name.removeSuffix(SUFFIX)).isDirectory) r.delete()
            }
        }
    }

    companion object {
        const val SUFFIX = ".install"

        /** What [probe] asks the store for: no build has this id. */
        private const val PROBE = "probe"

        /** The name of the folder a build (or a model id) is kept under: its letters, digits, dots, dashes and underscores. */
        fun folderName(s: String): String = s.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "x" }
    }
}
