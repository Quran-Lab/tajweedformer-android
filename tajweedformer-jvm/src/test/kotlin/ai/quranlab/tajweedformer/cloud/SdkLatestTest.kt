package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Ed25519
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SdkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Collections

/**
 * The signed config's `sdk_latest` (platform client doc, section 3): a debuggable build older than it logs one line
 * per launch; release builds, an equal or older version, and a missing or malformed field log nothing. And the
 * compatibility proof for 2.0.0 apps: [RemoteConfig.verify] checks the Ed25519 signature over the exact body bytes,
 * then reads only the fields it knows, so a config with `sdk_latest` (or any new top-level field) verifies and parses
 * the same; a changed byte does not verify.
 */
class SdkLatestTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private val lines = Collections.synchronizedList(ArrayList<String>())

    @Before fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-latest").toFile()
    }

    @After fun down() {
        stub.close()
        dir.deleteRecursively()
    }

    private fun cloud(debug: Boolean = true) = QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
        stub.environment(File(dir, "s${lines.size}${System.nanoTime()}"), debugBuild = debug, log = { lines.add(it) }))

    private fun latest(version: Any?, severity: Any? = "recommended", url: Any? = "https://quranlab.ai/sdk/android/2.0.2") =
        mapOf("sdk_latest" to mapOf("version" to version, "severity" to severity, "url" to url))

    @Test fun aNewerSdkLogsOneLinePerLaunchInADebuggableBuild() {
        stub.extra = latest("3.1.9")
        val c = cloud()
        c.refreshConfig()
        stub.extra = latest("3.2.0", "required")
        c.refreshConfig()
        assertEquals(listOf("Quran Lab SDK 3.1.9 is available (recommended), you have ${SdkInfo.VERSION}: https://quranlab.ai/sdk/android/2.0.2"),
            lines.toList())
        // the next launch (a new cloud for the process) logs again, once
        cloud().refreshConfig()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("Quran Lab SDK 3.2.0 is available (required), you have "))
    }

    @Test fun equalOlderReleaseOrMalformedLogNothing() {
        // equal, older, and newer ones that are malformed (two parts, no severity, a plain http link, a number, a string)
        for (extra in listOf(latest(SdkInfo.VERSION), latest("2.0.3"), latest("1.9.9"), latest("3.0"), latest("abc"),
            latest("3.1.2", null), latest("3.1.2", "recommended", "http://insecure"), latest(3.2), mapOf("sdk_latest" to "3.1.2"),
            mapOf("sdk_latest" to emptyMap<String, Any?>()), emptyMap())) {
            stub.extra = extra
            val c = cloud()
            c.refreshConfig()
            assertTrue("$extra: ${lines.toList()}", lines.isEmpty())
            assertTrue("the config is still in force with $extra", c.currentConfig != null)
        }
        stub.extra = latest("9.0.0")
        cloud(debug = false).refreshConfig()
        assertTrue("a release build is silent", lines.isEmpty())
    }

    @Test fun dottedNumbersCompareAndJunkIsNeverNewer() {
        val n = { a: String, b: String -> RemoteConfig.SdkLatest.newer(a, b) }
        assertTrue(n("2.0.10", "2.0.9"))
        assertTrue(n("2.1", "2.0.9"))
        assertTrue(n("3", "2.9.9"))
        assertFalse(n("2.0.1", "2.0.1"))
        assertFalse(n("2.0", "2.0.0"))
        assertFalse(n("2.0.1-beta", "2.0.0"))
        assertFalse(n("", "2.0.0"))
        assertFalse(n("2..1", "2.0.0"))
        assertNull(RemoteConfig.SdkLatest.parse(mapOf("version" to "2.0.2", "severity" to "recommended")))
    }

    /** The 2.0.0 parser (this RemoteConfig.verify, unchanged since 2.0.0): unknown top-level fields are ignored and the
     *  signature covers the raw bytes. */
    @Test fun aConfigWithNewTopLevelFieldsVerifiesAndParsesAsBefore() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = kp.public.encoded.copyOfRange(kp.public.encoded.size - 32, kp.public.encoded.size)
        val now = 1_762_700_000L
        val body = Json.write(linkedMapOf("v" to 1, "app" to "android:org.example.recite", "expires_at" to now + 3600,
            "flags" to mapOf("analytics" to true), "sdk_latest" to mapOf("version" to "2.0.2", "severity" to "optional", "url" to "https://x.example/n"),
            "accel_deny" to listOf(mapOf("accelerator" to "npu")), "a_field_no_sdk_knows" to listOf(1, 2, 3))).toByteArray()
        fun sign(b: ByteArray) = "ed25519=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            Signature.getInstance("Ed25519").apply { initSign(kp.private); update(b) }.sign())
        val c = RemoteConfig.verify(body, sign(body), RemoteConfig.keyId(pub), listOf(pub), now)
        assertTrue(c.flags.analytics)
        assertEquals("android:org.example.recite", c.app)
        assertTrue(Ed25519.verify(pub, body, Base64.getUrlDecoder().decode(sign(body).removePrefix("ed25519="))))
        // the signature is over the bytes: one changed byte (inside the new field) does not verify
        val tampered = String(body).replace("2.0.2", "2.0.3").toByteArray()
        try {
            RemoteConfig.verify(tampered, sign(body), RemoteConfig.keyId(pub), listOf(pub), now)
            fail("a changed body verified")
        } catch (e: ConfigRejected) {
            assertEquals("signature does not verify", e.message)
        }
    }
}
