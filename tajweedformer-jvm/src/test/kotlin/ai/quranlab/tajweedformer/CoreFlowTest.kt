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
import ai.quranlab.tajweedformer.core.CoreException
import ai.quranlab.tajweedformer.core.CoreLibrary
import ai.quranlab.tajweedformer.core.CoreTajweedEngine
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.Golden
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.ProtectedBuild
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * The whole desktop flow of SDK 4.0 on the REAL model, through the SDK's own classes and a host build of the Core
 * (scripts/build_host_core.sh): a protected test build of V4 made by the Core's `tools/make_test_build.py` (the
 * contract's test roots; TJ_CORE_TEST_BUILD = its folder), a fake API that attests the software install key and leases
 * the build to it as qlab-api would (the test kit), every container installed by the Core into its device form, the
 * model opened (the device forms decrypted from the bridge's file mapping), and then what 4.0 takes up of Core 4a5663c:
 *
 * - the vocabulary from the open model (R4, `qlc_model_token_name`): the 38 names of V4's tokens.txt;
 * - the reference data (R3, `qlc_reference_expected`): an ayah's units and its words, read at the first lookup;
 * - a take on the CPU stream: chunks, every head, the greedy units of a real clip (TJ_CORE_CLIP, 16 kHz float32;
 *   silence without it);
 * - the accelerator (R2): `qlc_accel_allowed` from the lease's flags, and `qlc_accel_prepare` of a build with no
 *   program refused with QLC_D_ACCEL_FILES, the CPU stream untouched after;
 * - no plaintext of any encrypted file anywhere on disk.
 * Skipped without the host Core or the test build.
 */
class CoreFlowTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-flow-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val tb: File? = (System.getProperty("TJ_CORE_TEST_BUILD") ?: System.getenv("TJ_CORE_TEST_BUILD"))?.let(::File)
        ?.takeIf { File(it, "cdn/sdk/v4.0/manifest.json").isFile }
    private val keyId = "apps-one"
    private val key = QuranLabKey.parse("qlk_v1_${keyId}_" + "a".repeat(22))!!
    private val app = AppIdentity("org.example.recite", "1.0")

    /** The fake API: attest with a certificate for the install, lease the build with [leaseFlags] (8: accelerator allowed). */
    @Suppress("UNCHECKED_CAST")
    private fun api(manifest: ModelManifest, leaseFlags: Int, pieces: ((Int, Long) -> Pair<Boolean, ByteArray?>)? = null): QuranLabApi {
        var asked = 0
        return api(manifest, leaseFlags) { piece -> asked++; pieces?.invoke(asked, piece) ?: (false to null) }
    }

    private fun api(manifest: ModelManifest, leaseFlags: Int, lease: (Long) -> Pair<Boolean, ByteArray?>) =
        QuranLabApi("http://stub", transport(manifest, leaseFlags, lease))

    /** [lease]: given the `core.piece` a request names, whether to refuse its proof, and the piece its answer hands. */
    @Suppress("UNCHECKED_CAST")
    private fun transport(manifest: ModelManifest, leaseFlags: Int, lease: (Long) -> Pair<Boolean, ByteArray?>): (HttpRequest) -> HttpResponse = { r: HttpRequest ->
        val path = r.url.substringAfter("http://stub").substringBefore('?')
        val b = r.body?.let { Json.parse(String(it)) as Map<String, Any?> }
        fun reply(status: Int, o: Any?) = HttpResponse(status, mapOf("content-type" to "application/json"), Json.write(o).toByteArray())
        val now = System.currentTimeMillis() / 1000
        when (path) {
            "/v1/attest/challenge" -> reply(200, mapOf("challenge" to "c1.${now + 300}.${"A".repeat(22)}.${"B".repeat(22)}", "expires_in" to 300))
            "/v1/attest" -> {
                val ik = unb64u(b!!["install_key"] as String)
                reply(200, mapOf("token" to "qlt_v1.e30.s", "expires_in" to 86_400, "attested" to false,
                    "install_cert" to ProtectedKit.cert("android:${app.bundleId}", "android", keyId, hex(sha256(ik)),
                        hex(sha256(unb64u(b["install_sign_key"] as String))), att = "none", hw = 0, iat = now, exp = now + 86_400, recheck = 0)))
            }
            "/v1/model-key" -> {
                val ik = unb64u(b!!["install_key"] as String)
                val (refuse, piece) = lease(((b["core"] as Map<String, Any?>)["piece"] as Number).toLong())
                if (refuse) reply(403, mapOf("error" to "lease_integrity_failed", "message" to "the Core could not be verified", "why" to "core")) else {
                    val kid = hex(manifest.encryption!!.keyId)
                    val bytes = ProtectedKit.lease(ProtectedKit.LeaseFields(flags = leaseFlags, iat = now - 60, exp = now + 86_340, refreshAfter = now + 43_140,
                        keyId = kid, build = hex(b["build"] as String), installHash = sha256(ik), model = b["model"] as String, version = b["version"] as String),
                        ik, buildKey = ProtectedKit.buildKeyFor(kid, b["model"] as String, b["version"] as String))
                    reply(200, linkedMapOf("v" to 1, "lease" to b64u(bytes), "iat" to now - 60, "exp" to now + 86_340, "refresh_after" to now + 43_140,
                        "base" to "https://cdn.quranlab.ai/models/x/y/${b["build"]}/sdk/v4.0/", "install_cert" to b["install_cert"]).also { o ->
                        piece?.let { o["piece"] = b64u(it) }
                    })
                }
            }
            else -> reply(404, mapOf("error" to "not_found"))
        }
    }

    /** The test build as the downloader keeps it: the plain files beside the manifest, the CDN forms under cdn/. */
    private fun place(dir: File): ModelManifest {
        val src = File(tb!!, "cdn/sdk/v4.0")
        val bytes = File(src, "manifest.json").readBytes()
        val m = ModelManifest.parse(bytes)
        File(dir, "manifest.json").writeBytes(bytes)
        for (e in m.files) {
            val from = File(src, e.path)
            if (!from.isFile) continue
            val to = if (e.encoding == ModelManifest.Encoding.QLE1) File(dir, "cdn/${e.localPath}") else File(dir, e.path)
            to.parentFile.mkdirs()
            from.copyTo(to, overwrite = true)
        }
        return m
    }

    private fun clip(): FloatArray {
        val f = (System.getProperty("TJ_CORE_CLIP") ?: System.getenv("TJ_CORE_CLIP"))?.let(::File)?.takeIf { it.isFile }
            ?: return FloatArray(3 * 16_000)
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun theWholeFlowOnTheRealModel() {
        assumeTrue("the host Core is not built (scripts/build_host_core.sh): ${CoreLibrary.loadError}", CoreLibrary.available)
        assumeTrue("TJ_CORE_TEST_BUILD names no test build of the Core's tools/make_test_build.py", tb != null)
        val dir = temp()
        val buildDir = File(dir, "models/v4.0/build")
        buildDir.mkdirs()
        val m = place(buildDir)
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            api(m, leaseFlags = 8), key, app, token = { "qlt_v1.e30.s" }, invalidateToken = {}, adoptToken = {}, integrity = null,
            projectNumber = { null }, removeModels = { true })
        try {
            val pb = ProtectedBuild(buildDir, m, File(buildDir, "manifest.json").readBytes(), l)
            val c = pb.core()
            assertTrue("a lease in force", c.tick().valid)
            assertTrue("the lease's flags allow accelerator programs", c.accelAllowed())
            // every container of the build into its device form
            val t0 = System.nanoTime()
            pb.install(m.files, { e -> File(pb.cdnDir, e.localPath).isFile }, { e -> File(pb.cdnDir, e.localPath) })
            println("install of ${m.files.count { it.encoding == ModelManifest.Encoding.QLE1 }} containers: ${(System.nanoTime() - t0) / 1_000_000} ms")
            for (e in m.files.filter { it.encoding == ModelManifest.Encoding.QLE1 }) assertTrue(e.localPath, pb.installed(e))

            val engine = CoreTajweedEngine(pb, "c16_l64")
            try {
                val model = engine.coreModel!!
                // R4: the vocabulary of the open model is V4's
                val names = model.tokenNames()
                assertEquals(38, names.size)
                assertEquals(Vocabulary.known(m.tokensSha256)!!.names, names)
                // R3: the reference data, read at the first lookup (under the lease)
                assertEquals(7, model.referenceAyahCount(1))
                val (units, starts) = model.referenceExpected(1, 1)
                assertTrue("1:1 has units", units.isNotEmpty())
                assertEquals("1:1 has four words", 4, starts.size)
                assertTrue("the word bar between words", units.count { it == 1 } >= 3)
                try {
                    model.referenceExpected(1, 8)
                    fail("an ayah the table does not have")
                } catch (x: CoreException) {
                    assertEquals(Core.E_ARG, x.status)
                }
                // R2: this build has no program for any SoC: the Core says so, and the CPU engine runs on
                try {
                    engine.prepareAccelerator("npu:tensor_g4", null)
                    fail("a program was prepared from a build without one")
                } catch (x: CoreException) {
                    assertEquals(Core.E_ENGINE, x.status)
                    assertEquals("accel_files", Core.detailName(x.status, x.detail))
                }
                // a take on the CPU stream: a real clip's units
                val pcm = clip()
                val s = engine.createStream(HeadId.entries.toSet(), accelerator = true)
                assertEquals("no program: the take runs on the CPU", Backend.CPU, s.backend)
                val chunks = ArrayList<HeadValues>()
                var at = 0
                while (at < pcm.size) {
                    val k = minOf(5120, pcm.size - at)
                    chunks += s.pushAudio(pcm, at, k)
                    at += k
                }
                chunks += s.finish()
                s.close()
                val all = HeadValues.concat(chunks)
                assertTrue("chunks", chunks.isNotEmpty())
                for (h in HeadId.entries) assertTrue("${h.key} computed", all[h] != null)
                val greedy = Golden.greedy(all[HeadId.LOG_PROBS]!!, HeadId.LOG_PROBS.width)
                println("${chunks.size} chunks, ${greedy.size} units: ${greedy.joinToString(" ") { names[it] }}")
                if ((System.getProperty("TJ_CORE_CLIP") ?: System.getenv("TJ_CORE_CLIP")) != null) assertTrue("a recitation has units", greedy.size > 10)
            } finally {
                engine.close()
            }
            // no plaintext of an encrypted file anywhere on disk (the whole folder, every file's SHA-256)
            val plain = m.files.filter { it.encoding == ModelManifest.Encoding.QLE1 }.map { it.plainSha256 }.toSet()
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                assertFalse("${f.path} holds a model file's plaintext", hex(sha256(f.readBytes())) in plain)
            }
        } finally {
            l.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun thePieceOrderSequences() {
        // spec fix 16 (Core d128af8, contract c7f6e9b): every sequence of piece_order.json through the REAL Core on the
        // real model: "accept" a lease the kit made for this install, "piece" the answer's piece right after it, "refuse"
        // a refusal that is not a stop, "busy" no call, "open" and "open_fails" real opens (the second of a context the
        // build does not ship), "close", and "restart" the Core made again from the state it saved (its model closed
        // first: a process that ends closes nothing, and an open after a restart is after every hand-over). Each ask
        // must be made under the piece the vector names, as the request's core.piece says
        assumeTrue(CoreLibrary.available)
        assumeTrue(tb != null)
        val v = ProtectedKit.vector("piece_order.json")
        for (sq in v["sequences"] as List<Map<String, Any?>>) {
            val name = sq["name"] as String
            val dir = temp()
            val buildDir = File(dir, "models/v4.0/build").apply { mkdirs() }
            val m = place(buildDir)
            val manifest = File(buildDir, "manifest.json").readBytes()
            fun client() = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
                api(m, 8), key, app, token = { "qlt_v1.e30.s" }, invalidateToken = {}, adoptToken = {}, integrity = null,
                projectNumber = { null }, removeModels = { true })
            var l = client()
            var model: ai.quranlab.tajweedformer.core.CoreModel? = null
            try {
                // the set-up: an install with a lease and its device forms (a lease asked under no piece, no piece)
                val pb = ProtectedBuild(buildDir, m, manifest, l)
                pb.core()
                pb.install(m.files, { e -> File(pb.cdnDir, e.localPath).isFile }, { e -> File(pb.cdnDir, e.localPath) })
                fun core(): Core = l.core()!!.also { pb.roots(it) }
                val now = System.currentTimeMillis() / 1000
                fun kitLease(c: Core): ByteArray {
                    val kid = hex(m.encryption!!.keyId)
                    return ProtectedKit.lease(ProtectedKit.LeaseFields(flags = 8, iat = now - 60, exp = now + 86_340, refreshAfter = now + 43_140,
                        keyId = kid, build = hex(m.build), installHash = sha256(c.key.spki), model = m.modelId, version = m.version!!),
                        c.key.spki, buildKey = ProtectedKit.buildKeyFor(kid, m.modelId, m.version!!))
                }
                var asks = 0
                for ((i, st) in (sq["steps"] as List<Map<String, Any?>>).withIndex()) {
                    val where = "$name, step $i ${Json.write(st)}"
                    when (st["op"]) {
                        "ask" -> {
                            val (json, _) = core().leaseAsk("c1.${now + 300}.${"A".repeat(22)}.${"B".repeat(22)}", m.build)
                            val piece = ((Json.parse(json) as Map<String, Any?>)["piece"] as Number).toLong()
                            assertEquals(where, (st["want"] as Double).toLong(), piece)
                            asks++
                        }
                        "accept" -> { val c = core(); c.leaseAccept(kitLease(c), manifest, m.build) }
                        "piece" -> {
                            val ok = core().leasePiece(ProtectedKit.pieces.getValue(st["name"] as String))
                            assertEquals(where, st["name"] != "piece_bad_sig", ok)
                        }
                        "refuse" -> assertFalse(where, core().refusalAccept(
                            Json.write(mapOf("error" to "lease_integrity_failed", "message" to "no", "why" to "core")).toByteArray(), keyId))
                        "busy" -> {}
                        "open" -> model = core().modelOpen(manifest, "c16_l64", 1, 16_000, 0)
                        "open_fails" -> try {
                            core().modelOpen(manifest, "c99_l99", 1, 16_000, 0).close()
                            fail("$where: the open did not fail")
                        } catch (_: CoreException) {
                        }
                        "close" -> { model?.close(); model = null }
                        "restart" -> {
                            model?.close()
                            model = null
                            l.close()
                            l = client()
                        }
                        else -> fail("$where: an op this test does not know")
                    }
                }
                assertTrue("$name: asks", asks > 0)
            } finally {
                model?.close()
                l.close()
            }
        }
    }

    @Test
    fun theRecognizersPromotionOfABuildWithoutAProgram() {
        // R2 through the SDK's own path: the recognizer opens the CPU engine (the vocabulary from the open model), then
        // promotes in the background: the Core's prepare of a build that has no program for the SoC is refused
        // (QLC_D_ACCEL_FILES), the promotion telemetry says so, the verdict keeps the build off the accelerator on this
        // device, and the takes stay on the CPU
        assumeTrue(CoreLibrary.available)
        assumeTrue(tb != null)
        val dir = temp()
        val buildDir = File(dir, "models/v4.0/build").apply { mkdirs() }
        val m = place(buildDir)
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            api(m, 8), key, app, token = { "qlt_v1.e30.s" }, invalidateToken = {}, adoptToken = {}, integrity = null,
            projectNumber = { null }, removeModels = { true })
        try {
            val pb = ProtectedBuild(buildDir, m, File(buildDir, "manifest.json").readBytes(), l)
            pb.core()
            pb.install(m.files, { e -> File(pb.cdnDir, e.localPath).isFile }, { e -> File(pb.cdnDir, e.localPath) })
            val folder = ModelFolder.protected(pb, ManifestVerifier(listOf(ProtectedKit.signerPublic), sdkVersion = "4.0.0"))
            val records = ai.quranlab.tajweedformer.core.AcceleratorRecords(File(dir, "core/accel.json"))
            val reports = java.util.concurrent.LinkedBlockingQueue<ai.quranlab.tajweedformer.core.PromotionInfo>()
            val plan = ai.quranlab.tajweedformer.core.AcceleratorPlan("tensor_g4", null, null,
                clips = { listOf("silence" to FloatArray(16_000)) }, records = records, report = { reports.add(it) })
            val rec = Recognizer.open(folder, Recognizer.Options(context = "c16_l64", accelerator = plan))
            try {
                assertEquals("the open model's vocabulary", Vocabulary.known(m.tokensSha256)!!.names, rec.model.vocabulary.names)
                val p = reports.poll(60, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("no promotion outcome in 60 s")
                assertFalse(p.promoted)
                assertEquals("compile", p.failedAt)
                assertEquals("accel_files", p.detail)
                assertTrue("the build stays off the accelerator on this device", records.blocked("${m.build}:npu:tensor_g4:c16_l64"))
                assertEquals(Backend.CPU, rec.backend)
            } finally {
                rec.close()
            }
        } finally {
            l.close()
        }
    }

    @Test
    fun aLeaseWithoutTheAcceleratorBitAllowsNoProgram() {
        assumeTrue(CoreLibrary.available)
        assumeTrue(tb != null)
        val dir = temp()
        val buildDir = File(dir, "models/v4.0/build").apply { mkdirs() }
        val m = place(buildDir)
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")),
            api(m, leaseFlags = 0), key, app, token = { "qlt_v1.e30.s" }, invalidateToken = {}, adoptToken = {}, integrity = null,
            projectNumber = { null }, removeModels = { true })
        try {
            val pb = ProtectedBuild(buildDir, m, File(buildDir, "manifest.json").readBytes(), l)
            assertFalse("flags bit 3 clear: no accelerator program (the SDK downloads none)", pb.core().accelAllowed())
        } finally {
            l.close()
        }
    }
}
