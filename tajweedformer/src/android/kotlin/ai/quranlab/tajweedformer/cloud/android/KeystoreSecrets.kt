package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.sharing.KeyStoreContributorKeys
import ai.quranlab.tajweedformer.sharing.KeyStoreSecretBox
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The contributor's P-256 key in the Android Keystore: StrongBox when the device
 * has it, else the TEE; never exportable, signing only, SHA-256. The private key never leaves the
 * Keystore. (Any code running inside the app's own process could ask the Keystore to sign, as on
 * every platform; what the key prevents is deletion from anywhere else: other devices, other apps,
 * the developer's servers, a stolen backup.)
 *
 * The reads (what is "not there" and what cannot be read) are [KeyStoreContributorKeys]'s. A key is made only after
 * the read said that the alias has no entry; nothing here deletes an entry to make one.
 */
internal class AndroidKeystoreContributorKeys(private val context: Context) : KeyStoreContributorKeys() {
    override fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    override fun generate(alias: String): KeyPair {
        val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(alias, strongBox)
        } catch (e: Exception) {
            if (strongBox && (Build.VERSION.SDK_INT >= 28 && e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException)) generate(alias, false) else throw e
        }
    }

    private fun generate(alias: String, strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        initialize(b.build())
        generateKeyPair()
    }

    override fun protection(alias: String): String = try {
        val key = store().getKey(alias, null) as PrivateKey
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

/** AES-256-GCM with a Keystore key (non-exportable): the recovery code is sealed at rest. Sealed = 12-byte IV ||
 *  ciphertext. The key is made only to seal a new code ([KeyStoreSecretBox]). */
internal class KeystoreSecretBox(alias: String = "quranlab.sharing.box") : KeyStoreSecretBox(alias) {
    override fun store(): KeyStore = KeyStore.getInstance(AndroidKeystoreContributorKeys.STORE).apply { load(null) }

    override fun generate(alias: String): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeystoreContributorKeys.STORE).run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }
}
