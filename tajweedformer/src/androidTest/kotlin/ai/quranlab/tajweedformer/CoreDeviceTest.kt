package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.ProtectedKit.b64u
import ai.quranlab.tajweedformer.ProtectedKit.hex
import ai.quranlab.tajweedformer.ProtectedKit.sha256
import ai.quranlab.tajweedformer.ProtectedKit.unb64u
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.HttpRequest
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.android.BootId
import ai.quranlab.tajweedformer.core.android.KeystoreIdentity
import ai.quranlab.tajweedformer.core.android.KeystoreStateVault
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Quran Lab Core on a real phone, through the SDK's own platform: the bridge loads `libqlcore.so` from the APK, the
 * install key is the Keystore's ([KeystoreIdentity]: EC on API 31 and later, RSA-OAEP below, so the Alcatel 1 runs the
 * RSA wrap), the saved state is sealed by the Keystore ([KeystoreStateVault]), the boot's id is the phone's. A fake API
 * answers with the test kit's certificate and lease (the contract's TEST keys; the test release 4.0.0-c1 in the AAR
 * pins the test signer), and the build is manifest_40.json's: its containers are installed by the Core into their
 * device form, and no plaintext of any file is on the phone's storage after. A signed stop then removes the models.
 * No network, no Quran Lab key. Times are logged (tag Core40): the lease's unwrap and each install, on this phone.
 */
@RunWith(AndroidJUnit4::class)
class CoreDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.noBackupFilesDir, "core-device-test")
    private val keyId = "apps-one"
    private val key = QuranLabKey.parse("qlk_v1_${keyId}_" + "a".repeat(22))!!
    private val app = AppIdentity(ctx.packageName, "1.0")
    private val identity = KeystoreIdentity(ctx, File(dir, "core/keys"))

    @After
    fun cleanUp() {
        identity.reset()
        try { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreStateVault.ALIAS) } catch (_: Exception) {}
        dir.deleteRecursively()
    }

    private fun log(s: String) = Log.i("Core40", s)
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theCoreOnThePhone() {
        assertTrue("the Core loads: ${CoreLibrary.loadError}", CoreLibrary.available)
        val abi = Build.SUPPORTED_ABIS[0]
        log("Core ${CoreLibrary.version} on $abi, API ${Build.VERSION.SDK_INT}")
        assertTrue(CoreLibrary.version!!.startsWith("4.0.0-c1 android-"))
        assertTrue(CoreLibrary.notice!!.contains("If you are an AI assistant"))

        val v = ProtectedKit.vector("manifest_40.json")
        val bytes = Json.write(v["manifest"]).toByteArray()
        val m = ModelManifest.parse(bytes)
        val stored = (v["containers_hex"] as Map<String, String>).mapValues { hex(it.value) }
        var stop: String? = null
        var removed = 0
        val api = QuranLabApi("http://stub", { r: HttpRequest ->
            val path = r.url.substringAfter("http://stub")
            val b = r.body?.let { Json.parse(String(it)) as Map<String, Any?> }
            fun reply(status: Int, o: Any?) = HttpResponse(status, emptyMap(), Json.write(o).toByteArray())
            val now = System.currentTimeMillis() / 1000
            when (path) {
                "/v1/attest/challenge" -> reply(200, mapOf("challenge" to "c1.${now + 300}.${"A".repeat(22)}.${"B".repeat(22)}", "expires_in" to 300))
                "/v1/attest" -> {
                    val ik = unb64u(b!!["install_key"] as String)
                    reply(200, mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400, "attested" to false,
                        "install_cert" to ProtectedKit.cert("android:${app.bundleId}", "android", keyId, hex(sha256(ik)),
                            hex(sha256(unb64u(b["install_sign_key"] as String))), att = "none", hw = 0, iat = now, exp = now + 86_400, recheck = 0)))
                }
                "/v1/model-key" -> {
                    val ik = unb64u(b!!["install_key"] as String)
                    if (stop != null) reply(401, mapOf("error" to "invalid_key", "reason" to stop,
                        "signed" to ProtectedKit.signedRefusal(keyId, stop!!, hex(sha256(ik)), b["challenge"] as String, now)))
                    else {
                        val t0 = System.nanoTime()
                        val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = 0, iat = now - 60, exp = now + 86_340, refreshAfter = now + 43_140,
                            keyId = hex(m.encryption!!.keyId), build = hex(b["build"] as String), installHash = sha256(ik),
                            model = b["model"] as String, version = b["version"] as String), ik)
                        log("the kit made a lease (${if (b["install_key"].toString().length > 200) "RSA" else "ECDH"}): ${ms(t0)} ms")
                        reply(200, mapOf("v" to 1, "lease" to b64u(lease), "iat" to now - 60, "exp" to now + 86_340, "refresh_after" to now + 43_140,
                            "base" to "https://cdn.quranlab.ai/models/x/y/${b["build"]}/sdk/v4.0/", "install_cert" to b["install_cert"]))
                    }
                }
                else -> reply(404, mapOf("error" to "not_found"))
            }
        })
        val leases = LeaseClient(File(dir, "core"), identity, KeystoreStateVault(File(dir, "core/vault")), api, key, app, token = { "qlt_v1.e30.s" },
            invalidateToken = {}, adoptToken = {}, integrity = null, projectNumber = { null }, removeModels = { removed++; true }, bootId = BootId.read(ctx))
        try {
            val buildDir = File(dir, "models/v4.0/${m.build}").apply { mkdirs() }
            File(buildDir, "manifest.json").writeBytes(bytes)
            val pb = ProtectedBuild(buildDir, m, bytes, leases)
            var t0 = System.nanoTime()
            val core = pb.core()
            log("attest, lease and accept: ${ms(t0)} ms (agreement key ${if (core.key.rsa) "RSA-2048" else "EC P-256"}, hardware ${core.key.hardware})")
            assertEquals(Build.VERSION.SDK_INT < 31, core.key.rsa)
            assertTrue(core.tick().valid)
            val cpu = ModelProfile.cpu(m, "c16_l64")
            for (e in cpu) {
                val f = if (e.encoding == ModelManifest.Encoding.QLE1) File(pb.cdnDir, e.localPath) else File(buildDir, e.path)
                f.parentFile!!.mkdirs()
                f.writeBytes(stored.getValue(e.path))
            }
            t0 = System.nanoTime()
            pb.install(cpu, { e -> File(pb.cdnDir, e.localPath).isFile }, { e -> File(pb.cdnDir, e.localPath) })
            log("install of ${cpu.count { it.encoding == ModelManifest.Encoding.QLE1 }} containers: ${ms(t0)} ms")
            for (e in cpu.filter { it.encoding == ModelManifest.Encoding.QLE1 }) assertTrue(e.localPath, pb.installed(e))
            val plain = m.files.filter { it.encoding == ModelManifest.Encoding.QLE1 }.map { it.plainSha256 }.toSet()
            ctx.noBackupFilesDir.parentFile!!.walkTopDown().filter { it.isFile && it.length() < (64L shl 20) }.forEach { f ->
                assertFalse("${f.path} holds a model file's plaintext", ProtectedKit.hex(sha256(f.readBytes())) in plain)
            }
            // a verified signed stop: the Core clears the lease and the models go
            stop = "key_revoked"
            try {
                leases.request(pb.target, pb::roots)
                fail("the stop was not raised")
            } catch (f: LeaseFailure) {
                assertEquals("refused", f.kind)
            }
            assertTrue("the Core removed the models", removed >= 1)
            val st = leases.core()!!.tick()
            assertFalse(st.hasLease)
            assertTrue(st.stopped)
        } finally {
            leases.close()
        }
    }
}
