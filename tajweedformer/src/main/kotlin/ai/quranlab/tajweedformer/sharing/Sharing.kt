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
 * background, under the consent version the learner agreed to.
 *
 * What the app can do: let the SDK show its own sheet (the default, themed or not), or show its own UI:
 * set [prompts] (or `QuranLabAndroid.sheets`), read the strings from [copy] and report the learner's tap
 * with [accept] or [decline]; ask at its own moment ([askNow]); replace the deck's words with its own
 * ([setWording]: the learner then agrees to those words, under their own consent version); the settings
 * switch ([setOn]); read the state. Asks and answers are counted the same on every path. What it cannot:
 * read the recovery code, sign with the contributor key, or delete; the SDK's own settings sheet does
 * those, after the learner confirms.
 */
class Sharing internal constructor(private val cloud: QuranLabCloud) {
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

    private var capture: ShareCapture? = null
    private var sessionInfo: TajweedObserver.SessionStart? = null

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
     * agreed to stays). Off stops uploads at once and deletes the unsent queue, and the SDK never asks again;
     * it works whatever words were agreed to.
     */
    fun setOn(on: Boolean) {
        val c = copy
        state.setFromSettings(on, c.consentVersion, textSha256 = c.consentTextSha256)
        if (!on) stopAndClearQueue() else env.scheduler.scheduleUploads(cloud)
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

    private fun stopAndClearQueue() {
        capture?.finish(completed = false)
        capture = null
        outbox.cancelQueued()
    }

    /** The signed config switched sharing off: pause; the local queue is kept 7 days (the outbox expires it). */
    internal fun configTurnedOff() {
        capture?.finish(completed = false)
        capture = null
    }

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
     */
    internal fun deleteEverything(): Deletion {
        if (!contributor.hasKey) {
            stopAndClearQueue()
            outbox.clearAll()
            state.afterDeletion()
            return Deletion.NOTHING_SHARED
        }
        val app = env.app.appId
        val id = contributor.id
        return try {
            val nonce = cloud.api.deletionChallenge(app, id)
            val ts = env.clock() / 1000
            val msg = ContributorKeys.deletionMessage(app, id, nonce, ts)
            val sig = contributor.sign(msg.toByteArray(Charsets.UTF_8))
            cloud.api.deleteSigned(linkedMapOf("app" to app, "contributor" to id, "nonce" to nonce, "timestamp" to ts,
                "action" to "delete-all", "signature" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig)))
            stopAndClearQueue()
            outbox.clearAll()
            contributor.reset()
            state.afterDeletion()
            Deletion.ACCEPTED
        } catch (e: CloudException) {
            if (e.offline) Deletion.OFFLINE else Deletion.REFUSED
        }
    }

    // ---- the recognizer's hooks (through QuranLabCloud) ----

    internal fun sessionStarted(e: TajweedObserver.SessionStart) {
        sessionInfo = e
        capture?.finish(completed = false)
        capture = null
        if (!active) return
        val binding = try { contributor.binding() } catch (_: Exception) { return }
        val c = contributor.id
        // the version the learner agreed to (not the copy's now), and for the app's own wording its hash
        val consentVersion = state.consentVersion ?: return
        val textSha = if (consentVersion.startsWith(ConsentWording.PREFIX)) state.consentTextSha256 ?: return else null
        val day = Day.of(env.clock())
        val dev = env.device
        capture = ShareCapture(outbox, { rate, route ->
            ShareCapture.sessionJson(e.revision, e.decodeBackend, e.weightsSha256, consentVersion, c, binding.first, binding.second,
                day, dev.deviceClass, dev.osMajor, rate, route, textSha)
        }, e.inputRoute.takeIf { it in ROUTES } ?: env.inputRoute().takeIf { it in ROUTES } ?: "unknown", { active })
    }

    internal fun audio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        capture?.append(samples, offset, length, sampleRate)
    }

    internal fun sessionEnded(e: TajweedObserver.SessionEnd) {
        capture?.let { it.finish(e.completed); lastCapture = it }
        capture = null
        if (!e.completed) return
        // an ask nobody can show is not counted: the sheet goes to the SDK's UI or the app's own
        val show = prompts
        val v = state.sessionCompleted(e.feedbackShown, available && show != null)
        if (v != null && show != null) {
            cloud.telemetry.shareAsked()
            show(SheetRequest.Consent(v, guardian))
        }
    }

    /** Waits until the open session's audio is encoded and on disk (tests). */
    internal fun syncCapture() { capture?.sync() }

    /** The last capture ended, for tests to wait on its encoder. */
    @Volatile internal var lastCapture: ShareCapture? = null

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
    }

    companion object {
        val ROUTES = setOf("built_in", "wired", "usb", "bluetooth", "car", "continuity", "other", "unknown")
    }
}
