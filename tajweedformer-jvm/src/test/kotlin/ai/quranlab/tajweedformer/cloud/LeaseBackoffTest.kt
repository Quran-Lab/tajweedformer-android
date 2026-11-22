package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.LeaseClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Contract 18.4.7.3 (SDK 4.0.2): the lease's retries after failures wait 60 s, 300 s, then uniform in [600, 1800],
 * [3600, 7200] and [10800, 21600] s, and a `Retry-After` is a floor that never shortens or resets the wait. Through the
 * public Client with the REAL Core ([TwoContextKit]): a lease whose `refresh_after` has passed (the Core's own clock)
 * and a lease route that answers `503 lease_unavailable` with a `Retry-After`; the row is the one the SDK stores
 * (`<storage>/core/lease.json`), the SDK's clock moved by the stand-in's skew. 4.0.1 let the `Retry-After` replace the
 * step: `503 lease_unavailable` with `Retry-After: 30` meant a retry every 30 to 60 s.
 */
@Suppress("UNCHECKED_CAST")
class LeaseBackoffTest : ai.quranlab.tajweedformer.OpenChecked() {
    private lateinit var kit: TwoContextKit
    private var client: TajweedClient? = null

    @Before
    fun up() {
        assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)
        kit = TwoContextKit()
        kit.refreshAfterS = 0
    }

    @After
    fun down() {
        try { client?.close() } catch (_: Exception) {}
        if (::kit.isInitialized) kit.close()
    }

    private fun record(): Map<String, Any?> = Json.parse(File(File(kit.storage, "core"), "lease.json").readText()) as Map<String, Any?>
    private fun failures() = (record()["failures"] as? Double)?.toInt() ?: 0
    private fun sdkNow() = kit.stub.clock()
    private fun leaseAsked() = kit.stub.seen("/v1/model-key").size

    /** One refresh point of the SDK (Android's minute tick); returns the wait the row then holds, in seconds. */
    private fun refreshFails(c: TajweedClient, expect: Int): Long {
        c.tick()
        val end = System.currentTimeMillis() + 30_000
        while (failures() < expect && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals("failures in the row", expect, failures())
        return ((record()["next_try_ms"] as Double).toLong() - sdkNow() + 500) / 1000
    }

    @Test
    fun aRunOf503WithRetryAfter30Waits600To1800sNever30() = runBlocking {
        withTimeout(120_000) {
            var c = kit.client().also { client = it }
            c.model().close()
            val ok = leaseAsked()
            kit.leaseReply = { _ -> StubQlab.Reply(503, """{"error":"lease_unavailable","message":"later"}""".toByteArray(),
                mapOf("content-type" to "application/json", "retry-after" to "30")) }
            val waits = ArrayList<Long>()
            for (n in 1..3) {
                val w = refreshFails(c, n)
                assertTrue("wait $n: $w s", w in 598..1800)
                waits += w
                // nothing goes before the wait: a tick 30 s on asks nothing
                kit.stub.skewMs += 30_000
                c.tick()
                Thread.sleep(500)
                assertEquals("asked before the wait", ok + n, leaseAsked())
                if (n == 2) {
                    // a restart while the retry waits: the new process waits the kept time too (18.4.7.3 item 5)
                    c.close()
                    c = kit.client().also { client = it }
                    c.tick()
                    Thread.sleep(500)
                    assertEquals("asked after a restart before the kept time", ok + n, leaseAsked())
                }
                kit.stub.skewMs += w * 1000
            }
            assertTrue("never 30 s: $waits", waits.none { it < 598 })
            // a valid lease opens with no request while a retry waits
            val before = leaseAsked()
            c.model().close()
            assertEquals("a request for a valid lease", before, leaseAsked())
            // a call with no lease for its build asks at once (no hold: the 503 named 30 s, long passed) and fails as before
            kit.offer(kit.newerBuild())
            c.cloud.refreshConfig()
            val e = try { c.model().close(); null } catch (x: TajweedError) { x }
            assertNotNull(e)
            assertEquals("asked at once", before + 1, leaseAsked())
            assertEquals("download_failed", e!!.code)
            assertEquals("lease", e.errorCause?.stage)
            assertEquals(503, e.errorCause?.httpStatus)
        }
    }

    @Test
    fun aRetryAfter3000AfterTheFirstFailureWaits3000() = runBlocking {
        withTimeout(120_000) {
            val c = kit.client().also { client = it }
            c.model().close()
            kit.leaseReply = { _ -> StubQlab.Reply(503, """{"error":"lease_unavailable","message":"later"}""".toByteArray(),
                mapOf("content-type" to "application/json", "retry-after" to "3000")) }
            val w = refreshFails(c, 1)
            assertTrue("wait $w s", w in 2998..3000)
            assertEquals(3_000_000L, LeaseClient.backoffMs(1, 3000) { 0.5 })
        }
    }
}
