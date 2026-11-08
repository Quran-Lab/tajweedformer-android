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
 * - [unmetered]: the network counts as unmetered (the test server is reached through adb reverse).
 */
internal object DebugHooks {
    @JvmField @Volatile var soc: String? = null
    @JvmField @Volatile var scheduleNow: Boolean = false
    @JvmField @Volatile var unmetered: Boolean = false

    fun reset() {
        soc = null
        scheduleNow = false
        unmetered = false
    }
}
