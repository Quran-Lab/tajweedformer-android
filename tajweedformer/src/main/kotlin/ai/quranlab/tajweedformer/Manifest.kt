package ai.quranlab.tajweedformer

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * A model folder's `manifest.json`, format 3 (`sdk/v4.0` layout v2, contract LAYOUT.md): the model's
 * identity and compatibility, and every file with its size, SHA-256 and tags (platforms, SoC, context,
 * role, need), stored either as is (`identity`) or gzip with the decoded file's own size and SHA-256.
 * An Ed25519 signature covers all of it. The same signed text, byte for byte, as the publisher's
 * `make_manifest3.py` (tajweedformer-coreml `publish/`) and every other 2.0.0 SDK.
 *
 * SDK 2.0.0 reads format 3 only: TajweedFormer V4 is the only model it hosts. Older formats (1.x SDKs,
 * v3.2) are refused with [RecognizerException.ManifestInvalid].
 */
internal class ModelManifest(
    val format: Int,
    /** "v4.0": the folder name under sdk/ in the model's repository. */
    val modelId: String,
    val family: String,
    val revision: String,
    /** The oldest SDK that may load this model. */
    val minSdkVersion: String,
    val engineFormat: Int,
    val coremlFormat: Int,
    /** The tokens file (always identity encoded). */
    val tokens: String,
    val tokensSha256: String,
    val defaultContext: String,
    val contexts: List<String>,
    val files: List<FileEntry>,
    val signature: String? = null,
    /** The `golden_tolerance` field as the JSON has it (null: absent). A 2.x field: signed, never applied by 3.0. */
    private val toleranceRaw: Any? = null,
    /**
     * The model's version, `x.y.z` (SDK 3.0: the weights and what they recognise; V4 is `4.0.0`). Null in a 2.x
     * build's manifest, which 3.0 refuses ([ManifestVerifier]).
     */
    val version: String? = null,
    /** The `golden_raw_tolerance` field as the JSON has it (null: absent). */
    private val rawToleranceRaw: Any? = null,
    /** The build id when the manifest was read from its file: the first 40 hex of the file's SHA-256. */
    private val buildId: String? = null,
) {
    /**
     * The build this manifest is: the first 40 hex digits of the SHA-256 of the signed manifest.json (what the config
     * names as `revision` and the CDN path carries). Diagnostics and keys only; an app selects by [version].
     */
    val build: String by lazy { buildId ?: hex(MessageDigest.getInstance("SHA-256").digest(toJson().toByteArray(Charsets.UTF_8))).take(40) }

    /**
     * `golden_raw_tolerance` (SDK 3.0, SPEC section 5): per accelerator build (`coreml`, `npu:<soc>:<context>`) what
     * its raw outputs may differ from the CPU engine's by on the self-test set: the WELL-FORMED builds only
     * ([RawTolerance.parse]). A malformed build has no signed line and no tolerance (it is judged on tokens only).
     * In force only when the signature covers the lines ([rawToleranceFor]).
     */
    val rawTolerance: Map<String, RawTolerance> = (rawToleranceRaw as? Map<*, *>)?.entries
        ?.mapNotNull { (k, v) -> (k as? String)?.takeIf { buildKey(it) }?.let { b -> RawTolerance.parse(v)?.let { b to it } } }?.toMap() ?: emptyMap()

    /** Whether the manifest has a `golden_raw_tolerance` object. */
    val hasRawTolerance: Boolean get() = rawToleranceRaw is Map<*, *>

    /** The builds of `golden_raw_tolerance` that are not well formed: judged on tokens only. */
    val tokensOnlyBuilds: List<String> get() = (rawToleranceRaw as? Map<*, *>)?.keys?.filterIsInstance<String>()?.filter { it !in rawTolerance } ?: emptyList()

    /** The signature verified WITH the `golden_raw` lines; otherwise the raw tolerance counts as absent. */
    @Volatile internal var rawToleranceSigned = false

    /** The raw tolerance of [build] when the signature covers it, else null (tokens only). */
    fun rawToleranceFor(build: String): RawTolerance? = if (rawToleranceSigned) rawTolerance[build] else null

    /** The golden test's allowance for one CTC head: token edits over the clean clips, and the log-prob distances. */
    data class HeadTolerance(val tokenEdits: Int, val lpMean: Double, val lpP99: Double)

    /** One build's allowance: per CTC head, and `attr` (the pooled mean probability difference of the attribute heads). */
    class RawTolerance(val heads: Map<String, HeadTolerance>, val pMean: Double?) {
        /** The signed lines' tails by head key (`attr` included). */
        internal val lines: Map<String, String>
            get() = heads.mapValues { (_, t) -> "token_edits=${t.tokenEdits} lp_mean=${num(t.lpMean)} lp_p99=${num(t.lpP99)}" } +
                (pMean?.let { mapOf("attr" to "p_mean=${num(it)}") } ?: emptyMap())

        companion object {
            private val HEAD = Regex("^[a-z][a-z0-9_]{0,31}$")

            /** A value as the signed text writes it: 6 digits after the dot, trailing zeros removed, one digit kept. */
            @JvmStatic
            fun num(x: Double): String {
                var s = String.format(java.util.Locale.ROOT, "%.6f", x)
                while (s.endsWith("0") && !s.endsWith(".0")) s = s.dropLast(1)
                return s
            }

            /** A number in [lo, hi] with at most 6 digits after the dot (it survives being written with 6), else null. */
            private fun dec(v: Any?, lo: Double, hi: Double): Double? {
                val d = v as? Double ?: return null
                if (!d.isFinite() || d < lo || d > hi) return null
                return d.takeIf { String.format(java.util.Locale.ROOT, "%.6f", d).toDouble() == d }
            }

            /**
             * A well-formed build: an object whose keys are `attr` (exactly `{"p_mean"}`, a number 0 to 1) or a CTC head
             * name (exactly `{"token_edits", "lp_mean", "lp_p99"}`: an integer value 0 to 1,000,000 and two numbers 0 to
             * 30), every decimal with at most 6 digits after the dot. One bad head makes the build malformed: null.
             */
            @JvmStatic
            fun parse(v: Any?): RawTolerance? {
                val m = v as? Map<*, *> ?: return null
                val heads = LinkedHashMap<String, HeadTolerance>()
                var pMean: Double? = null
                for ((k, x) in m) {
                    val name = k as? String ?: return null
                    val o = x as? Map<*, *> ?: return null
                    if (name == "attr") {
                        if (o.keys != setOf("p_mean")) return null
                        pMean = dec(o["p_mean"], 0.0, 1.0) ?: return null
                    } else {
                        if (!HEAD.matches(name) || o.keys != setOf("token_edits", "lp_mean", "lp_p99")) return null
                        val e = (o["token_edits"] as? Double)?.takeIf { it >= 0 && it <= 1_000_000 && it == Math.floor(it) } ?: return null
                        heads[name] = HeadTolerance(e.toInt(), dec(o["lp_mean"], 0.0, 30.0) ?: return null, dec(o["lp_p99"], 0.0, 30.0) ?: return null)
                    }
                }
                return RawTolerance(heads, pMean)
            }
        }
    }

    /**
     * `golden_tolerance` (SDK 2.0.1): per accelerator build (`coreml`, `coreml-compact`, `npu:<soc>:<context>`) the
     * differences from the CPU engine its golden test may show; null when the field is absent. An entry whose values
     * are not three non-negative integers is null (exact). In force only when the signature covers it ([toleranceFor]).
     */
    val goldenTolerance: Map<String, GoldenTolerance?>? =
        (toleranceRaw as? Map<*, *>)?.entries?.filter { it.key is String }?.associate { (k, v) -> k as String to GoldenTolerance.parse(v) }
            ?: if (toleranceRaw != null) emptyMap() else null

    /** The three values of each WELL-FORMED entry as the signed text writes them (a build key of 1 to 64 printable
     *  ASCII characters, three numbers with an integer value 0 to 1,000,000); any other entry has no line. */
    private val toleranceText: Map<String, String> = (toleranceRaw as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        val key = (k as? String)?.takeIf { buildKey(it) } ?: return@mapNotNull null
        val t = GoldenTolerance.parse(v) ?: return@mapNotNull null
        key to "${t.tokenDiffs} ${t.siteFlagDiffs} ${t.wordFlagDiffs}"
    }?.toMap() ?: emptyMap()

    /** The signature verified WITH the `golden_tolerance` lines ([ManifestVerifier]); otherwise the tolerance is ignored. */
    @Volatile internal var toleranceSigned = false

    /** The tolerance of [build] when the signature covers it, else exact. */
    fun toleranceFor(build: String): GoldenTolerance =
        (if (toleranceSigned) goldenTolerance?.get(build) else null) ?: GoldenTolerance.EXACT

    /** The golden test's allowance for one build: totals over the self-test set against the CPU run. */
    data class GoldenTolerance(val tokenDiffs: Int, val siteFlagDiffs: Int, val wordFlagDiffs: Int) {
        companion object {
            @JvmField val EXACT = GoldenTolerance(0, 0, 0)

            /** Three non-negative integers, else null (exact). */
            @JvmStatic
            fun parse(v: Any?): GoldenTolerance? {
                val m = v as? Map<*, *> ?: return null
                fun n(k: String): Int? = (m[k] as? Number)?.toDouble()?.takeIf { it >= 0 && it == Math.floor(it) && it <= 1_000_000 }?.toInt()
                return GoldenTolerance(n("token_diffs") ?: return null, n("site_flag_diffs") ?: return null, n("word_flag_diffs") ?: return null)
            }
        }
    }
    /** How a file is stored on the server. */
    enum class Encoding(val key: String) {
        IDENTITY("identity"), GZIP("gzip");

        companion object {
            fun of(key: String): Encoding =
                entries.firstOrNull { it.key == key } ?: throw JsonException("unknown encoding $key")
        }
    }

    /** Whether every profile needs a file ([REQUIRED]) or only the backend that uses it. */
    enum class Need(val key: String) {
        REQUIRED("required"), OPTIONAL("optional");

        companion object {
            fun of(key: String): Need = entries.firstOrNull { it.key == key } ?: throw JsonException("unknown need $key")
        }
    }

    /** The decoded file a gzip entry inflates to. */
    data class Decoded(val path: String, val size: Long, val sha256: String)

    /**
     * One file. [path], [size] and [sha256] are the stored (transport) bytes; for a gzip file
     * [decoded] is what the SDK keeps on disk.
     */
    data class FileEntry(
        val path: String,
        val size: Long,
        val sha256: String,
        /** `android`, `apple`, `web`, `native`, or `["*"]` for all (`"*"` in the JSON). */
        val platforms: List<String>,
        /** A SoC id (`tensor_g4`, `exynos2600`, ...) or `*`. */
        val soc: String,
        /** A context (`c16_l64`) or `*`. */
        val context: String,
        /** `tokens`, `calibration`, `expected`, `weights`, `frontend`, `graph`, `graph-data`, `frames`,
         *  `ort-graph`, `npu-model`, `npu-io`, `npu-masks`, `coreml`, `coreml-keeps`, `correction`. */
        val role: String,
        val need: Need,
        val encoding: Encoding,
        val decoded: Decoded? = null,
    ) {
        /** The path the SDK keeps the file at: the decoded path for gzip, else [path]. */
        val localPath: String get() = decoded?.path ?: path
        val localSize: Long get() = decoded?.size ?: size
        val localSha256: String get() = (decoded?.sha256 ?: sha256).lowercase()

        fun forPlatform(platform: String): Boolean = "*" in platforms || platform in platforms
    }

    /** The file whose decoded or stored path is [localPath], or null. */
    fun file(localPath: String): FileEntry? = files.firstOrNull { it.localPath == localPath }

    /**
     * The bytes the signature covers (LAYOUT.md "Signed text"):
     *
     *     quranlab-model-manifest 3
     *     model_id v4.0
     *     family tajweedformer
     *     revision <rev>
     *     min_sdk_version 2.0.0
     *     engine_format 3
     *     coreml_format 9
     *     tokens tokens.txt
     *     tokens_sha256 <hex>
     *     default_context c16_l64
     *     contexts c16_l64,c24_l72,c24_l256
     *     file <sha256> <size> <path> <platforms> <soc> <context> <role> <need> <encoding> <dsha256> <dsize> <dpath>
     *
     * Files sorted by the path's UTF-8 bytes; platforms comma-joined in the order
     * android,apple,web,native (or `*`); the three decoded fields `-` for identity files.
     */
    val signedMessage: ByteArray get() = signedMessage(withTolerance = true)

    /**
     * The signed text. With a `version` field, one line `version x.y.z` right after the `revision` line. With
     * [withTolerance] and a `golden_tolerance` field, one line per build after the header lines, sorted by the build's
     * UTF-8 bytes: `golden_tolerance <build> <token_diffs> <site_flag_diffs> <word_flag_diffs>`. With [withRaw] and a
     * `golden_raw_tolerance` field, after those and before the first `file` line, one line per build and head sorted
     * by `<build> <head>` bytes: `golden_raw <build> <head> token_edits=<int> lp_mean=<num> lp_p99=<num>` and
     * `golden_raw <build> attr p_mean=<num>`, the numbers exactly as the JSON wrote them. Without the three fields
     * the text is the 2.0.0 one, byte for byte.
     */
    @JvmOverloads
    fun signedMessage(withTolerance: Boolean, withRaw: Boolean = true): ByteArray {
            val s = StringBuilder("quranlab-model-manifest $format\n")
            s.append("model_id $modelId\nfamily $family\nrevision $revision\n")
            if (version != null) s.append("version $version\n")
            s.append("min_sdk_version $minSdkVersion\n")
            s.append("engine_format $engineFormat\ncoreml_format $coremlFormat\ntokens $tokens\n")
            s.append("tokens_sha256 ${tokensSha256.lowercase()}\ndefault_context $defaultContext\n")
            s.append("contexts ${contexts.joinToString(",")}\n")
            if (withTolerance) for (k in toleranceText.keys.sortedWith { a, b -> compareUtf8(a, b) }) s.append("golden_tolerance $k ${toleranceText[k]}\n")
            if (withRaw) {
                val lines = rawTolerance.flatMap { (b, t) -> t.lines.map { (h, tail) -> "$b $h" to tail } }
                for ((k, tail) in lines.sortedWith { a, b -> compareUtf8(a.first, b.first) }) s.append("golden_raw $k $tail\n")
            }
            for (f in files.sortedWith { a, b -> compareUtf8(a.path, b.path) }) {
                val d = f.decoded
                s.append("file ${f.sha256.lowercase()} ${f.size} ${f.path} ${platformsText(f.platforms)} ${f.soc} ")
                s.append("${f.context} ${f.role} ${f.need.key} ${f.encoding.key} ")
                s.append(if (d == null) "- - -" else "${d.sha256.lowercase()} ${d.size} ${d.path}")
                s.append('\n')
            }
            return s.toString().toByteArray(Charsets.UTF_8)
    }

    /** The JSON layout make_manifest3.py writes (keys sorted). */
    fun toJson(): String {
        val m = sortedMapOf<String, Any?>()
        m["format"] = format
        m["model_id"] = modelId
        m["family"] = family
        m["revision"] = revision
        if (version != null) m["version"] = version
        m["min_sdk_version"] = minSdkVersion
        m["engine_format"] = engineFormat
        m["coreml_format"] = coremlFormat
        m["tokens"] = tokens
        m["tokens_sha256"] = tokensSha256
        m["default_context"] = defaultContext
        m["contexts"] = contexts
        m["files"] = files.map { f ->
            val o = sortedMapOf<String, Any?>(
                "path" to f.path, "size" to f.size, "sha256" to f.sha256,
                "platforms" to (if (f.platforms == listOf("*")) "*" else f.platforms), "soc" to f.soc,
                "context" to f.context, "role" to f.role, "need" to f.need.key, "encoding" to f.encoding.key,
            )
            f.decoded?.let { o["decoded"] = sortedMapOf("path" to it.path, "size" to it.size, "sha256" to it.sha256) }
            o
        }
        if (toleranceRaw != null) m["golden_tolerance"] = toleranceRaw
        if (rawToleranceRaw != null) m["golden_raw_tolerance"] = rawToleranceRaw
        if (signature != null) m["signature"] = signature
        return Json.write(m)
    }

    fun withSignature(signature: String?) = ModelManifest(
        format, modelId, family, revision, minSdkVersion, engineFormat, coremlFormat, tokens, tokensSha256,
        defaultContext, contexts, files, signature, toleranceRaw, version, rawToleranceRaw,
    )

    /** The same manifest with another `golden_tolerance` field (null: none), unsigned. */
    fun withTolerance(raw: Any?) = ModelManifest(
        format, modelId, family, revision, minSdkVersion, engineFormat, coremlFormat, tokens, tokensSha256,
        defaultContext, contexts, files, null, raw, version, rawToleranceRaw,
    )

    /** The same manifest with another `golden_raw_tolerance` field (as [Json.parse] gives it; null: none), unsigned. */
    fun withRawTolerance(raw: Any?) = ModelManifest(
        format, modelId, family, revision, minSdkVersion, engineFormat, coremlFormat, tokens, tokensSha256,
        defaultContext, contexts, files, null, toleranceRaw, version, raw,
    )

    /** The same manifest with another `version` (null: a 2.x build's manifest), unsigned. */
    fun withVersion(version: String?) = ModelManifest(
        format, modelId, family, revision, minSdkVersion, engineFormat, coremlFormat, tokens, tokensSha256,
        defaultContext, contexts, files, null, toleranceRaw, version, rawToleranceRaw,
    )

    /** The model can run in this SDK: format 3, [minSdkVersion] at or below [sdkVersion], engine
     *  format 3. Throws [RecognizerException.ModelIncompatible]. */
    fun checkCompatible(sdkVersion: String = SdkInfo.VERSION) {
        if (!versionAtLeast(sdkVersion, minSdkVersion)) {
            throw RecognizerException.ModelIncompatible("$modelId $revision needs SDK $minSdkVersion or newer (this is $sdkVersion)", minSdkVersion)
        }
        if (engineFormat != SdkInfo.ENGINE_FORMAT) {
            throw RecognizerException.ModelIncompatible("engine format $engineFormat is not supported by this SDK (it reads ${SdkInfo.ENGINE_FORMAT})")
        }
        if (coremlFormat !in SdkInfo.SUPPORTED_COREML_FORMATS) {
            throw RecognizerException.ModelIncompatible("CoreML format $coremlFormat is not supported by this SDK")
        }
    }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val FORMAT = 3

        /** A tolerance build key: 1 to 64 printable ASCII characters (0x21 to 0x7E, so no space). */
        internal fun buildKey(k: String): Boolean = k.length in 1..64 && k.all { it.code in 0x21..0x7e }

        /**
         * The acceptance checks of a manifest's bytes, in the order the four SDKs share; the first that fails decides
         * the error: the bytes are the build the config named ([configBuild], the start of their SHA-256; else
         * `checksum_mismatch`), they parse as a format 3 manifest (`model_invalid`), the signature verifies
         * (`signature_invalid`), the manifest has a `version` and its `min_sdk_version` is not above this SDK's
         * (`model_incompatible`), and the version is the one the config named ([configVersion]; else `model_invalid`).
         * A folder opened without a config passes null for both.
         */
        @JvmStatic
        fun accept(bytes: ByteArray, verifier: ManifestVerifier, configBuild: String? = null, configVersion: String? = null): ModelManifest {
            val sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            if (configBuild != null && !sha.startsWith(configBuild.lowercase())) throw RecognizerException.ChecksumMismatch(FILE_NAME)
            val m = parse(String(bytes, Charsets.UTF_8), sha.take(40))
            verifier.verify(m)
            if (configVersion != null && m.version != configVersion) {
                throw RecognizerException.ManifestInvalid("the manifest is version ${m.version}, the config named $configVersion")
            }
            return m
        }

        /** The platform order of the signed text. */
        @JvmField val PLATFORMS = listOf("android", "apple", "web", "native")

        internal fun platformsText(p: List<String>): String =
            if (p == listOf("*")) "*" else PLATFORMS.filter { it in p }.joinToString(",")

        /** A path's UTF-8 bytes compared unsigned, as Python's `key=str.encode` and Swift's sort. */
        internal fun compareUtf8(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        /** Whether [version] (x.y.z) is at or above [minimum], compared numerically part by part.
         *  Missing parts count as 0, a "-suffix" is ignored, anything else that is not a number
         *  fails the comparison. The same rule as the Swift SDK. */
        @JvmStatic
        fun versionAtLeast(version: String, minimum: String): Boolean {
            fun parts(v: String): List<Int>? {
                val core = v.split("-", limit = 2)[0]
                val p = core.split(".").map { it.toIntOrNull() }
                return if (p.isEmpty() || p.any { it == null }) null else p.map { it!! }
            }
            val a = parts(version) ?: return false
            val b = parts(minimum) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return true
        }

        @JvmStatic
        fun read(dir: File): ModelManifest {
            val f = File(dir, FILE_NAME)
            if (!f.isFile) throw RecognizerException.ModelMissing("no $FILE_NAME in ${dir.path}")
            return parse(f.readBytes())
        }

        /** A model version: `x.y.z`, no leading zeros, no suffix, at most 4, 4 and 6 digits. */
        @JvmField val VERSION = Regex("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,5})$")

        /** Whether [v] is a model version ([VERSION]); anything else is refused, never ordered. */
        @JvmStatic
        fun isVersion(v: String?): Boolean = v != null && VERSION.matches(v)

        /** Two versions compared part by part as integers (`4.10.0` is newer than `4.9.9`); null when either is not one. */
        @JvmStatic
        fun compareVersions(a: String, b: String): Int? {
            if (!isVersion(a) || !isVersion(b)) return null
            val x = a.split('.').map { it.toInt() }
            val y = b.split('.').map { it.toInt() }
            for (i in 0..2) if (x[i] != y[i]) return x[i].compareTo(y[i])
            return 0
        }

        /** Parses the bytes of a manifest.json; the build id is the first 40 hex of their SHA-256. */
        @JvmStatic
        fun parse(bytes: ByteArray): ModelManifest =
            parse(String(bytes, Charsets.UTF_8), hex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(40))

        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val TOKEN = Regex("^[^\\s]+$")

        /** Parses a format 3 manifest.json; throws [RecognizerException.ManifestInvalid]. */
        @JvmStatic
        @JvmOverloads
        fun parse(text: String, buildId: String? = null): ModelManifest = try {
            val m = J.obj(Json.parse(text), FILE_NAME)
            // the format first: an older format has other fields, and its refusal should name the format
            val format = J.int(m, "format")
            if (format != FORMAT) {
                throw RecognizerException.ManifestInvalid("format $format is not supported (SDK ${SdkInfo.VERSION} reads format $FORMAT)")
            }
            val rawTol = m["golden_raw_tolerance"]
            val version = if (m.containsKey("version")) {
                (m["version"] as? String)?.takeIf { isVersion(it) } ?: throw JsonException("version is not x.y.z")
            } else null
            if (!isVersion(m["min_sdk_version"] as? String)) throw JsonException("min_sdk_version is not x.y.z")
            fun tok(o: Map<String, Any?>, k: String, what: String): String {
                val v = J.str(o, k, what)
                if (!TOKEN.matches(v)) throw JsonException("$what has white space or is empty")
                return v
            }
            fun sha(o: Map<String, Any?>, k: String, what: String): String {
                val v = J.str(o, k, what)
                if (!HEX64.matches(v)) throw JsonException("$what is not 64 hex digits")
                return v
            }
            val files = J.list(m, "files").mapIndexed { i, v ->
                val o = J.obj(v, "files[$i]")
                val w = "files[$i]"
                // "*" (every platform) or a list of platform names
                val platforms = when (val p = o["platforms"]) {
                    "*" -> listOf("*")
                    is List<*> -> p.map { it as? String ?: throw JsonException("$w.platforms") }
                    else -> throw JsonException("$w.platforms is neither \"*\" nor a list")
                }
                if (platforms.isEmpty() || (platforms != listOf("*") && platforms.any { it !in PLATFORMS })) {
                    throw JsonException("$w.platforms $platforms")
                }
                val encoding = Encoding.of(J.str(o, "encoding", "$w.encoding"))
                val decoded = J.objOrNull(o, "decoded")?.let { d ->
                    Decoded(tok(d, "path", "$w.decoded.path"), J.long(d, "size", "$w.decoded.size"), sha(d, "sha256", "$w.decoded.sha256"))
                }
                if ((encoding == Encoding.GZIP) != (decoded != null)) throw JsonException("$w: a gzip file has a decoded entry, an identity one none")
                FileEntry(
                    tok(o, "path", "$w.path"), J.long(o, "size", "$w.size"), sha(o, "sha256", "$w.sha256"), platforms,
                    tok(o, "soc", "$w.soc"), tok(o, "context", "$w.context"), tok(o, "role", "$w.role"),
                    Need.of(J.str(o, "need", "$w.need")), encoding, decoded,
                )
            }
            val contexts = J.list(m, "contexts").map { it as? String ?: throw JsonException("contexts") }
            // golden_tolerance never fails the manifest (the CPU path does not depend on it); a bad entry is exact
            ModelManifest(
                format = format,
                modelId = tok(m, "model_id", "model_id"),
                family = tok(m, "family", "family"),
                revision = tok(m, "revision", "revision"),
                minSdkVersion = tok(m, "min_sdk_version", "min_sdk_version"),
                engineFormat = J.int(m, "engine_format"),
                coremlFormat = J.int(m, "coreml_format"),
                tokens = tok(m, "tokens", "tokens"),
                tokensSha256 = sha(m, "tokens_sha256", "tokens_sha256"),
                defaultContext = tok(m, "default_context", "default_context"),
                contexts = contexts,
                files = files,
                signature = m["signature"] as? String,
                toleranceRaw = m["golden_tolerance"],
                version = version,
                rawToleranceRaw = rawTol,
                buildId = buildId,
            )
        } catch (e: JsonException) {
            throw RecognizerException.ManifestInvalid(e.message ?: "not JSON")
        }

        /** Size and lowercase hex SHA-256 of a file, read in 1 MB pieces. */
        @JvmStatic
        fun digest(file: File): Pair<Long, String> {
            if (!file.isFile) throw RecognizerException.ModelMissing(file.path)
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    size += n
                }
            }
            return size to hex(md.digest())
        }

        internal fun hex(b: ByteArray): String {
            val c = CharArray(b.size * 2)
            val d = "0123456789abcdef"
            for (i in b.indices) {
                c[2 * i] = d[(b[i].toInt() shr 4) and 15]
                c[2 * i + 1] = d[b[i].toInt() and 15]
            }
            return String(c)
        }
    }
}

/**
 * Checks a manifest before anything it lists is used: the signature (when signed, or always when
 * [requireSignature]), the structure (safe paths, nothing listed twice, the tokens file and its hash,
 * contexts), and the model's compatibility with this SDK. Files are checked one by one by
 * [ModelStore] and [ModelFolder] against the entries, and only the files of the profile in use.
 */
internal class ManifestVerifier @JvmOverloads constructor(
    /** Raw 32-byte Ed25519 public keys a signature may verify against. */
    val trustedKeys: List<ByteArray> = listOf(QURAN_LAB_PUBLIC_KEY),
    /** Refuse unsigned manifests. On by default: a manifest from the network must be signed. A
     *  folder staged by hand for development can pass false. A manifest that is signed must verify
     *  either way. */
    val requireSignature: Boolean = true,
    /** The SDK version min_sdk_version is compared with (tests can pretend to be another). */
    val sdkVersion: String = SdkInfo.VERSION,
    /** Refuse a manifest without `version` (a 2.x build). On by default: SDK 3.0 runs 3.0 builds only. */
    val requireVersion: Boolean = true,
) {
    /** The signature alone: it must verify when present, and be present when [required]. */
    fun verifySignature(m: ModelManifest, required: Boolean = requireSignature) {
        val sig = m.signature
        if (sig == null) {
            if (required) throw RecognizerException.SignatureInvalid("the manifest is not signed")
            return
        }
        val raw = try {
            Base64.getDecoder().decode(sig)
        } catch (e: IllegalArgumentException) {
            throw RecognizerException.SignatureInvalid("the signature is not base64")
        }
        fun ok(message: ByteArray) = trustedKeys.any { Ed25519.verify(it, message, raw) }
        if (raw.size != 64) throw RecognizerException.SignatureInvalid("the signature is not 64 bytes")
        // the tolerances are signed in their own lines; a manifest signed without a field's lines still verifies, that
        // tolerance then counting as absent. Four texts in a fixed order: with both groups of lines, without the raw
        // ones, without the 2.x ones, without both; a text equal to one already tried is skipped.
        val tried = ArrayList<String>()
        for ((t, r) in listOf(true to true, true to false, false to true, false to false)) {
            val message = m.signedMessage(withTolerance = t, withRaw = r)
            val text = String(message, Charsets.UTF_8)
            if (text in tried) continue
            tried.add(text)
            if (ok(message)) {
                m.toleranceSigned = t && m.goldenTolerance != null
                m.rawToleranceSigned = r && m.hasRawTolerance
                return
            }
        }
        throw RecognizerException.SignatureInvalid("no trusted key verifies it")
    }

    /** Signature, compatibility and structure. Throws a [RecognizerException]. */
    fun verify(m: ModelManifest) {
        verifySignature(m)
        if (requireVersion && m.version == null) {
            throw RecognizerException.ModelIncompatible("${m.modelId} ${m.revision} has no version: it is a build for SDK 2.x (this is $sdkVersion)")
        }
        m.checkCompatible(sdkVersion)
        val seen = HashSet<String>()
        for (f in m.files) {
            for (p in listOfNotNull(f.path, f.decoded?.path)) {
                if (!safePath(p)) throw RecognizerException.ManifestInvalid("bad path $p")
            }
            if (!seen.add(f.path)) throw RecognizerException.ManifestInvalid("${f.path} is listed twice")
            val d = f.decoded
            if (d != null && d.path != f.path && !seen.add(d.path)) throw RecognizerException.ManifestInvalid("${d.path} is listed twice")
            if (f.context != "*" && f.context !in m.contexts) {
                throw RecognizerException.ManifestInvalid("${f.path} names context ${f.context}, not one of ${m.contexts}")
            }
        }
        val tokens = m.files.firstOrNull { it.path == m.tokens && it.encoding == ModelManifest.Encoding.IDENTITY }
            ?: throw RecognizerException.ManifestInvalid("${m.tokens} is not listed as an identity file")
        if (tokens.sha256.lowercase() != m.tokensSha256.lowercase()) {
            throw RecognizerException.ManifestInvalid("tokens_sha256 does not match ${m.tokens}")
        }
        if (m.contexts.isEmpty() || m.defaultContext !in m.contexts) {
            throw RecognizerException.ManifestInvalid("default_context ${m.defaultContext} is not one of ${m.contexts}")
        }
        ModelIds.note(m)
    }

    companion object {
        /** Quran Lab's manifest signing key (the public half of qlab-cdn's and qlab-api's
         *  MANIFEST_SIGNING_KEY, key id 660a07197c4808da), the same bytes as the Swift SDK's. */
        @JvmField
        val QURAN_LAB_PUBLIC_KEY: ByteArray = bytes(
            0xfc, 0x02, 0xfb, 0xd9, 0x06, 0x60, 0x8e, 0xbc, 0x20, 0x6a, 0x37, 0x1d, 0x8e, 0x61, 0xc1, 0x06,
            0x2e, 0xa5, 0x55, 0x77, 0x8b, 0x44, 0xb1, 0xac, 0x83, 0x40, 0x61, 0x04, 0x3a, 0xee, 0x4a, 0x33,
        )

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** Relative, '/'-separated, no empty, `.` or `..` part, no backslash or drive colon. */
        internal fun safePath(p: String): Boolean =
            p.isNotEmpty() && !p.startsWith("/") && !p.contains('\\') && !p.contains(':') &&
                p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
