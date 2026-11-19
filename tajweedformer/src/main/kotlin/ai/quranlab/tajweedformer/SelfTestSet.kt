package ai.quranlab.tajweedformer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The self-test set (the golden set), shipped in the model folder under `selftest/` (role `selftest`): five clips as
 * 16 kHz int16 (the three Basmala must-decode clips, Husary_112001, and Husary_112001_vsub2, a clip with a spliced
 * vowel that int8 and float builds hear differently) and per context the greedy tokens of the exact CPU engine.
 * SDK 4.0: the clips are the accelerator's golden test (Recognizer.promote) and the model's sample clips
 * (`model.sampleClips`), plain files of the build; a build that lists no self-test set gets no NPU program
 * (core.AcceleratorGate).
 */
internal class SelfTestSet(val clips: List<Clip>, val rules: Map<String, Any?>) {
    data class Clip(
        val id: String, val file: String, val samples: Int, val sampleRate: Int, val mustDecode: Boolean,
        /** `clean` (a real recitation: its tokens must be reproduced) or `planted` (a spliced clip: a known int8
         *  against float difference, so its tokens are not compared; it enters the log-prob distances). */
        val kind: String = CLEAN,
    )

    /** One context's expected tokens (`expected_<ctx>.json`; only `tokens` of each clip is read). */
    class Expected(val context: String, val clips: Map<String, ClipExpected>)

    data class ClipExpected(val tokens: List<Int>)

    companion object {
        const val FORMAT = "quranlab.selftest/1"
        const val EXPECTED_FORMAT = "quranlab.selftest-expected/1"
        const val DIR = "selftest"
        const val CLEAN = "clean"
        const val PLANTED = "planted"

        /** The clean clips (id, must decode), from the engine's parity audio. */
        @JvmField
        val CLIPS = listOf("Husary_001001" to true, "Abdul_001001" to true, "Saood_001001" to true, "Husary_112001" to false)

        /** The spliced clip (a vowel substitution spliced into Husary 112:1). */
        const val PLANTED_CLIP = "Husary_112001_vsub2"

        /** Every clip a golden run must decode: the clean ones and the planted one. An expected file without one of
         *  them fails the golden stage (`missing_clip`). */
        @JvmField
        val REQUIRED = CLIPS.map { it.first } + PLANTED_CLIP

        @JvmStatic
        fun read(dir: File): SelfTestSet {
            val m = J.obj(Json.parse(File(dir, "selftest.json").readText()), "selftest.json")
            if (m["format"] != FORMAT) throw RecognizerException.ManifestInvalid("selftest.json format ${m["format"]}")
            val clips = J.list(m, "clips").map { v ->
                val o = J.obj(v, "clips")
                Clip(J.str(o, "id"), J.str(o, "file"), J.int(o, "samples"), J.int(o, "sample_rate"), o["must_decode"] == true,
                    (o["kind"] as? String) ?: if (o["planted"] != null) PLANTED else CLEAN)
            }
            @Suppress("UNCHECKED_CAST")
            return SelfTestSet(clips, (m["rules"] as? Map<String, Any?>) ?: emptyMap())
        }

        @JvmStatic
        fun readExpected(dir: File, context: String): Expected {
            val m = J.obj(Json.parse(File(dir, "expected_$context.json").readText()), "expected_$context.json")
            if (m["format"] != EXPECTED_FORMAT) throw RecognizerException.ManifestInvalid("expected_$context.json format ${m["format"]}")
            val clips = J.obj(m["clips"], "clips").mapValues { (id, v) ->
                ClipExpected(J.list(J.obj(v, id), "tokens").map { (it as Double).toInt() })
            }
            return Expected(J.str(m, "context"), clips)
        }

        /** A clip's int16 file as the float samples the SDK pushes (q / 32768). */
        @JvmStatic
        fun samples(file: File): FloatArray {
            val sb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        }
    }
}
