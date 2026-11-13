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
 * A take without audio is not a session (SPEC 6.1, contract 1.13.0): a take that ends, cancelled or finished, before
 * one sample of audio was pushed counts nothing in the telemetry (no `sessions`, no `aborted`, no `minutes`, no
 * `chunks`) and is not a finished session for the sharing ask. An app that opens a session only to have the engine
 * ready, and cancels it at once, did add an aborted session to its statistics at every launch.
 *
 * Through the public Client and Model with the fake engine, the stub API and a served build: the counts are read from
 * the day's report as the server would get it.
 */
class TakeWithoutAudioTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var served: ServedModel
    private lateinit var dir: File
    private var client: TajweedClient? = null

    @Before
    fun up() {
        stub = StubQlab()
        stub.inference = null
        served = ServedModel.of()
        dir = Files.createTempDirectory("tj-noaudio-").toFile()
    }

    @After
    fun down() {
        client?.close()
        served.close()
        stub.close()
        dir.deleteRecursively()
    }

    /** The cohorts of every report sealed so far (each as the payload has it), and the reports themselves. */
    @Suppress("UNCHECKED_CAST")
    private fun sealed(c: TajweedClient): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        c.cloud.background()
        val reports = c.cloud.telemetry.pendingReports().map { stub.parse(it.readBytes()) }
        return reports.flatMap { (it["cohorts"] as? List<Map<String, Any?>>) ?: emptyList() } to reports
    }

    @Test
    fun aTakeThatEndsBeforeAnyAudioCountsNothingAndIsNotAFinishedSession() = runBlocking {
        withTimeout(120_000) {
            stub.recommended = served.recommended()
            val c = TajweedClient(stub.key, TajweedClientOptions(),
                gatePlatform(stub, dir, served, MachineInstallKey(machineId = { "this-machine" }, user = { "learner" }), stubPlay(stub)), autoLaunch = false)
            client = c
            val model = c.model()
            // a sheet the learner would be shown: every ask lands here
            val asked = ArrayList<Any>()
            c.cloud.sharing.prompts = { asked.add(it) }

            // an app warms the engine: a take opened and cancelled at once; and one finished with no audio at all
            model.session().cancel()
            val empty = model.session()
            runCatching { empty.finish() }
            runCatching { empty.cancel() }

            val (cohorts, reports) = sealed(c)
            assertTrue("a take without audio was counted as a session: $cohorts", cohorts.all { (it["sessions"] as? Double ?: 0.0) == 0.0 })
            assertTrue("a take without audio was counted as aborted: $cohorts", cohorts.none { it.containsKey("aborted") })
            assertTrue("a take without audio counted minutes or chunks: $cohorts",
                cohorts.all { (it["minutes"] as? Double ?: 0.0) == 0.0 && (it["chunks"] as? Double ?: 0.0) == 0.0 && !it.containsKey("takes") })
            assertEquals("a take without audio led to the sharing ask", 0, asked.size)
            assertTrue("an ask was counted: $reports", reports.none { it.containsKey("share_asked") })

            // a take with audio is counted as before, and is a finished session for the ask
            val take = model.session()
            take.push(FloatArray(5120 * 2))
            take.finish()
            val (after, _) = sealed(c)
            assertEquals("the take with audio is one session", 1.0, after.sumOf { it["sessions"] as? Double ?: 0.0 }, 0.0)
            assertFalse("a finished take was counted as aborted: $after", after.any { it.containsKey("aborted") })
            assertEquals("the finished take with audio is asked about once", 1, asked.size)
            model.close()
        }
    }
}
