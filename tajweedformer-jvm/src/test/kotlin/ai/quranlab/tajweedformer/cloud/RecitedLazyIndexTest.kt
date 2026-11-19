package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.RecitedReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage B (11-18, the Alcatel 1): the recited-ayah index loader. The client loads it at the model's open (a load at
 * its first use put it near the process's peak: +2.59 MB of peak RSS against 3.1.6, over Quran Lab's +2 MB line), at
 * most once per open; a load that failed is tried again where `qlc_recited_finish` reads it, at a counted take's end
 * and at the recovery of a take a kill cut short, and never at a take's start, while it is fed, or for a take that
 * counts nothing.
 */
class RecitedLazyIndexTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-ri-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    @Test
    fun theIndexIsLoadedAtMostOncePerOpenAndOnlyWhenAsked() {
        val loads = ArrayList<String>()
        val l = RecitedIndexLoader<String> { b -> loads += b }
        assertFalse("nothing prepared: nothing to load", l.ensure())
        l.prepare("build-1")
        assertTrue("preparing loads nothing", loads.isEmpty())
        assertTrue(l.ensure())
        assertTrue(l.ensure())
        assertEquals("once per open", listOf("build-1"), loads)
        // the next open (the same build or another): loaded again at its first use
        l.prepare("build-2")
        assertTrue(l.ensure())
        assertEquals(listOf("build-1", "build-2"), loads)
        // an open whose build has no index installed
        l.prepare(null)
        assertFalse(l.ensure())
        assertEquals(2, loads.size)
    }

    @Test
    fun aFailedLoadIsTriedAgainAtTheNextUse() {
        var fail = true
        val tries = AtomicInteger()
        val l = RecitedIndexLoader<String> { tries.incrementAndGet(); if (fail) throw IllegalStateException("qlc_recited_index") }
        l.prepare("b")
        assertFalse(l.ensure())
        fail = false
        assertTrue(l.ensure())
        assertTrue(l.ensure())
        assertEquals(2, tries.get())
    }

    @Test
    fun twoCountsAtOnceLoadItOnce() {
        val tries = AtomicInteger()
        val gate = CountDownLatch(1)
        val l = RecitedIndexLoader<String> { tries.incrementAndGet(); gate.await() }
        l.prepare("b")
        val threads = List(4) { Thread { l.ensure() }.apply { start() } }
        Thread.sleep(100)
        gate.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, tries.get())
    }

    @Test
    fun theCountAsksForTheIndexOnlyWhereItReadsIt() {
        val root = temp()
        val asked = AtomicInteger()
        val t = Telemetry(File(root, "telemetry")) { 1_800_000_000_000L }
        var rc = RecitedCount(File(root, "core"), t, { 1_800_000_000_000L }, core = { null }, ensureIndex = { asked.incrementAndGet(); true })
        rc.indexReady = true
        // a take started, fed and reported, then cancelled: it counts nothing and never needs the index
        val s1 = Any()
        rc.takeStarted(s1)
        rc.report(s1, RecitedReport(1, 1, 7))
        rc.takeEnded(s1, false)
        assertEquals("not at a take's start, while it is fed, or for a take that counts nothing", 0, asked.get())
        // a take open when the process is killed: the next process's recovery reads the index, once
        val s2 = Any()
        rc.takeStarted(s2)
        rc.report(s2, RecitedReport(2, 1, 5))
        rc = RecitedCount(File(root, "core"), t, { 1_800_000_000_000L }, core = { null }, ensureIndex = { asked.incrementAndGet(); true })
        rc.indexReady = true
        rc.recover()
        assertEquals("the recovery of a take a kill cut short", 1, asked.get())
        // nothing to recover: nothing loaded
        rc.recover()
        assertEquals(1, asked.get())
        // the index could not be loaded: the cut take still counts, as not found
        val s3 = Any()
        rc.takeStarted(s3)
        rc.report(s3, RecitedReport(3, 1, 4))
        val failing = RecitedCount(File(root, "core"), t, { 1_800_000_000_000L }, core = { null }, ensureIndex = { false })
        failing.indexReady = true
        failing.recover()
        failing.flush()
        @Suppress("UNCHECKED_CAST")
        val takes = (t.peek(TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel10", "tensor_g5", 16, "16")))!!["recited_takes"]
            as Map<String, Any?>)
        assertEquals(2.0, takes["recovered"])
    }
}
