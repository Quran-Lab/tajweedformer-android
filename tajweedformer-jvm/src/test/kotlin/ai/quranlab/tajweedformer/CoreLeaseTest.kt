package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.ProtectedKit.b64u
import ai.quranlab.tajweedformer.ProtectedKit.hex
import ai.quranlab.tajweedformer.ProtectedKit.sha256
import ai.quranlab.tajweedformer.ProtectedKit.unb64u
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.HttpRequest
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The lease client and the install through the REAL Core (a host build of the test release 4.0.0-c1, which pins the
 * contract's test signer: scripts/build_host_core.sh), against a fake API that answers as qlab-api's protected-model
 * branch does, with the test kit's certificates, leases and signed stops. Skips when the host pair is not built.
 *
 * What it proves: the attest and lease bodies have exactly the closed schema's fields (and no `core.piece` while it is
 * 0); a lease made for the software install key (ECDH, and RSA-OAEP for wrap 2) is accepted by the Core through the
 * SDK's key store callbacks; the Core installs every container of the CPU set into its device form and no file on
 * disk holds any plaintext; each refusal of 18.4.10 gets its one repeat or its error; a verified signed stop clears the
 * lease and removes the models; a lease past its expiry by the Core's clock opens nothing.
 */
class CoreLeaseTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-core-").toFile().also { dirs += it }

    @Before
    fun need() = assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)

    @After
    fun cleanUp() {
        clients.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private val clients = ArrayList<LeaseClient>()
    private val keyId = "apps-one"
    private val key = QuranLabKey.parse("qlk_v1_${keyId}_" + "a".repeat(22))!!
    private val app = AppIdentity("org.example.recite", "1.0")

    /** The build of manifest_40.json, as the CDN serves it: its manifest bytes and every stored file. */
    @Suppress("UNCHECKED_CAST")
    private fun build(dir: File): ProtectedBuildFiles {
        val v = ProtectedKit.vector("manifest_40.json")
        val bytes = Json.write(v["manifest"]).toByteArray()
        File(dir, "manifest.json").writeBytes(bytes)
        val m = ModelManifest.parse(bytes)
        val files = (v["containers_hex"] as Map<String, String>).mapValues { hex(it.value) }
        return ProtectedBuildFiles(bytes, m, files)
    }

    internal class ProtectedBuildFiles(val bytes: ByteArray, val manifest: ModelManifest, val stored: Map<String, ByteArray>)

    /** The fake API: what each route answered last, and what a test wants a route to answer instead. */
    private inner class Api(var now: () -> Long = { System.currentTimeMillis() / 1000 }) {
        val seen = ArrayList<Pair<String, Map<String, Any?>?>>()
        var override: ((String, Map<String, Any?>?, Int) -> HttpResponse?)? = null
        var lastChallenge: String? = null
        var challenges = 0
        var certExp: Long = 0
        var leaseExp: Long = 0
        var leasePiece: ByteArray? = null
        var leaseBase: (String) -> String = { build -> "https://cdn.quranlab.ai/models/x/y/$build/sdk/v4.0/" }
        lateinit var bytes: ByteArray

        fun reply(status: Int, o: Any?) = HttpResponse(status, mapOf("content-type" to "application/json"), Json.write(o).toByteArray())

        @Suppress("UNCHECKED_CAST")
        val transport = { r: HttpRequest ->
            val path = r.url.substringAfter("http://stub").substringBefore('?')
            val body = r.body?.let { Json.parse(String(it)) as Map<String, Any?> }
            seen += path to body
            val n = seen.count { it.first == path }
            override?.invoke(path, body, n) ?: when (path) {
                "/v1/attest/challenge" -> {
                    challenges++
                    val c = "c1.${now() + 300}.${"A".repeat(21)}${'A' + (challenges % 26)}.${"B".repeat(22)}"
                    lastChallenge = c
                    reply(200, mapOf("challenge" to c, "expires_in" to 300))
                }
                "/v1/attest" -> {
                    val ik = unb64u(body!!["install_key"] as String)
                    val sk = unb64u(body["install_sign_key"] as String)
                    val ih = hex(sha256(ik))
                    // install_sig: the signing key over the agreement key's hash and the challenge (18.4.3)
                    val ok = Signature.getInstance("SHA256withECDSA").run {
                        initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(sk)))
                        update(LeaseClient.attestText(body["challenge"] as String, ih))
                        verify(unb64u(body["install_sig"] as String))
                    }
                    if (!ok) reply(401, mapOf("error" to "install_sig_invalid")) else {
                        val iat = now()
                        certExp = iat + 86_400
                        reply(200, mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400, "attested" to false,
                            "install_cert" to ProtectedKit.cert("android:${app.bundleId}", "android", keyId, ih, hex(sha256(sk)), att = "none", hw = 0,
                                iat = iat, exp = certExp, recheck = 0)))
                    }
                }
                "/v1/model-key" -> {
                    val b = body!!
                    val ik = unb64u(b["install_key"] as String)
                    val iat = now() - 60
                    leaseExp = iat + 86_400
                    val m = ModelManifest.parse(bytes)
                    val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = 0, iat = iat, exp = leaseExp, refreshAfter = iat + 43_200,
                        keyId = hex(m.encryption!!.keyId), build = hex(b["build"] as String), installHash = sha256(ik),
                        model = b["model"] as String, version = b["version"] as String), ik)
                    reply(200, linkedMapOf("v" to 1, "lease" to b64u(lease), "iat" to iat, "exp" to leaseExp, "refresh_after" to iat + 43_200,
                        "base" to leaseBase(b["build"] as String), "install_cert" to b["install_cert"]).also { o ->
                        leasePiece?.let { o["piece"] = b64u(it) }
                    })
                }
                else -> reply(404, mapOf("error" to "not_found"))
            }
        }
    }

    private class Clock(var wallMs: Long = System.currentTimeMillis(), var bootMs: Long = 1_000_000)

    private fun client(dir: File, api: Api, clock: Clock? = null, rsa: Boolean = false, removed: () -> Boolean = { true }): LeaseClient =
        LeaseClient(dir, SoftwareIdentity(File(dir, "keys"), rsaAgreement = rsa), FileStateVault(File(dir, "vault")), QuranLabApi("http://stub", api.transport),
            key, app, token = { "qlt_v1.e30.s" }, invalidateToken = {}, adoptToken = {}, integrity = null, projectNumber = { null },
            removeModels = removed, hostClock = clock?.let { c -> ({ c.wallMs }) to ({ c.bootMs }) }).also { clients += it }

    private fun protectedBuild(dir: File, b: ProtectedBuildFiles, l: LeaseClient): ProtectedBuild = ProtectedBuild(dir, b.manifest, b.bytes, l)

    @Suppress("UNCHECKED_CAST")
    @Test
    fun attestLeaseAndInstallThroughTheCore() {
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val l = client(File(dir, "core"), api)
        val pb = protectedBuild(dir, b, l)
        val c = pb.core()
        // the attest's body: the closed schema of POST /v1/attest with install keys (qlab-api ATTEST4), android
        val attest = api.seen.first { it.first == "/v1/attest" }.second!!
        assertEquals(setOf("platform", "bundle_id", "challenge", "install_key", "install_sign_key", "install_sig", "install_hw"), attest.keys)
        assertEquals(0.0, attest["install_hw"])
        // the lease request: exactly 18.4.4's fields; `core` as the Core wrote it, `piece` included (R9: 0 without one)
        val req = api.seen.first { it.first == "/v1/model-key" }.second!!
        assertEquals(setOf("v", "platform", "install_cert", "install_key", "install_sign_key", "challenge", "install_sig", "model", "version",
            "build", "key_id", "core"), req.keys)
        val core = req["core"] as Map<String, Any?>
        assertEquals(setOf("release", "target", "proof", "signals", "piece"), core.keys)
        assertEquals(0.0, core["piece"])
        assertEquals(ProtectedKit.RELEASE, core["release"])
        assertEquals(22, (core["proof"] as String).length)
        assertEquals(b.manifest.encryption!!.keyId, req["key_id"])
        assertEquals(b.manifest.build, req["build"])
        // install_sig of the lease: the signing key over 'quranlab-lease-ask 1\n' challenge '\n' build '\n'
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64u(req["install_sign_key"] as String))))
            update("quranlab-lease-ask 1\n${req["challenge"]}\n${req["build"]}\n".toByteArray())
            verify(unb64u(req["install_sig"] as String))
        }
        assertTrue("the lease's install_sig", ok)
        val st = c.tick()
        assertTrue(st.hasLease && st.valid)
        assertEquals(api.leaseExp, st.exp)
        // the CDN forms, as the downloader keeps them (cdn/<decoded path>), then the Core's install
        val cpu = ModelProfile.cpu(b.manifest, "c16_l64")
        for (e in cpu) {
            val f = if (e.encoding == ModelManifest.Encoding.QLE1) File(pb.cdnDir, e.localPath) else File(dir, e.path)
            f.parentFile.mkdirs()
            f.writeBytes(b.stored.getValue(e.path))
        }
        pb.install(cpu, { e -> File(pb.cdnDir, e.localPath).isFile }, { e -> File(pb.cdnDir, e.localPath) })
        for (e in cpu.filter { it.encoding == ModelManifest.Encoding.QLE1 }) {
            assertTrue("${e.localPath} installed", pb.installed(e))
            assertFalse("${e.localPath}: its CDN form goes once installed", File(pb.cdnDir, e.localPath).exists())
        }
        assertTrue(pb.contextInstalled("c16_l64"))
        // no plaintext of any encrypted file anywhere on disk (the whole folder, every file's SHA-256)
        val plain = b.manifest.files.filter { it.encoding == ModelManifest.Encoding.QLE1 }.map { it.plainSha256 }.toSet()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("${f.path} holds a model file's plaintext", ProtectedKit.hex(sha256(f.readBytes())) in plain)
        }
        // the device form is not the CDN form (another key, raw payload, the install's mark)
        val dev = File(pb.deviceDir, "layers.zfw").readBytes()
        assertNotEquals(ProtectedKit.hex(sha256(dev)), b.manifest.file("layers.zfw")!!.localSha256)
        // the vectors' build has no layout file and no real weights: the Core decrypts and refuses before the engine
        try {
            pb.open("c16_l64", 1, 16_000, -1)
            fail("a build without a layout opened")
        } catch (e: TajweedError) {
            assertTrue(e.code, e.code in setOf("model_invalid", "engine_failed"))
        }
    }

    @Test
    fun theRsaWrapOfOlderAndroid() {
        // API 26 to 30 (the Alcatel 1): an RSA-2048 agreement key, OAEP with SHA-1 and MGF1-SHA-1 (18.4.6 wrap 2)
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val l = client(File(dir, "core"), api, rsa = true)
        val c = protectedBuild(dir, b, l).core()
        assertTrue(c.key.rsa)
        assertTrue(c.tick().valid)
    }

    @Test
    fun aVerifiedSignedStopClearsTheLeaseAndRemovesTheModels() {
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        var removed = 0
        val l = client(File(dir, "core"), api, removed = { removed++; true })
        val pb = protectedBuild(dir, b, l)
        pb.core()
        // the next request is refused with a signed stop for this install and this request's challenge
        api.override = { path, body, _ ->
            if (path != "/v1/model-key") null else {
                val ih = hex(sha256(unb64u(body!!["install_key"] as String)))
                api.reply(401, mapOf("error" to "invalid_key", "reason" to "key_revoked",
                    "signed" to ProtectedKit.signedRefusal(keyId, "key_revoked", ih, body["challenge"] as String, System.currentTimeMillis() / 1000)))
            }
        }
        try {
            l.request(pb.target, pb::roots)
            fail("the stop was not raised")
        } catch (f: LeaseFailure) {
            assertEquals("refused", f.kind)
        }
        assertTrue("the Core removed the models", removed >= 1)
        val st = l.core()!!.tick()
        assertFalse(st.hasLease)
        assertTrue(st.stopped)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theAnswersPieceGoesToTheCoreRightAfterTheAccept() {
        // spec fix 16 (Core d128af8): the answer's piece is handed to qlc_lease_piece right after qlc_lease_accept,
        // always; the Core keeps it pending until a model opens (the vectors' build opens none: every ask stays under no
        // piece) and the SDK keeps nothing; a 429 and a 503 never reach the Core. CoreFlowTest replays piece_order.json
        // on the real model
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val named = ArrayList<Long>()
        api.override = { path, body, _ ->
            if (path != "/v1/model-key") null else {
                named += ((body!!["core"] as Map<String, Any?>)["piece"] as Number).toLong()
                api.leasePiece = ProtectedKit.pieces.getValue("piece7")
                when (named.size) {
                    3 -> api.reply(429, mapOf("error" to "rate_limited", "message" to "slow down"))
                    4 -> api.reply(503, mapOf("error" to "not_configured", "message" to "try later"))
                    else -> null
                }
            }
        }
        val l = client(File(dir, "core"), api)
        val pb = protectedBuild(dir, b, l)
        pb.core()
        assertFalse("the SDK keeps no piece of its own", File(dir, "core/lease.json").readText().contains("piece"))
        l.request(pb.target, pb::roots)
        for (st in listOf(429, 503)) {
            try {
                l.request(pb.target, pb::roots)
                fail("$st was not an error")
            } catch (f: LeaseFailure) {
                assertEquals("$st: tried again later (offline, with backoff), never a refusal", "offline", f.kind)
            }
        }
        assertEquals("no model opened: the pending piece never came into force", listOf(0L, 0L, 0L, 0L), named)
        assertTrue(l.core()!!.tick().valid)
    }

    @Test
    fun theBuildsFolderIsOnlyQuranLabsOwn() {
        // 18.4.5, as the Apple and C SDKs take it: https, quranlab.ai or a subdomain, a path ending in /, no port, user,
        // query, fragment or ..
        for (ok in listOf("https://cdn.quranlab.ai/models/Quran-Lab/tajweedformer-v4/${"a".repeat(40)}/sdk/v4.0/", "https://quranlab.ai/m/",
            "https://CDN.QuranLab.ai/x/")) assertTrue(ok, LeaseClient.baseOk(ok))
        for (bad in listOf("http://cdn.quranlab.ai/m/", "https://cdn.quranlab.ai/m", "https://evil.example/m/", "https://quranlab.ai.evil.example/m/",
            "https://evilquranlab.ai/m/", "https://cdn.quranlab.ai:8443/m/", "https://u@cdn.quranlab.ai/m/", "https://cdn.quranlab.ai/m/?x=1/",
            "https://cdn.quranlab.ai/m/#f/", "https://cdn.quranlab.ai/a/../b/", "https://cdn.quranlab.ai\\@evil.example/", "https:///m/", "https://.quranlab.ai/"))
            assertFalse(bad, LeaseClient.baseOk(bad))
        // a lease answer whose folder is not Quran Lab's: the build's manifest is asked for with no folder, and with none
        // on the device the request fails as an invalid answer; no file is fetched from another host
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val l = client(File(dir, "core"), api)
        val pb = protectedBuild(dir, b, l)
        pb.core()
        val seen = ArrayList<String?>()
        val t = ai.quranlab.tajweedformer.core.LeaseTarget(pb.target.model, pb.target.version, pb.target.build, pb.target.keyId) { base ->
            seen += base
            base ?: throw RecognizerException.DownloadFailed("manifest.json", "the lease answer names no valid folder for the build")
            b.bytes
        }
        api.leaseBase = { build -> "https://evil.example/models/$build/" }
        try {
            l.request(t, pb::roots)
            fail("an answer without a valid folder was taken")
        } catch (f: LeaseFailure) {
            assertEquals("download_failed", f.error.code)
        }
        api.leaseBase = { build -> "https://cdn.quranlab.ai/models/x/y/$build/sdk/v4.0/" }
        l.request(t, pb::roots)
        assertEquals(listOf(null, "https://cdn.quranlab.ai/models/x/y/${b.manifest.build}/sdk/v4.0/"), seen)
        assertEquals("https://cdn.quranlab.ai/models/x/y/${b.manifest.build}/sdk/v4.0/", l.record!!.base)
    }

    @Test
    fun anUnsignedOrForeignStopChangesNothingInTheCore() {
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        var removed = 0
        val l = client(File(dir, "core"), api, removed = { removed++; true })
        val pb = protectedBuild(dir, b, l)
        pb.core()
        // a stop signed for ANOTHER challenge (a replay): the Core keeps its lease, whatever the SDK's 3.x rules do
        api.override = { path, body, _ ->
            if (path != "/v1/model-key") null else {
                val ih = hex(sha256(unb64u(body!!["install_key"] as String)))
                api.reply(401, mapOf("error" to "invalid_key", "reason" to "key_revoked",
                    "signed" to ProtectedKit.signedRefusal(keyId, "key_revoked", ih, "c1.1790000300.${"A".repeat(22)}.${"B".repeat(22)}", System.currentTimeMillis() / 1000)))
            }
        }
        try { l.request(pb.target, pb::roots) } catch (_: LeaseFailure) {}
        assertEquals("the Core removed nothing", 0, removed)
        assertTrue(l.core()!!.tick().hasLease)
    }

    @Test
    fun eachRefusalGetsItsOneRepeat() {
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val l = client(File(dir, "core"), api)
        val pb = protectedBuild(dir, b, l)
        pb.core()
        val firstKey = l.core()!!.key.spki
        // 401 install_cert_invalid: attest again (a NEW agreement key, the last certificate as previous_cert), repeat once
        api.override = { path, _, n -> if (path == "/v1/model-key" && n == 2) api.reply(401, mapOf("error" to "install_cert_invalid", "why" to "expired")) else null }
        l.request(pb.target, pb::roots)
        val attests = api.seen.filter { it.first == "/v1/attest" }
        assertEquals(2, attests.size)
        assertTrue("the re-attest carries the last certificate", attests[1].second!!.containsKey("previous_cert"))
        assertFalse("a new agreement key", l.core()!!.key.spki.contentEquals(firstKey))
        // 401 challenge_invalid: a new challenge, repeat once; twice is the error
        api.seen.clear()
        api.override = { path, _, n -> if (path == "/v1/model-key" && n == 1) api.reply(401, mapOf("error" to "challenge_invalid")) else null }
        l.request(pb.target, pb::roots)
        assertEquals(2, api.seen.count { it.first == "/v1/model-key" })
        // the answers that are not stops: their errors, no repeat
        fun code(status: Int, body: Map<String, Any?>): String {
            api.seen.clear()
            api.override = { path, _, _ -> if (path == "/v1/model-key") api.reply(status, body) else null }
            return try { l.request(pb.target, pb::roots); "ok" } catch (f: LeaseFailure) { f.error.code }
        }
        assertEquals("model_unavailable", code(404, mapOf("error" to "not_found")))
        assertEquals("model_unavailable", code(404, mapOf("error" to "build_unknown")))
        assertEquals("sdk_too_old", code(403, mapOf("error" to "sdk_too_old", "min_sdk" to "4.0.1")))
        assertEquals("server_rejected", code(403, mapOf("error" to "lease_integrity_failed", "why" to "core")))
        assertEquals("server_rejected", code(403, mapOf("error" to "lease_attestation_required")))
        assertEquals("download_failed", code(429, mapOf("error" to "rate_limited")))
        assertEquals("download_failed", code(503, mapOf("error" to "not_configured")))
        assertEquals(1, api.seen.count { it.first == "/v1/model-key" })
    }

    @Test
    fun theCoresClockEndsALeaseAndNothingOpensAfterIt() {
        val dir = temp()
        val b = build(dir)
        val clock = Clock()
        val api = Api(now = { clock.wallMs / 1000 }).also { it.bytes = b.bytes }
        val l = client(File(dir, "core"), api, clock)
        val pb = protectedBuild(dir, b, l)
        val c = pb.core()
        assertTrue(c.tick().valid)
        // the wall clock moved back a year on the same boot: nothing changes (the Core runs on its anchor and the boot clock)
        clock.wallMs -= 365L * 86_400_000
        assertTrue("a clock moved back never ends a lease", c.tick().valid)
        // a day and a minute of boot clock: the 24-hour lease is over, whatever the wall clock says
        clock.bootMs += 86_460_000L
        val st = c.tick()
        assertFalse(st.valid)
        try {
            c.modelOpen(b.bytes, "c16_l64", 1, 16_000, -1)
            fail("an expired lease opened a model")
        } catch (e: ai.quranlab.tajweedformer.core.CoreException) {
            assertEquals(Core.E_NEEDS_CONNECTION, e.status)
            assertEquals("needs_connection", e.toError().code)
        }
    }

    @Test
    fun aLeaseWrappedForAnotherInstallIsRefusedByTheCore() {
        val dir = temp()
        val b = build(dir)
        val api = Api().also { it.bytes = b.bytes }
        val other = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        // the server wraps the lease for another key: the Core refuses it (the install hash), nothing is installed
        api.override = { path, body, _ ->
            if (path != "/v1/model-key") null else {
                val iat = System.currentTimeMillis() / 1000 - 60
                val lease = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = 0, iat = iat, exp = iat + 86_400, refreshAfter = iat + 43_200,
                    keyId = hex(b.manifest.encryption!!.keyId), build = hex(body!!["build"] as String), installHash = sha256(other.public.encoded)), other.public.encoded)
                api.reply(200, mapOf("v" to 1, "lease" to b64u(lease), "iat" to iat, "exp" to iat + 86_400, "refresh_after" to iat + 43_200))
            }
        }
        val l = client(File(dir, "core"), api)
        try {
            protectedBuild(dir, b, l).core()
            fail("a lease for another install was accepted")
        } catch (e: TajweedError) {
            assertEquals("download_failed", e.code)
        }
        assertFalse(l.core()!!.tick().hasLease)
    }
}
