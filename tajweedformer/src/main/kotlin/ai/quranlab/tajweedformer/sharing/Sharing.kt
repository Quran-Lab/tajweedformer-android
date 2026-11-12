package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.cloud.ApiError
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.Day
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import java.io.File

/**
 * Recitation sharing (docs/platform-client.md). Where Quran Lab's signed config offers it to this app
 * (`flags.contributions`, decided per app on the server; the app's option no longer does since 2.0.1),
 * the SDK asks the learner at the right moment (the sheet at most 3 times per install, never again after a
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

    @Volatile private var ownWording: Map<String, String> = emptyMap()
    @Volatile private var wordedCopy: SharingCopy? = null

    /**
     * The copy in the app's language (the SDK bundles every shipped language), with the app's own wording
     * ([setWording]) applied. Its [SharingCopy.consentVersion] is the version a new grant is recorded under.
     */
    val copy: SharingCopy
        get() {
            val base = SharingCopy.forLanguage(env.locale())
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

    /** The guardian variant of the sheet (children's apps). */
    val guardian: Boolean get() = cloud.options.audience == CloudOptions.Audience.CHILDREN

    /**
     * What the SDK wants shown; the UI module (or the app, for its own UI) registers here. Called off the main
     * thread. A [SheetRequest.Consent] is an ask the SDK already counted: show it, and report the learner's
     * tap with [accept] or [decline] (a dismissed sheet is a decline).
     */
    @Volatile var prompts: ((SheetRequest) -> Unit)? = null

    /** A sheet to show: the consent sheet ([variant]), the thank-you, or the impact line. */
    sealed class SheetRequest {
        data class Consent(val variant: ConsentState.Variant, val guardian: Boolean) : SheetRequest()
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
     * The signed config offers sharing to this install (the sheet may be shown). Quran Lab decides this per
     * app (`flags.contributions`); since 2.0.1 the app's `CloudOptions.contributions` does not.
     * Offered is never consent: nothing is captured or sent before the learner answers "Share" ([isOn]).
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
     * The app's own timing: the sheet to show now, or null (asks are counted as in the default timing,
     * within the same 3 showings per install; never after a yes or the settings switch turned off).
     */
    fun askNow(): ConsentState.Variant? {
        if (!available) return null
        val v = state.askNow()
        if (v != null) cloud.telemetry.shareAsked()
        return v
    }

    /**
     * "Share my recitations" on a consent sheet, the SDK's or the app's own (for a guardian sheet, after the
     * confirmation was ticked). Only from the learner's own tap.
     */
    @JvmOverloads
    fun accept(byGuardian: Boolean = guardian) {
        val c = copy
        state.grant(c.consentVersion, byGuardian, c.consentTextSha256)
        cloud.telemetry.shareAnswer(true)
        env.scheduler.scheduleUploads(cloud)
    }

    /** "Not now", or the sheet dismissed. */
    fun decline() {
        state.decline()
        cloud.telemetry.shareAnswer(false)
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

    /** Real numbers from the backend (`GET /v1/contributions/summary`), or null when the call fails or nothing was shared. */
    fun summary(): Map<String, Any?>? {
        if (!contributor.hasKey) return null
        return try {
            val t = cloud.tokens ?: return null
            cloud.api.contributionsSummary(t.token().token, contributor.id)
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

    /** The app went to the background: a recording waiting for the next take closes now (a running take's when it ends). */
    internal fun background() = recorder.background()

    /** The app is in the foreground again: takes in a row join again. */
    internal fun foreground() = recorder.foreground()

    // ---- the SDK's own settings sheet only (internal: no app API deletes or reveals) ----

    /** The learner's recovery code, for the SDK's settings sheet (shown on request, never to the app). */
    internal fun recoveryCode(): String? = if (contributor.hasKey) contributor.recoveryCode() else null

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
        val result = outbox.whileStopped {
            if (!contributor.hasKey) {
                state.afterDeletion()
                finishDeletion()
                Deletion.NOTHING_SHARED
            } else {
                val app = env.app.appId
                val id = contributor.id
                // on the device before the server is asked: what a process that ends in here leaves is a no. When it
                // cannot be stored, the server is asked all the same (SPEC 11.7)
                state.deletionAsked()
                try {
                    val nonce = cloud.api.deletionChallenge(app, id)
                    val ts = env.clock() / 1000
                    val msg = ContributorKeys.deletionMessage(app, id, nonce, ts)
                    val sig = contributor.sign(msg.toByteArray(Charsets.UTF_8))
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
    internal fun sessionStarted(e: TajweedObserver.SessionStart) = recorder.takeStarted(e)

    /**
     * What a recording is bound to: a take joins an open recording only under the same contributor, consent version
     * (and wording hash) and model revision. The backend and the input route may differ (the session JSON keeps the
     * first take's).
     */
    private fun identity(e: TajweedObserver.SessionStart): Any? {
        val version = state.consentVersion
        val textSha = if (version?.startsWith(ConsentWording.PREFIX) == true) state.consentTextSha256 else null
        return listOf(contributor.id, version, textSha, e.revision)
    }

    /** A new recording for the take [e] (its session JSON is written once, with the first audio), or null. */
    private fun open(e: TajweedObserver.SessionStart, order: RecordingOrder): Recording? {
        // the count first, the consent after it: a no between the two is either seen by the look at the consent or
        // leaves this recording with an old count
        val count = state.changes
        if (!active) return null
        val binding = try { contributor.binding() } catch (_: Exception) { return null }
        val c = contributor.id
        // the version the learner agreed to (not the copy's now), and for the app's own wording its hash
        val consentVersion = state.consentVersion ?: return null
        val textSha = if (consentVersion.startsWith(ConsentWording.PREFIX)) state.consentTextSha256 ?: return null else null
        val day = Day.of(env.clock())
        val dev = env.device
        return ShareCapture(outbox, { rate, route ->
            ShareCapture.sessionJson(e.revision, e.decodeBackend, e.weightsSha256, consentVersion, c, binding.first, binding.second,
                day, dev.deviceClass, dev.osMajor, rate, route, textSha,
                modelBuild = e.build, takeSeq = order.takeSeq, gapMsPrev = order.gapMsPrev)
        }, e.inputRoute.takeIf { it in ROUTES } ?: env.inputRoute().takeIf { it in ROUTES } ?: "unknown", { active }, consent = count)
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
        if (!e.completed) return
        // an ask nobody can show is not counted: the sheet goes to the SDK's UI or the app's own
        val show = prompts
        val v = state.sessionCompleted(e.feedbackShown, available && show != null)
        if (v != null && show != null) {
            cloud.telemetry.shareAsked()
            show(SheetRequest.Consent(v, guardian))
        }
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
        override fun badRequest(route: String) = cloud.report("other", "bad_request:$route")
        override fun log(message: String) { if (env.debugBuild) try { env.log(message) } catch (_: Exception) {} }
        override fun consentInForce(): Boolean = state.granted
        override fun consentCount(): Int = state.changes
        override fun deletedBelow(): Int = state.deletedBelow
    }

    companion object {
        val ROUTES = setOf("built_in", "wired", "usb", "bluetooth", "car", "continuity", "other", "unknown")
    }
}
