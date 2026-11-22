package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract 28.11 (SDK 4.0.2): every route honours a `Retry-After`, the essential ones included, and the holds outlive
 * the process (items 2, 3 and 6); Android's config is asked at launch, at a return to the foreground and at a
 * session's start, only when older than 300 s (item 5). Through [QuranLabCloud] and [StubQlab] on a clock the test
 * moves; a first host that answers 429 and a standby that is never asked (28.3).
 *
 * 4.0.1 read a Retry-After on the lease route only (as the step of its backoff, 18.4.7), forgot it at a restart, and
 * asked the config every 60 s while the app was in use.
 */
class RouteHoldsTest : OpenChecked() {
    private lateinit var first: StubQlab
    private lateinit var standby: StubQlab
    private val dirs = ArrayList<File>()
    private var now = System.currentTimeMillis()

    @Before
    fun up() {
        first = StubQlab()
        standby = StubQlab()
    }

    @After
    fun down() {
        first.close()
        standby.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-holds-").toFile().also { dirs += it }

    private val hosts = listOf(HostNames.API[0], HostNames.API[1])

    /** A cloud of one install on [storage] (a new process: a new cloud on the same storage). */
    private fun cloud(storage: File): QuranLabCloud {
        val ep = Endpoints.mapped(hosts, listOf(HostNames.MODELS[0]), mapOf(hosts[0] to first.base, hosts[1] to standby.base))
        return QuranLabCloud(CloudOptions(first.key, contributions = true, apiUrl = first.base, trustedKeys = listOf(first.publicKey), endpoints = ep),
            first.environment(storage, clock = { now }))
    }

    private fun reply429(retryAfter: Int) = StubQlab.Reply(429, """{"error":"rate_limited","message":"too many requests, retry later"}""".toByteArray(),
        mapOf("content-type" to "application/json", "retry-after" to retryAfter.toString()))

    /**
     * One route: a 429 with Retry-After: 90 at its first request; [call] made again 30 s later asks nothing (the held
     * answer again), a new process 10 s after the 429 asks nothing for the remaining 80 s, and after 90 s one request goes.
     */
    private fun held(path: String, prepare: (QuranLabCloud) -> Unit = {}, call: (QuranLabCloud) -> Unit) {
        val storage = temp()
        var c = cloud(storage)
        prepare(c)
        first.overrides[path] = { n, _ -> if (n == 1) reply429(90) else null }
        val before = first.seen(path).size
        call(c)
        assertEquals("$path: the 429", before + 1, first.seen(path).size)
        now += 10_000
        // a restart 10 s later: the new process keeps the hold
        c = cloud(storage)
        call(c)
        now += 20_000
        call(c)
        assertEquals("$path: a request inside the 90 s (and after a restart)", before + 1, first.seen(path).size)
        now += 61_000
        call(c)
        assertEquals("$path: one request after the 90 s", before + 2, first.seen(path).size)
        assertEquals("$path: another host asked", 0, standby.seen(path).size)
        first.overrides.remove(path)
    }

    @Test
    fun theConfigHonoursRetryAfter() = held("/v1/config") { c -> c.refreshConfig() }

    @Test
    fun theRegistrationHonoursRetryAfter() = held("/v1/register") { c -> c.register() }

    @Test
    fun theAttestChallengeHonoursRetryAfter() = held("/v1/attest/challenge") { c ->
        try { c.api.challenge(QuranLabKey.parse(first.key)!!) } catch (_: CloudException) {}
    }

    @Test
    fun theAttestHonoursRetryAfter() = held("/v1/attest") { c ->
        try { c.api.attest(QuranLabKey.parse(first.key)!!, c.env.app) } catch (_: CloudException) {}
    }

    @Test
    fun theLeaseRouteHonoursRetryAfter() = held("/v1/model-key") { c ->
        try { c.api.modelKey("qlt_v1.test", mapOf("v" to 1)) } catch (_: CloudException) {}
    }

    @Test
    fun eventsHonourRetryAfter() = held("/v1/events") { c -> try { c.api.events("qlt_v1.test", "{}".toByteArray()) } catch (_: CloudException) {} }

    @Test
    fun issuesHonourRetryAfter() = held("/v1/issues") { c -> try { c.api.issue("qlt_v1.test", mapOf("kind" to "other")) } catch (_: CloudException) {} }

    @Test
    fun aHoldFarAheadOfTheClockIsReadAsEndingNow() {
        val storage = temp()
        val c = cloud(storage)
        first.overrides["/v1/config"] = { _, _ -> reply429(90) }
        c.refreshConfig()
        // the clock moved back 7 hours: the kept end is more than 21600 s ahead
        now -= 7 * 3_600_000L
        first.overrides.remove("/v1/config")
        val again = cloud(storage)
        again.refreshConfig()
        assertEquals(2, first.seen("/v1/config").size)
    }

    /**
     * Item 5 (Android): no config request in 300 s of a session with the app in the foreground (the minute tick asks
     * none), one at the next session start after that.
     */
    @Test
    fun theConfigIsAskedAtASessionStartOnlyWhenOlderThan300s() {
        val storage = temp()
        val c = TajweedClient(first.key, TajweedClientOptions(), ClientPlatform(cloud = first.environment(storage, clock = { now }), apiUrl = first.base,
            trustedKeys = listOf(first.publicKey)), autoLaunch = false)
        try {
            c.cloud.launch(foreground = false)
            val base = first.seen("/v1/config").size
            assertEquals("the launch asked the config", 1, base)
            fun settle() = Thread.sleep(300)
            // a session starts now and runs 300 s in the foreground, the minute tick going
            c.foreground()
            c.sessionStarted()
            settle()
            for (minute in 1..4) {
                now += 60_000
                c.tick()
                settle()
            }
            now += 59_000
            c.tick()
            settle()
            assertEquals("a config request inside 300 s", base, first.seen("/v1/config").size)
            // the next session start after the 300 s
            now += 2_000
            c.sessionStarted()
            val until = System.currentTimeMillis() + 10_000
            while (first.seen("/v1/config").size == base && System.currentTimeMillis() < until) Thread.sleep(50)
            assertEquals("the session start after 300 s", base + 1, first.seen("/v1/config").size)
            // and a new process within 300 s of it asks none at its launch
            now += 10_000
            c.close()
            val again = TajweedClient(first.key, TajweedClientOptions(), ClientPlatform(cloud = first.environment(storage, clock = { now }), apiUrl = first.base,
                trustedKeys = listOf(first.publicKey)), autoLaunch = false)
            try {
                again.cloud.launch(foreground = false)
                assertEquals("a launch within 300 s of the last config", base + 1, first.seen("/v1/config").size)
            } finally {
                again.close()
            }
        } finally {
            c.close()
        }
        assertTrue(true)
    }

    /**
     * The stress (SDK 4.0.2), its config part: 1,000 times, close() races a config refresh in flight (a
     * session's start asks it; its answer is held and released 0 to 4 ms after close() began). close() returns, no
     * thread ends uncaught, and every 50th time a new process on the same storage launches and closes cleanly.
     */
    @Test
    fun closeRacesAConfigRefreshInFlight1000Times() {
        val random = java.util.Random(13)
        val uncaught = java.util.concurrent.atomic.AtomicInteger()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught.incrementAndGet(); System.err.println("uncaught in ${t.name}: $e") }
        val problems = ArrayList<String>()
        // an iteration whose client asked no config in 10 s raced nothing (the host's sockets, not the close): counted apart
        var noRequest = 0
        fun client(storage: File) = TajweedClient(first.key, TajweedClientOptions(), ClientPlatform(cloud = first.environment(storage, clock = { now }),
            apiUrl = first.base, trustedKeys = listOf(first.publicKey)), autoLaunch = false)
        try {
            for (i in 0 until 1000) {
                val storage = temp()
                val hold = StubQlab.Hold { _, p -> p == "/v1/config" }
                first.hold = hold
                val c = client(storage)
                c.sessionStarted()
                if (!hold.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) { noRequest++; first.hold = null; c.close(); continue }
                val closing = Thread({ try { c.close() } catch (t: Throwable) { problems += "$i/close: $t" } }, "close-$i").apply { start() }
                Thread.sleep(random.nextInt(5).toLong())
                hold.release.countDown()
                closing.join(10_000)
                if (closing.isAlive) problems += "$i: close() still running"
                first.hold = null
                if (i % 50 == 0) {
                    val again = client(storage)
                    try { again.cloud.launch(foreground = false) } catch (t: Throwable) { problems += "$i/launch: $t" } finally { again.close() }
                }
                storage.deleteRecursively()
                dirs -= storage
            }
        } finally {
            first.hold = null
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        println("close raced a config refresh 1000 times: problems ${problems.size}, uncaught ${uncaught.get()}, no request $noRequest")
        assertEquals(emptyList<String>(), problems.take(20))
        assertTrue("iterations that raced nothing: $noRequest", noRequest < 10)
        assertEquals("uncaught", 0, uncaught.get())
    }
}
