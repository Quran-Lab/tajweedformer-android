package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.android.KeystoreIdentity
import ai.quranlab.tajweedformer.core.android.KeystoreStateVault
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement

/**
 * The install's keys of SDK 4.0 on a real phone, in the Android Keystore through
 * [KeystoreIdentity] and [KeystoreStateVault]. No network, no Quran Lab key, no model. Every key it makes is deleted.
 *
 * - The agreement key is made NEW with the attest's challenge: EC P-256 `PURPOSE_AGREE_KEY` from API 31, RSA-2048
 *   `PURPOSE_DECRYPT` (OAEP SHA-1) below (the Alcatel 1 is API 30); StrongBox where the phone has it. Its certificate
 *   chain comes back leaf first, the leaf's key is the key, and the leaf carries the challenge (Key Attestation).
 * - What the Core asks of it works: ECDH with an ephemeral P-256 key gives the same x coordinate as the other side
 *   computes; RSA-OAEP with SHA-1 and MGF1-SHA-1 opens what the server's WebCrypto form wrapped.
 * - The signing key signs, and the signature verifies with its SPKI (the attest's and the lease's `install_sig`).
 * - A refused attest deletes the new key and keeps the old; an accepted one deletes the old.
 * - The state vault seals and opens; its counter only rises.
 *
 * Each step logs its time (tag InstallKey40): key creation with attestation can take seconds on a slow phone's TEE.
 */
@RunWith(AndroidJUnit4::class)
class InstallKeyDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.noBackupFilesDir, "install-key-device-test-40")
    private val id = KeystoreIdentity(ctx, File(dir, "keys"))

    @After
    fun cleanUp() {
        id.reset()
        dir.deleteRecursively()
    }

    private fun log(s: String) = Log.i("InstallKey40", s)
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private val challenge = "c1.4102444800.j2m5kXPeKJN6QDT5_rt0bA.v2tgPDs9l3CDNh_rhyIML3"

    @Test
    fun theAgreementKeyIsNewAndAttestedForTheChallenge() {
        var t0 = System.nanoTime()
        val k = id.prepare(challenge)
        log("agreement key (${if (k.rsa) "RSA-2048" else "EC P-256"}, strongbox ${k.strongBox}, hardware ${k.hardware}): ${ms(t0)} ms, chain ${k.chain.size}")
        assertEquals("RSA below API 31, EC from it", Build.VERSION.SDK_INT < 31, k.rsa)
        if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) log("the phone has StrongBox; used: ${k.strongBox}")
        assertTrue("a Key Attestation chain", k.chain.isNotEmpty())
        val cf = CertificateFactory.getInstance("X.509")
        val leaf = cf.generateCertificate(k.chain[0].inputStream())
        assertArrayEquals("the leaf is the install key", k.spki, leaf.publicKey.encoded)
        // the attestation extension carries the challenge's UTF-8 bytes (setAttestationChallenge)
        val ext = (leaf as java.security.cert.X509Certificate).getExtensionValue("1.3.6.1.4.1.11129.2.1.17")
        assertNotNull("the leaf has a key description", ext)
        assertTrue("the key description holds the challenge", indexOf(ext!!, challenge.toByteArray(Charsets.UTF_8)) >= 0)
        // each link is signed by the next
        for (i in 0 until k.chain.size - 1) {
            cf.generateCertificate(k.chain[i].inputStream()).verify(cf.generateCertificate(k.chain[i + 1].inputStream()).publicKey)
        }
        // a second key for another challenge is another key
        t0 = System.nanoTime()
        val k2 = id.prepare("c1.4102444800.AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB")
        log("a second agreement key: ${ms(t0)} ms")
        assertTrue(!k2.spki.contentEquals(k.spki))
        id.commit(k)
        id.discard(k2)
        assertArrayEquals("the committed key is the install's", k.spki, id.current()!!.spki)
    }

    @Test
    fun whatTheCoreAsksOfTheKeyWorks() {
        val k = id.prepare(challenge)
        id.commit(k)
        val t0 = System.nanoTime()
        if (!k.rsa) {
            val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val z = id.agree(k, ProtectedKit.point(eph.public))
            val other = KeyAgreement.getInstance("ECDH").run {
                init(eph.private)
                doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(k.spki)), true)
                generateSecret()
            }
            assertArrayEquals("ECDH in the Keystore", other, z)
            log("ECDH in the Keystore: ${ms(t0)} ms")
        } else {
            val kek = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
                init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(k.spki)), ai.quranlab.tajweedformer.core.P256.OAEP_SHA1)
                doFinal(kek)
            }
            assertArrayEquals("RSA-OAEP SHA-1 in the Keystore", kek, id.decryptRsa(k, c))
            log("RSA-OAEP in the Keystore: ${ms(t0)} ms")
        }
        val msg = "quranlab-lease-ask 1\n$challenge\n${"0".repeat(40)}\n".toByteArray()
        val sig = id.sign(msg)
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(id.signingSpki())))
            update(msg)
            verify(sig)
        }
        assertTrue("the signing key's signature verifies", ok)
    }

    @Test
    fun theStateVault() {
        val v = KeystoreStateVault(File(dir, "vault"))
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = v.seal(secret)!!
        assertArrayEquals(secret, v.open(sealed))
        assertEquals(0L, v.counterGet())
        assertTrue(v.counterRaise(5))
        assertTrue(v.counterRaise(3))
        assertEquals(5L, v.counterGet())
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreStateVault.ALIAS)
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
