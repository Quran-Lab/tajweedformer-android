package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.NpuFiles
import ai.quranlab.tajweedformer.NpuTajweedEngine
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.SelfTestRunner
import ai.quranlab.tajweedformer.SelfTestSet
import ai.quranlab.tajweedformer.TajweedBackend
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.npu.NpuChainRunner
import ai.quranlab.tajweedformer.npu.NpuChainOpener
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import java.io.File

/**
 * The Android NPU as an [AcceleratorPreparer]: the SoC's three `npu-*` files and the `selftest` set from
 * [store] (downloaded only on an unmetered network), the model loaded by [runnerFactory] (LiteRT with the
 * vendor dispatch), the host loop of [NpuTajweedEngine], and the self-test on the shipped golden set
 * scored by the exact engine's scorer (opened without a stream, so it never competes with a session).
 *
 * A chained build (io.json `parts`, the Exynos 2500) loads every part with [runnerFactory] (each with its own
 * part contract) and runs them as one [NpuChainRunner]; the self-test, watchdog, crash sentinel and fallback
 * are the same as for one model, over the whole chain.
 */
class NpuPreparer(
    private val store: ModelStore,
    private val model: TajweedModel,
    private val network: NetworkPolicy,
    private val runnerFactory: ((File, NpuContract) -> NpuRunner)?,
) : AcceleratorPreparer {
    private fun selftestFiles(m: ModelManifest, ctx: String) =
        m.files.filter { it.role == "selftest" && (it.context == "*" || it.context == ctx) }

    override fun unavailable(key: AcceleratorKey): String? {
        if (key.soc == null) return Reasons.SOC_UNKNOWN
        val m = store.manifest()
        if (store.npuFiles(key.soc, key.context).isEmpty()) return Reasons.NO_BUILD
        if (selftestFiles(m, key.context).none { it.localPath == "${SelfTestSet.DIR}/expected_${key.context}.json" }) return "no_selftest_set"
        if (runnerFactory == null) return Reasons.RUNTIME_MISSING
        return null
    }

    override fun download(key: AcceleratorKey) {
        val d = store.ensureNpu(key.soc, key.context, network, cpuSelfTestPassed = true)
        if (d.files == null) throw RecognizerException.DownloadFailed("npu/${key.soc}/${key.context}", d.reason)
        if (!network.isUnmetered() && selftestFiles(store.manifest(), key.context).any { !store.downloader.isInPlace(it) }) {
            throw RecognizerException.DownloadFailed(SelfTestSet.DIR, "metered_network")
        }
        for (e in selftestFiles(store.manifest(), key.context)) store.downloader.fetch(e)
    }

    override fun load(key: AcceleratorKey): PreparedAccelerator {
        val files = store.ensureNpu(key.soc, key.context, network, cpuSelfTestPassed = true).files
            ?: throw RecognizerException.BackendUnavailable("the NPU files are not in place")
        val contract = NpuContract.read(files.io)
        if (contract.context != key.context) throw RecognizerException.BackendUnavailable("io.json is for ${contract.context}")
        val masks = NpuMasks.read(files.masks, contract)
        val runner = if (contract.parts.isEmpty()) {
            if (files.parts.isNotEmpty()) throw RecognizerException.BackendUnavailable("the manifest lists parts but io.json has none")
            runnerFactory!!(files.model, contract)
        } else {
            chain(files, contract, runnerFactory!!)
        }
        val scorer = ExactTajweedEngine(model.dir, key.context, loadEngine = false)
        val dir = File(model.dir, SelfTestSet.DIR)
        val set = SelfTestSet.read(dir)
        val expected = SelfTestSet.readExpected(dir, key.context)
        return object : PreparedAccelerator {
            override val expected: SelfTestSet.Expected = expected
            override val chunkMs: Int = contract.chunkMs
            override val planted: Set<String> = set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id }.toSet()

            override fun engine(cpu: TajweedEngine, sampleRate: Int, onFallback: (String) -> Unit): TajweedEngine =
                NpuTajweedEngine(cpu, contract, masks, runner, sampleRate, onFallback).also { it.fallbackEnabled = true }

            override fun selfTest(): Map<String, SelfTestSet.ClipObserved> {
                // fallback off: an NPU error fails the self-test instead of moving to the CPU
                val e = NpuTajweedEngine(scorer, contract, masks, runner, 16_000) {}
                return SelfTestRunner.run(e, scorer, model.calibration, key.context, set, dir, TajweedBackend.INT8)
            }

            override fun close() {
                try { runner.close() } catch (_: Exception) {}
                scorer.close()
            }
        }
    }

    companion object {
        /** The parts of a chained build, matched to io.json's `parts` by file name (exactly the same set: a part
         *  the manifest lists but io.json does not name, or the other way round, refuses the build), opened in
         *  order: by the factory itself when it is an [NpuChainOpener] (the app's LiteRT factory: one shared
         *  environment), else part by part through it. */
        internal fun chain(files: NpuFiles, contract: NpuContract, factory: (File, NpuContract) -> NpuRunner): NpuRunner {
            val byName = files.parts.associateBy { it.name }
            val named = contract.parts.map { it.file }
            if (byName.size != files.parts.size || byName.keys != named.toSet()) {
                throw RecognizerException.BackendUnavailable("io.json names parts $named, the manifest has ${byName.keys.sorted()}")
            }
            if (factory is NpuChainOpener) return factory.openChain(contract, named.map { byName.getValue(it) })
            return NpuChainRunner.open(contract) { k, part -> factory(byName.getValue(named[k]), part) }
        }
    }
}
