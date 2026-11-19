package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 4.0's stored part of the ask (SHARE_ASK_40 20.4) in the consent state file: the three new fields written and
 * read back, an ask that never reached the screen counted nowhere (the 3.1.5 issue the Flutter work found), a 3.x
 * state read for the first time, and a request counted once whoever calls `shown`.
 */
class AskShownTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-ask-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private var t = 1_790_000_000_000L
    private val now get() = ShareAsk.Now(t, 0)

    @Suppress("UNCHECKED_CAST")
    private fun onDisk(f: File) = Json.parse(f.readText()) as Map<String, Any?>

    private fun goodTake(s: ConsentState): ShareAsk.Decision {
        s.decide(ShareAsk.Event("take_start"), null, now)
        return s.decide(ShareAsk.Event("take_end", end = "normal", speechMs = 60_000, offered = true), null, now)
    }

    @Test
    fun theFirstLaunchStoresItsTimeAndAnAskOnScreenStoresItsOwn() {
        val f = File(temp(), "state.json")
        val s = ConsentState(f) { t }
        s.decide(ShareAsk.Event("launch"), null, now)
        assertEquals("the first launch with 4.0 is stored at once", t.toDouble(), onDisk(f)["installedAt"])
        assertNull(onDisk(f)["lastAskAt"])
        val installed = t
        t += 25 * ShareAsk.HOUR_MS
        repeat(2) { assertEquals("none", goodTake(s).action) }
        val d = goodTake(s)
        assertEquals("sheet", d.action)
        assertEquals("in hand, not on screen: nothing stored of it", 0.0, onDisk(f)["asks"])
        assertEquals("sheet", s.decide(ShareAsk.Event("shown"), null, now).action)
        val o = onDisk(f)
        assertEquals(1.0, o["asks"])
        assertEquals(t.toDouble(), o["lastAskAt"])
        assertEquals(ShareAsk.localDay(now).toDouble(), o["lastAskDay"])
        assertEquals(3.0, o["lastAskSession"])
        // a new process reads the three back
        val again = ConsentState(f) { t }
        assertEquals(installed, again.installedAt)
        assertEquals(t, again.lastAskAt)
        assertEquals(ShareAsk.localDay(now), again.lastAskDay)
        assertEquals(1, again.asks)
    }

    @Test
    fun anAskThatWasNeverShownCountsNothing() {
        val f = File(temp(), "state.json")
        f.writeText("""{"v":1,"consent":"unknown","asks":0,"lastAskSession":0,"sessions":5,"installedAt":1,"thanked":false}""")
        val s = ConsentState(f) { t }
        s.decide(ShareAsk.Event("launch"), null, now)
        assertEquals("sheet", goodTake(s).action)
        // nothing could show it: taken back before it was on screen
        s.decide(ShareAsk.Event("withdrawn"), null, now)
        assertEquals(0, s.asks)
        assertNull(s.askInHand)
        // so the next good moment asks again, and only the ask on screen counts
        assertEquals("sheet", goodTake(s).action)
        assertEquals("sheet", s.decide(ShareAsk.Event("shown"), null, now).action)
        assertEquals(1, s.asks)
    }

    @Test
    fun a3xStateCarriesItsCountAndStartsItsClocksAtTheUpgrade() {
        val f = File(temp(), "state.json")
        f.writeText("""{"v":1,"consent":"declined","asks":1,"lastAskSession":2,"sessions":9,"thanked":false,"decidedAt":1700000000}""")
        val s = ConsentState(f) { t }
        s.decide(ShareAsk.Event("launch"), null, now)
        val o = onDisk(f)
        assertEquals(t.toDouble(), o["installedAt"])
        assertEquals("the last ask's time is unknown: the upgrade", t.toDouble(), o["lastAskAt"])
        assertEquals(ShareAsk.localDay(now).toDouble(), o["lastAskDay"])
        assertEquals("declined", o["consent"])
        assertEquals(1.0, o["asks"])
        // the 3.x fields pass through
        assertEquals(1_700_000_000.0, o["decidedAt"])
        // 14 days and 7 sessions after it: the card
        t += 14 * ShareAsk.DAY_MS
        assertEquals("card", goodTake(s).action)
    }

    @Test
    fun aConsentRequestCountsOnceWhateverCallsShown() {
        var counted = 0
        val r = Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST) { counted++; true }
        assertTrue(r.shown())
        assertTrue(r.shown())
        assertEquals(1, counted)
        // a stale ask: not shown, and every later call says so
        val stale = Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST) { false }
        assertFalse(stale.shown())
        assertFalse(stale.shown())
    }
}
