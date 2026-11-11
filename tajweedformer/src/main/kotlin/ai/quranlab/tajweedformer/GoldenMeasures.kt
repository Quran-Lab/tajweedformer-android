package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.accel.AcceleratorEnvironment
import ai.quranlab.tajweedformer.accel.AcceleratorKey
import ai.quranlab.tajweedformer.cloud.IssueQueue
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The golden stage's measures as the record a device would report: one per accelerator build that reached the golden
 * stage, per context, per run. SDK 3.0.0 sends it nowhere. It is the last part of the self-test event's text
 * ([SdkObserver.onSelfTest]), after [MARK], so the output of a bench run can be pasted to whoever measures a
 * tolerance for the next build of a version.
 *
 * The fields, their names and their units are the server notes' ("The golden measures a device would report"):
 * `build` (the manifest's build key, `npu:<soc>:<context>`), `context`, `model_version`, `model_build`, `clips`,
 * `clean_clips`, `tokens_expected`, `heads` (per CTC head `token_edits` in tokens, `lp_mean` and `lp_p99` in nats,
 * `frames`; `attr` with `p_mean`, a probability, and `values`), `passed`; then the device fields of an accelerator
 * incident and nothing else: `platform`, `os_version`, `os_build`, `device_model`, `chip`, `delegate`, `sdk_version`.
 * `heads` is the `detail.divergence.heads` of an incident report, made by the same code. No key, install, app or
 * account identifier, no audio and no text.
 */
internal object GoldenMeasures {
    /** What stands before the record in the self-test event's text. */
    const val MARK = "golden_measures "

    /** The record of one golden run, or null when the run measured nothing (a clip was missing or not finite). */
    fun record(
        key: AcceleratorKey, env: AcceleratorEnvironment, expected: SelfTestSet.Expected, planted: Set<String>,
        verdict: SelfTestSet.Companion.GoldenVerdict,
    ): Map<String, Any?>? {
        val d = verdict.divergence ?: return null
        if (verdict.measures == null) return null
        val o = LinkedHashMap<String, Any?>()
        o["build"] = "npu:${key.soc ?: "-"}:${key.context}"
        o["context"] = key.context
        o["model_version"] = ModelIds.version(key.revision)
        o["model_build"] = ModelIds.build(key.revision)
        o["clips"] = expected.clips.size
        o["clean_clips"] = expected.clips.keys.count { it !in planted }
        val measured = IssueQueue.divergence(d)
        o["tokens_expected"] = measured["tokens_expected"]
        o["heads"] = measured["heads"] ?: emptyMap<String, Any?>()
        o["passed"] = verdict.passed
        // the device, exactly as an incident report says it (the same cleaning), absent when unknown
        val device = IssueQueue.detail(AcceleratorIncident(kind = AcceleratorIncident.PROMOTION_FAILED, stage = "golden", cause = "other",
            revision = key.revision, context = key.context, accelerator = key.build, deviceModel = env.deviceModelCode,
            osBuild = env.osBuildReport, chip = key.soc, delegate = env.delegate))
        o["platform"] = SdkInfo.PLATFORM
        env.osVersion?.take(32)?.takeIf { it.isNotEmpty() }?.let { o["os_version"] = it }
        for (k in listOf("os_build", "device_model", "chip", "delegate")) device[k]?.let { o[k] = it }
        o["sdk_version"] = SdkInfo.VERSION
        return o
    }

    /** The self-test event's text: what it always said, then the record when there is one. */
    fun text(reasonAndDetail: String, record: Map<String, Any?>?): String =
        if (record == null) reasonAndDetail else "$reasonAndDetail | $MARK${json(record)}"

    /** The record found in a self-test event's text, as its JSON text; null when the text carries none. */
    fun find(text: String): String? = text.substringAfter(MARK, "").takeIf { it.startsWith("{") }

    /** JSON with the record's number form: a dot, no exponent, at most 6 decimals. */
    fun json(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append((v as Number).toLong())
            is Number -> sb.append(number(v.toDouble()))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(Json.write(k.toString())).append(':')
                    write(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            else -> sb.append(Json.write(v.toString()))
        }
    }

    /** A number with a dot, no exponent and at most 6 decimals (`0.001234`, `12`, `0`); a value that is not finite is 0. */
    fun number(x: Double): String {
        if (!x.isFinite()) return "0"
        val d = BigDecimal(x).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros()
        return if (d.signum() == 0) "0" else d.toPlainString()
    }
}
