package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.AnswerClass
import ai.quranlab.tajweedformer.cloud.HostAnswer
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
    /** SDK 4.0.1: the model hosts the folder's path is asked of; null: the folder's URL only. */
    private val endpoints: ai.quranlab.tajweedformer.cloud.Endpoints? = null,
    /** The verdicts of the manifest's check, remembered for the exact bytes that passed it (4.0.1); null: always in full. */
    private val verdicts: ManifestVerdicts? = null,
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
            current ?: ModelDownloader(root, filesBase, transport, headers, ranges, retry, ModelHosts.of(filesBase, endpoints)).also { current = it }
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
                // 28.3: a manifest that does not verify is no answer of its host, and the next model host is asked
                var got: Pair<ModelManifest, String>? = null
                val bytes = fetchBytes(ModelManifest.FILE_NAME) { b ->
                    got = try { accept(b) } catch (x: TajweedError) { throw x.ofManifest("manifest") }
                }
                val (m, sha) = got!!
                root.mkdirs()
                val tmp = File(root, ModelManifest.FILE_NAME + ".tmp")
                tmp.writeBytes(bytes)
                Files.move(tmp.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // the verdict sealed beside the bytes now on the device (the folder exists now)
                verdicts?.passed(root, sha, verifier, ManifestVerdicts.Flags(m.toleranceSigned, m.rawToleranceSigned))
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
        val m = try { accept(local.readBytes()).first } catch (x: TajweedError) { throw x.ofManifest("verify") }
        cached = m
        return m
    }

    /**
     * [ModelManifest.accept] of [bytes], with the verdict remembered for these exact bytes ([verdicts], 4.0.1): bytes
     * identical to ones that passed this verifier skip the signature's check (the config's build and version are
     * checked every time); any other bytes are checked in full, and remembered when they pass. Returns the manifest and
     * the SHA-256 of the bytes.
     */
    private fun accept(bytes: ByteArray): Pair<ModelManifest, String> {
        val sha = ModelManifest.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        val known = verdicts?.known(root, sha, verifier)
        val m = ModelManifest.accept(bytes, verifier, expectBuild, expectVersion, known)
        if (known == null) verdicts?.passed(root, sha, verifier, ManifestVerdicts.Flags(m.toleranceSigned, m.rawToleranceSigned))
        return m to sha
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

    /**
     * A small file whole (the manifest), which [accept] takes or throws for. It is asked once (SPEC 16.2): the retry rule
     * is for the files it lists. SDK 4.0.1: one request through the `models` hosts, the base's own
     * host first when it is not one of them; a host that does not answer, fails (5xx), redirects, answers a page that is not
     * its own or a file [accept] refuses was unreachable, and the next is asked at once. A refusal or a 429 ends it there.
     */
    private fun fetchBytes(path: String, accept: (ByteArray) -> Unit): ByteArray {
        val hosts = ModelHosts.of(filesBase, endpoints)
        val e = endpoints
        if (hosts.fixed || e == null) return fetchOnce(filesBase + path, path).also(accept)
        val got = e.request<Any>(e.models, fileStage(path), first = hosts.baseHost) { _, origin ->
            try {
                val bytes = fetchOnce(origin + hosts.path + path, path)
                try {
                    accept(bytes)
                    HostAnswer.ends(AnswerClass.TAKEN, bytes)
                } catch (x: TajweedError) {
                    HostAnswer.unreachable(null, x, "invalid_answer", 200)
                }
            } catch (x: RecognizerException.DownloadFailed) {
                // the system's refusal of the network is the same on every host
                if (x.cause is NetworkNotPermitted) throw x
                val c = x.cause40
                if (RetryPlan.resumable(x, true)) HostAnswer.unreachable(null, x, c?.reason ?: "network", c?.httpStatus)
                else HostAnswer.ends(if (c?.reason == "rate_limited") AnswerClass.SLOW_DOWN else AnswerClass.REFUSED, x)
            } catch (x: TajweedError) {
                // a refusal the Client read from the answer (a stop, the key, the daily limit)
                HostAnswer.ends(if (x is RecognizerException.ModelLimit) AnswerClass.SLOW_DOWN else AnswerClass.REFUSED, x)
            }
        }
        val v = got.answer.value
        if (v is ByteArray) return v
        val x = (v as? TajweedError) ?: (got.answer.failure as TajweedError)
        throw x.via("models", got.fallback)
    }

    /** One request for a small file at [url]. */
    private fun fetchOnce(url: String, path: String): ByteArray {
        try {
            modelRequest(transport, url, path, mapOf("Accept-Encoding" to "identity"), headers, setOf(200)).use { r ->
                val bytes = r.body.readBytes()
                if (bytes.size > MAX_MANIFEST_BYTES) {
                    throw RecognizerException.DownloadFailed(path, "too large", WrongAnswer("too large")).at("manifest", "invalid_answer", r.code)
                }
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
        return ModelFolder.protected(build)
    }

    /** Downloads [files] (the sample clips, a file the app asked for) that are not on the device yet. */
    fun fetch(files: List<ModelManifest.FileEntry>, cancel: Cancellation? = null) =
        fetchAll(files.filter { !downloader.isInPlace(it) }, cancel, null, manifest().build)

    /**
     * Downloads [files], up to [FILES_AT_ONCE] at a time (4.0.1; until 4.0.0 one after another, the small ones first):
     * the largest first, so the weights (about 54 MB, itself in ranges) start at once and the small files come beside
     * them. See [FilesAtOnce]. Progress is the bytes of all of them together, never lower than a report before it.
     */
    private fun fetchAll(files: List<ModelManifest.FileEntry>, cancel: Cancellation?, onProgress: ((ProfileProgress) -> Unit)?, revision: String) {
        if (files.isEmpty()) return
        // every file of the build comes only from the folder a lease answer named (18.4.5); none named: none is fetched
        if (filesBase.isEmpty()) throw RecognizerException.DownloadFailed(files.first().path, "no lease answer has named a valid folder for the build")
        FilesAtOnce(downloader, files.sortedByDescending { it.size }, FILES_AT_ONCE, cancel, onProgress, revision).run()
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 1 shl 20

        /** Files downloaded at the same time (4.0.1): the weights and three small files, or four small ones. */
        const val FILES_AT_ONCE = 4
    }
}

/**
 * Several files of one call at the same time (4.0.1, perf plan row 5: the CPU set is about 14 files, and each small one
 * waited for the one before it).
 *
 * - **At most [streams] files at once**, on threads of their own, taken from [files] in their order; one file alone
 *   is downloaded on the calling thread. Each file is downloaded as [ModelDownloader.fetch] downloads one (its resumes,
 *   its ranges, its checks); the Core's installs come after, on the caller's thread, as before.
 * - **The first failure ends the call**: no new file starts, the other files' requests are ended (their parts and
 *   maps stay for the next call, as a cut download's do), and the call fails with that first failure, never with the
 *   end it caused in the others (those are not reported as downloads of their own: [Cancellation.bySibling]). A
 *   refusal (the Client's error for an answer: a stop, the daily limit, a key the CDN does not take) is the call's
 *   error whatever failed first, as within one file in ranges.
 * - **A refusal's work** (a stop remembered, the model's files removed: [ModelAuth.refuse]) is done only once every
 *   other file has stopped writing: the file that read it ends the others first and waits for them, so nothing of the
 *   model is written after it was removed. Two files refused at once each wait only for the files not refused.
 * - **The token is renewed at most once** for all the files (401 `invalid_token` on several files at once): the others
 *   repeat with the new token.
 * - **A cancel** of the call (the Client closed, the caller's thread interrupted) ends every file.
 */
internal class FilesAtOnce(
    private val downloader: ModelDownloader,
    private val files: List<ModelManifest.FileEntry>,
    streams: Int,
    private val cancel: Cancellation?,
    private val onProgress: ((ProfileProgress) -> Unit)?,
    private val revision: String,
) {
    private val streams = maxOf(1, minOf(streams, files.size))
    private val total = ModelProfile.bytes(files)
    /** The files' own cancel: the caller's, and the first failure's. */
    private val own = Cancellation()
    private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue(files)
    /** Per stream: whether it is inside a file's download now (its requests may still write). */
    private val busy = BooleanArray(this.streams)
    /** Per stream: whether it read a refusal and waits to do its work. */
    private val refusing = BooleanArray(this.streams)
    private val state = Object()
    private var renewed = false
    /** Bytes of each file so far, as reported; the sum is what the call reports. */
    private val bytes = HashMap<String, Long>()
    private var reported = -1L

    fun run() {
        val linked = cancel?.whenCancelled { own.cancel() }
        try {
            if (streams == 1) {
                stream(0)
            } else {
                val ended = java.util.concurrent.CountDownLatch(streams)
                for (w in 0 until streams) {
                    Thread({
                        try {
                            stream(w)
                        } finally {
                            ended.countDown()
                        }
                    }, "tajweedformer-file-$w").apply { isDaemon = true }.start()
                }
                try {
                    ended.await()
                } catch (x: InterruptedException) {
                    // the caller's thread was interrupted (a cancelled coroutine): every file ends, their parts stay
                    own.cancel()
                    try { ended.await(HALT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                    Thread.currentThread().interrupt()
                    throw RecognizerException.DownloadFailed(files.first().path, "cancelled").tagged(files.first().path, "cancelled", null)
                }
            }
        } finally {
            linked?.close()
        }
        failure.get()?.let { throw it }
        // a call cancelled before a file failed (or before any started) is still a cancelled download
        queue.peek()?.let { e -> throw RecognizerException.DownloadFailed(e.path, "cancelled").tagged(e.path, "cancelled", null) }
    }

    /** One stream: files from the queue until it is empty or the call has failed. */
    private fun stream(w: Int) {
        val auth = downloader.headers?.let { inner -> if (inner is ModelAuth) StreamAuth(inner, w) else inner }
        // a cancelled call goes on taking files until one fails as cancelled (a file in place is not downloaded at all)
        while (failure.get() == null) {
            val e = queue.poll() ?: break
            synchronized(state) { busy[w] = true }
            try {
                downloader.fetch(e, own, { p -> progress(p.path, p.bytes) }, revision, auth)
                progress(e.path, e.size)
            } catch (x: Throwable) {
                failed(x)
            } finally {
                synchronized(state) {
                    busy[w] = false
                    state.notifyAll()
                }
            }
        }
    }

    /** The first failure is the call's, and ends the other files; a refusal is the call's whatever failed first. */
    private fun failed(x: Throwable) {
        val refusal = x is TajweedError && x !is RecognizerException.DownloadFailed
        // the others' ends that this failure (or the caller's cancel) caused are not failures of their own
        val first = if (refusal) { failure.set(x); true } else failure.compareAndSet(null, x)
        if (first || refusal) {
            if (!(cancel?.isCancelled ?: false)) own.bySibling = true
            own.cancel()
        }
    }

    /** A file's bytes so far: the sum over the files, reported in order and never lower than a report before it. */
    private fun progress(path: String, n: Long) {
        val cb = onProgress ?: return
        synchronized(bytes) {
            if (n > (bytes[path] ?: 0L)) bytes[path] = n
            val sum = bytes.values.sum()
            if (sum < reported) return
            reported = sum
            try { cb(ProfileProgress(path, sum, total)) } catch (_: Exception) {}
        }
    }

    /**
     * The Client's model-request rules for one stream of this call: the token renewed once for every file, and a
     * refusal that first ends every other file and then waits for them before its work on the device.
     */
    private inner class StreamAuth(private val inner: ModelAuth, private val w: Int) : ModelAuth {
        override fun headers(url: String): Map<String, String> = inner.headers(url)
        override fun renewal(status: Int, body: ByteArray): Boolean = inner.renewal(status, body)

        /** Inside the lock: a file refused at the same time waits here until the token is renewed, then repeats with it. */
        override fun renew() {
            synchronized(this@FilesAtOnce) {
                if (renewed) return
                renewed = true
                inner.renew()
            }
        }

        override fun refused(status: Int, body: ByteArray): TajweedError? {
            val error = inner.refused(status, body) ?: return null
            // recorded before anything is ended, so the call fails with it however the other files end
            failed(error)
            return error
        }

        /** Every other file has stopped writing (each that is not itself refused), then the refusal's work. */
        override fun refuse(status: Int, body: ByteArray) {
            synchronized(state) {
                refusing[w] = true
                try {
                    while ((0 until streams).any { it != w && busy[it] && !refusing[it] }) state.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            synchronized(this@FilesAtOnce) { inner.refuse(status, body) }
        }

        override fun served() = inner.served()
    }

    private companion object {
        /** How long a cancelled call waits for its files' streams. */
        const val HALT_WAIT_MS = 5_000L
    }
}

/** Bytes of a profile so far ([path] = the file in progress). */
internal data class ProfileProgress(val path: String, val bytes: Long, val total: Long) {
    val fraction: Double get() = if (total > 0) bytes.toDouble() / total else 1.0
}
