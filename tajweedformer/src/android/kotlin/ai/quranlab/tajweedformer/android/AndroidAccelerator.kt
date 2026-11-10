package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.Conditions
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The accelerator's Android environment:
 * - the key: `Build.FINGERPRINT` (changes with every OS update, so the NPU is promoted again), the device model, the
 *   SoC ([DeviceSoc]);
 * - the pipeline's conditions: battery saver off, battery at 20 % or more or charging (the sticky battery broadcast),
 *   thermal status NONE or LIGHT (API 29+); the download stage waits for an unmetered network;
 * - at a session start, thermal SEVERE or worse and memory warnings (`onTrimMemory(RUNNING_LOW)` or worse, or
 *   `MemoryInfo.lowMemory`) keep that session on the CPU;
 * - the denylist: the portal link's `forcedBackend(revision)`;
 * - scheduling: work with no delay and no network need runs at once on a low-priority thread; a delayed or
 *   unmetered-only run goes through WorkManager (with that delay, and `NetworkType.UNMETERED` when asked).
 * - incident reports: `Build.MODEL` (a model code, never a name), `<release>/<Build.ID>/<incremental>`, the dispatch
 *   library of the SoC.
 */
class AndroidAccelerator internal constructor(
    context: Context,
    override val soc: String?,
    /** Every run at once on a background thread (DebugHooks.scheduleNow, debuggable apps only). */
    internal val scheduleNow: Boolean,
) : AcceleratorEnvironment, ComponentCallbacks2 {
    constructor(context: Context) : this(context, DeviceSoc.id, false)

    private val app = context.applicationContext
    private val network = AndroidNetwork(app)
    @Volatile private var trimLow = false

    init {
        app.registerComponentCallbacks(this)
    }

    override val osBuild: String = Build.FINGERPRINT ?: "${Build.ID} ${Build.VERSION.INCREMENTAL}"
    override val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override val deviceModelCode: String = Build.MODEL ?: "unknown"
    override val osBuildReport: String = "${Build.VERSION.RELEASE}/${Build.ID}/${Build.VERSION.INCREMENTAL}"

    /** The vendor dispatch library the SoC uses (its version is the app's LiteRT dependency). */
    override val delegate: String? = when {
        soc == null -> null
        soc.startsWith("tensor") -> "LiteRtDispatch_GoogleTensor/2.2.0"
        soc.startsWith("exynos") -> "LiteRtDispatch_Samsung/2.2.0"
        else -> null
    }

    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    override fun thermalSevere(): Boolean = thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE

    override fun lowMemory(): Boolean {
        if (trimLow) return true
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }

    override fun conditions(): Conditions {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) 100 * level / scale else 100
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = when (thermalStatus()) {
            0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; else -> "shutdown"
        }
        return Conditions(batteryPct = pct, charging = plugged, powerSave = pm.isPowerSaveMode, thermal = thermal)
    }

    override fun unmetered(): Boolean = network.isUnmetered()

    override fun forcedBackend(revision: String): String? = try { QuranLabAndroid.cloud?.forcedBackend(revision) } catch (_: Exception) { null }

    override fun accelDenied(revision: String, accelerator: String): String? = try {
        QuranLabAndroid.cloud?.accelDenied(accelerator, revision, osBuildReport, deviceModelCode, soc, delegate)
    } catch (_: Exception) {
        null
    }

    /**
     * A pipeline run, durable: the closure runs in this process (at once on a low-priority thread when it is due now and
     * needs no network, else when WorkManager's unique work fires), and the same unique work survives process death and
     * reboots, so a fresh process resumes every key from its persisted record ([TajweedFormerAndroid.resumePromotions]).
     * Constraints: battery not low; an unmetered network when the download waits for one; no "device idle" (rarely met
     * while the app is in use). A run due now also leaves a durable fallback 15 minutes later (nothing to do once done).
     */
    override fun schedule(delayMs: Long, unmetered: Boolean, work: () -> Unit) {
        pending.add(work)
        val now = scheduleNow || (delayMs <= 0 && !unmetered)
        if (now) Thread({ runPending() }, "tajweedformer-npu-prepare").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        val c = Constraints.Builder().setRequiresBatteryNotLow(true)
        if (unmetered) c.setRequiredNetworkType(NetworkType.UNMETERED)
        val req = OneTimeWorkRequestBuilder<NpuPrepareWorker>().setConstraints(c.build())
            .setInitialDelay(if (now) FALLBACK_MS else maxOf(0, delayMs), TimeUnit.MILLISECONDS).build()
        try {
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) {
            // WorkManager not initialised in this process (a custom configuration that is not ready): the next launch resumes
        }
    }

    override fun onTrimMemory(level: Int) {
        trimLow = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    }

    @Deprecated("ComponentCallbacks")
    override fun onLowMemory() {
        trimLow = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    companion object {
        /** The unique work of the promotion pipeline. */
        const val WORK = "tajweedformer-npu-prepare"
        private const val FALLBACK_MS = 15 * 60_000L

        /** Pipeline runs scheduled in this process (each one resumes its key from its record; running one twice is harmless). */
        internal val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

        /** Runs what this process scheduled; false when there was nothing (a fresh process). */
        internal fun runPending(): Boolean {
            var any = false
            while (true) {
                val w = pending.poll() ?: return any
                any = true
                try { w() } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Runs the promotion pipeline once WorkManager's delay and constraints hold: what this process scheduled, or, in a
 * process WorkManager started for it (after the app was killed or the phone restarted), every key resumed from its
 * persisted record ([TajweedFormerAndroid.resumePromotions]).
 */
class NpuPrepareWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!AndroidAccelerator.runPending()) {
            try { TajweedFormerAndroid.resumePromotions(applicationContext) } catch (_: Exception) {}
        }
        return Result.success()
    }
}
