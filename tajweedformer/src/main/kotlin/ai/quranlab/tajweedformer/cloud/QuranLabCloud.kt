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
    /** Telemetry v2, on by default (anonymous daily sums). [Analytics.OFF] is the developer's one opt-out. */
    val analytics: Analytics = Analytics.ON,
    /** Issue reports (no free text). */
    val issueReports: Boolean = true,
    /**
     * Ignored since 2.0.1: whether the SDK offers recitation sharing (its own sheet, then whole sessions
     * uploaded for a learner who said "Share") is Quran Lab's decision per app, in the signed config.
     */
    val contributions: Boolean = false,
    /** Children: the sheet addresses the parent or guardian. */
    val audience: Audience = Audience.GENERAL,
    /**
     * Sharing uploads only on unmetered networks (default: any network; about 86 MB per hour at the top
     * bitrate, less where uploads struggle: the bitrate steps down to 32 kbit/s, about 16 MB an hour).
     */
    val uploadsOnUnmeteredOnly: Boolean = false,
    val apiUrl: String = QuranLabApi.DEFAULT_API,
    /** The Ed25519 keys the signed config may be signed with (raw 32 bytes). */
    val trustedKeys: List<ByteArray> = listOf(ManifestVerifier.QURAN_LAB_PUBLIC_KEY),
) {
    enum class Analytics { ON, OFF }
    enum class Audience { GENERAL, CHILDREN }
}

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
    /** The current network is unmetered (Wi-Fi, Ethernet). */
    val unmetered: () -> Boolean = { true },
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

    /** Every API answer: one whose body carries a stop reason is remembered as a refusal of the API. */
    val api = QuranLabApi(options.apiUrl, env.http) { r ->
        if (!r.ok) r.error.reason?.let { if (ModelGate.stops(it)) refusals.stop(it, Refusals.API) }
    }
    val config = ConfigStore(cloudDir, options.trustedKeys, env.clock)
    val tokens: TokenProvider? = key?.let {
        TokenProvider(api, it, env.app, cloudDir, env.integrity, env.clock,
            projectNumber = { currentConfig?.flags?.playIntegrityProjectNumber },
            issued = { refusals.apiAccepted(freshConfig = false) })
    }
    val telemetry = Telemetry(File(env.storage, "telemetry"), env.clock)
    val issues = IssueQueue(cloudDir, env.clock)
    val sharing = Sharing(this)

    /** A refusal stopped network calls until the next launch (revoked key, blocked app, SDK too old). */
    private val stopped = AtomicBoolean(false)
    @Volatile var stopReason: String? = null
        private set
    @Volatile private var telemetryRetryAt = 0L
    private var telemetryFailures = 0
    @Volatile private var lastSealAt = 0L

    init {
        telemetry.enabled = options.analytics == CloudOptions.Analytics.ON && config.current?.flags?.analytics != false
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
                ModelGate.stops(a.reason) -> ModelGate.error(a.reason!!, env.app.bundleId)
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
        if (foreground) {
            telemetry.launch()
            register(modelRevision)
        }
        refreshConfig()
        sendPoint()
        if (sharing.outbox.hasWork()) env.scheduler.scheduleUploads(this)
    }

    /**
     * A cold start or a return to the foreground (off the main thread): one launch counted, the daily
     * registration if this is the day's first, a fresh telemetry backoff, and the 15-minute timer restarted.
     */
    @JvmOverloads
    fun foreground(modelRevision: String? = null) {
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
        sharing.background()
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
            false
        }
    }

    /** Fetches and verifies the config (conditional on its ETag); the last good one stays on failure. */
    @Synchronized
    fun refreshConfig(): RemoteConfig? {
        if (key == null || stopped.get()) return config.current
        return try {
            val pin = modelVersion
            // a pin changes the answer: never conditional on the ETag of an answer made for another pin
            val etag = if (pin == pinOfStored()) config.etagToSend() else null
            val r = authed("config") { api.config(Credential.Token(it), bucket, env.app, etag, pin) }
            val c = config.apply(r)
            // a verified config (or the server's 304 for the one in force) accepts the key
            refusals.apiAccepted(freshConfig = true)
            rememberPin(pin)
            lastConfigError = null
            lastConfigAt = env.clock()
            applyFlags(c)
            noteSdkLatest(c)
            c?.pinUnavailable?.let { v -> if (env.debugBuild) try { env.log("model version $v is not available for this SDK: using ${c.recommendedVersion}") } catch (_: Exception) {} }
            c
        } catch (e: ConfigRejected) {
            lastConfigError = "signature_invalid"
            report("signature_invalid", "config:" + (e.message ?: "rejected").replace(' ', '_'))
            config.current.also { noteSdkLatest(it) }
        } catch (e: CloudException) {
            lastConfigError = e.api?.refusal ?: if (e.notPermitted) NOT_PERMITTED else "offline"
            config.current.also { noteSdkLatest(it) }
        }
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
        telemetry.enabled = options.analytics == CloudOptions.Analytics.ON && f.analytics
        if (!f.issues) issues.clear()
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
        if (telemetry.enabled && flags?.analytics != false && env.clock() >= telemetryRetryAt) reports = sendTelemetry()
        if (options.issueReports && flags?.issues != false) sent = sendIssues()
        return reports to sent
    }

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
                when {
                    r.status == 202 || r.ok -> { telemetry.dropReport(f); n++; telemetryFailures = 0 }
                    r.status == 400 -> { telemetry.dropReport(f); report("other", "bad_request:events") }
                    r.status == 413 -> telemetry.dropReport(f)
                    r.status == 403 -> { refused(CloudException(r.error, "events"), "events"); return n }
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
                if (r.ok || r.status == 400 || r.status == 413) { issues.removeFirst(); if (r.ok) n++ } else break
            } catch (e: CloudException) {
                break
            }
        }
        return n
    }

    /** Queues an issue report (no free text) and schedules delivery. */
    fun report(kind: String, code: String, error: Throwable? = null, backend: String? = null, revision: String? = null) {
        if (!options.issueReports || currentConfig?.flags?.issues == false) return
        if (issues.add(kind, code, IssueQueue.frames(error), env.app, env.device, backend, revision)) env.scheduler.scheduleDelivery(this)
    }

    /** Queues an accelerator incident (section 7.1) and schedules delivery. */
    fun reportIncident(i: ai.quranlab.tajweedformer.AcceleratorIncident) {
        if (!options.issueReports || currentConfig?.flags?.issues == false) return
        if (issues.addIncident(i, IssueQueue.frames(i.error), env.app, env.device)) env.scheduler.scheduleDelivery(this)
    }

    // ---- TajweedObserver ----

    override fun onPromotion(e: ai.quranlab.tajweedformer.PromotionEvent) {
        telemetry.promotion(e)
        if (!e.promoted) e.incident?.let { reportIncident(it) }
    }

    override fun onFailover(e: ai.quranlab.tajweedformer.AcceleratorFailoverEvent) {
        telemetry.failover(e)
        reportIncident(e.incident)
    }

    override fun onBlankConfirmed(e: ai.quranlab.tajweedformer.BlankConfirmedEvent) = telemetry.blankConfirmed(e)

    override fun onModelLoad(e: TajweedObserver.ModelLoad) = telemetry.modelLoad(e)
    override fun onSelfTest(revision: String, context: String, backend: String, passed: Boolean, build: String) {
        telemetry.selfTest(revision, context, backend, passed, build)
        if (!passed) report("self_test_failed", "self_test:$backend", backend = backend, revision = revision)
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

        @JvmStatic fun install(c: QuranLabCloud?) { shared = c }
    }
}
