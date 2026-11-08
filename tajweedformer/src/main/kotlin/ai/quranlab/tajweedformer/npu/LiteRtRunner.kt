package ai.quranlab.tajweedformer.npu

import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.engine.NativeLibrary
import java.io.File

/** native/litert_npu.c: LiteRT's C API through dlopen. */
internal object LiteRtJni {
    init {
        NativeLibrary.load()
    }

    @JvmStatic external fun open(model: String, dispatchDir: String?, inputs: Array<String>, outputs: Array<String>): Long
    @JvmStatic external fun run(handle: Long, inputs: Array<ShortArray>, outputs: Array<ShortArray>)
    @JvmStatic external fun close(handle: Long)

    /** A LiteRT environment shared by the parts of a chained build; released with [closeEnv]. */
    @JvmStatic external fun openEnv(dispatchDir: String?): Long
    @JvmStatic external fun openPart(env: Long, model: String, inputs: Array<String>, outputs: Array<String>): Long
    @JvmStatic external fun closeEnv(env: Long)
}

/**
 * A runner factory that opens a chained build ([NpuContract.parts]) itself instead of part by part through
 * its function type, e.g. every part in ONE LiteRT environment ([LiteRtRunner.Factory]). [parts] are the
 * part files in the chain's order.
 */
interface NpuChainOpener {
    fun openChain(contract: NpuContract, parts: List<File>): NpuRunner
}

/**
 * Runs an AOT-compiled NPU model (LiteRT with the vendor dispatch) through LiteRT's C API, int16 in
 * and out, every tensor by its io.json name.
 *
 * Needs in the app: `libLiteRt.so` (LiteRT 2.2.0, `com.google.ai.edge.litert:litert:2.2.0`) and the
 * SoC's dispatch library (`libLiteRtDispatch_GoogleTensor.so` or `libLiteRtDispatch_Samsung.so`) in
 * [dispatchDir] (on Android the app's native library folder). Without them it throws
 * [RecognizerException.BackendUnavailable] and the recognizer runs on the CPU.
 *
 * A chained build (the Exynos 2500) opens through [Factory]: ONE LiteRT environment for every part, set up as
 * the board runner that verified the chain set it up (native/litert_npu.c), one runner per part with the
 * part's own contract ([NpuContract.partContract]), the parts run by [NpuChainRunner].
 *
 * On hardware (11-08): the native code under this runner (native/litert_npu.c, through its device harness)
 * ran on the Exynos 2500 (chained) and Exynos 2600 (one model) boards with every head byte-identical to the
 * bench runner. This Kotlin / JNI layer itself has not run in an app on an NPU phone yet, nor on Google
 * Tensor; the self-test against the CPU guards every use.
 */
class LiteRtRunner private constructor(private val contract: NpuContract, open: (Array<String>, Array<String>) -> Long) : NpuRunner {
    /** One model with its own LiteRT environment (the vendor dispatch library from [dispatchDir]). */
    constructor(model: File, contract: NpuContract, dispatchDir: String?) :
        this(contract, { ins, outs -> LiteRtJni.open(model.path, dispatchDir, ins, outs) })

    private val inputNames = contract.inputs.keys.toList()
    private val outputNames = contract.outputs.keys.toList()
    private var handle: Long = native { open(inputNames.toTypedArray(), outputNames.toTypedArray()) }

    override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
        check(handle != 0L) { "the NPU runner is closed" }
        val ins = Array(inputNames.size) { i ->
            val name = inputNames[i]
            val v = inputs[name] ?: throw IllegalArgumentException("no input $name")
            require(v.size == contract.inputs.getValue(name).size) { "$name has ${v.size} values" }
            v
        }
        val outs = Array(outputNames.size) { ShortArray(contract.outputs.getValue(outputNames[it]).size) }
        LiteRtJni.run(handle, ins, outs)
        return outputNames.indices.associate { outputNames[it] to outs[it] }
    }

    override fun close() {
        if (handle != 0L) {
            LiteRtJni.close(handle)
            handle = 0
        }
    }

    /**
     * The app's runner factory: a one-model build gets [LiteRtRunner] with its own environment (as before);
     * a chained build gets ONE shared environment ([LiteRtJni.openEnv]) and a runner per part in it, chained by
     * [NpuChainRunner]. The environment lives until the last part is closed.
     */
    class Factory(private val dispatchDir: String?) : (File, NpuContract) -> NpuRunner, NpuChainOpener {
        override fun invoke(model: File, contract: NpuContract): NpuRunner = LiteRtRunner(model, contract, dispatchDir)

        override fun openChain(contract: NpuContract, parts: List<File>): NpuRunner {
            require(parts.size == contract.parts.size) { "${parts.size} files for ${contract.parts.size} parts" }
            val env = native { LiteRtJni.openEnv(dispatchDir) }
            try {
                return NpuChainRunner.open(contract) { k, part ->
                    LiteRtRunner(part) { ins, outs -> LiteRtJni.openPart(env, parts[k].path, ins, outs) }
                }
            } finally {
                LiteRtJni.closeEnv(env) // the opened parts hold it
            }
        }
    }

    private companion object {
        inline fun <T> native(block: () -> T): T = try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            throw RecognizerException.BackendUnavailable("the JNI library has no NPU runner: ${e.message}")
        }
    }
}
