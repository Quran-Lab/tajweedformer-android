package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.cloud.ApiError
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.Day
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import java.io.File

/**
 * Recitation sharing (docs/platform-client.md). Every learner is asked and the choice is theirs (no app turns the
 * ask off; the app's option no longer does since 2.0.1; the signed config's `flags.contributions` is Quran Lab's
 * incident switch for every app at once): the SDK asks the learner at the right moment (the sheet at most 3 times per install, never again after a
 * yes), and while the learner agreed, every recitation session the SDK listens to is shared whole, in the
 * background, under the consent version the learner agreed to. Since 2.0.3 takes that follow one another
 * within a few seconds are one recording ([TakeJoin]): an app that stops and starts takes while the learner
 * keeps reciting no longer splits the recitation.
 *
 * What the app can do: let the SDK show its own sheet (the default, themed or not), or show its own UI:
 * set [prompts] (or `QuranLabAndroid.sheets`), read the strings from [copy] and report the learner's tap
 * with [accept] or [decline]; ask at its own moment ([askNow]); replace the deck's words with its own
 * ([setWording]: the learner then agrees to those words, under their own consent version); the settings
 * switch ([setOn]); read the state. Asks and answers are counted the same on every path. What it cannot:
 * read the recovery code, sign with the contributor key, or delete; the SDK's own settings sheet does
 * those, after the learner confirms.
 */
internal class Sharing internal constructor(private val cloud: QuranLabCloud) {
    private val env = cloud.env
    private val dir = File(env.storage, "sharing")
    internal val contributor = Contributor(dir, env.contributorKeys, env.secretBox)
    val state = ConsentState(File(dir, "state.json"), env.clock)

    /**
     * Whether this is the install's first launch: the first process of
     * the install, told at the Client's start by the absence of the marker of an earlier start ([STARTED], written
     * now). An install from before 3.1.5 has no marker: its consent state or its registration says it started before.
     * SDK 4.0 no longer uses it for the ask (SHARE_ASK_40 20.9: the first 24 hours cover it).
     */
    val firstLaunch: Boolean = firstStart()

    private fun firstStart(): Boolean {
        val cloudDir = File(env.storage, "cloud")
        val marker = File(cloudDir, STARTED)
        if (marker.exists()) return false
        val earlier = File(dir, "state.json").exists() || File(cloudDir, "installed").exists()
        try {
            cloudDir.mkdirs()
            marker.writeText("")
        } catch (_: Exception) {
            // not written: the next start is a first launch again, and the SDK does not ask then either
        }
        return !earlier
    }

    @Volatile private var ownWording: Map<String, String> = emptyMap()
    @Volatile private var wordedCopy: SharingCopy? = null

    /** For tests: a deck in place of the bundled one (a deck without the card's lines, as before 2026-11-15.1). */
    @Volatile internal var deckForTests: SharingCopy? = null

    /**
     * The copy in the app's language (the SDK bundles every shipped language), with the app's own wording
     * ([setWording]) applied. Its [SharingCopy.consentVersion] is the version a new grant is recorded under.
     */
    val copy: SharingCopy
        get() {
            val base = deckForTests ?: SharingCopy.forLanguage(env.locale())
            val w = ownWording
            if (w.isEmpty()) return base
            wordedCopy?.let { if (it.lang == base.lang && it.wording == w) return it }
            return base.withWording(w).also { wordedCopy = it }
        }

    /** The app's own wording in force (key to text), empty for the deck's. */
    val wording: Map<String, String> get() = ownWording

    /**
     * Replaces strings of the copy deck with the app's own (key to text; null or empty: the deck's own
     * again). Keys are the deck's ([ConsentWording.KEYS]: `sheet.title`, `sheet.what`, ...,
     * `impact.minutes.one`); an unknown key, or an empty or blank text, throws [IllegalArgumentException]
     * and nothing changes. A learner who agrees from now on agrees to these words: the consent version of
     * the grant becomes `custom-<24 hex>` ([consentTextSha256]) and every session of that grant carries the
     * hash. A learner who already agreed keeps sharing under the version recorded then and is not asked again.
     * Your text must say honestly what is sent, to whom, why, and how to stop and delete.
     */
    fun setWording(overrides: Map<String, String>?) {
        val w = ConsentWording.validate(overrides ?: emptyMap())
        ownWording = w
        wordedCopy = null
    }

    /** SHA-256 (64 lower-case hex) of the canonical text of the app's own wording, or null without one. Send it to Quran Lab with [consentText]. */
    val consentTextSha256: String? get() = copy.consentTextSha256

    /** The canonical text of the app's own wording ([ConsentWording.canonical]), or null without one. */
    val consentText: String? get() = copy.consentText

    /**
     * What the SDK wants shown; the UI module (or the app, for its own UI) registers here. Called off the main
     * thread. A [SheetRequest.Consent] is an ask the SDK counts when its sheet is on screen ([SheetRequest.Consent.shown]):
     * show it, call `shown()`, and report the learner's tap with [accept] or [decline] (a dismissed sheet is a decline).
     */
    @Volatile var prompts: ((SheetRequest) -> Unit)? = null

    /** A sheet to show: the consent sheet ([variant]), the thank-you, or the impact line. */
    sealed class SheetRequest {
        /**
         * The consent sheet. Whoever shows it calls [shown] right before it goes on screen and shows it only when that
         * is true (SHARE_ASK_40 20.6: the ask is counted then, or it is stale and is not shown).
         */
        data class Consent(val variant: ConsentState.Variant) : SheetRequest() {
            /** What counts the ask (set by the SDK when it makes the request): whether the sheet may be shown. */
            @Volatile internal var onShown: () -> Boolean = { true }
            private val counted = java.util.concurrent.atomic.AtomicBoolean(false)
            @Volatile private var allowed = true
            /** The SDK takes the sheet back (it was never shown, or the app left): the UI closes it if it is up. */
            @Volatile var onDismiss: () -> Unit = {}

            internal constructor(variant: ConsentState.Variant, onShown: () -> Boolean) : this(variant) {
                this.onShown = onShown
            }

            /** The sheet goes on screen now: true when it may (counted once; later calls give the first answer). */
            fun shown(): Boolean {
                if (counted.compareAndSet(false, true)) allowed = try { onShown() } catch (_: Exception) { false }
                return allowed
            }
        }

        /**
         * SDK 4.0's re-ask (SHARE_ASK_40 20.6): a small non-blocking card at the bottom of the app's window with [line]
         * and two equal buttons, [openLabel] (opens the full sheet: [open]) and [notNowLabel] ([notNow]). No consent is
         * given on it. Whoever shows it calls [shown] right before it appears and shows it only when that is true; it
         * stays until the learner acts or the SDK takes it back ([onDismiss]: a take started, the app left).
         */
        class Card internal constructor(
            val line: String,
            val openLabel: String,
            val notNowLabel: String,
            private val onShown: () -> Boolean,
            private val onOpen: () -> Unit,
            private val onNotNow: () -> Unit,
        ) : SheetRequest() {
            private val counted = java.util.concurrent.atomic.AtomicBoolean(false)
            @Volatile private var allowed = true
            @Volatile var onDismiss: () -> Unit = {}

            fun shown(): Boolean {
                if (counted.compareAndSet(false, true)) allowed = try { onShown() } catch (_: Exception) { false }
                return allowed
            }

            /** "See how it helps": the full sheet comes (a [Consent] request) for the same ask. */
            fun open() = onOpen()

            /** "Not now", or the card swiped away. */
            fun notNow() = onNotNow()
        }
        object Thanks : SheetRequest()
        data class Impact(val minutes: Long) : SheetRequest()
    }

    val outbox: ContributionOutbox = ContributionOutbox(File(env.storage, "outbox"), cloud.api, { contributor.id }, Gate(), env.clock, onWork = {
        env.scheduler.scheduleUploads(cloud)
    })

    /**
     * The recording of the takes (SDK 2.0.3): it stays open after a take for the join window (the signed config's
     * `flags.share_join_ms`, 3000 ms when absent), so the next take in a row continues the same stream ([TakeJoin]).
     */
    internal val recorder = ShareRecorder(env.clock, { cloud.currentConfig?.flags?.shareJoinMs ?: TakeJoin.DEFAULT_WINDOW_MS },
        { active }, ::identity, ::open, TakeSequence(File(dir, "take-seq.json")))

    init {
        // an accepted deletion whose work on this device a kill cut short is finished before anything else happens
        if (state.deletionUnfinished) try { finishDeletion() } catch (_: Exception) {}
    }

    // ---- state ----

    /**
     * The sheet may be shown to this install: a signed config is in force, Quran Lab's incident switch
     * (`flags.contributions`, for every app at once) is not off, and this device may upload; since 2.0.1 the app's
     * `CloudOptions.contributions` changes nothing. Being asked is never consent: nothing is captured or sent before
     * the learner answers "Share" ([isOn]).
     */
    val available: Boolean get() = configAllows()

    /**
     * The learner agreed and has not turned it off. A yes persists across copy decks, the app's wording and
     * SDK upgrades; every shared session carries the version agreed to ([ConsentState.consentVersion]).
     */
    val isOn: Boolean get() = state.granted

    /** Sessions are being shared now. */
    val active: Boolean get() = available && isOn

    private fun configAllows(): Boolean {
        val f = cloud.currentConfig?.flags ?: return false
        if (f.contributions != true) return false
        return when (f.trust(env.app.platform)) {
            "capped" -> cloud.tokens != null
            "attested" -> cloud.tokens?.canAttest() == true
            else -> false
        }
    }

    // ---- the learner's answers ----

    /**
     * The app's own moment (SHARE_ASK_40 20.5 `ask_now`): the ask is shown now when one is due by the same rules (the
     * 60 s aside), never at launch (no completed session in this app session), during a take or before it is due.
     * Returns whether it is being shown.
     */
    fun askNow(): Boolean {
        val d = decide(ShareAsk.Event("ask_now", offered = available && prompts != null))
        if (d.action == "none") return false
        present()
        return true
    }

    /**
     * "Share my recitations" on a consent sheet, the SDK's or the app's own. Only from the learner's own tap. The same
     * sheet in every app: the yes takes no argument.
     */
    fun accept() {
        answered()
        val d = decide(ShareAsk.Event("answer", answer = "yes"))
        onScreen = null
        // a yes on a card that was not opened is not a yes (20.6, card_has_no_yes): the full sheet comes in the
        // card's place, for the same ask, and its answer is the learner's
        if (d.why == "card_has_no_yes") {
            openCard()
            return
        }
        val c = copy
        state.grant(c.consentVersion, c.consentTextSha256)
        cloud.telemetry.shareAnswer(true)
        env.scheduler.scheduleUploads(cloud)
    }

    /** "Not now", or the sheet or card dismissed: an answer to the ask in hand (none in hand: nothing changes). */
    fun decline() {
        answered()
        val d = decide(ShareAsk.Event("answer", answer = "not_now"))
        onScreen = null
        if (d.why == null) cloud.telemetry.shareAnswer(false)
    }

    /** "See how it helps" on the card: the full sheet for the same ask, without the re-ask line (20.6). */
    private fun openCard() {
        val d = decide(ShareAsk.Event("answer", answer = "open"))
        if (d.action != "sheet") return
        val show = prompts ?: return
        // already counted: the sheet opened from the card is the same ask
        val r = SheetRequest.Consent(ConsentState.Variant.FIRST) { true }
        lastRequest = r
        onScreen = r
        show(r)
    }

    /**
     * The settings switch (not a sheet answer). On records the version in force now (already on: the version
     * agreed to stays). Off ends the upload in progress and deletes the unsent queue, and the SDK never asks again;
     * it works whatever words were agreed to.
     */
    fun setOn(on: Boolean) {
        val c = copy
        // the learner's no is recorded first: from then on the uploader's own look before every request says no
        // too; then the uploader is stopped, its request ended, and the queue deleted
        decide(ShareAsk.Event("setting", on = on))
        takeBack()
        state.setFromSettings(on, c.consentVersion, textSha256 = c.consentTextSha256)
        if (!on) {
            stopAndClearQueue()
            // a no the device could not store is in force in this process all the same; it is tried again now that
            // the queue's files are gone, and at every later change of the state
            state.store()
        } else {
            env.scheduler.scheduleUploads(cloud)
        }
    }

    /** Every session in the outbox, oldest first. */
    val items: List<Contribution> get() = outbox.items

    /** Real numbers from the backend (`GET /v1/contributions/summary`), or null when the call fails or nothing was shared.
     *  Not asked while the contributor cannot be read (SDK 3.1.4). */
    fun summary(): Map<String, Any?>? {
        val who = contributor.standing() as? Contributor.Standing.Bound ?: return null
        return try {
            val t = cloud.tokens ?: return null
            cloud.api.contributionsSummary(t.token().token, who.id)
        } catch (_: CloudException) {
            null
        }
    }

    /** The quiet impact line, at most once a week: the learner's real shared minutes, or null. */
    fun impactLine(): String? {
        val f = File(dir, "impact-shown")
        val last = f.takeIf { it.isFile }?.readText()?.toLongOrNull() ?: 0
        if (env.clock() - last < 7 * 86_400_000L) return null
        @Suppress("UNCHECKED_CAST")
        val you = summary()?.get("you") as? Map<String, Any?> ?: return null
        val minutes = (you["minutes"] as? Double)?.toLong() ?: return null
        if (minutes < 1) return null
        dir.mkdirs()
        f.writeText(env.clock().toString())
        return copy.impactMinutes(minutes)
    }

    /**
     * Sharing goes off (SPEC 11.7): the uploader is stopped and its request in flight ended first; then the open
     * recording ends at once, in a take or waiting for the next one; then the unsent queue is deleted.
     */
    private fun stopAndClearQueue() = outbox.cancelQueued(first = { recorder.stop() })

    /** The signed config switched sharing off: pause; the local queue is kept 7 days (the outbox expires it). */
    internal fun configTurnedOff() = recorder.stop()

    /** The app went to the background: a recording waiting for the next take closes now (a running take's when it ends).
     *  An ask not yet on screen, or a card on screen, is taken back (SHARE_ASK_40 20.6 `withdrawn`). */
    internal fun background() {
        recorder.background()
        backgroundAt = env.clock()
        val a = state.askInHand
        if (a != null && (!a.shown || a.kind == "card")) {
            cancelSettle()
            decide(ShareAsk.Event("withdrawn"))
            takeBack()
        }
    }

    /** The app is in the foreground again: takes in a row join again; after 30 minutes or more away, a new app session. */
    internal fun foreground() {
        recorder.foreground()
        val away = backgroundAt
        backgroundAt = null
        if (away != null && env.clock() - away >= ShareAsk.NEW_APP_SESSION_MS) launched()
    }

    // ---- SDK 4.0: when to ask (SHARE_ASK_40, section 20) ----

    @Volatile private var backgroundAt: Long? = null

    /** The wall clock with the device's offset from UTC at that moment (20.5 `now`). */
    private fun now(): ShareAsk.Now {
        val ms = env.clock()
        return ShareAsk.Now(ms, java.util.TimeZone.getDefault().getOffset(ms) / 60_000)
    }

    /** [e] through the decision with the signed config's `share_ask` (20.3; absent: the defaults). */
    private fun decide(e: ShareAsk.Event): ShareAsk.Decision {
        val a = state.askInHand
        val d = state.decide(e, cloud.currentConfig?.shareAsk, now())
        // a card on screen that the decision took as "Not now" (a take started, the app left): share_no (20.8)
        if (a != null && a.shown && a.kind == "card" && state.askInHand == null && e.type in setOf("take_start", "withdrawn"))
            cloud.telemetry.shareAnswer(false)
        return d
    }

    /** A launch (20.2): the Client's start in this process, and the app back after 30 minutes or more away. */
    internal fun launched() {
        cancelSettle()
        takeBack()
        decide(ShareAsk.Event("launch"))
    }

    /** The settle of 20.6 waiting to present the ask in hand, and what it runs. */
    @Volatile private var settle: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var settleTask: Runnable? = null

    private fun cancelSettle() {
        settleTask = null
        settle?.cancel(false)
        settle = null
    }

    /** Tests on a fake clock: the settle's wait is over now (what it would run, runs here). */
    internal fun settleNow() {
        val t = settleTask ?: return
        settle?.cancel(false)
        settle = null
        t.run()
    }

    /** How long an ask waits for the learner's pause before it is presented (20.6; tests set less). */
    @Volatile internal var settleMs: Long = ShareAsk.SETTLE_MS

    private val timer: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tj-share-ask").apply { isDaemon = true } }
    }

    /** The request on screen, to take back when the decision says so. */
    @Volatile private var onScreen: SheetRequest? = null

    private fun takeBack() {
        val r = onScreen ?: return
        onScreen = null
        try {
            when (r) {
                is SheetRequest.Card -> r.onDismiss()
                is SheetRequest.Consent -> r.onDismiss()
                else -> {}
            }
        } catch (_: Exception) {
        }
    }

    /**
     * The ask in hand goes to the UI: the full sheet for the first ask; for a re-ask the card, or, when the deck has no
     * card strings (the decks before 2026-11-15.1; every bundled deck has them since), the 3.x re-ask sheet in its
     * place: the same ask, `shown`, then `answer open` at once, then the sheet's answer.
     */
    private fun present() {
        val a = state.askInHand ?: return
        if (a.shown) return
        // nobody to show it to: no UI registered, or the app is in the background
        val show = prompts?.takeIf { backgroundAt == null } ?: run { decide(ShareAsk.Event("withdrawn")); return }
        val c = copy
        val r: SheetRequest = if (a.kind == "sheet") consentRequest(ConsentState.Variant.FIRST)
        else if (c.hasCard) {
            SheetRequest.Card(c["card.line"], c["card.open"], c["card.not_now"],
                onShown = { shownNow() },
                onOpen = { answered(); openCard() },
                onNotNow = { decline() }).also { lastRequest = it }
        } else {
            SheetRequest.Consent(ConsentState.Variant.REASK) {
                shownNow() && decide(ShareAsk.Event("answer", answer = "open")).action == "sheet"
            }.also { lastRequest = it }
        }
        onScreen = r
        show(r)
    }

    /** The UI could not show [r] (no activity in front, a take open): the ask goes back, counted nowhere. */
    internal fun notShown(r: SheetRequest) {
        if (r !is SheetRequest.Consent && r !is SheetRequest.Card) return
        if (onScreen !== r) return
        onScreen = null
        if (state.askInHand?.shown == false) decide(ShareAsk.Event("withdrawn"))
    }

    /** `shown`: the ask in hand is on screen now (counted: `share_asked`), or stale (not shown). */
    private fun shownNow(): Boolean {
        val d = decide(ShareAsk.Event("shown"))
        if (d.action == "none") return false
        cloud.telemetry.shareAsked()
        return true
    }

    // ---- the SDK's own settings sheet only (internal: no app API deletes or reveals) ----

    /** The learner's recovery code, for the SDK's settings sheet (shown on request, never to the app): whenever it reads,
     *  also while the device key cannot be read (it deletes on the portal); null when there is none or it cannot be read. */
    internal fun recoveryCode(): String? = contributor.recoveryCode()

    /** Result of a deletion request. */
    enum class Deletion { ACCEPTED, NOTHING_SHARED, OFFLINE, REFUSED }

    /**
     * "Delete what I shared": a single-use nonce, the deletion message signed with
     * the device key, POST /v1/contributions/delete with no app credential. 202 for every
     * well-formed request; the server purges after answering. Then: sharing off, the local queue
     * gone, a new contributor id and key at the next share. Blocking.
     *
     * The uploader is stopped BEFORE the server is asked and stays stopped until it answered (SPEC 11.7): nothing
     * is uploaded under a contributor that is being deleted. The no is on the device before the server is asked too
     * (R5): a process that ends during the ask finds sharing off at its next launch and deletes the queue. A deletion
     * the server did not take changes nothing: the earlier state is written back, sharing is still on, nothing on
     * the device was deleted, and the queue goes on. When the device cannot store the no (a full disk), the server
     * is asked all the same; an accepted deletion then turns sharing off in this process, deletes the queue, and
     * stores the no as soon as the device can (first of all right after the queue's files are gone).
     */
    internal fun deleteEverything(): Deletion {
        // read once, before anything (SDK 3.1.4): while the contributor's id, device key or code cannot be read, or the
        // key or the code is missing beside the other, the server is not asked, the uploader is not stopped, and nothing
        // on the device changes
        val who = contributor.standing()
        if (who is Contributor.Standing.Lost) throw RecognizerException.StorageFailed("sharing: ${who.why}")
        val result = outbox.whileStopped {
            if (who !is Contributor.Standing.Bound) {
                state.afterDeletion()
                finishDeletion()
                Deletion.NOTHING_SHARED
            } else {
                val app = env.app.appId
                val id = who.id
                // on the device before the server is asked: what a process that ends in here leaves is a no. When it
                // cannot be stored, the server is asked all the same (SPEC 11.7)
                state.deletionAsked()
                try {
                    val nonce = cloud.api.deletionChallenge(app, id)
                    val ts = env.clock() / 1000
                    val msg = ContributorKeys.deletionMessage(app, id, nonce, ts)
                    val sig = try {
                        contributor.sign(id, msg.toByteArray(Charsets.UTF_8))
                    } catch (e: Exception) {
                        throw RecognizerException.StorageFailed("sharing: the device key did not sign", e)
                    }
                    cloud.api.deleteSigned(linkedMapOf("app" to app, "contributor" to id, "nonce" to nonce, "timestamp" to ts,
                        "action" to "delete-all", "signature" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig)))
                } catch (e: CloudException) {
                    state.deletionRefused()
                    return@whileStopped if (e.offline) Deletion.OFFLINE else Deletion.REFUSED
                } catch (e: Exception) {
                    state.deletionRefused()
                    throw e
                }
                // accepted: the no in force, in one write with the count and the mark of this deletion; then the device's part
                state.afterDeletion()
                finishDeletion()
                Deletion.ACCEPTED
            }
        }
        // not taken: the upload that was in flight was ended for nothing, so a pass is asked for again
        if (result == Deletion.OFFLINE || result == Deletion.REFUSED) env.scheduler.scheduleUploads(cloud)
        return result
    }

    /**
     * The device's part of an accepted deletion, after [ConsentState.afterDeletion] saved the no: the open recording,
     * the queue and every status, the take count, the contributor. A kill in here leaves the mark in the saved state,
     * and the next launch runs this again before anything else.
     */
    private fun finishDeletion() {
        clearEverything()
        if (contributor.hasKey) contributor.reset()
        state.deletionFinished()
    }

    /** After a deletion: the open recording, the unsent queue, and every uploaded recording's status. */
    private fun clearEverything() {
        stopAndClearQueue()
        // the count of recordings and the previous take's end go with what was shared (SPEC 11.3)
        recorder.forget()
        outbox.clearAll()
    }

    // ---- the recognizer's hooks (through QuranLabCloud) ----

    /** A take starts: it continues the recording the previous take left open, or starts a new one. */
    internal fun sessionStarted(e: TajweedObserver.SessionStart) {
        recorder.takeStarted(e)
        // 20.5 take_start: an ask not yet on screen is dropped (decided again at this take's end); a card on screen goes
        cancelSettle()
        val a = state.askInHand
        decide(ShareAsk.Event("take_start"))
        if (a != null && (!a.shown || a.kind == "card")) takeBack()
    }

    /**
     * What a recording is bound to: a take joins an open recording only under the same contributor, consent version
     * (and wording hash) and model revision. The backend and the input route may differ (the session JSON keeps the
     * first take's).
     */
    private fun identity(e: TajweedObserver.SessionStart): Any? {
        val version = state.consentVersion
        val textSha = if (version?.startsWith(ConsentWording.PREFIX) == true) state.consentTextSha256 else null
        // an id that cannot be read joins nothing (SDK 3.1.4)
        val id = try { contributor.id } catch (_: Exception) { return Any() }
        return listOf(id, version, textSha, e.revision)
    }

    /** A new recording for the take [e] (its session JSON is written once, with the first audio), or null. */
    private fun open(e: TajweedObserver.SessionStart, order: RecordingOrder): Recording? {
        // the count first, the consent after it: a no between the two is either seen by the look at the consent or
        // leaves this recording with an old count
        val count = state.changes
        if (!active) return null
        // while the contributor cannot be read, no recording begins (SDK 3.1.4); the session itself runs
        val binding = try { contributor.binding() } catch (_: Exception) { return null }
        val c = binding.id
        // the version the learner agreed to (not the copy's now), and for the app's own wording its hash
        val consentVersion = state.consentVersion ?: return null
        val textSha = if (consentVersion.startsWith(ConsentWording.PREFIX)) state.consentTextSha256 ?: return null else null
        val day = Day.of(env.clock())
        val dev = env.device
        return ShareCapture(outbox, { rate, route, flac ->
            ShareCapture.sessionJson(e.revision, e.decodeBackend, e.weightsSha256, consentVersion, c, binding.key, binding.recoveryHash,
                day, dev.deviceClass, dev.osMajor, rate, route, textSha,
                modelBuild = e.build, takeSeq = order.takeSeq, gapMsPrev = order.gapMsPrev, flac = flac, deviceModel = dev.model)
        }, e.inputRoute.takeIf { it in ROUTES } ?: env.inputRoute().takeIf { it in ROUTES } ?: "unknown", { active }, consent = count,
            // SDK 4.0 (SHARING_40.md 19.3): lossless at the capture rate once Quran Lab turned flags.share_flac on
            flac = { cloud.currentConfig?.flags?.shareFlac == true })
    }

    internal fun audio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) = recorder.audio(samples, offset, length, sampleRate)

    /**
     * Audio the app captures outside takes (SDK 3.0, SPEC 11.2): the last second before a take becomes the recording's
     * pre-roll, up to half a second after a take its tail. Dropped at once, with nothing kept, unless sharing is on
     * for this install and the learner said yes.
     */
    internal fun feed(samples: FloatArray, sampleRate: Int) = recorder.feed(samples, 0, samples.size, sampleRate)

    internal fun sessionEnded(e: TajweedObserver.SessionEnd) {
        // the recording stays open for the next take; the ask below does not close it
        recorder.takeEnded(e.completed)
        // 20.5 take_end: how the take ended and its speech (40 ms a speech frame); a take without audio has none. An ask
        // nobody can show (no UI registered) is not offered here
        val d = decide(ShareAsk.Event("take_end", end = if (e.withoutAudio) "cancelled" else e.end, speechMs = 40L * e.speechFrames,
            offered = available && prompts != null))
        if (d.action == "none") return
        // 20.6: presented once the moment settled, with no new take (a take start drops it, the app leaving takes it back)
        cancelSettle()
        var task: Runnable? = null
        task = Runnable {
            if (settleTask !== task) return@Runnable
            settleTask = null
            try { if (state.askInHand?.shown == false && !state.app.takeOpen) present() } catch (_: Exception) {}
        }
        settleTask = task
        settle = try {
            timer.schedule(task, settleMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    /** The full sheet of an ask in hand (20.6): counted once it is on screen ([SheetRequest.Consent.shown]). */
    internal fun consentRequest(v: ConsentState.Variant): SheetRequest.Consent =
        SheetRequest.Consent(v) { shownNow() }.also { lastRequest = it }

    /** The last sheet or card the SDK asked for, until it is answered: an answer means it was on screen. */
    @Volatile private var lastRequest: SheetRequest? = null

    /** A sheet or card was answered: the request it answers counts as shown (an app's own UI may not say so itself). */
    private fun answered() {
        when (val r = lastRequest) {
            is SheetRequest.Consent -> r.shown()
            is SheetRequest.Card -> r.shown()
            else -> {}
        }
        lastRequest = null
    }


    /** Waits until the open recording's audio is encoded and on disk (tests). */
    internal fun syncCapture() = recorder.sync()

    /** The last recording closed, for tests to wait on its encoder. */
    internal val lastCapture: ShareCapture? get() = recorder.lastClosed as? ShareCapture

    /** The join window's check now (its timer calls the same): closes a waiting recording whose window passed (tests). */
    internal fun checkJoinWindow() = recorder.checkWindow()

    private inner class Gate : SharingGate {
        override fun uploadsAllowed(): Boolean = active
        override fun uploadToken(): String {
            val t = cloud.tokens ?: throw CloudException(ApiError(401, "unauthorized"), "no key")
            val tier = cloud.currentConfig?.flags?.trust(env.app.platform) ?: "attested"
            if (tier == "attested") return t.token(attested = true).token
            // capped: an attested token where one can be had, else an unattested one
            return try { t.token(attested = t.canAttest()).token } catch (e: CloudException) {
                if (e.offline) throw e
                t.token(attested = false).token
            }
        }
        override fun invalidateToken() { cloud.tokens?.invalidate() }
        override fun networkAllowed(): Boolean = !cloud.options.uploadsOnUnmeteredOnly || env.unmetered()
        override fun uploaded(id: String) { if (state.takeThanks()) prompts?.invoke(SheetRequest.Thanks) }
        override fun contributorRevoked() { contributor.reset() }
        override fun contributorReadable(): Boolean = !contributor.lost()
        override fun badRequest(route: String) = cloud.report("other", "bad_request:$route")
        override fun lost(status: Int, code: String) {
            val rules = ai.quranlab.tajweedformer.cloud.ErrorRules
            val reason = if (status == 429 && code == "volume_limit") "quota" else rules.reasonOf(status, code, null)
            cloud.silent(ai.quranlab.tajweedformer.cloud.ErrorCause("sharing", reason, status.takeIf { it in 100..599 },
                errorCode = rules.token(code, rules.ERROR_CODE)?.takeIf { !it.startsWith("http_") }))
        }
        override fun log(message: String) { if (env.debugBuild) try { env.log(message) } catch (_: Exception) {} }
        override fun consentInForce(): Boolean = state.granted
        override fun consentCount(): Int = state.changes
        override fun deletedBelow(): Int = state.deletedBelow
    }

    companion object {
        /** The marker of a Client's start in the cloud folder: its absence at a start is the install's first launch. */
        const val STARTED = "started"

        val ROUTES = setOf("built_in", "wired", "usb", "bluetooth", "car", "continuity", "other", "unknown")
    }
}
