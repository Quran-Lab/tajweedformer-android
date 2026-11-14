package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SDK 3.1.5: when the SDK's own sheet is asked for (PLATFORM_CLIENT.md item 2, SPEC 14.2).
 *
 * - A sheet that ended without an answer (its window went with the activity, the process ended while it was shown)
 *   is "not now": the next ask comes at least 7 sessions after it, with the re-ask line, and there are three asks at
 *   most. Until 3.1.5 the same first sheet came back at the next completed take.
 * - Never on the install's first launch: the first process of the install, a Client's start that
 *   finds no marker of an earlier start. The guard holds the SDK's own asking only: the app's ask() may come on it,
 *   and the takes of the first launch count as sessions. An install from before 3.1.5 (a consent state or a
 *   registration on the device) is past its first launch.
 *
 * Each [cloud] is one launch on the storage (a new Client's start); `firstLaunch` true leaves the storage as a new
 * install has it, false (the default of the stub) as an install that has started before.
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

    private val asked = ArrayList<Pair<Int, Sharing.SheetRequest>>()

    /** One launch: a new Client on [storage], its sheets collected with the session they followed. */
    private fun cloud(storage: File, firstLaunch: Boolean = false): QuranLabCloud {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)),
            firstLaunch = firstLaunch)
        c.refreshConfig()
        c.sharing.prompts = { r -> if (r is Sharing.SheetRequest.Consent) asked.add(c.sharing.state.sessions to r) }
        return c
    }

    /** A completed take with feedback: when the rules allow, the SDK counts an ask and hands its sheet over. */
    private fun completedSession(c: QuranLabCloud) {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", sampleRate = 16_000, inputRoute = "built_in", decodeBackend = "mock"))
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, true, feedbackShown = true))
    }

    private fun first() = Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, false)
    private fun reask() = Sharing.SheetRequest.Consent(ConsentState.Variant.REASK, false)

    // ---- C: a sheet that ended without an answer ----

    @Test fun aSheetThatEndedWithoutAnAnswerDoesNotComeBackAtTheNextTake() {
        val s = storage()
        var c = cloud(s)
        completedSession(c)
        assertEquals("the first ask", listOf(1 to first()), asked)
        // the sheet's window went with its activity: no answer recorded
        assertEquals(ConsentState.Consent.UNKNOWN, c.sharing.state.consent)
        completedSession(c)
        assertEquals("asked again at the next take, in the same launch", 1, asked.size)
        // and after a restart
        c = cloud(s)
        for (i in 3..30) {
            completedSession(c)
            if (i == 12) c = cloud(s)
        }
        assertEquals("unanswered sheets: the re-ask 7 sessions after each ask, three in all",
            listOf(1 to first(), 8 to reask(), 15 to reask()), asked)
        assertEquals(3, cloud(s).sharing.state.asks)
    }

    @Test fun theAppsAskAfterAnUnansweredSheetIsTheReask() {
        val s = storage()
        val c = cloud(s)
        completedSession(c)
        assertEquals(listOf(1 to first()), asked)
        assertEquals("the second ask has the re-ask line", ConsentState.Variant.REASK, c.sharing.askNow())
        assertEquals(ConsentState.Variant.REASK, c.sharing.askNow())
        assertNull("three asks at most", c.sharing.askNow())
    }

    /** The control: "Not now" and a sheet closed without a button were already a no, asked again 7 sessions later. */
    @Test fun aNotNowIsAskedAgainSevenSessionsLater() {
        for (exit in listOf(SheetExit.NOT_NOW, SheetExit.CLOSED)) {
            asked.clear()
            val s = storage()
            val c = cloud(s)
            c.sharing.prompts = { r -> if (r is Sharing.SheetRequest.Consent) { asked.add(c.sharing.state.sessions to r); exit.record(c.sharing, false) } }
            repeat(30) { completedSession(c) }
            assertEquals("$exit", listOf(1 to first(), 8 to reask(), 15 to reask()), asked)
            assertEquals("$exit", ConsentState.Consent.FINAL, c.sharing.state.consent)
        }
    }

    // ---- D: never on the first launch ----

    @Test fun theSdkDoesNotAskOnTheFirstLaunch() {
        val s = storage()
        val c = cloud(s, firstLaunch = true)
        repeat(3) { completedSession(c) }
        assertTrue("asked on the first launch: $asked", asked.isEmpty())
        assertEquals("the first launch's takes count as sessions", 3, c.sharing.state.sessions)
        assertEquals("no ask counted", 0, c.sharing.state.asks)
        // a second Client of the same storage is a later launch: its first completed take is the first ask
        val next = cloud(s, firstLaunch = true)
        completedSession(next)
        assertEquals("the first ask, at the next launch's first take", listOf(4 to first()), asked)
        // and the re-ask 7 sessions after it
        repeat(7) { completedSession(next) }
        assertEquals(listOf(4 to first(), 11 to reask()), asked)
    }

    @Test fun theAppMayAskOnTheFirstLaunch() {
        val c = cloud(storage(), firstLaunch = true)
        assertEquals(ConsentState.Variant.FIRST, c.sharing.askNow())
        assertEquals(1, c.sharing.state.asks)
    }

    /** An install from before 3.1.5 has no marker of a start: its consent state or its registration says it started. */
    @Test fun anInstallFromBeforeIsPastItsFirstLaunch() {
        for (left in listOf("a consent state", "a registration")) {
            asked.clear()
            val s = storage()
            if (left == "a consent state") {
                File(s, "sharing").mkdirs()
                File(s, "sharing/state.json").writeText("""{"v":1,"consent":"unknown","asks":0,"lastAskSession":0,"sessions":2,"thanked":false}""")
            } else {
                File(s, "cloud").mkdirs()
                File(s, "cloud/installed").writeText("")
            }
            val c = cloud(s, firstLaunch = true)
            completedSession(c)
            assertEquals(left, 1, asked.size)
            assertEquals(left, ConsentState.Variant.FIRST, (asked.single().second as Sharing.SheetRequest.Consent).variant)
        }
    }
}
