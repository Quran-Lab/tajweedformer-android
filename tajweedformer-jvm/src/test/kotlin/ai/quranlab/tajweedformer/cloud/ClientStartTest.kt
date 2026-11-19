package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage B, the Flutter port's gaps in the Android API (11-18), on the JVM against a stand-in for Quran Lab's server:
 * - a process that WorkManager started holds the Client before the app makes one: the app's `TajweedClient(...)` is
 *   `client_exists`, and `TajweedClient.current` is that Client (one Client per process, never a second);
 * - a Client made while the app is already in the foreground (Android: after the first activity started, as a Flutter
 *   plugin makes it) counts the opening and registers the install for the day, as a Client made before it does at
 *   the first activity's start; a Client made in the background (a worker) counts nothing;
 * - `sharing.ask()` while sharing is not offered to the app is not an error: it shows nothing and returns false
 *   (SHARE_ASK_40 20.5, decision `not_offered`); `setOn(true)` then is still `sharing_unavailable`.
 */
class ClientStartTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private val open = ArrayList<TajweedClient>()

    @Before
    fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-start-").toFile()
    }

    @After
    fun down() {
        open.forEach { try { it.close() } catch (_: Exception) {} }
        stub.close()
        dir.deleteRecursively()
    }

    /** A Client with Android's shape: a lifecycle of its own (so the launch counts an opening only when told). */
    private fun client(inForeground: Boolean, autoLaunch: Boolean = true, storage: File = File(dir, "s")): TajweedClient =
        TajweedClient(stub.key, TajweedClientOptions(),
            ClientPlatform(modelRetry = ai.quranlab.tajweedformer.RetryPlan.NONE, cloud = stub.environment(storage), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey), lifecycle = { { } }, inForegroundAtStart = inForeground),
            autoLaunch = autoLaunch).also { open += it }

    /** Waits for the client's background launch: the config asked for. */
    private fun launched() {
        val until = System.currentTimeMillis() + 10_000
        while (stub.seen("/v1/config").isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("the launch asked for the config", stub.seen("/v1/config").isNotEmpty())
        Thread.sleep(200)
    }

    /** The launches counted so far: in the open report and in the sealed ones waiting to be sent (the launch's send
     *  point seals what it counted; the stand-in's scheduler sends nothing by itself). */
    private fun launches(c: TajweedClient): Int {
        val open = (c.cloud.telemetry.peek(TelemetryContext(c.cloud.env.app, c.cloud.env.device))?.get("launches") as? Number)?.toInt() ?: 0
        val sealed = c.cloud.telemetry.pendingReports().sumOf { f ->
            Regex(""""launches":(\d+)""").findAll(f.readText()).sumOf { it.groupValues[1].toInt() }
        }
        return open + sealed
    }

    @Test
    fun aWorkerFirstProcessHandsTheAppItsClient() {
        assertNull(TajweedClient.current)
        // the worker's restore (AndroidPlatform.restore): the Client from the options saved last time, in the background
        val worker = client(inForeground = false)
        assertSame(worker, TajweedClient.current)
        // the app then makes its own, as a plugin does after the first activity: one Client per process
        try {
            client(inForeground = true, storage = File(dir, "app"))
            fail("a second Client was made")
        } catch (e: TajweedError) {
            assertEquals("client_exists", e.code)
        }
        // the public way to it: the same Client, usable
        val c = TajweedClient.current ?: fail("no current Client") as Nothing
        assertSame(worker, c)
        assertEquals("the failed second one left no trace", worker, TajweedClient.current)
        c.foreground()
        // closed: none, and the app can make its own
        c.close()
        assertNull(TajweedClient.current)
        val mine = client(inForeground = true, storage = File(dir, "app"))
        assertSame(mine, TajweedClient.current)
    }

    @Test
    fun aClientMadeInTheForegroundCountsTheOpening() {
        client(inForeground = true)
        launched()
        val until = System.currentTimeMillis() + 10_000
        while (stub.seen("/v1/register").isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("the day's registration", 1, stub.seen("/v1/register").size)
        assertEquals("a launch counted", 1, launches(open.single()))
    }

    @Test
    fun aClientMadeInTheBackgroundCountsNothing() {
        client(inForeground = false)
        launched()
        assertEquals("no registration from a worker's Client", 0, stub.seen("/v1/register").size)
        assertEquals(0, launches(open.single()))
    }

    @Test
    fun theDiagnosisHasTheProtectedObjectBeforeAnyLease() {
        val c = client(inForeground = false, autoLaunch = false)
        val p = kotlinx.coroutines.runBlocking { c.diagnose() }.toMap()["protected"] as Map<*, *>
        // contract sdk_4.0.0/DIAGNOSIS_40.md: every name there, no key yet, no lease
        assertEquals(listOf("keyStore", "lease", "leaseExpires", "leaseClass", "refreshAfter", "graceUntil", "core"), p.keys.toList())
        assertNull(p["keyStore"])
        assertEquals("none", p["lease"])
        for (k in listOf("leaseExpires", "leaseClass", "refreshAfter", "graceUntil")) assertNull(k, p[k])
        // the eleven fields of 3.0 and downloadProtocol come first, as before
        assertEquals("protected", c.let { kotlinx.coroutines.runBlocking { it.diagnose() } }.toMap().keys.last())
    }

    @Test
    fun askingWhileSharingIsNotOfferedShowsNothingAndIsNoError() {
        stub.flags = stub.flags + ("contributions" to false)
        val c = client(inForeground = true, autoLaunch = false)
        c.cloud.launch(foreground = false)
        assertFalse(c.sharing.isAvailable)
        assertFalse("not offered: nothing shown, no error", c.sharing.ask())
        assertFalse(c.sharing.isOn)
        try {
            c.sharing.setOn(true)
            fail("sharing turned on while it is not offered")
        } catch (e: TajweedError) {
            assertEquals("sharing_unavailable", e.code)
        }
    }
}
