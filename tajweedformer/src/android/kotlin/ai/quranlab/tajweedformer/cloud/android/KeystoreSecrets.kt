package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.sharing.ContributorKeyStore
import ai.quranlab.tajweedformer.sharing.ContributorKeys
import ai.quranlab.tajweedformer.sharing.SecretBox
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The contributor's P-256 key in the Android Keystore: StrongBox when the device
 * has it, else the TEE; never exportable, signing only, SHA-256. The private key never leaves the
 * Keystore. (Any code running inside the app's own process could ask the Keystore to sign, as on
 * every platform; what the key prevents is deletion from anywhere else: other devices, other apps,
 * the developer's servers, a stolen backup.)
 */
class AndroidKeystoreContributorKeys(private val context: Context) : ContributorKeyStore {
    private val ks: KeyStore get() = KeyStore.getInstance(STORE).apply { load(null) }

    override fun create(alias: String): ByteArray {
        try { ks.deleteEntry(alias) } catch (_: Exception) {}
        val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val pair = try {
            generate(alias, strongBox)
        } catch (e: Exception) {
            if (strongBox && (Build.VERSION.SDK_INT >= 28 && e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException)) generate(alias, false) else throw e
        }
        return ContributorKeys.point(pair.public as ECPublicKey)
    }

    private fun generate(alias: String, strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        initialize(b.build())
        generateKeyPair()
    }

    override fun publicKey(alias: String): ByteArray? = try {
        (ks.getCertificate(alias)?.publicKey as? ECPublicKey)?.let(ContributorKeys::point)
    } catch (_: Exception) {
        null
    }

    override fun sign(alias: String, message: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    override fun delete(alias: String) {
        try { ks.deleteEntry(alias) } catch (_: Exception) {}
    }

    override fun protection(alias: String): String = try {
        val key = ks.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java)
        when {
            Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
            Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
            @Suppress("DEPRECATION") info.isInsideSecureHardware -> "tee"
            else -> "software"
        }
    } catch (_: Exception) {
        "unknown"
    }

    companion object {
        const val STORE = "AndroidKeyStore"
    }
}

/** AES-256-GCM with a Keystore key (non-exportable): the recovery code is sealed at rest. Sealed = 12-byte IV || ciphertext. */
class KeystoreSecretBox(private val alias: String = "quranlab.sharing.box") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(AndroidKeystoreContributorKeys.STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeystoreContributorKeys.STORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}
