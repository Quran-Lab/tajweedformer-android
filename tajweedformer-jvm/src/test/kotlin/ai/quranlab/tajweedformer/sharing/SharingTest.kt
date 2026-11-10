package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.Day
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.StubQlab
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The Swift SDK's SharingTests on the JVM: whole sessions through the observer hooks the recognizer
 * calls, the outbox against [StubQlab] (qlab-api and R2 on 127.0.0.1), plus the consent state machine,
 * the device-key deletion, and since 2.0.1 the adaptive bitrate, the app's own wording and own UI.
 */
class SharingTest {
    private lateinit var stub: StubQlab
    private lateinit var storage: File

    /** The assumption comes first: without the host Opus library (see README "Local tests") every test here
     *  is SKIPPED before anything is set up, and [down] has nothing to clean. */
    @Before fun up() {
        OggTestKit.requireOpus()
        stub = StubQlab()
        storage = Files.createTempDirectory("tj-share").toFile()
    }

    /** JUnit runs @After even when @Before stopped on an assumption: clean only what was set up. */
    @After fun down() {
        if (::storage.isInitialized) storage.deleteRecursively()
        if (!::stub.isInitialized) return
        try {
            assertTrue(stub.seen.none { it.headers.containsKey("origin") })
        } finally {
            stub.close()
        }
    }

    /** A configured cloud with the config fetched; [consent]: the learner said yes. */
    private fun cloud(consent: Boolean = true, contributions: Boolean = true, at: File = storage): QuranLabCloud {
        val c = stub.cloud(at, CloudOptions(stub.key, contributions = contributions, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)))
        c.refreshConfig()
        if (consent && !c.sharing.isOn) c.sharing.accept()
        return c
    }

    /** A session over [seconds] of audio at [rate] through the observer hooks; [end]: finish, cancel or open. */
    private fun session(c: QuranLabCloud, seconds: Double, rate: Int = 48_000, end: String = "finish", feedback: Boolean = true) {
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", "balanced", "general", sampleRate = rate, inputRoute = "built_in",
            decodeBackend = "mock"))
        feed(c, seconds, rate)
        if (end != "open") endSession(c, end == "finish", feedback)
    }

    private var seed = 1L

    /** [seconds] more audio into the open session, in 100 ms pieces. */
    private fun feed(c: QuranLabCloud, seconds: Double, rate: Int = 48_000) {
        val n = Math.round(rate * seconds).toInt()
        var at = 0
        while (at < n) {
            val k = minOf(rate / 10, n - at)
            c.onAudio(OggTestKit.noise(k, seed = seed++), 0, k, rate)
            at += k
        }
    }

    private fun endSession(c: QuranLabCloud, completed: Boolean, feedback: Boolean = true) {
        c.onSessionEnd(TajweedObserver.SessionEnd(0.0, completed, feedbackShown = feedback))
        c.sharing.lastCapture?.await()
    }

    private fun settle(c: QuranLabCloud, n: Int = 1, done: (Contribution) -> Boolean = { it.status == Contribution.Status.UPLOADED }): List<Contribution> {
        repeat(20) {
            c.sharing.outbox.drain(ignoringBackoff = true)
            val items = c.sharing.items
            if (items.size == n && items.all(done)) return items
            Thread.sleep(20)
        }
        throw AssertionError("outbox did not settle: ${c.sharing.items}")
    }

    private fun sig(url: String) = Regex("X-Amz-Signature=([^&]+)").find(url)?.groupValues?.get(1)

    @Test fun offByDefaultNothingIsCaptured() {
        val c = cloud(consent = false, contributions = false)
        session(c, 12.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue(c.sharing.items.isEmpty())
        assertTrue(stub.starts().isEmpty() && stub.puts.isEmpty())
        assertEquals(0, File(storage, "outbox").listFiles()!!.size)
        // the app turned it on but the learner was not asked yet: still nothing
        val d = cloud(consent = false, at = Files.createTempDirectory("tj-s2").toFile())
        session(d, 3.0)
        assertTrue(d.sharing.items.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun aSharedSessionStreamsItsSegmentsToR2() {
        val c = cloud()
        session(c, 25.0)
        val item = settle(c)[0]
        assertEquals(2, item.segments)
        assertEquals(25_000, item.durationMs)
        assertEquals(true, item.completed)
        val start = stub.starts().single()
        assertTrue(start.headers["authorization"]!!.startsWith("Bearer qlt_v1."))
        assertTrue("an attested (Play Integrity) token", start.headers["authorization"]!!.removePrefix("Bearer ") in stub.attestedTokens)
        assertEquals(item.id, start.headers["x-qlab-id"])
        val meta = start.json!!
        assertEquals(setOf("format", "audio", "decode", "consent_version", "contributor", "contributor_key", "recovery_hash", "day", "device_class", "os_major"), meta.keys)
        assertEquals("share-2026-11-09", meta["consent_version"])
        assertFalse("the deck's own words: no wording hash", meta.containsKey("consent_text_sha256"))
        assertEquals("both segments at the top rate on a good network", listOf(192_000, 192_000), item.segmentBitrates)
        assertEquals(c.sharing.contributor.id, meta["contributor"])
        assertEquals(Day.of(System.currentTimeMillis()), meta["day"])
        assertEquals(15.0, meta["os_major"])
        assertEquals(mapOf("codec" to "opus", "container" to "ogg", "sample_rate" to 48_000.0, "channels" to 1.0, "input_sample_rate" to 48_000.0, "input_route" to "built_in"), meta["audio"])
        val decode = meta["decode"] as Map<String, Any?>
        assertEquals(setOf("model_revision", "backend", "weights_sha256", "sdk_version"), decode.keys)
        assertEquals("mock", decode["backend"])
        assertEquals(87, (meta["contributor_key"] as String).length)
        assertEquals(64, (meta["recovery_hash"] as String).length)

        val puts = stub.puts
        assertEquals((1..2).map { "/r2/qlab-contributions/v1/${item.id}/seg-0000$it.opus" }, puts.map { it.path })
        assertEquals("each segment on its own URL from the start's batch", listOf("b1-1", "b1-2"), puts.map { sig(it.path + "?" + it.query) })
        for (p in puts) {
            assertEquals("audio/ogg", p.headers["content-type"])
            assertNull("a plain PUT: the URL carries the signature", p.headers["authorization"])
            assertFalse(p.headers.keys.any { it.startsWith("x-amz-") || it.startsWith("x-qlab") })
        }
        assertEquals("the Worker is called once", 1, stub.seen.count { it.path.startsWith("/v1/uploads") })
        val segs = stub.segments(item.id)
        val pages = segs.map { OggTestKit.pages(it) }
        assertEquals(10, pages[0].count { it.flags and 0x02 == 0 && String(it.packets[0], 0, minOf(8, it.packets[0].size)) != "OpusTags" })
        assertEquals("the last page ends the stream", 0x04, pages.last().last().flags)
        assertEquals(25L * 48_000, pages.last().last().granule - 312)
        assertEquals(25 * 48_000, OggTestKit.decode(segs.reduce { a, b -> a + b }).size)
        val left = File(File(storage, "outbox"), item.id).list()!!
        assertFalse("the audio goes once uploaded", left.any { it.endsWith(".opus") })
    }

    @Test fun a441kHzSessionRecordsItsInputRate() {
        val c = cloud()
        session(c, 12.0, rate = 44_100)
        val item = settle(c)[0]
        assertEquals(12_000, item.durationMs)
        @Suppress("UNCHECKED_CAST")
        val audio = stub.starts().single().json!!["audio"] as Map<String, Any?>
        assertEquals(48_000.0, audio["sample_rate"])
        assertEquals(44_100.0, audio["input_sample_rate"])
        val segs = stub.segments(item.id)
        val head = OggTestKit.pages(segs[0])[0].packets[0]
        assertEquals(44_100, java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(12 * 48_000, OggTestKit.decode(segs.reduce { a, b -> a + b }).size)
    }

    @Test fun shortCancelledSessionsStayLocal() {
        val c = cloud()
        session(c, 1.5, end = "cancel")
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue(stub.starts().isEmpty() && stub.puts.isEmpty())
        assertEquals(0, File(storage, "outbox").listFiles()!!.size)
        session(c, 2.5, rate = 16_000, end = "cancel")
        val item = settle(c)[0]
        assertEquals(false, item.completed)
        assertEquals(2_500, item.durationMs)
        assertEquals(1, stub.puts.size)
    }

    @Test fun theConfigFlagSwitchesSharingOff() {
        stub.flags = stub.flags + ("contributions" to false)
        val c = cloud()
        assertFalse(c.sharing.available)
        session(c, 3.0)
        assertTrue(c.sharing.items.isEmpty())
        // and an upload tier of off for android
        stub.flags = mapOf("contributions" to true, "upload_trust" to mapOf("android" to "off"))
        val d = cloud(at = Files.createTempDirectory("tj-off").toFile())
        assertFalse(d.sharing.available)
    }

    @Test fun withoutPlayIntegrityTheAttestedTierDoesNotShare() {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)), integrity = null)
        c.refreshConfig()
        assertFalse(c.sharing.available)
        // the capped tier takes unattested tokens
        stub.flags = mapOf("contributions" to true, "upload_trust" to mapOf("android" to "capped"))
        c.refreshConfig()
        assertTrue(c.sharing.available)
        c.sharing.accept()
        session(c, 3.0)
        settle(c)
        assertFalse(stub.starts().single().headers["authorization"]!!.removePrefix("Bearer ") in stub.attestedTokens)
    }

    @Test fun anExpiredUrlIsRenewed() {
        val c = cloud()
        var refused = false
        val expired = "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>"
        session(c, 1.0)
        val id = c.sharing.items.single().id
        stub.overrides["/r2/qlab-contributions/v1/$id/seg-00001.opus"] = { _, _ ->
            if (!refused) { refused = true; StubQlab.Reply(403, expired.toByteArray()) } else null
        }
        val item = settle(c)[0]
        assertEquals("fresh URLs from the refused segment", listOf(1), stub.urlCalls(item.id))
        assertEquals(c.sharing.contributor.id, stub.seen("/v1/uploads/${item.id}/urls").single().headers["x-qlab-contributor"])
        assertEquals(listOf("b1-1", "b2-1"), stub.puts.map { sig(it.query) })
        assertEquals(1, stub.segments(item.id).size)
    }

    @Test fun theNextBatchComesWhenFiveRemain() {
        stub.batch = 6
        val c = cloud()
        session(c, 12.0, end = "open")
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        val id = c.sharing.items.single().id
        assertEquals("segment 1 used, 5 left: the batch from 7", listOf(7), stub.urlCalls(id))
        endSession(c, completed = true)
        val done = settle(c)[0]
        assertEquals(2, done.segments)
        assertEquals(listOf(7), stub.urlCalls(id))
        assertEquals(listOf("b1-1", "b1-2"), stub.puts.map { sig(it.query) })
        assertEquals(1, stub.starts().size)
    }

    @Test fun aSegmentWithoutAUrlAsksFromItsNumber() {
        stub.batch = 1
        val c = cloud()
        stub.down = true
        session(c, 25.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        stub.down = false
        val item = settle(c)[0]
        assertEquals(2, item.segments)
        assertEquals(listOf(2), stub.urlCalls(item.id))
        assertEquals(listOf("b1-1", "b2-2"), stub.puts.map { sig(it.query) })
        assertEquals(2, stub.segments(item.id).size)
    }

    @Test fun offlineSegmentsWaitAndGoInOrderAcrossARelaunch() {
        val c = cloud()
        stub.down = true
        session(c, 35.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        val waiting = c.sharing.items.single()
        assertEquals(Contribution.Status.QUEUED, waiting.status)
        assertEquals(3, waiting.segments)
        assertNotNull(waiting.lastError)
        stub.down = false
        // a new process: everything is on disk
        val second = cloud()
        val done = settle(second)[0]
        assertEquals(waiting.id, done.id)
        assertEquals((1..3).map { "/r2/qlab-contributions/v1/${waiting.id}/seg-0000$it.opus" }, stub.puts.map { it.path })
        val before = stub.puts.size
        cloud().sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("an uploaded session is never sent again", before, stub.puts.size)
    }

    /** Killed 13 s in (the second segment half written, a page torn): the next launch cuts the torn page and uploads. */
    @Test fun aKilledSessionIsUploadedOnTheNextLaunch() {
        val c = cloud()
        stub.down = true
        session(c, 13.0, end = "open")
        // the encoder caught up; copy the disk as the killed app left it
        c.sharing.syncCapture()
        val killed = Files.createTempDirectory("tj-killed").toFile()
        storage.copyRecursively(killed)
        endSession(c, completed = false)
        val folder = File(killed, "outbox").listFiles()!!.single { it.name.length == 32 }
        val open = File(folder, "seg-00002.opus")
        val whole = open.readBytes()
        assertTrue(whole.isNotEmpty())
        open.appendBytes("OggS".toByteArray() + ByteArray(100) { 7 })
        ShareCapture.ACTIVE.clear()
        stub.down = false
        val relaunched = cloud(at = killed)
        val item = settle(relaunched) { it.id == folder.name && it.status == Contribution.Status.UPLOADED }[0]
        assertEquals(false, item.completed)
        assertEquals(2, item.segments)
        val segs = stub.segments(item.id)
        assertEquals(2, segs.size)
        assertArrayEquals("the torn page is cut off, the whole pages stay", whole, segs[1])
        val pages = segs.map { OggTestKit.pages(it) }
        assertNotEquals("no end of stream: the session did not end", 0x04, pages.last().last().flags)
        assertEquals(((pages.last().last().granule - 312) / 48).toInt(), item.durationMs)
        assertEquals("the recovered segment keeps the bitrate it was opened at", listOf(192_000, 192_000), item.segmentBitrates)
        assertEquals(1, stub.starts().size)
        killed.deleteRecursively()
    }

    @Test fun turningSharingOffCancelsQueuedUploads() {
        val c = cloud()
        stub.down = true
        session(c, 1.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(listOf(Contribution.Status.QUEUED), c.sharing.items.map { it.status })
        c.sharing.setOn(false)
        assertFalse(c.sharing.isOn)
        assertTrue(c.sharing.items.isEmpty())
        session(c, 1.0)
        assertTrue("a session after sharing went off is not shared", c.sharing.items.isEmpty())
        assertEquals(ConsentState.Consent.FINAL, c.sharing.state.consent)
    }

    @Test fun refusalsFailForGood() {
        val c = cloud()
        stub.overrides["/v1/uploads"] = { _, _ -> StubQlab.Reply.json(400, mapOf("error" to "invalid_body", "message" to "body.format: out of range")) }
        session(c, 1.0)
        val item = settle(c) { it.status == Contribution.Status.FAILED }[0]
        assertEquals("HTTP 400 invalid_body", item.lastError)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("a refused item is not retried", 1, stub.starts().size)
        assertTrue(stub.puts.isEmpty())
        assertTrue(c.issues.pending().any { it["code"] == "bad_request:uploads" })
    }

    @Test fun contributorIdIsStableUntilReset() {
        val c = cloud()
        val id = c.sharing.contributor.id
        assertTrue(id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(id, cloud().sharing.contributor.id)
        c.sharing.contributor.reset()
        assertNotEquals(id, c.sharing.contributor.id)
    }

    @Test fun theSdkAsksAtTheRightMoment() {
        val c = cloud(consent = false)
        val asked = ArrayList<Sharing.SheetRequest>()
        c.sharing.prompts = { asked.add(it) }
        // a session without feedback, a cancelled one: no ask
        session(c, 0.2, feedback = false)
        session(c, 0.2, end = "cancel")
        assertTrue(asked.isEmpty())
        session(c, 0.2)
        assertEquals(listOf<Sharing.SheetRequest>(Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, false)), asked)
        c.sharing.decline()
        // re-asks: at least 7 sessions apart, at most twice
        val at = ArrayList<Int>()
        for (i in 1..40) {
            val before = asked.size
            session(c, 0.1)
            if (asked.size > before) { at.add(i); c.sharing.decline() }
        }
        assertEquals(listOf(7, 14), at)
        assertEquals(ConsentState.Consent.FINAL, c.sharing.state.consent)
        assertTrue(asked.drop(1).all { it == Sharing.SheetRequest.Consent(ConsentState.Variant.REASK, false) })
        // the learner opens the setting
        c.sharing.setOn(true)
        assertTrue(c.sharing.isOn)
    }

    @Test fun guardianVariantAndThanksOnce() {
        val c = stub.cloud(storage, CloudOptions(stub.key, contributions = true, audience = CloudOptions.Audience.CHILDREN, apiUrl = stub.base, trustedKeys = listOf(stub.publicKey)))
        c.refreshConfig()
        val asked = ArrayList<Sharing.SheetRequest>()
        c.sharing.prompts = { asked.add(it) }
        session(c, 0.2)
        assertEquals(Sharing.SheetRequest.Consent(ConsentState.Variant.FIRST, true), asked.single())
        c.sharing.accept()
        assertTrue(c.sharing.state.guardian)
        session(c, 3.0)
        settle(c)
        session(c, 3.0)
        settle(c, 2)
        assertEquals("the thank-you once", 1, asked.count { it == Sharing.SheetRequest.Thanks })
    }

    @Test fun anAskNowFromTheAppIsCountedAndRespected() {
        val c = cloud(consent = false)
        assertEquals(ConsentState.Variant.FIRST, c.sharing.askNow())
        c.sharing.decline()
        assertEquals(ConsentState.Variant.REASK, c.sharing.askNow())
        c.sharing.decline()
        assertEquals(ConsentState.Variant.REASK, c.sharing.askNow())
        c.sharing.decline()
        assertNull("never again", c.sharing.askNow())
        // 2.0.1: the backend decides whether the sheet is offered; the app's option neither vetoes nor forces it
        assertEquals(ConsentState.Variant.FIRST, cloud(consent = false, contributions = false, at = Files.createTempDirectory("tj-n").toFile()).sharing.askNow())
        stub.flags = stub.flags + ("contributions" to false)
        assertNull(cloud(consent = false, contributions = true, at = Files.createTempDirectory("tj-o").toFile()).sharing.askNow())
    }

    @Test fun offeredIsNotConsentAndAGrantToAnOlderDeckKeepsSharingUnderIt() {
        // the config offers sharing, the learner was never asked: no capture, nothing sent
        val c = cloud(consent = false, contributions = false)
        assertTrue(c.sharing.available)
        assertFalse(c.sharing.isOn)
        session(c, 12.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue(c.sharing.items.isEmpty() && stub.starts().isEmpty())
        // a 2.0.1 grant to an earlier deck, the 2026-11-09.1 deck in force: no ask, shared under the version agreed to
        c.sharing.state.grant("share-2026-11-01", byGuardian = false)
        val asked = ArrayList<Sharing.SheetRequest>()
        c.sharing.prompts = { asked.add(it) }
        assertTrue(c.sharing.isOn)
        assertNull(c.sharing.askNow())
        session(c, 12.0)
        repeat(20) { session(c, 0.1) }
        settle(c, 21) { it.status == Contribution.Status.UPLOADED }
        assertTrue("never asked: $asked", asked.none { it is Sharing.SheetRequest.Consent })
        assertTrue(stub.starts().all { it.json!!["consent_version"] == "share-2026-11-01" && !it.json!!.containsKey("consent_text_sha256") })
        assertEquals("share-2026-11-01", c.sharing.state.consentVersion)
    }

    /** Writes [json] as the stored consent state, as an earlier SDK left it on disk. */
    private fun storedState(json: String, at: File = storage) {
        File(at, "sharing").mkdirs()
        File(File(at, "sharing"), "state.json").writeText(json)
    }

    /** The exact file SDK 2.0.0 wrote (its key order and fields, `updateAsked` included) for a learner who said yes. */
    private fun state200(version: String?, asks: Int = 1, consent: String = "granted") =
        "{\"v\":1,\"consent\":\"$consent\",\"asks\":$asks,\"lastAskSession\":3,\"sessions\":5,\"thanked\":true,\"decidedAt\":1762510000," +
            "\"consentVersion\":${version?.let { "\"$it\"" } ?: "null"},\"guardian\":false,\"updateAsked\":null}"

    /** 2.0.0 to 2.0.1: a stored yes keeps sharing under the version 2.0.0 recorded, with no sheet before, during or after the sessions. */
    @Test fun anUpgradeFrom200KeepsTheGrantAndItsVersionWithoutAsking() {
        for ((stored, expected) in listOf("share-2026-11-07" to "share-2026-11-07", null to ConsentState.LEGACY_CONSENT_VERSION)) {
            val at = Files.createTempDirectory("tj-up").toFile()
            storedState(state200(stored), at)
            val c = cloud(consent = false, at = at)
            val asked = ArrayList<Sharing.SheetRequest>()
            c.sharing.prompts = { asked.add(it) }
            assertEquals("share-2026-11-09", c.sharing.copy.consentVersion)
            assertTrue(c.sharing.isOn && c.sharing.active)
            assertEquals(expected, c.sharing.state.consentVersion)
            assertTrue(asked.isEmpty())
            val before = stub.starts().size
            session(c, 3.0)
            repeat(30) { session(c, 0.1) }
            settle(c, 31)
            assertTrue("no sheet: $asked", asked.isEmpty())
            assertNull(c.sharing.askNow())
            val starts = stub.starts().drop(before)
            assertEquals(31, starts.size)
            c.background()
            assertTrue("no ask counted", c.telemetry.pendingReports().map { stub.parse(it.readBytes()) }.none { it.containsKey("share_asked") })
            assertTrue(starts.all { it.json!!["consent_version"] == expected && !it.json!!.containsKey("consent_text_sha256") })
            // the next launch reads the file 2.0.1 wrote back: the same
            val again = cloud(consent = false, at = at)
            assertTrue(again.sharing.isOn)
            assertEquals(expected, again.sharing.state.consentVersion)
            assertFalse(File(File(at, "sharing"), "state.json").readText().contains("updateAsked"))
            at.deleteRecursively()
        }
        // a declined or off 2.0.0 state stays so: nothing captured
        for (consent in listOf("declined", "final")) {
            val at = Files.createTempDirectory("tj-up").toFile()
            storedState(state200(null, asks = 3, consent = consent), at)
            val c = cloud(consent = false, at = at)
            assertFalse(c.sharing.isOn)
            assertNull(c.sharing.state.consentVersion)
            at.deleteRecursively()
        }
    }

    /** 2.0.0's ask count carries over: 2 sheets shown and a "Not now" leave one more ask at most, with the usual spacing. */
    @Test fun anUpgradeFrom200CarriesItsAskCount() {
        storedState("{\"v\":1,\"consent\":\"declined\",\"asks\":2,\"lastAskSession\":10,\"sessions\":10,\"thanked\":false,\"decidedAt\":1762510000," +
            "\"consentVersion\":null,\"guardian\":false,\"updateAsked\":null}")
        val c = cloud(consent = false)
        val asked = ArrayList<Pair<Int, ConsentState.Variant>>()
        var n = 0
        c.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) { asked.add(n to it.variant); c.sharing.decline() } }
        while (n < 40) { n++; session(c, 0.1) }
        assertEquals("the third and last showing, 7 sessions after 2.0.0's second", listOf(7 to ConsentState.Variant.REASK), asked)
        assertEquals(ConsentState.Consent.FINAL, c.sharing.state.consent)
        assertNull(c.sharing.askNow())
        assertFalse(c.sharing.isOn)
    }

    /** Three "Not now" answers: no fourth sheet, across many sessions and a restart; the settings switch still turns it on. */
    @Test fun aThirdNotNowIsTheLastAskAcrossSessionsAndARestart() {
        val c = cloud(consent = false)
        val asked = ArrayList<ConsentState.Variant>()
        c.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) { asked.add(it.variant); c.sharing.decline() } }
        repeat(60) { session(c, 0.1) }
        assertEquals(listOf(ConsentState.Variant.FIRST, ConsentState.Variant.REASK, ConsentState.Variant.REASK), asked)
        assertEquals(3, c.sharing.state.asks)
        assertNull(c.sharing.askNow())
        // a restart reads the count back
        val again = cloud(consent = false)
        again.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) asked.add(it.variant) }
        repeat(30) { session(again, 0.1) }
        assertNull(again.sharing.askNow())
        assertEquals(3, asked.size)
        assertTrue(again.sharing.items.isEmpty() && stub.starts().isEmpty())
        // only the settings switch turns it on, under the version in force then
        again.sharing.setWording(mapOf("sheet.what" to "Your recitation audio and the app's version. Nothing else."))
        again.sharing.setOn(true)
        assertTrue(again.sharing.isOn)
        session(again, 3.0)
        settle(again)
        val j = stub.starts().single().json!!
        assertEquals("custom-df8c4e50965be0005aaca5d6", j["consent_version"])
        assertEquals("df8c4e50965be0005aaca5d6ad8a2692efac1367c91bab97a50cf985d3c294d4", j["consent_text_sha256"])
        assertEquals(3, asked.size)
    }

    /** Sharing turned off in the settings, after a yes or before any ask: never asked again; on again records the version in force. */
    @Test fun turningSharingOffEndsAsking() {
        for (yesFirst in listOf(true, false)) {
            val at = Files.createTempDirectory("tj-off").toFile()
            val c = cloud(consent = yesFirst, at = at)
            val asked = ArrayList<Sharing.SheetRequest>()
            c.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) asked.add(it) }
            c.sharing.setOn(false)
            assertFalse(c.sharing.isOn)
            repeat(30) { session(c, 0.1) }
            assertNull(c.sharing.askNow())
            val again = cloud(consent = false, at = at)
            again.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) asked.add(it) }
            repeat(10) { session(again, 0.1) }
            assertNull(again.sharing.askNow())
            assertTrue(asked.isEmpty())
            again.sharing.setOn(true)
            assertEquals("share-2026-11-09", again.sharing.state.consentVersion)
            at.deleteRecursively()
        }
        // the switch turned on while already on keeps the version agreed to
        storedState(state200("share-2026-11-07"))
        val c = cloud(consent = false)
        c.sharing.setOn(true)
        assertEquals("share-2026-11-07", c.sharing.state.consentVersion)
        session(c, 3.0)
        settle(c)
        assertEquals("share-2026-11-07", stub.starts().single().json!!["consent_version"])
    }

    /** Withdrawal and deletion under the version a 2.0.0 grant recorded. */
    @Test fun withdrawalAndDeletionUnderAnOldVersion() {
        storedState(state200("share-2026-11-07"))
        val c = cloud(consent = false)
        session(c, 3.0)
        settle(c)
        // the switch off: the unsent queue goes at once
        stub.down = true
        session(c, 1.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(2, c.sharing.items.size)
        c.sharing.setOn(false)
        assertFalse(c.sharing.isOn)
        assertEquals(listOf(Contribution.Status.UPLOADED), c.sharing.items.map { it.status })
        stub.down = false
        // delete what I shared, from a fresh 2.0.0 grant
        val at = Files.createTempDirectory("tj-del").toFile()
        storedState(state200("share-2026-11-07"), at)
        val d = cloud(consent = false, at = at)
        session(d, 3.0)
        settle(d)
        assertEquals("share-2026-11-07", stub.starts().last().json!!["consent_version"])
        assertEquals(Sharing.Deletion.ACCEPTED, d.sharing.deleteEverything())
        assertEquals(1, stub.deletions)
        assertFalse(d.sharing.isOn)
        assertTrue(d.sharing.items.isEmpty())
        val asked = ArrayList<Sharing.SheetRequest>()
        d.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) asked.add(it) }
        repeat(10) { session(d, 0.1) }
        assertTrue("not asked after a deletion", asked.isEmpty())
        at.deleteRecursively()
    }

    @Test fun deleteWhatIShared() {
        val c = cloud()
        session(c, 12.0)
        val item = settle(c)[0]
        val oldId = c.sharing.contributor.id
        assertEquals(2, stub.segments(item.id).size)
        val code = c.sharing.recoveryCode()!!
        assertEquals(26, ContributorKeys.normaliseRecoveryCode(code)!!.length)
        assertEquals("the server holds the hash of the code shown", stub.starts().single().json!!["recovery_hash"],
            ContributorKeys.recoveryHash(ContributorKeys.normaliseRecoveryCode(code)!!))
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        // challenge and signed deletion, no app credential on either
        val ch = stub.seen("/v1/contributions/challenge").single()
        val del = stub.seen("/v1/contributions/delete").single()
        for (s in listOf(ch, del)) assertTrue(s.headers["authorization"] == null && s.headers["x-qlab-key"] == null)
        assertEquals(mapOf("app" to "android:org.example.recite", "contributor" to oldId), ch.json)
        assertEquals(setOf("app", "contributor", "nonce", "timestamp", "action", "signature"), del.json!!.keys)
        assertEquals(1, stub.deletions)
        assertTrue("the server verified the device signature and purged", stub.segments(item.id).isEmpty())
        // afterwards: off, nothing local, a new contributor at the next share
        assertFalse(c.sharing.isOn)
        assertTrue(c.sharing.items.isEmpty())
        assertNotEquals(oldId, c.sharing.contributor.id)
        assertNull(c.sharing.recoveryCode())
        // sharing again binds a fresh key to the fresh id
        c.sharing.setOn(true)
        session(c, 3.0)
        settle(c)
        assertNotEquals(stub.starts()[0].json!!["contributor_key"], stub.starts()[1].json!!["contributor_key"])
    }

    @Test fun aForgedDeletionDoesNothing() {
        val c = cloud()
        session(c, 3.0)
        val item = settle(c)[0]
        // another device (or the developer's server) with the id but not the key: its signature does not match the bound key
        val other = FileContributorKeyStore(Files.createTempDirectory("tj-k").toFile())
        other.create("x")
        val nonce = c.api.deletionChallenge("android:org.example.recite", c.sharing.contributor.id)
        val msg = ContributorKeys.deletionMessage("android:org.example.recite", c.sharing.contributor.id, nonce, System.currentTimeMillis() / 1000)
        val r = c.api.deleteSigned(linkedMapOf("app" to "android:org.example.recite", "contributor" to c.sharing.contributor.id, "nonce" to nonce,
            "timestamp" to System.currentTimeMillis() / 1000, "action" to "delete-all",
            "signature" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(other.sign("x", msg.toByteArray()))))
        assertEquals("the same answer either way", 202, r.status)
        assertEquals(0, stub.deletions)
        assertEquals(1, stub.segments(item.id).size)
    }

    @Test fun theAppHasNoPublicWayToDeleteOrReadTheCode() {
        // Kotlin `internal`: compiled with a mangled name, absent from the public API the app is built against
        val public = Sharing::class.java.methods.filter { '$' !in it.name }
        assertFalse(public.any { it.name == "deleteEverything" || it.name == "recoveryCode" || it.name == "sign" })
        assertFalse(public.any { it.returnType == Contributor::class.java })
    }

    @Test fun requestCountsBySessionLength() {
        fun segments(seconds: Int): Int { var n = 0; var t = 0; while (t < seconds) { n++; t += SegmentPlan.seconds(n) }; return n }
        assertEquals(2, segments(20))
        assertEquals(7, segments(300))
        assertEquals(122, segments(7_200))
        fun calls(seconds: Int) = 1 + maxOf(0, segments(seconds) - 15 + 19) / 20
        assertEquals(1, calls(20))
        assertEquals(1, calls(300))
        assertEquals(7, calls(7_200))
    }

    @Test fun theSessionJsonIsSortedAndClosed() {
        val j = String(ShareCapture.sessionJson("v4.0+abc", "exact", "a".repeat(64), "share-2026-11-09", "0".repeat(32), "K", "h".repeat(64),
            "2026-11-09", "Pixel8", 15, 44_100, "bluetooth"))
        assertEquals("{\"audio\":{\"channels\":1,\"codec\":\"opus\",\"container\":\"ogg\",\"input_route\":\"bluetooth\",\"input_sample_rate\":44100,\"sample_rate\":48000}," +
            "\"consent_version\":\"share-2026-11-09\",\"contributor\":\"00000000000000000000000000000000\",\"contributor_key\":\"K\"," +
            "\"day\":\"2026-11-09\",\"decode\":{\"backend\":\"exact\",\"model_revision\":\"v4.0+abc\",\"sdk_version\":\"2.0.1\",\"weights_sha256\":\"${"a".repeat(64)}\"}," +
            "\"device_class\":\"Pixel8\",\"format\":1,\"os_major\":15,\"recovery_hash\":\"${"h".repeat(64)}\"}", j)
        // under the app's own wording: the hash next to the version, in key order
        val custom = String(ShareCapture.sessionJson(null, "exact", null, "custom-df8c4e50965be0005aaca5d6", "0".repeat(32), null, null,
            "2026-11-09", "Pixel8", 15, 48_000, "built_in", "df8c4e50965be0005aaca5d6ad8a2692efac1367c91bab97a50cf985d3c294d4"))
        assertTrue(custom, custom.contains("\"consent_text_sha256\":\"df8c4e50965be0005aaca5d6ad8a2692efac1367c91bab97a50cf985d3c294d4\"," +
            "\"consent_version\":\"custom-df8c4e50965be0005aaca5d6\""))
        assertTrue(MessageDigest.getInstance("SHA-256") != null)
    }

    /**
     * 2.0.1, through the real outbox and capture: an upload failure (a 5xx on the session start) lowers the
     * bitrate of the next segment the running capture opens; three segment uploads in a row raise it again.
     */
    @Test fun uploadFailuresAndSuccessesStepTheBitrateOfTheRunningCapture() {
        val c = cloud()
        val ladder = c.sharing.outbox.ladder
        stub.overrides["/v1/uploads"] = { _, _ -> StubQlab.Reply.error(500, "internal") }
        c.onSessionStart(TajweedObserver.SessionStart("v4.0+abc", "c16_l64", "cpu", "balanced", "general", sampleRate = 48_000, inputRoute = "built_in",
            decodeBackend = "mock"))
        feed(c, 12.0) // segment 1 closed at 192k, segment 2 open at 192k
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("a 5xx is a network failure: one rung down", 96_000, ladder.bitrate)
        stub.overrides.remove("/v1/uploads")
        feed(c, 20.0) // segment 2 closed at 30 s, segment 3 opens at 96k
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("two uploads in a row: not yet", 96_000, ladder.bitrate)
        feed(c, 40.0) // segment 3 closed at 70 s, segment 4 opens at 96k
        c.sharing.syncCapture()
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals("the third in a row with nothing waiting: one rung up", 192_000, ladder.bitrate)
        feed(c, 65.0) // segment 4 closed at 130 s, segment 5 opens at 192k (137 s in all)
        endSession(c, completed = true)
        val item = settle(c)[0]
        assertEquals(listOf(192_000, 192_000, 96_000, 96_000, 192_000), item.segmentBitrates)
        val markers = ShareFiles.markers(File(File(storage, "outbox"), item.id))
        assertEquals(item.segmentBitrates, markers.map { it.bitrate })
        val segs = stub.segments(item.id)
        assertEquals(5, segs.size)
        val whole = segs.reduce { a, b -> a + b }
        assertEquals("one valid stream across the rate changes", 137 * 48_000, OggTestKit.decode(whole).size)
        val perSecond = segs.mapIndexed { i, b -> b.size / SegmentPlan.seconds(i + 1).toDouble() }
        assertTrue("a 96k segment is smaller per second than a 192k one: $perSecond", perSecond[2] < perSecond[1] * 0.8)
    }

    /**
     * 2.0.1: the app's own wording has its own version for a new grant; a learner who agreed earlier is not
     * asked again and keeps the version (and hash) recorded at agreement; a learner after the change records the new one.
     */
    @Test fun ownWordingHasItsOwnVersionAndAnEarlierGrantKeepsItsOwn() {
        val c = cloud()
        val deck = c.sharing.copy.consentVersion
        assertEquals("share-2026-11-09", deck)
        assertNull(c.sharing.consentTextSha256)
        val asked = ArrayList<Sharing.SheetRequest>()
        c.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) asked.add(it) }
        session(c, 3.0)
        settle(c)
        assertFalse(stub.starts().single().json!!.containsKey("consent_text_sha256"))
        // refused wording changes nothing
        for (bad in listOf(mapOf("sheet.nope" to "x"), mapOf("sheet.what" to "  "), mapOf("sheet.what" to ""))) {
            try { c.sharing.setWording(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
            assertEquals(deck, c.sharing.copy.consentVersion)
            assertTrue(c.sharing.isOn)
        }
        val words = mapOf("sheet.what" to "Your recitation audio and the app's version. Nothing else.")
        c.sharing.setWording(words)
        val hash = "df8c4e50965be0005aaca5d6ad8a2692efac1367c91bab97a50cf985d3c294d4"
        assertEquals("custom-df8c4e50965be0005aaca5d6", c.sharing.copy.consentVersion)
        assertEquals(hash, c.sharing.consentTextSha256)
        assertEquals(words["sheet.what"], c.sharing.copy["sheet.what"])
        assertEquals("quranlab.consent-text/1\nbase share-2026-11-09\nsheet.what\t${words["sheet.what"]}\n", c.sharing.consentText)
        // the learner agreed to the deck's words: still on, never asked, the deck's version and no hash
        assertTrue(c.sharing.isOn)
        repeat(10) { session(c, 0.1) }
        assertNull(c.sharing.askNow())
        settle(c, 11)
        assertTrue(asked.isEmpty())
        assertTrue(stub.starts().all { it.json!!["consent_version"] == deck && !it.json!!.containsKey("consent_text_sha256") })

        // a learner who agrees under the app's words, then the app changes them
        val at = Files.createTempDirectory("tj-w").toFile()
        val d = cloud(consent = false, at = at)
        d.sharing.setWording(words)
        d.sharing.prompts = { if (it is Sharing.SheetRequest.Consent) { asked.add(it); d.sharing.accept() } }
        session(d, 0.2)
        assertEquals(1, asked.size)
        assertEquals("custom-df8c4e50965be0005aaca5d6", d.sharing.state.consentVersion)
        assertEquals(hash, d.sharing.state.consentTextSha256)
        val newer = mapOf("sheet.title" to "Help other learners")
        d.sharing.setWording(newer)
        val newVersion = d.sharing.copy.consentVersion
        assertNotEquals("custom-df8c4e50965be0005aaca5d6", newVersion)
        assertTrue(d.sharing.isOn)
        val before = stub.starts().size
        repeat(10) { session(d, 0.1) }
        assertNull(d.sharing.askNow())
        settle(d, 10)
        assertEquals(1, asked.size)
        val js = stub.starts().drop(before).map { it.json!! }
        assertEquals("the ten sessions after the grant (the one that ended with the ask was not captured)", 10, js.size)
        assertTrue(js.all { it["consent_version"] == "custom-df8c4e50965be0005aaca5d6" && it["consent_text_sha256"] == hash })
        // after a relaunch, before the app sets its words again: still on, the same version and hash
        val relaunched = cloud(consent = false, at = at)
        assertTrue(relaunched.sharing.isOn)
        assertEquals("custom-df8c4e50965be0005aaca5d6", relaunched.sharing.state.consentVersion)
        assertEquals(hash, relaunched.sharing.state.consentTextSha256)
        at.deleteRecursively()

        // a learner who agrees after the change records the new version and its hash
        val e = cloud(consent = false, at = Files.createTempDirectory("tj-w2").toFile())
        e.sharing.setWording(newer)
        assertEquals(ConsentState.Variant.FIRST, e.sharing.askNow())
        e.sharing.accept()
        assertEquals(newVersion, e.sharing.state.consentVersion)
        assertEquals(e.sharing.consentTextSha256, e.sharing.state.consentTextSha256)
        session(e, 3.0)
        settle(e)
        assertEquals(newVersion, stub.starts().last().json!!["consent_version"])
        assertEquals(e.sharing.consentTextSha256, stub.starts().last().json!!["consent_text_sha256"])
    }

    /** 2.0.1: under the app's own wording nothing is captured before the grant; withdrawal and deletion work as for the deck's. */
    @Test fun ownWordingNeverSharesBeforeTheGrantAndWithdrawalWorks() {
        val c = cloud(consent = false)
        c.sharing.setWording(mapOf("sheet.title" to "Help other learners", "sheet.share" to "Share", "sheet.not_now" to "Not now"))
        assertEquals("custom-a33668d3d103a75b669688e4", c.sharing.copy.consentVersion)
        assertEquals("Help other learners", c.sharing.copy["sheet.title"])
        session(c, 3.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertTrue("offered is not consent", c.sharing.items.isEmpty() && stub.starts().isEmpty())
        assertEquals(ConsentState.Variant.FIRST, c.sharing.askNow())
        c.sharing.accept()
        session(c, 3.0)
        settle(c)
        assertEquals("a33668d3d103a75b669688e45d3a674fc52a754ee197dd502e88e30baab0e0ab", stub.starts().single().json!!["consent_text_sha256"])
        // the settings switch off: queued uploads go at once
        stub.down = true
        session(c, 1.0)
        c.sharing.outbox.drain(ignoringBackoff = true)
        assertEquals(2, c.sharing.items.size)
        c.sharing.setOn(false)
        assertFalse(c.sharing.isOn)
        assertEquals(listOf(Contribution.Status.UPLOADED), c.sharing.items.map { it.status })
        stub.down = false
        // on again from the settings: under the words in force
        c.sharing.setOn(true)
        assertTrue(c.sharing.isOn)
        assertEquals("custom-a33668d3d103a75b669688e4", c.sharing.state.consentVersion)
        // delete what I shared, under a custom version
        assertEquals(Sharing.Deletion.ACCEPTED, c.sharing.deleteEverything())
        assertEquals(1, stub.deletions)
        assertFalse(c.sharing.isOn)
        assertTrue(c.sharing.items.isEmpty())
    }

    /** 2.0.1: the app's own UI is asked where the SDK counts an ask; asks and answers reach the telemetry as for the SDK's sheet. */
    @Suppress("UNCHECKED_CAST")
    @Test fun ownUiAsksAreCountedLikeTheSheetAndReachTelemetry() {
        val c = cloud(consent = false)
        val asked = ArrayList<Sharing.SheetRequest>()
        // the app's own UI: it shows the deck's strings and the learner taps "Not now"
        c.sharing.prompts = { r ->
            asked.add(r)
            if (r is Sharing.SheetRequest.Consent) {
                assertTrue(c.sharing.copy["sheet.what"].isNotEmpty())
                c.sharing.decline()
            }
        }
        session(c, 0.2)
        repeat(7) { session(c, 0.1) }
        assertEquals("the first ask and one re-ask 7 sessions later, as for the SDK's sheet",
            listOf(ConsentState.Variant.FIRST, ConsentState.Variant.REASK), asked.map { (it as Sharing.SheetRequest.Consent).variant })
        assertTrue(c.sharing.items.isEmpty())
        // at the app's own moment the learner taps "Share"
        c.sharing.prompts = null
        assertEquals(ConsentState.Variant.REASK, c.sharing.askNow())
        c.sharing.accept()
        // the settings switch is not a sheet answer
        c.sharing.setOn(false)
        c.sharing.setOn(true)
        c.background()
        val b = stub.parse(c.telemetry.pendingReports().single().readBytes())
        assertEquals(3.0, b["share_asked"])
        assertEquals(1.0, b["share_yes"])
        assertEquals(2.0, b["share_no"])
    }
}
