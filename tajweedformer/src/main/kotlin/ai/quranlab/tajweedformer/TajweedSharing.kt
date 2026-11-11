package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.sharing.Contribution
import ai.quranlab.tajweedformer.sharing.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recitation sharing, reached through `client.sharing`. Quran Lab decides per app whether learners are asked
 * ([isAvailable]); the developer switches nothing on. Where it is offered, the SDK asks the learner with its own
 * sheet at a quiet moment (at most three times; a yes is never asked again), and while the learner said yes
 * ([isOn]) every take is shared whole, in the background. Developers never receive recordings.
 *
 * What an app does with it: nothing (the default), or a settings row ([isOn], [setOn]), its own moment to ask
 * ([ask]), its own wording ([setWording]), the learner's deletion ([delete], [recoveryCode]), and, when it captures
 * audio between takes itself, [feed].
 */
public class TajweedSharing internal constructor(private val client: TajweedClient) {
    private val inner: Sharing get() = client.cloud.sharing

    /** Quran Lab's signed config offers sharing to this app on this device. Offered is not consent. */
    public val isAvailable: Boolean get() = inner.available

    /** The learner said yes and has not turned it off. */
    public val isOn: Boolean get() = inner.isOn

    /**
     * The settings switch. Off stops uploads at once and deletes the queue of recordings not sent yet; the SDK then
     * never asks again. On, while sharing is not available for this app, is the error `sharing_unavailable`.
     */
    public fun setOn(on: Boolean) {
        if (on && !inner.available) throw RecognizerException.SharingUnavailable()
        inner.setOn(on)
    }

    /**
     * Asks now, with the SDK's sheet, if an ask is still allowed (at most three per install, never after a yes or
     * after the switch was turned off). Returns whether the sheet is being shown. While sharing is not available for
     * this app the error is `sharing_unavailable`.
     */
    public fun ask(): Boolean {
        if (!inner.available) throw RecognizerException.SharingUnavailable()
        val show = inner.prompts ?: return false
        val variant = inner.askNow() ?: return false
        show(Sharing.SheetRequest.Consent(variant, inner.guardian))
        return true
    }

    /**
     * Audio the app already captures OUTSIDE takes (before the first, between them, after the last), mono float in
     * -1..1 at [sampleRate]. With it a shared recording starts about a second before a take and ends half a second
     * after it, so speech is not cut at the edges. The last second is kept in memory only; the model never sees it.
     * While sharing is off or the learner has not said yes, the samples are dropped at once and nothing is kept.
     * An app that uses `model.listen()` does not need this.
     */
    public fun feed(samples: FloatArray, sampleRate: Int) {
        if (sampleRate !in 8_000..192_000) throw RecognizerException.UnsupportedAudioFormat("$sampleRate Hz")
        inner.feed(samples, sampleRate)
    }

    /**
     * The outbox: one entry per recording, oldest first, each with `status` (`queued`, `uploaded` or `failed`),
     * `day` (UTC), `durationMs`, `bytes`, `segments` and `takes`.
     */
    public val items: List<Map<String, Any>>
        get() = inner.items.map { c: Contribution ->
            linkedMapOf<String, Any>("status" to c.status.name.lowercase(), "day" to c.day, "durationMs" to c.durationMs,
                "bytes" to c.bytes, "segments" to c.segments, "takes" to c.takes)
        }

    /**
     * The learner's summary from Quran Lab (the gratitude line): `you` (`sessions`, `minutes`), `app` (`contributors`,
     * `minutes`; null under ten contributors) and `all`. Null when nothing was shared or the call fails.
     */
    public suspend fun summary(): Map<String, Any?>? = withContext(Dispatchers.IO) { try { inner.summary() } catch (_: Exception) { null } }

    /**
     * "Delete what I shared": everything this learner shared from this app is deleted from Quran Lab's servers, the
     * queue is emptied and sharing is turned off. Returns true when the request was accepted (or nothing had been
     * shared), false when there was no connection or the server refused it: then nothing changed and it can be
     * tried again.
     */
    public suspend fun delete(): Boolean = withContext(Dispatchers.IO) {
        when (inner.deleteEverything()) {
            Sharing.Deletion.ACCEPTED, Sharing.Deletion.NOTHING_SHARED -> true
            else -> false
        }
    }

    /**
     * The learner's recovery code, for deleting what they shared when the device is lost; null before anything was
     * shared. Show it only to the learner, on request.
     */
    public val recoveryCode: String? get() = inner.recoveryCode()

    /**
     * The app's own wording for the sharing sheet: deck key to text (null or empty: Quran Lab's wording again). An
     * unknown key or an empty text is `invalid_argument` and nothing changes. A learner who agrees from then on
     * agrees to these words, recorded under their own consent version; a learner who already agreed is not asked
     * again. Your text must say honestly what is sent, to whom, why, and how to stop and delete.
     */
    public fun setWording(overrides: Map<String, String>?) {
        guard { inner.setWording(overrides) }
    }
}
