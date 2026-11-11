package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorController
import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.accel.AcceleratorStore
import ai.quranlab.tajweedformer.accel.NpuPreparer
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * One model build on this device and its accelerators (the internals under `TajweedClient.model()`): keeps the
 * build's files on the device and makes recognizers on the best backend.
 *
 * - [prepare] downloads the CPU profile (any network) and verifies it.
 * - [recognizer] opens a recognizer on the exact CPU engine at once (CPU first). The
 *   NPU is prepared in the background ([accelerator]: download on an unmetered network, load, the golden test
 *   on the shipped self-test set) and later sessions run on it once it is ready.
 */
internal class ModelClient(
    val store: ModelStore,
    private val platform: Platform = Platform(),
) {
    /** What the platform layer provides. */
    class Platform(
        /** The device's SoC id ([SocIds]), null when unknown. */
        val soc: String? = null,
        val network: NetworkPolicy = NetworkPolicy.ALWAYS_UNMETERED,
        /** Loads an NPU model; null when no NPU runtime is in the app. */
        val npuRunner: ((File, NpuContract) -> NpuRunner)? = null,
        /** Fetch NPU files at all. */
        val npuDownloads: Boolean = true,
        /** The signed config's `inference.context` (the portal's Response time), read when a recognizer is opened. */
        val configContext: (() -> String?)? = null,
        /** The backend the signed config's deny-list forces for a model build (`exact` / `cpu`), or null. */
        val forcedBackend: ((String) -> String?)? = null,
        /** Where the NPU is prepared (OS build, device, SoC, thermal, memory, background scheduling);
         *  null: no accelerator (the CPU only). */
        val accelerator: AcceleratorEnvironment? = null,
    )

    private val lock = Mutex()

    @Volatile private var refreshed = false

    /**
     * Downloads (if needed) and verifies the CPU profile of [context] (null: the default). The first
     * call of a client asks the server for the current manifest (a new revision is then downloaded);
     * without a network the verified manifest on disk is used.
     */
    suspend fun prepare(context: String? = null, cancel: Cancellation? = null, onProgress: ((ProfileProgress) -> Unit)? = null): ModelFolder =
        lock.withLock {
            runInterruptible(Dispatchers.IO) {
                if (!refreshed) {
                    store.manifest(refresh = true)
                    refreshed = true
                }
                store.ensureCpu(context, cancel, onProgress)
            }
        }

    private val accelerators = HashMap<String, AcceleratorController>()

    /**
     * The NPU controller of [model] and [context], made and started once per key: it
     * prepares the NPU in the background (WorkManager on Android) while the CPU serves. Null when the
     * platform gives no accelerator environment or NPU downloads are off.
     */
    @Synchronized
    fun accelerator(model: ModelFolder, context: String = model.defaultContext): AcceleratorController? {
        val env = platform.accelerator ?: return null
        if (!platform.npuDownloads) return null
        val key = AcceleratorKey(model.manifest.build, env.osBuild, env.deviceModel, env.soc, context)
        return accelerators.getOrPut(key.id) {
            val c = AcceleratorController(env, AcceleratorStore(store.root), NpuPreparer(store, model, platform.network, platform.npuRunner), key)
            c.start()
            c
        }
    }

    /**
     * At SDK init: every context whose key was promoted in an earlier launch starts loading and warming its accelerator now,
     * in the background (no golden test), so the first session after launch normally starts on it; a session that starts
     * before it is warm runs on the CPU and never waits. Blocking only for reading the records; returns the keys started.
     */
    fun startPromoted(): Int {
        val env = platform.accelerator ?: return 0
        if (!platform.npuDownloads) return 0
        val m = try { store.manifest() } catch (_: Exception) { return 0 }
        val records = AcceleratorStore(store.root)
        var n = 0
        for (ctx in m.contexts) {
            val r = records.record(AcceleratorKey(m.build, env.osBuild, env.deviceModel, env.soc, ctx)) ?: continue
            if (r.status != ai.quranlab.tajweedformer.accel.PromotionRecord.PROMOTED) continue
            val model = store.openIfReady(ctx) ?: continue
            if (accelerator(model, ctx) != null) n++
        }
        return n
    }

    /**
     * The app became active (Android does this by itself through the accelerator environment; a host without activities
     * calls it when it comes to the foreground): every accelerator runs a warm-up off the audio path, and the next chunk
     * gets the first-chunk limit.
     */
    @Synchronized
    fun appActivated() {
        for (c in accelerators.values) try { c.appActivated() } catch (_: Exception) {}
    }

    /**
     * Resumes, blocking, every accelerator promotion this device has a record of (the Android worker calls this in a fresh
     * process): for each context of the stored manifest whose CPU files are in place and whose key has a record, the
     * controller is started and its pipeline run to its next wait. Returns the keys run.
     */
    fun resumePromotions(): Int {
        val env = platform.accelerator ?: return 0
        if (!platform.npuDownloads) return 0
        val m = try { store.manifest() } catch (_: Exception) { return 0 }
        val records = AcceleratorStore(store.root)
        var n = 0
        for (ctx in m.contexts) {
            val model = store.openIfReady(ctx) ?: continue
            if (records.record(AcceleratorKey(m.build, env.osBuild, env.deviceModel, env.soc, ctx)) == null) continue
            val c = accelerator(model, ctx) ?: continue
            c.runPipeline()
            n++
        }
        return n
    }

    /**
     * A recognizer: the exact CPU engine now (after its self-test), the NPU for later sessions once it is
     * ready ([AcceleratorController.state]). The context is the app's ([options]), then the signed config's
     * `inference.context`, then the manifest's default. [options]' `accelerator`, `soc` and `selfTest` are filled in
     * here; the signed config's denylist forces the CPU.
     */
    suspend fun recognizer(options: Recognizer.Options = Recognizer.Options()): Recognizer {
        val configContext = options.configContext ?: try { platform.configContext?.invoke() } catch (_: Exception) { null }
        val wanted = options.context ?: configContext?.takeIf { c -> try { c in store.manifest().contexts } catch (_: Exception) { false } }
        val model = prepare(wanted)
        val context = Recognizer.resolveContext(model, options.context, configContext)
        val preference = if (forcedCpu(model)) BackendPreference.CPU else options.backend
        val acc = if (preference == BackendPreference.AUTO) runInterruptible(Dispatchers.IO) { accelerator(model, context) } else null
        val o = options.copy(context = context, configContext = null, backend = preference, accelerator = acc, soc = platform.soc, selfTest = true)
        return Recognizer.create(model, o)
    }

    /** Whether the signed config's denylist forces the CPU for [model]'s build. */
    fun forcedCpu(model: ModelFolder): Boolean {
        val forced = try { platform.forcedBackend?.invoke(model.manifest.build) } catch (_: Exception) { null }
        return forced == "exact" || forced == "cpu"
    }
}
