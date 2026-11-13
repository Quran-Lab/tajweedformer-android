package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * How the consent sheet can end, and what each ending records (SDK 3.1.1, the rule of 3.1.0 kept): one case per way a
 * learner or the system can end the sheet, against the real sharing state and the stub server. For each: the ask
 * counter, the stored answer (read back by a new client on the same storage, as after a restart), the telemetry's
 * counters of asks, yeses and nos, and whether the next completed session asks again.
 *
 * Which UI event becomes which [SheetExit] is the drawings' part: the Views dialog and the Compose sheet both report
 * it through [SheetExit.record]. The Compose module's device check (`SheetHostActivity`, driven from a host with the
 * system's own input) showed each event on a phone reaching the exit named here.
 */
class SheetExitTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private val dirs = ArrayList<File>()

    @Before fun up() { stub = StubQlab() }

    @After fun down() {
        dirs.forEach { it.deleteRecursively() }
        if (::stub.isInitialized) stub.close()
    }

    private fun cloud(storage: File, guardian: Boolean = false): QuranLabCloud {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey),
            audience = if (guardian) CloudOptions.Audience.CHILDREN else CloudOptions.Audience.GENERAL))
        c.refreshConfig()
        return c
    }

    /** A completed session with feedback: when the rules allow, the SDK counts an ask and hands its sheet over. */
    private fun completedSession(c: QuranLabCloud) {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = 16_000, inputRoute = "built_in", decodeBackend = "mock"))
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))
    }

    /** What 3.1.0 recorded for an ending: the stored answer, the asks counted, the telemetry's yes and no. */
    private data class Recorded(val consent: ConsentState.Consent, val asks: Int, val yes: Int, val no: Int, val askedAgainAtTheNextSession: Boolean)

    /**
     * Every way the sheet can end. A button or a dismissal ends it with an exit; the others end nothing (the window goes
     * with the activity or the composition, or the app or the process goes) and nothing is recorded.
     */
    private val endings: List<Pair<String, SheetExit?>> = listOf(
        "the Share button" to SheetExit.SHARE,
        "the Not now button" to SheetExit.NOT_NOW,
        "the back gesture or key (3.1.0: the dialog was cancelable)" to SheetExit.CLOSED,
        "a tap beside the sheet (3.1.0: a tap outside the dialog)" to SheetExit.CLOSED,
        "a drag down from the grabber (new in 3.1.1, recorded as 3.1.0's cancel)" to SheetExit.CLOSED,
        "a configuration change that recreates the activity (the window goes, no dismissal)" to null,
        "the Compose host leaving the composition" to null,
        "the app going to the background (the sheet stays)" to null,
        "the process ending while the sheet is shown" to null,
    )

    /** 3.1.0's record for each exit after the first ask (the learner's variant). */
    private fun expected(exit: SheetExit?) = when (exit) {
        SheetExit.SHARE -> Recorded(ConsentState.Consent.GRANTED, asks = 1, yes = 1, no = 0, askedAgainAtTheNextSession = false)
        SheetExit.NOT_NOW, SheetExit.CLOSED -> Recorded(ConsentState.Consent.DECLINED, asks = 1, yes = 0, no = 1, askedAgainAtTheNextSession = false)
        null -> Recorded(ConsentState.Consent.UNKNOWN, asks = 1, yes = 0, no = 0, askedAgainAtTheNextSession = true)
    }

    @Test fun eachEndingRecordsWhat310Recorded() {
        for ((how, exit) in endings) {
            val storage = Files.createTempDirectory("tj-exit").toFile().also { dirs.add(it) }
            val c = cloud(storage)
            val asked = ArrayList<Sharing.SheetRequest>()
            c.sharing.prompts = { asked.add(it) }
            completedSession(c)
            assertEquals("$how: the ask is counted before the sheet shows", listOf<Sharing.SheetRequest>(Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, false)), asked)
            assertEquals(how, 1, c.sharing.state.asks)
            if (how.startsWith("the app going to the background")) {
                c.background()
                assertEquals("$how: nothing recorded while away", ConsentState.Consent.UNKNOWN, c.sharing.state.consent)
                c.foreground()
            }
            exit?.record(c.sharing, guardian = false)
            c.background()
            // the counters of every report sealed since the install (one per send point)
            val t = HashMap<String, Double>()
            for (f in c.telemetry.pendingReports()) for ((k, v) in stub.parse(f.readBytes())) if (v is Double) t[k] = (t[k] ?: 0.0) + v
            // the stored answer, as a restart reads it
            val again = cloud(storage)
            val askedAgain = ArrayList<Sharing.SheetRequest>()
            again.sharing.prompts = { askedAgain.add(it) }
            val stored = again.sharing.state.consent
            val storedAsks = again.sharing.state.asks
            // after a yes a session would start a recording: the SDK's own question, whether it may ask now, says the same
            val askedAgainNow = if (stored == ConsentState.Consent.GRANTED) again.sharing.askNow() != null else {
                completedSession(again)
                askedAgain.isNotEmpty()
            }
            val got = Recorded(stored, storedAsks, (t["share_yes"] ?: 0.0).toInt(), (t["share_no"] ?: 0.0).toInt(), askedAgainNow)
            assertEquals(how, expected(exit), got)
            assertEquals("$how: one ask counted in telemetry", 1.0, t["share_asked"])
            if (got.askedAgainAtTheNextSession) {
                assertEquals("$how: no answer, so the first sheet again, counted as the second ask",
                    listOf<Sharing.SheetRequest>(Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, false)), askedAgain)
                assertEquals(how, 2, again.sharing.state.asks)
            }
        }
    }

    /** "Not now" and the sheet closed without a button are the same no: a re-ask 7 sessions later, the third one final. */
    @Test fun aClosedSheetIsANotNowUpToTheThirdAsk() {
        for (exit in listOf(SheetExit.NOT_NOW, SheetExit.CLOSED)) {
            val storage = Files.createTempDirectory("tj-exit").toFile().also { dirs.add(it) }
            val c = cloud(storage)
            val at = ArrayList<Int>()
            c.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) exit.record(c.sharing, guardian = false) }
            for (i in 1..30) {
                val before = c.sharing.state.asks
                completedSession(c)
                if (c.sharing.state.asks > before) at.add(i)
            }
            assertEquals("$exit: the first ask, then 7 sessions apart", listOf(1, 8, 15), at)
            assertEquals("$exit: the third no is final", ConsentState.Consent.FINAL, cloud(storage).sharing.state.consent)
            assertNull(c.sharing.askNow())
        }
    }

    /** The guardian variant: Share records the guardian's yes; closing it is a no, as 3.1.0's guardian sheet. */
    @Test fun theGuardiansSheet() {
        for (exit in listOf(SheetExit.SHARE, SheetExit.CLOSED)) {
            val storage = Files.createTempDirectory("tj-exit").toFile().also { dirs.add(it) }
            val c = cloud(storage, guardian = true)
            val asked = ArrayList<Sharing.SheetRequest>()
            c.sharing.prompts = { asked.add(it) }
            completedSession(c)
            assertEquals(Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, true), asked.single())
            exit.record(c.sharing, guardian = true)
            val stored = cloud(storage, guardian = true).sharing.state
            if (exit == SheetExit.SHARE) {
                assertEquals(ConsentState.Consent.GRANTED, stored.consent)
                assertEquals("the yes is the guardian's", true, stored.guardian)
            } else {
                assertEquals(ConsentState.Consent.DECLINED, stored.consent)
            }
        }
    }
}
