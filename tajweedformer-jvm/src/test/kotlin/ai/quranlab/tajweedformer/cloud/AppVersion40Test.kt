package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.sharing.FileContributorKeyStore
import ai.quranlab.tajweedformer.sharing.PlainSecretBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract section 27 (`sdk_4.0.0/APP_VERSION_40.md`, SDK 4.0.1): every config request carries the app's version and
 * build, cleaned by 27.1's one rule, and register sends the same cleaned version. Through the cloud side's own requests
 * against the stand-in API, which records each query and body (27.3 checks 1, 2, 3, 6 and 7; Android's own reading of
 * `versionName` and `longVersionCode` is the device's).
 */
class AppVersion40Test : ai.quranlab.tajweedformer.OpenChecked() {
    private val stub = StubQlab()
    private val dirs = ArrayList<File>()

    @After
    fun down() {
        stub.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun cloud(version: String?, build: String?): QuranLabCloud {
        val dir = Files.createTempDirectory("tj-appver-").toFile().also { dirs += it }
        File(dir, "cloud/started").let { it.parentFile.mkdirs(); it.writeText("") }
        val env = CloudEnvironment(dir, AppIdentity(stub.appId, AppIdentity.cleanVersion(version), "Recite", build = build),
            DeviceProfile("Pixel8", "tensor_g3", 15, "15", "8_16"), FileContributorKeyStore(File(dir, "keys")), PlainSecretBox,
            IntegrityProvider { h, n -> stub.play(h, n) }, CloudScheduler.Manual, stub.transport, stub.clock)
        return QuranLabCloud(CloudOptions(stub.key, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)), env)
    }

    private fun configs() = stub.seen.filter { it.path == "/v1/config" }
    private fun names(q: String) = q.split('&').map { it.substringBefore('=') }

    /** 27.1, in its order: ASCII 0-9 A-Z a-z . + - only, the leading . + - dropped, then the first 32; "0" when nothing is
     *  left. The leading sign before 40 digits tells the order apart: 4.0.0 cut first and kept 31. */
    @Test
    fun theOneCleaning() {
        val digits = "1234567890".repeat(4)
        for ((raw, clean) in listOf(" 7.1 (beta)" to "7.1beta", "-7.1" to "7.1", "v2.0+42" to "v2.0+42", "7.1-\u00E9" to "7.1-", "..." to "0",
            "0" to "0", "" to "0", null to "0", digits to digits.take(32), "-$digits" to digits.take(32), "+.-${digits}" to digits.take(32),
            "\uFF17.\uFF11" to "0", "7.1+beta" to "7.1+beta")) {
            assertEquals("'$raw'", clean, AppIdentity.cleanVersion(raw))
            assertTrue("'$raw' is a version register takes", clean == "0" || Regex("^[0-9A-Za-z][0-9A-Za-z.+-]*$").matches(clean) && clean.length <= 32)
        }
    }

    /** 27.3 checks 1, 3, 6 and 7: both parameters on the first config request and on a refresh with If-None-Match, after
     *  the parameters of 4.0.0 and in their order; register sends the same cleaned version; `+` as `%2B`. */
    @Test
    fun theConfigRequestCarriesTheVersionAndTheBuild() {
        val c = cloud("7.1+beta", " 412 ")
        c.launch()
        c.refreshConfig()
        val q = configs()
        assertTrue("two config requests: ${q.size}", q.size >= 2)
        for (s in q) {
            assertEquals(listOf("bucket", "sdk", "app_version", "app_build"), names(s.query.removePrefix("?")))
            assertEquals("7.1%2Bbeta", s.q("app_version"))
            assertEquals("412", s.q("app_build"))
        }
        assertTrue("the refresh is conditional", q.last().headers["if-none-match"] != null)
        assertEquals("7.1+beta", stub.seen.single { it.path == "/v1/register" }.json!!["app_version"])
        // a pin comes before them, as in 4.0.0
        c.modelVersion = "4.0.0"
        c.refreshConfig()
        assertEquals(listOf("bucket", "sdk", "model_version", "app_version", "app_build"), names(configs().last().query.removePrefix("?")))
    }

    /** 27.3 checks 2 and 7: an unknown version or build, or one that cleans to nothing or to "0", is left out of the config
     *  request; register sends "0"; the other examples go as cleaned. */
    @Test
    fun whatIsLeftOutAndWhatIsCleaned() {
        val digits = "9".repeat(40)
        for ((given, sent) in listOf(Triple(null, null, "0") to (null to null), Triple("...", "0", "0") to (null to null),
            Triple("0", "", "0") to (null to null), Triple(" 7.1 (beta)", "-7.1", "7.1beta") to ("7.1beta" to "7.1"),
            Triple("v2.0+42", digits, "v2.0+42") to ("v2.0%2B42" to digits.take(32)))) {
            stub.seen.clear()
            val (version, build, register) = given
            val c = cloud(version, build)
            c.launch()
            val s = configs().first()
            assertEquals("$given", sent.first, s.q("app_version"))
            assertEquals("$given", sent.second, s.q("app_build"))
            if (sent.first == null) assertTrue("$given", "app_version" !in names(s.query.removePrefix("?")))
            if (sent.second == null) assertTrue("$given", "app_build" !in names(s.query.removePrefix("?")))
            assertEquals("$given", register, stub.seen.single { it.path == "/v1/register" }.json!!["app_version"])
            assertNull("register's body never carries the build", stub.seen.single { it.path == "/v1/register" }.json!!["app_build"])
        }
    }
}
