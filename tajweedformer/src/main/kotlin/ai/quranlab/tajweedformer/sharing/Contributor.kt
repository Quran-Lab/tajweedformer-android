package ai.quranlab.tajweedformer.sharing

import java.io.File
import java.math.BigInteger
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
 */
interface ContributorKeyStore {
    /** Makes a new key under [alias] (replacing any); returns the public point, 65 bytes `04 || X || Y`. */
    fun create(alias: String): ByteArray

    /** The public point of [alias], or null. */
    fun publicKey(alias: String): ByteArray?

    /** ECDSA P-256 with SHA-256 over [message] (DER or raw r || s). */
    fun sign(alias: String, message: ByteArray): ByteArray

    fun delete(alias: String)

    /** Where the key is: `strongbox`, `tee`, `software`. */
    fun protection(alias: String): String = "software"
}

/** A small secret kept encrypted at rest (the recovery code). On Android: AES-GCM with a Keystore key. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The desktop JVM's stand-in (tests, tools): a software key in a file of the SDK's storage. Not
 * hardware backed; the Android SDK never uses it.
 */
class FileContributorKeyStore(private val dir: File) : ContributorKeyStore {
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

    override fun publicKey(alias: String): ByteArray? = File(dir, "$alias.pub").takeIf { it.isFile }?.readBytes()

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
object PlainSecretBox : SecretBox {
    override fun seal(plain: ByteArray) = plain.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}

object ContributorKeys {
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

/**
 * This install's contributor (PLATFORM_CLIENT.md section 9, the 2.0.0 design): a random 128-bit id
 * (32 lowercase hex), and from the first shared session a P-256 key bound to it on the server and a
 * one-time recovery code (its SHA-256 sent, the code itself kept sealed on the device and shown
 * only in the SDK's own settings sheet). [reset] after a deletion: a new id, a new key, a new code.
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
    private val lock = Any()

    private fun valid(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The id, made on first use. */
    val id: String get() = synchronized(lock) {
        try { idFile.readText().trim().takeIf(::valid) } catch (_: Exception) { null } ?: run {
            val b = ByteArray(16)
            random.nextBytes(b)
            val v = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            dir.mkdirs()
            idFile.writeText(v)
            v
        }
    }

    private fun alias(id: String) = "quranlab.contributor.$id"

    /** What a session start sends: the public key (base64url, no padding) and the recovery hash; made at the first share. */
    fun binding(): Pair<String, String> = synchronized(lock) {
        val id = id
        var pub = keys.publicKey(alias(id))
        var sealed = try { codeFile.readBytes() } catch (_: Exception) { null }
        if (pub == null || sealed == null) {
            pub = keys.create(alias(id))
            val b = ByteArray(16)
            random.nextBytes(b)
            sealed = box.seal(ContributorKeys.recoveryCode(b).toByteArray(Charsets.UTF_8))
            codeFile.writeBytes(sealed)
        }
        val code = String(box.open(sealed!!), Charsets.UTF_8)
        Base64.getUrlEncoder().withoutPadding().encodeToString(pub) to ContributorKeys.recoveryHash(ContributorKeys.normaliseRecoveryCode(code)!!)
    }

    /** Whether a key exists (something was shared with this id). */
    val hasKey: Boolean get() = synchronized(lock) { keys.publicKey(alias(id)) != null }

    /** The recovery code, for the SDK's own settings sheet only. */
    fun recoveryCode(): String? = synchronized(lock) {
        val sealed = try { codeFile.readBytes() } catch (_: Exception) { return null }
        String(box.open(sealed), Charsets.UTF_8)
    }

    fun sign(message: ByteArray): ByteArray = synchronized(lock) { keys.sign(alias(id), message) }

    fun protection(): String = keys.protection(alias(id))

    /** A new contributor: the old key and code are destroyed. */
    fun reset() = synchronized(lock) {
        val old = try { idFile.readText().trim() } catch (_: Exception) { null }
        if (old != null) try { keys.delete(alias(old)) } catch (_: Exception) {}
        idFile.delete()
        codeFile.delete()
    }
}
