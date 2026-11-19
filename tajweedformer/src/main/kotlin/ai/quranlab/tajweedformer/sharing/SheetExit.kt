package ai.quranlab.tajweedformer.sharing

/**
 * How the SDK's consent sheet ended, and what that records: the rule of 3.1.0, kept in 3.1.1 for both of the SDK's
 * sheets (the Views dialog and the Compose sheet) and tested per case in `SheetExitTest`.
 *
 * The ask itself was counted right before the sheet went on screen (SDK 4.0: SHARE_ASK_40 20.6, `shown`), whatever
 * happens to the sheet afterwards. Then:
 *
 * - "Share" records a yes ([Sharing.accept]).
 * - "Not now" records a no ([Sharing.decline]).
 * - Closed without a button ([CLOSED]): the back gesture or key, a tap beside the sheet, and (new in 3.1.1, a gesture
 *   the grabber invites) a drag down from its top. 3.1.0's sheets were cancelable (its Views dialog by back and by a
 *   tap outside, its Compose dialog by back and a click outside) and recorded a cancel as "Not now"; so does this.
 * - No ending at all, so nothing is recorded: the sheet's window goes with its activity (a configuration change that
 *   recreates it, the activity finishing), the Compose host leaves the composition, the app goes to the background
 *   (the sheet is still there when it comes back), the process ends. The consent stays as it was (no answer), and
 *   the ask is used: the next one comes at the re-ask interval (SHARE_ASK_40 20.5), under the same cap of three.
 */
internal enum class SheetExit {
    SHARE,
    NOT_NOW,
    CLOSED;

    /** Records this ending as 3.1.0 did: "Share" a yes, "Not now" and a cancel a no. */
    fun record(sharing: Sharing) {
        when (this) {
            SHARE -> sharing.accept()
            NOT_NOW, CLOSED -> sharing.decline()
        }
    }
}
