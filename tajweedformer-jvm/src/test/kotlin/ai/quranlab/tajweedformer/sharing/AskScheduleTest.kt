package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.CloudBridge
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.SessionInfo
import ai.quranlab.tajweedformer.SessionStats
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 4.0: when the SDK asks a learner to share (SHARE_ASK_40, section 20), through the real hooks on the stub's fake
 * clock: the Client's start (`launch`), takes started and ended as `CloudBridge` reports them, the settle of 20.6
 * ([Sharing.settleNow] ends its wait), the request handed to the UI, `shown` before it is on screen, the answers. The
 * card appears after the settle, goes with a take start, the sheet opens from it, and while the deck has no card
 * strings the 3.x re-ask sheet stands in (20.10 item 5). The decision itself is pinned by `ShareAskVectorsTest`.
 */
class AskScheduleTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()

    @Before fun up() { stub = StubQlab() }

    @After fun down() {
        dirs.forEach { it.deleteRecursively() }
        if (::stub.isInitialized) stub.close()
    }

    private fun storage() = Files.createTempDirectory("tj-ask").toFile().also { dirs.add(it) }

    private val requests = ArrayList<Sharing.SheetRequest>()
    private val dismissed = ArrayList<Sharing.SheetRequest>()

    private val now get() = stub.clock()

    /** Due at the next good moment: installed two days ago, two completed sessions, never asked. */
    private fun ready() = """{"v":1,"consent":"unknown","asks":0,"lastAskSession":0,"sessions":2,"installedAt":${now - 2 * ShareAsk.DAY_MS},"thanked":false}"""

    /** One "Not now" 15 days and 8 sessions ago: the card is due at the next good moment. */
    private fun reask(): String {
        val at = now - 15 * ShareAsk.DAY_MS
        return """{"v":1,"consent":"declined","asks":1,"lastAskSession":1,"sessions":8,"installedAt":${now - 40 * ShareAsk.DAY_MS},""" +
            """"lastAskAt":$at,"lastAskDay":${ShareAsk.localDay(ShareAsk.Now(at, 0))},"thanked":false,"decidedAt":1}"""
    }

    /** The card's lines (20.7) as an app's own wording (every deck from 2026-11-15.1 has its own; an app may still set them). */
    private val card = mapOf("card.line" to "Your recitation today could help another learner tomorrow.", "card.open" to "See how it helps",
        "card.not_now" to "Not now")

    /** The English deck without the card's lines: a deck before 2026-11-15.1. */
    private fun deckWithoutCard(): SharingCopy {
        val text = SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/en.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val d = SharingCopy.parse(text.lines().filterNot { it.trimStart().startsWith("\"card.") }.joinToString("\n"))
        assertTrue("the card's lines are in the bundled deck", SharingCopy.ENGLISH.hasCard)
        assertFalse("and not in this one", d.hasCard)
        return d
    }

    /** A Client's start on [storage] (with [state] written first): the launch, the signed config, the UI's requests collected. */
    private fun cloud(storage: File, state: String? = null, wording: Map<String, String>? = null): QuranLabCloud {
        state?.let { File(storage, "sharing").mkdirs(); File(storage, "sharing/state.json").writeText(it) }
        val c = stub.cloud(storage)
        c.launch(foreground = false)
        wording?.let { c.sharing.setWording(it) }
        c.sharing.prompts = { r ->
            when (r) {
                is Sharing.SheetRequest.Consent -> r.onDismiss = { dismissed += r }
                is Sharing.SheetRequest.Card -> r.onDismiss = { dismissed += r }
                else -> {}
            }
            requests += r
        }
        return c
    }

    private fun start(c: QuranLabCloud) =
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = 16_000, inputRoute = "built_in", decodeBackend = "mock"))

    /** A take that ends [end] with [speechFrames] of speech (1500: a minute). */
    private fun take(c: QuranLabCloud, speechFrames: Int = 1500, end: String = "normal") {
        start(c)
        c.onSessionEnd(TajweedObserver.SessionEnd(60.0, completed = end == "normal", feedbackShown = true, audioMs = 60_000,
            speechFrames = speechFrames, end = end))
    }

    private fun consent(v: ConsentState.Variant) = Sharing.SheetRequest.Consent(v)

    @Test fun theFirstAskComesOnceTheMomentSettled() {
        val c = cloud(storage(), ready())
        take(c)
        assertTrue("nothing before the settle: $requests", requests.isEmpty())
        assertEquals("in hand", ShareAsk.Ask("sheet", 1, false), c.sharing.state.askInHand)
        c.sharing.settleNow()
        assertEquals(listOf(consent(ConsentState.Variant.FIRST)), requests)
        assertEquals("counted only when on screen", 0, c.sharing.state.asks)
        assertTrue((requests.single() as Sharing.SheetRequest.Consent).shown())
        assertEquals(1, c.sharing.state.asks)
        assertEquals(now.toDouble(), c.sharing.state.lastAskAt!!.toDouble(), 5_000.0)
        c.sharing.accept()
        assertTrue(c.sharing.isOn)
    }

    @Test fun aTakeThatStartsBeforeTheSettleDropsTheAsk() {
        val c = cloud(storage(), ready())
        take(c)
        start(c)
        assertNull("dropped, counted nowhere", c.sharing.state.askInHand)
        c.sharing.settleNow()
        assertTrue(requests.isEmpty())
        // decided again at that take's end
        c.onSessionEnd(TajweedObserver.SessionEnd(60.0, completed = true, feedbackShown = true, audioMs = 60_000, speechFrames = 1500))
        c.sharing.settleNow()
        assertEquals(listOf(consent(ConsentState.Variant.FIRST)), requests)
    }

    @Test fun theCardAppearsAfterTheSettleAndGoesWithATakeStart() {
        // the bundled deck's own lines (2026-11-15.1), in the language in force
        val c = cloud(storage(), reask())
        val deck = c.sharing.copy
        assertTrue(deck.hasCard)
        assertEquals("the card's Not now is the sheet's", deck["sheet.not_now"], deck["card.not_now"])
        take(c)
        assertTrue(requests.isEmpty())
        c.sharing.settleNow()
        val r = requests.single() as Sharing.SheetRequest.Card
        assertEquals(deck["card.line"], r.line)
        assertEquals(deck["card.open"], r.openLabel)
        assertEquals(deck["card.not_now"], r.notNowLabel)
        if (deck.lang == "en") assertEquals(card, mapOf("card.line" to r.line, "card.open" to r.openLabel, "card.not_now" to r.notNowLabel))
        assertTrue(r.shown())
        assertEquals("a card on screen is one of the three", 2, c.sharing.state.asks)
        start(c)
        assertEquals("taken back", listOf<Sharing.SheetRequest>(r), dismissed)
        assertNull(c.sharing.state.askInHand)
        assertEquals("a card gone with a take start is a Not now", ConsentState.Consent.DECLINED, c.sharing.state.consent)
        assertEquals(2, c.sharing.state.asks)
    }

    @Test fun theSheetOpensFromTheCard() {
        val c = cloud(storage(), reask(), card)
        take(c)
        c.sharing.settleNow()
        val r = requests.single() as Sharing.SheetRequest.Card
        assertTrue(r.shown())
        r.open()
        assertEquals("the full sheet, without the re-ask line", consent(ConsentState.Variant.FIRST), requests.last())
        assertTrue((requests.last() as Sharing.SheetRequest.Consent).shown())
        assertEquals("one ask, not two", 2, c.sharing.state.asks)
        c.sharing.accept()
        assertTrue(c.sharing.isOn)
    }

    @Test fun aYesOnACardThatWasNotOpenedBringsTheSheet() {
        val c = cloud(storage(), reask(), card)
        take(c)
        c.sharing.settleNow()
        assertTrue((requests.single() as Sharing.SheetRequest.Card).shown())
        // an app's own card answering yes: not a yes (card_has_no_yes); the full sheet comes in its place
        c.sharing.accept()
        assertFalse(c.sharing.isOn)
        assertEquals(consent(ConsentState.Variant.FIRST), requests.last())
        c.sharing.accept()
        assertTrue(c.sharing.isOn)
        assertEquals(2, c.sharing.state.asks)
    }

    @Test fun withoutTheCardsStringsTheReaskSheetStandsIn() {
        val c = cloud(storage(), reask())
        c.sharing.deckForTests = deckWithoutCard()
        take(c)
        c.sharing.settleNow()
        assertEquals(listOf(consent(ConsentState.Variant.REASK)), requests)
        assertTrue((requests.single() as Sharing.SheetRequest.Consent).shown())
        assertEquals(2, c.sharing.state.asks)
        assertEquals("shown, then opened at once: the sheet's answer is the ask's", ShareAsk.Ask("sheet", 2, true), c.sharing.state.askInHand)
        c.sharing.decline()
        assertEquals(ConsentState.Consent.DECLINED, c.sharing.state.consent)
        assertNull(c.sharing.state.askInHand)
        assertEquals(2, c.sharing.state.asks)
    }

    @Test fun theAppsAskKeepsItsMomentButNotTheCadence() {
        val c = cloud(storage(), ready())
        assertFalse("never at launch", c.sharing.askNow())
        start(c)
        assertFalse("never during a take", c.sharing.askNow())
        // a short take: no ask of the SDK's (under a minute), but the app's may come now
        c.onSessionEnd(TajweedObserver.SessionEnd(5.0, completed = true, feedbackShown = true, audioMs = 5_000, speechFrames = 100))
        c.sharing.settleNow()
        assertTrue(requests.isEmpty())
        assertTrue(c.sharing.askNow())
        assertEquals("at once, no settle", listOf(consent(ConsentState.Variant.FIRST)), requests)
        assertTrue((requests.single() as Sharing.SheetRequest.Consent).shown())
        c.sharing.decline()
        assertFalse("not again in this app session", c.sharing.askNow())
    }

    @Test fun aStaleAskIsNotShown() {
        val c = cloud(storage(), ready())
        take(c)
        c.sharing.settleNow()
        val r = requests.single() as Sharing.SheetRequest.Consent
        // the switch turned before the sheet came up
        c.sharing.setOn(false)
        assertEquals(listOf<Sharing.SheetRequest>(r), dismissed)
        assertFalse(r.shown())
        assertEquals(0, c.sharing.state.asks)
    }

    @Test fun onlyANormalEndWithSpeechIsASession() {
        val c = cloud(storage(), ready())
        take(c, end = "interrupted")
        take(c, end = "error")
        take(c, end = "cancelled")
        take(c, speechFrames = 0)
        assertEquals(2, c.sharing.state.sessions)
        c.sharing.settleNow()
        assertTrue(requests.isEmpty())
        take(c)
        assertEquals(3, c.sharing.state.sessions)
    }

    @Test fun theMinuteIsSummedOverTheAppSessionAndANewOneStartsAfterThirtyMinutesAway() {
        val c = cloud(storage(), ready())
        take(c, speechFrames = 500)
        assertEquals(1, c.sharing.state.app.takes)
        // away under 30 minutes: the same app session
        c.background()
        stub.skewMs += 29 * ShareAsk.MINUTE_MS
        c.foreground()
        assertEquals(1, c.sharing.state.app.takes)
        // away 30 minutes: a new one, its recitation from nothing
        c.background()
        stub.skewMs += 30 * ShareAsk.MINUTE_MS
        c.foreground()
        assertEquals(0, c.sharing.state.app.takes)
        take(c, speechFrames = 500)
        take(c, speechFrames = 500)
        c.sharing.settleNow()
        assertTrue("40 s in this app session: $requests", requests.isEmpty())
        take(c, speechFrames = 500)
        c.sharing.settleNow()
        assertEquals("a minute summed over three takes", listOf(consent(ConsentState.Variant.FIRST)), requests)
    }

    @Test fun theAppLeavingTakesBackAnAskNotYetShown() {
        val c = cloud(storage(), ready())
        take(c)
        c.background()
        c.sharing.settleNow()
        assertTrue(requests.isEmpty())
        assertNull(c.sharing.state.askInHand)
        assertEquals(0, c.sharing.state.asks)
    }

    @Test fun theConfigsShareAskIsRead() {
        stub.extra = mapOf("share_ask" to mapOf("first_min_sessions" to 5))
        val c = cloud(storage(), ready())
        repeat(2) {
            take(c)
            c.sharing.settleNow()
        }
        assertTrue("sessions 3 and 4 of 5: $requests", requests.isEmpty())
        take(c)
        c.sharing.settleNow()
        assertEquals(listOf(consent(ConsentState.Variant.FIRST)), requests)
    }

    @Test fun howATakeEndedComesFromTheSession() {
        val ends = ArrayList<TajweedObserver.SessionEnd>()
        val bridge = CloudBridge(object : TajweedObserver {
            override fun onSessionEnd(e: TajweedObserver.SessionEnd) { ends += e }
        })
        val info = SessionInfo(1, "v4.0", "c16_l64", Backend.CPU, 16_000, 0)
        fun end(outcome: String, by: String?) =
            bridge.onSessionEnd(info, SessionStats(10.0, 1.0, 10, outcome, Backend.CPU, speechFrames = 200, endedBy = by))
        end("finished", null)
        end("finished", "SILENCE")
        end("finished", "LIMIT")
        end("finished", "SOURCE_END")
        end("finished", "BACKGROUND")
        end("finished", "HIDDEN")
        end("finished", "INTERRUPTED")
        end("failed", "ERROR")
        end("cancelled", null)
        assertEquals(listOf("normal", "normal", "normal", "normal", "interrupted", "interrupted", "interrupted", "error", "cancelled"), ends.map { it.end })
        assertEquals(200, ends.first().speechFrames)
    }
}
