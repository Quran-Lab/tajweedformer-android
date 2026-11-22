package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.ProtectedKit
import ai.quranlab.tajweedformer.ProtectedKit.b64u
import ai.quranlab.tajweedformer.ProtectedKit.hex
import ai.quranlab.tajweedformer.ProtectedKit.sha256
import ai.quranlab.tajweedformer.ProtectedKit.unb64u
import ai.quranlab.tajweedformer.RetryPlan
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TajweedEngineFactory
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSessionOptions
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.OpenWait
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.CoreRegistry
import ai.quranlab.tajweedformer.core.CoreTajweedEngine
import ai.quranlab.tajweedformer.core.DispatchLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SDK 4.0.2 made one Core per storage folder and install key in the process ([CoreRegistry]); the Core opens one model
 * at a time (QLC_E_BUSY for a second open: `engine_failed`, error_code `busy`). These run the public Client with the
 * REAL Core (the host build of the test release) and the REAL engine ([TajweedEngineFactory.CORE]) on the protected
 * test build of the Core's `tools/make_test_build.py` (TJ_CORE_TEST_BUILD, as CoreFlowTest), so every open below is a
 * `qlc_model_open` of the Core and its one-model rule is the Core's own. Skipped without the host Core or the build.
 *
 * The open slot of the shared Core: client A opens a model; A is closed while the app keeps A's Model; client B of the
 * same storage calls model(). The same with a take of A's Model running when A is closed, with A's model() waiting on
 * a lease when A is closed (B then shares A's Core), and six model() calls at once on one Client: these passed on SDK
 * 4.0.2 as released. A close() that lands while A's model() is opening did not: A's model() handed out an open Model
 * after its Client closed, and B, sharing A's Core (a lease refresh in flight held it), failed `engine_failed` `busy`
 * (aClientClosedWhileItsModelOpensLeavesNoModelInTheSharedCore). SDK 4.0.3 fixes that.
 */
class SharedCoreOpenSlotTest : ai.quranlab.tajweedformer.OpenChecked() {
    private var kit: RealBuildKit? = null
    private val clients = ArrayList<TajweedClient>()
    private val models = ArrayList<TajweedModel>()

    @Before
    fun need() {
        assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)
        assumeTrue("TJ_CORE_TEST_BUILD names no test build of the Core's tools/make_test_build.py", RealBuildKit.source() != null)
        kit = RealBuildKit(RealBuildKit.source()!!)
    }

    @After
    fun down() {
        models.forEach { try { it.close() } catch (_: Exception) {} }
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        kit?.close()
    }

    private fun client(engine: TajweedEngineFactory = TajweedEngineFactory.CORE): TajweedClient = kit!!.client(engine).also { clients += it }

    /** A whole take on [m]: two seconds pushed, then finish(); its log_probs have frames. */
    private suspend fun take(m: TajweedModel) {
        val s = m.session()
        s.push(FloatArray(32_000))
        val h = s.finish()
        assertTrue("the take has frames", (h["log_probs"]?.frames ?: 0) > 0)
    }

    /** The control: with a model open in the Core, a second open of the same Core is refused (`engine_failed`, error_code `busy`). */
    @Test
    fun aSecondOpenOnTheSameCoreIsBusy() = runBlocking<Unit> {
        withTimeout(600_000) {
            val a = client()
            val m = a.model().also { models += it }
            val b = m.folder.protected!!
            val e = try {
                b.open(m.defaultContext, 1, 16_000, 0).close()
                null
            } catch (x: TajweedError) {
                x
            }
            assertEquals("engine_failed", e?.code)
            assertEquals("busy", e?.errorCause?.errorCode)
            assertEquals("model_open", e?.errorCause?.stage)
        }
    }

    /**
     * A opens a model, A is closed while the test keeps A's Model, B of the same storage calls model(). B opens, its
     * take runs and its reference data answers.
     */
    @Test
    fun aNewClientOpensAfterTheClosedClientsModel() = runBlocking<Unit> {
        withTimeout(600_000) {
            val a = client()
            val kept = a.model().also { models += it }
            val coreA = a.leases!!.core()!!
            take(kept)
            a.close()
            // what the app keeps of A: a closed Model (its engine and the Core's model closed with the Client)
            val after = try { kept.session(); null } catch (x: TajweedError) { x }
            assertEquals("the kept Model after its Client closed", "session_closed", after?.code)
            assertFalse("A's Core went with its last holder", CoreRegistry.PROCESS.live(coreA))

            val b = client()
            val mb = try {
                b.model().also { models += it }
            } catch (x: TajweedError) {
                fail("B's model() after A closed: ${x.code} ${x.errorCause}")
                throw x
            }
            assertNotSame("B has a Core of its own making", coreA, b.leases!!.core())
            take(mb)
            mb.reference?.let { r -> assertTrue("1:1 has units", r.units(1, 1).isNotEmpty()) }
        }
    }

    /**
     * A take of A's Model is running (a push in flight) when A is closed: close() ends the take and closes the Core's
     * model before it returns, so B (which can only be made after that) opens, and its take runs; nothing of A's take
     * is left (the process's one engine stream is free).
     */
    @Test
    fun aTakeRunningWhenItsClientClosesEndsAndTheNewClientOpens() = runBlocking<Unit> {
        withTimeout(600_000) {
            val a = client()
            val kept = a.model().also { models += it }
            val s = kept.session()
            s.push(FloatArray(16_000))
            val pushing = async(Dispatchers.IO) {
                try {
                    while (isActive) s.push(FloatArray(1_600))
                    null
                } catch (x: TajweedError) {
                    x
                }
            }
            delay(100)
            a.close()
            val ended = withTimeout(10_000) { pushing.await() }
            assertTrue("A's take ended with an error of the SDK: ${ended?.code}", ended != null)
            assertFalse("the engine stream of A's take is free", CoreTajweedEngine.streamLive)

            val b = client()
            val mb = try {
                b.model().also { models += it }
            } catch (x: TajweedError) {
                fail("B's model() after A closed during a take: ${x.code} ${x.errorCause}")
                throw x
            }
            take(mb)
        }
    }

    /**
     * A's model() waits on a lease answer when A is closed: close() lets go after its wait, the attempt in flight keeps
     * A's Core (an attempt holds it, [CoreRegistry]), and B, made next, takes that same Core. Then the answer lands.
     * A's model() opens nothing in the Core B now holds and hands no open Model to the app; B's model() opens.
     */
    @Test
    fun aModelCallWaitingOnItsLeaseWhenItsClientClosesOpensNothingInTheSharedCore() = runBlocking<Unit> {
        withTimeout(600_000) {
            val k = kit!!
            // a later launch: the build on the device and installed, the lease record gone (the next model() asks)
            client().let { p -> p.prefetch(); p.close() }
            assertTrue(File(File(k.storage, "core"), "lease.json").delete())
            val hold = AtomicBoolean(true)
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            k.beforeLease = { _ ->
                if (hold.compareAndSet(true, false)) {
                    arrived.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
            }
            val a = client()
            val call = async(Dispatchers.IO) { try { a.model() } catch (x: TajweedError) { x } }
            assertTrue("A's lease request reached the server", withContext(Dispatchers.IO) { arrived.await(60, TimeUnit.SECONDS) })
            val coreA = a.leases!!.core()!!
            withContext(Dispatchers.IO) { a.close() }
            assertTrue("A's attempt in flight keeps A's Core", CoreRegistry.PROCESS.live(coreA))
            val b = client()
            b.diagnose()
            assertSame("B takes the folder's live Core", coreA, b.leases!!.core())
            release.countDown()
            val late = withTimeout(60_000) { call.await() }
            (late as? TajweedModel)?.let { models += it }
            println("A's model() after A closed: ${(late as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: late}")
            val mb = try {
                b.model().also { models += it }
            } catch (x: TajweedError) {
                fail("B's model() in the shared Core: ${x.code} ${x.errorCause}")
                throw x
            }
            take(mb)
            assertTrue("A's model() after A closed hands out no open Model: $late", late is TajweedError)
        }
    }

    /**
     * Six model() calls at once on ONE Client, then takes that make the Core open the model again (a take at another rate closes the engine and opens it for that rate, Recognizer.cpuFor) while more model()
     * calls and reference lookups run beside them. Every model() gets the one Model, nothing is busy, every take runs.
     */
    @Test
    fun sixModelCallsAtOnceAndOverlappingOpensOnOneClientAreNeverBusy() = runBlocking<Unit> {
        withTimeout(600_000) {
            val c = client()
            val got = (1..6).map { async(Dispatchers.IO) { try { c.model() } catch (x: TajweedError) { x } } }.awaitAll()
            got.filterIsInstance<TajweedModel>().toSet().forEach { models += it }
            val failed = got.filterIsInstance<TajweedError>().map { "${it.code} ${it.errorCause}" }
            assertEquals("model() calls that failed: $failed", emptyList<String>(), failed)
            assertEquals("one Model for every call", 1, got.toSet().size)
            val m = got.first() as TajweedModel
            val beside = java.util.Collections.synchronizedList(ArrayList<String>())
            val looked = java.util.concurrent.atomic.AtomicInteger()
            val side = launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        if (c.model() !== m) beside += "model() gave another Model"
                        m.reference?.units(1, 1)
                        looked.incrementAndGet()
                    } catch (x: TajweedError) {
                        beside += "${x.code} ${x.errorCause}"
                    }
                    // beside the takes, not a flood of the engine's thread (a lookup runs there)
                    delay(10)
                }
            }
            for (rate in listOf(48_000, 16_000, 44_100, 16_000)) {
                val t0 = System.nanoTime()
                val failedBefore = beside.size
                val s = m.session(TajweedSessionOptions(sampleRate = rate))
                val t1 = System.nanoTime()
                s.push(FloatArray(rate * 2))
                val h = s.finish()
                println("take at $rate Hz: session() ${(t1 - t0) / 1_000_000} ms, the take ${(System.nanoTime() - t1) / 1_000_000} ms, " +
                    "lookups failed meanwhile ${beside.size - failedBefore}, answered so far ${looked.get()}")
                assertTrue("a take at $rate Hz has frames", (h["log_probs"]?.frames ?: 0) > 0)
            }
            side.cancelAndJoin()
            println("beside the takes: ${beside.groupingBy { it }.eachCount()}, answered ${looked.get()}")
            assertTrue("busy beside the takes: $beside", beside.none { it.contains("busy") || it.startsWith("engine_failed") })
        }
    }

    /**
     * The Core's open has returned in A's model() (the engine is open in the Core) when A is closed, and a lease refresh
     * that is due is in flight then (its answer held), so the refresh's attempt keeps A's Core past close(). close()
     * finds no Model to close. Then A's model() goes on. A's model() must hand out no open Model after its Client
     * closed, and B, made next on the same storage (which takes that same Core), opens. On SDK 4.0.2 as released A's
     * model() returned an open Model and B's model() failed `engine_failed` with error_code `busy`.
     *
     * [HeldOpen] holds A's model() right after the Core's real open returned (the time a phone's native open takes).
     */
    @Test
    fun aClientClosedWhileItsModelOpensLeavesNoModelInTheSharedCore() = runBlocking<Unit> {
        withTimeout(600_000) {
            val k = kit!!
            k.refreshAfterS = 0
            val held = HeldOpen()
            val a = client(held)
            val call = async(Dispatchers.IO) { try { a.model() } catch (x: TajweedError) { x } }
            var late: TajweedModel? = null
            val stubHold = AtomicBoolean(false)
            val leaseArrived = CountDownLatch(1)
            val leaseGo = CountDownLatch(1)
            k.beforeLease = { _ ->
                if (stubHold.compareAndSet(true, false)) {
                    leaseArrived.countDown()
                    leaseGo.await(60, TimeUnit.SECONDS)
                }
            }
            try {
                assertTrue("A's open reached the Core", withContext(Dispatchers.IO) { held.opened.await(120, TimeUnit.SECONDS) })
                val coreA = a.leases!!.core()!!
                // the refresh that is due (refresh_after has passed): its request is held at the server
                stubHold.set(true)
                a.foreground()
                assertTrue("the refresh's request reached the server", withContext(Dispatchers.IO) { leaseArrived.await(60, TimeUnit.SECONDS) })
                withContext(Dispatchers.IO) { a.close() }
                assertTrue("the refresh in flight keeps A's Core", CoreRegistry.PROCESS.live(coreA))
                held.go.countDown()
                val got = withTimeout(60_000) { call.await() }
                late = got as? TajweedModel
                println("A's model() after A closed: ${(got as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "an open Model"}")

                val b = client()
                assertSame("B takes the folder's live Core", coreA, b.leases!!.core())
                val mb = try {
                    b.model().also { models += it }
                } catch (x: TajweedError) {
                    x
                }
                println("B's model(): ${(mb as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "opened"}")
                // A's late Model is closed here, while B still holds the Core it is open in
                val lateWasOpen = late != null
                late?.close()
                late = null
                assertTrue("B's model() in the shared Core: ${(mb as? TajweedError)?.let { "${it.code} ${it.errorCause}" }}", mb is TajweedModel)
                take(mb as TajweedModel)
                assertFalse("A's model() handed out an open Model after its Client closed", lateWasOpen)
            } finally {
                held.go.countDown()
                leaseGo.countDown()
                try { late?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * The same with nothing else holding A's Core: close() lands while A's open is held (the Core's open has returned),
     * so close() lets go of A's last hold. SDK 4.0.3: the open holds the Core until its model is closed, so the Core
     * outlives the late model (on 4.0.2 it was destroyed under it: qlc_destroy does not close the Core's model, and the
     * late Model was then used on freed memory; this case was not run on 4.0.2 for that reason). B, made next, takes
     * that Core, and its open, which meets A's model there, waits for it; A's model() closes its late model and fails,
     * and B's model() then opens.
     */
    @Test
    fun aClientClosedWhileItsModelOpensKeepsItsCoreUntilTheLateModelClosesAndTheNextClientWaits() = runBlocking<Unit> {
        withTimeout(600_000) {
            val held = HeldOpen()
            val a = client(held)
            val call = async(Dispatchers.IO) { try { a.model() } catch (x: TajweedError) { x } }
            try {
                assertTrue("A's open reached the Core", withContext(Dispatchers.IO) { held.opened.await(120, TimeUnit.SECONDS) })
                val coreA = a.leases!!.core()!!
                withContext(Dispatchers.IO) { a.close() }
                assertTrue("A's open in flight keeps A's Core after close()", CoreRegistry.PROCESS.live(coreA))
                val b = client()
                assertSame("B takes the folder's live Core", coreA, b.leases!!.core())
                val t0 = System.nanoTime()
                val bCall = async(Dispatchers.IO) { try { b.model() } catch (x: TajweedError) { x } }
                // B's suspend call waits with delay: while it waits, no thread is blocked in the registry's wait
                var sampled = 0
                while (System.nanoTime() - t0 < 1_000_000_000L) {
                    delay(100)
                    if (bCall.isCompleted) break
                    sampled++
                    assertFalse("a thread is blocked in the wait for A's model", blockedInTheWait())
                }
                assertFalse("B's open waits for A's model to close: ${bCall.takeIf { it.isCompleted }?.await()}", bCall.isCompleted)
                assertTrue("the wait was looked at while it ran", sampled >= 5)
                held.go.countDown()
                val late = withTimeout(60_000) { call.await() }
                (late as? TajweedModel)?.let { models += it }
                val mb = withTimeout(60_000) { bCall.await() }
                val waitedMs = (System.nanoTime() - t0) / 1_000_000
                (mb as? TajweedModel)?.let { models += it }
                println("A's model() after A closed: ${(late as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "an open Model"}; " +
                    "B's model(): ${(mb as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "opened"} after $waitedMs ms")
                assertTrue("A's model() after A closed hands out no open Model: $late", late is TajweedError)
                assertTrue("B's model() after it waited: ${(mb as? TajweedError)?.let { "${it.code} ${it.errorCause}" }}", mb is TajweedModel)
                take(mb as TajweedModel)
                assertSame("B's model opened in the same Core", coreA, b.leases!!.core())
            } finally {
                held.go.countDown()
            }
        }
    }

    /** Whether a thread is blocked in the registry's wait for another client's model (a sync caller's wait). */
    private fun blockedInTheWait(): Boolean = Thread.getAllStackTraces().values.any { st ->
        st.any { it.className.endsWith("CoreRegistry") && it.methodName == "awaitOthers" }
    }

    /** A closed Client's late model open in the Core (A's open held right after the Core's open returned, A closed),
     *  and B made next on the same storage, holding that Core too. [Late.held] lets A's open go on. */
    private class Late(val held: HeldOpen, val call: kotlinx.coroutines.Deferred<Any>, val core: Core, val b: TajweedClient)

    private suspend fun kotlinx.coroutines.CoroutineScope.lateModel(): Late {
        val held = HeldOpen()
        val a = client(held)
        val call = async(Dispatchers.IO) { try { a.model() } catch (x: TajweedError) { x } }
        assertTrue("A's open reached the Core", withContext(Dispatchers.IO) { held.opened.await(120, TimeUnit.SECONDS) })
        val core = a.leases!!.core()!!
        withContext(Dispatchers.IO) { a.close() }
        val b = client()
        assertSame("B takes the folder's live Core", core, b.leases!!.core())
        return Late(held, call, core, b)
    }

    /** A's late model closes (its open goes on, finds its Client closed); A's model() fails. */
    private suspend fun Late.end() {
        held.go.countDown()
        val got = withTimeout(60_000) { call.await() }
        (got as? TajweedModel)?.let { models += it }
        assertTrue("A's model() after A closed hands out no open Model: $got", got is TajweedError)
    }

    /** The build B opens in a sync call (the SDK's own blocking open, [ProtectedBuild.open]). */
    private fun buildOf(b: TajweedClient): ProtectedBuild {
        val m = File(kit!!.storage, "models").walkTopDown().first { it.name == ModelManifest.FILE_NAME && File(it.parentFile, "device").isDirectory }
        val bytes = m.readBytes()
        return ProtectedBuild(m.parentFile, ModelManifest.parse(bytes), bytes, b.leases!!)
    }

    /** [body] on a thread of its own named [name]: what it returned or threw, and how long it took (ms). */
    private fun onThread(name: String, body: () -> Any?): Pair<Any?, Long> {
        val out = java.util.concurrent.atomic.AtomicReference<Any?>()
        val t0 = System.nanoTime()
        val t = Thread({ out.set(try { body() } catch (x: Throwable) { x }) }, name)
        t.start()
        t.join(60_000)
        return out.get() to (System.nanoTime() - t0) / 1_000_000
    }

    /**
     * A sync open on the main looper (the seam [OpenWait.isUiThread]; Android: Looper.getMainLooper()) that meets a
     * closed Client's model in the Core never waits (Android's ANR limit): `session_open` at once.
     */
    @Test
    fun aSyncOpenOnTheMainLooperIsSessionOpenAtOnce() = runBlocking<Unit> {
        withTimeout(600_000) {
            val late = lateModel()
            val before = OpenWait.isUiThread
            try {
                val pb = buildOf(late.b)
                OpenWait.isUiThread = { Thread.currentThread().name == "test-main-looper" }
                val (got, ms) = withContext(Dispatchers.IO) { onThread("test-main-looper") { pb.open(pb.manifest.defaultContext, 1, 16_000, 0) } }
                (got as? ai.quranlab.tajweedformer.core.CoreModel)?.let { pb.closed(it) }
                println("a sync open on the main looper: ${(got as? TajweedError)?.code ?: got} after $ms ms")
                assertEquals("session_open", (got as? TajweedError)?.code)
                assertTrue("no wait on the main looper: $ms ms", ms < 1_000)
            } finally {
                OpenWait.isUiThread = before
                late.end()
            }
        }
    }

    /** A sync open off the main looper waits (blocking, at most 3 s) for the closed Client's model to close, then opens. */
    @Test
    fun aSyncOpenOffTheMainLooperWaitsAndOpens() = runBlocking<Unit> {
        withTimeout(600_000) {
            val late = lateModel()
            try {
                val pb = buildOf(late.b)
                val release = launch(Dispatchers.IO) {
                    delay(1_000)
                    // the sync open reaches the wait (seconds on a busy machine), and is let go from there
                    val until = System.nanoTime() + 60_000_000_000L
                    while (!blockedInTheWait() && System.nanoTime() < until) delay(50)
                    assertTrue("the sync open waits in the registry", blockedInTheWait())
                    late.end()
                }
                val (got, ms) = withContext(Dispatchers.IO) { onThread("test-background") { pb.open(pb.manifest.defaultContext, 1, 16_000, 0) } }
                (got as? ai.quranlab.tajweedformer.core.CoreModel)?.let { pb.closed(it) }
                release.join()
                println("a sync open off the main looper: ${(got as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "opened"} after $ms ms")
                assertTrue("opened after the wait: $got", got is ai.quranlab.tajweedformer.core.CoreModel)
                assertTrue("it waited for A's model: $ms ms", ms >= 900)
            } finally {
                late.held.go.countDown()
            }
        }
    }

    /**
     * The wait runs out: A's late model stays open past the 3 s bound, so B's model() (a suspend call: its wait is a
     * delay) fails `session_open` after about 3 s (as on the other SDKs), and nothing is left broken: once A's model is
     * closed, B's next model() opens and runs a take.
     */
    @Test
    fun theWaitRunsOutAfterThreeSeconds() = runBlocking<Unit> {
        withTimeout(600_000) {
            val late = lateModel()
            try {
                val t0 = System.nanoTime()
                val got = try { late.b.model().also { models += it } } catch (x: TajweedError) { x }
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("B's model() with A's model open past the bound: ${(got as? TajweedError)?.let { "${it.code} ${it.errorCause}" } ?: "opened"} after $ms ms")
                assertEquals("session_open", (got as? TajweedError)?.code)
                assertTrue("it waited the bound: $ms ms", ms >= OpenWait.MS - 100)
                // the call's own work before and after the wait (the lease, the files, the open) is in it too: on a busy
                // machine that is seconds, never a minute
                assertTrue("and then it ended: $ms ms", ms < OpenWait.MS + 60_000)
                late.end()
                val mb = late.b.model().also { models += it }
                take(mb)
            } finally {
                late.held.go.countDown()
            }
        }
    }

    /** The Core's engine, whose first open waits, once the Core's open has returned, until the test lets it go. */
    private class HeldOpen : TajweedEngineFactory {
        val opened = CountDownLatch(1)
        val go = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun open(model: ai.quranlab.tajweedformer.ModelFolder, context: String, options: ai.quranlab.tajweedformer.Recognizer.Options):
            ai.quranlab.tajweedformer.TajweedEngine {
            val e = TajweedEngineFactory.CORE.open(model, context, options)
            if (first.compareAndSet(true, false)) {
                opened.countDown()
                val until = System.nanoTime() + 60_000_000_000L
                while (System.nanoTime() < until) {
                    try {
                        if (go.await(100, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        // a native open does not stop for an interrupt
                    }
                }
            }
            return e
        }
    }
}

/**
 * The public Client of one install on the desktop JVM, with the REAL Core and engine, and the protected test build
 * ([src], TJ_CORE_TEST_BUILD's `cdn/sdk/v4.0`) served at the folder the lease names, as TwoContextKit serves its own:
 * the stand-in API attests the software install key and leases the build under the build key of its key id.
 */
internal class RealBuildKit(src: File) : AutoCloseable {
    val stub = StubQlab()
    val served: ServedModel = ServedModel.ofFolder(src)
    val manifest: ModelManifest = ModelManifest.parse(File(src, ModelManifest.FILE_NAME).readBytes())
    private val dirs = ArrayList<File>()

    /** For the n-th lease request (from 1): run before it is answered (a test holds an answer with it). */
    @Volatile var beforeLease: ((Int) -> Unit)? = null

    /** Seconds from a lease's `iat` to its `refresh_after` (0: a refresh is due at once). */
    @Volatile var refreshAfterS = 43_200L

    init {
        stub.inference = null
        stub.recommended = mapOf("model" to ServedModel.REPO, "model_id" to manifest.modelId, "folder" to ServedModel.FOLDER, "revision" to served.build,
            "version" to manifest.version, "key_id" to manifest.encryption!!.keyId, "source" to "cdn")
        val keyId = QuranLabKey.parse(stub.key)!!.keyId
        stub.overrides["/v1/attest/challenge"] = { n, _ ->
            StubQlab.Reply.json(200, mapOf("challenge" to "c1.${now() + 300}.${"A".repeat(21)}${'A' + (n % 26)}.${"B".repeat(22)}", "expires_in" to 300))
        }
        stub.overrides["/v1/attest"] = { _, q ->
            val b = q.json
            if (b == null || !b.containsKey("install_key")) null else {
                val ik = unb64u(b["install_key"] as String)
                val sk = unb64u(b["install_sign_key"] as String)
                StubQlab.Reply.json(200, mapOf("token" to stub.token(false), "expires_in" to 86_400, "attested" to false,
                    "install_cert" to ProtectedKit.cert("android:${stub.appId}", "android", keyId, hex(sha256(ik)), hex(sha256(sk)), att = "none",
                        hw = 0, iat = now(), exp = now() + 30L * 86_400, recheck = 0)))
            }
        }
        stub.overrides["/v1/model-key"] = { n, q ->
            beforeLease?.invoke(n)
            val b = q.json!!
            val ik = unb64u(b["install_key"] as String)
            val iat = now() - 60
            val exp = iat + 86_400
            val kid = hex(manifest.encryption!!.keyId)
            val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = 0, iat = iat, exp = exp, refreshAfter = iat + refreshAfterS,
                keyId = kid, build = hex(b["build"] as String), installHash = sha256(ik), model = b["model"] as String, version = b["version"] as String),
                ik, buildKey = ProtectedKit.buildKeyFor(kid, b["model"] as String, b["version"] as String))
            StubQlab.Reply.json(200, linkedMapOf("v" to 1, "lease" to b64u(lease), "iat" to iat, "exp" to exp, "refresh_after" to iat + refreshAfterS,
                "base" to "https://${HostNames.MODELS[0]}/${ServedModel.REPO}/${b["build"]}/${ServedModel.FOLDER}/", "install_cert" to b["install_cert"]))
        }
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun temp(): File = Files.createTempDirectory("tj-realbuild-").toFile().also { dirs += it }

    /** One install's storage (every Client of the kit is a Client of this install). */
    val storage: File = temp()

    /** A Client of this install, with the Core's engine ([engine]: a test's wrapper of it). */
    fun client(engine: TajweedEngineFactory = TajweedEngineFactory.CORE): TajweedClient {
        val api = HostNames.API[0]
        val cdn = HostNames.MODELS[0]
        val ep = Endpoints.mapped(listOf(api), listOf(cdn), mapOf(api to stub.base, cdn to served.url))
        val platform = ClientPlatform(cloud = stub.environment(storage), apiUrl = stub.base, endpoints = ep,
            trustedKeys = listOf(stub.publicKey, ProtectedKit.signerPublic), engine = engine, modelRetry = RetryPlan.NONE,
            network = NetworkPolicy { "unmetered" },
            dispatch = { s -> DispatchLibrary.Resolution(DispatchLibrary.Folder(temp(), "libedgetpu_litert.so", "lib:test:$s", false), null) })
        return TajweedClient(stub.key, TajweedClientOptions(), platform, autoLaunch = false)
    }

    override fun close() {
        served.close()
        stub.close()
        dirs.forEach { it.deleteRecursively() }
    }

    companion object {
        /** The served folder of TJ_CORE_TEST_BUILD (`cdn/sdk/v4.0`), or null. */
        fun source(): File? = (System.getProperty("TJ_CORE_TEST_BUILD") ?: System.getenv("TJ_CORE_TEST_BUILD"))?.let { File(it, "cdn/sdk/v4.0") }
            ?.takeIf { File(it, ModelManifest.FILE_NAME).isFile }
    }
}
