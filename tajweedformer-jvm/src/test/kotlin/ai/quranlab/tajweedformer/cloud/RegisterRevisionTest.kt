package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.MachineInstallKey
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The daily registration names the model the install has (`model_revision`, PLATFORM_CLIENT.md section 5): the
 * version of the Model that is open, else of the build the device holds, whatever the model's id. Until 3.1.3 the
 * Client registered with none.
 *
 * Through the public Client with the stub API and a served build. The model id of the second and third test is made
 * up for the test ("t7.2"): it is no model of Quran Lab's.
 */
class RegisterRevisionTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var served: ServedModel
    private lateinit var dir: File
    private var client: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        stub.inference = null
        served = ServedModel.of()
        dir = Files.createTempDirectory("tj-register-").toFile()
    }

    @After
    fun down() {
        client?.close()
        served.close()
        stub.close()
        dir.deleteRecursively()
    }

    /** A start of the app: a new Client on the same storage (the one before is closed); [launch]: with its launch sequence. */
    private fun start(launch: Boolean): TajweedClient {
        client?.close()
        client = null
        return TajweedClient(stub.key, TajweedClientOptions(),
            gatePlatform(stub, dir, served, MachineInstallKey(machineId = { "this-machine" }, user = { "learner" }), stubPlay(stub)), autoLaunch = launch)
            .also { client = it }
    }

    /** The body of the registration the server got, waited for (the launch sequence runs on the Client's own thread). */
    private fun registered(): Map<String, Any?> {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            stub.seen("/v1/register").lastOrNull()?.json?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no registration reached the server")
    }

    @Test
    fun anInstallWithoutAModelRegistersWithNone() {
        stub.recommended = served.recommended()
        start(launch = true)
        val body = registered()
        assertFalse("a first launch holds no model, and named one: $body", body.containsKey("model_revision"))
        assertEquals(true, body["new_install"])
    }

    @Test
    fun theModelTheDeviceHoldsIsNamedWhateverItsId() = runBlocking {
        withTimeout(120_000) {
            // a model whose id is not v4.0, downloaded by an earlier start that did not register
            stub.recommended = served.recommended() + ("model_id" to "t7.2")
            start(launch = false).model().close()
            assertTrue("the build is kept under its model id", File(File(dir, "models"), "t7.2").isDirectory)
            assertTrue(stub.seen("/v1/register").isEmpty())
            // the next start: its launch sequence registers, with no Model open
            start(launch = true)
            assertEquals("the version of the build the device holds", "4.0.0", registered()["model_revision"])
        }
    }

    @Test
    fun theOpenModelIsNamedWhenTheAppComesToTheForeground() = runBlocking {
        withTimeout(120_000) {
            stub.recommended = served.recommended() + ("model_id" to "t7.2")
            val c = start(launch = false)
            val model = c.model()
            assertTrue(stub.seen("/v1/register").isEmpty())
            c.foreground()
            assertEquals("the version of the Model that is open", "4.0.0", registered()["model_revision"])
            model.close()
        }
    }
}
