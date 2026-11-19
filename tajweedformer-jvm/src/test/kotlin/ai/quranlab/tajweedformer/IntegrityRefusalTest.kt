package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.cloud.ErrorReporter
import ai.quranlab.tajweedformer.cloud.ErrorRules
import ai.quranlab.tajweedformer.cloud.HttpRequest
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.core.FileStateVault
import ai.quranlab.tajweedformer.core.LeaseClient
import ai.quranlab.tajweedformer.core.LeaseFailure
import ai.quranlab.tajweedformer.core.SoftwareIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage B (11-18, a device lab: re-signed APKs and bootloader-unlocked Pixels were refused as
 * `lease_integrity_failed:verdict`, which named no check): a `403 lease_integrity_failed` names the check that failed,
 * from the server's `why` and `check` (PROTECTED_MODEL.md 18.4.10; 18.4.8's checks software, boot, origin, purpose,
 * package, digest, and `recorded`; Play Integrity's failures; `downgrade`; `core`). The app gets the same code as before
 * (`server_rejected`, no new code), whose message says the check in words and with the server's tokens; the error
 * report keeps its signature (stage, reason `integrity_failed`, code, 403) and its `error_code` says `why:check`
 * (24.2: a token of at most 32). A `why` or `check` that is not a token is left out, never echoed.
 */
@Suppress("UNCHECKED_CAST")
class IntegrityRefusalTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-integrity-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    @Test
    fun theDetailNamesTheCheckInWordsAndTokens() {
        val boot = LeaseClient.integrityDetail("verdict", "boot")
        assertTrue(boot, boot.contains("bootloader is unlocked"))
        assertTrue(boot, boot.endsWith("(lease_integrity_failed, why verdict, check boot)"))
        val digest = LeaseClient.integrityDetail("verdict", "digest")
        assertTrue(digest, digest.contains("signing certificate") && digest.contains("development key"))
        assertTrue(LeaseClient.integrityDetail("verdict", "software").contains("secure hardware"))
        assertTrue(LeaseClient.integrityDetail("verdict", "origin").contains("secure hardware"))
        assertTrue(LeaseClient.integrityDetail("verdict", "purpose").contains("purpose"))
        assertTrue(LeaseClient.integrityDetail("verdict", "package").contains("package name"))
        assertTrue(LeaseClient.integrityDetail("verdict", "package_mismatch").contains("package name"))
        assertTrue(LeaseClient.integrityDetail("verdict", "recorded").contains("last 24 hours"))
        assertTrue(LeaseClient.integrityDetail("verdict", "device_integrity").contains("Play Integrity"))
        assertTrue(LeaseClient.integrityDetail("verdict", "app_unrecognized_version").contains("Google Play delivers"))
        assertTrue(LeaseClient.integrityDetail("verdict", "unlicensed").contains("not licensed"))
        assertTrue(LeaseClient.integrityDetail("downgrade", null).contains("stronger check before"))
        assertTrue(LeaseClient.integrityDetail("core", null).contains("Core"))
        assertEquals("the install's attestation did not pass (lease_integrity_failed, why verdict, check verdict_stale)",
            LeaseClient.integrityDetail("verdict", "verdict_stale"))
        assertEquals("the install could not be verified (lease_integrity_failed)", LeaseClient.integrityDetail(null, null))
        // not a token: left out of the message, never echoed
        val odd = LeaseClient.integrityDetail("verdict", "<b>Boot</b> loader")
        assertFalse(odd, odd.contains("<b>") || odd.contains("loader"))
        assertTrue(odd, odd.endsWith("(lease_integrity_failed, why verdict)"))
    }

    @Test
    fun theReportsErrorCodeIsWhyAndCheck() {
        assertEquals("verdict:boot", LeaseClient.integrityErrorCode("verdict", "boot"))
        assertEquals("verdict:digest", LeaseClient.integrityErrorCode("verdict", "digest"))
        assertEquals("verdict:recorded", LeaseClient.integrityErrorCode("verdict", "recorded"))
        assertEquals("verdict:app_unrecognized_version", LeaseClient.integrityErrorCode("verdict", "app_unrecognized_version"))
        assertEquals("downgrade", LeaseClient.integrityErrorCode("downgrade", null))
        assertEquals("core", LeaseClient.integrityErrorCode("core", null))
        assertEquals("verdict", LeaseClient.integrityErrorCode("verdict", "<b>Boot</b>"))
        // longer than 24.2's 32: the why alone
        assertEquals("verdict", LeaseClient.integrityErrorCode("verdict", "a_check_name_far_too_long_for_it"))
        assertNull(LeaseClient.integrityErrorCode(null, "boot"))
        for (c in listOf("software", "boot", "origin", "purpose", "package", "digest", "recorded", "device_integrity", "package_mismatch",
                "request_hash_mismatch", "verdict_stale", "verdict_incomplete", "unlicensed", "app_unrecognized_version")) {
            val code = LeaseClient.integrityErrorCode("verdict", c)!!
            assertTrue(code, ErrorRules.ERROR_CODE.matches(code))
        }
    }

    /** POST /v1/attest refused with [body] (18.4.8: the verdict is decided at attest), through the lease client. */
    private fun attestRefused(body: Map<String, Any?>): TajweedError {
        val dir = temp()
        val api = QuranLabApi("http://stub", { r: HttpRequest ->
            when {
                r.method == "GET" && r.url.endsWith("/v1/attest/challenge") ->
                    HttpResponse(200, emptyMap(), Json.write(mapOf("challenge" to "c1.1800000300.${"A".repeat(22)}.${"B".repeat(22)}")).toByteArray())
                r.method == "POST" && r.url.endsWith("/v1/attest") ->
                    HttpResponse(403, mapOf("content-type" to "application/json"), Json.write(body).toByteArray())
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        })
        val l = LeaseClient(File(dir, "core"), SoftwareIdentity(File(dir, "core/keys")), FileStateVault(File(dir, "core/vault")), api,
            QuranLabKey.parse("qlk_v1_apps-one_" + "a".repeat(22))!!, AppIdentity("org.example.recite", "1.0"), token = { "t" },
            invalidateToken = {}, adoptToken = {}, integrity = null, projectNumber = { null }, removeModels = { true })
        try {
            l.attest()
            fail("the stub refused")
            throw AssertionError()
        } catch (f: LeaseFailure) {
            return f.error
        } finally {
            l.close()
        }
    }

    @Test
    fun anUnlockedBootloaderAtAttestIsNamedToTheAppAndInTheReport() {
        val e = attestRefused(mapOf("error" to "lease_integrity_failed", "message" to "the install could not be verified", "why" to "verdict", "check" to "boot"))
        // the code is the one it always was: no new code (24.4)
        assertEquals("server_rejected", e.code)
        assertTrue(e.message, e.message.startsWith("Quran Lab refused this app: this phone's bootloader is unlocked"))
        assertTrue(e.message, e.message.contains("(lease_integrity_failed, why verdict, check boot)"))
        // the report: the same signature as before (attest, integrity_failed, server_rejected, 403), the check in error_code
        val c = ErrorCause.of(e)
        assertEquals(listOf("attest", "integrity_failed", 403, "verdict:boot"), listOf(c.stage, c.reason, c.httpStatus, c.errorCode))
        val reports = ErrorReporter(null)
        assertTrue(reports.occur("2026-11-18", c.stage, c.reason, e.code, c.httpStatus, false, mapOf("error_code" to c.errorCode)))
        val r = reports.queued().single()
        assertEquals(ErrorRules.signatureHex("attest", "integrity_failed", "server_rejected", 403), r.sig)
        val b = reports.body(r, AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel8", "tensor_g3", 34, "14"), SdkInfo.VERSION)
        val err = b["error"] as Map<String, Any?>
        assertEquals("verdict:boot", err["error_code"])
        assertEquals("integrity_failed", err["reason"])
        // never a message in a report (24.2)
        assertFalse(Json.write(b).contains("bootloader"))
    }

    @Test
    fun aReSignedApkIsNamedAsTheSigningDigest() {
        val e = attestRefused(mapOf("error" to "lease_integrity_failed", "why" to "verdict", "check" to "digest"))
        assertEquals("server_rejected", e.code)
        assertTrue(e.message, e.message.contains("signing certificate is not one declared for this app"))
        assertEquals("verdict:digest", ErrorCause.of(e).errorCode)
    }

    @Test
    fun aRefusalWithoutACheckStillSaysWhatItCan() {
        val e = attestRefused(mapOf("error" to "lease_integrity_failed", "why" to "verdict"))
        assertEquals("server_rejected", e.code)
        assertTrue(e.message, e.message.contains("attestation did not pass (lease_integrity_failed, why verdict)"))
        assertEquals("verdict", ErrorCause.of(e).errorCode)
        // no why at all: the server's error string, as before
        val bare = attestRefused(mapOf("error" to "lease_integrity_failed"))
        assertEquals("lease_integrity_failed", ErrorCause.of(bare).errorCode)
        assertTrue(bare.message, bare.message.contains("(lease_integrity_failed)"))
        // another refusal is as it was
        val other = attestRefused(mapOf("error" to "app_blocked"))
        assertEquals("server_rejected", other.code)
        assertEquals("app_blocked", ErrorCause.of(other).errorCode)
    }
}
