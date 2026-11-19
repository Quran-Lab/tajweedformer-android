package ai.quranlab.tajweedformer.core.android

import ai.quranlab.tajweedformer.core.AgreementKey
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.InstallIdentity
import ai.quranlab.tajweedformer.core.P256
import ai.quranlab.tajweedformer.core.StateVault
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The install's keys in the Android Keystore:
 *
 * - the agreement key, made NEW at every attest under a new alias with `setAttestationChallenge(<UTF-8 of the
 *   server's challenge>)`, never with an attest key (`setAttestKeyAlias`): from API 31 EC P-256 with
 *   `PURPOSE_AGREE_KEY` (ECDH in the Keystore); from API 26 to 30 (the Alcatel 1 is API 30) RSA-2048 with
 *   `PURPOSE_DECRYPT`, OAEP with SHA-1 and MGF1-SHA-1 (18.4.6: the one form the server's WebCrypto and the Keystore
 *   before API 34 both do). StrongBox (`setIsStrongBoxBacked`) where the device has `FEATURE_STRONGBOX_KEYSTORE`
 *   (API 28 and later), else the TEE; a software-only Keystore is allowed (its chain says so, and the server decides);
 * - the signing key, EC P-256 `PURPOSE_SIGN` with SHA-256, same backing, made once: it signs `install_sig`.
 *
 * Which alias is the install's is kept in `<dir>/install-key.json`. On a refusal of the attest the new key is deleted
 * and the old one stays; on 200 the old one is deleted. The private keys never leave the Keystore.
 */
internal class KeystoreIdentity(private val context: Context, private val dir: File) : InstallIdentity {
    private val file = File(dir, "install-key.json")
    private val lock = Any()

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private val strongBoxPresent: Boolean by lazy {
        Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    /** alias, rsa, hw, and (from Stage B, for diagnose()'s keyStore) StrongBox: a fourth word `sb`, absent before. */
    private fun readCurrent(): Triple<String, Boolean, Boolean>? = try {
        val t = file.readText().trim().split(' ')
        if (t.size >= 3) Triple(t[0], t[1] == "rsa", t[2] == "hw") else null
    } catch (_: Exception) {
        null
    }

    private fun readStrongBox(): Boolean = try { file.readText().trim().split(' ').getOrNull(3) == "sb" } catch (_: Exception) { false }

    override fun current(): AgreementKey? = synchronized(lock) {
        val (alias, rsa, hw) = readCurrent() ?: return null
        val cert = try { store().getCertificate(alias) } catch (_: Exception) { null } ?: return null
        AgreementKey(alias, cert.publicKey.encoded, rsa, hardware = hw, strongBox = hw && readStrongBox())
    }

    private fun signingKey(): Pair<PrivateKey, ByteArray> = synchronized(lock) {
        val ks = store()
        val existing = try { ks.getKey(SIGN_ALIAS, null) as? PrivateKey } catch (_: Exception) { null }
        if (existing != null) return existing to ks.getCertificate(SIGN_ALIAS).publicKey.encoded
        fun make(strongBox: Boolean) {
            val b = KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(b.build()) }.generateKeyPair()
        }
        try {
            make(strongBoxPresent)
        } catch (e: Exception) {
            if (!strongBoxPresent || !isStrongBoxRefusal(e)) throw e
            make(false)
        }
        (ks.getKey(SIGN_ALIAS, null) as PrivateKey) to ks.getCertificate(SIGN_ALIAS).publicKey.encoded
    }

    override fun signingSpki(): ByteArray = signingKey().second

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signingKey().first)
        update(message)
        sign()
    }

    override fun prepare(challenge: String): AgreementKey = synchronized(lock) {
        val alias = AGREE_PREFIX + java.lang.Long.toHexString(SecureRandom().nextLong() and Long.MAX_VALUE)
        val rsa = Build.VERSION.SDK_INT < 31
        val c = challenge.toByteArray(Charsets.UTF_8)
        fun make(strongBox: Boolean) {
            val b = if (!rsa) {
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            } else {
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            }
            b.setAttestationChallenge(c)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            KeyPairGenerator.getInstance(if (rsa) KeyProperties.KEY_ALGORITHM_RSA else KeyProperties.KEY_ALGORITHM_EC, STORE)
                .apply { initialize(b.build()) }.generateKeyPair()
        }
        var strongBox = strongBoxPresent
        try {
            make(strongBox)
        } catch (e: Exception) {
            if (!strongBox || !isStrongBoxRefusal(e)) throw e
            strongBox = false
            make(false)
        }
        val ks = store()
        val chain = ks.getCertificateChain(alias)?.map { it.encoded } ?: emptyList()
        val spki = ks.getCertificate(alias).publicKey.encoded
        AgreementKey(alias, spki, rsa, chain, hardware = inSecureHardware(ks, alias, rsa), strongBox = strongBox)
    }

    /** What the Keystore says of where the key lives (KeyInfo): TEE or StrongBox, or software. */
    @Suppress("DEPRECATION")
    private fun inSecureHardware(ks: KeyStore, alias: String, rsa: Boolean): Boolean = try {
        val k = ks.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(if (rsa) KeyProperties.KEY_ALGORITHM_RSA else KeyProperties.KEY_ALGORITHM_EC, STORE)
            .getKeySpec(k, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            info.isInsideSecureHardware
        }
    } catch (_: Exception) {
        false
    }

    override fun commit(k: AgreementKey) = synchronized(lock) {
        val old = readCurrent()?.first
        dir.mkdirs()
        val tmp = File(dir, "install-key.json.tmp")
        tmp.writeText("${k.alias} ${if (k.rsa) "rsa" else "ec"} ${if (k.hardware) "hw" else "sw"}${if (k.strongBox) " sb" else ""}\n")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        if (old != null && old != k.alias) try { store().deleteEntry(old) } catch (_: Exception) {}
    }

    override fun discard(k: AgreementKey) = synchronized(lock) {
        if (readCurrent()?.first != k.alias) try { store().deleteEntry(k.alias) } catch (_: Exception) {}
    }

    private fun privateKey(k: AgreementKey): PrivateKey = store().getKey(k.alias, null) as? PrivateKey
        ?: throw IllegalStateException("the Keystore has no key ${k.alias}")

    override fun agree(k: AgreementKey, eph: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH", STORE).run {
        init(privateKey(k))
        doPhase(P256.publicKey(eph), true)
        generateSecret()
    }

    override fun decryptRsa(k: AgreementKey, c: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, privateKey(k), P256.OAEP_SHA1)
            doFinal(c)
        }

    /** A Keystore whose keys are all software has nothing to attest with: risk signal 16 (18.6.4). */
    override val signals: Int
        get() = if (current()?.hardware == false) Core.SIGNAL_SOFTWARE_KEYSTORE else 0

    override fun reset() = synchronized(lock) {
        try {
            val ks = store()
            for (a in ks.aliases().toList()) if (a.startsWith(AGREE_PREFIX) || a == SIGN_ALIAS) ks.deleteEntry(a)
        } catch (_: Exception) {
        }
        file.delete()
        Unit
    }

    private fun isStrongBoxRefusal(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(6).any { Build.VERSION.SDK_INT >= 28 && it is StrongBoxUnavailableException }

    companion object {
        const val STORE = "AndroidKeyStore"
        const val AGREE_PREFIX = "quranlab.install.agree."
        const val SIGN_ALIAS = "quranlab.install.sign.1"
    }
}

/**
 * The Core's saved state sealed by a device-bound AES-256-GCM key in the Android Keystore that is not the agreement
 * key (quranlab-core docs/spec-fixes/10), so the state survives a new install key and a new Core release. The monotonic
 * counter is kept in a file sealed by the same key (Android gives apps no hardware counter: a restored copy of the
 * whole folder rolls both back; the Core then asks the server's time again at the next lease).
 */
internal class KeystoreStateVault(private val dir: File) : StateVault {
    private val counterFile = File(dir, "state.counter")

    private fun key(): SecretKey = synchronized(this) {
        val ks = KeyStore.getInstance(KeystoreIdentity.STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreIdentity.STORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    override fun seal(data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.iv + c.doFinal(data)
    } catch (_: Exception) {
        null
    }

    override fun open(data: ByteArray): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (_: Exception) {
        null
    }

    override fun counterGet(): Long {
        return try {
            if (!counterFile.isFile) 0L else {
                val v = open(counterFile.readBytes()) ?: return -1L
                String(v, Charsets.US_ASCII).toLong()
            }
        } catch (_: Exception) {
            -1L
        }
    }

    override fun counterRaise(v: Long): Boolean = synchronized(this) {
        try {
            val now = counterGet()
            if (now >= 0 && v <= now) return true
            val sealed = seal(v.toString().toByteArray(Charsets.US_ASCII)) ?: return false
            dir.mkdirs()
            val tmp = File(dir, "state.counter.tmp")
            java.io.FileOutputStream(tmp).use { o -> o.write(sealed); o.fd.sync() }
            if (!tmp.renameTo(counterFile)) { counterFile.delete(); tmp.renameTo(counterFile) }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val ALIAS = "quranlab.core.state.1"
    }
}

/** The boot's id for the Core's clock (18.4.7): SHA-256 of `Settings.Global.BOOT_COUNT`, read once per process. */
internal object BootId {
    fun read(context: Context): ByteArray {
        val count = try {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            -1
        }
        val text = if (count >= 0) "boot-count:$count" else {
            // no count: the boot's start, to the minute, from the two clocks (a wall clock change looks like a reboot,
            // which can only end a lease early, never lengthen it)
            "boot-at:${(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000}"
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
