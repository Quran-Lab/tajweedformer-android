package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.CloudEnvironment
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.CloudScheduler
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import ai.quranlab.tajweedformer.sharing.Sharing
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Android entry point of the portal link. Call once, in `Application.onCreate`:
 *
 * ```
 * QuranLabAndroid.configure(this, CloudOptions(key = "qlk_v1_..."))
 * ```
 *
 * Then hand [observer] to the recognizer (the core SDK does this when it was configured). From there
 * everything is silent and in the background: registration once a UTC day (at the first opening of the
 * day), the signed config at launch, on every return to the foreground and every 60 s while the app is
 * in use (portal changes apply at the next session start), telemetry deltas every 15 minutes in use and
 * when the app goes to the background, sent through WorkManager with the issues, and recitation sharing
 * (where Quran Lab's config offers it) with the SDK's consent sheet ([sharingTheme]) or the app's own UI
 * ([sheets]), and a persistent upload queue that survives process death and reboots.
 */
object QuranLabAndroid {
    @Volatile var cloud: QuranLabCloud? = null
        private set

    /** What the recognizer reports to: the configured cloud, else (the SDK's client installed it) the offline keeper of
     *  accelerator incidents, sent once the app calls [configure] (an app that never does sends none). */
    val observer: TajweedObserver get() = cloud ?: offline ?: NONE

    private val NONE = object : TajweedObserver {}
    @Volatile private var offline: TajweedObserver? = null

    /** Keeps accelerator incidents on the device until [configure] (installed with the SDK's client). */
    @JvmStatic
    internal fun keepIncidentsOffline(context: Context) {
        if (offline != null) return
        offline = ai.quranlab.tajweedformer.cloud.OfflineIncidents(ai.quranlab.tajweedformer.cloud.IncidentSpool(File(storage(context.applicationContext), "cloud")))
    }

    /**
     * The app's own UI for the SDK's sheets; null: the SDK shows its own over the resumed activity. A
     * [Sharing.SheetRequest.Consent] is an ask already counted: show the strings of `cloud.sharing.copy`
     * and report the learner's tap with `sharing.accept()` or `sharing.decline()`.
     */
    @Volatile var sheets: ((Sharing.SheetRequest) -> Unit)? = null

    /** The look of the SDK's own sheets (colours, corner radius, fonts); the words stay the SDK's. */
    @Volatile var sharingTheme: SharingTheme = SharingTheme()

    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "quranlab-cloud").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val uploads = Executors.newSingleThreadExecutor { r -> Thread(r, "quranlab-uploads").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var ticker: Handler? = null
    private var started = 0
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var sessionOpen = false

    /** The activity in front, for the SDK's own sheets. */
    val resumedActivity: Activity? get() = resumed?.get()

    @JvmStatic
    @Synchronized
    fun configure(context: Context, options: CloudOptions): QuranLabCloud {
        cloud?.let { return it }
        val app = context.applicationContext
        val env = environment(app, options)
        val c = QuranLabCloud(options, env)
        cloud = c
        QuranLabCloud.install(c)
        saveOptions(app, options)
        c.sharing.prompts = { r -> Handler(Looper.getMainLooper()).post { show(r) } }
        (app as? Application)?.registerActivityLifecycleCallbacks(Lifecycle)
        // the opening itself (a launch, the day's registration) is counted when the first activity starts
        work.execute { try { c.launch(foreground = false) } catch (_: Exception) {} }
        return c
    }

    /** A worker in a process where the app did not configure (WorkManager started it): the options saved at configure. */
    internal fun restore(context: Context): QuranLabCloud? {
        cloud?.let { return it }
        val o = try {
            @Suppress("UNCHECKED_CAST")
            Json.parse(File(storage(context), "cloud/options.json").readText()) as Map<String, Any?>
        } catch (_: Exception) { return null }
        return configure(context, CloudOptions(
            key = o["key"] as? String,
            analytics = if (o["analytics"] == "off") CloudOptions.Analytics.OFF else CloudOptions.Analytics.ON,
            issueReports = o["issues"] as? Boolean ?: true,
            contributions = o["contributions"] as? Boolean ?: false,
            audience = if (o["audience"] == "children") CloudOptions.Audience.CHILDREN else CloudOptions.Audience.GENERAL,
            uploadsOnUnmeteredOnly = o["unmetered"] as? Boolean ?: false,
            playIntegrityCloudProjectNumber = (o["pi"] as? Double)?.toLong(),
            apiUrl = o["api"] as? String ?: CloudOptions(null).apiUrl,
        ))
    }

    private fun saveOptions(context: Context, o: CloudOptions) {
        try {
            val f = File(storage(context), "cloud/options.json")
            f.parentFile?.mkdirs()
            f.writeText(Json.write(linkedMapOf("key" to o.key, "analytics" to o.analytics.name.lowercase(), "issues" to o.issueReports,
                "contributions" to o.contributions, "audience" to o.audience.name.lowercase(), "unmetered" to o.uploadsOnUnmeteredOnly,
                "pi" to o.playIntegrityCloudProjectNumber, "api" to o.apiUrl)))
        } catch (_: Exception) {
        }
    }

    internal fun storage(context: Context) = File(context.noBackupFilesDir, "quranlab")

    private fun environment(context: Context, options: CloudOptions): CloudEnvironment {
        val pm = context.packageManager
        val info = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val label = try { context.applicationInfo.loadLabel(pm).toString() } catch (_: Exception) { null }
        val app = AppIdentity(context.packageName, AppIdentity.cleanVersion(info?.versionName), label?.take(80))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } else Build.HARDWARE
        val device = DeviceProfile(
            DeviceProfile.deviceClass(Build.MODEL),
            DeviceProfile.token(soc?.lowercase()),
            Build.VERSION.RELEASE.takeWhile { it.isDigit() }.toIntOrNull()?.coerceIn(1, 99) ?: (Build.VERSION.SDK_INT - 19).coerceIn(1, 99),
            Build.VERSION.RELEASE.take(32),
            DeviceProfile.ramTier(mem.totalMem),
        )
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return CloudEnvironment(
            storage = storage(context),
            app = app,
            device = device,
            contributorKeys = AndroidKeystoreContributorKeys(context),
            secretBox = KeystoreSecretBox(),
            integrity = PlayIntegrity.create(context, options.playIntegrityCloudProjectNumber),
            scheduler = WorkScheduler(context),
            inputRoute = { inputRoute(context) },
            locale = { context.resources.configuration.locales[0].toLanguageTag() },
            debugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            unmetered = { !cm.isActiveNetworkMetered },
            log = { android.util.Log.w("QuranLab", it) },
        )
    }

    /** The kind of the current input (never a device name). */
    @JvmStatic
    fun inputRoute(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }.toSet()
        return when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it == AudioDeviceInfo.TYPE_BLE_HEADSET) } -> "bluetooth"
            types.contains(AudioDeviceInfo.TYPE_USB_DEVICE) || types.contains(AudioDeviceInfo.TYPE_USB_HEADSET) -> "usb"
            types.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) -> "wired"
            types.contains(AudioDeviceInfo.TYPE_BUILTIN_MIC) -> "built_in"
            else -> "unknown"
        }
    }

    /** The recognizer says a session is open (the config is refreshed every 60 s while it is). */
    @JvmStatic
    fun sessionOpen(open: Boolean) {
        sessionOpen = open
        updateTicker()
    }

    private fun show(r: Sharing.SheetRequest) {
        val custom = sheets
        if (custom != null) return custom(r)
        val a = resumedActivity ?: return
        if (sessionOpen || a.isFinishing) return
        val c = cloud ?: return
        SharingDialogs.show(a, c.sharing, r, sharingTheme)
    }

    @Synchronized
    private fun updateTicker() {
        val inUse = started > 0 || sessionOpen
        if (inUse && ticker == null) {
            val t = HandlerThread("quranlab-config").apply { start() }
            val h = Handler(t.looper)
            ticker = h
            val tick = object : Runnable {
                override fun run() {
                    work.execute { cloud?.let { it.refreshConfig(); it.tick() } }
                    h.postDelayed(this, 60_000)
                }
            }
            h.postDelayed(tick, 60_000)
        } else if (!inUse && ticker != null) {
            ticker?.looper?.quitSafely()
            ticker = null
        }
    }

    /** The last activity stopped for a configuration change (a rotation): its successor is no new opening. */
    @Volatile private var changingConfig = false

    /**
     * The process's foreground and background, as ProcessLifecycleOwner sees them (the first activity started,
     * the last one stopped), without its dependency: a rotation is neither.
     */
    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            val first = synchronized(QuranLabAndroid) { started++ == 0 }
            if (first) {
                val reopened = !changingConfig
                changingConfig = false
                // a cold start or back in the foreground: a launch counted, the day's registration, the config, deliveries due
                if (reopened) work.execute { cloud?.let { try { it.foreground(); it.refreshConfig(); it.deliver() } catch (_: Exception) {} } }
            }
            updateTicker()
        }

        override fun onActivityStopped(a: Activity) {
            val last = synchronized(QuranLabAndroid) { --started <= 0 }
            if (last) {
                started = 0
                if (a.isChangingConfigurations) {
                    changingConfig = true
                } else {
                    // in the background: a telemetry send point (sealed on disk, sent by WorkManager)
                    work.execute { cloud?.let { try { it.background() } catch (_: Exception) {} } }
                }
            }
            updateTicker()
        }

        override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
        override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    /**
     * WorkManager does the network work, so it survives process death and reboots: one unique job
     * for telemetry and issues, one for the sharing outbox (any network, or unmetered only when the
     * developer chose it). While the app runs, the outbox is also drained at once in-process.
     */
    internal class WorkScheduler(context: Context) : CloudScheduler {
        private val wm by lazy { WorkManager.getInstance(context.applicationContext) }

        override fun scheduleDelivery(cloud: QuranLabCloud) {
            wm.enqueueUniqueWork("quranlab.delivery", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DeliveryWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                    .build())
        }

        override fun scheduleUploads(cloud: QuranLabCloud, atMs: Long?) {
            if (atMs == null) uploads.execute { try { cloud.sharing.outbox.drain() } catch (_: Exception) {} }
            val delay = atMs?.let { maxOf(0, it - System.currentTimeMillis()) } ?: 30_000L
            val net = if (cloud.options.uploadsOnUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            wm.enqueueUniqueWork("quranlab.outbox", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build())
        }
    }
}

/** Telemetry reports and issue reports. */
class DeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = QuranLabAndroid.cloud ?: QuranLabAndroid.restore(applicationContext) ?: return Result.success()
        c.deliver()
        return Result.success()
    }
}

/** The sharing outbox: drains while there is network; retried with backoff while something is left. */
class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = QuranLabAndroid.cloud ?: QuranLabAndroid.restore(applicationContext) ?: return Result.success()
        c.sharing.outbox.drain()
        return if (c.sharing.active && c.sharing.outbox.hasWork()) Result.retry() else Result.success()
    }
}
