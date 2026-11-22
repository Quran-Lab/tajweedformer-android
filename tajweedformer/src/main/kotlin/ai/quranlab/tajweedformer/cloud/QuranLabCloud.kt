package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ManifestVerifier
import ai.quranlab.tajweedformer.sharing.ContributorKeyStore
import ai.quranlab.tajweedformer.sharing.SecretBox
import ai.quranlab.tajweedformer.sharing.Sharing
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The developer's choices for the platform link (all optional but the key). */
internal data class CloudOptions @JvmOverloads constructor(
    /** The app's developer key, `qlk_v1_...`, from sdk.quranlab.ai. Null: offline only, nothing is sent. */
    val key: String?,
    /** Issue reports (no free text). */
    val issueReports: Boolean = true,
    /**
     * Ignored since 2.0.1: every learner is asked (the SDK's own sheet, then whole sessions uploaded for a learner
     * who said "Share"); the signed config's `flags.contributions` is Quran Lab's incident switch for every app.
     */
    val contributions: Boolean = false,
    val apiUrl: String = QuranLabApi.DEFAULT_API,
    /** The Ed25519 keys the signed config may be signed with (raw 32 bytes). */
    val trustedKeys: List<ByteArray> = listOf(ManifestVerifier.QURAN_LAB_PUBLIC_KEY),
    /** SDK 4.0.1: the hosts of each role. Null: the built-in sets ([Endpoints.of] for a test
     *  build's [apiUrl]); a test build passes its stand-ins ([Endpoints.mapped]). */
    val endpoints: Endpoints? = null,
)

/** What the cloud side needs from the platform (Android: `QuranLabAndroid` builds it). */
internal class CloudEnvironment(
    /** The SDK's private storage (Android: `noBackupFilesDir/quranlab`). */
    val storage: File,
    val app: AppIdentity,
    val device: DeviceProfile,
    val contributorKeys: ContributorKeyStore,
    val secretBox: SecretBox,
    val integrity: IntegrityProvider? = null,
    /** Background work: telemetry and issue delivery, the outbox. Android: WorkManager. */
    val scheduler: CloudScheduler = CloudScheduler.Inline,
    val http: HttpTransport = UrlConnectionTransport(),
    val clock: () -> Long = System::currentTimeMillis,
    /** The current input route, `built_in|wired|usb|bluetooth|car|other|unknown`. */
    val inputRoute: () -> String = { "unknown" },
    /** The app's locale, for the copy deck. */
    val locale: () -> String = { java.util.Locale.getDefault().toLanguageTag() },
    /** A debuggable build. */
    val debugBuild: Boolean = false,
    /** The SDK's warnings for the developer (Android: `Log.w`); only debuggable builds write any. */
    val log: (String) -> Unit = { System.err.println("W/QuranLab: $it") },
)

/** Where background work runs. */
internal interface CloudScheduler {
    /** Delivery of telemetry reports and issues is due. */
    fun scheduleDelivery(cloud: QuranLabCloud)
    /** The sharing outbox has something to send (now, or at [atMs]). */
    fun scheduleUploads(cloud: QuranLabCloud, atMs: Long? = null)

    /** Runs the work on the calling thread (tests). */
    object Inline : CloudScheduler {
        override fun scheduleDelivery(cloud: QuranLabCloud) { cloud.deliver() }
        override fun scheduleUploads(cloud: QuranLabCloud, atMs: Long?) { if (atMs == null) cloud.sharing.outbox.drain() }
    }

    /** Nothing runs until asked (tests drive it). */
    object Manual : CloudScheduler {
        override fun scheduleDelivery(cloud: QuranLabCloud) {}
        override fun scheduleUploads(cloud: QuranLabCloud, atMs: Long?) {}
    }
}

/**
 * Everything the SDK does with Quran Lab (sdk.quranlab.ai, api.quranlab.ai) for one app (docs/platform-client.md): the token, the
 * daily registration, the signed config (and its `tajweed` block and flags), telemetry v2 deltas, issue
 * reports, and recitation sharing. It is the [TajweedObserver] the recognizer reports to. Nothing
 * here sits in the recognition path: network work runs on [CloudEnvironment.scheduler]'s threads.
 *
 * The app's life (Android: `QuranLabAndroid` calls these): [launch] once per process, [foreground] at a
 * cold start and every return to the foreground, [tick] while the app is in use, [background] when it
 * goes to the background. Telemetry is sealed into a delta at launch, every 15 minutes in use
 * ([Telemetry.SEND_INTERVAL_MS]) and at the background, and sent oldest first.
 *
 * Blocking methods ([launch], [foreground], [refreshConfig], [deliver]) are for background threads.
 */
internal class QuranLabCloud(val options: CloudOptions, val env: CloudEnvironment) : TajweedObserver {
    val key: QuranLabKey? = QuranLabKey.parse(options.key)
    private val cloudDir = File(env.storage, "cloud")

    /** The refusal that stops a model on this device, remembered across launches (SPEC 15.3). */
    val refusals = Refusals(cloudDir)

    /** Every API answer: a 401 or a 403 whose body carries a stop reason is remembered as a refusal of the API. A
     *  stop reason under any other status is not a stop (SPEC 15.3). */
    /** The hosts of each role and their failover order, for the API and the model requests. */
    val endpoints: Endpoints = options.endpoints ?: Endpoints.of(options.apiUrl, env.clock)

    /** 28.11 (SDK 4.0.2): every route's Retry-After hold and the optional traffic's rows, kept across restarts. */
    internal val holds = RouteHolds(File(File(env.storage, "cloud"), "holds.json"), env.clock)

    val api = QuranLabApi(options.apiUrl, env.http, endpoints, holds) { r ->
        if (r.status == 401 || r.status == 403) r.error.reason?.let { if (ModelGate.stops(r.status, it)) refusals.stop(it, Refusals.API) }
    }
    val config = ConfigStore(cloudDir, options.trustedKeys, env.clock)
    val tokens: TokenProvider? = key?.let {
        TokenProvider(api, it, env.app, cloudDir, env.integrity, env.clock,
            projectNumber = { currentConfig?.flags?.playIntegrityProjectNumber },
            issued = { refusals.apiAccepted(freshConfig = false) })
    }
    val telemetry = Telemetry(File(env.storage, "telemetry"), env.clock)
    val issues = IssueQueue(cloudDir, env.clock)

    /** Error reports: every failure that reaches the app, and the silent ones of 24.1's list. */
    val errors = ErrorReporter(cloudDir)
    val sharing = Sharing(this)

    /** The model a report names (24.2 `revision` and `build`): the version and build in use or being fetched. */
    @Volatile var reportModel: Pair<String?, String?>? = null

    /** The app is in the foreground (24.6: the 30 s send point runs only then). The desktop JVM's launch says so. */
    @Volatile var inForeground = false

    /** The wait between an occurrence that made a new report and its send point (24.6: 30 s). Tests shorten it. */
    @Volatile var errorSendDelayMs = ERROR_SEND_DELAY_MS
    private val errorTimerSet = AtomicBoolean(false)
    private val errorTimer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tajweedformer-errors").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** A refusal stopped network calls until the next launch (revoked key, blocked app, SDK too old). */
    private val stopped = AtomicBoolean(false)
    @Volatile var stopReason: String? = null
        private set
    @Volatile private var telemetryRetryAt = 0L
    private var telemetryFailures = 0

    /** 28.11 item 4: telemetry's `POST /v1/events` after a 429 (a return to the foreground does not end it). */
    internal val eventsSlowDown = SlowDown(env.clock, holds, "events")

    /** 28.11 item 4: the issue and error reports' `POST /v1/issues` after a 429. */
    internal val issuesSlowDown = SlowDown(env.clock, holds, "issues")
    @Volatile private var lastSealAt = 0L

    /** SDK 4.0: the recited count of each finished take (SPEC 17.1, 17.6); the Client gives it the Core. */
    @Volatile var recited: RecitedCount? = null

    init {
        // 28.2: the hosts a config names are used while that config is in force, never after
        endpoints.configured = { config.current?.raw?.get("endpoints") }
        // 28.7: a request answered on another host after its first host was unreachable, one silent report per stage
        endpoints.onFailover = { f ->
            f.stage?.let { stage -> silent(ErrorCause(stage, "failover", f.status, errorCode = f.reason, hostRole = f.role, fallback = true)) }
        }
        // 34.6: the model requests' counts per `models` host, with the day's telemetry (the background job's too: its
        // Client is made the same way)
        endpoints.meter = telemetry
        // 28.11 items 2 and 3: a model file's 429, or 503 with Retry-After, holds its path on its host, across restarts
        endpoints.holds = holds
        // SDK 4.0 (owner, 11-16): anonymous analytics are always on; the signed config's flags.analytics is the only switch
        telemetry.enabled = config.current?.flags?.analytics != false
        telemetry.metric = { g -> currentConfig?.flags?.metric(g) != false }
        // 24.5: the signed config's flags.issues false turns error reports off (4.0 has no app option for them)
        if (!options.issueReports || config.current?.flags?.issues == false) errors.off()
        // 24.1: a telemetry report kept 7 days without being accepted is dropped, and that is reported
        telemetry.expired = { silent(ErrorCause("events", "expired")) }
        // incidents made before the app configured Quran Lab join the queue (sent with the key once online)
        val spool = IncidentSpool(cloudDir, env.clock)
        try {
            if (options.issueReports && config.current?.flags?.issues != false) issues.adopt(spool, env.app, env.device) else spool.clear()
        } catch (_: Exception) {
        }
    }

    /** The bucket 0..99, drawn once per install (not an identifier). */
    val bucket: Int by lazy {
        val f = File(cloudDir, "bucket")
        f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.takeIf { it in 0..99 } ?: run {
            val b = java.security.SecureRandom().nextInt(100)
            cloudDir.mkdirs()
            f.writeText(b.toString())
            b
        }
    }

    /** The current verified config, or null. */
    val currentConfig: RemoteConfig? get() = config.current

    /** The signed config's `inference.context` (the portal's Response time); applies when the next model is opened. */
    val inferenceContext: String? get() = currentConfig?.inferenceContext

    /** The app's pin (a model version), sent with every config request; null: Quran Lab's choice. */
    @Volatile var modelVersion: String? = null

    /** The last refusal or failure of a config request, for `diagnose()`: a server code, `offline`, or `signature_invalid`. */
    @Volatile var lastConfigError: String? = null
        private set

    /** The time of the last config answer the server gave (200 or 304), ms; 0 when none in this process. */
    @Volatile var lastConfigAt: Long = 0
        private set

    val networkStopped: Boolean get() = stopped.get()

    private fun stop(why: String) {
        stopReason = why
        stopped.set(true)
    }

    /** The SDK version the server's `sdk_too_old` answer asked for (`min_sdk`), when it named one. */
    @Volatile var sdkRequired: String? = null
        private set

    /** Handles a refusal common to every route. Returns true when the caller should give up this launch. */
    private fun refused(e: CloudException, route: String): Boolean {
        val a = e.api ?: return false
        if (a.code == "sdk_too_old") a.minSdk?.takeIf { ai.quranlab.tajweedformer.ModelManifest.isVersion(it) }?.let { sdkRequired = it }
        when (a.action) {
            // what is remembered is what the app was refused for: the body's reason when it has a known one
            ErrorAction.STOP_UNTIL_NEXT_LAUNCH, ErrorAction.STOP_THIS_LAUNCH, ErrorAction.STOP -> { stop(a.refusal); return true }
            ErrorAction.DROP_AND_REPORT -> report("other", "bad_request:$route")
            else -> {}
        }
        return false
    }

    /** A call with the token, a new token once after 401 `invalid_token` (never when it carries a stop reason: the key
     *  was revoked after the token was issued, SPEC 15.3). */
    private fun <T> authed(route: String, body: (String) -> T): T {
        val t = tokens ?: throw CloudException(ApiError(401, "unauthorized"), "no key")
        if (stopped.get()) throw CloudException(ApiError(403, stopReason ?: "stopped"), "network stopped this launch")
        return try {
            body(t.token().token)
        } catch (e: CloudException) {
            if (e.api?.action != ErrorAction.RENEW_TOKEN || ModelGate.stops(e.api.reason)) { refused(e, route); throw e }
            t.invalidate()
            try { body(t.token().token) } catch (e2: CloudException) { refused(e2, route); throw e2 }
        }
    }

    /**
     * The token of a model request (SPEC 15.4): attested where the signed config gives Quran Lab's Play Integrity
     * project number and the platform has Play Integrity, unattested otherwise and whenever attestation fails (the
     * rules of PLATFORM_CLIENT.md section 2). While the network is stopped for this launch only a fresh cached token
     * is used. Throws the catalogue error of a refusal (a stop reason's when the answer carries one), or
     * `download_failed` with the IOException as its cause when there is no connection.
     */
    fun modelToken(): String {
        val t = tokens ?: throw ai.quranlab.tajweedformer.RecognizerException.InvalidKey()
        if (stopped.get()) {
            t.cachedFresh()?.let { return it.token }
            throw refusals.current?.let { ModelGate.error(it.reason, env.app.bundleId) }
                ?: ai.quranlab.tajweedformer.TajweedError.ofServerCode(stopReason, sdkRequired, refused = true, appId = env.app.bundleId)
                ?: ai.quranlab.tajweedformer.RecognizerException.InvalidKey()
        }
        return try {
            if (t.canAttest()) {
                try {
                    return t.token(attested = true).token
                } catch (e: CloudException) {
                    // no attested token can be had: continue unattested
                    if (e.api?.code != "attestation_required") throw e
                }
            }
            t.token(attested = false).token
        } catch (e: CloudException) {
            refused(e, "attest")
            val a = e.api
            throw when {
                a == null -> ai.quranlab.tajweedformer.RecognizerException.DownloadFailed(ai.quranlab.tajweedformer.ModelManifest.FILE_NAME,
                    "network: ${e.message}", e.cause as? java.io.IOException ?: java.io.IOException(e.message))
                ModelGate.stops(a.status, a.reason) -> ModelGate.error(a.reason!!, env.app.bundleId)
                else -> ai.quranlab.tajweedformer.TajweedError.ofCloud(e)
            }
        }
    }

    // ---- the app's life ----

    /**
     * The launch sequence (call once per process, off the main thread): with [foreground] (the app is
     * being opened, not woken for background work) a launch is counted and the install registered (once
     * a UTC day); then the signed config, a telemetry send point (counts left by earlier runs included)
     * and the sharing outbox. Each step is independent: one failing (offline) does not stop the others.
     * Android calls it with `foreground = false` and counts the opening in [foreground].
     */
    @JvmOverloads
    fun launch(modelRevision: String? = null, foreground: Boolean = true) {
        // a new app session for the share ask (SHARE_ASK_40 20.2); the first launch with 4.0 stores its time
        try { sharing.launched() } catch (_: Exception) {}
        if (foreground) {
            inForeground = true
            telemetry.launch()
            register(modelRevision)
        }
        refreshConfigIfOlder()
        sendPoint()
        if (sharing.outbox.hasWork()) env.scheduler.scheduleUploads(this)
    }

    /**
     * A cold start or a return to the foreground (off the main thread): one launch counted, the daily
     * registration if this is the day's first, a fresh telemetry backoff, and the 15-minute timer restarted.
     */
    @JvmOverloads
    fun foreground(modelRevision: String? = null) {
        inForeground = true
        sharing.foreground()
        telemetry.launch()
        telemetryFailures = 0
        telemetryRetryAt = 0
        lastSealAt = env.clock()
        register(modelRevision)
    }

    /** While the app is in use (Android: every minute): a send point once 15 minutes passed since the last. */
    fun tick() {
        if (env.clock() - lastSealAt >= Telemetry.SEND_INTERVAL_MS) sendPoint()
    }

    /**
     * The app went to the background: a send point, and a shared recording that waits for the next take closes
     * (2.0.3; a take that is running keeps its recording until it ends).
     */
    fun background() {
        inForeground = false
        sharing.background()
        // the takes waiting for the app's report are counted (17.6)
        try { recited?.flush() } catch (_: Exception) {}
        sendPoint()
    }

    /** What was counted since the last send point is sealed (one report per UTC day) and delivery is scheduled. */
    fun sendPoint() {
        lastSealAt = env.clock()
        telemetry.seal(TelemetryContext(env.app, env.device))
        env.scheduler.scheduleDelivery(this)
    }

    private val installedMarker get() = File(cloudDir, "installed")
    private val pendingNew get() = File(cloudDir, "register-pending")
    private val registerDay get() = File(cloudDir, "register-day")

    /** The UTC day of the last accepted registration. */
    val registeredOn: String? get() = try { registerDay.takeIf { it.isFile }?.readText()?.trim() } catch (_: Exception) { null }

    /**
     * `POST /v1/register`, at most once per UTC day per install (a day stamp written only after a 200), so the
     * server counts daily active installs. The new-install flag survives a failed attempt.
     */
    @Synchronized
    fun register(modelRevision: String? = null): Boolean {
        if (key == null || stopped.get()) return false
        val today = Day.of(env.clock())
        if (registeredOn == today) return true
        cloudDir.mkdirs()
        val isNew = !installedMarker.exists()
        if (isNew) { installedMarker.writeText(""); pendingNew.writeText("1") }
        val newInstall = pendingNew.exists()
        return try {
            authed("register") { api.register(Credential.Token(it), env.app, modelRevision, newInstall) }
            refusals.apiAccepted(freshConfig = false)
            pendingNew.delete()
            try { registerDay.writeText(today) } catch (_: Exception) {}
            true
        } catch (e: CloudException) {
            if (!e.retryable) pendingNew.delete()
            // 24.1: the launch went on without its registration (the app's token, attest, when that was what failed)
            silentCall(e, "register")
            false
        }
    }

    /** The cause of the last config request that failed (24.3), or null after one that was answered and verified. */
    @Volatile var lastConfigCause: ErrorCause? = null
        private set

    /**
     * Fetches and verifies the config (conditional on its ETag); the last good one stays on failure. A failure is a silent
     * error report (24.1) unless [silentReport] is false: a call of the app that needs the config reports it, once, when
     * it fails because of it.
     */
    @JvmOverloads
    @Synchronized
    fun refreshConfig(silentReport: Boolean = true): RemoteConfig? {
        if (key == null || stopped.get()) return config.current
        var answer: HttpResponse? = null
        return try {
            val pin = modelVersion
            // a pin changes the answer: never conditional on the ETag of an answer made for another pin
            val etag = if (pin == pinOfStored()) config.etagToSend() else null
            // 28.3: a config that does not verify is no answer of that host, and the next one is asked
            val verify: (HttpResponse) -> Unit = { a ->
                try { RemoteConfig.verify(a.body, a.header("x-qlab-signature"), a.header("x-qlab-key-id"), config.trustedKeys, env.clock() / 1000) }
                catch (_: ConfigRejected) { throw NotTaken() }
            }
            val r = authed("config") { api.config(Credential.Token(it), bucket, env.app, etag, pin, verify) }
            answer = r
            val c = config.apply(r)
            // a verified config (or the server's 304 for the one in force) accepts the key
            refusals.apiAccepted(freshConfig = true)
            rememberPin(pin)
            lastConfigError = null
            lastConfigCause = null
            lastConfigAt = env.clock()
            try { cloudDir.mkdirs(); configAtFile.writeText(lastConfigAt.toString()) } catch (_: Exception) {}
            applyFlags(c)
            noteSdkLatest(c)
            c?.pinUnavailable?.let { v -> if (env.debugBuild) try { env.log("model version $v is not available for this SDK: using ${c.recommendedVersion}") } catch (_: Exception) {} }
            c
        } catch (e: ConfigRejected) {
            lastConfigError = "signature_invalid"
            lastConfigCause = ErrorCause("config", e.reason, hostRole = QuranLabApi.ROLE, fallback = answer?.fallback ?: false)
            report("signature_invalid", "config:" + (e.message ?: "rejected").replace(' ', '_'))
            if (silentReport) silent(lastConfigCause!!)
            config.current.also { noteSdkLatest(it) }
        } catch (e: CloudException) {
            lastConfigError = e.api?.refusal ?: if (e.notPermitted) NOT_PERMITTED else "offline"
            lastConfigCause = ErrorCause.ofCloud(e, ErrorRules.stageOfPath(e.path) ?: "config")
            if (silentReport) silentCall(e, "config")
            config.current.also { noteSdkLatest(it) }
        }
    }

    /** When the config in force was last answered (ms), kept across restarts for [refreshConfigIfOlder]. */
    private val configAtFile get() = File(cloudDir, "config.at")

    /**
     * Contract 28.11 item 5 (SDK 4.0.2): the config is asked at launch, when the app comes to the foreground and at a
     * session's start, and only when the one in force is older than [CONFIG_MAX_AGE_MS] or there is none ([configFresh]).
     * 4.0.1's Android asked it at every minute tick while the app was in use.
     */
    fun refreshConfigIfOlder(): RemoteConfig? = if (configFresh()) config.current else refreshConfig()

    /** A verified config is in force and was answered less than [CONFIG_MAX_AGE_MS] ago (in this process or an earlier
     *  one; a time ahead of the clock, a clock that moved back, is no age). */
    fun configFresh(): Boolean {
        val at = maxOf(lastConfigAt, try { configAtFile.readText().trim().toLong() } catch (_: Exception) { 0L })
        val now = env.clock()
        return config.current != null && at <= now && now - at < CONFIG_MAX_AGE_MS
    }

    private val pinFile get() = File(cloudDir, "config.pin")

    /** The pin the stored config was asked with ("" for none). */
    private fun pinOfStored(): String? = try { pinFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    /** Whether the config in force was asked with [pin] (null: with none). A config asked with another pin says
     *  nothing about this one: it has to be asked again. */
    fun configAskedWith(pin: String?): Boolean = pin == pinOfStored()

    private fun rememberPin(pin: String?) {
        try {
            if (pin == null) pinFile.delete() else { cloudDir.mkdirs(); pinFile.writeText(pin) }
        } catch (_: Exception) {
        }
    }

    private val sdkLatestLogged = AtomicBoolean(false)

    /**
     * A debuggable build whose SDK is older than the signed config's `sdk_latest` gets one warning per launch (this
     * cloud is the process's): `Quran Lab SDK X is available (severity), you have Y: URL`. Release builds stay silent;
     * a missing or malformed field is ignored.
     */
    private fun noteSdkLatest(c: RemoteConfig?) {
        if (!env.debugBuild) return
        val l = c?.sdkLatest ?: return
        val mine = ai.quranlab.tajweedformer.SdkInfo.VERSION
        if (!l.newerThan(mine)) return
        if (!sdkLatestLogged.compareAndSet(false, true)) return
        try { env.log("Quran Lab SDK ${l.version} is available (${l.severity}), you have $mine: ${l.url}") } catch (_: Exception) {}
    }

    private fun applyFlags(c: RemoteConfig?) {
        val f = c?.flags ?: return
        telemetry.enabled = f.analytics
        if (!telemetry.enabled) try { recited?.clear() } catch (_: Exception) {}
        if (!f.issues) issues.clear()
        if (!f.issues || !options.issueReports) errors.off() else errors.enabled = true
        if (f.contributions == false) sharing.configTurnedOff()
    }

    /** The backend the deny-list forces on this install for [revision] (`exact`, say), or null. */
    fun forcedBackend(revision: String?): String? =
        currentConfig?.denyRule(env.app.platform, env.device.osVersion, env.device.deviceClass, ai.quranlab.tajweedformer.SdkInfo.VERSION,
            revision?.let { ai.quranlab.tajweedformer.ModelIds.version(it) })
            ?.get("backend_forced") as? String

    private val accelDenyLogged = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Whether the signed config's `accel_deny` keeps [accelerator] (`npu:<soc>`) off on this install for [revision]:
     * the matching rule's `reason` (or "accel_deny"), else null. Read at every call from the verified config in force.
     */
    fun accelDenied(accelerator: String, revision: String, osBuild: String?, deviceModel: String?, chip: String?, delegate: String?): String? {
        val c = currentConfig ?: return null
        val v = RemoteConfig.AccelValues(env.app.platform, env.device.osVersion, env.device.deviceClass, ai.quranlab.tajweedformer.SdkInfo.VERSION,
            ai.quranlab.tajweedformer.ModelIds.version(revision), accelerator, osBuild, deviceModel?.replace(' ', '_'), chip, delegate)
        val r = c.accelDenyRule(v, env.clock() / 1000) ?: return null
        val reason = (r["reason"] as? String)?.takeIf { it.isNotBlank() } ?: "accel_deny"
        if (env.debugBuild && accelDenyLogged.add("$accelerator|$revision|$reason")) {
            try { env.log("accel_deny keeps $accelerator off for $revision: $reason") } catch (_: Exception) {}
        }
        return reason
    }

    // ---- delivery: telemetry and issues ----

    /** Sends the sealed telemetry reports (oldest first) and queued issues. Returns (reports accepted, issues accepted). */
    @Synchronized
    fun deliver(): Pair<Int, Int> {
        if (key == null || stopped.get()) return 0 to 0
        var reports = 0
        var sent = 0
        val flags = currentConfig?.flags
        if (telemetry.enabled && flags?.analytics != false && env.clock() >= telemetryRetryAt && eventsSlowDown.allowed()) reports = sendTelemetry()
        // 28.10: nothing to /v1/issues before its Retry-After (or the wait of 429s in a row) has passed
        if (options.issueReports && flags?.issues != false && issuesSlowDown.allowed()) sent = sendIssues()
        // 24.6: after the telemetry reports, each queued error report
        if (options.issueReports && flags?.issues != false && issuesSlowDown.allowed()) errorsSent += sendErrors()
        return reports to sent
    }

    /** Error reports accepted by the server in this process (tests). */
    @Volatile var errorsSent = 0
        private set

    /**
     * Each sealed report, its exact bytes (a retry sends the same `report_id`, which the server counts once):
     * 202 (duplicate or not) deletes it; 400 drops it and reports the SDK bug; 413 drops it; 401 renews the
     * token and retries; 429, 5xx, 503 and network errors keep it and back off.
     */
    private fun sendTelemetry(): Int {
        var n = 0
        for (f in telemetry.pendingReports()) {
            val body = try { f.readBytes() } catch (_: Exception) { telemetry.dropReport(f); continue }
            try {
                val r = authed("events") { api.events(it, body).also { r -> if (r.status == 401) throw CloudException(r.error, "events") } }
                // 28.10: a 429 waits its Retry-After (300 s when none), doubling in a row; a 503's Retry-After is honoured
                eventsSlowDown.answered(r.status, r.retryAfter)
                when {
                    r.status == 202 || r.ok -> { telemetry.dropReport(f); n++; telemetryFailures = 0 }
                    r.status == 400 -> {
                        telemetry.dropReport(f)
                        report("other", "bad_request:events")
                        silent(ErrorCause("events", "http", 400, errorCode = ErrorRules.serverCode(r.error)))
                    }
                    r.status == 413 -> {
                        telemetry.dropReport(f)
                        silent(ErrorCause("events", "http", 413, errorCode = ErrorRules.serverCode(r.error)))
                    }
                    r.status == 403 -> { refused(CloudException(r.error, "events"), "events"); return n }
                    // the 429's wait is the route's own (28.10), not the minute and ten of a failure
                    r.status == 429 -> return n
                    else -> { backoffTelemetry(); return n }
                }
            } catch (e: CloudException) {
                if (e.api == null || e.retryable) backoffTelemetry()
                return n
            }
        }
        return n
    }

    /** 1 min, 10 min, then the next launch or return to the foreground. */
    private fun backoffTelemetry() {
        telemetryFailures++
        telemetryRetryAt = env.clock() + when (telemetryFailures) {
            1 -> 60_000L
            2 -> 600_000L
            else -> Long.MAX_VALUE / 2
        }
    }

    private fun sendIssues(): Int {
        var n = 0
        while (true) {
            val b = issues.firstToSend() ?: break
            try {
                val r = authed("issues") { api.issue(it, b).also { r -> if (r.status == 401) throw CloudException(r.error, "issues") } }
                issuesSlowDown.answered(r.status, r.retryAfter)
                if (r.ok || r.status == 400 || r.status == 413) { issues.removeFirst(); if (r.ok) n++ } else break
            } catch (e: CloudException) {
                break
            }
        }
        return n
    }

    // ---- error reports ----

    /** 24.1 item 1: a failure that reached the app, with the stage of its cause. */
    fun errorReachedApp(e: ai.quranlab.tajweedformer.TajweedError) {
        occur(ErrorCause.of(e), e.code)
    }

    /**
     * 24.1 item 2: a failure the SDK went on from without telling the app: code `none` (a call of the app that fails
     * on the same cause is its own report, with its code, 24.1). A plain network failure is not reported (24.1).
     */
    fun silent(cause: ErrorCause) {
        occur(cause, "none")
    }

    /** A background call of the launch that failed (24.1): its stage is the request's (an attest under a register is
     *  `attest`). */
    private fun silentCall(e: CloudException, stage: String) {
        silent(ErrorCause.ofCloud(e, ErrorRules.stageOfPath(e.path) ?: stage))
    }

    private fun occur(c: ErrorCause, code: String) {
        try {
            val firstLaunch = try { sharing.firstLaunch } catch (_: Exception) { false }
            val made = errors.occur(Day.of(env.clock()), c.stage, c.reason, code, c.httpStatus, firstLaunch, snapshot(c))
            if (made) armErrorTimer()
        } catch (_: Exception) {
        }
    }

    /** The occurrence's fields (24.2): the error's class and code, the retries, the model, and the device's snapshot. */
    private fun snapshot(c: ErrorCause): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>()
        c.errorName?.let { d["error_name"] = it }
        c.errorCode?.let { d["error_code"] = it }
        c.retries?.let { d["retries"] = it }
        // 28.7: every report of a request to Quran Lab's hosts names its role and whether another host was asked
        (c.hostRole ?: ErrorRules.roleOfStage(c.stage, c.reason))?.let { role ->
            d["host_role"] = role
            d["fallback"] = c.fallback ?: false
        }
        val (version, build) = reportModel ?: currentConfig?.let { it.recommendedVersion to it.recommendedRevision } ?: (null to null)
        version?.let { d["revision"] = it }
        build?.let { d["build"] = it }
        env.device.modelCode?.let { d["device_model"] = it }
        env.device.ramTier?.let { d["ram_tier"] = it }
        env.device.abi?.let { d["abi"] = it }
        try { if (env.storage.isDirectory) d["storage_free"] = ErrorRules.storageFree(env.storage.usableSpace) } catch (_: Exception) {}
        return d
    }

    /** 24.6: one timer for the SDK, 30 s after an occurrence that made a new report, while the app is in the foreground. */
    private fun armErrorTimer() {
        if (!inForeground || key == null || !errorTimerSet.compareAndSet(false, true)) return
        try {
            errorTimer.schedule({
                errorTimerSet.set(false)
                if (inForeground) try { env.scheduler.scheduleDelivery(this) } catch (_: Exception) {}
            }, errorSendDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            errorTimerSet.set(false)
        }
    }

    /**
     * 24.6: each queued report, oldest first, one request each. 202 sent; 400 or 413 dropped (never reported: that would
     * loop); 401 `invalid_token` renewed once by [authed]; 429, 5xx, no answer kept, and this send point stops.
     */
    private fun sendErrors(): Int {
        if (!errors.enabled) return 0
        var n = 0
        errors.send(Day.of(env.clock())) { r ->
            val body = errors.body(r, env.app, env.device, ai.quranlab.tajweedformer.SdkInfo.VERSION)
            try {
                val a = authed("issues") { api.issue(it, body).also { x -> if (x.status == 401) throw CloudException(x.error, "issues") } }
                issuesSlowDown.answered(a.status, a.retryAfter)
                if (a.ok) { n++; 202 } else a.status
            } catch (_: Exception) {
                null
            }
        }
        return n
    }

    /** Queues an issue report (no free text) and schedules delivery. */
    fun report(kind: String, code: String, error: Throwable? = null, backend: String? = null, revision: String? = null) {
        if (!options.issueReports || currentConfig?.flags?.issues == false) return
        if (issues.add(kind, code, IssueQueue.frames(error), env.app, env.device, backend, revision)) env.scheduler.scheduleDelivery(this)
    }


    // ---- TajweedObserver ----




    override fun onModelLoad(e: TajweedObserver.ModelLoad) = telemetry.modelLoad(e)
    override fun onSelfTest(revision: String, context: String, backend: String, passed: Boolean, build: String) {
        telemetry.selfTest(revision, context, backend, passed, build)
        // the code stays `self_test:npu` (one issue group per backend kind, as before); the report's backend names the SoC
        if (!passed) report("self_test_failed", "self_test:${backend.substringBefore(':')}", backend = backend, revision = revision)
    }
    override fun onFallback(revision: String, context: String, from: String, reason: TajweedObserver.FallbackReason, build: String) {
        telemetry.fallback(revision, context, from, reason, build)
    }
    override fun onDownload(e: TajweedObserver.Download) = telemetry.download(e)
    override fun onSessionStart(e: TajweedObserver.SessionStart) {
        telemetry.sessionStart(e)
        sharing.sessionStarted(e)
    }
    override fun onAudio(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) = sharing.audio(samples, offset, length, sampleRate)
    override fun onChunk(computeMs: Double, audioMs: Double) = telemetry.chunk(computeMs, audioMs)
    override fun onTokens(margins: FloatArray?, confidences: FloatArray?) = telemetry.tokens(margins, confidences)

    override fun onThermal(throttled: Boolean) = telemetry.thermal(throttled)
    override fun onSessionEnd(e: TajweedObserver.SessionEnd) {
        telemetry.sessionEnd(e)
        sharing.sessionEnded(e)
        // a send point when 15 minutes passed (hosts without a timer of their own)
        if (lastSealAt != 0L) tick()
    }
    override fun onIssue(kind: String, code: String, error: Throwable?) = report(kind, code, error)

    companion object {
        @Volatile private var shared: QuranLabCloud? = null

        /** The cloud of this process (set by `QuranLabAndroid.configure`). */
        @JvmStatic val current: QuranLabCloud? get() = shared

        /** [lastConfigError] when the system did not let the request out (no INTERNET permission). */
        const val NOT_PERMITTED = "network_not_permitted"

        /** 28.11 item 5: the config in force is asked again only when older than this. */
        const val CONFIG_MAX_AGE_MS = 300_000L

        /** 24.6: an occurrence that made a new report is sent this long after it, while the app is in the foreground. */
        const val ERROR_SEND_DELAY_MS = 30_000L

        @JvmStatic fun install(c: QuranLabCloud?) { shared = c }
    }
}
