package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.NpuFiles
import ai.quranlab.tajweedformer.NpuTajweedEngine
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.SelfTestRunner
import ai.quranlab.tajweedformer.SelfTestSet

import ai.quranlab.tajweedformer.TajweedEngineStream
import ai.quranlab.tajweedformer.HeadId
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import ai.quranlab.tajweedformer.npu.NpuChainOpener
import ai.quranlab.tajweedformer.npu.NpuChainRunner
import ai.quranlab.tajweedformer.npu.NpuContract
import ai.quranlab.tajweedformer.npu.NpuMasks
import ai.quranlab.tajweedformer.npu.NpuRunner
import java.io.File

/**
 * The Android NPU as an [AcceleratorPreparer]:
 *
 * - `download`: the SoC's `npu-*` files of the context and the `selftest` set (unmetered network only; resumable, each
 *   file verified as the CPU set is). On a first install none of them is fetched before the pipeline starts.
 * - `compile`: the NPU program loaded by [runnerFactory] (LiteRT with the vendor dispatch). The shipped builds are
 *   compiled ahead of time for each SoC, so the device step is LiteRT's CreateCompiledModel through the dispatch
 *   library: no on-device compiler runs, and LiteRT's compiler cache does not apply to them.
 * - `warmup`, `golden`: the host loop of [NpuTajweedEngine] (the exact engine's front end, the NPU per chunk), the
 *   golden clips also through the exact CPU engine in the same run; the raw outputs of both are compared.
 *
 * A chained build (io.json `parts`, the Exynos 2500) loads every part with [runnerFactory] and runs them as one
 * [NpuChainRunner]; everything else is the same over the whole chain.
 */
internal class NpuPreparer(
    private val store: ModelStore,
    private val model: ModelFolder,
    private val network: NetworkPolicy,
    private val runnerFactory: ((File, NpuContract) -> NpuRunner)?,
) : AcceleratorPreparer {
    private fun selftestFiles(m: ModelManifest, ctx: String) =
        m.files.filter { it.role == "selftest" && (it.context == "*" || it.context == ctx) }

    override fun unavailable(key: AcceleratorKey): String? {
        if (key.soc == null) return Reasons.SOC_UNKNOWN
        val m = store.manifest()
        if (store.npuFiles(key.soc, key.context).isEmpty()) return Reasons.NO_BUILD
        if (selftestFiles(m, key.context).none { it.localPath == "${SelfTestSet.DIR}/expected_${key.context}.json" }) return Reasons.NO_SELFTEST_SET
        if (runnerFactory == null) return Reasons.RUNTIME_MISSING
        return null
    }

    override fun download(key: AcceleratorKey) {
        if (inPlace(key)) return
        if (!network.isUnmetered()) throw StageWait("unmetered_network")
        val d = try {
            store.ensureNpu(key.soc, key.context, network, cpuSelfTestPassed = true)
        } catch (e: RecognizerException.DownloadFailed) {
            throw StageFailure(reason(e), "${e.path}: ${e.reason}", e)
        }
        if (d.files == null) {
            if (d.reason == "metered_network") throw StageWait("unmetered_network")
            throw StageFailure(Reasons.OTHER, "npu/${key.soc}/${key.context}: ${d.reason}")
        }
        for (e in selftestFiles(store.manifest(), key.context)) {
            try {
                store.downloader.fetch(e)
            } catch (x: RecognizerException.DownloadFailed) {
                throw StageFailure(reason(x), "${x.path}: ${x.reason}", x)
            }
        }
    }

    override fun programId(key: AcceleratorKey): String = store.npuProgramId(key.soc, key.context)

    override fun inPlace(key: AcceleratorKey): Boolean =
        store.isNpuReady(key.soc, key.context) && selftestFiles(store.manifest(), key.context).all { store.downloader.isInPlace(it) }

    private fun reason(e: RecognizerException.DownloadFailed): String {
        val r = e.reason.lowercase()
        return when {
            "space" in r || "storage" in r -> Reasons.STORAGE
            "sha" in r || "size" in r || "mismatch" in r || "signature" in r -> Reasons.VERIFY
            "network" in r || "http" in r || "timeout" in r -> Reasons.NETWORK
            else -> Reasons.NETWORK
        }
    }

    override fun compile(key: AcceleratorKey): PreparedAccelerator {
        val files = store.ensureNpu(key.soc, key.context, network, cpuSelfTestPassed = true).files
            ?: throw StageFailure(Reasons.OTHER, "the NPU files are not in place")
        val contract = NpuContract.read(files.io)
        if (contract.context != key.context) throw StageFailure(Reasons.COMPILE_ERROR, "io.json is for ${contract.context}")
        val masks = NpuMasks.read(files.masks, contract)
        val runner = if (contract.parts.isEmpty()) {
            if (files.parts.isNotEmpty()) throw StageFailure(Reasons.COMPILE_ERROR, "the manifest lists parts but io.json has none")
            runnerFactory!!(files.model, contract)
        } else {
            chain(files, contract, runnerFactory!!)
        }
        return Prepared(key, contract, masks, runner)
    }

    private inner class Prepared(
        private val key: AcceleratorKey,
        private val contract: NpuContract,
        private val masks: NpuMasks,
        private val runner: NpuRunner,
    ) : PreparedAccelerator {
        private val dir = File(model.dir, SelfTestSet.DIR)
        private val set = SelfTestSet.read(dir)
        override val expected: SelfTestSet.Expected = SelfTestSet.readExpected(dir, key.context)
        override val chunkMs: Int = contract.chunkMs
        override val planted: Set<String> = set.clips.filter { it.kind == SelfTestSet.PLANTED }.map { it.id }.toSet()
        override val tolerance = store.manifest().rawToleranceFor("npu:${key.soc}:${key.context}")

        /** The widest value any head of this build can take: int16 codes times the head output's scale. */
        override val range: HeadRange = HeadRange.fromLogitBound(contract.heads.maxOf { 32768.0 * contract.outputs.getValue(it.output).scale })

        private fun engine(rate: Int) = NpuTajweedEngine(contract, masks, runner, rate)

        override fun stream(heads: Set<HeadId>, sampleRate: Int): TajweedEngineStream = engine(sampleRate).createStream(heads)

        override fun warmup() {
            val clip = set.clips.first()
            val audio = FloatArray(16_000) + SelfTestSet.samples(File(dir, clip.file))
            stream(HeadId.BASE_HEADS, 16_000).use { s ->
                var at = 0
                val got = ArrayList<ai.quranlab.tajweedformer.HeadValues>()
                while (at < audio.size) {
                    val k = minOf(SelfTestRunner.PIECE, audio.size - at)
                    got += s.pushAudio(audio, at, k)
                    at += k
                }
                got += s.finish()
                for ((i, h) in got.withIndex()) {
                    Failover.check(h, range)?.let { throw StageFailure(Reasons.NON_FINITE, "warm-up chunk $i: $it", clip = clip.id) }
                }
            }
        }

        override fun golden(abort: () -> Boolean): GoldenRun {
            // the CPU engine first (it holds the process's one engine stream while it runs), then the NPU path
            val cpu = ExactTajweedEngine(model.dir, key.context)
            val cpuObs = try {
                SelfTestRunner.run({ cpu.createStream() }, set, dir, abort)
            } finally {
                cpu.close()
            }
            val accObs = SelfTestRunner.run({ stream(HeadId.BASE_HEADS, 16_000) }, set, dir, abort)
            return GoldenRun(accObs, cpuObs)
        }

        override fun close() {
            try { runner.close() } catch (_: Exception) {}
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
