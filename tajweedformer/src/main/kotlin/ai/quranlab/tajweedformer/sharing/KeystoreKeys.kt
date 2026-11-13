package ai.quranlab.tajweedformer.sharing

import java.security.KeyPair
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The contributor's device key in a `java.security.KeyStore`: on Android the Android Keystore
 * (`AndroidKeystoreContributorKeys` gives the store and makes the key). Kept outside the Android sources so that its
 * reads are tested on the JVM against the Keystore's own answers.
 *
 * "Not there" is only the Keystore's answer that the alias has no entry: `getKey` gives null. On Android 12 and later
 * that is KEY_NOT_FOUND alone; every other failure is thrown, a key that is permanently invalidated included. It is
 * the answer the install key of SDK 3.1.3 reads, and nothing else is read as "not there": `getCertificate` is never
 * asked whether the entry is there, because on Android it gives null for every failure of the Keystore too. A key
 * that `getKey` found and whose certificate the Keystore does not give cannot be read.
 */
internal abstract class KeyStoreContributorKeys : ContributorKeyStore {
    /** The store, loaded. */
    protected abstract fun store(): KeyStore

    /** Makes the key pair under [alias] in the store. */
    protected abstract fun generate(alias: String): KeyPair

    override fun create(alias: String): ByteArray = ContributorKeys.point(generate(alias).public as ECPublicKey)

    override fun publicKey(alias: String): ByteArray? {
        val ks = store()
        ks.getKey(alias, null) ?: return null
        val cert = ks.getCertificate(alias) ?: throw KeyStoreException("the key under $alias is there and the Keystore gives no certificate for it")
        val pub = cert.publicKey as? ECPublicKey ?: throw KeyStoreException("the key under $alias is not a P-256 key")
        return ContributorKeys.point(pub)
    }

    override fun sign(alias: String, message: ByteArray): ByteArray {
        val key = store().getKey(alias, null) as? PrivateKey ?: throw KeyStoreException("no private key under $alias")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    override fun delete(alias: String) {
        try { store().deleteEntry(alias) } catch (_: Exception) {}
    }
}

/**
 * AES-256-GCM with a key of a `java.security.KeyStore` (on Android the Android Keystore, `KeystoreSecretBox`): the
 * recovery code is sealed at rest. Sealed = 12-byte IV || ciphertext. The key is made only to seal a new code, never
 * by [open]: a sealed code whose key has no entry cannot be read.
 */
internal abstract class KeyStoreSecretBox(private val alias: String) : SecretBox {
    /** The store, loaded. */
    protected abstract fun store(): KeyStore

    /** Makes the AES key under [alias] in the store. */
    protected abstract fun generate(alias: String): SecretKey

    private fun key(make: Boolean): SecretKey {
        val k = store().getKey(alias, null)
        if (k != null) return k as? SecretKey ?: throw KeyStoreException("the key under $alias is not a secret key")
        if (!make) throw KeyStoreException("the sealing key under $alias is not there")
        return generate(alias)
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(make = true))
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(make = false), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}
