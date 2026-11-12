package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.InstallKeyUnavailable
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The device key of the install records on Android (SPEC 15.2): a 256-bit HMAC-SHA256 key made in the Android
 * Keystore at first need, never exportable. The Keystore computes the MAC; the key never leaves it, so an app's files
 * (a backup, a copy from another device, a folder shipped in the app) cannot carry it.
 *
 * - No key under the alias (never made, or the Keystore was cleared, or the key was invalidated): [mac] is null, so no
 *   record is valid, and [create] makes a new key.
 * - The Keystore fails in another way (it does not load, a call throws): [InstallKeyUnavailable], and nothing is
 *   opened, deleted or downloaded.
 */
internal class KeystoreInstallKey(private val alias: String = "quranlab.model.install.1") : InstallKeyStore {
    private fun store(): KeyStore = try {
        KeyStore.getInstance(AndroidKeystoreContributorKeys.STORE).apply { load(null) }
    } catch (e: Exception) {
        throw InstallKeyUnavailable("the Android Keystore did not load: ${e.javaClass.simpleName}", e)
    }

    override fun mac(message: ByteArray): ByteArray? {
        val ks = store()
        val key = try {
            ks.getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            throw InstallKeyUnavailable("the Android Keystore did not give the key: ${e.javaClass.simpleName}", e)
        } ?: return null
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(message)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // the system invalidated the key: as if there were none (a new one is made, every record is invalid)
            try { ks.deleteEntry(alias) } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            throw InstallKeyUnavailable("the Android Keystore did not compute the MAC: ${e.javaClass.simpleName}", e)
        }
    }

    override fun create() {
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, AndroidKeystoreContributorKeys.STORE).run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build())
                generateKey()
            }
        } catch (e: Exception) {
            throw InstallKeyUnavailable("the Android Keystore did not make the key: ${e.javaClass.simpleName}", e)
        }
    }
}
