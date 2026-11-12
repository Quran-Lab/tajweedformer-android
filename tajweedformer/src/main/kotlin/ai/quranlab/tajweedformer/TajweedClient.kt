package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.ModelGate
import ai.quranlab.tajweedformer.cloud.ModelRequestHeaders
import ai.quranlab.tajweedformer.cloud.QuranLabCloud
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.cloud.Refusals
import ai.quranlab.tajweedformer.cloud.RemoteConfig
import kotlinx.coroutines.Dispatchers
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
    @Volatile private var modelClient: ModelClient? = null
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
                analytics = if (options.analytics == TajweedClientOptions.Analytics.OFF) CloudOptions.Analytics.OFF else CloudOptions.Analytics.ON,
                audience = if (options.audience == TajweedClientOptions.Audience.CHILDREN) CloudOptions.Audience.CHILDREN else CloudOptions.Audience.GENERAL,
                apiUrl = platform.apiUrl, trustedKeys = platform.trustedKeys,
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
        // without a lifecycle of its own (the desktop JVM) the making of the client is the app's opening
        if (autoLaunch) work.execute { try { cloud.launch(foreground = platform.lifecycle == null) } catch (_: Exception) {} }
    }

    /** Sharing recitations with Quran Lab, where Quran Lab offers it to this app and the learner said yes. */
    public val sharing: TajweedSharing = TajweedSharing(this)

    /** The app's own counters for its statistics at sdk.quranlab.ai. */
    public val analytics: TajweedAnalytics = TajweedAnalytics(this)

    private inline fun <T> tracked(body: () -> T): T = try {
        guard(body)
    } catch (e: TajweedError) {
        lastErrorCode = e.code
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
     * `needs_connection` (the model is not on the device and there is no connection), `download_failed`,
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
            // while a refusal holds no Model is given, also not the open one (which runs on until the app closes it)
            model?.let { m -> if (cloud.refusals.current == null && (version == null || m.version == version || version == modelPin)) return m }
            val m = withContext(Dispatchers.IO) { runInterruptible { openModel(version) } }
            model?.close()
            model = m
            modelPin = version
            m
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
            withContext(Dispatchers.IO) {
                runInterruptible {
                    if (unmeteredOnly && connection() in WAITS) return@runInterruptible
                    val target = target(cloud.modelVersion, refresh = true)
                    val store = store(target)
                    val state = admit(store, target)
                    download(store, target, state, onProgress)
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
            modelClient = null
            withContext(Dispatchers.IO) {
                if (modelsDir.exists() && !modelsDir.deleteRecursively()) throw RecognizerException.StorageFailed(modelsDir.path)
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
                cloud.foreground()
                cloud.refreshConfig()
                cloud.deliver()
                modelClient?.appActivated()
            } catch (_: Exception) {
            }
        }
    }

    /** While the app is in use (Android: every minute): the signed config is refreshed and a send point taken when due. */
    internal fun tick() {
        if (closed) return
        work.execute { try { cloud.refreshConfig(); cloud.tick() } catch (_: Exception) {} }
    }

    /** Ends the client: the open Model and its Session are closed, what was counted is kept on disk for the next
     *  start. After it a new client can be made. */
    override fun close() {
        if (closed) return
        closed = true
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { cloud.telemetry.flushToDisk() } catch (_: Exception) {}
        for (u in undo) try { u() } catch (_: Exception) {}
        QuranLabCloud.install(null)
        work.shutdown()
        CURRENT.compareAndSet(this, null)
        OpenObjects.closed(this)
    }

    // ---- the model on this device ----

    /** What to download and open: a build of a version, where its folder is served and where it is kept. */
    internal class Target(val modelId: String, val repo: String, val build: String, val version: String?, val folder: String, val baseUrl: String) {
        fun json(): Map<String, Any?> = linkedMapOf("model_id" to modelId, "repo" to repo, "build" to build, "version" to version,
            "folder" to folder, "base_url" to baseUrl)

        companion object {
            fun of(o: Map<*, *>): Target? {
                return Target(o["model_id"] as? String ?: return null, o["repo"] as? String ?: return null, o["build"] as? String ?: return null,
                    o["version"] as? String, o["folder"] as? String ?: return null, o["base_url"] as? String ?: return null)
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
        try {
            removalOwed.parentFile?.mkdirs()
            removalOwed.writeText("owed\n")
        } catch (_: Exception) {
        }
        try {
            modelsDir.listFiles()?.forEach { m ->
                if (m.isFile) m.delete() else m.listFiles { f -> f.isFile && f.name.endsWith(InstallRecords.SUFFIX) }?.forEach { it.delete() }
            }
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

    /** The install records of the builds this Client downloaded itself (SPEC 15.2). */
    private val records = InstallRecords(platform.installKeys)

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
        if (refresh || cfg == null || cfg.recommendedRevision == null || !cloud.configAskedWith(version)) cfg = cloud.refreshConfig()
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
                Target(id, repo, build, cfg.recommendedVersion, folder, ModelStore.folderUrl(cfg.base ?: platform.modelsUrl, repo, build, folder))
            } else null
        } else null
        if (fromConfig != null && (version == null || fromConfig.version == null || fromConfig.version == version || cfg?.pinUnavailable == version)) return fromConfig
        val last = current()
        if (last != null && (version == null || last.version == version) && File(dir(last), ModelManifest.FILE_NAME).isFile) return last
        if (refused != null) throw refused
        // the server answered and offers this SDK no model build (none is published for it yet): not a missing
        // connection, and nothing to retry in a loop (the config refresh is the retry)
        if (cfg != null && r == null) throw RecognizerException.ModelUnavailable()
        // no answer because the system does not let the app use the network: that is what to fix, not the connection
        if (cloud.lastConfigError == QuranLabCloud.NOT_PERMITTED) throw RecognizerException.NetworkNotPermitted()
        throw RecognizerException.NeedsConnection()
    }

    /** The store of [t]: every model request with the key and the install's token, made in one place (SPEC 15.4). */
    private fun store(t: Target): ModelStore =
        ModelStore(dir(t), t.baseUrl, platform.modelHttp, ModelRequestHeaders(cloud), ManifestVerifier(platform.trustedKeys),
            expectBuild = t.build, expectVersion = t.version)

    /**
     * Before anything of [t] is opened or asked for (SPEC 15.2, 15.3): every build folder on the device without a valid
     * install record is deleted (when the key store fails, nothing is deleted and the call fails with
     * `storage_failed`); a build without a record gets one that says `downloading`, before the first byte of it is asked
     * for. Then a refusal of a model request that the API's fresh config let through is asked of the CDN again: a model
     * request it serves clears it, anything else leaves it, and the call fails with it. Returns the record's state.
     */
    private fun admit(store: ModelStore, t: Target): InstallRecords.State {
        // a removal that is owed comes first: after a refusal the device holds no model, also once the key is accepted
        if (removalOwed.exists()) removeModelsForStop()
        records.sweep(modelsDir)
        val dir = dir(t)
        val state = records.state(dir, t.build) ?: run {
            records.remove(dir)
            records.write(dir, t.build, InstallRecords.State.DOWNLOADING)
            InstallRecords.State.DOWNLOADING
        }
        if (cloud.refusals.current != null) {
            try { store.manifest(refresh = true) } catch (_: TajweedError) {}
            cloud.refusals.current?.let { throw ModelGate.error(it.reason, platform.cloud.app.bundleId) }
        }
        return state
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
            e.cause is java.io.IOException -> RecognizerException.NeedsConnection(e)
            else -> e
        }
    }

    /** The context whose files are downloaded first: the config's `inference.context` when the build ships it. */
    private fun firstContext(m: ModelManifest): String = cloud.inferenceContext?.takeIf { it in m.contexts } ?: m.defaultContext

    /**
     * Brings the CPU set of [t] onto the device (resuming what an earlier call left) and opens the verified folder.
     * When every file of it is verified, the install record of a download ([state] `downloading`) says `installed`.
     */
    private fun download(store: ModelStore, t: Target, state: InstallRecords.State, onProgress: ((Long, Long) -> Unit)?): ModelFolder {
        val m = manifest(store, t)
        val ctx = firstContext(m)
        val missing = store.missingCpu(ctx)
        if (missing.isNotEmpty()) {
            val need = missing.sumOf { it.size + it.localSize }
            val free = platform.freeSpace?.invoke(store.root)
            if (free != null && free < need) throw RecognizerException.StorageFull(need, free)
        }
        var got = false
        val folder = try {
            store.ensureCpu(ctx, null) { p ->
                got = true
                try { onProgress?.invoke(p.bytes, p.total) } catch (_: Exception) {}
            }
        } catch (e: RecognizerException.DownloadFailed) {
            // the system refused the network; nothing came at all: there is no connection; something came and then
            // stopped: a download that resumes
            throw when {
                e.cause is NetworkNotPermitted -> RecognizerException.NetworkNotPermitted(e)
                !got && e.cause is java.io.IOException -> RecognizerException.NeedsConnection(e)
                else -> e
            }
        }
        if (state != InstallRecords.State.INSTALLED) records.write(dir(t), m.build, InstallRecords.State.INSTALLED)
        modelsDir.mkdirs()
        try { currentFile.writeText(Json.write(Target(t.modelId, t.repo, m.build, m.version, t.folder, t.baseUrl).json())) } catch (_: Exception) {}
        return folder
    }

    private fun openModel(version: String?): TajweedModel {
        val t = target(version, refresh = cloud.lastConfigAt == 0L)
        val store = store(t)
        val state = admit(store, t)
        val folder = download(store, t, state, null)
        // the sample clips (0.6 MB) come with the model when they can; a build or a network without them still opens
        try { for (e in ModelProfile.selfTest(folder.manifest, folder.defaultContext)) store.downloader.fetch(e) } catch (_: Exception) {}
        // older builds of this model are no longer served to this app: their files and records go
        try {
            dir(t).parentFile?.listFiles { f -> f.isDirectory && f != dir(t) }?.forEach { records.remove(it) }
        } catch (_: Exception) {
        }
        val mc = ModelClient(store, ModelClient.Platform(
            soc = platform.soc, network = platform.network, npuRunner = platform.npuRunner,
            npuDownloads = platform.accelerator != null, configContext = { cloud.inferenceContext },
            forcedBackend = { build -> cloud.forcedBackend(build) }, accelerator = platform.accelerator,
        ))
        modelClient = mc
        val host = ModelHost(
            store = store, verifier = store.verifier, engine = platform.engine, soc = platform.soc, warmRate = platform.warmRate,
            microphone = { platform.microphone }, configContext = { cloud.inferenceContext },
            accelerator = { f, ctx -> mc.accelerator(f, ctx) }, forcedCpu = { f -> mc.forcedCpu(f) },
            recited = { r -> cloud.telemetry.recited(r.first, r.second, r.third) },
            chunkDropped = { droppedChunks.incrementAndGet() },
            onClose = { m ->
                if (model === m) model = null
                // the files of a Model that was open at a refusal go once it is closed
                if (removalOwed.exists()) removeModelsForStop()
            },
        )
        val m = TajweedModel.of(folder, host)
        // a build promoted in an earlier launch loads and warms its accelerator now, in the background
        if (platform.accelerator != null) {
            Thread({ try { mc.startPromoted() } catch (_: Exception) {} }, "tajweedformer-npu-warm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
        return m
    }

    /** The accelerator state of the open Model's context, for the Android worker and tests. */
    internal fun acceleratorState(): AcceleratorState? = model?.let { m -> modelClient?.accelerator(m.folder, m.contextInForce() ?: m.defaultContext)?.state?.value }

    /** Resumes, blocking, every accelerator promotion recorded for the build last used on this device (the Android
     *  worker calls this in a process WorkManager started). Returns the keys run. */
    internal fun resumePromotions(): Int {
        val t = current() ?: return 0
        if (platform.accelerator == null) return 0
        // only a build this Client downloaded itself, and none while a refusal holds (SPEC 15.2, 15.3)
        if (cloud.refusals.current != null) return 0
        if (try { records.state(dir(t), t.build) } catch (_: TajweedError) { null } != InstallRecords.State.INSTALLED) return 0
        val mc = modelClient ?: ModelClient(store(t), ModelClient.Platform(soc = platform.soc, network = platform.network,
            npuRunner = platform.npuRunner, npuDownloads = true, forcedBackend = { build -> cloud.forcedBackend(build) },
            accelerator = platform.accelerator))
        return mc.resumePromotions()
    }

    internal companion object {
        private val CURRENT = AtomicReference<TajweedClient?>(null)

        /** What `diagnose().connection` may say. */
        val CONNECTIONS = listOf("unmetered", "metered", "none", "unknown")

        /** The connections on which `prefetch(unmeteredOnly = true)` waits. */
        private val WAITS = setOf("metered", "none")

        /** The process's client, or null. */
        val current: TajweedClient? get() = CURRENT.get()
    }
}

/**
 * The app's own counters, for its statistics at sdk.quranlab.ai. Optional: nothing is sent if the app never calls it,
 * or when analytics are off.
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
