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
import ai.quranlab.tajweedformer.cloud.DeviceModel
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.android.AndroidKeystoreContributorKeys
import ai.quranlab.tajweedformer.cloud.android.KeystoreSecretBox
import ai.quranlab.tajweedformer.cloud.android.PlayIntegrity
import ai.quranlab.tajweedformer.core.android.BootId
import ai.quranlab.tajweedformer.core.android.KeystoreIdentity
import ai.quranlab.tajweedformer.core.android.KeystoreStateVault
import ai.quranlab.tajweedformer.sharing.Sharing
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
 * private storage (`noBackupFilesDir/quranlab`), the Keystore (the install's keys of SDK 4.0, the state vault, the
 * sharing keys), Play Integrity (when the signed config gives its project number), the network state, the microphone,
 * and the process's foreground and background.
 *
 * From there everything is silent and in the background: registration once a UTC day (at the first opening of the
 * day), the signed config at launch, on every return to the foreground and every 60 s while the app is in use,
 * telemetry deltas every 15 minutes in use and when the app goes to the background, sent through WorkManager with
 * the issues, and recitation sharing (every learner is asked; the choice is theirs) with the SDK's sheets over the
 * resumed activity and an upload queue that survives process death and reboots.
 */
internal object AndroidPlatform {
    private val uploads = Executors.newSingleThreadExecutor { r -> Thread(r, "quranlab-uploads").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var ticker: Handler? = null
    private var started = 0
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var sessionOpen = false
    @Volatile private var client: TajweedClient? = null

    /**
     * The activity in front, for the SDK's own sheets and the microphone's permission dialog. SDK 4.0: also when the
     * Client was made after that activity resumed (a Flutter app, a Client made in `onResume` or later), which the
     * lifecycle callbacks never told: the activity is seeded when the Client is made ([seedForeground]), and looked
     * up once more when nothing was seeded.
     */
    val resumedActivity: Activity?
        get() = resumed?.get()?.takeIf { !it.isFinishing } ?: foundResumed()?.also { resumed = WeakReference(it) }

    /** The application context of the process's client (the public `Microphone` reads the device's rate with it). */
    @Volatile var appContext: Context? = null
        private set

    internal fun storage(context: Context) = File(context.noBackupFilesDir, "quranlab")

    /** The client of `TajweedClient(context, key, options)`. */
    fun client(context: Context, key: String, options: TajweedClientOptions): TajweedClient {
        val app = context.applicationContext
        appContext = app
        // before the lifecycle hooks: the activity that is in front already (3.1.5 learnt it only from callbacks, so
        // a Client made after the activity resumed had none: asks were lost and listen() was microphone_denied)
        val seeded = seedForeground(context)
        val storage = options.storage ?: storage(app)
        // made after an activity started: no callback will count this opening, so the making of the client does
        val c = TajweedClient(key, options, platform(app, options, storage, inForeground = seeded))
        saveOptions(app, key, options)
        return c
    }

    private fun platform(app: Context, options: TajweedClientOptions, storage: File, inForeground: Boolean): ClientPlatform {
        val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val network = AndroidNetwork(app)
        val microphone = AndroidMicrophone(app)
        return ClientPlatform(
            cloud = environment(app, options, storage, debug),
            soc = DebugHooks.soc ?: DeviceSoc.id,
            // R2: the vendor's LiteRT dispatch library: the app's native library folder when the library is extracted
            // there (Google's documented setup), else copied out of the app's APKs (AGP's default packaging keeps it
            // inside the APK, where LiteRT, which lists a folder, cannot find it)
            dispatch = { soc -> dispatchFolder(app, soc) },
            npuRuntimeMissing = { soc -> npuRuntimeMissing(soc) },
            // SDK 4.0: a transient accelerator verdict holds until an OS update
            osBuild = Build.FINGERPRINT ?: "",
            // the signed config's accel_deny matches this (3.1.6's incident reports' form)
            osBuildReport = "${Build.VERSION.RELEASE}/${Build.ID}/${Build.VERSION.INCREMENTAL}",
            network = if (debug && DebugHooks.unmetered) NetworkPolicy { true } else network,
            microphone = microphone,
            warmRate = microphone.nativeRate(),
            lifecycle = { c -> register(app, c) },
            inForegroundAtStart = inForeground,
            sheets = { sharing, request -> Handler(Looper.getMainLooper()).post { show(sharing, request) } },
            extraObserver = { cloud -> thermal(app, cloud) },
            // SDK 4.0: the install's keys and the Core's state vault in the Android Keystore
            identity = { dir -> KeystoreIdentity(app, dir) },
            stateVault = { dir -> KeystoreStateVault(dir) },
            bootId = BootId.read(app),
            // the large file in ranges (SDK 3.1.3); a debuggable app can ask for one stream per file, as 3.1.2 did
            modelRanges = if (debug && DebugHooks.singleStream) ai.quranlab.tajweedformer.RangePlan.SINGLE else ai.quranlab.tajweedformer.RangePlan.DEFAULT,
            // the download goes on when the app leaves or its process ends (SDK 3.1.3); a debuggable app can turn it off
            downloads = if (debug && !DebugHooks.backgroundDownloads) null else ModelDownloads(app),
        ).also { if (debug) DebugHooks.watchDownloads() }
    }

    /**
     * The dispatch library's folder for [soc] ([ai.quranlab.tajweedformer.core.DispatchLibrary.resolve]): the app's
     * `nativeLibraryDir`, its base APK and split APKs (an install-time feature module such as LiteRT's
     * `google_tensor_runtime`), the process's 64-bit ABI, and the SDK's folder in the code cache, which the system
     * clears when the app or the platform is updated.
     */
    internal fun dispatchFolder(app: Context, soc: String): ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution {
        val info = app.applicationInfo
        val apks = (listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())).map(::File)
        val abi = if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.firstOrNull() else null
        val native = info.nativeLibraryDir?.let(::File)
        // LiteRT is the app's to add (not a dependency of the AAR): without its runtime nothing is resolved or copied,
        // and the gate fetches no program
        val noRuntime = ai.quranlab.tajweedformer.core.DispatchLibrary.runtimeMissing(native, apks, abi)
        val r = if (noRuntime != null) ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution(null, noRuntime)
        else ai.quranlab.tajweedformer.core.DispatchLibrary.resolve(soc, native, apks, abi, File(app.codeCacheDir, "quranlab-dispatch"))
        android.util.Log.i("QuranLab", "NPU dispatch for $soc: " + (r.folder?.let { "${it.dir} (${it.id})" } ?: r.missing))
        return r
    }

    /**
     * Why this phone cannot run the NPU program of [soc], when that is certain. Google Tensor: LiteRT's NPU path needs
     * Android 12 (API 31) or later and the system's `libedgetpu_litert.so`, which some Pixel system images lack
     * (Firebase's Pixel 8 and 8a images on Android 15, Pixel 9 builds BP2A.250605): with the vendor library folder
     * readable and that library not in it, nothing is downloaded. An unreadable folder proves nothing.
     */
    internal fun npuRuntimeMissing(soc: String): String? {
        if (Build.VERSION.SDK_INT < 31) return "Android ${Build.VERSION.SDK_INT} is below LiteRT's NPU floor (31)"
        if (!soc.startsWith("tensor_")) return null
        val names = try { File("/vendor/lib64").list() } catch (_: Exception) { null } ?: return null
        if (names.isEmpty() || "libedgetpu_litert.so" in names) return null
        return if (File("/system/lib64/libedgetpu_litert.so").exists()) null else "no libedgetpu_litert.so in this system image"
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
            // SDK 4.0 (SHARING_40.md 19.2): sent only in a shared recording's start body
            model = DeviceModel.of(Build.MANUFACTURER, Build.MODEL),
            // contract 24.2: an error report's model code and the process's ABI (a 32-bit process on a 64-bit phone)
            modelCode = DeviceProfile.modelCode(Build.MODEL),
            abi = (if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).firstOrNull(),
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
            f.writeText(Json.write(linkedMapOf("key" to key,
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
        // a file saved by 3.x may also name an audience: 4.0 has none and leaves it unread
        return try {
            client(context, key, TajweedClientOptions(
                appId = o["app_id"] as? String, appVersion = o["app_version"] as? String, storage = (o["storage"] as? String)?.let(::File),
            ))
        } catch (_: Exception) {
            TajweedClient.current
        }
    }

    // ---- the activity in front when the Client is made ----

    /** The Activity [context] is, or wraps (a ContextWrapper chain), or null. */
    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * Seeds [resumed] (and one started activity, so the process counts as in the foreground) when the Client is made
     * after an activity resumed: the context it is made with when that is an activity, else the activity the system
     * shows as resumed. Callbacks that come later correct it.
     */
    internal fun seedForeground(context: Context): Boolean {
        val a = activityOf(context)?.takeIf { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } ?: foundResumed() ?: return false
        if (resumed?.get() == null) resumed = WeakReference(a)
        // true: this seed is the process's start in the foreground (no callback counted one), so the Client's making is
        // the app's opening (TajweedClient's launch counts it and registers the install for the day)
        return synchronized(this) { if (started == 0) { started = 1; true } else false }
    }

    /**
     * The resumed activity of this process as the system tracks it (ActivityThread's records: an unsupported
     * interface the platform allows to read; null where it is not there). Only a fallback for an activity no callback
     * named: the callbacks win as soon as one comes.
     */
    internal fun foundResumed(): Activity? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        val f = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = (f.get(thread) as? Map<*, *>)?.values ?: emptyList<Any?>()
        records.firstNotNullOfOrNull { r ->
            if (r == null) return@firstNotNullOfOrNull null
            val rc = r.javaClass
            val paused = rc.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(r)
            val a = rc.getDeclaredField("activity").apply { isAccessible = true }.get(r) as? Activity
            a?.takeIf { !paused && !it.isFinishing }
        }
    } catch (_: Throwable) {
        null
    }

    // ---- the SDK's own sheets ----

    private fun show(sharing: Sharing, r: Sharing.SheetRequest) {
        val a = resumedActivity
        // nothing in front, or a take open: an ask is not shown, and counts nothing (SHARE_ASK_40 20.6)
        if (a == null || sessionOpen || a.isFinishing) {
            sharing.notShown(r)
            return
        }
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
