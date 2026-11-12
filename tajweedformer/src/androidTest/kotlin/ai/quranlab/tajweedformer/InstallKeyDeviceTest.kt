package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.android.KeystoreInstallKey
import android.os.Build
import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The install record's key on a real phone (SPEC 15.2): the Android Keystore's HMAC key that [KeystoreInstallKey] makes,
 * through the same [InstallRecords] the Client uses. Stand-in files only: no model, no network, no Quran Lab key. Its
 * own key aliases, so the SDK's own key on the phone is never touched.
 *
 * 1. [aTheKeyAndTheRecords]: no key yet reads as "no key"; the key is made; a `downloading` and an `installed` record
 *    are written and read back as valid; NEW instances (what a new process makes) read the record as valid; a record
 *    made with another Keystore key is not valid. The key and the `installed` record are left for 2.
 * 2. [bAfterARelaunch]: run alone in a second `am instrument` (a new process): the record written by 1 is valid; then
 *    the key is deleted, and the state is "no key" (a null MAC, no record valid, a sweep deletes the folder), not "the
 *    key store failed". Everything this test made is removed. In a run of the whole class it runs after 1 in the same
 *    process.
 *
 * Each step logs its time (tag InstallKeyDevice): key creation, and one record check, the check the Client makes
 * before a model opens.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InstallKeyDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(ctx.noBackupFilesDir, "install-key-device-test")
    private val models = File(root, "models")
    private val buildDir = File(File(models, "v4.0"), BUILD)

    private fun log(s: String) {
        Log.i(TAG, s)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test
    fun aTheKeyAndTheRecords() {
        val ks = store()
        ks.deleteEntry(ALIAS)
        ks.deleteEntry(OTHER)
        root.deleteRecursively()
        log("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")

        // no key yet: "no key", not a failure
        val keys = KeystoreInstallKey(ALIAS)
        val records = InstallRecords(keys)
        assertNull("a MAC without a key", keys.mac("x".toByteArray()))
        assertFalse("the store says there is a key before one was made", records.probe())

        // the key is made (as at the first download)
        var t0 = System.nanoTime()
        records.write(buildDir, BUILD, InstallRecords.State.DOWNLOADING)
        val created = ms(t0)
        val key = store().getKey(ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        @Suppress("DEPRECATION")
        val hardware = info.isInsideSecureHardware
        val level = if (Build.VERSION.SDK_INT >= 31) info.securityLevel.toString() else "n/a below API 31"
        log("key ${key.algorithm} made and the downloading record written in %.1f ms; inside secure hardware: $hardware; security level: $level".format(created))
        assertEquals(InstallRecords.State.DOWNLOADING, records.state(buildDir, BUILD))

        // installed, read back
        records.write(buildDir, BUILD, InstallRecords.State.INSTALLED)
        t0 = System.nanoTime()
        assertEquals(InstallRecords.State.INSTALLED, records.state(buildDir, BUILD))
        log("one record check (installed, valid) in %.1f ms".format(ms(t0)))

        // new instances of the classes, as a new process makes them
        t0 = System.nanoTime()
        assertEquals("a new instance does not read the record as valid", InstallRecords.State.INSTALLED,
            InstallRecords(KeystoreInstallKey(ALIAS)).state(buildDir, BUILD))
        log("new instances: one record check in %.1f ms".format(ms(t0)))

        // a record made with another Keystore key (another device's) is not valid here
        val elsewhere = File(File(File(root, "elsewhere"), "v4.0"), BUILD)
        InstallRecords(KeystoreInstallKey(OTHER)).write(elsewhere, BUILD, InstallRecords.State.INSTALLED)
        assertEquals(InstallRecords.State.INSTALLED, InstallRecords(KeystoreInstallKey(OTHER)).state(elsewhere, BUILD))
        val copied = File(File(File(root, "copied"), "v4.0"), BUILD).apply { mkdirs() }
        File(elsewhere.parentFile, BUILD + InstallRecords.SUFFIX).copyTo(File(copied.parentFile, BUILD + InstallRecords.SUFFIX))
        assertNull("a record made with another key reads as valid", InstallRecords(KeystoreInstallKey(ALIAS)).state(copied, BUILD))
        store().deleteEntry(OTHER)
        File(root, "elsewhere").deleteRecursively()
        File(root, "copied").deleteRecursively()
        log("a record made with another Keystore key: not valid")
        // the key and the installed record stay for bAfterARelaunch
    }

    @Test
    fun bAfterARelaunch() {
        Assume.assumeTrue("no record from aTheKeyAndTheRecords", File(buildDir.parentFile, BUILD + InstallRecords.SUFFIX).isFile)
        log("process ${android.os.Process.myPid()}")
        // the first check in this process: the Keystore loads, the Keystore computes the MAC
        var t0 = System.nanoTime()
        val records = InstallRecords(KeystoreInstallKey(ALIAS))
        assertEquals("the record of the earlier run is not valid", InstallRecords.State.INSTALLED, records.state(buildDir, BUILD))
        log("first record check in this process (installed, valid) in %.1f ms".format(ms(t0)))
        t0 = System.nanoTime()
        assertEquals(InstallRecords.State.INSTALLED, records.state(buildDir, BUILD))
        log("second record check in %.1f ms".format(ms(t0)))

        // the key deleted (a cleared Keystore): "no key", never "the key store failed"
        store().deleteEntry(ALIAS)
        val keys = KeystoreInstallKey(ALIAS)
        assertNull("a MAC without a key", keys.mac("x".toByteArray()))
        val after = InstallRecords(keys)
        assertFalse(after.probe())
        assertNull("a record reads as valid without its key", after.state(buildDir, BUILD))
        File(buildDir, "stand-in.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1024)) }
        after.sweep(models)
        assertFalse("the folder whose record cannot be valid was kept", buildDir.exists())
        log("the key deleted: no key (no exception), no record valid, the folder swept")
        root.deleteRecursively()
        assertTrue(!root.exists())
    }

    private companion object {
        const val TAG = "InstallKeyDevice"
        /** Aliases of this test only (the SDK's own is quranlab.model.install.1). */
        const val ALIAS = "quranlab.model.install.devicetest"
        const val OTHER = "quranlab.model.install.devicetest.other"
        const val BUILD = "0123456789abcdef0123456789abcdef01234567"
    }
}
