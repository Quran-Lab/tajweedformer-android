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

    /**
     * Makes a new device key in the place of one that cannot be used any more ([InstallKeyGuard]): what is left of the
     * old one is removed first, as far as the store allows. Throws [InstallKeyUnavailable].
     */
    fun replace() = create()
}

/** The key store did not answer: nothing is opened, deleted or downloaded, and the next call tries again. */
internal class InstallKeyUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** What failed, as the class of the store's own exception (`KeyStoreException`): two failures are the same when
     *  this is the same. Never a message: a message can carry anything. */
    val failure: String get() = (cause ?: this).javaClass.let { c -> c.simpleName.ifEmpty { c.name.substringAfterLast('.') } }
}

/**
 * Which errors of a key store say that the key is gone for good (SPEC 15.2, contract 1.14.0): the system invalidated
 * it permanently, or it cannot be recovered. Such an answer is "no key" (a new key and one download), not "the store
 * failed". Told by the classes of the error and of its causes, by name where the class is Android's (this file is also
 * the desktop JVM's). On Android 13 and later the Keystore's own error code is read as well
 * ([ai.quranlab.tajweedformer.cloud.android.KeystoreInstallKey]).
 */
internal object InstallKeyFailures {
    private val GONE = setOf(
        "android.security.keystore.KeyPermanentlyInvalidatedException",
        "java.security.UnrecoverableKeyException",
        "java.security.UnrecoverableEntryException",
    )

    /** [e] and its causes (at most eight deep). */
    fun chain(e: Throwable): List<Throwable> = generateSequence(e) { it.cause }.take(8).toList()

    fun goneForGood(e: Throwable): Boolean = chain(e).any { x -> generateSequence<Class<*>>(x.javaClass) { it.superclass }.any { it.name in GONE } }
}

/**
 * A key store must never keep a device from the model for good (SPEC 15.2, contract 1.14.0), the Android Keystore's
 * rule. An error of the store is "the store failed" for that start: nothing is opened, deleted or downloaded, the
 * call fails with `storage_failed`, and the next call tries again. When the SAME failure ([InstallKeyUnavailable.failure],
 * the exception's class) has been met at [STARTS] starts in a row, the SDK makes a new key ([InstallKeyStore.replace]):
 * no record is valid under it, so the model is downloaded once, and [replaced] is told the failure's class once (the
 * Client reports the issue `other` / `install_key_replaced:<class>`).
 *
 * What a start is: a process of the app in which the store was asked. A start's failure is counted once, however many
 * calls met it; a start in which the store answers (a MAC, or "no key") ends the row; another failure starts a row of
 * its own. The row is kept in [file], beside the SDK's other state and outside `models/` (a removal of the models does
 * not end it). When the new key cannot be made either, the call fails as before and every later start tries again.
 */
internal class InstallKeyGuard(
    private val keys: InstallKeyStore,
    private val file: File,
    private val replaced: (failure: String) -> Unit,
    private val starts: Int = STARTS,
) : InstallKeyStore {
    private val lock = Any()
    /** This start's failure is in the row already. */
    private var counted = false

    override fun mac(message: ByteArray): ByteArray? = synchronized(lock) {
        try {
            keys.mac(message).also { answered() }
        } catch (e: InstallKeyUnavailable) {
            renew(e)
            keys.mac(message)
        }
    }

    override fun create() = synchronized(lock) {
        try {
            keys.create()
        } catch (e: InstallKeyUnavailable) {
            renew(e)
        }
    }

    override fun replace() = synchronized(lock) { keys.replace() }

    /** After the failure [e]: counts it, and throws it again unless this is the start that makes a new key. */
    private fun renew(e: InstallKeyUnavailable) {
        if (failed(e.failure) < starts) throw e
        try {
            keys.replace()
        } catch (x: InstallKeyUnavailable) {
            // no new key either: the store failed, as before; the row stays, so the next start tries again
            throw e
        }
        try { file.delete() } catch (_: Exception) {}
        counted = false
        try { replaced(e.failure) } catch (_: Exception) {}
    }

    /** The store answered: the row of failures ends. */
    private fun answered() {
        counted = false
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    /** Counts this start's failure (once) and returns how many starts in a row have met [failure] now. */
    private fun failed(failure: String): Int {
        val row = try { Json.parse(file.readText()) as? Map<*, *> } catch (_: Exception) { null }
        val same = row?.get("failure") == failure
        val before = if (same) (row?.get("starts") as? Double)?.toInt() ?: 0 else 0
        if (counted && same) return before
        val now = before + 1
        counted = true
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(linkedMapOf("failure" to failure, "starts" to now)))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // the row cannot be kept: this start counts as the first
        }
        return now
    }

    companion object {
        /** The same failure at this many starts in a row, and a new key is made. */
        const val STARTS = 3

        /** The file of the row of failures, in the SDK's `cloud` folder. */
        const val FILE_NAME = "install-key-failures.json"
    }
}

/**
 * The device key on the desktop JVM, which has no key store of its own (SPEC 15.2, "as Linux, or the nearest thing"):
 * `SHA-256("quranlab-model-install-key 1\n" + machine id + "\n" + user name)`. The machine id is the system's own
 * (Linux: `/etc/machine-id`; Windows: the registry's `MachineGuid`; macOS: the platform UUID). On a machine where none
 * of them can be read (some containers) the key is 32 random bytes in the file [keyFile] of the SDK's storage folder,
 * which a copied storage folder carries with it. So a storage folder copied to another machine or another user is not
 * opened. It is not a secret: a program that runs as the same user on the same machine can compute it.
 *
 * A machine id that is read by a command (Windows: `reg`, macOS: `ioreg`) can fail or time out at one start and work
 * at the next, and the key must not change with it: the id a read gave is kept in [keptId], in the
 * SDK's store beside the records. A read that works wins and is kept; the kept answer serves only when a read fails,
 * and counts only as part of the store that kept it: it carries a MAC made with the folder's key file ([keyFile], made
 * for this when the folder has none) and counts only when that MAC verifies. So a whole storage
 * folder carried to a machine whose read fails opens there (the kept answer and its key file came with it), and a kept
 * answer without its key file, or changed by hand, is no answer. A read that works and differs from the kept answer
 * means the store was made on another machine (a restored backup, a cloned machine, a copied folder): the key is the
 * new id's, the records are not valid under it, the model is downloaded once, and nothing is reported. A first start
 * whose read fails and that has no kept answer uses the key file; when a later read works, its answer wins and the
 * model is downloaded once more.
 */
internal class MachineInstallKey(
    /** Where the key is kept when the machine has no id; null: a machine without an id has a key for this process only. */
    private val keyFile: File? = null,
    private val machineId: () -> String? = { MACHINE_ID },
    private val user: () -> String = { System.getProperty("user.name") ?: "" },
    /** Where a machine id that was read is kept; null: it is not kept (Linux reads a file, which does not time out). */
    private val keptId: File? = null,
) : InstallKeyStore {
    @Volatile private var processKey: ByteArray? = null

    /** The machine id of a store that keeps it, decided once per store (a start of the app): the read, else the kept one. */
    private val kept: String? by lazy {
        val f = keptId!!
        val read = try { machineId() } catch (_: Exception) { null }
        if (read == null) return@lazy keptAnswer(f)
        if (keptAnswer(f) != read) {
            try {
                val key = fileKey() ?: newKeyFile()
                if (key != null) {
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(Json.write(linkedMapOf("v" to 1, "id" to read, "mac" to keptMac(key, read))))
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: Exception) {
                // not kept this time: the next read that works keeps it
            }
        }
        read
    }

    /** The folder's key file as a key, or null when there is none or it is not one. */
    private fun fileKey(): ByteArray? = try { keyFile?.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == 32 } } catch (_: java.io.IOException) { null }

    /** Makes the folder's key file (32 random bytes, readable by the user only); null when the store has no place for one. */
    private fun newKeyFile(): ByteArray? {
        val f = keyFile ?: return null
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        try { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) } catch (_: Exception) {}
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }

    private fun keptMac(key: ByteArray, id: String): String =
        ModelManifest.hex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("quranlab-model-install-machine 1\n$id\n".toByteArray(Charsets.UTF_8)))

    /** The machine id this store kept, when its MAC verifies under the folder's key file; null otherwise. */
    private fun keptAnswer(f: File): String? {
        val o = try { Json.parse(f.readText()) as? Map<*, *> } catch (_: Exception) { null } ?: return null
        val id = (o["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val mac = o["mac"] as? String ?: return null
        val key = fileKey() ?: return null
        return if (MessageDigest.isEqual(mac.toByteArray(Charsets.UTF_8), keptMac(key, id).toByteArray(Charsets.UTF_8))) id else null
    }

    private fun id(): String? = if (keptId != null) kept else machineId()

    private fun key(): ByteArray? {
        val id = id()
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
        if (id() != null) return
        val f = keyFile ?: run { processKey = ByteArray(32).also { SecureRandom().nextBytes(it) }; return }
        try {
            newKeyFile()
        } catch (e: java.io.IOException) {
            throw InstallKeyUnavailable("${f.path} cannot be written", e)
        }
    }

    companion object {
        /** Read once per process; null when the machine has no id the SDK can read. */
        private val MACHINE_ID: String? by lazy { read() }

        /** Whether this system's machine id is read by a command (Windows, macOS), which can fail or time out: there
         *  the id is kept in the SDK's store ([keptId]). */
        val READ_BY_COMMAND: Boolean by lazy {
            val os = (System.getProperty("os.name") ?: "").lowercase()
            os.startsWith("windows") || os.startsWith("mac")
        }

        /** The file a machine id is kept in, in the SDK's `cloud` folder beside the key file of a machine without an id. */
        const val KEPT_ID_NAME = "install.machine"

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
