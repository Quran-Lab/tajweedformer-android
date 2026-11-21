package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.CountingEngines
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate C (2026-11-20), SDK 4.0.1: a model that was opened is never dropped. `TajweedClient.model()` opens the model on
 * the IO dispatcher; when the app's coroutine was cancelled while the open ran, the Core's open did not stop for the
 * interrupt and completed, and `withContext` threw the cancel instead of returning the Model: the Model stayed open with
 * no one holding it, and since the Core opens one model at a time, every later `model()` of that Client failed
 * `engine_failed` (QLC_E_BUSY). [openKept] is what `model()` now opens through: the Model it opened becomes the Client's,
 * or it is closed.
 *
 * The Core here is a stand-in with the Core's two traits that matter ([OneModelCore]): one open model at a time (a
 * second open is QLC_E_BUSY, read as the SDK reads the Core's failure at the open), and an open that does not stop for
 * an interrupt. The Model is the SDK's own (TajweedModel over a signed plain folder, as the other JVM tests open it).
 */
class ModelHandOverTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    /** Every Model opened, closed at the end whatever the test did (the control drops one on purpose). */
    private val opened = Collections.synchronizedList(ArrayList<TajweedModel>())

    @After
    fun cleanUp() {
        opened.forEach { try { it.close() } catch (_: Exception) {} }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun folder(): ModelFolder {
        val dir = Files.createTempDirectory("tj-handover-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        return ModelFolder.open(dir, TestKit.verifier)
    }

    /**
     * The Core's one open model: an engine factory that refuses an open while an engine it opened is not closed
     * (QLC_E_BUSY, through [CoreException.toOpenError] as the SDK reads it), and whose open, while [hold] is set, waits
     * for it without stopping for an interrupt (a native call completes whatever the thread's interrupt says).
     */
    private class OneModelCore : TajweedEngineFactory {
        private val inner = CountingEngines()
        /** Engines open now. */
        val live = AtomicInteger()
        val opens = AtomicInteger()
        @Volatile var hold: CountDownLatch? = null
        /** The open returns with the thread's interrupt still set, as a native call does (it neither clears nor reads it). */
        @Volatile var keepInterrupt = false
        val entered = CountDownLatch(1)

        override fun open(model: ModelFolder, context: String, options: Recognizer.Options): TajweedEngine {
            if (live.get() > 0) throw CoreException(Core.E_BUSY, 0, "qlc_model_open").toOpenError()
            entered.countDown()
            var interrupted = false
            hold?.let { h ->
                while (true) {
                    try {
                        if (h.await(30, TimeUnit.SECONDS)) break else throw AssertionError("the test never released the open")
                    } catch (_: InterruptedException) {
                        // the native open does not stop for it
                        interrupted = true
                    }
                }
            }
            if (interrupted && keepInterrupt) Thread.currentThread().interrupt()
            val e = inner.open(model, context, options)
            live.incrementAndGet()
            opens.incrementAndGet()
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            return object : TajweedEngine by e {
                override fun close() {
                    e.close()
                    if (closed.compareAndSet(false, true)) live.decrementAndGet()
                }
            }
        }
    }

    private fun open(folder: ModelFolder, core: OneModelCore): TajweedModel =
        TajweedModel.of(folder, ModelHost(verifier = TestKit.verifier, engine = core)).also { opened.add(it) }

    /** The control: 4.0.0's shape of `model()` drops the Model when its caller is cancelled during the open, and the next
     *  open meets QLC_E_BUSY. This is the failure the fix is for; the class reproduces it, or its other tests prove nothing. */
    @Test
    fun theShapeOf400DropsTheModelAndTheNextOpenIsBusy() = runBlocking {
        val core = OneModelCore().also { it.hold = CountDownLatch(1) }
        val folder = folder()
        var kept: TajweedModel? = null
        val job = launch(Dispatchers.Default) {
            val m = withContext(Dispatchers.IO) { runInterruptible { open(folder, core) } }
            kept = m
        }
        assertTrue("the open began", core.entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        core.hold!!.countDown()
        job.join()
        assertNull("the caller never got the Model", kept)
        assertEquals("the Model was opened and stays open, held by no one", 1, core.live.get())
        core.hold = null
        try {
            open(folder, core)
            fail("a second open beside the dropped one")
        } catch (e: TajweedError) {
            assertEquals("engine_failed", e.code)
            assertEquals("busy", ErrorCause.of(e).errorCode)
        }
    }

    /** The fix: the caller cancelled while the open runs; the Model it opened is closed, and the next open works. */
    @Test
    fun aCallerCancelledDuringTheOpenLeavesNothingOpen() = runBlocking {
        val core = OneModelCore().also { it.hold = CountDownLatch(1) }
        val folder = folder()
        var kept: TajweedModel? = null
        val job = launch(Dispatchers.Default) {
            openKept({ open(folder, core) }) { kept = it }
        }
        assertTrue("the open began", core.entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        core.hold!!.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertNull("the caller never got the Model", kept)
        assertEquals("the Model the cancelled call opened was closed", 0, core.live.get())
        assertEquals(1, core.opens.get())
        // the next open of the same Core works
        core.hold = null
        val m = openKept({ open(folder, core) }) { kept = it }
        assertSame(m, kept)
        assertEquals(1, core.live.get())
        m.close()
        assertEquals(0, core.live.get())
    }

    /** The same with the thread's interrupt still set when the open returns, as after a native call: whatever the steps
     *  after the open do with it, nothing stays open and the next open works. */
    @Test
    fun anOpenThatReturnsInterruptedLeavesNothingOpen() = runBlocking {
        val core = OneModelCore().also { it.hold = CountDownLatch(1); it.keepInterrupt = true }
        val folder = folder()
        var kept: TajweedModel? = null
        val job = launch(Dispatchers.Default) {
            openKept({ open(folder, core) }) { kept = it }
        }
        assertTrue("the open began", core.entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        core.hold!!.countDown()
        job.join()
        assertNull(kept)
        assertEquals(1, core.opens.get())
        assertEquals("nothing the cancelled call opened stays open", 0, core.live.get())
        core.hold = null
        openKept({ open(folder, core) }) {}.close()
        assertEquals(0, core.live.get())
    }

    /** A step after the open that fails (here the hand-over itself) closes the Model, and its failure goes on. */
    @Test
    fun aFailureAfterTheOpenClosesTheModel() = runBlocking {
        val core = OneModelCore()
        val folder = folder()
        try {
            openKept({ open(folder, core) }) { throw IllegalStateException("the step after the open failed") }
            fail("the hand-over's failure was lost")
        } catch (e: IllegalStateException) {
            assertEquals("the step after the open failed", e.message)
        }
        assertEquals(1, core.opens.get())
        assertEquals(0, core.live.get())
        val m = openKept({ open(folder, core) }) {}
        m.close()
    }

    /** An open that fails hands nothing over and closes nothing: its own error goes to the caller, as before. */
    @Test
    fun anOpenThatFailsIsTheCallersError() = runBlocking {
        val core = OneModelCore()
        val folder = folder()
        val first = open(folder, core)
        try {
            openKept({ open(folder, core) }) { fail("nothing was opened to keep") }
            fail("a second open beside an open Model")
        } catch (e: TajweedError) {
            assertEquals("engine_failed", e.code)
            assertEquals(listOf("model_open", "engine", "busy"), ErrorCause.of(e).let { listOf(it.stage, it.reason, it.errorCode) })
            assertTrue("the Core's failure is the cause", e.cause is CoreException)
        }
        assertEquals("the open Model is untouched", 1, core.live.get())
        first.close()
    }
}
