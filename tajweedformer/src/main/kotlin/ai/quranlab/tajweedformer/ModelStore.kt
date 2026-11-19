package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.ProtectedBuild
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The model on this device (SDK 4.0): fetches the signed format 3 manifest, downloads only the CPU profile in use
 * ([ModelProfile]) in its CDN form, has the Core install each encrypted file into its device form, and opens the build
 * as a [ModelFolder].
 *
 * Made by the Client only, with the Client's headers ([HeaderProvider]: the key and the install's token, SPEC 15.4).
 * The signed config of 4.0 names the build and its key id and no folder; the lease answer names the
 * build's folder (`base`), and the manifest and every file come from there ([filesBase], [manifestFor]). No file of a
 * model is ever decoded by the SDK: a qle1 entry is kept as it came until the Core has installed it, then deleted. All
 * calls block: run them off the main thread.
 */
internal class ModelStore @JvmOverloads constructor(
    /** Where the build is kept (app-private storage). */
    val root: File,
    /** The build's folder, ending in `/` (the last lease answer's `base`), or empty while no lease named it. */
    val baseUrl: String = "",
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val headers: HeaderProvider? = null,
    val verifier: ManifestVerifier = ManifestVerifier(),
    /** The build the signed config named (40 hex, the start of the SHA-256 of the manifest's bytes); null: any. */
    private val expectBuild: String? = null,
    /** The model version the signed config named; null: any. */
    private val expectVersion: String? = null,
    /** How a large file is fetched (in ranges, SDK 3.1.3). */
    private val ranges: RangePlan = RangePlan.DEFAULT,
    /** How a request that the network cut is made again inside the call (SDK 3.1.3). */
    private val retry: RetryPlan = RetryPlan.DEFAULT,
) {
    /** The folder the build's manifest and files come from: the lease answer's `base` (18.4.5); [baseUrl] at first. */
    @Volatile var filesBase: String = baseUrl
        set(v) {
            val b = if (v.isEmpty() || v.endsWith("/")) v else "$v/"
            if (b != field) {
                field = b
                synchronized(this) { current = null }
            }
        }

    @Volatile private var current: ModelDownloader? = null

    val downloader: ModelDownloader
        get() = current ?: synchronized(this) {
            current ?: ModelDownloader(root, filesBase, transport, headers, ranges, retry).also { current = it }
        }

    @Volatile private var cached: ModelManifest? = null

    /** The manifest's bytes as they were verified (the Core is handed the same bytes). */
    fun manifestBytes(): ByteArray = File(root, ModelManifest.FILE_NAME).readBytes()

    /**
     * The manifest, verified. With [refresh] it is fetched from [filesBase] first; when the network fails (or no lease
     * named the folder yet) the copy on disk is used if there is one (verified again). Throws
     * [RecognizerException.DownloadFailed] when there is neither.
     */
    @JvmOverloads
    fun manifest(refresh: Boolean = false): ModelManifest {
        if (!refresh) cached?.let { return it }
        val local = File(root, ModelManifest.FILE_NAME)
        if (filesBase.isEmpty() && !local.isFile) {
            throw RecognizerException.DownloadFailed(ModelManifest.FILE_NAME, "no lease has named the build's folder yet")
        }
        if ((refresh || !local.isFile) && filesBase.isNotEmpty()) {
            try {
                val bytes = fetchBytes(ModelManifest.FILE_NAME)
                val m = try { ModelManifest.accept(bytes, verifier, expectBuild, expectVersion) } catch (x: TajweedError) { throw x.ofManifest("manifest") }
                root.mkdirs()
                val tmp = File(root, ModelManifest.FILE_NAME + ".tmp")
                tmp.writeBytes(bytes)
                Files.move(tmp.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cached = m
                return m
            } catch (e: TajweedError) {
                // contract 24.8: a failure of the manifest counts in the day's downloads too, under its build's version
                manifestFailed(e)
                if (e !is RecognizerException.DownloadFailed || !local.isFile) throw e
                SdkEvents.emit { it.onError("manifest", e) }
            }
        }
        if (!local.isFile) throw RecognizerException.ModelMissing("no ${ModelManifest.FILE_NAME} in ${root.path}").at("verify", "missing")
        val m = try { ModelManifest.accept(local.readBytes(), verifier, expectBuild, expectVersion) } catch (x: TajweedError) { throw x.ofManifest("verify") }
        cached = m
        return m
    }

    /**
     * The verified manifest's bytes for a lease of this build (18.4.5): [base], the lease answer's folder (null when the
     * answer named none that is Quran Lab's), becomes where the build comes from; the manifest is fetched from it unless
     * the device has it. It must be the build the config named (its bytes, version and signature, [expectBuild],
     * [expectVersion]) and carry the config's [keyId].
     */
    fun manifestFor(base: String?, keyId: String): ByteArray {
        filesBase = base ?: ""
        val local = File(root, ModelManifest.FILE_NAME).isFile
        if (base == null && !local) throw RecognizerException.DownloadFailed(ModelManifest.FILE_NAME, "the lease answer names no valid folder for the build")
        val m = manifest(refresh = !local)
        if (m.encryption?.keyId != keyId) {
            throw RecognizerException.ManifestInvalid("the manifest's key id is not the one the config named for build ${m.build}")
        }
        return manifestBytes()
    }

    /** A small file whole (the manifest). It is asked once (SPEC 16.2): the retry rule is for the files it lists. */
    private fun fetchBytes(path: String): ByteArray {
        try {
            modelRequest(transport, filesBase + path, path, mapOf("Accept-Encoding" to "identity"), headers, setOf(200)).use { r ->
                val bytes = r.body.readBytes()
                if (bytes.size > MAX_MANIFEST_BYTES) throw RecognizerException.DownloadFailed(path, "too large").at("manifest", "invalid_answer", r.code)
                return bytes
            }
        } catch (e: IOException) {
            throw RecognizerException.DownloadFailed(path, "network: ${e.message}", e).tagged(path, "network", e)
        }
    }

    /** A manifest that did not verify, as its stage names it: fetched (`manifest`) or on the device (`verify`). */
    private fun TajweedError.ofManifest(stage: String): TajweedError = when (this) {
        is RecognizerException.SignatureInvalid -> at(stage, "signature")
        is RecognizerException.ManifestInvalid -> at(stage, if (stage == "manifest") "parse" else "invalid")
        is RecognizerException.ModelIncompatible -> at(stage, "incompatible")
        is RecognizerException.ChecksumMismatch -> at(stage, "hash")
        else -> this
    }

    /** A fetch of the manifest failed: one failed download of the build the config named. */
    private fun manifestFailed(e: TajweedError) {
        val build = expectBuild ?: return
        try {
            ModelIds.expect(build, expectVersion)
            val reason = ai.quranlab.tajweedformer.cloud.ErrorRules.telemetryReason(ai.quranlab.tajweedformer.cloud.ErrorCause.of(e).reason)
            val r = DownloadResult(ModelManifest.FILE_NAME, "manifest", 0, 0, 0, false, e.code, revision = build, failureReason = reason)
            SdkEvents.emit { it.onDownloadFinished(r) }
        } catch (_: Exception) {
        }
    }

    private fun ctx(context: String?, m: ModelManifest) = context ?: m.defaultContext

    /** Whether [e] is on the device: its device form when it is a container, else the kept file. */
    private fun present(e: ModelManifest.FileEntry, build: ProtectedBuild?): Boolean =
        (e.encoding == ModelManifest.Encoding.QLE1 && build?.installed(e) == true) || downloader.isInPlace(e)

    /** The CPU profile files of [context] not yet on the device (neither installed nor downloaded). */
    @JvmOverloads
    fun missingCpu(context: String? = null, build: ProtectedBuild? = null): List<ModelManifest.FileEntry> {
        val m = manifest()
        return ModelProfile.cpu(m, ctx(context, m)).filter { !present(it, build) }
    }

    /**
     * Downloads what is missing of the CPU profile of [context] (any network), has the Core install every container
     * of it that is not installed yet ([ProtectedBuild.install]: no model may be open in the Core then), and returns
     * the build as a [ModelFolder]. [onProgress] gets each file's progress; the totals in [ProfileProgress].
     */
    @JvmOverloads
    fun ensureCpu(
        build: ProtectedBuild,
        context: String? = null,
        cancel: Cancellation? = null,
        onProgress: ((ProfileProgress) -> Unit)? = null,
        install: Boolean = true,
    ): ModelFolder {
        val m = manifest()
        val c = ctx(context, m)
        val files = ModelProfile.cpu(m, c)
        fetchAll(files.filter { !present(it, build) }, cancel, onProgress, m.build)
        if (install) build.install(files, { downloader.isInPlace(it) }, { downloader.local(it) })
        return ModelFolder.protected(build, verifier)
    }

    /** Downloads [files] (the sample clips, a file the app asked for) that are not on the device yet. */
    fun fetch(files: List<ModelManifest.FileEntry>, cancel: Cancellation? = null) =
        fetchAll(files.filter { !downloader.isInPlace(it) }, cancel, null, manifest().build)

    private fun fetchAll(files: List<ModelManifest.FileEntry>, cancel: Cancellation?, onProgress: ((ProfileProgress) -> Unit)?, revision: String) {
        if (files.isEmpty()) return
        // every file of the build comes only from the folder a lease answer named (18.4.5); none named: none is fetched
        if (filesBase.isEmpty()) throw RecognizerException.DownloadFailed(files.first().path, "no lease answer has named a valid folder for the build")
        val total = ModelProfile.bytes(files)
        var done = 0L
        // small files first, so a model is quickly known to be usable or not
        for (e in files.sortedBy { it.size }) {
            downloader.fetch(e, cancel, { p -> onProgress?.invoke(ProfileProgress(p.path, done + p.bytes, total)) }, revision)
            done += e.size
            onProgress?.invoke(ProfileProgress(e.path, done, total))
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 1 shl 20
    }
}

/** Bytes of a profile so far ([path] = the file in progress). */
internal data class ProfileProgress(val path: String, val bytes: Long, val total: Long) {
    val fraction: Double get() = if (total > 0) bytes.toDouble() / total else 1.0
}
