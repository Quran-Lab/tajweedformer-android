package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SDK 3.1.4: the reads of the contributor's device key and of the sealed code over the Android Keystore's answers
 * ([FakeAndroidKeyStore]). "Not there" is only the Keystore's answer that the alias has no entry; every other answer is
 * thrown; nothing is made by a read.
 */
class KeystoreKeysTest : OpenChecked() {
    private val fake = FakeAndroidKeyStore()
    private val keys = keystoreKeys(fake)
    private val box = keystoreBox(fake)

    private fun throws(what: String, call: () -> Any?) {
        val got = try { call() } catch (_: Exception) { return }
        fail("$what: no exception, got $got")
    }

    @Test
    fun anAliasWithNoEntryIsTheOnlyNotThere() {
        assertNull(keys.publicKey("quranlab.contributor.a"))
        val point = keys.create("quranlab.contributor.a")
        assertArrayEquals(point, keys.publicKey("quranlab.contributor.a"))
        for (fault in FakeAndroidKeyStore.Fault.values()) {
            fake.fail["quranlab.contributor.a"] = fault
            throws("$fault") { keys.publicKey("quranlab.contributor.a") }
            throws("$fault, signing") { keys.sign("quranlab.contributor.a", "m".toByteArray()) }
        }
        fake.fail.clear()
        fake.down = true
        throws("the Keystore down") { keys.publicKey("quranlab.contributor.a") }
        // an alias with no entry, while the Keystore is down: not "not there" either
        throws("the Keystore down, another alias") { keys.publicKey("quranlab.contributor.b") }
        fake.down = false
        assertArrayEquals("the key that was there", point, keys.publicKey("quranlab.contributor.a"))
        assertEquals("a key made by a read", 1, fake.madeUnder("quranlab.contributor."))
    }

    @Test
    fun aSealedCodeWhoseKeyIsNotThereCannotBeOpenedAndNoKeyIsMade() {
        val sealed = box.seal("ABCD".toByteArray())
        assertEquals(1, fake.made[BOX_ALIAS])
        assertArrayEquals("ABCD".toByteArray(), box.open(sealed))
        fake.lose(BOX_ALIAS)
        throws("the sealing key gone") { box.open(sealed) }
        assertFalse("a sealing key was made by a read", fake.has(BOX_ALIAS))
        fake.generateAes(BOX_ALIAS)
        throws("another sealing key") { box.open(sealed) }
        for (fault in FakeAndroidKeyStore.Fault.values()) {
            fake.fail[BOX_ALIAS] = fault
            throws("$fault") { box.open(sealed) }
        }
    }
}
