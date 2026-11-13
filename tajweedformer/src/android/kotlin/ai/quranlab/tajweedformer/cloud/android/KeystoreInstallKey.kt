package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.InstallKeyFailures
import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.InstallKeyUnavailable
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The device key of the install records on Android (SPEC 15.2): a 256-bit HMAC-SHA256 key made in the Android
 * Keystore at first need, never exportable. The Keystore computes the MAC; the key never leaves it, so an app's files
 * (a backup, a copy from another device, a folder shipped in the app) cannot carry it.
 *
 * - No key under the alias (never made, or the Keystore was cleared): [mac] is null, so no record is valid, and
 *   [create] makes a new key.
 * - The Keystore says the key is gone for good ([goneForGood]: permanently invalidated, unrecoverable, and on Android
 *   13 and later the Keystore's own codes for a key that does not exist or is corrupted): the same, "no key"; what is
 *   left under the alias is removed.
 * - The Keystore fails in another way (it does not load, a call throws): [InstallKeyUnavailable] with the exception as
 *   its cause, and nothing is opened, deleted or downloaded at that start. The same failure at three starts in a row
 *   makes a new key ([ai.quranlab.tajweedformer.InstallKeyGuard], which calls [replace]).
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
            if (goneForGood(e)) return forget(ks)
            throw InstallKeyUnavailable("the Android Keystore did not give the key: ${e.javaClass.simpleName}", e)
        } ?: return null
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(message)
            }
        } catch (e: Exception) {
            // gone for good: as if there were none (a new one is made, every record is invalid)
            if (goneForGood(e)) return forget(ks)
            throw InstallKeyUnavailable("the Android Keystore did not compute the MAC: ${e.javaClass.simpleName}", e)
        }
    }

    /** What is left of a key that is gone is removed; the answer is "no key". */
    private fun forget(ks: KeyStore): ByteArray? {
        try { ks.deleteEntry(alias) } catch (_: Exception) {}
        return null
    }

    /**
     * Whether [e] says the key is gone for good (SPEC 15.2, contract 1.14.0): permanently invalidated or unrecoverable
     * by its class ([InstallKeyFailures]); on Android 13 and later the Keystore's own code decides where it gives one:
     * a key that does not exist or is corrupted is gone, and a failure the Keystore calls transient is never "gone",
     * whatever exception carries it.
     */
    private fun goneForGood(e: Exception): Boolean {
        if (Build.VERSION.SDK_INT >= 33) keystoreSays(e)?.let { return it }
        return InstallKeyFailures.goneForGood(e)
    }

    /** The Keystore's own word on [e] or one of its causes: true gone for good, false a failure that passes, null none. */
    @RequiresApi(33)
    private fun keystoreSays(e: Exception): Boolean? {
        val k = InstallKeyFailures.chain(e).firstOrNull { it is android.security.KeyStoreException } as? android.security.KeyStoreException ?: return null
        return when {
            k.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST ||
                k.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_CORRUPTED -> true
            k.isTransientFailure -> false
            else -> null
        }
    }

    /** A new key in the place of one the Keystore cannot use any more: the entry is removed (as far as the Keystore
     *  lets it), then the key is made under the same alias. */
    override fun replace() {
        try { store().deleteEntry(alias) } catch (_: Exception) {}
        create()
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
