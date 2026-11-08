package ai.quranlab.tajweedformer

import java.io.File

/**
 * A V4 model's calibration.json (`quranlab.tajweed-calibration/1`): the presets, the per-class
 * thresholds of every shipped context (and per backend where they differ), the fusion weights, the
 * madd cuts and the status of every check. It ships inside the model folder, listed and hashed in
 * the signed manifest; the SDK only reads it.
 *
 * The shipped contexts are whatever [contexts] holds (c16_l64, c24_l72, c24_l256, ...): nothing in
 * the SDK assumes a fixed set. A class with no entry, or a preset with no threshold, is reported as
 * disabled, never guessed. Every number traces to a file named in its `evidence` / `source` fields,
 * which are kept in the `raw` maps as they are.
 */
class Calibration private constructor(
    val format: String,
    val modelId: String,
    val checkpointSha256: String?,
    val tokensSha256: String?,
    val defaultContext: String,
    val defaultPreset: TajweedPreset,
    val presets: Map<TajweedPreset, PresetInfo>,
    /** The site classes in calibration.json's order (the order verdicts and settings use). */
    val classes: List<SiteClass>,
    /** The shipped contexts by name, in the file's order. */
    val contexts: Map<String, ContextCalibration>,
    /** The whole file as parsed (see [Json]). */
    val raw: Map<String, Any?>,
) {
    /** A preset's target: the fraction of correct sites of a class flagged ([siteFa]). */
    data class PresetInfo(val siteFa: Double, val label: String)

    class ContextCalibration(
        val name: String,
        /** Heads fused (`["log_probs", "inter1_log_probs"]`). */
        val fusionHeads: List<String>,
        private val fusionA: Map<String, Double>,
        /** Classes the file describes; a class missing here is `off` with no thresholds. */
        val sites: Map<SiteClass, SiteCalibration>,
        /** `backend_overrides[backend].sites[class].thresholds`, where the file has them. */
        private val backendThresholds: Map<TajweedBackend, Map<SiteClass, Map<TajweedPreset, Double?>>>,
        val madd: MaddCalibration,
        val qalqalah: FeatureCalibration,
        val gemination: FeatureCalibration,
        val ghunna: FeatureCalibration,
        val tafkheem: FeatureCalibration,
        val raw: Map<String, Any?>,
    ) {
        /** The fusion weight `a` of [cls]: fused = a * LLR(log_probs) + (1 - a) * LLR(inter1). */
        fun fusionWeight(cls: SiteClass): Double =
            fusionA[cls.key] ?: fusionA["default"] ?: throw RecognizerException.CalibrationInvalid("no fusion weight for ${cls.key} in $name")

        fun status(cls: SiteClass): CheckStatus = sites[cls]?.status ?: CheckStatus.OFF

        /** The calibrated threshold of [cls] at [preset] for heads from [backend], or null when the
         *  file has none (the class is then disabled). */
        fun threshold(cls: SiteClass, preset: TajweedPreset, backend: TajweedBackend = TajweedBackend.INT8): Double? {
            val t = backendThresholds[backend]?.get(cls) ?: sites[cls]?.thresholds ?: return null
            return t[preset]
        }
    }

    class SiteCalibration(
        val status: CheckStatus,
        /** The presets the file gives a threshold for. */
        val thresholds: Map<TajweedPreset, Double?>,
        val raw: Map<String, Any?>,
    )

    class MaddCalibration(
        val status: CheckStatus,
        /** Lazim's own status (`cuts.lazim.status`), OFF when absent. */
        val lazimStatus: CheckStatus,
        val unit: String?,
        val minShortVowels: Int?,
        /** `cuts` as in the file: per madd class, the count boundaries ("2|4": 8.25). */
        val cuts: Map<String, Any?>,
        val presetCuts: Map<String, Any?>,
        val raw: Map<String, Any?>,
    )

    /** qalqalah, gemination, ghunna, tafkheem: a status, the score threshold [tau] with its
     *  per-preset values, and the file's other fields in [raw]. */
    class FeatureCalibration(val status: CheckStatus, val raw: Map<String, Any?>) {
        /** A number field such as `tau`, or null. */
        fun number(key: String): Double? = raw[key] as? Double

        /** `preset_tau[preset]` when the file has that key, else `tau`. */
        fun tau(preset: TajweedPreset): Double? {
            val pt = raw["preset_tau"] as? Map<*, *>
            return if (pt != null && pt.containsKey(preset.key)) pt[preset.key] as? Double else raw["tau"] as? Double
        }
    }

    fun context(name: String): ContextCalibration =
        contexts[name] ?: throw RecognizerException.CalibrationInvalid("no context $name")

    companion object {
        const val FORMAT = "quranlab.tajweed-calibration/1"
        const val FILE_NAME = "calibration.json"

        @JvmStatic
        fun read(file: File): Calibration {
            if (!file.isFile) throw RecognizerException.ModelMissing(file.path)
            return parse(file.readText(Charsets.UTF_8))
        }

        /** Parses and checks a calibration.json; throws [RecognizerException.CalibrationInvalid]. */
        @JvmStatic
        fun parse(text: String): Calibration = try {
            build(J.obj(Json.parse(text), "calibration.json"))
        } catch (e: JsonException) {
            throw RecognizerException.CalibrationInvalid(e.message ?: "not JSON")
        }

        private fun status(m: Map<String, Any?>, what: String): CheckStatus {
            val s = m["status"] ?: return CheckStatus.OFF
            return CheckStatus.fromKey(s as? String ?: "") ?: throw JsonException("$what.status '$s' is not off, experimental or validated")
        }

        private fun thresholds(v: Any?, what: String): Map<TajweedPreset, Double?>? {
            val m = v as? Map<*, *> ?: return null
            if (m.isEmpty()) return null
            val out = LinkedHashMap<TajweedPreset, Double?>()
            for ((k, x) in m) {
                val p = TajweedPreset.fromKey(k.toString()) ?: continue
                if (x != null && (x !is Double || !x.isFinite())) throw JsonException("$what.$k is not a finite number")
                out[p] = x as Double?
            }
            return out
        }

        private fun build(m: Map<String, Any?>): Calibration {
            val format = J.str(m, "format")
            if (format != FORMAT) throw RecognizerException.CalibrationInvalid("format $format is not $FORMAT")
            val presetsRaw = J.objOrNull(m, "presets") ?: emptyMap()
            val presets = LinkedHashMap<TajweedPreset, PresetInfo>()
            for (p in TajweedPreset.entries) {
                val o = J.objOrNull(presetsRaw, p.key) ?: continue
                presets[p] = PresetInfo((o["site_fa"] as? Double) ?: Double.NaN, o["label"] as? String ?: p.key)
            }
            val classes = J.list(m, "classes").map { v ->
                val k = v as? String ?: throw JsonException("classes holds a non-string")
                SiteClass.fromKey(k) ?: throw JsonException("class $k is not one this SDK knows")
            }
            if (classes.toSet().size != classes.size) throw JsonException("a class is listed twice")
            val ctxRaw = J.obj(m["contexts"], "contexts")
            if (ctxRaw.isEmpty()) throw JsonException("no contexts")
            val contexts = LinkedHashMap<String, ContextCalibration>()
            for ((name, v) in ctxRaw) {
                if (name.isEmpty()) throw JsonException("a context has an empty name")
                contexts[name] = context(name, J.obj(v, "contexts.$name"))
            }
            val defaultContext = J.str(m, "default_context")
            if (defaultContext !in contexts) throw JsonException("default_context $defaultContext is not in contexts")
            val dp = J.str(m, "default_preset")
            val defaultPreset = TajweedPreset.fromKey(dp) ?: throw JsonException("default_preset $dp is not a preset")
            return Calibration(
                format, J.str(m, "model_id"), m["checkpoint_sha256"] as? String, m["tokens_sha256"] as? String,
                defaultContext, defaultPreset, presets, classes, contexts, m,
            )
        }

        private fun context(name: String, c: Map<String, Any?>): ContextCalibration {
            val w = "contexts.$name"
            val fusion = J.objOrNull(c, "fusion") ?: emptyMap()
            val heads = (fusion["heads"] as? List<*>)?.map { it as? String ?: throw JsonException("$w.fusion.heads") }
                ?: listOf("log_probs", "inter1_log_probs")
            val a = (J.objOrNull(fusion, "a") ?: emptyMap()).mapValues { (k, v) ->
                val d = v as? Double ?: throw JsonException("$w.fusion.a.$k is not a number")
                if (!(d in 0.0..1.0)) throw JsonException("$w.fusion.a.$k is outside 0..1")
                d
            }
            val sitesRaw = J.objOrNull(c, "sites") ?: emptyMap()
            val sites = LinkedHashMap<SiteClass, SiteCalibration>()
            for ((k, v) in sitesRaw) {
                val cls = SiteClass.fromKey(k) ?: continue
                val s = J.obj(v, "$w.sites.$k")
                sites[cls] = SiteCalibration(status(s, "$w.sites.$k"), thresholds(s["thresholds"], "$w.sites.$k.thresholds") ?: emptyMap(), s)
            }
            val backends = LinkedHashMap<TajweedBackend, Map<SiteClass, Map<TajweedPreset, Double?>>>()
            for ((bk, bv) in J.objOrNull(c, "backend_overrides") ?: emptyMap()) {
                val backend = TajweedBackend.fromKey(bk) ?: continue
                val bs = (bv as? Map<*, *>)?.get("sites") as? Map<*, *> ?: continue
                val per = LinkedHashMap<SiteClass, Map<TajweedPreset, Double?>>()
                for ((ck, cv) in bs) {
                    val cls = SiteClass.fromKey(ck.toString()) ?: continue
                    thresholds((cv as? Map<*, *>)?.get("thresholds"), "$w.backend_overrides.$bk.sites.$ck.thresholds")?.let { per[cls] = it }
                }
                backends[backend] = per
            }
            val maddRaw = J.objOrNull(c, "madd") ?: emptyMap()
            val cuts = J.objOrNull(maddRaw, "cuts") ?: emptyMap()
            val lazimStatus = J.objOrNull(cuts, "lazim")?.let { status(it, "$w.madd.cuts.lazim") } ?: CheckStatus.OFF
            val madd = MaddCalibration(
                status(maddRaw, "$w.madd"), lazimStatus, maddRaw["unit"] as? String,
                (maddRaw["min_short_vowels"] as? Double)?.toInt(), cuts, J.objOrNull(maddRaw, "preset_cuts") ?: emptyMap(), maddRaw,
            )

            fun feature(key: String): FeatureCalibration {
                val f = J.objOrNull(c, key) ?: return FeatureCalibration(CheckStatus.OFF, emptyMap())
                return FeatureCalibration(status(f, "$w.$key"), f)
            }
            return ContextCalibration(
                name, heads, a, sites, backends, madd,
                feature("qalqalah"), feature("gemination"), feature("ghunna"), feature("tafkheem"), c,
            )
        }
    }
}
