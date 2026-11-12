package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.AndroidMicrophone
import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.SharingDialogs
import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.CloudEnvironment
import ai.quranlab.tajweedformer.cloud.CloudScheduler
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.android.AndroidKeystoreContributorKeys
import ai.quranlab.tajweedformer.cloud.android.KeystoreInstallKey
import ai.quranlab.tajweedformer.cloud.android.KeystoreSecretBox
import ai.quranlab.tajweedformer.cloud.android.PlayIntegrity
import ai.quranlab.tajweedformer.npu.LiteRtRunner
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
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What `TajweedClient(context, key)` is made of on Android: the app's identity and the device profile, the SDK's
 * private storage (`noBackupFilesDir/quranlab`), the Keystore, Play Integrity (when the signed config asks for it), the network
 * state, the SoC and the LiteRT runtime for the NPU, the microphone, and the process's foreground and background.
 *
 * From there everything is silent and in the background: registration once a UTC day (at the first opening of the
 * day), the signed config at launch, on every return to the foreground and every 60 s while the app is in use,
 * telemetry deltas every 15 minutes in use and when the app goes to the background, sent through WorkManager with
 * the issues, and recitation sharing (where Quran Lab's config offers it) with the SDK's sheets over the resumed
 * activity and an upload queue that survives process death and reboots.
 */
internal object AndroidPlatform {
    private val uploads = Executors.newSingleThreadExecutor { r -> Thread(r, "quranlab-uploads").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var ticker: Handler? = null
    private var started = 0
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var sessionOpen = false
    @Volatile private var client: TajweedClient? = null
    @Volatile private var accelerator: AndroidAccelerator? = null

    /** The activity in front, for the SDK's own sheets. */
    val resumedActivity: Activity? get() = resumed?.get()

    /** The application context of the process's client (the public `Microphone` reads the device's rate with it). */
    @Volatile var appContext: Context? = null
        private set

    internal fun storage(context: Context) = File(context.noBackupFilesDir, "quranlab")

    /** The client of `TajweedClient(context, key, options)`. */
    fun client(context: Context, key: String, options: TajweedClientOptions): TajweedClient {
        val app = context.applicationContext
        appContext = app
        val storage = options.storage ?: storage(app)
        val c = TajweedClient(key, options, platform(app, options, storage))
        saveOptions(app, key, options)
        return c
    }

    private fun platform(app: Context, options: TajweedClientOptions, storage: File): ClientPlatform {
        val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val soc = if (debug) DebugHooks.soc ?: DeviceSoc.id else DeviceSoc.id
        val network = AndroidNetwork(app)
        val microphone = AndroidMicrophone(app)
        return ClientPlatform(
            cloud = environment(app, options, storage, debug),
            soc = soc,
            network = if (debug && DebugHooks.unmetered) NetworkPolicy { true } else network,
            npuRunner = LiteRtRunner.Factory(app.applicationInfo.nativeLibraryDir),
            accelerator = environment(app, soc, debug && DebugHooks.scheduleNow),
            microphone = microphone,
            warmRate = microphone.nativeRate(),
            lifecycle = { c -> register(app, c) },
            sheets = { sharing, request -> Handler(Looper.getMainLooper()).post { show(sharing, request) } },
            extraObserver = { cloud -> thermal(app, cloud) },
            // the install records' key: an HMAC key in the Android Keystore (SPEC 15.2)
            installKeys = KeystoreInstallKey(),
        )
    }

    /**
     * The model layer alone, with no Client and no key: a model folder served at [baseUrl] (scripts/serve_model.py),
     * verified with [verifier], kept in [dir]. The device speed check app (bench-app, not published) measures the
     * engine and the accelerator through it.
     */
    internal fun modelClient(context: Context, baseUrl: String, verifier: ai.quranlab.tajweedformer.ManifestVerifier,
                             dir: File = File(context.noBackupFilesDir, "tajweedformer/v4.0")): ai.quranlab.tajweedformer.ModelClient {
        val app = context.applicationContext
        val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val soc = if (debug) DebugHooks.soc ?: DeviceSoc.id else DeviceSoc.id
        val store = ai.quranlab.tajweedformer.ModelStore(dir, baseUrl, verifier = verifier)
        return ai.quranlab.tajweedformer.ModelClient(store, ai.quranlab.tajweedformer.ModelClient.Platform(
            soc = soc,
            network = if (debug && DebugHooks.unmetered) NetworkPolicy { true } else AndroidNetwork(app),
            npuRunner = LiteRtRunner.Factory(app.applicationInfo.nativeLibraryDir),
            accelerator = environment(app, soc, debug && DebugHooks.scheduleNow),
        ))
    }

    /** Thermal changes for the telemetry: PowerManager's status (API 29+), throttled from MODERATE up. Returns what stops it. */
    private fun thermal(app: Context, cloud: ai.quranlab.tajweedformer.cloud.TajweedObserver): () -> Unit {
        if (Build.VERSION.SDK_INT < 29) return {}
        return try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val l = PowerManager.OnThermalStatusChangedListener { status -> cloud.onThermal(status >= PowerManager.THERMAL_STATUS_MODERATE) }
            pm.addThermalStatusListener(l)
            val stop: () -> Unit = { try { pm.removeThermalStatusListener(l) } catch (_: Exception) {} }
            stop
        } catch (_: Exception) {
            {}
        }
    }

    @Synchronized
    private fun environment(app: Context, soc: String?, now: Boolean): AndroidAccelerator {
        val e = accelerator
        // the debug overrides (DebugHooks, debuggable apps only) make a new environment when they change
        if (e != null && e.soc == soc && e.scheduleNow == now) return e
        return AndroidAccelerator(app, soc, now).also { accelerator = it }
    }

    private fun environment(context: Context, options: TajweedClientOptions, storage: File, debug: Boolean): CloudEnvironment {
        val pm = context.packageManager
        val info = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val label = try { context.applicationInfo.loadLabel(pm).toString() } catch (_: Exception) { null }
        val app = AppIdentity(options.appId ?: context.packageName, AppIdentity.cleanVersion(options.appVersion ?: info?.versionName), label?.take(80))
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
            storage = storage,
            app = app,
            device = device,
            contributorKeys = AndroidKeystoreContributorKeys(context),
            secretBox = KeystoreSecretBox(),
            // asked only when Quran Lab's signed config gives the Cloud project number (SPEC 15.4)
            integrity = PlayIntegrity.create(context),
            scheduler = WorkScheduler(context),
            inputRoute = { inputRoute(context) },
            locale = { context.resources.configuration.locales[0].toLanguageTag() },
            debugBuild = debug,
            unmetered = { !cm.isActiveNetworkMetered },
            log = { android.util.Log.w("QuranLab", it) },
        )
    }

    /** The kind of the current input (never a device name). */
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

    // ---- a process WorkManager started: the client the app made last time ----

    private fun saveOptions(context: Context, key: String, o: TajweedClientOptions) {
        try {
            val f = File(storage(context), "cloud/options.json")
            f.parentFile?.mkdirs()
            f.writeText(Json.write(linkedMapOf("key" to key, "analytics" to o.analytics.name.lowercase(), "audience" to o.audience.name.lowercase(),
                "app_id" to o.appId, "app_version" to o.appVersion, "storage" to o.storage?.path)))
        } catch (_: Exception) {
        }
    }

    /** The process's client, or (a worker in a process where the app made none) one made from the options saved last time. */
    internal fun restore(context: Context): TajweedClient? {
        TajweedClient.current?.let { return it }
        val o = try {
            @Suppress("UNCHECKED_CAST")
            Json.parse(File(storage(context), "cloud/options.json").readText()) as Map<String, Any?>
        } catch (_: Exception) { return null }
        val key = o["key"] as? String ?: return null
        return try {
            client(context, key, TajweedClientOptions(
                appId = o["app_id"] as? String, appVersion = o["app_version"] as? String, storage = (o["storage"] as? String)?.let(::File),
                analytics = if (o["analytics"] == "off") TajweedClientOptions.Analytics.OFF else TajweedClientOptions.Analytics.ON,
                audience = if (o["audience"] == "children") TajweedClientOptions.Audience.CHILDREN else TajweedClientOptions.Audience.GENERAL,
            ))
        } catch (_: Exception) {
            TajweedClient.current
        }
    }

    // ---- the SDK's own sheets ----

    private fun show(sharing: Sharing, r: Sharing.SheetRequest) {
        val a = resumedActivity ?: return
        if (sessionOpen || a.isFinishing) return
        SharingDialogs.show(a, sharing, r, SharingTheme.current)
    }

    // ---- the process's foreground and background ----

    /** Hooks the client to the activity lifecycle; returns what unhooks it. */
    private fun register(app: Context, c: TajweedClient): () -> Unit {
        client = c
        c.onSessionOpen = { open ->
            sessionOpen = open
            updateTicker()
        }
        (app as? Application)?.registerActivityLifecycleCallbacks(Lifecycle)
        return {
            (app as? Application)?.unregisterActivityLifecycleCallbacks(Lifecycle)
            if (client === c) client = null
            sessionOpen = false
            synchronized(this) { started = 0 }
            updateTicker()
        }
    }

    @Synchronized
    private fun updateTicker() {
        val inUse = (started > 0 || sessionOpen) && client != null
        if (inUse && ticker == null) {
            val t = HandlerThread("quranlab-config").apply { start() }
            val h = Handler(t.looper)
            ticker = h
            val tick = object : Runnable {
                override fun run() {
                    client?.tick()
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
            val first = synchronized(AndroidPlatform) { started++ == 0 }
            if (first) {
                val reopened = !changingConfig
                changingConfig = false
                // a cold start or back in the foreground: a launch counted, the day's registration, the config, deliveries due
                if (reopened) client?.foreground()
            }
            updateTicker()
        }

        override fun onActivityStopped(a: Activity) {
            val last = synchronized(AndroidPlatform) { --started <= 0 }
            if (last) {
                synchronized(AndroidPlatform) { started = 0 }
                if (a.isChangingConfigurations) {
                    changingConfig = true
                } else {
                    // in the background: a `listen` take ends, and a telemetry send point (sealed on disk, sent by WorkManager)
                    client?.background()
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
     * for telemetry and issues, one for the sharing outbox. While the app runs, the outbox is also drained at once
     * in-process.
     */
    internal class WorkScheduler(context: Context) : CloudScheduler {
        private val wm by lazy { WorkManager.getInstance(context.applicationContext) }

        override fun scheduleDelivery(cloud: QuranLabCloud) {
            try {
                wm.enqueueUniqueWork("quranlab.delivery", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ai.quranlab.tajweedformer.cloud.android.DeliveryWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                        .build())
            } catch (_: Exception) {
                // WorkManager not initialised in this process: the next foreground delivers
            }
        }

        override fun scheduleUploads(cloud: QuranLabCloud, atMs: Long?) {
            if (atMs == null) uploads.execute { try { cloud.sharing.outbox.drain() } catch (_: Exception) {} }
            val delay = atMs?.let { maxOf(0, it - System.currentTimeMillis()) } ?: 30_000L
            val net = if (cloud.options.uploadsOnUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            try {
                wm.enqueueUniqueWork("quranlab.outbox", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ai.quranlab.tajweedformer.cloud.android.OutboxWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build())
            } catch (_: Exception) {
            }
        }
    }
}
