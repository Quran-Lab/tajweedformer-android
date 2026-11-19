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
import ai.quranlab.tajweedformer.core.AgreementKey
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.InstallCert
import ai.quranlab.tajweedformer.core.InstallIdentity
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The SDK's side of the contract's SDK 4.0.0 vectors (sdk_4.0.0/vectors, branch sdk-4.0.0-protected 7ee6ff4), with no
 * Core: the manifest's `encryption` line and `qle1` entries (manifest_40.json), the plain rule, the attest body an
 * Android SDK sends (keyattest.json: the install keys, the chain, `install_sig`), Play's requestHash for 4.0, the
 * install certificate's fields (install_cert.json), the signed refusal's text (refusal_signed.json), and the test kit
 * itself: its lease is byte for byte the contract's (lease.json), so the Core tests that use it test the real format.
 */
class Protected40VectorsTest : ai.quranlab.tajweedformer.OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-40-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    @Test
    fun theVectorsAreTheContracts() {
        // every copy byte for byte the contract's file, when the contract is on this machine (TJ_VECTORS40_DIR =
        // sdk_4.0.0/vectors at the pinned commit 7ee6ff4; files the contract adds later are taken in with their code)
        val dir = System.getProperty("TJ_VECTORS40_DIR") ?: System.getenv("TJ_VECTORS40_DIR") ?: return
        val mine = File(javaClass.getResource("/vectors40/manifest_40.json")!!.toURI()).parentFile
        val copies = mine.listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue(copies.size >= 10)
        for (f in copies) {
            val theirs = File(dir, f.name)
            assertTrue("${f.name} is in the contract", theirs.isFile)
            assertArrayEquals(f.name, theirs.readBytes(), f.readBytes())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun manifest40(): Map<String, Any?> = ProtectedKit.vector("manifest_40.json")["manifest"] as Map<String, Any?>

    private val verifier = ManifestVerifier(listOf(ProtectedKit.signerPublic), sdkVersion = "4.0.0")

    @Test
    fun theEncryptedManifestsSignedTextAndSignature() {
        val v = ProtectedKit.vector("manifest_40.json")
        val m = ModelManifest.parse(Json.write(manifest40()).toByteArray())
        assertEquals("qle1", m.encryption!!.scheme)
        assertEquals("d14d9aeeeecb3a238cb86c41a5e8b0c2", m.encryption!!.keyId)
        assertEquals(v["signed_text"], String(m.signedMessage, Charsets.UTF_8))
        verifier.verify(m)
        // a qle1 entry is kept as its container, by its decoded path
        val layers = m.file("layers.zfw")!!
        assertEquals(ModelManifest.Encoding.QLE1, layers.encoding)
        assertEquals("layers.zfw.qle", layers.path)
        assertEquals(layers.sha256.lowercase(), layers.localSha256)
        assertEquals(layers.decoded!!.sha256.lowercase(), layers.plainSha256)
        // the file index (18.2.5): the qle1 entries sorted by the UTF-8 bytes of their decoded path
        @Suppress("UNCHECKED_CAST")
        val index = v["file_index"] as Map<String, Any?>
        val sorted = m.files.filter { it.encoding == ModelManifest.Encoding.QLE1 }.map { it.localPath }.sortedWith { a, b -> ModelManifest.compareUtf8(a, b) }
        for ((p, i) in index) assertEquals(p, (i as Double).toInt(), sorted.indexOf(p))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aChangedFieldIsRefused() {
        for (r in ProtectedKit.vector("manifest_40.json")["refuse"] as List<Map<String, Any?>>) {
            val o = Json.parse(Json.write(manifest40())) as MutableMap<String, Any?>
            val path = r["set"] as List<Any?>
            var at: Any? = o
            for (k in path.dropLast(1)) at = if (k is Double) (at as List<Any?>)[k.toInt()] else (at as Map<String, Any?>)[k as String]
            val last = path.last()
            if (last is Double) (at as MutableList<Any?>)[last.toInt()] = r["value"] else (at as MutableMap<String, Any?>)[last as String] = r["value"]
            try {
                verifier.verify(ModelManifest.parse(Json.write(o).toByteArray()))
                fail("${r["set"]} = ${r["value"]} was accepted")
            } catch (e: TajweedError) {
                assertTrue("${r["set"]}: ${e.code}", e.code in setOf("signature_invalid", "model_invalid", "model_incompatible"))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun thePlainRule() {
        val rule = ProtectedKit.vector("manifest_40.json")["plain_rule"] as Map<String, Any?>
        for (c in rule["cases"] as List<Map<String, Any?>>) {
            assertEquals("${c["role"]} ${c["decoded_path"]}", c["plain"], ManifestVerifier.plainAllowed(c["role"] as String, c["decoded_path"] as String))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theKitsLeaseIsTheContracts() {
        val v = ProtectedKit.vector("lease.json")
        val ctx = v["core_context"] as Map<String, Any?>
        val a = (v["accept"] as List<Map<String, Any?>>).first { it["wrap"] == "1" || it["wrap"] == 1.0 }
        val fields = a["fields"] as Map<String, Any?>
        fun num(k: String) = (fields[k] as Double).toLong()
        fun str(k: String) = fields[k] as String
        val eph = ProtectedKit.ecPrivate(unb64u(a["eph_pkcs8_b64u"] as String))
        val install = (v["install_ecdh"] as Map<String, Any?>)["spki_b64u"] as String
        val lease = ProtectedKit.lease(
            ProtectedKit.LeaseFields(flags = num("flags").toInt(), leaseId = hex(str("lease_id_hex")), iat = num("iat"), exp = num("exp"),
                refreshAfter = num("refresh_after"), keyId = hex(ctx["key_id_hex"] as String), build = hex(ctx["build"] as String),
                installHash = hex(str("install_hash_hex")), mark = hex(str("mark_hex"))),
            unb64u(install), buildKey = hex(a["build_key_hex"] as String), ephPrivate = eph, ephPublic = hex(a["eph_hex"] as String))
        assertEquals(a["lease_hex"], ProtectedKit.hex(lease))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theInstallCertificatesFields() {
        val v = ProtectedKit.vector("install_cert.json")
        for (c in v["cases"] as List<Map<String, Any?>>) {
            val cert = InstallCert.parse(c["cert"] as String)!!
            val text = c["text"] as String
            fun line(k: String) = text.lines().firstOrNull { it.startsWith("$k ") }?.substringAfter(' ')
            assertEquals(c["name"] as String, line("att"), cert.att)
            assertEquals(line("exp")!!.toLong(), cert.exp)
            assertEquals(line("recheck") == "1", cert.recheck)
            assertEquals(line("install"), cert.install)
        }
        // the kit signs as the server does: the valid case again, byte for byte (Ed25519 is deterministic)
        val valid = (v["cases"] as List<Map<String, Any?>>).first { it["name"] == "valid" }
        val text = valid["text"] as String
        val signed = b64u(text.toByteArray()) + "." + b64u(ProtectedKit.Ed.sign(ProtectedKit.signerSeed, text.toByteArray()))
        assertEquals(valid["cert"], signed)
        assertNull(InstallCert.parse("not.a-cert"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theSignedRefusalsText() {
        val v = ProtectedKit.vector("refusal_signed.json")
        val ctx = v["context"] as Map<String, Any?>
        val c = (v["cases"] as List<Map<String, Any?>>).first { it["name"] == "a signed stop (key_revoked)" }
        val body = Json.parse(c["body"] as? String ?: Json.write(c["body"])) as Map<String, Any?>
        val made = ProtectedKit.signedRefusal(ctx["key"] as String, body["reason"] as String, ctx["install_hash_hex"] as String,
            ctx["challenge"] as String, String(unb64u((body["signed"] as String).substringBefore('.'))).lines()[5].toLong())
        assertEquals(body["signed"], made)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun playsRequestHashFor40() {
        val p = ProtectedKit.vector("keyattest.json")["play"] as Map<String, Any?>
        assertEquals(p["request_hash"], LeaseClient.requestHash(p["challenge"] as String, p["install_hash_hex"] as String))
    }

    /** An install key store that holds the vector's keys. */
    private class Vectors(private val agree: ByteArray, private val chain: List<ByteArray>, signPkcs8: ByteArray,
                          private val signSpki: ByteArray, private val hw: Boolean) : InstallIdentity {
        private val sign = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(signPkcs8))
        var committed = false
        var discarded = false
        override fun current(): AgreementKey? = null
        override fun signingSpki(): ByteArray = signSpki
        override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(sign); update(message); sign() }
        override fun prepare(challenge: String): AgreementKey = AgreementKey("v", agree, false, chain, hw)
        override fun commit(k: AgreementKey) { committed = true }
        override fun discard(k: AgreementKey) { discarded = true }
        override fun agree(k: AgreementKey, eph: ByteArray): ByteArray = throw UnsupportedOperationException()
        override fun decryptRsa(k: AgreementKey, c: ByteArray): ByteArray = throw UnsupportedOperationException()
        override val signals: Int get() = 0
        override fun reset() {}
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theAttestBodyAnAndroidSdkSends() {
        val v = ProtectedKit.vector("keyattest.json")
        var n = 0
        for (c in v["cases"] as List<Map<String, Any?>>) {
            val body = c["attest_body"] as Map<String, Any?>
            val chain = (body["key_attestation"] as? List<String>)?.map { unb64u(it) } ?: emptyList()
            val id = Vectors(unb64u(body["install_key"] as String), chain, unb64u(c["install_sign_pkcs8_b64u"] as String),
                unb64u(body["install_sign_key"] as String), body["install_hw"] == 1.0)
            var sent: Map<String, Any?>? = null
            val api = QuranLabApi("http://stub", { r: HttpRequest ->
                when {
                    r.method == "GET" && r.url.endsWith("/v1/attest/challenge") ->
                        HttpResponse(200, emptyMap(), Json.write(mapOf("challenge" to body["challenge"])).toByteArray())
                    r.method == "POST" && r.url.endsWith("/v1/attest") -> {
                        sent = Json.parse(String(r.body!!)) as Map<String, Any?>
                        HttpResponse(403, emptyMap(), Json.write(mapOf("error" to "lease_integrity_failed", "why" to "verdict")).toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            })
            val dir = temp()
            val l = LeaseClient(dir, id, FileStateVault(dir), api, QuranLabKey.parse("qlk_v1_apps-one_" + "a".repeat(22))!!,
                AppIdentity(v["package"] as String, "1.0"), token = { "t" }, invalidateToken = {}, adoptToken = {}, integrity = null,
                projectNumber = { null }, removeModels = { true })
            try {
                l.attest()
                fail("the stub refused")
            } catch (_: LeaseFailure) {
            }
            val s = sent!!
            for (k in listOf("platform", "bundle_id", "challenge", "install_key", "install_sign_key", "install_hw", "key_attestation")) {
                assertEquals("${c["name"]}: $k", body[k], s[k])
            }
            // install_sig: ECDSA by the signing key over the attest text (deterministic in the vector, random here)
            val installHex = ProtectedKit.hex(sha256(unb64u(s["install_key"] as String)))
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64u(s["install_sign_key"] as String))))
                update(LeaseClient.attestText(s["challenge"] as String, installHex))
                verify(unb64u(s["install_sig"] as String))
            }
            assertTrue("${c["name"]}: install_sig", ok)
            // nothing else rides along: no previous certificate (a first attest) and no Play token (no provider)
            assertFalse(s.containsKey("previous_cert"))
            assertFalse(s.containsKey("integrity_token"))
            // a refused attest deletes the new key and keeps none
            assertTrue(id.discarded)
            assertFalse(id.committed)
            n++
        }
        assertTrue(n >= 40)
    }
}
