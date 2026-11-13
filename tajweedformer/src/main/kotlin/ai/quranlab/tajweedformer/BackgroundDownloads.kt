package ai.quranlab.tajweedformer

/**
 * Keeps a model download going when the app leaves the foreground or its process ends (SDK 3.1.3). The Client says
 * when a download of the model's CPU set starts ([owe]) and when nothing more is owed ([settled]); in between the
 * platform runs [TajweedClient.continueDownload] by itself, in the app's process or in a new one, until it answers
 * [Continued.DONE] or [Continued.GAVE_UP]. Android: one WorkManager job on the network the call allowed, with no
 * foreground service and no notification ([ai.quranlab.tajweedformer.android.ModelDownloads]). The desktop JVM has
 * none: there the process is the app.
 */
internal interface BackgroundDownloads {
    /** A download starts: continue it when the app leaves or the process ends, on any network or, with
     *  [unmeteredOnly], on an unmetered one only. */
    fun owe(unmeteredOnly: Boolean)

    /** Nothing is owed any more: the model is on the device, it was removed, or what stopped it is not for retrying. */
    fun settled()
}

/** What a background continuation did. */
internal enum class Continued {
    /** Nothing was owed, or the model's CPU set is on the device now. */
    DONE,

    /** The network failed, or the continuation was ended: to be tried again later. */
    RETRY,

    /** It cannot go on by itself (a refusal, the daily limit, the storage): nothing more until the app asks. */
    GAVE_UP,
}
