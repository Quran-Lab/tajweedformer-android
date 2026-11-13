package ai.quranlab.tajweedformer.android

/**
 * Overrides for the on-device NPU check (bench-app's NpuCheckActivity), honoured ONLY when the host app is
 * debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`); a release app never sees them. Internal to the SDK (Java code
 * in a debug tool can set the fields; Kotlin apps cannot).
 *
 * - [soc]: the SoC id the client and the accelerator use instead of [DeviceSoc.id] (a phone without that NPU
 *   can then run a stand-in LiteRT for that SoC's build);
 * - [scheduleNow]: the first NPU preparation runs at once on a background thread instead of waiting for
 *   WorkManager's idle + charging + unmetered constraints;
 * - [unmetered]: the network counts as unmetered (the test server is reached through adb reverse);
 * - [singleStream], [backgroundDownloads], [downloads]: the model download of SDK 3.1.3 against 3.1.2's, measured on a
 *   phone (the example app's debug screen DownloadProofActivity).
 */
internal object DebugHooks {
    @JvmField @Volatile var soc: String? = null
    @JvmField @Volatile var scheduleNow: Boolean = false
    @JvmField @Volatile var unmetered: Boolean = false

    /** Every model file in one request, as 3.1.2 fetched them (the phone proof of SDK 3.1.3 measures both). Read when
     *  a client is made. */
    @JvmField @Volatile var singleStream: Boolean = false

    /** False: no background job for the model download, as in 3.1.2. Read when a client is made. */
    @JvmField @Volatile var backgroundDownloads: Boolean = true

    /** One line per model file downloaded in this process (path, bytes, time, ranges, protocols), newest last, at most
     *  200: what the phone proof reads. */
    @JvmField val downloads: java.util.concurrent.ConcurrentLinkedDeque<String> = java.util.concurrent.ConcurrentLinkedDeque()

    @Volatile private var watching = false

    /** Fills [downloads] from the SDK's own download events (debuggable apps only; once per process). */
    @Synchronized
    fun watchDownloads() {
        if (watching) return
        watching = true
        ai.quranlab.tajweedformer.SdkEvents.add(object : ai.quranlab.tajweedformer.SdkObserver {
            override fun onDownloadFinished(result: ai.quranlab.tajweedformer.DownloadResult) {
                val line = "path=${result.path} ok=${result.ok} bytes=${result.networkBytes} of=${result.totalBytes} " +
                    "resumed=${result.resumedFrom} ms=${result.millis} ranged=${result.ranged} protocols=${result.protocols}" +
                    (result.error?.let { " error=$it" } ?: "")
                downloads.add(line)
                while (downloads.size > 200) downloads.pollFirst()
                // also to the device's log: the phone proof reads what a process did that no screen of the app was in
                // (the background job's own process, after the app's was ended)
                log("SDK file $line")
            }
        })
    }

    /** A line in the device's log (tag TJ_DL), for the phone proof; called in debuggable apps only. */
    fun log(line: String) {
        try { android.util.Log.i("TJ_DL", line) } catch (_: Throwable) {}
    }

    fun reset() {
        soc = null
        scheduleNow = false
        unmetered = false
        singleStream = false
        backgroundDownloads = true
    }
}
