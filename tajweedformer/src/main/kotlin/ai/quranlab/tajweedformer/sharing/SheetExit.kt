package ai.quranlab.tajweedformer.sharing

/**
 * How the SDK's consent sheet ended, and what that records: the rule of 3.1.0, kept in 3.1.1 for both of the SDK's
 * sheets (the Views dialog and the Compose sheet) and tested per case in `SheetExitTest`.
 *
 * The ask itself was counted before the sheet was shown ([ConsentState.sessionCompleted], [ConsentState.askNow]),
 * whatever happens to the sheet afterwards. Then:
 *
 * - "Share" records a yes ([Sharing.accept], the guardian's in the guardian variant).
 * - "Not now" records a no ([Sharing.decline]).
 * - Closed without a button ([CLOSED]): the back gesture or key, a tap beside the sheet, and (new in 3.1.1, a gesture
 *   the grabber invites) a drag down from its top. 3.1.0's sheets were cancelable (its Views dialog by back and by a
 *   tap outside, its Compose dialog by back and a click outside) and recorded a cancel as "Not now"; so does this.
 * - No ending at all, so nothing is recorded: the sheet's window goes with its activity (a configuration change that
 *   recreates it, the activity finishing), the Compose host leaves the composition, the app goes to the background
 *   (the sheet is still there when it comes back), the process ends. The consent stays as it was (no answer), and
 *   the ask counts as a "Not now" (SDK 3.1.5): the next one comes at least 7 sessions later, with the re-ask line,
 *   under the same cap of three showings. Until 3.1.5 the first sheet came back at the next completed session.
 */
internal enum class SheetExit {
    SHARE,
    NOT_NOW,
    CLOSED;

    /** Records this ending as 3.1.0 did: "Share" a yes, "Not now" and a cancel a no. */
    fun record(sharing: Sharing, guardian: Boolean) {
        when (this) {
            SHARE -> sharing.accept(guardian)
            NOT_NOW, CLOSED -> sharing.decline()
        }
    }
}
