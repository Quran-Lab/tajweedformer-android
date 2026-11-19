package ai.quranlab.tajweedformer.core

import ai.quranlab.tajweedformer.Json
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * One agreement key of the install: what leases are wrapped for. [spki] is its SubjectPublicKeyInfo
 * DER, whose SHA-256 is the install hash. [chain] is its Key Attestation chain, leaf first (Android: the Keystore's
 * certificate chain of a key made with the attest's challenge; empty where the platform has none). [hardware]: the
 * key store says the key lives in secure hardware (`install_hw`, a signal the server cannot check).
 */
internal class AgreementKey(
    val alias: String,
    val spki: ByteArray,
    val rsa: Boolean,
    val chain: List<ByteArray> = emptyList(),
    val hardware: Boolean = false,
    val strongBox: Boolean = false,
)

/**
 * The install's keys: an agreement key that receives leases (made NEW at every attest, with the
 * attest's challenge) and a signing key that proves the install holds them. The private halves never leave the key
 * store: what crosses into the Core is one lease's ECDH secret or RSA-unwrapped KEK, and signatures.
 *
 * Android: [ai.quranlab.tajweedformer.core.android.KeystoreIdentity] (Keystore, StrongBox when present, EC P-256
 * `PURPOSE_AGREE_KEY` from API 31, RSA-2048 OAEP below). The desktop JVM: [SoftwareIdentity].
 */
internal interface InstallIdentity {
    /** The agreement key in use, or null before the install's first attest. */
    fun current(): AgreementKey?

    /** The signing key's SPKI (made at first use). */
    fun signingSpki(): ByteArray

    /** ECDSA P-256 with SHA-256 by the signing key, DER. */
    fun sign(message: ByteArray): ByteArray

    /** A new agreement key made for [challenge]; it is not the install's until [commit]. */
    fun prepare(challenge: String): AgreementKey

    /** The attest with [k] was accepted: [k] is the install's key from now on, the old one is deleted. */
    fun commit(k: AgreementKey)

    /** The attest with [k] was refused: [k] is deleted, the old key stays. */
    fun discard(k: AgreementKey)

    /** The x coordinate of ECDH between [k]'s private key and [eph] (an uncompressed P-256 point). */
    fun agree(k: AgreementKey, eph: ByteArray): ByteArray

    /** RSA-OAEP (SHA-1, MGF1-SHA-1, empty label) of [c] with [k]'s private key: the lease's 32-byte KEK. */
    fun decryptRsa(k: AgreementKey, c: ByteArray): ByteArray

    /** The key store's risk signals: 16 when keys are software only. */
    val signals: Int

    /** Forget every key (the learner's data was cleared, or a key the store lost). */
    fun reset()
}

/**
 * Where the Core's saved state is sealed and how an older copy is seen (docs/spec-fixes/10 of quranlab-core): a
 * device-bound key that is not the agreement key, and a monotonic counter.
 */
internal interface StateVault {
    fun seal(data: ByteArray): ByteArray?
    fun open(data: ByteArray): ByteArray?

    /** The counter (0 before the first raise), or -1 when it cannot be read. */
    fun counterGet(): Long

    /** Sets the counter to [v] when [v] is larger; false when it cannot be stored. */
    fun counterRaise(v: Long): Boolean
}

/** P-256 helpers shared by the key stores. */
internal object P256 {
    /** An uncompressed point (65 bytes, 0x04 || x || y) as a public key with the curve's parameters. */
    fun publicKey(point: ByteArray): PublicKey {
        require(point.size == 65 && point[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val x = java.math.BigInteger(1, point.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, point.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params()))
    }

    private fun params(): java.security.spec.ECParameterSpec {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return (g.generateKeyPair().public as ECPublicKey).params
    }

    /** OAEP with SHA-1 and MGF1-SHA-1 and an empty label. */
    val OAEP_SHA1: OAEPParameterSpec = OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
}

/**
 * The desktop JVM's install keys: P-256 software keys (or RSA-2048 for the
 * tests of wrap 2) in a file of the SDK's folder, never sent anywhere. `hw 0` and signal 16 (software key store).
 * The tests use it on Android too, where it stands for a key store.
 */
internal class SoftwareIdentity(private val dir: File, private val rsaAgreement: Boolean = false) : InstallIdentity {
    private val file = File(dir, "install-keys.json")
    private val lock = Any()
    private var keys: MutableMap<String, Any?> = load()

    @Suppress("UNCHECKED_CAST")
    private fun load(): MutableMap<String, Any?> = try {
        (Json.parse(file.readText()) as Map<String, Any?>).toMutableMap()
    } catch (_: Exception) {
        LinkedHashMap()
    }

    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "install-keys.json.tmp")
        tmp.writeText(Json.write(keys))
        try { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) } catch (_: Exception) {}
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: Any?) = Base64.getDecoder().decode(s as String)

    @Suppress("UNCHECKED_CAST")
    override fun current(): AgreementKey? = synchronized(lock) {
        val alias = keys["agree"] as? String ?: return null
        val k = keys[alias] as? Map<String, Any?> ?: return null
        AgreementKey(alias, unb64(k["spki"]), k["rsa"] == true)
    }

    @Suppress("UNCHECKED_CAST")
    override fun signingSpki(): ByteArray = synchronized(lock) {
        val s = keys["sign"] as? Map<String, Any?> ?: run {
            val p = ec()
            val m = linkedMapOf<String, Any?>("spki" to b64(p.public.encoded), "pkcs8" to b64(p.private.encoded))
            keys["sign"] = m
            save()
            m
        }
        unb64(s["spki"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun privateKey(entry: Map<String, Any?>, algo: String): PrivateKey =
        KeyFactory.getInstance(algo).generatePrivate(PKCS8EncodedKeySpec(unb64(entry["pkcs8"])))

    @Suppress("UNCHECKED_CAST")
    override fun sign(message: ByteArray): ByteArray {
        signingSpki()
        val s = synchronized(lock) { keys["sign"] as Map<String, Any?> }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(s, "EC"))
            update(message)
            sign()
        }
    }

    private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override fun prepare(challenge: String): AgreementKey = synchronized(lock) {
        val alias = "agree." + b64(ByteArray(6).also { SecureRandom().nextBytes(it) }).replace('/', '_').replace('+', '-')
        val p = if (rsaAgreement) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() else ec()
        keys[alias] = linkedMapOf("spki" to b64(p.public.encoded), "pkcs8" to b64(p.private.encoded), "rsa" to rsaAgreement)
        save()
        AgreementKey(alias, p.public.encoded, rsaAgreement)
    }

    override fun commit(k: AgreementKey) = synchronized(lock) {
        val old = keys["agree"] as? String
        keys["agree"] = k.alias
        if (old != null && old != k.alias) keys.remove(old)
        save()
    }

    override fun discard(k: AgreementKey) = synchronized(lock) {
        if (keys["agree"] != k.alias) keys.remove(k.alias)
        save()
    }

    @Suppress("UNCHECKED_CAST")
    override fun agree(k: AgreementKey, eph: ByteArray): ByteArray {
        val e = synchronized(lock) { keys[k.alias] as? Map<String, Any?> } ?: throw IllegalStateException("no key ${k.alias}")
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey(e, "EC"))
            doPhase(P256.publicKey(eph), true)
            generateSecret()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun decryptRsa(k: AgreementKey, c: ByteArray): ByteArray {
        val e = synchronized(lock) { keys[k.alias] as? Map<String, Any?> } ?: throw IllegalStateException("no key ${k.alias}")
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, privateKey(e, "RSA"), P256.OAEP_SHA1)
            doFinal(c)
        }
    }

    override val signals: Int get() = Core.SIGNAL_SOFTWARE_KEYSTORE

    override fun reset() = synchronized(lock) {
        keys.clear()
        file.delete()
        Unit
    }

    companion object {
        /** The public key of an SPKI (tests). */
        fun publicKey(spki: ByteArray, algo: String = "EC"): PublicKey = KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(spki))
    }
}

/**
 * The desktop JVM's state vault: an AES-256-GCM key and the counter in files of the SDK's folder. Without a key store
 * this is obscurity against another user at most; the Core says so for platforms without one (docs/hardening.md).
 */
internal class FileStateVault(private val dir: File) : StateVault {
    private val keyFile = File(dir, "state.key")
    private val counterFile = File(dir, "state.counter")

    private fun key(): ByteArray = synchronized(this) {
        if (keyFile.isFile && keyFile.length() == 32L) return keyFile.readBytes()
        dir.mkdirs()
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile.writeBytes(k)
        k
    }

    override fun seal(data: ByteArray): ByteArray? = try {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
        iv + c.doFinal(data)
    } catch (_: Exception) {
        null
    }

    override fun open(data: ByteArray): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (_: Exception) {
        null
    }

    override fun counterGet(): Long = try {
        if (!counterFile.isFile) 0L else counterFile.readText().trim().toLong()
    } catch (_: Exception) {
        -1L
    }

    override fun counterRaise(v: Long): Boolean = synchronized(this) {
        try {
            val now = counterGet()
            if (now >= 0 && v <= now) return true
            dir.mkdirs()
            val tmp = File(dir, "state.counter.tmp")
            tmp.writeText(v.toString())
            if (!tmp.renameTo(counterFile)) { counterFile.delete(); tmp.renameTo(counterFile) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
