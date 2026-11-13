package ai.quranlab.tajweedformer.sharing

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.ProviderException
import java.security.PublicKey
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android Keystore as `java.security.KeyStore` shows it to the SDK on Android 12 and later (keystore2), for the JVM
 * tests. Its answers are AOSP's, read in the Android SDK's sources for API 33 (`android/security/keystore2/
 * AndroidKeyStoreSpi.java` and `AndroidKeyStoreProvider.java`):
 *
 * - `getKey`: null when the alias has no entry (KEY_NOT_FOUND). An entry whose key is permanently invalidated:
 *   `UnrecoverableKeyException` with the message of the `KeyPermanentlyInvalidatedException` it replaces, and no cause.
 *   Any other failure of the Keystore: `UnrecoverableKeyException("Failed to obtain information about key")` whose
 *   cause is the Keystore's own exception (here a `java.security.KeyStoreException` stands in for
 *   `android.security.KeyStoreException`, which the JVM does not have).
 * - `getCertificate`: null for an alias with no entry AND for every failure of the Keystore (`getKeyMetadata` logs the
 *   failure and returns null).
 * - `deleteEntry`: nothing for an alias with no entry; a `KeyStoreException` when the Keystore fails.
 * - `aliases`: empty when the Keystore fails.
 *
 * [fail] sets how one alias's entry answers ([Fault]); [down] makes the whole Keystore fail. Keys are made by
 * [generateEc] and [generateAes], as the Keystore's generators make them: an entry that is there is replaced.
 */
internal class FakeAndroidKeyStore {
    /** How an entry that is there answers. */
    enum class Fault {
        /** The Keystore fails for this entry (a system error). */
        SYSTEM,

        /** The key is permanently invalidated. */
        INVALIDATED,

        /** The Keystore says the key is corrupted. */
        CORRUPTED,
    }

    private class Entry(val key: Key, val publicKey: PublicKey?)

    private val entries = Collections.synchronizedMap(LinkedHashMap<String, Entry>())

    /** The entries that answer with a fault, by alias. */
    val fail: MutableMap<String, Fault> = Collections.synchronizedMap(HashMap())

    /** The whole Keystore fails: no call answers. */
    @Volatile var down = false

    /** Keys made, by alias. */
    val made: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())

    /** The keys made under aliases that start with [prefix]. */
    fun madeUnder(prefix: String): Int = synchronized(made) { made.filterKeys { it.startsWith(prefix) }.values.sum() }

    fun has(alias: String): Boolean = entries.containsKey(alias)

    /** The Keystore lost the entry (as when the device's Keystore was cleared). */
    fun lose(alias: String) {
        entries.remove(alias)
        fail.remove(alias)
    }

    /** The public key of [alias] as it was made (the tests' view, not the Keystore's). */
    fun publicOf(alias: String): PublicKey? = entries[alias]?.publicKey

    fun generateEc(alias: String): KeyPair {
        if (down) throw ProviderException("Failed to generate key pair")
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        entries[alias] = Entry(kp.private, kp.public)
        fail.remove(alias)
        made.merge(alias, 1, Int::plus)
        return kp
    }

    fun generateAes(alias: String): SecretKey {
        if (down) throw ProviderException("Failed to generate key")
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        entries[alias] = Entry(k, null)
        fail.remove(alias)
        made.merge(alias, 1, Int::plus)
        return k
    }

    private fun failed(why: String): UnrecoverableKeyException =
        UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(KeyStoreException(why)) }

    private inner class Spi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (down) throw failed("system error")
            val e = entries[alias] ?: return null
            when (fail[alias]) {
                Fault.INVALIDATED -> throw UnrecoverableKeyException("User changed or deleted their auth credentials")
                Fault.SYSTEM -> throw failed("system error")
                Fault.CORRUPTED -> throw failed("key corrupted")
                null -> {}
            }
            return e.key
        }

        override fun engineGetCertificate(alias: String): Certificate? {
            if (down || fail[alias] != null) return null
            return entries[alias]?.publicKey?.let(::FakeCertificate)
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = engineGetCertificate(alias)?.let { arrayOf(it) }
        override fun engineGetCreationDate(alias: String): Date? = if (!down && entries.containsKey(alias)) Date() else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            if (down) throw KeyStoreException("Failed to delete entry: $alias")
            entries.remove(alias)
            fail.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> =
            if (down) Collections.emptyEnumeration() else Collections.enumeration(synchronized(entries) { entries.keys.toList() })

        override fun engineContainsAlias(alias: String): Boolean = !down && fail[alias] == null && entries.containsKey(alias)
        override fun engineSize(): Int = if (down) 0 else entries.size
        override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    /** A certificate that carries the public key, as the Keystore's self-signed one does. */
    private class FakeCertificate(private val key: PublicKey) : Certificate("X.509") {
        override fun getEncoded(): ByteArray = key.encoded
        override fun verify(key: PublicKey) {}
        override fun verify(key: PublicKey, sigProvider: String) {}
        override fun toString(): String = "FakeCertificate"
        override fun getPublicKey(): PublicKey = key
    }

    @Suppress("DEPRECATION")
    private val provider = object : java.security.Provider("FakeAndroidKeyStore", 1.0, "the Android Keystore's answers, for tests") {}

    /** A loaded `KeyStore` over this Keystore, as `KeyStore.getInstance("AndroidKeyStore").apply { load(null) }` gives one. */
    fun keyStore(): KeyStore = object : KeyStore(Spi(), provider, "AndroidKeyStore") {}.apply { load(null) }
}
