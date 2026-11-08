package ai.quranlab.tajweedformer

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** Builds format 3 model folders (transport and decoded) for the tests, signed with a JDK Ed25519 key. */
object TestKit {
    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun env(name: String): String? = System.getProperty(name) ?: System.getenv(name)

    /** One file: [path] is the decoded (kept) path; stored as `<path>.gz` when [gz]. */
    class F(
        val path: String, val bytes: ByteArray, val role: String, val context: String = "*",
        val need: String = "required", val platforms: List<String> = listOf("*"), val soc: String = "*", val gz: Boolean = true,
    ) {
        val stored: ByteArray by lazy { if (gz) gzip(bytes) else bytes }
        val storedPath: String get() = if (gz) "$path.gz" else path

        fun entry() = ModelManifest.FileEntry(
            storedPath, stored.size.toLong(), sha(stored), platforms, soc, context, role,
            ModelManifest.Need.of(need), if (gz) ModelManifest.Encoding.GZIP else ModelManifest.Encoding.IDENTITY,
            if (gz) ModelManifest.Decoded(path, bytes.size.toLong(), sha(bytes)) else null,
        )
    }

    fun tj3Tokens(): String {
        val names = listOf("<blk>", "|") + (2..29).map { "c$it" } + listOf("n~", "a", "i", "u", "aa", "ii", "uu", "<foreign>")
        return names.mapIndexed { i, s -> "$s $i" }.joinToString("\n", postfix = "\n")
    }

    fun resource(name: String): ByteArray = TestKit::class.java.getResourceAsStream("/tajweed/$name")!!.use { it.readBytes() }

    /** The CPU files of a V4 folder with placeholder engine files and the calibration fixture
     *  (contexts c16_l64, c24_l72), plus NPU files for [npuSocs] at c16_l64. */
    fun v4Files(
        npuSocs: List<String> = emptyList(), contexts: List<String> = listOf("c16_l64", "c24_l72"),
        /** SoCs with a CHAINED build at c16_l64 (role npu-part, [chainParts] parts, like the Exynos 2500's 9). */
        chainSocs: List<String> = emptyList(), chainParts: Int = 3,
    ): List<F> {
        val out = arrayListOf(
            F("tokens.txt", tj3Tokens().toByteArray(), "tokens", gz = false),
            F("calibration.json", resource("calibration_fixture.json"), "calibration"),
            F(TajweedModel.EXPECTED_TABLE, ByteArray(4000) { (it % 7).toByte() }, "expected"),
            F("layers.zfw", ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }, "weights"),
            F("frontend.zfw", "frontend".toByteArray(), "frontend"),
        )
        for (c in contexts) {
            out += F("$c/model_engine2.onnx.zfg", "graph $c".toByteArray(), "graph", c)
            out += F("$c/model_engine2.onnx.zfg.bin", "data $c".toByteArray(), "graph-data", c)
            out += F("$c/frames.zfw", "frames $c".toByteArray(), "frames", c)
            out += F("$c/model_engine2.onnx", "ort $c".toByteArray(), "ort-graph", c, "optional", listOf("native"))
        }
        for (s in npuSocs) {
            val d = "npu/$s/c16_l64/tajweedformer_v4_${s}_c16_l64"
            out += F("$d.tflite", ByteArray(5000) { 3 }, "npu-model", "c16_l64", "optional", listOf("android"), s)
            out += F("$d.io.json", "{}".toByteArray(), "npu-io", "c16_l64", "optional", listOf("android"), s)
            out += F("$d.masks.json", "{}".toByteArray(), "npu-masks", "c16_l64", "optional", listOf("android"), s)
        }
        for (s in chainSocs) {
            val d = "npu/$s/c16_l64/tajweedformer_v4_${s}_c16_l64"
            for (k in 0 until chainParts) {
                out += F("$d.P$k.tflite", ByteArray(3000 + k) { (k + 1).toByte() }, "npu-part", "c16_l64", "optional", listOf("android"), s)
            }
            out += F("$d.io.json", "{}".toByteArray(), "npu-io", "c16_l64", "optional", listOf("android"), s)
            out += F("$d.masks.json", "{}".toByteArray(), "npu-masks", "c16_l64", "optional", listOf("android"), s)
        }
        return out
    }

    fun manifest(
        files: List<F>, contexts: List<String> = listOf("c16_l64", "c24_l72"), minSdk: String = "2.0.0",
        engineFormat: Int = 3, revision: String = "v4.0-test.1",
    ): ModelManifest {
        val tokens = files.first { it.path == "tokens.txt" }
        return ModelManifest(3, "v4.0", "tajweedformer", revision, minSdk, engineFormat, 9, "tokens.txt", sha(tokens.bytes),
            contexts.first(), contexts, files.map { it.entry() })
    }

    /** A key pair: raw 32-byte public key and a signer. */
    fun keyPair(): Pair<ByteArray, (ByteArray) -> ByteArray> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val enc = kp.public.encoded
        return enc.copyOfRange(enc.size - 32, enc.size) to { msg: ByteArray ->
            Signature.getInstance("Ed25519").run {
                initSign(kp.private)
                update(msg)
                sign()
            }
        }
    }

    val key: Pair<ByteArray, (ByteArray) -> ByteArray> by lazy { keyPair() }
    val verifier: ManifestVerifier get() = ManifestVerifier(listOf(key.first))

    fun sign(m: ModelManifest, signer: (ByteArray) -> ByteArray = key.second): ModelManifest =
        m.withSignature(Base64.getEncoder().encodeToString(signer(m.signedMessage)))

    /** The stored (server) folder. */
    fun writeTransport(dir: File, files: List<F>, m: ModelManifest) {
        for (f in files) File(dir, f.storedPath).also { it.parentFile.mkdirs() }.writeBytes(f.stored)
        File(dir, ModelManifest.FILE_NAME).writeText(m.toJson())
    }

    /** The decoded folder as an SDK keeps it (only [which] files). */
    fun writeDecoded(dir: File, files: List<F>, m: ModelManifest, which: (F) -> Boolean = { it.need == "required" && "*" in it.platforms }) {
        for (f in files.filter(which)) File(dir, f.path).also { it.parentFile.mkdirs() }.writeBytes(f.bytes)
        File(dir, ModelManifest.FILE_NAME).writeText(m.toJson())
    }
}
