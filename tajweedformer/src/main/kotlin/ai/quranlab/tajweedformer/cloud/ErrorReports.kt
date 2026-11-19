package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedError
import java.io.File

/**
 * Where a failure's cause is, as an error report names it:
 * the stage of the CAUSE (not the call that failed), a reason token of the stage's list, the HTTP status of the answer
 * that failed, the platform error's class and code (never its message), and the attempts made inside the failing call
 * beyond the first. Made where the failure is made ([TajweedError.at]); [of] reads it back, or infers it from the error.
 */
internal data class ErrorCause(
    val stage: String,
    val reason: String,
    val httpStatus: Int? = null,
    val errorName: String? = null,
    val errorCode: String? = null,
    val retries: Int? = null,
) {
    companion object {
        /** The cause of [e]: the one tagged where it (or an error it wraps) was made, else what its code and chain say. */
        fun of(e: Throwable): ErrorCause {
            val name = ErrorRules.platformName(e)
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 8) {
                if (t is TajweedError) t.cause40?.let { c -> return if (c.errorName == null && name != null) c.copy(errorName = name) else c }
                t = t.cause
                depth++
            }
            chain(e).firstOrNull { it is CloudException }?.let { c ->
                val ce = c as CloudException
                return ofCloud(ce, ErrorRules.stageOfPath(ce.path) ?: "config")
            }
            val code = (e as? TajweedError)?.code ?: "engine_failed"
            val oom = chain(e).any { it is OutOfMemoryError }
            fun c(stage: String, reason: String, status: Int? = null, ec: String? = null) = ErrorCause(stage, reason, status, name, ec)
            return when (code) {
                "needs_connection", "network_not_permitted" -> c("config", "network")
                "download_failed" -> when (e) {
                    is RecognizerException.ModelLimit -> c("download", "rate_limited", 429, "model_limit")
                    else -> c("download", "other")
                }
                "storage_full" -> c("storage", "quota")
                "storage_failed" -> c("storage", if (ErrorRules.noSpace(e)) "quota" else "write")
                "checksum_mismatch" -> c("verify", "hash")
                "signature_invalid" -> c("verify", "signature")
                "model_invalid" -> c("verify", "invalid")
                "model_missing" -> c("verify", "missing")
                "model_incompatible" -> c("verify", "incompatible")
                "model_unavailable" -> c("config", "unavailable")
                "invalid_key", "server_rejected", "sdk_too_old" ->
                    c("config", "refused", ec = ErrorRules.token((e as? RecognizerException.ServerRejected)?.serverCode, ErrorRules.ERROR_CODE)
                        ?: code.takeIf { it != "server_rejected" })
                "runtime_unsupported" -> c("runtime", "native_library")
                "microphone_denied" -> c("audio", "permission_denied")
                "microphone_unavailable" -> c("audio", "unavailable")
                "interrupted" -> c("audio", "interrupted")
                "reference_missing" -> c("download", "other")
                "sharing_unavailable" -> c("sharing", "other")
                "engine_failed" -> c("session", if (oom) "memory" else "engine")
                else -> c("runtime", "other")
            }
        }

        /** A request of the API that did not succeed, as a cause at [stage]: no answer (`network`, `timeout`), or its answer. */
        fun ofCloud(e: CloudException, stage: String, retries: Int? = null): ErrorCause {
            val name = ErrorRules.platformName(e)
            val a = e.api ?: return ErrorCause(stage, if (ErrorRules.timedOut(e)) "timeout" else "network", null, name, retries = retries)
            return ErrorCause(stage, ErrorRules.reasonOf(a.status, a.code, a.reason), a.status.takeIf { it in 100..599 }, name,
                ErrorRules.serverCode(a), retries)
        }

        private fun chain(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(8)
    }
}

/**
 * The rules of section 24 as `sdk_4.0.0/tools/errors40.py` writes them (its vectors are replayed by the tests): the code
 * the app gets for a cause (24.4), whether a failure is reported (24.1), the signature (24.5), the telemetry name of a
 * failed download (24.8), and the closed sets and tokens of the body (24.2).
 */
internal object ErrorRules {
    val STAGES = listOf("config", "register", "attest", "lease", "manifest", "download", "verify", "storage", "runtime", "model_open",
        "accelerator", "audio", "session", "sharing", "events")
    val CODES_NOT_REPORTED = setOf("invalid_argument", "client_exists", "session_open", "session_closed", "cancelled")
    private val KEY_REASONS = setOf("key_revoked", "key_unknown")
    /** An attestation the platform cannot give, or that failed: the SDK goes on unattested (24.4 rule 3a). */
    private val ATTESTATION = setOf("unsupported", "attestation_failed", "integrity_failed", "turnstile")
    const val DAILY_SIGNATURES = 16
    const val QUEUE_MAX = 32
    const val REPORTED_MAX = 64
    const val FIRST_AGAIN_DAYS = 90
    const val KEEP_DAYS = 7
    const val COUNT_MAX = 100_000

    val TOKEN = Regex("^[a-z][a-z0-9_]{0,31}$")
    val ERROR_NAME = Regex("^[A-Za-z][A-Za-z0-9_.:$-]{0,47}$")
    val ERROR_CODE = Regex("^[A-Za-z0-9_.:-]{1,32}$")
    val DEVICE_MODEL = Regex("^[A-Za-z0-9_.,-]{1,32}$")
    val HEX40 = Regex("^[0-9a-f]{40}$")
    val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$")
    val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "arm64", "x86_64_mac")
    val RAM_TIERS = setOf("lt2", "2_4", "4_8", "8_16", "ge16")

    /** 24.5: `error\n<stage>\n<reason>\n<code>\n<http_status, or 0>`. */
    fun signature(stage: String, reason: String, code: String, httpStatus: Int?): String = "error\n$stage\n$reason\n$code\n${httpStatus ?: 0}"

    /** The first 16 bytes of the signature's SHA-256, hex (how the server groups). */
    fun signatureHex(stage: String, reason: String, code: String, httpStatus: Int?): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(signature(stage, reason, code, httpStatus).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.substring(0, 32)

    /**
     * 24.4: the code of the error the app gets for a cause, or `none` when the call goes on (a silent failure). [onDevice]:
     * `none` (nothing of the build), `partial` (part of a download), `complete` (a build that opens).
     */
    fun codeFor(stage: String, reason: String, status: Int? = null, onDevice: String = "none", errorCode: String? = null): String {
        // rule 3a: the token gates the config; with a build on the device, or unattested, the SDK goes on
        if (stage == "attest" && (onDevice == "complete" || reason in ATTESTATION)) return "none"
        when (stage) {
            "sharing", "events" -> return "none"
            "storage" -> return mapOf("quota" to "storage_full", "read" to "storage_failed", "write" to "storage_failed", "corrupt" to "storage_failed")[reason] ?: "none"
            "verify" -> return mapOf("hash" to "checksum_mismatch", "signature" to "signature_invalid", "missing" to "model_missing",
                "invalid" to "model_invalid", "incompatible" to "model_incompatible")[reason] ?: "none"
            "runtime" -> return when (reason) {
                "wasm_unsupported", "simd", "threads", "webcrypto", "ed25519", "native_library" -> "runtime_unsupported"
                "worker" -> "none"
                else -> "engine_failed"
            }
            "model_open" -> return mapOf("incompatible" to "model_incompatible", "invalid" to "model_invalid", "decrypt" to "model_invalid",
                "missing" to "model_missing")[reason] ?: "engine_failed"
            "accelerator" -> return if (reason == "unavailable") "runtime_unsupported" else "engine_failed"
            "audio" -> return mapOf("permission_denied" to "microphone_denied", "interrupted" to "interrupted")[reason] ?: "microphone_unavailable"
            "session" -> return "engine_failed"
        }
        // the network stages: config, register, attest (rule 3a), lease, manifest, download
        if (stage == "lease") {
            // rule 3: only a stop the Core verified is a stop; rule 1: no valid lease and no answer is needs_connection,
            // whatever is on the device (a protected build does not open without a lease)
            return when (reason) {
                "grace" -> "none" // 18.4.7.1: an expired lease opened in its grace while Quran Lab could not be reached
                "stopped" -> if (errorCode in KEY_REASONS) "invalid_key" else "server_rejected"
                "refused" -> if (errorCode == "sdk_too_old") "sdk_too_old" else "server_rejected"
                "network", "expired" -> "needs_connection"
                in ATTESTATION, "invalid" -> "server_rejected"
                else -> "download_failed"
            }
        }
        if (reason == "refused") {
            if (errorCode in KEY_REASONS || errorCode == "invalid_key" || errorCode == "unauthorized") return "invalid_key"
            return if (errorCode == "sdk_too_old") "sdk_too_old" else "server_rejected"
        }
        if (onDevice == "complete" && stage in setOf("config", "register", "attest", "manifest")) return "none"
        if (stage == "register") return "none"
        if (reason == "network") return if (onDevice == "none") "needs_connection" else "download_failed"
        if (stage == "config" && reason == "unavailable") return "model_unavailable"
        if (stage == "manifest" && reason in setOf("signature", "parse", "incompatible")) {
            return mapOf("signature" to "signature_invalid", "parse" to "model_invalid", "incompatible" to "model_incompatible")[reason]!!
        }
        if (stage == "download") {
            if (reason == "hash") return "checksum_mismatch"
            if (reason == "signature") return "signature_invalid"
            if (reason == "quota") return "storage_full"
            if (reason == "storage") return "storage_failed"
        }
        return "download_failed"
    }

    /** 24.1: never the app's own misuse, never a silent plain network failure. */
    fun reported(stage: String, reason: String, code: String): Boolean =
        code !in CODES_NOT_REPORTED && !(code == "none" && reason == "network")

    /** 24.8: the `failure_reasons` name of a failed download's reason. */
    fun telemetryReason(reason: String): String = when (reason) {
        "network", "timeout" -> "network"
        "http", "rate_limited", "refused", "redirect", "invalid_answer" -> "http"
        "hash" -> "hash_mismatch"
        "signature" -> "signature"
        "storage", "quota" -> "storage"
        else -> "other"
    }

    /** 24.7: free bytes as the five buckets. */
    fun storageFree(bytes: Long): String = when {
        bytes < 100_000_000L -> "lt_100m"
        bytes < 500_000_000L -> "lt_500m"
        bytes < 1_000_000_000L -> "lt_1g"
        bytes < 5_000_000_000L -> "lt_5g"
        else -> "ge_5g"
    }

    /** Days from [a] to [b] (`YYYY-MM-DD`, UTC). */
    fun daysBetween(a: String, b: String): Long =
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b))

    /** [v] when it is a token of [rx], else null: a value that is not one is left out, never cut into one (24.2). */
    fun token(v: String?, rx: Regex): String? = v?.takeIf { rx.matches(it) }

    /** The reason of an HTTP answer that failed (24.3): `rate_limited` (429), `redirect` (3xx), `refused` (a 401 or 403 that
     *  names its error), else `http`. */
    fun reasonOf(status: Int, code: String?, reason: String?): String = when {
        status == 429 -> "rate_limited"
        status in 300..399 -> "redirect"
        // a 401 invalid_token that names no stop reason is the token's own answer (renewed once, repeated once), never a
        // refusal (ERRORS_40.md 24.3; model_gate.json: no error of its own)
        status == 401 && code == "invalid_token" && !ModelGate.stops(reason) -> "http"
        (status == 401 || status == 403) && (reason != null || (code != null && !code.startsWith("http_"))) -> "refused"
        else -> "http"
    }

    /** The server's `error` (or its reason, for a refusal) of a 4xx as `error_code`; null for any other answer. */
    fun serverCode(a: ApiError): String? = if (a.status !in 400..499) null else token(if (a.status == 401 || a.status == 403) a.refusal else a.code, ERROR_CODE)
        ?.takeIf { !it.startsWith("http_") }

    /** The stage of an API path: `/v1/config` config, `/v1/register` register, `/v1/attest` attest, `/v1/model-key` lease,
     *  `/v1/events` events, `/v1/uploads` sharing. */
    fun stageOfPath(path: String?): String? {
        val p = path?.substringBefore('?') ?: return null
        return when {
            p.startsWith("/v1/config") -> "config"
            p.startsWith("/v1/register") -> "register"
            p.startsWith("/v1/attest") -> "attest"
            p.startsWith("/v1/model-key") -> "lease"
            p.startsWith("/v1/events") -> "events"
            p.startsWith("/v1/uploads") || p.startsWith("/v1/contributions") -> "sharing"
            else -> null
        }
    }

    /** The first error of the chain that is the platform's (not one of the SDK's own classes), by its class. */
    fun platformName(e: Throwable): String? {
        val t = generateSequence(e) { it.cause }.take(8).firstOrNull { !it.javaClass.name.startsWith("ai.quranlab.") } ?: return null
        return token(t.javaClass.name, ERROR_NAME) ?: token(t.javaClass.simpleName, ERROR_NAME)
    }

    /** A wait that ended for its limit (a stall of 16.2, the platform's timeout). */
    fun timedOut(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { it is java.net.SocketTimeoutException ||
        it.javaClass.name == "java.net.http.HttpTimeoutException" }

    /** A write that failed for space: the system's ENOSPC (Android's `ErrnoException` errno 28, or its message). */
    fun noSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { t ->
        errno(t) == 28 || (t is java.io.IOException && t.message?.contains("No space left", ignoreCase = true) == true)
    }

    /** Android's `ErrnoException.errno` of [t], read without a dependency on the platform's class. */
    fun errno(t: Throwable): Int? = if (t.javaClass.name != "android.system.ErrnoException") null else try {
        t.javaClass.getField("errno").getInt(t)
    } catch (_: Exception) {
        null
    }

    /** The errno of the chain as `error_code`, when the platform gave one. */
    fun errnoCode(e: Throwable): String? = generateSequence(e) { it.cause }.take(8).firstNotNullOfOrNull { errno(it) }?.toString()

    /** `os_version` as section 7 takes it (`14`, `8.1.0`): the leading numbers of the system's version. */
    fun osVersion(v: String): String = Regex("^\\d{1,2}(\\.\\d{1,3}){0,2}").find(v.trim())?.value ?: v.take(32)
}

/**
 * One install's error reports (24.5, 24.6), as `errors40.py`'s Reporter keeps them: the queue (at most 32, the oldest go
 * first), each UTC day's signatures (at most 16 a day; `queued` or `sent`), and the signatures reported (at most 64, each
 * with the day of its last accepted report, for `first_report`). Kept in `errors.json` in [dir]; where nothing can be
 * written they live in memory for the process. Never waits on the network: [send] is given the request to make.
 */
internal class ErrorReporter(private val dir: File?) {
    /** A queued report: its day and signature, the count so far, and the last occurrence's fields ([detail]). */
    class Report(val day: String, val sig: String, val stage: String, val reason: String, val code: String, val httpStatus: Int?,
                 var count: Int, val firstReport: Boolean, var firstLaunch: Boolean, var detail: Map<String, Any?>) {
        fun copy() = Report(day, sig, stage, reason, code, httpStatus, count, firstReport, firstLaunch, detail)

        fun json(): Map<String, Any?> = linkedMapOf("day" to day, "sig" to sig, "stage" to stage, "reason" to reason, "code" to code,
            "http_status" to httpStatus, "count" to count, "first_report" to firstReport, "first_launch" to firstLaunch, "detail" to detail)
    }

    private val lock = Any()
    private val queue = ArrayList<Report>()
    private val days = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private val reported = ArrayList<Pair<String, String>>()
    private val file: File? = dir?.let { File(it, FILE) }

    /** Off: nothing is recorded (the signed config's `flags.issues: false`; in 3.x also the app's issue option). */
    @Volatile var enabled: Boolean = true

    init {
        load()
    }

    /**
     * An occurrence on [day] (24.5): a new report with count 1, or one more on the day's queued report of the same
     * signature (its fields replaced by this occurrence's [detail]), or nothing once that report was accepted or the
     * day has its 16 signatures. Returns whether it made a new report.
     */
    fun occur(day: String, stage: String, reason: String, code: String, httpStatus: Int?, firstLaunch: Boolean,
              detail: Map<String, Any?> = emptyMap()): Boolean = synchronized(lock) {
        if (!enabled || !ErrorRules.reported(stage, reason, code)) return false
        val sig = ErrorRules.signatureHex(stage, reason, code, httpStatus)
        days.keys.filter { ErrorRules.daysBetween(it, day) > ErrorRules.KEEP_DAYS }.forEach { days.remove(it) }
        val today = days.getOrPut(day) { LinkedHashMap() }
        when (today[sig]) {
            SENT -> return false
            QUEUED -> {
                queue.firstOrNull { it.day == day && it.sig == sig }?.let { r ->
                    r.count = minOf(ErrorRules.COUNT_MAX, r.count + 1)
                    r.firstLaunch = r.firstLaunch || firstLaunch
                    r.detail = detail
                    save()
                }
                return false
            }
        }
        if (today.size >= ErrorRules.DAILY_SIGNATURES) return false
        val last = reported.firstOrNull { it.first == sig }?.second
        val first = (last == null || ErrorRules.daysBetween(last, day) > ErrorRules.FIRST_AGAIN_DAYS) && queue.none { it.sig == sig }
        queue.add(Report(day, sig, stage, reason, code, httpStatus, 1, first, firstLaunch, detail))
        today[sig] = QUEUED
        while (queue.size > ErrorRules.QUEUE_MAX) {
            val old = queue.removeAt(0)
            days[old.day]?.remove(old.sig)
        }
        save()
        true
    }

    /** `flags.issues: false` (24.5): nothing is recorded from now on and the queue is dropped. */
    fun off() = synchronized(lock) {
        enabled = false
        queue.clear()
        save()
    }

    /** The queued reports, oldest first (copies). */
    fun queued(): List<Report> = synchronized(lock) { queue.map { it.copy() } }

    /**
     * A send point on [today] (24.6): reports older than 7 days are dropped, then each queued report, oldest first, is
     * given to [post] (one request; it returns the answer's status, or null for no answer), at most [limit] of them. 202:
     * sent, its signature on the reported list; 400 or 413: dropped for good; anything else keeps it and ends the send
     * point. Returns each report as it was sent with its answer.
     */
    fun send(today: String, limit: Int = Int.MAX_VALUE, post: (Report) -> Int?): List<Pair<Report, Int?>> {
        val pending = synchronized(lock) {
            val keep = queue.filter { ErrorRules.daysBetween(it.day, today) <= ErrorRules.KEEP_DAYS }
            if (keep.size != queue.size) {
                queue.clear()
                queue.addAll(keep)
                save()
            }
            queue.toList()
        }
        val made = ArrayList<Pair<Report, Int?>>()
        for (r in pending) {
            if (made.size >= limit) break
            val snap = synchronized(lock) { r.copy() }
            val answer = post(snap)
            made.add(snap to answer)
            val settled = answer == 202 || answer == 400 || answer == 413
            if (settled) synchronized(lock) {
                queue.remove(r)
                days.getOrPut(r.day) { LinkedHashMap() }[r.sig] = SENT
                if (answer == 202) {
                    reported.removeAll { it.first == r.sig }
                    reported.add(r.sig to r.day)
                    while (reported.size > ErrorRules.REPORTED_MAX) reported.removeAt(0)
                }
                save()
            }
            if (!settled) break
        }
        return made
    }

    /** The section 7 body of [r] with `kind: "error"` and its closed `error` object (24.2). */
    fun body(r: Report, app: AppIdentity, device: DeviceProfile, sdkVersion: String): Map<String, Any?> {
        val d = r.detail
        fun s(k: String, rx: Regex? = null): String? = (d[k] as? String)?.let { v -> if (rx == null || rx.matches(v)) v else null }
        fun n(k: String): Int? = (d[k] as? Number)?.toInt()
        val e = LinkedHashMap<String, Any?>()
        e["stage"] = r.stage
        e["reason"] = r.reason
        r.httpStatus?.takeIf { it in 100..599 }?.let { e["http_status"] = it }
        s("error_name", ErrorRules.ERROR_NAME)?.let { e["error_name"] = it }
        s("error_code", ErrorRules.ERROR_CODE)?.let { e["error_code"] = it }
        e["count"] = r.count.coerceIn(1, ErrorRules.COUNT_MAX)
        n("retries")?.let { e["retries"] = it.coerceIn(0, 1000) }
        e["first_launch"] = r.firstLaunch
        e["first_report"] = r.firstReport
        s("build", ErrorRules.HEX40)?.let { e["build"] = it }
        s("device_model", ErrorRules.DEVICE_MODEL)?.let { e["device_model"] = it }
        s("ram_tier")?.takeIf { it in ErrorRules.RAM_TIERS }?.let { e["ram_tier"] = it }
        s("storage_free")?.let { e["storage_free"] = it }
        s("abi")?.takeIf { it in ErrorRules.ABIS }?.let { e["abi"] = it }
        val b = LinkedHashMap<String, Any?>()
        b["day"] = r.day
        b["platform"] = app.platform
        b["sdk_version"] = sdkVersion
        b["app_version"] = app.version
        b["os_version"] = ErrorRules.osVersion(device.osVersion)
        b["device_class"] = device.deviceClass
        s("revision", ErrorRules.VERSION)?.let { b["revision"] = it }
        b["kind"] = "error"
        b["code"] = r.code
        s("backend")?.let { b["backend"] = it }
        b["error"] = e
        return b
    }

    private fun load() {
        val f = file ?: return
        try {
            if (!f.isFile) return
            val o = Json.parse(f.readText()) as? Map<*, *> ?: return
            for (x in o["queue"] as? List<*> ?: emptyList<Any?>()) {
                val q = x as? Map<*, *> ?: continue
                @Suppress("UNCHECKED_CAST")
                queue.add(Report(q["day"] as String, q["sig"] as String, q["stage"] as String, q["reason"] as String, q["code"] as String,
                    (q["http_status"] as? Number)?.toInt(), (q["count"] as? Number)?.toInt() ?: 1, q["first_report"] == true,
                    q["first_launch"] == true, (q["detail"] as? Map<String, Any?>) ?: emptyMap()))
            }
            (o["days"] as? Map<*, *>)?.forEach { (day, sigs) ->
                val m = LinkedHashMap<String, String>()
                (sigs as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) m[k] = v }
                if (day is String) days[day] = m
            }
            for (x in o["reported"] as? List<*> ?: emptyList<Any?>()) {
                val p = x as? List<*> ?: continue
                val sig = p.getOrNull(0) as? String ?: continue
                val day = p.getOrNull(1) as? String ?: continue
                reported.add(sig to day)
            }
        } catch (_: Exception) {
            queue.clear()
            days.clear()
            reported.clear()
        }
    }

    /** Written after every change; where it cannot be, the state stays in memory (24.5). */
    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Json.write(linkedMapOf("v" to 1, "queue" to queue.map { it.json() }, "days" to days,
                "reported" to reported.map { listOf(it.first, it.second) })))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val FILE = "errors.json"
        private const val QUEUED = "queued"
        private const val SENT = "sent"
    }
}
