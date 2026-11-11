package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorState
import ai.quranlab.tajweedformer.accel.Reasons
import ai.quranlab.tajweedformer.accel.Stages

/**
 * What ran or will run the model: the CPU engine or an accelerator, and where the accelerator's promotion stands.
 * Plain data, read from a Model (`model.backend`: what the next Session will use), a Session or a chunk.
 *
 * The CPU engine serves from the first take. On a phone with a supported NPU the SDK prepares the accelerator in the
 * background (download, compile, a test against the CPU engine) and later takes run on it once it passed.
 */
public class TajweedBackend internal constructor(
    /** `cpu` or `accelerator`. */
    public val kind: Kind,
    /** `cpu`, or the accelerator build: `npu:<soc>` (for example `npu:tensor_g4`). */
    public val name: String,
    /** Where the promotion stands. */
    public val stage: Stage,
    /** Null, or the stable reason code of a failed or demoted promotion (the codes of the incident reports:
     *  `golden_mismatch`, `compile_error`, `failover`, `denylist`, ...). */
    public val reason: String? = null,
) {
    public enum class Kind { CPU, ACCELERATOR }

    public enum class Stage {
        /** No accelerator on this device, or its promotion has not started or did not succeed. */
        CPU,
        DOWNLOADING,
        COMPILING,
        TESTING,
        /** The accelerator passed its test: takes run on it. */
        PROMOTED,
        /** The accelerator was promoted and then taken back to the CPU after a failure in a take. */
        DEMOTED,
    }

    override fun equals(other: Any?): Boolean =
        other is TajweedBackend && other.kind == kind && other.name == name && other.stage == stage && other.reason == reason

    override fun hashCode(): Int = listOf(kind, name, stage, reason).hashCode()

    override fun toString(): String = "TajweedBackend(${kind.name.lowercase()}, $name, ${stage.name.lowercase()}" + (reason?.let { ", $it" } ?: "") + ")"

    internal companion object {
        val CPU = TajweedBackend(Kind.CPU, "cpu", Stage.CPU)

        /** The Backend a take would get now, from the accelerator's state ([soc] names the build). */
        fun of(state: AcceleratorState?, soc: String?, cpuReason: String? = null): TajweedBackend = when (state) {
            null, is AcceleratorState.Cpu -> CPU
            is AcceleratorState.Preparing -> TajweedBackend(Kind.CPU, "cpu", when (state.stage) {
                Stages.DOWNLOAD -> Stage.DOWNLOADING
                Stages.COMPILE -> Stage.COMPILING
                else -> Stage.TESTING
            })
            is AcceleratorState.Ready ->
                // promoted, but this take may still run on the CPU (thermal, memory, the denylist, a busy accelerator)
                if (cpuReason != null) TajweedBackend(Kind.CPU, "cpu", Stage.PROMOTED, cpuReason.takeIf { it == Reasons.DENYLIST })
                else TajweedBackend(Kind.ACCELERATOR, "npu:${state.soc ?: soc ?: "unknown"}", Stage.PROMOTED)
            is AcceleratorState.Disabled ->
                TajweedBackend(Kind.CPU, "cpu", if (state.reason == Reasons.FAILOVER) Stage.DEMOTED else Stage.CPU,
                    if (state.reason in Reasons.PIPELINE) state.reason else Reasons.OTHER)
        }

        /** The Backend that ran a chunk. */
        fun ofChunk(b: Backend, soc: String?): TajweedBackend =
            if (b == Backend.NPU) TajweedBackend(Kind.ACCELERATOR, "npu:${soc ?: "unknown"}", Stage.PROMOTED) else CPU
    }
}
