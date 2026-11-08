package ai.quranlab.tajweedformer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The model on this device: fetches the signed format 3 manifest, downloads only the profile in use
 * ([ModelProfile]), and opens the verified folder as a [TajweedModel].
 *
 * ```
 * val store = ModelStore(File(context.noBackupFilesDir, "tajweedformer"), headers = { mapOf("x-qlab-key" to key) })
 * val model = store.ensureCpu()                       // 56.5 MB the first time, nothing after
 * ```
 *
 * Network policy (LAYOUT.md): the CPU profile downloads on any network; NPU files ([ensureNpu]) only
 * on an unmetered network and only after the CPU path passed its self-test. All calls block: run
 * them off the main thread (the [TajweedFormerClient] façade does).
 */
class ModelStore @JvmOverloads constructor(
    /** Where the model is kept (app-private storage; the engine maps these files). */
    val root: File,
    /** The model folder's URL, ending in `/`. */
    val baseUrl: String = DEFAULT_BASE_URL,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val headers: HeaderProvider? = null,
    val verifier: ManifestVerifier = ManifestVerifier(),
) {
    val downloader = ModelDownloader(root, baseUrl, transport, headers)

    @Volatile private var cached: ModelManifest? = null

    /**
     * The manifest, verified. With [refresh] it is fetched from [baseUrl] first; when the network
     * fails the copy on disk is used if there is one (verified again). Throws
     * [RecognizerException.DownloadFailed] when there is neither.
     */
    @JvmOverloads
    fun manifest(refresh: Boolean = false): ModelManifest {
        if (!refresh) cached?.let { return it }
        val local = File(root, ModelManifest.FILE_NAME)
        if (refresh || !local.isFile) {
            try {
                val text = fetchText(ModelManifest.FILE_NAME)
                val m = ModelManifest.parse(text)
                verifier.verify(m)
                root.mkdirs()
                val tmp = File(root, ModelManifest.FILE_NAME + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                Files.move(tmp.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cached = m
                return m
            } catch (e: RecognizerException.DownloadFailed) {
                if (!local.isFile) throw e
                SdkEvents.emit { it.onError("manifest", e) }
            }
        }
        val m = ModelManifest.read(root)
        verifier.verify(m)
        cached = m
        return m
    }

    private fun fetchText(path: String): String {
        val h = HashMap<String, String>()
        headers?.headers(baseUrl + path)?.let { h.putAll(it) }
        h["Accept-Encoding"] = "identity"
        try {
            transport.get(baseUrl + path, h).use { r ->
                if (r.code != 200) throw RecognizerException.DownloadFailed(path, "HTTP ${r.code}")
                val bytes = r.body.readBytes()
                if (bytes.size > MAX_MANIFEST_BYTES) throw RecognizerException.DownloadFailed(path, "too large")
                return String(bytes, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            throw RecognizerException.DownloadFailed(path, "network: ${e.message}", e)
        }
    }

    /** The context the profile is for when none is given: the manifest's default. */
    private fun ctx(context: String?, m: ModelManifest) = context ?: m.defaultContext

    /** The CPU profile files of [context] not yet in place. */
    @JvmOverloads
    fun missingCpu(context: String? = null): List<ModelManifest.FileEntry> {
        val m = manifest()
        return ModelProfile.cpu(m, ctx(context, m)).filter { !downloader.isInPlace(it) }
    }

    /**
     * Downloads what is missing of the CPU profile of [context] (any network) and opens the model.
     * [onProgress] gets each file's progress; the totals of the whole profile are in [ProfileProgress].
     */
    @JvmOverloads
    fun ensureCpu(
        context: String? = null,
        cancel: Cancellation? = null,
        onProgress: ((ProfileProgress) -> Unit)? = null,
    ): TajweedModel {
        val m = manifest()
        val c = ctx(context, m)
        fetchAll(ModelProfile.cpu(m, c), cancel, onProgress, m.revision)
        return TajweedModel.open(root, verifier, m, listOf(c))
    }

    /** Opens what is on disk without downloading (null when the profile of [context] is incomplete). */
    @JvmOverloads
    fun openIfReady(context: String? = null): TajweedModel? {
        val m = try { manifest() } catch (e: RecognizerException) { return null }
        val c = ctx(context, m)
        if (ModelProfile.cpu(m, c).any { !downloader.isInPlace(it) }) return null
        return TajweedModel.open(root, verifier, m, listOf(c))
    }

    /** The NPU files of [soc] and [context] in the manifest (empty: no NPU build for them). */
    fun npuFiles(soc: String?, context: String): List<ModelManifest.FileEntry> = ModelProfile.npu(manifest(), soc, context)

    /** Whether every NPU file of [soc] and [context] is in place and verified. */
    fun isNpuReady(soc: String?, context: String): Boolean {
        val f = npuFiles(soc, context)
        return f.isNotEmpty() && f.all { downloader.isInPlace(it) }
    }

    /**
     * Downloads the NPU files of [soc] and [context] when allowed: there is a build for them, the CPU
     * path passed its self-test ([cpuSelfTestPassed]) and [network] is unmetered now. Returns the
     * local files (model or parts, io, masks) when they are all in place, else null with the reason in
     * [NpuDownload.reason]. A chained build downloads every part (all or nothing: it is ready only when
     * every part is in place and verified).
     */
    @JvmOverloads
    fun ensureNpu(
        soc: String?,
        context: String,
        network: NetworkPolicy,
        cpuSelfTestPassed: Boolean,
        cancel: Cancellation? = null,
        onProgress: ((ProfileProgress) -> Unit)? = null,
    ): NpuDownload {
        if (soc == null) return NpuDownload(null, "soc_unknown")
        val files = npuFiles(soc, context)
        if (files.isEmpty()) return NpuDownload(null, "no_npu_build")
        if (files.all { downloader.isInPlace(it) }) return NpuDownload(npuLocal(files), "ready")
        if (!cpuSelfTestPassed) return NpuDownload(null, "cpu_self_test_pending")
        if (!network.isUnmetered()) return NpuDownload(null, "metered_network")
        fetchAll(files, cancel, onProgress, manifest().revision)
        return NpuDownload(npuLocal(files), "downloaded")
    }

    private fun npuLocal(files: List<ModelManifest.FileEntry>): NpuFiles {
        fun f(role: String) = downloader.local(files.first { it.role == role })
        val parts = files.filter { it.role == ModelProfile.NPU_PART_ROLE }
            .sortedWith { a, b -> ModelManifest.compareUtf8(a.localPath, b.localPath) }.map { downloader.local(it) }
        return if (parts.isEmpty()) NpuFiles(f("npu-model"), f("npu-io"), f("npu-masks"))
        else NpuFiles(parts.first(), f("npu-io"), f("npu-masks"), parts)
    }

    private fun fetchAll(files: List<ModelManifest.FileEntry>, cancel: Cancellation?, onProgress: ((ProfileProgress) -> Unit)?, revision: String) {
        val total = ModelProfile.bytes(files)
        var done = 0L
        // small files first, so a model is quickly known to be usable or not
        for (e in files.sortedBy { it.size }) {
            downloader.fetch(e, cancel, { p -> onProgress?.invoke(ProfileProgress(p.path, done + p.bytes, total)) }, revision)
            done += e.size
            onProgress?.invoke(ProfileProgress(e.path, done, total))
        }
    }

    /** Deletes kept files the current manifest does not list (an older revision's), and stale parts. */
    fun prune(): Int {
        val m = manifest()
        val keep = m.files.map { it.localPath }.toSet() + m.files.flatMap { listOf(it.path + ".part", it.path + ".part.v") } +
            setOf(ModelManifest.FILE_NAME, VerifiedStamps.FILE_NAME)
        var n = 0
        root.walkBottomUp().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel !in keep && f.delete()) n++
        }
        return n
    }

    companion object {
        /**
         * The Quran Lab CDN's mirror of `Quran-Lab/tajweedformer-v4` (`/hf/{org}/{repo}/{commit}/{path}`,
         * app key in `x-qlab-key`). The commit segment is the ingested revision: `main` here is a
         * placeholder until the model is published; pass the published one (the portal's setup API
         * gives it) as `baseUrl`.
         */
        const val DEFAULT_BASE_URL = "https://cdn.quranlab.ai/hf/Quran-Lab/tajweedformer-v4/main/sdk/v4.0/"
        const val MAX_MANIFEST_BYTES = 1 shl 20
    }
}

/** Bytes of a profile so far ([path] = the file in progress). */
data class ProfileProgress(val path: String, val bytes: Long, val total: Long) {
    val fraction: Double get() = if (total > 0) bytes.toDouble() / total else 1.0
}

/**
 * The local NPU files of one SoC and context. For a chained build [parts] holds every part (in path order; the
 * run order is io.json's `parts`, matched by file name) and [model] is the first of them; for a one-model build
 * [parts] is empty.
 */
data class NpuFiles @JvmOverloads constructor(val model: File, val io: File, val masks: File, val parts: List<File> = emptyList())

/** [ModelStore.ensureNpu]'s answer: the files when in place, and why (`ready`, `downloaded`,
 *  `soc_unknown`, `no_npu_build`, `cpu_self_test_pending`, `metered_network`). */
data class NpuDownload(val files: NpuFiles?, val reason: String)
