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
class ModelManifest(
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
) {
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
    val signedMessage: ByteArray
        get() {
            val s = StringBuilder("quranlab-model-manifest $format\n")
            s.append("model_id $modelId\nfamily $family\nrevision $revision\nmin_sdk_version $minSdkVersion\n")
            s.append("engine_format $engineFormat\ncoreml_format $coremlFormat\ntokens $tokens\n")
            s.append("tokens_sha256 ${tokensSha256.lowercase()}\ndefault_context $defaultContext\n")
            s.append("contexts ${contexts.joinToString(",")}\n")
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
        if (signature != null) m["signature"] = signature
        return Json.write(m)
    }

    fun withSignature(signature: String?) = ModelManifest(
        format, modelId, family, revision, minSdkVersion, engineFormat, coremlFormat, tokens, tokensSha256,
        defaultContext, contexts, files, signature,
    )

    /** The model can run in this SDK: format 3, [minSdkVersion] at or below [sdkVersion], engine
     *  format 3. Throws [RecognizerException.ModelIncompatible]. */
    fun checkCompatible(sdkVersion: String = TajweedFormer.VERSION) {
        if (!versionAtLeast(sdkVersion, minSdkVersion)) {
            throw RecognizerException.ModelIncompatible("$modelId $revision needs SDK $minSdkVersion or newer (this is $sdkVersion)", minSdkVersion)
        }
        if (engineFormat != TajweedFormer.ENGINE_FORMAT) {
            throw RecognizerException.ModelIncompatible("engine format $engineFormat is not supported by this SDK (it reads ${TajweedFormer.ENGINE_FORMAT})")
        }
        if (coremlFormat !in TajweedFormer.SUPPORTED_COREML_FORMATS) {
            throw RecognizerException.ModelIncompatible("CoreML format $coremlFormat is not supported by this SDK")
        }
    }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val FORMAT = 3

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
            return parse(f.readText(Charsets.UTF_8))
        }

        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val TOKEN = Regex("^[^\\s]+$")

        /** Parses a format 3 manifest.json; throws [RecognizerException.ManifestInvalid]. */
        @JvmStatic
        fun parse(text: String): ModelManifest = try {
            val m = J.obj(Json.parse(text), FILE_NAME)
            val format = J.int(m, "format")
            if (format != FORMAT) {
                throw RecognizerException.ManifestInvalid("format $format is not supported (SDK ${TajweedFormer.VERSION} reads format $FORMAT)")
            }
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
 * [ModelStore] and [TajweedModel] against the entries, and only the files of the profile in use.
 */
class ManifestVerifier @JvmOverloads constructor(
    /** Raw 32-byte Ed25519 public keys a signature may verify against. */
    val trustedKeys: List<ByteArray> = listOf(QURAN_LAB_PUBLIC_KEY),
    /** Refuse unsigned manifests. On by default: a manifest from the network must be signed. A
     *  folder staged by hand for development can pass false. A manifest that is signed must verify
     *  either way. */
    val requireSignature: Boolean = true,
    /** The SDK version min_sdk_version is compared with (tests can pretend to be another). */
    val sdkVersion: String = TajweedFormer.VERSION,
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
        val message = m.signedMessage
        if (trustedKeys.none { Ed25519.verify(it, message, raw) }) {
            throw RecognizerException.SignatureInvalid("no trusted key verifies it")
        }
    }

    /** Signature, compatibility and structure. Throws a [RecognizerException]. */
    fun verify(m: ModelManifest) {
        verifySignature(m)
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
