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
 * The SDK in one object: keeps the model on the device and makes recognizers on the best backend.
 * On Android make it with `TajweedFormerAndroid.client(context)`, which fills in the SoC, the network
 * and the LiteRT runtime.
 *
 * - [prepare] downloads the CPU profile (any network) and verifies it.
 * - [recognizer] opens a recognizer on the exact CPU engine at once (the 2.0.0 design: CPU first). The
 *   NPU is prepared in the background ([accelerator]: download on an unmetered network, load, self-test
 *   on the shipped golden set) and later sessions run on it once it is ready.
 */
class TajweedFormerClient(
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
        val debugBuild: Boolean = false,
        /** Fetch NPU files at all (an app can turn it off to save data). */
        val npuDownloads: Boolean = true,
        /** The portal's current `tajweed` block, read at every session start (the cloud module). */
        val remoteTajweed: (() -> String?)? = null,
        /** The backend the portal's deny-list forces for a model revision (`exact` / `cpu`), or null. */
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
    suspend fun prepare(context: String? = null, cancel: Cancellation? = null, onProgress: ((ProfileProgress) -> Unit)? = null): TajweedModel =
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
    fun accelerator(model: TajweedModel, context: String = model.defaultContext): AcceleratorController? {
        val env = platform.accelerator ?: return null
        if (!platform.npuDownloads) return null
        val key = AcceleratorKey(model.manifest.revision, env.osBuild, env.deviceModel, env.soc, context)
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
            val r = records.record(AcceleratorKey(m.revision, env.osBuild, env.deviceModel, env.soc, ctx)) ?: continue
            if (r.status != ai.quranlab.tajweedformer.accel.PromotionRecord.PROMOTED) continue
            val model = store.openIfReady(ctx) ?: continue
            if (accelerator(model, ctx) != null) n++
        }
        return n
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
            if (records.record(AcceleratorKey(m.revision, env.osBuild, env.deviceModel, env.soc, ctx)) == null) continue
            val c = accelerator(model, ctx) ?: continue
            c.runPipeline()
            n++
        }
        return n
    }

    /**
     * A recognizer: the exact CPU engine now (after its self-test), the NPU for later sessions once it is
     * ready ([AcceleratorController.state]). [options]' `accelerator`, `soc`, `debugBuild` and `selfTest`
     * are filled in here (an explicit `debugBuild = true` is kept); the portal's denylist forces the CPU.
     */
    suspend fun recognizer(options: TajweedRecognizer.Options = TajweedRecognizer.Options()): TajweedRecognizer {
        val model = prepare(options.context ?: options.tajweed.context)
        val context = options.context ?: options.tajweed.context ?: model.defaultContext
        val forced = try { platform.forcedBackend?.invoke(model.manifest.revision) } catch (_: Exception) { null }
        val preference = if (forced == "exact" || forced == "cpu") BackendPreference.CPU else options.backend
        val acc = if (preference == BackendPreference.AUTO) runInterruptible(Dispatchers.IO) { accelerator(model, context) } else null
        val o = options.copy(
            backend = preference, accelerator = acc, soc = platform.soc, selfTest = true,
            debugBuild = options.debugBuild || platform.debugBuild,
        )
        val rec = TajweedRecognizer.create(model, o)
        platform.remoteTajweed?.let { p -> rec.remoteConfigProvider = p }
        return rec
    }
}
