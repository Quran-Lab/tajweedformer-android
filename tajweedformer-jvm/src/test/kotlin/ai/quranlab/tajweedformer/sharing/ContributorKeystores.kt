package ai.quranlab.tajweedformer.sharing

import java.security.KeyPair
import java.security.KeyStore
import javax.crypto.SecretKey

/*
 * The Android SDK's Keystore classes over [FakeAndroidKeyStore], for the JVM tests of the contributor: the SDK's own
 * reads ([KeyStoreContributorKeys], [KeyStoreSecretBox], which `AndroidKeystoreContributorKeys` and `KeystoreSecretBox`
 * extend), with the fake's store and generators in place of the Android Keystore's.
 *
 * The red run of these tests (on the code of 3.1.3) had here 3.1.3's two Android classes ported line for line; the
 * commit "Tests of the contributor's id, device key and recovery code, written before the change" holds that version.
 */

/** `AndroidKeystoreContributorKeys` over the fake. */
internal fun keystoreKeys(fake: FakeAndroidKeyStore): ContributorKeyStore = object : KeyStoreContributorKeys() {
    override fun store(): KeyStore = fake.keyStore()
    override fun generate(alias: String): KeyPair = fake.generateEc(alias)
}

/** `KeystoreSecretBox` over the fake. */
internal fun keystoreBox(fake: FakeAndroidKeyStore, alias: String = BOX_ALIAS): SecretBox = object : KeyStoreSecretBox(alias) {
    override fun store(): KeyStore = fake.keyStore()
    override fun generate(alias: String): SecretKey = fake.generateAes(alias)
}

/** The alias of the box's AES key (`KeystoreSecretBox`'s default). */
internal const val BOX_ALIAS = "quranlab.sharing.box"
