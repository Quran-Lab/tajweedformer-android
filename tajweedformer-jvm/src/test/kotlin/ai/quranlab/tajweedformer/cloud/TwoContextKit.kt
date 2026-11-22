package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.BackgroundDownloads
import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.ProtectedKit
import ai.quranlab.tajweedformer.ProtectedKit.b64u
import ai.quranlab.tajweedformer.ProtectedKit.hex
import ai.quranlab.tajweedformer.ProtectedKit.sha256
import ai.quranlab.tajweedformer.ProtectedKit.unb64u
import ai.quranlab.tajweedformer.RetryPlan
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.core.DispatchLibrary
import java.io.File
import java.nio.file.Files

/**
 * A fresh install's Client on the desktop JVM with the REAL Core (as ClientModelOpenTest), serving the two-context
 * test build (`two_context_build/`, scripts/testdata/make_two_context_build.py: the contract's synthetic build with a
 * second context, c24_l256, and a self-test set) at the folder the lease names, on the model host of a test build's
 * endpoints. The connection the platform reports is the test's ([network]). Every request of the build is recorded
 * by [served]; the API's by [stub].
 */
internal class TwoContextKit : AutoCloseable {
    val stub = StubQlab()
    private val dirs = ArrayList<File>()
    val served: ServedModel
    val manifest: ModelManifest

    /** The lease's flags (18.4.6): bit 3 (8) allows accelerator programs. */
    @Volatile var leaseFlags = 0

    /**
     * What the platform says the network is. SDK 4.0.2 asks the platform nothing else: 4.0.1's `isUnmetered()` and
     * `saveData()` are gone from [NetworkPolicy], so with the system's data saver on the platform's only answer is
     * `metered` (Android's report then, [ai.quranlab.tajweedformer.android.AndroidNetwork]).
     */
    @Volatile var connection = "unmetered"

    /** How often the SDK asked the platform for the connection. */
    val connectionAsked = java.util.concurrent.atomic.AtomicInteger()

    val network = NetworkPolicy {
        connectionAsked.incrementAndGet()
        connection
    }

    init {
        val src = File(javaClass.getResource("/two_context_build/manifest.json")!!.toURI()).parentFile
        // a copy of its own: a test may make the server lose a file
        val dir = temp()
        src.copyRecursively(dir)
        served = ServedModel.ofFolder(dir)
        manifest = ModelManifest.parse(File(dir, ModelManifest.FILE_NAME).readBytes())
        stub.inference = null
        stub.recommended = mapOf("model" to ServedModel.REPO, "model_id" to "v4.0", "folder" to ServedModel.FOLDER, "revision" to served.build,
            "version" to manifest.version, "key_id" to manifest.encryption!!.keyId, "source" to "cdn")
        val keyId = QuranLabKey.parse(stub.key)!!.keyId
        stub.overrides["/v1/attest/challenge"] = { n, _ ->
            StubQlab.Reply.json(200, mapOf("challenge" to "c1.${now() + 300}.${"A".repeat(21)}${'A' + (n % 26)}.${"B".repeat(22)}", "expires_in" to 300))
        }
        stub.overrides["/v1/attest"] = { _, q ->
            val b = q.json
            if (b == null || !b.containsKey("install_key")) null else {
                val ik = unb64u(b["install_key"] as String)
                val sk = unb64u(b["install_sign_key"] as String)
                StubQlab.Reply.json(200, mapOf("token" to stub.token(false), "expires_in" to 86_400, "attested" to false,
                    "install_cert" to ProtectedKit.cert("android:${stub.appId}", "android", keyId, hex(sha256(ik)), hex(sha256(sk)), att = "none",
                        hw = 0, iat = now(), exp = now() + 30L * 86_400, recheck = 0)))
            }
        }
        stub.overrides["/v1/model-key"] = { n, q ->
            leaseReply?.invoke(n) ?: leaseAnswer(q)
        }
    }

    /** For the n-th lease request (from 1): an answer in place of the lease (a 503 `lease_unavailable`); null: the lease. */
    @Volatile var leaseReply: ((Int) -> StubQlab.Reply?)? = null

    /** Seconds from a lease's `iat` to its `refresh_after` (0: due at once). */
    @Volatile var refreshAfterS = 43_200L

    private fun leaseAnswer(q: StubQlab.Seen): StubQlab.Reply {
        run {
            val b = q.json!!
            val ik = unb64u(b["install_key"] as String)
            val iat = now() - 60
            val exp = iat + 86_400
            val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = leaseFlags, iat = iat, exp = exp, refreshAfter = iat + refreshAfterS,
                keyId = hex(manifest.encryption!!.keyId), build = hex(b["build"] as String), installHash = sha256(ik),
                model = b["model"] as String, version = b["version"] as String), ik)
            return StubQlab.Reply.json(200, linkedMapOf("v" to 1, "lease" to b64u(lease), "iat" to iat, "exp" to exp, "refresh_after" to iat + refreshAfterS,
                "base" to baseOf(b["build"] as String), "install_cert" to b["install_cert"]))
        }
    }

    private val api = HostNames.API[0]
    private val cdn = HostNames.MODELS[0]
    /** The folder a lease of [build] names (the served build, or one of [ServedModel.others]). */
    private fun baseOf(build: String) = "https://$cdn/${ServedModel.REPO}/$build/${ServedModel.FOLDER}/"

    fun temp(): File = Files.createTempDirectory("tj-twoctx-").toFile().also { dirs += it }

    private fun now() = System.currentTimeMillis() / 1000

    /** One install's storage (a new process of the same install: [client] again on it). */
    val storage: File = temp()

    /**
     * A Client of this install. [soc] and a dispatch library for it: the accelerator gate fetches the build's program
     * for that SoC (the test build has one for tensor_g4); [downloads]: the platform's background job.
     */
    fun client(engine: ai.quranlab.tajweedformer.TajweedEngineFactory = CountingEngines(), soc: String? = null,
               downloads: BackgroundDownloads? = null): TajweedClient {
        val ep = Endpoints.mapped(listOf(api), listOf(cdn), mapOf(api to stub.base, cdn to served.url))
        val platform = ClientPlatform(cloud = stub.environment(storage), apiUrl = stub.base, endpoints = ep,
            trustedKeys = listOf(stub.publicKey, ProtectedKit.signerPublic), engine = engine, modelRetry = RetryPlan.NONE,
            network = network, soc = soc, downloads = downloads,
            dispatch = { s -> DispatchLibrary.Resolution(DispatchLibrary.Folder(temp(), "libedgetpu_litert.so", "lib:test:$s", false), null) })
        return TajweedClient(stub.key, TajweedClientOptions(), platform, autoLaunch = false)
    }

    /**
     * A newer build of the model (the config's next build, an update): the same files under a manifest of another
     * revision, signed with the contract's test signer, served beside the first. Returns its build id.
     */
    fun newerBuild(): String {
        val dir = temp()
        served.root.copyRecursively(dir, overwrite = true)
        val f = File(dir, ModelManifest.FILE_NAME)
        @Suppress("UNCHECKED_CAST")
        val m = LinkedHashMap(ai.quranlab.tajweedformer.Json.parse(f.readText()) as Map<String, Any?>)
        m["revision"] = "v4.1-two-contexts-402-newer"
        val text = ModelManifest.parse(ai.quranlab.tajweedformer.Json.write(m).toByteArray()).signedMessage
        m["signature"] = java.util.Base64.getEncoder().encodeToString(ProtectedKit.Ed.sign(ProtectedKit.signerSeed, text))
        f.writeText(ai.quranlab.tajweedformer.Json.write(m))
        val build = sha256(f.readBytes()).joinToString("") { "%02x".format(it) }.take(40)
        served.others = served.others + (build to dir)
        return build
    }

    /** The config names [build] (the served one, or one of [newerBuild]). */
    fun offer(build: String) {
        stub.recommended = stub.recommended!! + mapOf("revision" to build)
        // the offer comes in a later session: the config the device holds is older than 300 s, so the next process asks
        // again (28.11 item 5: within 300 s of the last config a new process asks none)
        File(storage, "cloud/config.at").takeIf { it.isFile }?.writeText((System.currentTimeMillis() - 301_000L).toString())
    }

    /** The paths of the build asked for, in order. */
    fun paths(): List<String> = served.paths()

    /** The CDN paths of [context]'s own files (its graph, graph data and frames). */
    fun contextFiles(context: String): List<String> = manifest.files.filter { it.context == context && it.role in setOf("graph", "graph-data", "frames") }
        .map { it.path }.sorted()

    /** The CDN path of the build's reference data. */
    val reference: String get() = manifest.files.single { it.role == "reference" }.path

    /** The CDN path of the NPU program of tensor_g4. */
    val program: String get() = manifest.files.single { it.role == "npu-model" }.path

    override fun close() {
        served.close()
        stub.close()
        dirs.forEach { it.deleteRecursively() }
    }
}
