package ai.quranlab.tajweedformer

/** A per-class setting: a preset's calibrated threshold, or a threshold of its own. */
sealed interface ClassOverride {
    data class Preset(val preset: TajweedPreset) : ClassOverride

    /** A fused-LLR cut, finite, in -20..20. App code passing anything else throws
     *  [RecognizerException.InvalidAppOption]. */
    class Threshold(val threshold: Double) : ClassOverride {
        init {
            if (!threshold.isFinite() || threshold < -20.0 || threshold > 20.0) {
                throw RecognizerException.InvalidAppOption("threshold", "$threshold is not a finite number in -20..20")
            }
        }

        override fun equals(other: Any?) = other is Threshold && other.threshold == threshold
        override fun hashCode() = threshold.hashCode()
        override fun toString() = "Threshold($threshold)"
    }

    companion object {
        @JvmStatic fun preset(preset: TajweedPreset): ClassOverride = Preset(preset)
        @JvmStatic fun threshold(threshold: Double): ClassOverride = Threshold(threshold)
    }
}

/**
 * Tajweed settings from app code: they win over the portal's and over calibration.json's. Every
 * field is optional; null or absent lets the lower layers show through.
 *
 * An invalid value is a programming error and throws [RecognizerException.InvalidAppOption]: an
 * out-of-range threshold when it is made, a context the model does not ship when it is resolved.
 */
data class TajweedOptions(
    /** The global preset. Per-class [overrides] and [featurePresets] (app, then portal) win over it. */
    val preset: TajweedPreset? = null,
    val overrides: Map<SiteClass, ClassOverride> = emptyMap(),
    val checks: Map<TajweedCheck, Boolean> = emptyMap(),
    /** One of calibration.json's contexts ("c16_l64", ...). */
    val context: String? = null,
    /** A preset for madd, qalqalah or gemination alone (the JSON `overrides` keys of those names,
     *  which take a preset only). */
    val featurePresets: Map<TajweedFeature, TajweedPreset> = emptyMap(),
) {
    companion object {
        private val KEYS = setOf("preset", "overrides", "checks", "context")

        /**
         * Options from a JSON object of the remote block's shape without `v` (for apps that keep
         * their settings as JSON). Unlike the portal's block, anything invalid throws
         * [RecognizerException.InvalidAppOption]; a context is checked against the model when the
         * settings are resolved.
         */
        @JvmStatic
        fun fromJson(json: String): TajweedOptions = fromMap(
            try {
                Json.parse(json)
            } catch (e: JsonException) {
                throw RecognizerException.InvalidAppOption("json", e.message ?: "not JSON")
            },
        )

        /** The same from a parsed JSON value (see [Json]). */
        @JvmStatic
        fun fromMap(value: Any?): TajweedOptions {
            if (value == null) return TajweedOptions()
            val m = value as? Map<*, *> ?: throw RecognizerException.InvalidAppOption("options", "not an object")
            for (k in m.keys) if (k !in KEYS) throw RecognizerException.InvalidAppOption(k.toString(), "unknown option")
            val preset = if (m.containsKey("preset")) {
                val p = m["preset"] as? String
                (if (p == null) null else TajweedPreset.fromKey(p))
                    ?: throw RecognizerException.InvalidAppOption("preset", "unknown preset ${m["preset"]}")
            } else null
            val context = if (m.containsKey("context")) {
                m["context"] as? String ?: throw RecognizerException.InvalidAppOption("context", "not a string")
            } else null
            val overrides = LinkedHashMap<SiteClass, ClassOverride>()
            val features = LinkedHashMap<TajweedFeature, TajweedPreset>()
            if (m.containsKey("overrides")) {
                val ov = m["overrides"] as? Map<*, *> ?: throw RecognizerException.InvalidAppOption("overrides", "not an object")
                for ((k, o) in ov) {
                    val name = "overrides.$k"
                    val feature = TajweedFeature.fromKey(k.toString())
                    val cls = SiteClass.fromKey(k.toString())
                    if (cls == null && feature == null) throw RecognizerException.InvalidAppOption(name, "unknown class")
                    val om = o as? Map<*, *> ?: throw RecognizerException.InvalidAppOption(name, "not an object")
                    val hasP = om.containsKey("preset")
                    val hasT = om.containsKey("threshold")
                    if (hasP == hasT) throw RecognizerException.InvalidAppOption(name, "needs exactly one of preset and threshold")
                    if (feature != null) {
                        if (hasT) throw RecognizerException.InvalidAppOption(name, "${feature.key} takes a preset only")
                        val fp = om["preset"] as? String
                        features[feature] = (if (fp == null) null else TajweedPreset.fromKey(fp))
                            ?: throw RecognizerException.InvalidAppOption(name, "unknown preset ${om["preset"]}")
                        continue
                    }
                    overrides[cls!!] = if (hasP) {
                        val p = om["preset"] as? String
                        ClassOverride.Preset(
                            (if (p == null) null else TajweedPreset.fromKey(p))
                                ?: throw RecognizerException.InvalidAppOption(name, "unknown preset ${om["preset"]}"),
                        )
                    } else {
                        val t = om["threshold"] as? Double ?: throw RecognizerException.InvalidAppOption(name, "threshold is not a number")
                        if (!t.isFinite() || t < -20.0 || t > 20.0) throw RecognizerException.InvalidAppOption(name, "threshold outside -20..20")
                        ClassOverride.Threshold(t)
                    }
                }
            }
            val checks = LinkedHashMap<TajweedCheck, Boolean>()
            if (m.containsKey("checks")) {
                val ch = m["checks"] as? Map<*, *> ?: throw RecognizerException.InvalidAppOption("checks", "not an object")
                for ((k, b) in ch) {
                    val c = TajweedCheck.fromKey(k.toString()) ?: throw RecognizerException.InvalidAppOption("checks.$k", "unknown check")
                    checks[c] = b as? Boolean ?: throw RecognizerException.InvalidAppOption("checks.$k", "not a boolean")
                }
            }
            return TajweedOptions(preset, overrides, checks, context, features)
        }
    }
}

/**
 * The portal's `tajweed` block, validated. Parsing never throws: an invalid field
 * is dropped (and named in [dropped]), never the config; a block whose `v` is not 1 is ignored
 * whole, so a newer portal never half-applies.
 */
class RemoteTajweed private constructor(
    val preset: TajweedPreset?,
    val overrides: Map<SiteClass, ClassOverride>?,
    val checks: Map<TajweedCheck, Boolean>?,
    /** As sent; whether the model ships it is checked when the settings are resolved. */
    val context: String?,
    /** Presets for madd, qalqalah or gemination alone. */
    val featurePresets: Map<TajweedFeature, TajweedPreset>?,
    /** What validation dropped, sorted: "remote.preset", "remote.overrides.cons_sub", ... */
    val dropped: List<String>,
    /** False when there was no usable block (absent, not an object, or a `v` other than 1). */
    val present: Boolean,
) {
    companion object {
        /** No block: every setting comes from the app and calibration.json. */
        @JvmField val ABSENT = RemoteTajweed(null, null, null, null, null, emptyList(), false)

        private val PRESETS = TajweedPreset.entries.map { it.key }

        /** The block from its parsed JSON value (see [Json]); null is an absent block. */
        @JvmStatic
        fun parse(raw: Any?): RemoteTajweed {
            if (raw == null) return ABSENT
            val m = raw as? Map<*, *> ?: return RemoteTajweed(null, null, null, null, null, listOf("remote"), false)
            val v = m["v"]
            if (!(v is Double && v == 1.0)) return RemoteTajweed(null, null, null, null, null, listOf("remote.v"), false)
            val dropped = ArrayList<String>()
            var preset: TajweedPreset? = null
            var context: String? = null
            var overrides: LinkedHashMap<SiteClass, ClassOverride>? = null
            var checks: LinkedHashMap<TajweedCheck, Boolean>? = null
            var featurePresets: LinkedHashMap<TajweedFeature, TajweedPreset>? = null
            for ((key, value) in m) {
                when (val k = key.toString()) {
                    "v" -> Unit
                    "preset" -> {
                        val p = (value as? String)?.let { TajweedPreset.fromKey(it) }
                        if (p != null) preset = p else dropped.add("remote.preset")
                    }
                    "context" -> {
                        if (value is String && value.isNotEmpty()) context = value else dropped.add("remote.context")
                    }
                    "overrides" -> {
                        if (value !is Map<*, *>) {
                            dropped.add("remote.overrides")
                            continue
                        }
                        val ov = LinkedHashMap<SiteClass, ClassOverride>()
                        val fps = LinkedHashMap<TajweedFeature, TajweedPreset>()
                        for ((ck, o) in value) {
                            val name = "remote.overrides.$ck"
                            val found = SiteClass.fromKey(ck.toString())
                            val feature = TajweedFeature.fromKey(ck.toString())
                            if ((found == null && feature == null) || o !is Map<*, *>) {
                                dropped.add(name)
                                continue
                            }
                            val hasP = o.containsKey("preset")
                            val hasT = o.containsKey("threshold")
                            if ((feature != null && hasT) || hasP == hasT) {
                                dropped.add(name)
                                continue
                            }
                            if (feature != null) {
                                val fp = o["preset"]
                                if (fp is String && fp in PRESETS) fps[feature] = TajweedPreset.fromKey(fp)!! else dropped.add(name)
                                continue
                            }
                            val cls = found!!
                            if (hasP) {
                                val p = o["preset"]
                                if (p is String && p in PRESETS) ov[cls] = ClassOverride.Preset(TajweedPreset.fromKey(p)!!) else dropped.add(name)
                            } else {
                                val t = o["threshold"]
                                if (t is Double && t.isFinite() && t >= -20.0 && t <= 20.0) ov[cls] = ClassOverride.Threshold(t) else dropped.add(name)
                            }
                        }
                        overrides = ov
                        featurePresets = fps
                    }
                    "checks" -> {
                        if (value !is Map<*, *>) {
                            dropped.add("remote.checks")
                            continue
                        }
                        val ch = LinkedHashMap<TajweedCheck, Boolean>()
                        for ((ck, b) in value) {
                            val c = TajweedCheck.fromKey(ck.toString())
                            if (c != null && b is Boolean) ch[c] = b else dropped.add("remote.checks.$ck")
                        }
                        checks = ch
                    }
                    else -> dropped.add("remote.$k")
                }
            }
            return RemoteTajweed(preset, overrides, checks, context, featurePresets, dropped.sorted(), true)
        }

        /** The block from its JSON text; text that is not JSON is dropped whole ("remote"). */
        @JvmStatic
        fun parseJson(json: String?): RemoteTajweed {
            if (json == null) return ABSENT
            val v = try {
                Json.parse(json)
            } catch (e: JsonException) {
                return RemoteTajweed(null, null, null, null, null, listOf("remote"), false)
            }
            return parse(v)
        }

        /** The block inside a whole `/v1/config` document (its `tajweed` key); a document without one
         *  gives [ABSENT]. Never throws. */
        @JvmStatic
        fun fromConfigJson(configJson: String): RemoteTajweed {
            val v = try {
                Json.parse(configJson)
            } catch (e: JsonException) {
                return RemoteTajweed(null, null, null, null, null, listOf("remote"), false)
            }
            val m = v as? Map<*, *> ?: return RemoteTajweed(null, null, null, null, null, listOf("remote"), false)
            return parse(m["tajweed"])
        }
    }
}

/**
 * One class's effective setting. [threshold] is null (and [enabled] false) when calibration.json has
 * no threshold for the preset in force; [preset] is null for a threshold override.
 */
data class ResolvedSite(
    val status: CheckStatus,
    val enabled: Boolean,
    val threshold: Double?,
    val preset: TajweedPreset?,
    val source: SettingSource,
)

/** A check's effective state. [preset] and [source]: madd, qalqalah and gemination; [tau] (the
 *  score threshold, `preset_tau[preset]` else `tau`): qalqalah and gemination. */
data class ResolvedCheck(
    val status: CheckStatus,
    val enabled: Boolean,
    val preset: TajweedPreset? = null,
    val source: SettingSource? = null,
    val tau: Double? = null,
)

/**
 * The tajweed settings in force: what [TajweedSettings.resolve] gives. [toJson] is the layout every
 * SDK's settings vectors compare: `{context, backend, preset, sites{...}, madd, lazim, gemination,
 * qalqalah, dropped, refused}`.
 */
data class ResolvedTajweedSettings(
    val context: String,
    val backend: TajweedBackend,
    /** The global preset. */
    val preset: TajweedPreset,
    /** In calibration.json's class order. */
    val sites: Map<SiteClass, ResolvedSite>,
    val madd: ResolvedCheck,
    val lazim: ResolvedCheck,
    val gemination: ResolvedCheck,
    val qalqalah: ResolvedCheck,
    /** Remote fields validation dropped, sorted and de-duplicated. */
    val dropped: List<String>,
    /** `"<layer>:<item>"` for every `off` item a layer tried to turn on and could not (any remote
     *  switch, or app code in a release build), sorted. */
    val refused: List<String>,
) {
    fun toJsonMap(): Map<String, Any?> {
        fun feature(c: ResolvedCheck, tau: Boolean) = linkedMapOf<String, Any?>(
            "status" to c.status.key, "enabled" to c.enabled, "preset" to c.preset?.key, "source" to c.source?.key,
        ).apply { if (tau) put("tau", c.tau) }
        return linkedMapOf(
            "context" to context,
            "backend" to backend.key,
            "preset" to preset.key,
            "sites" to sites.entries.associate { (cls, s) ->
                cls.key to linkedMapOf(
                    "status" to s.status.key, "enabled" to s.enabled, "threshold" to s.threshold,
                    "preset" to s.preset?.key, "source" to s.source.key,
                )
            },
            "madd" to feature(madd, false),
            "lazim" to linkedMapOf("status" to lazim.status.key, "enabled" to lazim.enabled),
            "gemination" to feature(gemination, true),
            "qalqalah" to feature(qalqalah, true),
            "dropped" to dropped,
            "refused" to refused,
        )
    }

    fun toJson(): String = Json.write(toJsonMap())
}

/**
 * Resolves the tajweed settings: explicit app code > the portal's block > calibration.json, as
 * TAJWEED_SETTINGS.md (revision 2) defines it. Per class and per feature, an override
 * (app, then portal) wins over a global preset (app, then portal), which wins over calibration's
 * default preset. The same inputs give the same JSON as the reference (settings_ref.py) and every
 * other SDK.
 */
object TajweedSettings {
    /**
     * @param remote the portal's block ([RemoteTajweed.parseJson]); [RemoteTajweed.ABSENT] for none.
     * @param app the app's options; throws [RecognizerException.InvalidAppOption] for a context the
     *   model does not ship.
     * @param debug whether the host app is a debug build: only then can app code (never the portal)
     *   turn on a check calibration.json marks `off`. On Android pass
     *   `TajweedAndroid.isDebuggable(context)` (the app's FLAG_DEBUGGABLE); on the JVM, say it.
     * @param backend where the heads come from: [TajweedBackend.INT8] for the exact engine,
     *   [TajweedBackend.FP32] for CoreML, NPUs and servers.
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(
        calibration: Calibration,
        remote: RemoteTajweed = RemoteTajweed.ABSENT,
        app: TajweedOptions = TajweedOptions(),
        debug: Boolean,
        backend: TajweedBackend = TajweedBackend.INT8,
    ): ResolvedTajweedSettings {
        if (app.context != null && app.context !in calibration.contexts) {
            throw RecognizerException.InvalidAppOption("context", "${app.context} is not shipped (${calibration.contexts.keys})")
        }
        val dropped = remote.dropped.toMutableList()
        var remoteContext = remote.context
        if (remoteContext != null && remoteContext !in calibration.contexts) {
            dropped.add("remote.context")
            remoteContext = null
        }
        val contextName = app.context ?: remoteContext ?: calibration.defaultContext
        val cc = calibration.context(contextName)
        val preset = app.preset ?: remote.preset ?: calibration.defaultPreset
        val refused = ArrayList<String>()

        fun flag(check: TajweedCheck): Pair<Boolean, SettingSource>? {
            app.checks[check]?.let { return it to SettingSource.APP }
            remote.checks?.get(check)?.let { return it to SettingSource.REMOTE }
            return null
        }

        fun enabled(item: String, check: TajweedCheck, status: CheckStatus): Boolean {
            val (on, src) = flag(check) ?: return status == CheckStatus.VALIDATED
            if (!on) return false
            if (status != CheckStatus.OFF) return true
            if (src == SettingSource.APP && debug) return true
            refused.add("${src.key}:$item")
            return false
        }

        /** A global preset, app then portal, else calibration's default. */
        fun globalPreset(): Pair<TajweedPreset, SettingSource> = when {
            app.preset != null -> app.preset to SettingSource.APP
            remote.preset != null -> remote.preset to SettingSource.REMOTE
            else -> calibration.defaultPreset to SettingSource.CALIBRATION
        }

        val sites = LinkedHashMap<SiteClass, ResolvedSite>()
        for (cls in calibration.classes) {
            val o: ClassOverride
            val src: SettingSource
            val appOv = app.overrides[cls]
            val remoteOv = remote.overrides?.get(cls)
            when {
                appOv != null -> { o = appOv; src = SettingSource.APP }
                remoteOv != null -> { o = remoteOv; src = SettingSource.REMOTE }
                else -> globalPreset().let { (p, s) -> o = ClassOverride.Preset(p); src = s }
            }
            val (thr, p) = when (o) {
                is ClassOverride.Threshold -> o.threshold to null
                is ClassOverride.Preset -> cc.threshold(cls, o.preset, backend) to o.preset
            }
            val status = cc.status(cls)
            val on = enabled(cls.key, cls.check, status)
            sites[cls] = ResolvedSite(status, on && thr != null, thr, p, src)
        }

        fun featurePreset(f: TajweedFeature): Pair<TajweedPreset, SettingSource> {
            app.featurePresets[f]?.let { return it to SettingSource.APP }
            remote.featurePresets?.get(f)?.let { return it to SettingSource.REMOTE }
            return globalPreset()
        }

        val maddOn = enabled("madd", TajweedCheck.MADD, cc.madd.status)
        val lazimOn = enabled("lazim", TajweedCheck.LAZIM, cc.madd.lazimStatus)
        val (maddPreset, maddSource) = featurePreset(TajweedFeature.MADD)

        fun feature(f: TajweedFeature, check: TajweedCheck, c: Calibration.FeatureCalibration): ResolvedCheck {
            val (p, s) = featurePreset(f)
            return ResolvedCheck(c.status, enabled(f.key, check, c.status), p, s, c.tau(p))
        }
        val qlq = feature(TajweedFeature.QALQALAH, TajweedCheck.QALQALAH, cc.qalqalah)
        val gem = feature(TajweedFeature.GEMINATION, TajweedCheck.GEMINATION, cc.gemination)
        return ResolvedTajweedSettings(
            context = contextName,
            backend = backend,
            preset = preset,
            sites = sites,
            madd = ResolvedCheck(cc.madd.status, maddOn, maddPreset, maddSource),
            lazim = ResolvedCheck(cc.madd.lazimStatus, lazimOn && maddOn),
            gemination = gem,
            qalqalah = qlq,
            dropped = dropped.toSortedSet().toList(),
            refused = refused.toSortedSet().toList(),
        )
    }
}
