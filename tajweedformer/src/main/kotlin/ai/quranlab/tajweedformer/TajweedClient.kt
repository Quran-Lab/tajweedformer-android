package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.cloud.ErrorRules
import ai.quranlab.tajweedformer.cloud.ModelGate
import ai.quranlab.tajweedformer.cloud.ModelRequestHeaders
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.cloud.Refusals
import ai.quranlab.tajweedformer.cloud.RemoteConfig
import ai.quranlab.tajweedformer.cloud.RecitedCount
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.LeaseTarget
import ai.quranlab.tajweedformer.core.ProtectedBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's link to Quran Lab: the key, the signed config, registration, anonymous usage statistics, issue reports,
 * recitation sharing, and the model files on disk. One per process: make it once (on Android
 * `TajweedClient(context, key)`, on the desktop JVM `TajweedClient(key, storage)`) and keep it.
 *
 * ```
 * val client = TajweedClient(context, "qlk_v1_...")     // your key from sdk.quranlab.ai
 * val model = client.model()                             // downloads once, verifies, opens and warms the engine
 * val session = model.listen()
 * session.chunks.collect { chunk -> heard.text = chunk.decoded.text }
 * ```
 *
 * Making a second client while the first is not closed is the error `client_exists`.
 */
public class TajweedClient internal constructor(
    key: String,
    private val options: TajweedClientOptions,
    internal val platform: ClientPlatform,
    /** Run the launch sequence (the signed config, a telemetry send point) on a background thread now; tests drive it. */
    autoLaunch: Boolean = true,
) : AutoCloseable {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "tajweedformer-client").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val modelLock = Mutex()
    /** One removal of the models at a time (SPEC 15.3); made before the init block that may run one. */
    private val removalLock = Any()
    @Volatile private var closed = false
    @Volatile private var model: TajweedModel? = null
    /** The version the open Model was asked for (null: Quran Lab's choice). The server may have answered a pin with
     *  another version; the same pin again is still this Model. */
    @Volatile private var modelPin: String? = null
    @Volatile private var lastErrorCode: String? = null
    private val undo = ArrayList<() -> Unit>()

    /** A take opened or closed (Android keeps the config fresh while one is open). */
    @Volatile internal var onSessionOpen: ((Boolean) -> Unit)? = null

    internal val cloud: QuranLabCloud = start(key)

    /** The one client of the process takes its place, or the constructor fails and leaves no trace. */
    private fun start(key: String): QuranLabCloud {
        if (QuranLabKey.parse(key) == null) throw RecognizerException.InvalidAppOption("key", "it is not a Quran Lab key (qlk_v1_...)")
        if (!CURRENT.compareAndSet(null, this)) throw RecognizerException.ClientExists()
        try {
            platform.cloud.storage.mkdirs()
            if (!platform.cloud.storage.isDirectory) throw RecognizerException.StorageFailed(platform.cloud.storage.path)
            val c = QuranLabCloud(CloudOptions(
                key = key,
                apiUrl = platform.apiUrl, trustedKeys = platform.trustedKeys, endpoints = platform.endpoints,
            ), platform.cloud)
            QuranLabCloud.install(c)
            val bridge = CloudBridge(c, platform.cloud.inputRoute, sessionOpen = { open -> try { onSessionOpen?.invoke(open) } catch (_: Exception) {} })
            SdkEvents.add(bridge)
            undo += { SdkEvents.remove(bridge) }
            platform.sheets?.let { show -> c.sharing.prompts = { r -> show(c.sharing, r) } }
            platform.extraObserver?.let { undo += it(c) }
            return c
        } catch (e: Throwable) {
            for (u in undo) try { u() } catch (_: Exception) {}
            QuranLabCloud.install(null)
            CURRENT.compareAndSet(this, null)
            throw e
        }
    }

    init {
        OpenObjects.opened(this, "Client")
        // SPEC 15.3: before the Client does anything else, a removal of the models that is owed, or
        // model files on a device that holds a refusal (put back, or left by a removal that could not finish), go;
        // and from now on every refusal that stops a model removes them
        if (removalOwed.exists() || (cloud.refusals.current != null && hasModelFiles())) removeModelsForStop()
        cloud.refusals.onStop = { removeModelsForStop() }
        platform.lifecycle?.let { undo += it(this) }
        // without a lifecycle of its own (the desktop JVM), or made while the app is already in the foreground (Android:
        // a client made after the first activity started, as a Flutter app makes it), the making of the client is the
        // app's opening: a launch counted and the day's registration (Stage B, the Flutter port: such a client counted
        // neither until the app went to the background and came back)
        if (autoLaunch) work.execute {
            try { cloud.launch(heldRevision(), foreground = platform.lifecycle == null || platform.inForegroundAtStart) } catch (_: Exception) {}
        }
    }

    /** Sharing recitations with Quran Lab: every learner is asked, and shares only after saying yes. */
    public val sharing: TajweedSharing = TajweedSharing(this)

    /** The app's own counters for its statistics at sdk.quranlab.ai. */
    public val analytics: TajweedAnalytics = TajweedAnalytics(this)

    /**
     * A call of the app: its error is remembered for `diagnose().lastError`. A call whose coroutine was cancelled ends
     * as a cancelled coroutine, whatever the work it interrupted failed with (a download sees the cancel as an
     * interrupted thread and fails with "cancelled"): a cancel is not an error of the app's call.
     */
    private suspend inline fun <T> tracked(body: () -> T): T = try {
        guardQuiet(body)
    } catch (e: TajweedError) {
        currentCoroutineContext().ensureActive()
        lastErrorCode = e.code
        // contract 24.1: the failure reached the app (a cancel did not: it left above as the coroutine's)
        reachedApp(e)
        throw e
    }

    private fun checkOpen() {
        if (closed) throw RecognizerException.InvalidAppOption("client", "it is closed")
    }

    /**
     * The Model for this app. The first call downloads it (the CPU set of the context in use, about 57 MB), verifies
     * every file, opens the engine and warms it; later calls open what is on the device, and return the Model that is
     * already open. Call it early (at app start) so the first take starts at once.
     *
     * [version] pins a model version (`"4.0.0"`); null takes the version Quran Lab serves this app. Errors:
     * `needs_connection` (the SDK needs to reach Quran Lab to continue, and there is no connection), `download_failed`,
     * `storage_full`, `storage_failed`, `invalid_key`, `sdk_too_old`, `server_rejected`, and the model's own:
     * `model_invalid`, `signature_invalid`, `checksum_mismatch`, `model_incompatible`.
     *
     * A model comes only from Quran Lab's server, through this call or [prefetch]: the SDK opens only a build it
     * downloaded itself on this device. When Quran Lab has refused the key or the app, the call fails with that
     * refusal (`invalid_key`, or `server_rejected` with its reason), also when a model is on the device, until a later
     * answer of the server accepts the key. A Model that is already open keeps running until the app closes it.
     */
    @JvmOverloads
    public suspend fun model(version: String? = null): TajweedModel = tracked {
        checkOpen()
        checkVersion(version)
        modelLock.withLock {
            // a call that waited for another one while the Client was closed
            checkOpen()
            // while a refusal holds no Model is given, also not the open one (which runs on until the app closes it)
            model?.let { m -> if (cloud.refusals.current == null && (version == null || m.version == version || version == modelPin)) return m }
            // the Core opens one model at a time and installs nothing while one is open: the open Model goes first
            model?.close()
            model = null
            // Gate C (SDK 4.0.1): the opened Model becomes the Client's, or is closed; never dropped ([openKept])
            openKept({ openModel(version) }) { m ->
                model = m
                modelPin = version
            }
        }
    }

    private fun checkVersion(version: String?) {
        if (version != null && !ModelManifest.isVersion(version)) throw RecognizerException.InvalidAppOption("version", "$version is not x.y.z")
    }

    /**
     * Downloads and verifies the model ahead of use, without opening it. [onProgress] gets the bytes done and the
     * total of the download. A download that was cut resumes where it stopped at the next call; when everything is
     * on the device the call does nothing. With [unmeteredOnly] nothing is downloaded while the connection is
     * `metered` or `none` (the call returns, and a later call on Wi-Fi does the work; `diagnose().connection` says
     * which it is). Where the platform cannot tell (`unknown`: the desktop JVM) the option cannot be honoured and
     * the download runs. Safe to call at every app start.
     */
    @JvmOverloads
    public suspend fun prefetch(unmeteredOnly: Boolean = false, onProgress: ((done: Long, total: Long) -> Unit)? = null): Unit = tracked {
        checkOpen()
        modelLock.withLock {
            checkOpen()
            withContext(Dispatchers.IO) {
                runInterruptible {
                    if (unmeteredOnly && connection() in WAITS) return@runInterruptible
                    val target = target(cloud.modelVersion, refresh = true)
                    val store = store(target)
                    admit(store, target)
                    download(store, target, onProgress, unmeteredOnly)
                    Unit
                }
            }
        }
    }

    /**
     * What the SDK holds on disk: `models`, a list with one entry per model build on the device (`version`, `build`,
     * `bytes`), ordered by version then build, and `sharingQueueBytes`, the bytes of the recordings waiting to be sent.
     */
    public val storage: Map<String, Any>
        get() {
            val models = ArrayList<Map<String, Any>>()
            for (dir in buildDirs()) {
                val m = try { ModelManifest.read(dir) } catch (_: Exception) { null } ?: continue
                models.add(linkedMapOf("version" to (m.version ?: "0.0.0"), "build" to m.build, "bytes" to size(dir)))
            }
            models.sortWith { a, b ->
                val v = ModelManifest.compareVersions(a["version"] as String, b["version"] as String) ?: 0
                if (v != 0) v else (a["build"] as String).compareTo(b["build"] as String)
            }
            return linkedMapOf("models" to models, "sharingQueueBytes" to size(File(platform.cloud.storage, "outbox")))
        }

    private fun size(f: File): Long = try { f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }

    private val modelsDir: File get() = File(platform.cloud.storage, "models")

    private fun buildDirs(): List<File> = modelsDir.listFiles { f -> f.isDirectory }?.flatMap { it.listFiles { f -> f.isDirectory }?.toList() ?: emptyList() } ?: emptyList()

    /**
     * Removes every model file from the device (the next `model()` downloads again). The open Model is closed first.
     * The learner's sharing choice and the sharing queue are never touched.
     */
    public suspend fun removeModels(): Unit = tracked {
        checkOpen()
        modelLock.withLock {
            model?.close()
            model = null
            withContext(Dispatchers.IO) {
                ManifestVerdicts.forget()
                if (modelsDir.exists() && !modelsDir.deleteRecursively()) throw RecognizerException.StorageFailed(modelsDir.path)
                // a download that was going on in the background is not owed any more
                settle(job = true)
            }
        }
    }

    /**
     * One structured report of how the SDK stands on this device: the SDK version and whether a newer one exists, the
     * key, the signed config, the model, the context and backend in force, the microphone permission, storage, and
     * the last error. No audio, text or identifiers: safe to paste into an issue (`toJson()`).
     */
    public suspend fun diagnose(): TajweedDiagnosis = withContext(Dispatchers.IO) {
        val cfg = try { cloud.currentConfig } catch (_: Exception) { null }
        // the key: refused (a refusal that stops the model, remembered on the device, or a server refusal that stops
        // the client), accepted (the server answered a config request in this process), or not known yet
        val refusal = (cloud.refusals.current?.let { ModelGate.error(it.reason, platform.cloud.app.bundleId) }
            ?: TajweedError.ofServerCode(cloud.stopReason, refused = true) ?: TajweedError.ofServerCode(cloud.lastConfigError))?.code
        val accepted: Boolean? = when {
            refusal != null -> false
            cloud.lastConfigAt != 0L -> true
            else -> null
        }
        // fresh: a verified config that has not expired is in force; stale: the stored one is past its expires_at
        val state = when {
            cfg != null -> "fresh"
            cloud.config.hasStored -> "stale"
            else -> "none"
        }
        val m = model
        val last = current()?.takeIf { File(dir(it), ModelManifest.FILE_NAME).isFile }
        val context = m?.let { open ->
            try { Recognizer.resolveContext(open.folder, null, cloud.inferenceContext) } catch (_: Exception) { open.defaultContext }
        }
        TajweedDiagnosis(
            sdkVersion = SdkInfo.VERSION, sdkLatest = cfg?.sdkLatest?.version,
            keyAccepted = accepted, keyReason = if (accepted == false) refusal else null,
            config = state,
            modelVersion = m?.version ?: last?.version, modelBuild = m?.build ?: last?.build, modelVerified = m != null,
            context = context,
            backend = m?.backend,
            microphone = (platform.microphone?.permission() ?: MicPermission.NONE).key,
            storage = storage,
            lastError = lastErrorCode,
            droppedChunks = droppedChunks.get(),
            connection = connection(),
            downloadProtocol = downloadProtocol,
            protectedModel = try { leases?.diagnosis() } catch (_: Exception) { null },
            endpoints = try { cloud.endpoints.diagnosis() } catch (_: Exception) { null },
        )
    }

    /** The connection now: `unmetered`, `metered`, `none`, or `unknown` where the platform cannot tell. */
    private fun connection(): String = try {
        platform.network.connection().takeIf { it in CONNECTIONS } ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** Waiting chunks dropped since this Client was made, because the app did not collect a Session's `chunks`. */
    private val droppedChunks = java.util.concurrent.atomic.AtomicLong()

    /** The app left the foreground. Automatic on Android; call it on the desktop JVM. A `listen` take ends, and what
     *  was counted is sent. */
    public fun background() {
        model?.background()
        if (!closed) work.execute { try { cloud.background() } catch (_: Exception) {} }
    }

    /** The app is in the foreground again. Automatic on Android; call it on the desktop JVM. */
    public fun foreground() {
        if (closed) return
        work.execute {
            try {
                cloud.foreground(heldRevision())
                cloud.refreshConfig()
                cloud.deliver()
                refreshLease()
            } catch (_: Exception) {
            }
        }
    }

    /** While the app is in use (Android: every minute): the signed config is refreshed and a send point taken when due. */
    internal fun tick() {
        if (closed) return
        work.execute { try { cloud.refreshConfig(); cloud.tick(); refreshLease() } catch (_: Exception) {} }
    }

    /** Ends the client: the open Model and its Session are closed, what was counted is kept on disk for the next
     *  start. A `model()` or `prefetch()` call that is downloading ends with `download_failed`, and what it had
     *  downloaded stays on the device for the next client. After it a new client can be made. */
    override fun close() {
        if (closed) return
        closed = true
        backgroundCancel.cancel()
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { recited?.close() } catch (_: Exception) {}
        try { cloud.telemetry.flushToDisk() } catch (_: Exception) {}
        try { leases?.close() } catch (_: Exception) {}
        coreTicker.shutdownNow()
        for (u in undo) try { u() } catch (_: Exception) {}
        QuranLabCloud.install(null)
        work.shutdown()
        CURRENT.compareAndSet(this, null)
        OpenObjects.closed(this)
    }

    // ---- the model on this device ----

    /** What to download and open: a build of a version, where its folder is served and where it is kept. */
    /** A build the signed config names (18.4.5): no folder, which the lease answer gives; its key id for the lease. */
    internal class Target(val modelId: String, val repo: String, val build: String, val version: String?, val folder: String, val keyId: String?) {
        fun json(): Map<String, Any?> = linkedMapOf("model_id" to modelId, "repo" to repo, "build" to build, "version" to version,
            "folder" to folder, "key_id" to keyId)

        companion object {
            fun of(o: Map<*, *>): Target? {
                return Target(o["model_id"] as? String ?: return null, o["repo"] as? String ?: return null, o["build"] as? String ?: return null,
                    o["version"] as? String, o["folder"] as? String ?: return null, o["key_id"] as? String)
            }
        }
    }

    /** Says that a removal of the models is owed (SPEC 15.3): there until every model file is gone. */
    private val removalOwed: File get() = File(File(platform.cloud.storage, "cloud"), "models-removal-owed")

    private fun hasModelFiles(): Boolean = try { modelsDir.walkTopDown().any { it.isFile } } catch (_: Exception) { false }

    /**
     * A refusal that stops a model removes every model build from the device (SPEC 15.3, contract 1.10.0): what
     * [removeModels] removes, `models/` whole, whatever the version. The install records and the pointer file go
     * first, so a folder that cannot go now (a file the system holds) is never opened again. What cannot be removed is
     * owed ([removalOwed]): tried again when the open Model is closed, at the next start, at every refusal, and before a
     * build is admitted. Never an error of its own; the remembered refusal, the device key, the sharing state and the
     * unsent recordings are elsewhere and not touched. Returns whether every model file is gone.
     */
    internal fun removeModelsForStop(): Boolean = synchronized(removalLock) {
        // nothing the manifest's check decided is used any more (the sealed verdicts go with the builds' folders)
        ManifestVerdicts.forget()
        try {
            removalOwed.parentFile?.mkdirs()
            removalOwed.writeText("owed\n")
        } catch (_: Exception) {
        }
        try {
            // the pointer file first, so a build that cannot go now is never opened again
            modelsDir.listFiles()?.forEach { m -> if (m.isFile) m.delete() }
        } catch (_: Exception) {
        }
        val gone = try { !modelsDir.exists() || platform.deleteTree(modelsDir) } catch (_: Exception) { false }
        if (gone && !hasModelFiles()) {
            removalOwed.delete()
            true
        } else {
            false
        }
    }

    /**
     * The model this install has, for the daily registration (`model_revision`, PLATFORM_CLIENT.md 5): the version of
     * the Model that is open, else of the build last downloaded or opened on this device, whatever its model id; null
     * when the device holds none (a first launch, or after the models were removed).
     */
    private fun heldRevision(): String? = try {
        model?.version?.takeIf { it.isNotEmpty() }
            ?: current()?.takeIf { File(dir(it), ModelManifest.FILE_NAME).isFile }?.let { t -> t.version ?: ModelIds.version(t.build).takeIf { it != t.build } }
    } catch (_: Exception) {
        null
    }

    /**
     * The protected model's keys and lease for this install (SDK 4.0, contract 18.4): the install certificate, the
     * attest, the lease and its refresh, and the Core that holds them. Null without a key. Its files are in
     * `<storage>/core`, beside `models/` (a removal of the models leaves them: the lease is not a model file).
     */
    internal val leases: LeaseClient? = cloud.key?.let { key ->
        val dir = File(platform.cloud.storage, "core")
        LeaseClient(
            dir = dir, identity = platform.identity(File(dir, "keys")), vault = platform.stateVault(File(dir, "vault")), api = cloud.api,
            key = key, app = platform.cloud.app,
            token = { cloud.tokens?.token()?.token ?: throw RecognizerException.InvalidKey() },
            invalidateToken = { cloud.tokens?.invalidate() },
            adoptToken = { t -> cloud.tokens?.adopt(t) },
            integrity = platform.cloud.integrity, projectNumber = { cloud.currentConfig?.flags?.playIntegrityProjectNumber },
            removeModels = { removeModelsForStop() },
            report = { kind, code -> cloud.report(kind, code) },
            leaseEvent = { failed -> cloud.telemetry.lease(failed) },
            onGrace = { cause -> try { cloud.silent(cause) } catch (_: Exception) {} },
            bootId = platform.bootId, clock = platform.cloud.clock, hostClock = platform.coreClock, platformSignals = platform.coreSignals,
        )
    }

    /** SDK 4.0 (SPEC 17.1, 17.6): which ayahs each finished take recited, counted once through the Core's journal. */
    internal val recited: RecitedCount = RecitedCount(File(platform.cloud.storage, "core"), cloud.telemetry, platform.cloud.clock,
        analytics = { cloud.telemetry.enabled },
        detector = { cloud.currentConfig?.flags?.recited != false },
        twins = { cloud.currentConfig?.flags?.recitedTwins != false },
        core = { leases?.core()?.takeIf { cloud.recited?.indexReady == true } },
        ensureIndex = { ensureRecitedIndex() },
    ).also { cloud.recited = it }

    /** The Core's tick while a take runs (17.6: the recited journal written every 10 s) and the lease's clock. */
    private val coreTicker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tajweedformer-core-tick").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.also { ex ->
        ex.scheduleWithFixedDelay({
            try { if (model?.sessionOpen == true) leases?.core()?.tick() } catch (_: Exception) {}
        }, CORE_TICK_MS, CORE_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private val currentFile: File get() = File(modelsDir, "current.json")

    /** The build last opened or downloaded on this device, or null. */
    private fun current(): Target? = try { Target.of(Json.parse(currentFile.readText()) as Map<*, *>) } catch (_: Exception) { null }

    private fun dir(t: Target): File = File(File(modelsDir, safe(t.modelId)), safe(t.build))

    private fun safe(s: String): String = s.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "x" }

    /**
     * The build to use: the one the signed config names for this SDK (refreshed first with [refresh], or when there
     * is none), else, offline, the one last used on this device. The server picks the build; the SDK never does.
     */
    private fun target(version: String?, refresh: Boolean): Target {
        cloud.modelVersion = version
        var cfg: RemoteConfig? = cloud.currentConfig
        // a config asked with another pin (or with none) does not say what the server has for this one
        if (refresh || cfg == null || cfg.recommendedRevision == null || !cloud.configAskedWith(version)) cfg = cloud.refreshConfig(silentReport = false)
        val appId = platform.cloud.app.bundleId
        // a refusal that stops the model (SPEC 15.3): no model, also not one on the device. One of a model request, once
        // the API has accepted the key again, is asked of the CDN again ([admit])
        cloud.refusals.current?.let { s -> if (s.from != Refusals.CDN || !cloud.refusals.askCdnAgain) throw ModelGate.error(s.reason, appId) }
        // a refusal that stops the client (an SDK too old, a key the server does not take) is the error when nothing
        // on the device can serve
        val refused = TajweedError.ofServerCode(cloud.stopReason, cloud.sdkRequired ?: cfg?.minSdk, refused = true, appId = appId)
            ?: TajweedError.ofServerCode(cloud.lastConfigError, cloud.sdkRequired ?: cfg?.minSdk, appId = appId)
        val r = cfg?.recommended
        val fromConfig = if (r != null && cfg != null) {
            val repo = r["model"] as? String
            val build = r["revision"] as? String
            val id = r["model_id"] as? String
            val folder = r["folder"] as? String ?: id?.let { "sdk/$it" }
            if (repo != null && build != null && id != null && folder != null) {
                Target(id, repo, build, cfg.recommendedVersion, folder, cfg.recommendedKeyId)
            } else null
        } else null
        if (fromConfig != null && (version == null || fromConfig.version == null || fromConfig.version == version || cfg?.pinUnavailable == version)) {
            cloud.reportModel = fromConfig.version to fromConfig.build
            return fromConfig
        }
        val last = current()
        if (last != null && (version == null || last.version == version) && File(dir(last), ModelManifest.FILE_NAME).isFile) {
            cloud.reportModel = last.version to last.build
            return last
        }
        val cause = cloud.lastConfigCause
        if (refused != null) throw refused.at(cause?.takeIf { it.reason == "refused" } ?: ErrorCause("config", "refused", errorCode = cloud.stopReason))
        // the server answered and offers this SDK no model build (none is published for it yet): not a missing
        // connection, and nothing to retry in a loop (the config refresh is the retry)
        if (cfg != null && r == null) throw RecognizerException.ModelUnavailable().at("config", "unavailable")
        // no answer because the system does not let the app use the network: that is what to fix, not the connection
        if (cloud.lastConfigError == QuranLabCloud.NOT_PERMITTED) throw RecognizerException.NetworkNotPermitted().at(cause ?: ErrorCause("config", "network"))
        // contract 24.4: an answer of the server (a 5xx, a 429, a 503 network_blocked, a redirect) and a config that does not
        // verify, has expired or cannot be read are `download_failed` naming it; only no answer at all is `needs_connection`.
        // The token gates the config (rule 3a): a token request that failed decides it the same way
        if (cause != null && ErrorRules.codeFor(cause.stage, cause.reason, cause.httpStatus, "none", cause.errorCode) == "download_failed") {
            throw RecognizerException.DownloadFailed("config", configFailure(cause)).at(cause)
        }
        throw RecognizerException.NeedsConnection().at(cause ?: ErrorCause("config", "network"))
    }

    /** What answered a config request that failed, for `download_failed`'s detail. */
    private fun configFailure(c: ErrorCause): String = when (c.reason) {
        "signature" -> "the signed config does not verify"
        "expired" -> "the signed config has expired"
        "parse" -> "the signed config cannot be read"
        "timeout" -> "the server did not answer in time"
        else -> c.httpStatus?.let { "the server answered $it" } ?: "the server's answer failed"
    }

    /** The protocol of the last answer to a model request of this Client (`diagnose().downloadProtocol`): in memory only. */
    @Volatile private var downloadProtocol: String? = null

    /** The model transport, with every answer's protocol noted for the diagnose report. */
    private val modelHttp: HttpTransport = object : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpResponse = get(url, headers, null)

        override fun get(url: String, headers: Map<String, String>, cancel: Cancellation?): HttpResponse =
            platform.modelHttp.get(url, headers, cancel).also { r -> downloadProtocol = r.protocol.takeIf { it in DOWNLOAD_PROTOCOLS } }

        override val separateConnections: Boolean get() = platform.modelHttp.separateConnections
    }

    /**
     * The store of [t]: every model request with the key and the install's token, made in one place (SPEC 15.4). Its
     * folder is the one the last lease of this build named (18.4.5), or none yet: the config names no folder.
     */
    private fun store(t: Target): ModelStore =
        ModelStore(dir(t), leases?.record?.takeIf { it.build == t.build }?.base ?: "", modelHttp, ModelRequestHeaders(cloud),
            ManifestVerifier(platform.trustedKeys), expectBuild = t.build, expectVersion = t.version, ranges = platform.modelRanges, retry = platform.modelRetry,
            endpoints = cloud.endpoints, verdicts = manifestVerdicts)

    /**
     * The manifest check's verdicts, remembered for the exact bytes that passed (4.0.1, [ManifestVerdicts]): sealed by
     * the install's state vault (the Keystore's key on Android), beside each build. A stop or a removal forgets them.
     */
    private val manifestVerdicts by lazy { ManifestVerdicts(platform.stateVault(File(platform.cloud.storage, "core/vault"))) }

    /** The encrypted build [m] of [store] (SDK 4.0): the Core opens it under the install's lease. */
    private fun protectedBuild(store: ModelStore, m: ModelManifest): ProtectedBuild {
        val l = coreLeases()
        if (m.encryption == null) {
            throw RecognizerException.ModelIncompatible("${m.modelId} ${m.version ?: m.build} is not an encrypted build; SDK ${SdkInfo.VERSION} opens encrypted builds only")
        }
        return ProtectedBuild(store.root, m, store.manifestBytes(), l, recited)
    }

    /** The lease client, with the Core loaded: the app's error otherwise. */
    private fun coreLeases(): LeaseClient {
        val l = leases ?: throw RecognizerException.InvalidKey()
        if (!CoreLibrary.available) {
            throw RecognizerException.RuntimeUnsupported("the Quran Lab Core did not load on this device (${System.getProperty("os.arch") ?: "unknown"}): ${CoreLibrary.loadError}")
        }
        return l
    }

    /**
     * What a lease of [t] is asked with (18.4.4, 18.4.5): the config's model id, version, build and key id. The manifest
     * comes from the lease answer's folder (the device's copy when it has one, verified again) and must carry that key
     * id. A build already on the device that the config no longer describes (offline) takes them from its manifest.
     */
    private fun leaseTarget(store: ModelStore, t: Target): LeaseTarget {
        val local = if (File(store.root, ModelManifest.FILE_NAME).isFile) try { store.manifest() } catch (_: Exception) { null } else null
        val keyId = t.keyId ?: local?.encryption?.keyId ?: throw RecognizerException.ModelUnavailable()
        val version = t.version ?: local?.version ?: throw RecognizerException.ModelUnavailable()
        return LeaseTarget(t.modelId, version, t.build, keyId) { base -> store.manifestFor(base, keyId) }
    }

    /**
     * A valid lease of [t] in the Core (asked for when there is none, contract 18.4), its manifest on the device, and
     * the folder the build's files come from (the lease answer's `base`). The app's error when the lease cannot be had.
     */
    private fun lease(store: ModelStore, t: Target) {
        val l = coreLeases()
        try {
            l.ensure(leaseTarget(store, t), ProtectedBuild.roots(store.root))
        } catch (f: LeaseFailure) {
            throw f.error
        }
        l.record?.takeIf { it.build == t.build }?.base?.let { store.filesBase = it }
    }

    /** A lease refresh that is due (18.4.7: at the first app start or model open after `refresh_after`), in the background. */
    private fun refreshLease() {
        val l = leases ?: return
        try {
            if (!l.refreshDue()) return
            val t = current() ?: return
            val store = store(t)
            l.refreshIfDue(leaseTarget(store, t), ProtectedBuild.roots(store.root)) { f -> leaseFailedSilently(f.error) }
        } catch (_: Exception) {
        }
    }

    /** Contract 24.1: a lease refresh that failed while the build went on with the lease in force (any but the network). */
    private fun leaseFailedSilently(e: TajweedError) {
        try { cloud.silent(ErrorCause.of(e)) } catch (_: Exception) {}
    }

    /**
     * Before anything of [t] is opened or asked for (SPEC 15.3): a removal that is owed comes first. Then a refusal of
     * a model request that the API's fresh config let through is asked of the CDN again: a model request it serves
     * clears it, anything else leaves it, and the call fails with it. (SPEC 15.2's install records are gone in 4.0: a
     * build's device forms open only with a lease bound to this install's key, so a copied folder opens nowhere.)
     */
    private fun admit(store: ModelStore, @Suppress("UNUSED_PARAMETER") t: Target) {
        if (removalOwed.exists()) removeModelsForStop()
        if (cloud.refusals.current != null) {
            try { store.manifest(refresh = true) } catch (_: TajweedError) {}
            cloud.refusals.current?.let { throw ModelGate.error(it.reason, platform.cloud.app.bundleId) }
        }
    }

    /**
     * The verified manifest of [t]: on disk, else fetched. The store runs the acceptance checks in their order: the
     * bytes are the build the config named, they parse, the signature verifies, the build has a version this SDK
     * runs, and the version is the one the config named.
     */
    private fun manifest(store: ModelStore, t: Target): ModelManifest = try {
        store.manifest(refresh = !File(store.root, ModelManifest.FILE_NAME).isFile)
    } catch (e: RecognizerException.DownloadFailed) {
        throw when {
            e.cause is NetworkNotPermitted -> RecognizerException.NetworkNotPermitted(e)
            else -> specific(e, if (anyFileOf(store)) "partial" else "none")
        }
    }

    /** Whether any file of the build is on the device (its kept files, or a part of one): contract 24.4's "nothing". */
    private fun anyFileOf(store: ModelStore): Boolean = try {
        store.root.walkTopDown().any { it.isFile && it.name != ModelManifest.FILE_NAME && it.length() > 0 }
    } catch (_: Exception) {
        false
    }

    /**
     * Contract 24.4: the code the app gets for a download that failed is the specific one for its cause and for what of
     * the build is on the device ([onDevice]): `needs_connection` only when no request got an answer and nothing is on
     * the device; a write on a full volume `storage_full`, another write that failed `storage_failed`; the rest stays.
     */
    private fun specific(e: RecognizerException.DownloadFailed, onDevice: String, need: Long = 0): TajweedError {
        val c = ErrorCause.of(e)
        return when (ErrorRules.codeFor(c.stage, c.reason, c.httpStatus, onDevice, c.errorCode)) {
            "needs_connection" -> RecognizerException.NeedsConnection(e)
            "storage_full" -> RecognizerException.StorageFull(need, try { platform.freeSpace?.invoke(modelsDir) ?: modelsDir.usableSpace } catch (_: Exception) { 0L }).at(c)
            "storage_failed" -> RecognizerException.StorageFailed(e.path, e)
            else -> e
        }
    }

    /** The context whose files are downloaded first: the config's `inference.context` when the build ships it. */
    private fun firstContext(m: ModelManifest): String = cloud.inferenceContext?.takeIf { it in m.contexts } ?: m.defaultContext

    /**
     * Brings the CPU set of [t] onto the device (resuming what an earlier call left): the manifest, a lease for the
     * build (18.4), the CDN forms from the lease's folder, each installed by the Core into its device form (no model
     * may be open in the Core then: with [install] false, while one is open, the forms wait for the next open).
     *
     * A download that starts is owed to the app until it is complete (SDK 3.1.3): the platform's background job
     * ([BackgroundDownloads], [unmeteredOnly] its network rule) continues it when the app leaves or its process ends.
     * What fails for good (a refusal, the daily limit, the storage) is not owed any more. [background]: the call is that
     * continuation ([continueDownload]).
     */
    private fun download(store: ModelStore, t: Target, onProgress: ((Long, Long) -> Unit)?,
                         unmeteredOnly: Boolean = false, background: Boolean = false, install: Boolean = model == null): ModelFolder {
        lease(store, t)
        val m = manifest(store, t)
        val b = protectedBuild(store, m)
        val ctx = firstContext(m)
        val missing = store.missingCpu(ctx, b)
        if (missing.isNotEmpty()) {
            val need = missing.sumOf { it.size + it.localSize }
            val free = platform.freeSpace?.invoke(store.root)
            if (free != null && free < need) throw RecognizerException.StorageFull(need, free).at("storage", "quota")
            if (!background) owe(unmeteredOnly)
        }
        var got = false
        val folder = try {
            // every download of this Client ends when the Client is closed (SPEC 16.3): the call then fails with
            // `download_failed`, and what it had stored stays for the next Client
            store.ensureCpu(b, ctx, backgroundCancel, { p ->
                got = true
                try { onProgress?.invoke(p.bytes, p.total) } catch (_: Exception) {}
            }, install = install)
        } catch (e: TajweedError) {
            // the system refused the network; nothing came at all: there is no connection; something came and then
            // stopped: a download that resumes
            val x = if (e !is RecognizerException.DownloadFailed) e else when {
                e.cause is NetworkNotPermitted -> RecognizerException.NetworkNotPermitted(e)
                else -> specific(e, if (got || missing.size < ModelProfile.cpu(m, ctx).size || anyFileOf(store)) "partial" else "none",
                    missing.sumOf { it.size + it.localSize })
            }
            if (!retryable(x)) settle(job = !background)
            throw x
        }
        if (missing.isNotEmpty() || owedFile.exists()) settle(job = !background)
        modelsDir.mkdirs()
        try { currentFile.writeText(Json.write(Target(t.modelId, t.repo, m.build, m.version, t.folder, m.encryption?.keyId).json())) } catch (_: Exception) {}
        return folder
    }

    /** Ends this Client's downloads at [close]: a call of the app that is downloading then, and the background
     *  continuation ([continueDownload]). */
    private val backgroundCancel = Cancellation()

    /**
     * A download of the CPU set that is owed to the app (SDK 3.1.3): there while it is not complete, with the network
     * rule and the version pin of the call that started it. Inside `models/`, so a stop and [removeModels] take it
     * away with the models.
     */
    private val owedFile: File get() = File(modelsDir, "download.json")

    private fun owe(unmeteredOnly: Boolean) {
        val bg = platform.downloads ?: return
        try {
            modelsDir.mkdirs()
            owedFile.writeText(Json.write(linkedMapOf("unmetered_only" to unmeteredOnly, "version" to cloud.modelVersion)))
        } catch (_: Exception) {
            return
        }
        try { bg.owe(unmeteredOnly) } catch (_: Exception) {}
    }

    /** Nothing is owed; with [job] the platform's job is called off too (the continuation itself leaves its own). */
    private fun settle(job: Boolean) {
        try { owedFile.delete() } catch (_: Exception) {}
        if (job) try { platform.downloads?.settled() } catch (_: Exception) {}
    }

    /** Whether a download that failed this way may go on later by itself: the network, a failure of the server (5xx,
     *  after the call's own resumes), or the call was ended. */
    private fun retryable(e: TajweedError): Boolean = e is RecognizerException.NeedsConnection ||
        (e is RecognizerException.DownloadFailed && (e.reason == "cancelled" || RetryPlan.resumable(e) || e.cause40?.stage == "config"))

    /**
     * Continues, blocking, a download of the model that the app started and did not finish (SDK 3.1.3): the platform's
     * background job calls it ([BackgroundDownloads]), in the app's process or in one it started. It waits for a call
     * of the app that is downloading, then downloads what is still missing of the build the config names (the same
     * pin), under the same rules as `prefetch`: the install record, a refusal, the key and the token, the network rule
     * of the call that started it. Not an app's call: `diagnose().lastError` does not change.
     */
    internal fun continueDownload(): Continued {
        if (closed || !owedFile.isFile) return Continued.DONE
        return try {
            runBlocking {
                modelLock.withLock {
                    withContext(Dispatchers.IO) {
                        runInterruptible {
                            val o = try { Json.parse(owedFile.readText()) as? Map<*, *> } catch (_: Exception) { null }
                            when {
                                closed || o == null -> Continued.DONE
                                o["unmetered_only"] == true && connection() in WAITS -> Continued.RETRY
                                else -> {
                                    val target = target(o["version"] as? String, refresh = true)
                                    val store = store(target)
                                    admit(store, target)
                                    download(store, target, null, o["unmetered_only"] == true, background = true)
                                    Continued.DONE
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: TajweedError) {
            if (retryable(e)) Continued.RETRY else {
                settle(job = false)
                Continued.GAVE_UP
            }
        } catch (_: Exception) {
            Continued.RETRY
        }
    }

    private fun openModel(version: String?): TajweedModel {
        val t = target(version, refresh = cloud.lastConfigAt == 0L)
        val store = store(t)
        admit(store, t)
        val folder = download(store, t, null, install = true)
        val b = folder.protected!!
        // the sample clips (0.6 MB, plain) come with the model when they can; a build or a network without them still opens
        try { store.fetch(ModelProfile.selfTest(folder.manifest, folder.defaultContext)) } catch (_: Exception) {}
        // older builds of this model are no longer served to this app: their files go
        try {
            dir(t).parentFile?.listFiles { f -> f.isDirectory && f != dir(t) }?.forEach { platform.deleteTree(it) }
        } catch (_: Exception) {
        }
        // SPEC 17.1: the recited count's index (the build's `recited` file) once it is on the device; fetched after a
        // session ran, installed before the model opens (the Core installs nothing while a model is open)
        prepareRecited(store, b)
        // R3: the reference data (small) comes with the model, installed before it opens; R2: an accelerator program
        // downloaded in the background since the last open is installed now
        prepareReference(store, b)
        prepareAccelerator(store, b, folder.defaultContext)
        val host = ModelHost(
            store = store, verifier = store.verifier, engine = platform.engine, soc = platform.soc, warmRate = platform.warmRate,
            microphone = { platform.microphone }, configContext = { cloud.inferenceContext },
            ensureContext = { ctx -> store.ensureCpu(b, ctx, backgroundCancel) },
            takeStarted = { s -> recited.takeStarted(s) },
            takeEnded = { s, finished ->
                recited.takeEnded(s, finished)
                if (finished) sessionRan()
            },
            recited = { s, r -> recited.report(s, r) },
            chunkDropped = { droppedChunks.incrementAndGet() },
            accelerator = { ctx -> acceleratorPlan(b, ctx) },
            onClose = { m ->
                if (model === m) model = null
                // the files of a Model that was open at a refusal go once it is closed
                if (removalOwed.exists()) removeModelsForStop()
            },
        )
        val m = try {
            TajweedModel.of(folder, host)
        } catch (e: TajweedError) {
            // contract 24.3: the engine opening the model (a file it found damaged is the build's check, `verify`)
            val oom = generateSequence<Throwable>(e) { it.cause }.take(8).any { it is OutOfMemoryError }
            throw when (e) {
                is RecognizerException.ChecksumMismatch -> e.at("verify", "hash")
                is RecognizerException.SignatureInvalid -> e.at("verify", "signature")
                is RecognizerException.ModelIncompatible -> e.at("model_open", "incompatible")
                is RecognizerException.ModelMissing -> e.at("model_open", "missing")
                is RecognizerException.ModelLoadFailed, is RecognizerException.ManifestInvalid -> e.at("model_open", "invalid")
                else -> e.at("model_open", if (oom) "memory" else "engine")
            }
        }
        // Gate C (SDK 4.0.1): from here the Model is open in the Core, which opens one at a time; a step after it that
        // fails closes it before the failure goes on, so no later open of this Client meets QLC_E_BUSY
        try {
            // the index is loaded where it is used (a counted take's end, a take a kill cut short), not at the open
            prepareRecitedIndex(b)
            // the accelerator's program for the next open, when the lease allows it (on Wi-Fi, in the background)
            fetchAccelerator(store, b, m.contextInForce() ?: folder.defaultContext, m)
        } catch (t: Throwable) {
            try { m.close() } catch (_: Exception) {}
            throw t
        }
        return m
    }

    // ---------------------------------------------------------------- R2 and R3

    /** The verdicts of this device's accelerator per build (`<storage>/core/accel.json`). */
    private val accelRecords by lazy { ai.quranlab.tajweedformer.core.AcceleratorRecords(File(File(platform.cloud.storage, "core"), "accel.json")) }

    private fun accelKey(b: ProtectedBuild, soc: String, ctx: String) = "${b.manifest.build}:npu:$soc:$ctx"

    /** The dispatch library per SoC ([ClientPlatform.dispatch]), resolved once in a process. */
    private val dispatchBySoc = java.util.concurrent.ConcurrentHashMap<String, ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution>()

    private fun dispatch(soc: String): ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution = dispatchBySoc.getOrPut(soc) {
        try {
            platform.dispatch(soc)
        } catch (e: Exception) {
            ai.quranlab.tajweedformer.core.DispatchLibrary.Resolution(null, "dispatch library: ${e.javaClass.simpleName}")
        }
    }

    /** Whether this phone fetches the build's program for [ctx] ([ai.quranlab.tajweedformer.core.AcceleratorGate]). */
    private fun acceleratorChoice(b: ProtectedBuild, ctx: String): ai.quranlab.tajweedformer.core.AcceleratorGate.Choice {
        val soc = platform.soc
        val key = soc?.let { accelKey(b, it, ctx) } ?: ""
        return ai.quranlab.tajweedformer.core.AcceleratorGate.choose(b.manifest, soc, ctx, accelRecords, key, SdkInfo.VERSION,
            platform.osBuild, platform.cloud.clock(), ::dispatch, { s -> try { platform.npuRuntimeMissing(s) } catch (_: Exception) { null } },
            configInForce = cloud.currentConfig != null, denied = { s -> accelDenied(b, s) })
    }

    /**
     * Whether the signed config in force keeps `npu:<soc>` off for this build (PLATFORM_CLIENT.md section 3): a
     * `denylist` row that forces the CPU, or an `accel_deny` rule, matched with the values 3.1.6 matched them with (the
     * SoC id as `chip`, `os_build` as RELEASE/ID/INCREMENTAL, the device model). The rule's reason, or null.
     */
    private fun accelDenied(b: ProtectedBuild, soc: String): String? = try {
        cloud.forcedBackend(b.manifest.revision)?.let { "denylist ($it)" }
            ?: cloud.accelDenied("npu:$soc", b.manifest.revision, platform.osBuildReport.ifEmpty { null },
                platform.cloud.device.modelCode, soc, null)
    } catch (_: Exception) {
        null
    }

    /** Denials already said in this process (a denial is never held: the next config without the rule brings it back). */
    private val deniedSaid = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** The program files of this phone's SoC for [ctx] that are fetched and installed (none: no NPU build for it, the
     *  SoC is not known, or it could not be promoted here). */
    private fun acceleratorFiles(b: ProtectedBuild, ctx: String): List<ModelManifest.FileEntry> = acceleratorChoice(b, ctx).files

    /**
     * What the recognizer of [ctx] promotes (R2), when the program is installed: the Core's kind `npu:<soc>`, the
     * dispatch folder and its library's identity, the build's tolerance and the golden clips (the build's self-test
     * clips). Null: the CPU only.
     */
    private fun acceleratorPlan(b: ProtectedBuild, ctx: String): ai.quranlab.tajweedformer.core.AcceleratorPlan? {
        val soc = platform.soc ?: return null
        val choice = acceleratorChoice(b, ctx)
        val folder = choice.dispatch?.folder ?: return null
        if (choice.files.isEmpty() || !choice.files.all { b.installed(it) }) return null
        return ai.quranlab.tajweedformer.core.AcceleratorPlan(soc, folder.dir.path, b.manifest.rawToleranceFor("npu:$soc:$ctx"),
            clips = { goldenClips(b) }, records = accelRecords,
            report = { p -> try { cloud.telemetry.promotion(p) } catch (_: Exception) {} },
            minSpeedup = { cloud.currentConfig?.flags?.accelMinSpeedup },
            os = platform.osBuild, clock = platform.cloud.clock, dispatchId = folder.id)
    }

    /**
     * A build whose program this phone will not fetch for a reason of the app, the OS or the build (no dispatch library,
     * no NPU runtime in the system image, no self-test set): said once in the promotion telemetry, and held like any
     * transient verdict (until an SDK, OS or dispatch library update, or 30 days), so it is not said at every start.
     */
    private fun noteUnusable(b: ProtectedBuild, ctx: String, c: ai.quranlab.tajweedformer.core.AcceleratorGate.Choice) {
        val soc = platform.soc ?: return
        val why = c.why ?: return
        val key = accelKey(b, soc, ctx)
        if (c.denied) {
            if (!deniedSaid.add("$key|$why")) return
            try {
                cloud.telemetry.promotion(ai.quranlab.tajweedformer.core.PromotionInfo(soc, b.manifest.build, ctx, promoted = false,
                    failedAt = "download", reason = "denylist", detail = why.take(120), version = b.manifest.version ?: ""))
            } catch (_: Exception) {
            }
            return
        }
        accelRecords.hold(key, ai.quranlab.tajweedformer.core.AcceleratorRecords.UNSUPPORTED, why, SdkInfo.VERSION, platform.osBuild,
            platform.cloud.clock(), mapOf("dispatch" to (c.dispatch?.id ?: ai.quranlab.tajweedformer.core.DispatchLibrary.NONE)))
        try {
            cloud.telemetry.promotion(ai.quranlab.tajweedformer.core.PromotionInfo(soc, b.manifest.build, ctx, promoted = false,
                failedAt = "download", reason = c.reason ?: "other", detail = why.take(120), version = b.manifest.version ?: ""))
        } catch (_: Exception) {
        }
    }

    /** The self-test clips of the build (plain files, `selftest/`) as 16 kHz samples. */
    private fun goldenClips(b: ProtectedBuild): List<Pair<String, FloatArray>> {
        val dir = File(b.dir, SelfTestSet.DIR)
        return SelfTestSet.read(dir).clips.mapNotNull { c ->
            val f = File(dir, c.file)
            if (!f.isFile || c.sampleRate <= 0) null
            else c.id to ai.quranlab.tajweedformer.core.Golden.resample(SelfTestSet.samples(f), c.sampleRate, 16_000)
        }
    }

    /** The program's CDN forms that arrived since the last open, installed before this one (no model is open yet). */
    private fun prepareAccelerator(store: ModelStore, b: ProtectedBuild, ctx: String) {
        val files = acceleratorFiles(b, ctx)
        if (files.isEmpty() || files.all { b.installed(it) }) return
        try {
            b.install(files, { store.downloader.isInPlace(it) }, { store.downloader.local(it) })
        } catch (_: Exception) {
        }
    }

    /**
     * The program's CDN forms (110 to 167 MB) in the background, for the next open: only when the lease in force allows
     * accelerator programs (`qlc_accel_allowed`), on an unmetered network, and not when the system asks to save data.
     */
    private fun fetchAccelerator(store: ModelStore, b: ProtectedBuild, ctx: String, m: TajweedModel? = null) {
        val choice = acceleratorChoice(b, ctx)
        if (choice.why != null) noteUnusable(b, ctx, choice)
        val files = choice.files.filter { !b.installed(it) && !store.downloader.isInPlace(it) }
        if (files.isEmpty() || closed) return
        val nothingInstalled = choice.files.none { b.installed(it) }
        work.execute {
            try {
                if (connection() != "unmetered" || saveData()) return@execute
                val c = leases?.core() ?: return@execute
                if (!c.accelAllowed()) return@execute
                // a firmware the Core refuses is known before the download (AcceleratorGate.firmwareRefused)
                val soc = platform.soc
                val host = if (nothingInstalled) m?.acceleratorHost() else null
                if (soc != null && host != null &&
                    ai.quranlab.tajweedformer.core.AcceleratorGate.firmwareRefused(host, "npu:$soc", choice.dispatch?.folder?.dir?.path)) {
                    accelRecords.hold(accelKey(b, soc, ctx), ai.quranlab.tajweedformer.core.AcceleratorRecords.FIRMWARE,
                        "before the download: qlc_accel_prepare (accel_firmware)", SdkInfo.VERSION, platform.osBuild, platform.cloud.clock(),
                        mapOf("dispatch" to (choice.dispatch?.id ?: ai.quranlab.tajweedformer.core.DispatchLibrary.NONE)))
                    try {
                        cloud.telemetry.promotion(ai.quranlab.tajweedformer.core.PromotionInfo(soc, b.manifest.build, ctx, promoted = false,
                            failedAt = "download", reason = "firmware", detail = "accel_firmware", version = b.manifest.version ?: ""))
                    } catch (_: Exception) {
                    }
                    return@execute
                }
                store.fetch(files, backgroundCancel)
            } catch (_: Exception) {
            }
        }
    }

    /** The build's reference data (R3: role `reference`, about 1 MB) downloaded and installed with the model. */
    private fun prepareReference(store: ModelStore, b: ProtectedBuild) {
        val files = ModelProfile.reference(b.manifest).filter { it.encoding == ModelManifest.Encoding.QLE1 && !b.installed(it) }
        if (files.isEmpty()) return
        try {
            if (files.any { !store.downloader.isInPlace(it) } && !saveData()) store.fetch(files, backgroundCancel)
            b.install(files, { store.downloader.isInPlace(it) }, { store.downloader.local(it) })
        } catch (_: Exception) {
        }
    }

    /** The marker of a session that ran on this install (17.1: the index is fetched only after one). */
    private val sessionRanFile: File get() = File(File(platform.cloud.storage, "core"), "session-ran")

    private fun sessionRan() {
        if (sessionRanFile.exists()) return
        try { sessionRanFile.parentFile?.mkdirs(); sessionRanFile.writeText("") } catch (_: Exception) {}
        // the index's CDN form now, in the background; it is installed at the next open
        if (!closed) work.execute {
            try {
                val t = current() ?: return@execute
                val store = store(t)
                val m = store.manifest()
                if (!recitedWanted()) return@execute
                val e = m.files.firstOrNull { it.role in RECITED_ROLES && it.forPlatform(SdkInfo.PLATFORM) } ?: return@execute
                if (!saveData()) store.fetch(listOf(e), backgroundCancel)
            } catch (_: Exception) {
            }
        }
    }

    private fun recitedWanted(): Boolean = cloud.telemetry.enabled && cloud.currentConfig?.flags?.recited != false

    /** 17.3: the SDK's own downloads wait while the system says to save data (Data Saver, roaming), or for Wi-Fi with
     *  the Client's `unmeteredOnly`. */
    private fun saveData(): Boolean = try {
        val c = connection()
        c == "none" || (options.unmeteredOnly && c == "metered") || platform.network.saveData()
    } catch (_: Exception) {
        false
    }

    /** The index file of [b] (role `recited`) installed by the Core when its CDN form is here and no model is open. */
    private fun prepareRecited(store: ModelStore, b: ProtectedBuild) {
        if (!recitedWanted()) return
        val e = b.manifest.files.firstOrNull { it.role in RECITED_ROLES && it.forPlatform(SdkInfo.PLATFORM) } ?: return
        try {
            if (!b.installed(e) && sessionRanFile.exists() && !store.downloader.isInPlace(e) && !saveData()) store.fetch(listOf(e), backgroundCancel)
            b.install(listOf(e), { store.downloader.isInPlace(it) }, { store.downloader.local(it) })
        } catch (_: Exception) {
        }
    }

    /** The recited index of the last model open's build, loaded into the Core at its first use (Stage B, 11-18). */
    private val recitedIndex = ai.quranlab.tajweedformer.cloud.RecitedIndexLoader<ProtectedBuild> { b ->
        val c = b.core()
        b.roots(c)
        c.recitedIndex(b.manifestBytes)
    }

    /**
     * The recited count's index, loaded at the model's open (qlc_recited_index: the 1.3 MB index decrypted into memory
     * and held by the Core; only qlc_recited_finish reads it, at a counted take's end and at the recovery of a take a
     * kill cut short). Stage B tried loading it at its first use instead (dd76453): open and first result were 0.2 s
     * faster on the Alcatel 1, but the load then came after a take, near the process's peak, and peak RSS rose to
     * +2.59 MB against 3.1.6 (Quran Lab's line is +2 MB; at the open it is +0.68 MB), and the first counted take's
     * `finish()` took 459 ms instead of 254. So it is loaded here again; a load that failed is tried again at the
     * next use ([ensureRecitedIndex]).
     */
    private fun prepareRecitedIndex(b: ProtectedBuild) {
        recited.indexReady = false
        recitedIndex.prepare(null)
        if (!recitedWanted()) return
        val e = b.manifest.files.firstOrNull { it.role in RECITED_ROLES && it.forPlatform(SdkInfo.PLATFORM) } ?: return
        if (!b.installed(e)) return
        recitedIndex.prepare(b)
        recitedIndex.ensure()
        recited.indexReady = true
        try { recited.recover() } catch (_: Exception) {}
    }

    /** The index loaded into the Core for the build of the last open (at most once per open); false: not loaded. */
    private fun ensureRecitedIndex(): Boolean = recitedIndex.ensure()

    public companion object {
        private val CURRENT = AtomicReference<TajweedClient?>(null)

        /**
         * The process's Client, or null while there is none. There is one Client per process (a second one while the
         * first is not closed is `client_exists`), and the app is not always the one that made it: on Android a process
         * that WorkManager started for the SDK's background work (a recording's upload, a model download, the
         * statistics) makes the Client from the options the app gave last time, before any activity runs. An app or a
         * plugin that makes its Client after its first activity (a Flutter or React Native plugin) takes that one:
         *
         * ```
         * val client = TajweedClient.current ?: TajweedClient(context, key)
         * ```
         *
         * It is the Client of the key and options the app used last time: an app whose key changed closes it and makes
         * its own.
         */
        @JvmStatic
        public val current: TajweedClient? get() = CURRENT.get()

        /** What `diagnose().connection` may say. */
        internal val CONNECTIONS = listOf("unmetered", "metered", "none", "unknown")

        /** What `diagnose().downloadProtocol` may say beside null (the contract's names.json, `diagnosis.downloadProtocol`). */
        internal val DOWNLOAD_PROTOCOLS = listOf("h3", "h2", "http/1.1")

        /** The connections on which `prefetch(unmeteredOnly = true)` waits. */
        private val WAITS = setOf("metered", "none")

        /** The recited-ayah index's roles (quranlab-core `recited`; SPEC 17.5 `recited-index`). */
        private val RECITED_ROLES = setOf(ModelProfile.RECITED_ROLE, "recited-index")

        /** The Core's tick while a take runs (17.6: the journal at most 10 s behind). */
        private const val CORE_TICK_MS = 10_000L

    }
}

/**
 * The app's own counters, for its statistics at sdk.quranlab.ai. Optional: nothing is sent if the app never calls it,
 * or while Quran Lab's signed config has analytics off (`flags.analytics`, the only switch: SDK 4.0 has no app option).
 */
public class TajweedAnalytics internal constructor(private val client: TajweedClient) {
    /**
     * App-reported: the app's own judge checked [checked] places for [rule] and flagged [flagged] of them. Counts
     * only: never text, audio, ids or times. [rule] is a short name of lower-case letters, digits and underscores
     * (use the calibration's class names where they apply: `vowel_sub` ... `insertion`, `madd`, `qalqalah`,
     * `gemination`, `word`); 0 <= [flagged] <= [checked] <= 100000 per call; at most 32 different rules a day (later
     * ones are dropped). Anything else is `invalid_argument`.
     */
    public fun reportChecks(rule: String, checked: Int, flagged: Int) {
        check(rule, checked, flagged)
        client.cloud.telemetry.checks(rule, checked, flagged)
    }

    internal companion object {
        const val MAX_CHECKED = 100_000

        /** Throws `invalid_argument` unless the call is one `reportChecks` takes. */
        fun check(rule: String, checked: Int, flagged: Int) {
            if (!ai.quranlab.tajweedformer.cloud.Telemetry.RULE_KEY.matches(rule)) {
                throw RecognizerException.InvalidAppOption("rule", "$rule is not a lower-case letter followed by up to 23 lower-case letters, digits or underscores")
            }
            if (checked < 0 || checked > MAX_CHECKED) throw RecognizerException.InvalidAppOption("checked", "$checked is not 0..$MAX_CHECKED")
            if (flagged < 0 || flagged > checked) throw RecognizerException.InvalidAppOption("flagged", "$flagged is not 0..$checked")
        }
    }
}

/**
 * Gate C (2026-11-20, SDK 4.0.1): a model that was opened is never dropped. [open] runs on the IO dispatcher, where the
 * caller's cancel interrupts it; what it opened is handed to [keep] in the caller's coroutine. When the caller is
 * cancelled while the open runs (the Core's open does not stop for an interrupt and completes, and `withContext` then
 * throws the cancel instead of returning the model), or [keep] throws, the opened model is closed here: dropped, it
 * would hold the Core's one open model, and every later open of the Client would fail with QLC_E_BUSY (`engine_failed`,
 * error_code `busy`).
 */
internal suspend fun <M : AutoCloseable> openKept(open: () -> M, keep: (M) -> Unit): M {
    val opened = AtomicReference<M?>()
    try {
        val m = withContext(Dispatchers.IO) { runInterruptible { open().also { opened.set(it) } } }
        keep(m)
        opened.set(null)
        return m
    } finally {
        opened.getAndSet(null)?.let { m -> try { m.close() } catch (_: Exception) {} }
    }
}
