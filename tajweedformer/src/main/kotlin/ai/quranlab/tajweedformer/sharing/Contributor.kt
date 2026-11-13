package ai.quranlab.tajweedformer.sharing

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Where the contributor's P-256 key lives. On Android: the Keystore, StrongBox
 * when the device has it, never exportable (`AndroidKeystoreContributorKeys`). The private key
 * never leaves it; the SDK asks it to sign the deletion message and nothing else.
 *
 * SDK 3.1.4: [publicKey] says "not there" (null) only when the alias has no entry; any other failure of the store is
 * thrown, and the caller reads it as "cannot be read": nothing is made, replaced or deleted then.
 */
internal interface ContributorKeyStore {
    /** Makes a new key under [alias]; returns the public point, 65 bytes `04 || X || Y`. Called only after
     *  [publicKey] said that the alias has no entry. */
    fun create(alias: String): ByteArray

    /** The public point of [alias]; null only when the alias has no entry. Throws when the store cannot read it. */
    fun publicKey(alias: String): ByteArray?

    /** ECDSA P-256 with SHA-256 over [message] (DER or raw r || s). */
    fun sign(alias: String, message: ByteArray): ByteArray

    fun delete(alias: String)

    /** Where the key is: `strongbox`, `tee`, `software`. */
    fun protection(alias: String): String = "software"
}

/**
 * A small secret kept encrypted at rest (the recovery code). On Android: AES-GCM with a Keystore key. [open] never
 * makes a key: a sealed secret whose key is not there cannot be read, and [open] throws.
 */
internal interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The desktop JVM's stand-in (tests, tools): a software key in a file of the SDK's storage. Not
 * hardware backed; the Android SDK never uses it.
 */
internal class FileContributorKeyStore(private val dir: File) : ContributorKeyStore {
    private fun file(alias: String) = File(dir, "$alias.p8")

    override fun create(alias: String): ByteArray {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = g.generateKeyPair()
        dir.mkdirs()
        file(alias).writeBytes(kp.private.encoded)
        File(dir, "$alias.pub").writeBytes(ContributorKeys.point(kp.public as ECPublicKey))
        return ContributorKeys.point(kp.public as ECPublicKey)
    }

    override fun publicKey(alias: String): ByteArray? = try {
        Files.readAllBytes(File(dir, "$alias.pub").toPath())
    } catch (_: NoSuchFileException) {
        null
    }

    override fun sign(alias: String, message: ByteArray): ByteArray {
        val k = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(file(alias).readBytes()))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(k)
        s.update(message)
        return s.sign()
    }

    override fun delete(alias: String) {
        file(alias).delete()
        File(dir, "$alias.pub").delete()
    }
}

/** No encryption (desktop JVM, tests). */
internal object PlainSecretBox : SecretBox {
    override fun seal(plain: ByteArray) = plain.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}

internal object ContributorKeys {
    /** The uncompressed point `04 || X || Y` of a P-256 public key. */
    @JvmStatic
    fun point(k: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            val out = ByteArray(32)
            val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            src.copyInto(out, 32 - src.size)
            return out
        }
        return byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    }

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 16 bytes as 26 Crockford base32 characters (the last two bits zero), in groups of 4: qlab-api's `recoveryCode`. */
    @JvmStatic
    fun recoveryCode(bytes16: ByteArray): String {
        require(bytes16.size == 16)
        val sb = StringBuilder()
        var acc = 0
        var bits = 0
        for (x in bytes16) {
            acc = (acc shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(CROCKFORD[(acc ushr (bits - 5)) and 31])
                bits -= 5
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(CROCKFORD[(acc shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    /** What a person typed, normalised as qlab-api does, or null. */
    @JvmStatic
    fun normaliseRecoveryCode(code: String): String? {
        if (code.length > 64) return null
        val s = code.uppercase().replace(Regex("[\\s-]"), "").replace('O', '0').replace(Regex("[IL]"), "1")
        return s.takeIf { Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(it) }
    }

    /** SHA-256 hex of the normalised code: what the server stores. */
    @JvmStatic
    fun recoveryHash(normalised: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The exact bytes the device signs to delete (UTF-8, LF, a final LF): qlab-api's `deletionMessage`. */
    @JvmStatic
    fun deletionMessage(app: String, contributor: String, nonce: String, timestamp: Long, action: String = "delete-all"): String =
        "quranlab-contribution-delete 1\napp $app\ncontributor $contributor\nnonce $nonce\ntimestamp $timestamp\naction $action\n"
}

/** The contributor's id, device key or recovery code is there and cannot be read (SDK 3.1.4): nothing of the
 *  contributor is made, replaced or deleted, and nothing is recorded or sent for sharing. */
internal class ContributorUnreadable(why: String, cause: Throwable? = null) : IOException("sharing: $why", cause)

/**
 * This install's contributor (PLATFORM_CLIENT.md section 9, the 2.0.0 design): a random 128-bit id
 * (32 lowercase hex), and from the first shared session a P-256 key bound to it on the server and a
 * one-time recovery code (its SHA-256 sent, the code itself kept sealed on the device and shown
 * only in the SDK's own settings sheet). [reset] after a deletion: a new id, a new key, a new code.
 *
 * SDK 3.1.4, the rule for the three: something that is there and cannot be read is not "not there".
 * - Each is read once, with three results: not there (for a file the system's "no such file", for the device key the
 *   Keystore's answer that the alias has no entry), read, or cannot be read. A value that was read and is not what
 *   the SDK writes there (an id that is not 32 hex, a sealed code that does not open or is not a recovery code) cannot
 *   be read. No second call decides which of the three it is.
 * - The device key and the code are made together, and only when both are not there; the id only when it is not
 *   there and the code is not either (a device key belongs to an id: without the id there is none to look up, and
 *   the app's Keystore can hold keys of earlier contributors). When one of the three cannot be read, or the key or the code is
 *   missing beside the other, the contributor is [Standing.Lost]: nothing is made, replaced or deleted, no take is
 *   recorded for sharing, the upload pass sends nothing, and a deletion fails with the storage error. What still reads
 *   is still shown (the code deletes on the portal). Each is read again at the next need.
 * - A pairing note is written before the key and the code are made and removed once both are: a pair that a stop of
 *   the process cut halfway was never sent, and the next share makes it again.
 *
 * Nothing here is public API: the app cannot read the code, sign with the key or export it.
 */
internal class Contributor(
    private val dir: File,
    private val keys: ContributorKeyStore,
    private val box: SecretBox,
    private val random: SecureRandom = SecureRandom(),
) {
    private val idFile = File(dir, "contributor")
    private val codeFile = File(dir, "recovery.sealed")
    private val pairingFile = File(dir, "pairing")
    private val lock = Any()

    /** What one read found. */
    private sealed class Found<out T> {
        object Absent : Found<Nothing>()
        class Read<T>(val value: T) : Found<T>()
        class Unreadable(val what: String, val cause: Throwable?) : Found<Nothing>()
    }

    /** The contributor as the device holds it. */
    internal sealed class Standing {
        /** Nothing yet: no id, no device key, no code. */
        object None : Standing()

        /** The id, and no device key and no code bound to it ([halfMade]: a pair cut halfway, never sent). */
        class Unbound(val id: String, val halfMade: Boolean = false) : Standing()

        /** All three read. */
        class Bound(val id: String, val point: ByteArray, val code: String) : Standing()

        /** Something is there and cannot be read, or the key or the code is missing beside the other ([why]).
         *  [code]: the recovery code, when it still reads. */
        class Lost(val why: String, val code: String?) : Standing()
    }

    /** What a session start sends: the id, the public key (base64url, no padding) and the recovery hash. */
    internal class Binding(val id: String, val key: String, val recoveryHash: String)

    private fun valid(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun alias(id: String) = "$PREFIX$id"

    private fun readFile(f: File): Found<ByteArray> = try {
        Found.Read(Files.readAllBytes(f.toPath()))
    } catch (_: NoSuchFileException) {
        Found.Absent
    } catch (e: Exception) {
        Found.Unreadable(f.name, e)
    }

    private fun readId(): Found<String> = when (val r = readFile(idFile)) {
        is Found.Read -> String(r.value, Charsets.UTF_8).trim().let { if (valid(it)) Found.Read(it) else Found.Unreadable("the contributor id", null) }
        Found.Absent -> Found.Absent
        is Found.Unreadable -> r
    }

    private fun readKey(id: String): Found<ByteArray> = try {
        keys.publicKey(alias(id))?.let { Found.Read(it) } ?: Found.Absent
    } catch (e: Exception) {
        Found.Unreadable("the device key", e)
    }

    private fun readCode(): Found<String> = when (val r = readFile(codeFile)) {
        is Found.Read -> try {
            val code = String(box.open(r.value), Charsets.UTF_8)
            if (ContributorKeys.normaliseRecoveryCode(code) != null) Found.Read(code) else Found.Unreadable("the recovery code", null)
        } catch (e: Exception) {
            Found.Unreadable("the recovery code", e)
        }
        Found.Absent -> Found.Absent
        is Found.Unreadable -> r
    }

    /** The contributor as the device holds it, read now. Makes nothing. */
    fun standing(): Standing = synchronized(lock) { read() }

    private fun read(): Standing {
        val code = readCode()
        val shown = (code as? Found.Read)?.value
        val id = when (val r = readId()) {
            is Found.Read -> r.value
            is Found.Unreadable -> return Standing.Lost("the contributor id cannot be read", shown)
            Found.Absent -> {
                if (code is Found.Absent) return Standing.None
                return Standing.Lost("the contributor id is not there beside a recovery code", shown)
            }
        }
        val key = readKey(id)
        if (key is Found.Unreadable) return Standing.Lost("the device key cannot be read", shown)
        if (code is Found.Unreadable) return Standing.Lost("the recovery code cannot be read", null)
        when (val p = readFile(pairingFile)) {
            Found.Absent -> {}
            is Found.Unreadable -> return Standing.Lost("the pairing note cannot be read", shown)
            // the key and the code were being made for this id and the process stopped: nothing was sent with them
            is Found.Read -> return if (String(p.value, Charsets.UTF_8).trim() == id) Standing.Unbound(id, halfMade = true)
                else Standing.Lost("the pairing note names another contributor", shown)
        }
        return when {
            key is Found.Read && code is Found.Read -> Standing.Bound(id, key.value, code.value)
            key is Found.Absent && code is Found.Absent -> Standing.Unbound(id)
            key is Found.Absent -> Standing.Lost("the device key is not there beside the recovery code", shown)
            else -> Standing.Lost("the recovery code is not there beside the device key", null)
        }
    }

    /** Writes through a temporary file and a rename: a stop of the process leaves the old bytes or the new ones. */
    private fun writeWhole(f: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        Files.write(tmp.toPath(), bytes)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun makeId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        val v = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        writeWhole(idFile, v.toByteArray(Charsets.UTF_8))
        return v
    }

    /** The device key and the code of [id], made together (the device holds neither). */
    private fun makePair(id: String): Standing.Bound {
        writeWhole(pairingFile, id.toByteArray(Charsets.UTF_8))
        val point = keys.create(alias(id))
        val b = ByteArray(16)
        random.nextBytes(b)
        val code = ContributorKeys.recoveryCode(b)
        writeWhole(codeFile, box.seal(code.toByteArray(Charsets.UTF_8)))
        Files.delete(pairingFile.toPath())
        return Standing.Bound(id, point, code)
    }

    /** A pair cut halfway (never sent): what was made of it goes before it is made again. */
    private fun dropHalfMade(id: String) {
        keys.delete(alias(id))
        Files.deleteIfExists(codeFile.toPath())
        Files.deleteIfExists(pairingFile.toPath())
    }

    /** The id: made when the device has no contributor at all. Throws [ContributorUnreadable] while it is lost. */
    val id: String get() = synchronized(lock) {
        when (val s = read()) {
            is Standing.Bound -> s.id
            is Standing.Unbound -> s.id
            Standing.None -> makeId()
            is Standing.Lost -> throw ContributorUnreadable(s.why)
        }
    }

    /** What a session start sends; the key and the code are made at the first share. Throws [ContributorUnreadable]
     *  while the contributor is lost (no take is recorded for sharing then). */
    fun binding(): Binding = synchronized(lock) {
        val b = when (val s = read()) {
            is Standing.Bound -> s
            is Standing.Lost -> throw ContributorUnreadable(s.why)
            Standing.None -> makePair(makeId())
            is Standing.Unbound -> {
                if (s.halfMade) dropHalfMade(s.id)
                makePair(s.id)
            }
        }
        Binding(b.id, Base64.getUrlEncoder().withoutPadding().encodeToString(b.point),
            ContributorKeys.recoveryHash(ContributorKeys.normaliseRecoveryCode(b.code)!!))
    }

    /** Whether the device key of the id reads (any failure: no). After an accepted deletion only. */
    val hasKey: Boolean get() = synchronized(lock) {
        val id = (readId() as? Found.Read)?.value ?: return false
        readKey(id) is Found.Read
    }

    /** Whether the contributor is lost now (the upload pass sends nothing then). */
    fun lost(): Boolean = standing() is Standing.Lost

    /** The recovery code, for the SDK's own settings sheet only: shown whenever it reads, also while the contributor is
     *  lost otherwise (it deletes on the portal); null when there is none or it cannot be read. */
    fun recoveryCode(): String? = when (val s = standing()) {
        is Standing.Bound -> s.code
        is Standing.Lost -> s.code
        else -> null
    }

    /** The deletion message signed by the device key of [id]. */
    fun sign(id: String, message: ByteArray): ByteArray = synchronized(lock) { keys.sign(alias(id), message) }

    fun protection(): String = keys.protection(alias(id))

    /** A new contributor: the old key and code are destroyed. */
    fun reset() = synchronized(lock) {
        val old = try { idFile.readText().trim() } catch (_: Exception) { null }
        if (old != null) try { keys.delete(alias(old)) } catch (_: Exception) {}
        idFile.delete()
        codeFile.delete()
        pairingFile.delete()
    }

    companion object {
        /** The Keystore alias of a contributor's device key is this and its id. */
        const val PREFIX = "quranlab.contributor."
    }
}
