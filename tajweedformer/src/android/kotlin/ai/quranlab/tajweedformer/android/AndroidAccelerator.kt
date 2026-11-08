package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * The accelerator's Android environment:
 * - the key: `Build.FINGERPRINT` (changes with every OS update, so the NPU is prepared again), the device
 *   model, the SoC ([DeviceSoc]);
 * - thermal: `PowerManager.getCurrentThermalStatus() >= THERMAL_STATUS_SEVERE` (API 29+) prefers the CPU;
 * - memory: `onTrimMemory(RUNNING_LOW)` or worse, or `ActivityManager.MemoryInfo.lowMemory`, prefers the CPU;
 * - the denylist: the portal link's `forcedBackend(revision)`;
 * - scheduling: a first preparation runs through WorkManager when the device is idle and charging on an
 *   unmetered network (the download needs it); a key that already passed is loaded again at once on a
 *   low-priority thread.
 */
class AndroidAccelerator internal constructor(
    context: Context,
    override val soc: String?,
    /** The first preparation at once on a background thread (DebugHooks.scheduleNow, debuggable apps only). */
    internal val scheduleNow: Boolean,
) : AcceleratorEnvironment, ComponentCallbacks2 {
    constructor(context: Context) : this(context, DeviceSoc.id, false)

    private val app = context.applicationContext
    @Volatile private var trimLow = false

    init {
        app.registerComponentCallbacks(this)
    }

    override val osBuild: String = Build.FINGERPRINT ?: "${Build.ID} ${Build.VERSION.INCREMENTAL}"
    override val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun thermalSevere(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    override fun lowMemory(): Boolean {
        if (trimLow) return true
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }

    override fun forcedBackend(revision: String): String? = try { QuranLabAndroid.cloud?.forcedBackend(revision) } catch (_: Exception) { null }

    override fun schedule(constrained: Boolean, work: () -> Unit) {
        if (!constrained || scheduleNow) {
            Thread({ work() }, "tajweedformer-npu-prepare").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            return
        }
        val id = "tajweedformer-npu-prepare"
        pending[id] = work
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val req = OneTimeWorkRequestBuilder<NpuPrepareWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(app).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, req)
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
        /** The preparation work waiting for its constraints, run by [NpuPrepareWorker] in this process. */
        internal val pending = ConcurrentHashMap<String, () -> Unit>()
    }
}

/**
 * Runs the NPU preparation once WorkManager's constraints hold (idle, charging, unmetered). When the
 * process was restarted in between there is nothing to run: the next start schedules it again.
 */
class NpuPrepareWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AndroidAccelerator.pending.remove("tajweedformer-npu-prepare")?.invoke()
        return Result.success()
    }
}
