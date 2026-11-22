package ai.quranlab.tajweedformer.core

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.at
import ai.quranlab.tajweedformer.via
import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.AppToken
import ai.quranlab.tajweedformer.cloud.CloudException
import ai.quranlab.tajweedformer.cloud.HttpResponse
import ai.quranlab.tajweedformer.cloud.IntegrityProvider
import ai.quranlab.tajweedformer.cloud.HostNames
import ai.quranlab.tajweedformer.cloud.ModelGate
import ai.quranlab.tajweedformer.cloud.NotTaken
import ai.quranlab.tajweedformer.cloud.QuranLabApi
import ai.quranlab.tajweedformer.cloud.QuranLabKey
import ai.quranlab.tajweedformer.cloud.b64url
import ai.quranlab.tajweedformer.cloud.b64urlDecode
import ai.quranlab.tajweedformer.cloud.hex
import ai.quranlab.tajweedformer.cloud.sha256
import java.io.File

/**
 * The install certificate: base64url of its text, a dot, base64url of Quran Lab's signature. The SDK
 * reads it only to schedule (when to attest again, whether Play is owed a re-check) and sends it back as it came; the
 * server decides everything from it.
 */
internal class InstallCert private constructor(val encoded: String, private val f: Map<String, String>) {
    val att: String get() = f["att"] ?: "none"
    val platform: String? get() = f["platform"]
    val install: String? get() = f["install"]
    val sign: String? get() = f["sign"]
    val iat: Long get() = f["iat"]?.toLongOrNull() ?: 0L
    val exp: Long get() = f["exp"]?.toLongOrNull() ?: 0L
    val recheck: Boolean get() = f["recheck"] == "1"
    val hw: Boolean get() = f["hw"] == "1"
    operator fun get(k: String): String? = f[k]

    companion object {
        const val FIRST_LINE = "quranlab-install-cert 1"

        /** The certificate's fields, or null when [s] is not one (two base64url parts, the text's first line). */
        @JvmStatic
        fun parse(s: String?): InstallCert? {
            val t = s?.trim() ?: return null
            val dot = t.indexOf('.')
            if (dot <= 0 || dot != t.lastIndexOf('.')) return null
            val text = try { String(b64urlDecode(t.substring(0, dot)), Charsets.UTF_8) } catch (_: Exception) { return null }
            val lines = text.split('\n')
            if (lines.firstOrNull() != FIRST_LINE || !text.endsWith("\n")) return null
            val m = LinkedHashMap<String, String>()
            for (l in lines.drop(1)) {
                if (l.isEmpty()) continue
                val sp = l.indexOf(' ')
                if (sp <= 0) return null
                m[l.substring(0, sp)] = l.substring(sp + 1)
            }
            return InstallCert(t, m)
        }
    }
}

/** What the SDK keeps of the lease in force, beside the Core's own copy: for which build, where its files are, and when
 *  to ask again (`<storage>/core/lease.json`). */
internal data class LeaseRecord(
    val build: String,
    val base: String?,
    val exp: Long,
    val refreshAfter: Long,
    /** Refreshes that failed in a row, and when the next one may run (ms, this device's clock). */
    val failures: Int = 0,
    val nextTryMs: Long = 0,
    /** The lease's issue time (the answer's `iat`, server seconds; 0 in a record of SDK 4.0.1): a record never gives way
     *  to one whose lease the server issued before it. */
    val iat: Long = 0,
) {
    fun json(): Map<String, Any?> = linkedMapOf("build" to build, "base" to base, "exp" to exp, "refresh_after" to refreshAfter,
        "failures" to failures, "next_try_ms" to nextTryMs, "iat" to iat)

    companion object {
        fun of(o: Map<*, *>?): LeaseRecord? {
            o ?: return null
            return LeaseRecord(o["build"] as? String ?: return null, o["base"] as? String, (o["exp"] as? Double)?.toLong() ?: 0,
                (o["refresh_after"] as? Double)?.toLong() ?: 0, (o["failures"] as? Double)?.toInt() ?: 0, (o["next_try_ms"] as? Double)?.toLong() ?: 0,
                (o["iat"] as? Double)?.toLong() ?: 0)
        }
    }
}

/**
 * One Core per storage folder and install key in the process (the design approved for Apple and Android, 2026-11-22). A
 * lease client takes the live Core of its folder and key while any holder still has it (an open client, an attempt in
 * flight of a client that closed), so a new client never makes a second Core over the same saved state, and the late
 * answer of a closed client's attempt goes into the Core the new client uses. The Core is destroyed when its last holder
 * lets go ([Core.close]: after its calls in flight). A test makes a registry of its own for another process.
 */
internal class CoreRegistry {
    private class Entry(val core: Core, var holds: Int) {
        /** SDK 4.0.3: for each model open or opening in this Core, the lease client it belongs to (one entry per model). */
        val models = ArrayList<Any>()
    }
    private val entries = HashMap<String, Entry>()
    private val modelsChanged = Object()
    private val folderLocks = HashMap<String, Any>()
    private val clears = HashMap<String, Long>()

    /** The live Core of [dir] and [key] with one hold more, or a new one from [make] (its first hold). */
    fun hold(dir: File, key: AgreementKey, make: () -> Core): Core = synchronized(this) {
        val k = name(dir) + "|" + key.alias
        entries[k]?.let { e -> e.holds++; return e.core }
        make().also { entries[k] = Entry(it, 1) }
    }

    /** One hold more of [core], when it is still live (false: its last holder let it go). */
    fun retain(core: Core): Boolean = synchronized(this) {
        val e = entries.values.firstOrNull { it.core === core } ?: return false
        e.holds++
        true
    }

    /** One hold less of [core]; the last one destroys it, waiting at most [waitMs] on this thread ([Core.close]). */
    fun release(core: Core, waitMs: Long = Core.CLOSE_WAIT_MS) {
        synchronized(this) {
            val e = entries.entries.firstOrNull { it.value.core === core } ?: return
            if (--e.value.holds > 0) return
            entries.remove(e.key)
        }
        core.close(waitMs)
    }

    /** Whether [core] is still live here (tests). */
    fun live(core: Core): Boolean = synchronized(this) { entries.values.any { it.core === core } }

    /**
     * SDK 4.0.3: a model of [owner] is about to open in [core]: one hold more, kept until [modelEnded] (the open failed,
     * or its model is really closed), so the Core is never destroyed under an open or an open model (qlc_destroy does
     * not close the Core's model). False when [core] is not live any more.
     */
    fun modelBegins(core: Core, owner: Any): Boolean = synchronized(this) {
        val e = entries.values.firstOrNull { it.core === core } ?: return false
        e.holds++
        e.models += owner
        true
    }

    /** The open [modelBegins] began failed, or its model is closed: its hold goes ([release]). */
    fun modelEnded(core: Core, owner: Any) {
        synchronized(this) {
            entries.values.firstOrNull { it.core === core }?.models?.remove(owner)
        }
        synchronized(modelsChanged) { modelsChanged.notifyAll() }
        release(core)
    }

    /** The models open or opening in [core]: [owner]'s and the other owners' (null when [core] is not live). */
    private fun counts(core: Core, owner: Any): Pair<Int, Int>? = synchronized(this) {
        entries.values.firstOrNull { it.core === core }?.models?.let { m -> m.count { it === owner } to m.count { it !== owner } }
    }

    /** Whether [owner] has a model open in [core] besides the open it is making (which [modelBegins] counted). */
    fun ownModelOpen(core: Core, owner: Any): Boolean = (counts(core, owner)?.first ?: 0) > 1

    /** Whether a model of another owner is open or opening in [core]. */
    fun othersOpen(core: Core, owner: Any): Boolean = (counts(core, owner)?.second ?: 0) > 0

    /**
     * After an open of [owner] in [core] met QLC_E_BUSY (that open counted by [modelBegins]), on a thread that may
     * block (a sync call off the main looper, [OpenWait]): waits at most [waitMs] while a model of ANOTHER owner is
     * open or opening in [core] (a Client that was closed while its model opened closes that model as soon as its open
     * returns). True when none is left: the open may be tried once more. False at once when [owner] has a model of its
     * own open there (its Client's, which only its Client closes), when [core] is not live, and when the wait ends
     * with one still open.
     */
    fun awaitOthers(core: Core, owner: Any, waitMs: Long): Boolean {
        val end = System.nanoTime() + waitMs * 1_000_000
        synchronized(modelsChanged) {
            while (true) {
                val counts = counts(core, owner) ?: return false
                if (counts.first > 1) return false
                if (counts.second == 0) return true
                val ms = (end - System.nanoTime()) / 1_000_000
                if (ms <= 0) return false
                try { modelsChanged.wait(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
    }

    /** The lock of [dir]'s lease files: lease.json is read, compared and written under it, by every client of the folder. */
    fun folderLock(dir: File): Any = synchronized(this) { folderLocks.getOrPut(name(dir)) { Any() } }

    /** How many clears of [dir]'s lease (a stop) this process has made: a lease answer to a request that went out
     *  before the last one is never accepted. */
    fun clears(dir: File): Long = synchronized(this) { clears[name(dir)] ?: 0 }

    /** A clear of [dir]'s lease (under the folder's lock). */
    fun cleared(dir: File) = synchronized(this) { clears[name(dir)] = (clears[name(dir)] ?: 0) + 1 }

    private fun name(dir: File): String = try { dir.canonicalPath } catch (_: Exception) { dir.absoluteFile.normalize().path }

    companion object {
        /** The process's registry. */
        @JvmField val PROCESS = CoreRegistry()
    }
}

/**
 * A model build the lease is asked for (18.4.4): the model id, version, build and key id the signed config names
 * (18.4.5: `recommended.key_id`, each model's `key_id`). [manifest] gives the build's signed manifest, verified, for the
 * lease answer's `base` (the build's folder, null when the answer has none): the Core accepts the lease for those bytes.
 */
internal class LeaseTarget(val model: String, val version: String, val build: String, val keyId: String, val manifest: (base: String?) -> ByteArray)

/** Why a lease or an attest failed, for the `lease.failed` histogram (17.8): offline, refused, expired, clock,
 *  attestation, other. */
internal class LeaseFailure(val kind: String, val error: TajweedError, /** a stop the Core verified (18.4.10) */ val stopped: Boolean = false) :
    Exception(error.message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The protected model's keys and lease for this install: the install certificate, the attest that
 * makes it (a NEW agreement key every time, Key Attestation first, Play Integrity only where there is no usable
 * chain and the signed config gives a project number), the lease request and its refusals, and the refresh with the
 * server's jitter. The Core keeps the lease, its clock and the saved state; this class talks to the server and to the
 * key store.
 *
 * Files (`<storage>/core`): `install_cert.txt` (the certificate in force), `previous_cert.txt` (the last one, any age,
 * for a re-attest), `lease.json` ([LeaseRecord]), `state.bin` (the Core's sealed state), and the Core's own (the
 * recited journal). Nothing here is a model file.
 */
internal class LeaseClient(
    val dir: File,
    private val identity: InstallIdentity,
    private val vault: StateVault,
    private val api: QuranLabApi,
    private val key: QuranLabKey,
    private val app: AppIdentity,
    /** A fresh app token, and what to do after a 401 `invalid_token` and with a token an attest answered. */
    private val token: () -> String,
    private val invalidateToken: () -> Unit,
    private val adoptToken: (AppToken) -> Unit,
    private val integrity: IntegrityProvider?,
    private val projectNumber: () -> Long?,
    /** A verified stop removes every model file (SPEC 15.3). */
    private val removeModels: () -> Boolean,
    /** An issue for Quran Lab (`kind`, `code`): an SDK bug the server named (400 `invalid_body`, `install_sig_invalid`). */
    private val report: (String, String) -> Unit = { _, _ -> },
    /** A lease refresh ended: null ok, else the failure's kind (17.8 `lease`). */
    leaseEvent: (String?) -> Unit = {},
    private val bootId: ByteArray = ByteArray(32),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Tests: the Core's clocks come from [hostClock] (wall ms, boot ms). */
    private val hostClock: Pair<() -> Long, () -> Long>? = null,
    /** The platform's risk signals beside the key store's (18.6.4). */
    private val platformSignals: Int = 0,
    /** 18.4.7.1: an open in the lease's grace, reported silently. */
    private val onGrace: (ai.quranlab.tajweedformer.cloud.ErrorCause) -> Unit = {},
    /** 18.4.7.1: how long a model open past `exp` waits for a lease attempt (10 s), and how long an unreachable outcome
     *  of this process serves the next open with no request (60 s). Tests shorten them. */
    private val graceWaitMs: Long = 10_000,
    private val graceReuseMs: Long = 60_000,
    /** 18.4.7.3: the random source of the backoff's drawn waits (uniform in [0, 1)); tests fix it. */
    private val random: () -> Double = { java.util.concurrent.ThreadLocalRandom.current().nextDouble() },
    /** Where the Core of this folder and key is shared ([CoreRegistry.PROCESS]; a test's own for another process). */
    private val registry: CoreRegistry = CoreRegistry.PROCESS,
) : AutoCloseable {
    private val lock = Any()
    private val certFile = File(dir, "install_cert.txt")
    private val previousFile = File(dir, "previous_cert.txt")
    private val leaseFile = File(dir, "lease.json")
    private val stateFile = File(dir, "state.bin")
    private val playRetryFile = File(dir, "play_retry")
    private var core: Core? = null
    @Volatile private var modelsOpen = 0

    private val onLeaseEvent = leaseEvent

    /** A lease attempt ended: told to the Client (17.8 `lease`), except a failure that [close] caused (never a failed
     *  attempt). */
    private fun leaseEvent(kind: String?) {
        if (kind != null && closing) return
        onLeaseEvent(kind)
    }

    /** [close] has waited for the attempt in flight and cancels what is left: an attempt that ends from here was
     *  cancelled by it, never a failed attempt (no outcome told to the Core, no row of 18.4.7.3, no failure event). */
    @Volatile private var closing = false

    /** [close] has run: no Core is made again. */
    @Volatile private var closed = false
    private var closeStarted = false

    val cert: InstallCert? get() = InstallCert.parse(try { certFile.readText() } catch (_: Exception) { null })

    /** The lease record; a folder in it that is not Quran Lab's ([baseOk]) is not taken. */
    val record: LeaseRecord?
        get() = try {
            LeaseRecord.of(Json.parse(leaseFile.readText()) as? Map<*, *>)?.let { r -> if (r.base != null && !baseOk(r.base)) r.copy(base = null) else r }
        } catch (_: Exception) {
            null
        }

    /** The lock of this folder's lease files, shared by every client of the folder ([CoreRegistry.folderLock]). */
    private val folderLock: Any = registry.folderLock(dir)

    /**
     * lease.json, read, compared and written whole under the folder's lock: a record never replaces one whose lease the
     * server issued later ([LeaseRecord.iat]; another client of the folder took a newer one), unless [accepted] (the
     * Core holds this one now). Null removes it. Whether it was written.
     */
    private fun saveRecord(r: LeaseRecord?, accepted: Boolean = false): Boolean = synchronized(folderLock) {
        try {
            if (r == null) { leaseFile.delete(); return true }
            val cur = record
            if (!accepted && cur != null && cur.iat > r.iat) return false
            dir.mkdirs()
            val tmp = File(dir, "lease.json.tmp")
            tmp.writeText(Json.write(r.json()))
            if (!tmp.renameTo(leaseFile)) { leaseFile.delete(); tmp.renameTo(leaseFile) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The Core for the install key in force, made at first need with the saved state; null before the first attest. */
    fun core(): Core? = synchronized(lock) {
        core?.let { return it }
        if (closed) return null
        val k = identity.current() ?: return null
        // the live Core of this folder and key when another holder has it, else a new one from the saved state
        val c = registry.hold(dir, k) { openCore(k) }
        core = c
        c
    }

    /**
     * `diagnose()`'s `protected` object: which key store
     * holds the install's agreement key (`strongbox`, `tee`, `software`; null before the install has one), the lease
     * as the Core holds it (`valid`, `grace`, `expired`, `stopped`, `none`), its `leaseExpires` and `refreshAfter`
     * (unix seconds), its `leaseClass` (`attested` when the lease's flags allow accelerator programs, which 18.4.6 gives
     * only the attested class, else `standard`; never from the lease's times, which are the server's), `graceUntil`
     * (only while `lease` is `grace`, 18.4.7.1) and the Core's release and target. No key, path or identifier.
     */
    fun diagnosis(): Map<String, Any?> {
        val k = try { identity.current() } catch (_: Exception) { null }
        val keyStore = k?.let { if (it.strongBox) "strongbox" else if (it.hardware) "tee" else "software" }
        val c = try { core() } catch (_: Throwable) { null }
        val st = try { c?.tick() } catch (_: Exception) { null }
        val grace = try { c?.grace() } catch (_: Exception) { null }
        val lease = when {
            st == null -> "none"
            st.stopped -> "stopped"
            st.valid -> "valid"
            grace?.first == true -> "grace"
            st.hasLease -> "expired"
            else -> "none"
        }
        val held = st != null && st.hasLease && lease != "stopped" && lease != "none"
        val cls = if (!held) null else try { if (c!!.accelAllowed()) "attested" else "standard" } catch (_: Exception) { null }
        return linkedMapOf(
            "keyStore" to keyStore,
            "lease" to lease,
            "leaseExpires" to st?.takeIf { it.hasLease && it.exp > 0 }?.exp,
            "leaseClass" to cls,
            "refreshAfter" to st?.takeIf { held && it.refreshAfter > 0 }?.refreshAfter,
            "graceUntil" to grace?.takeIf { lease == "grace" && it.second > 0 }?.second,
            "core" to try { CoreLibrary.version } catch (_: Throwable) { null },
        )
    }

    private fun openCore(k: AgreementKey): Core {
        val host = object : CoreHost(identity, k, vault, stateFile, removeModels) {
            override fun wallMs(): Long = hostClock?.first?.invoke() ?: super.wallMs()
            override fun bootMs(): Long = hostClock?.second?.invoke() ?: super.bootMs()
        }
        val state = try { stateFile.takeIf { it.isFile }?.readBytes() } catch (_: Exception) { null }
        val flags = if (hostClock != null) Core.FLAG_HOST_CLOCKS else 0
        return Core.open(host, k, state, dir, bootId, identity.signals or platformSignals, flags)
    }

    /** SDK 4.0.3 ([CoreRegistry.modelBegins]): a model of this client is about to open in [c], which is held until
     *  [modelEnded]; false when [c] is not live any more. */
    fun modelBegins(c: Core): Boolean = registry.modelBegins(c, this)

    /** The open [modelBegins] began failed, or its model is really closed. */
    fun modelEnded(c: Core) = registry.modelEnded(c, this)

    /** After a busy open in [c], blocking: waits at most [waitMs] for another client's model there to close
     *  ([CoreRegistry.awaitOthers]); true when the open may be tried once more. */
    fun awaitOtherModels(c: Core, waitMs: Long): Boolean = registry.awaitOthers(c, this, waitMs)

    /** Whether this client has a model open in [c] besides the open it is making. */
    fun ownModelOpen(c: Core): Boolean = registry.ownModelOpen(c, this)

    /** Whether another client's model is open or opening in [c]. */
    fun otherModelsOpen(c: Core): Boolean = registry.othersOpen(c, this)

    /** A model of the Core opened or closed: the install key is not replaced while one is open (the Core's model is
     *  bound to the handle it was opened on). */
    fun modelOpened(roots: ((Core) -> Unit)? = null) {
        synchronized(lock) {
            modelsOpen++
            if (roots != null) openRoots = roots
        }
    }

    /** The storage roots of the build whose model is open: an install of another build beside it sets them back after
     *  (the Core's roots are one per handle, and the open model's later reads, its reference data and its accelerator
     *  program, stay on its own folder). */
    @Volatile private var openRoots: ((Core) -> Unit)? = null

    /** After an install with other roots: the open model's roots again (none open: nothing to do). */
    fun restoreOpenRoots(c: Core) {
        val r = synchronized(lock) { if (modelsOpen > 0) openRoots else null } ?: return
        try { r(c) } catch (_: Exception) {}
    }

    fun modelClosed() {
        synchronized(lock) {
            if (modelsOpen > 0) modelsOpen--
            if (modelsOpen == 0) openRoots = null
        }
    }

    // ---------------------------------------------------------------- attest (18.4.3, 18.4.8)

    /** A certificate in force for at least [marginS] more seconds, attesting when there is none. */
    fun ensureCert(marginS: Long = 300): InstallCert {
        val now = clock() / 1000
        cert?.takeIf { it.exp - now > marginS && identity.current() != null && sha256(identity.current()!!.spki).hex() == it.install }?.let { return it }
        return attest()
    }

    /**
     * POST /v1/attest with a NEW agreement key made for a fresh challenge (18.4.8 steps 1 to 4): its chain, the
     * signing key's `install_sig`, the last certificate as `previous_cert`. Play Integrity's token is sent only where
     * the signed config gives the project number and the new key has no usable chain (not in secure hardware), or the
     * last certificate came from Play; an answer of `att none` with Play available is asked once more with Play, at
     * most once a day. On 200 the new key and certificate are kept and the old key deleted; on any refusal the new key
     * is deleted and the old key and certificate stay.
     */
    fun attest(withPlay: Boolean = false): InstallCert {
        synchronized(lock) {
            if (modelsOpen > 0 && identity.current() != null) {
                throw LeaseFailure("attestation", RecognizerException.NeedsConnection(IllegalStateException("a model is open: the install key is renewed when it closes")))
            }
        }
        var tries = 0
        while (true) {
            tries++
            val challenge = try { api.challenge(key) } catch (e: CloudException) { answered(e); throw failure(e, "attestation") }
            answered(200, false, 0)
            val k = try { identity.prepare(challenge) } catch (e: Exception) {
                throw LeaseFailure("attestation", RecognizerException.StorageFailed("the key store did not make the install key: ${e.javaClass.simpleName}"))
            }
            val installHex = sha256(k.spki).hex()
            val previous = (cert?.encoded ?: try { previousFile.readText().trim() } catch (_: Exception) { null })?.takeIf { InstallCert.parse(it) != null }
            val prevAtt = InstallCert.parse(previous)?.att
            val project = projectNumber()
            val play = integrity != null && project != null && (withPlay || !k.hardware || k.chain.isEmpty() || prevAtt == "play")
            val body = LinkedHashMap<String, Any?>()
            body["platform"] = app.platform
            body["bundle_id"] = app.bundleId
            body["challenge"] = challenge
            body["install_key"] = b64url(k.spki)
            body["install_sign_key"] = b64url(identity.signingSpki())
            body["install_sig"] = b64url(identity.sign(attestText(challenge, installHex)))
            body["install_hw"] = if (k.hardware) 1 else 0
            if (app.platform == "android") {
                if (k.chain.isNotEmpty()) body["key_attestation"] = k.chain.map { b64url(it) }
                if (previous != null) body["previous_cert"] = previous
                if (play) {
                    try {
                        body["integrity_token"] = integrity!!.token(requestHash(challenge, installHex), project!!)
                    } catch (_: Exception) {
                        // Play could not answer: the server decides from the chain, or the unattested class
                    }
                }
            }
            val r = try { api.attestInstall(key, body) } catch (e: CloudException) {
                answered(e)
                identity.discard(k)
                throw failure(e, "attestation")
            }
            answered(r.status, r.status != 200 && r.error.apiRefusal, r.retryAfter ?: 0)
            if (r.status == 200) {
                val o = r.json()
                val c = InstallCert.parse(o?.get("install_cert") as? String)
                if (c == null || c.install != installHex) {
                    identity.discard(k)
                    throw LeaseFailure("attestation", RecognizerException.DownloadFailed("attest", "the answer carries no certificate for this install key"))
                }
                // a client that closed meanwhile commits nothing: a new client of the folder keeps its key and certificate
                if (closing) {
                    identity.discard(k)
                    throw LeaseFailure("attestation", RecognizerException.NeedsConnection())
                }
                // the folder's lock before the client's: the one order of the two (an accept writes the certificate so)
                synchronized(folderLock) {
                    synchronized(lock) {
                        certFile.takeIf { it.isFile }?.let { try { write(previousFile, it.readText()) } catch (_: Exception) {} }
                        identity.commit(k)
                        write(certFile, c.encoded)
                        write(previousFile, c.encoded)
                        // the Core is made again for the new key: its lease part starts over, the clock and usage carry over
                        core?.let { registry.release(it) }
                        core = null
                        saveRecord(null)
                    }
                }
                try { adoptToken(AppToken.from(r)) } catch (_: Exception) {}
                // `att none` where Play could have answered: one more attest with Play, at most once a day
                if (c.att == "none" && !play && !withPlay && integrity != null && project != null && app.platform == "android" && playRetryDue()) {
                    markPlayRetry()
                    return try { attest(withPlay = true) } catch (_: LeaseFailure) { c }
                }
                return c
            }
            identity.discard(k)
            val e = r.error
            if (e.code == "challenge_invalid" && tries < 2) continue
            if (e.code == "install_sig_invalid" || e.code == "invalid_body") report("other", "bad_request:attest:${e.code}")
            throw refused(r, "attestation", "attest")
        }
    }

    private fun playRetryDue(): Boolean = try {
        !playRetryFile.isFile || clock() - playRetryFile.readText().trim().toLong() > 86_400_000L
    } catch (_: Exception) {
        true
    }

    private fun markPlayRetry() = try { write(playRetryFile, clock().toString()) } catch (_: Exception) {}

    // ---------------------------------------------------------------- the lease (18.4.4 to 18.4.10)

    /**
     * A valid lease of [t] in the Core: the one it holds when it is for this build and valid now, else a new one asked
     * for at once (the app's call waits). A refresh that is due runs later in the background ([refreshIfDue]).
     * Throws a [LeaseFailure] whose error is the app's.
     */
    fun ensure(t: LeaseTarget, roots: (Core) -> Unit): Core = try {
        val c = core()
        if (c != null) roots(c)
        val r = record
        val st = c?.tick()
        when {
            c != null && st!!.valid && r?.build == t.build -> c
            // 18.4.7.1: this build's lease has expired: an attempt first, then the Core's grace decides
            c != null && st!!.hasLease && !st.stopped && r?.build == t.build -> pastExp(c, t, roots)
            else -> attempt(t, roots)
        }
    } catch (e: CoreException) {
        // the Core closed under the call (the Client closing): the app's error through the usual path, never a crash
        if (e.status != Core.E_CLOSED) throw e
        throw LeaseFailure("other", e.toError())
    } finally {
        // the roots of another build, set for this lease, never stay under an open model
        synchronized(lock) { core }?.let { restoreOpenRoots(it) }
    }

    /** Whether the lease in force is past its `refresh_after` and a refresh may run now (18.4.7's backoff). */
    fun refreshDue(): Boolean {
        val c = core() ?: return false
        val r = record ?: return false
        val st = try { c.tick() } catch (_: Exception) { return false }
        return st.hasLease && st.refreshDue && clock() >= r.nextTryMs
    }

    /**
     * A refresh in the background (18.4.7): at the first app start or model open after `refresh_after`. After a
     * failure the next try waits the backoff of 18.4.7.3 ([backoffMs]; a `Retry-After` is a floor, never a reset). A
     * valid lease is never in question while it waits.
     */
    fun refreshIfDue(t: LeaseTarget, roots: (Core) -> Unit, failed: (LeaseFailure) -> Unit = {}) {
        if (!refreshDue()) return
        try {
            attempt(t, roots)
            synchronized(lock) { core }?.let { restoreOpenRoots(it) }
        } catch (f: LeaseFailure) {
            try { failed(f) } catch (_: Exception) {}
            synchronized(lock) { core }?.let { restoreOpenRoots(it) }
        }
    }

    /**
     * 18.4.7.3: an attempt that brought no lease (any outcome but a verified stop) is one more failure in the row, and
     * the SDK's own next attempt (the refresh, its retries, an open in the grace) waits [backoffMs] of the row's length,
     * or the answer's `Retry-After` when that is longer. Every attempt counts, an app's call too (item 4). The row ends at
     * a lease the Core accepts (the record is written again) and at a verified stop (the record goes).
     */
    private fun failedInRow(retryAfterS: Int) {
        synchronized(folderLock) {
            val r = record ?: return
            val n = r.failures + 1
            saveRecord(r.copy(failures = n, nextTryMs = clock() + backoffMs(n, retryAfterS, random)))
        }
    }

    /** 18.4.7.3 item 3: the SDK's own attempts wait while the row says so; an app's call that needs a lease does not. */
    private fun rowWaits(): Boolean = record?.let { it.failures > 0 && clock() < it.nextTryMs } ?: false

    /**
     * The lease request, with the one repeat each refusal allows (18.4.10), and one more for a proof refused under a
     * new data piece (18.6.7: 403 `lease_integrity_failed`, why `core`): the refusal sent the Core back to its last good
     * piece, so the repeat is asked under that one. A piece that was the last good one already is refused the same
     * way, and the request fails.
     */
    fun request(t: LeaseTarget, roots: (Core) -> Unit): Core {
        var renewedToken = false
        var attested = false
        var newChallenge = false
        var keystoreRetry = false
        var pieceRetry = false
        while (true) {
            if (cert == null || identity.current() == null) {
                ensureCert()
                attested = true
            }
            val c = core() ?: throw LeaseFailure("attestation", RecognizerException.NeedsConnection())
            // the attempt holds the Core it asks with until it ends, also when the client closes meanwhile (its lease is kept)
            attemptHolds.get()?.let { h -> if (h.none { it === c } && registry.retain(c)) h += c }
            roots(c)
            val tok = try { token() } catch (e: CloudException) { answered(e); throw failure(e, "offline").also { leaseEvent(it.kind) } } catch (e: TajweedError) {
                throw LeaseFailure("other", e).also { leaseEvent(it.kind) }
            }
            val challenge = try { api.challenge(tok) } catch (e: CloudException) {
                answered(e)
                if (e.api?.code == "invalid_token" && e.api.reason == null && !renewedToken) { renewedToken = true; invalidateToken(); continue }
                throw failure(e, "offline", "lease").also { leaseEvent(it.kind) }
            }
            answered(200, false, 0)
            val (coreJson, installSig) = c.leaseAsk(challenge, t.build)
            val cert = this.cert ?: continue
            val body = LinkedHashMap<String, Any?>()
            body["v"] = 1
            body["platform"] = app.platform
            body["install_cert"] = cert.encoded
            body["install_key"] = b64url(c.key.spki)
            body["install_sign_key"] = b64url(identity.signingSpki())
            body["challenge"] = challenge
            body["install_sig"] = installSig
            body["model"] = t.model
            body["version"] = t.version
            body["build"] = t.build
            body["key_id"] = t.keyId
            val coreObj = coreObject(coreJson)
            body["core"] = coreObj
            val underPiece = ((coreObj["piece"] as? Number)?.toLong() ?: 0L) > 0L
            // a certificate that owes Play a re-check (Play was not answering at the attest) carries a fresh token
            val project = projectNumber()
            if (cert.recheck && integrity != null && project != null) {
                try { body["integrity_token"] = integrity.token(requestHash(challenge, sha256(c.key.spki).hex()), project) } catch (_: Exception) {}
            }
            // 28.3, 28.5 (SDK 4.0.1): the request goes through the api hosts, and a 200 is taken only as a lease the Core
            // accepts ([accept]); one that is no lease answer of 18.4.5 (no lease, a base off the allow-list) or whose lease
            // the Core refuses makes that host unreachable, and the next host is asked with the same challenge. A stop
            // cleared the folder's lease after the request went out: its answer is not accepted ([accept])
            val clearsBefore = registry.clears(dir)
            val r = try {
                api.modelKey(tok, body) { a -> accept(c, t, a, clearsBefore) }
            } catch (e: CloudException) {
                answered(e)
                throw failure(e, "offline").also { leaseEvent(it.kind) }
            } catch (f: LeaseFailure) {
                // 28.7: an answer every host gave that was no lease (the first host's failure), after more than one host
                answered(200, false, 0, api.lastRequestFallback())
                leaseEvent(f.kind)
                throw f
            } catch (e: TajweedError) {
                answered(200, false, 0, api.lastRequestFallback())
                // the manifest of the answer's folder could not be had (the network, a manifest that is not this build)
                throw LeaseFailure(if (e is RecognizerException.NeedsConnection || e is RecognizerException.NetworkNotPermitted) "offline" else "other", e)
                    .also { leaseEvent(it.kind) }
            } catch (e: CoreException) {
                answered(200, false, 0, api.lastRequestFallback())
                // the key store could not open the outer wrap (a key the store lost): a new key, once
                if (e.status == Core.E_KEYSTORE && !keystoreRetry) {
                    keystoreRetry = true
                    dropCert()
                    continue
                }
                throw LeaseFailure("other", e.toError()).also { leaseEvent(it.kind) }
            }
            answered(r.status, r.status != 200 && r.error.apiRefusal, r.retryAfter ?: 0, r.fallback)
            if (r.status == 200) {
                leaseEvent(null)
                return c
            }
            val e = r.error
            // every refusal of the route goes to the Core (18.4.10, 18.6.7): a signed stop that verifies clears its lease
            // and removes the models; any other refusal while a new data piece is in force sends the Core back to its
            // last good piece for the next ask (a proof the server refused under the new one). A refusal is a 4xx with a
            // body, never a 429: a rate limit, a server failure or no answer say nothing about the request and are
            // tried again later (18.4.7's backoff), the piece in force unchanged.
            // a clear (a stop) runs under the folder's lock, after any accept already started there, and no lease answer
            // to a request that went out before it is accepted after it (another attempt of the folder in flight)
            val stop = (r.status == 401 || r.status == 403) && ModelGate.stops(r.status, e.reason ?: e.code)
            val verified = synchronized(folderLock) {
                val v = r.status in 400..499 && r.status != 429 && r.body.isNotEmpty() &&
                    try { c.refusalAccept(r.body, key.keyId) } catch (_: Exception) { false }
                if (v || stop) registry.cleared(dir)
                // lease.json goes whatever its lease's issue time (a clear never passes the monotonic save)
                if (stop) saveRecord(null)
                v
            }
            when {
                // a stop of 15.3, signed (18.4.10): the Core clears its lease and the models go when it verifies. Contract
                // 24.4 rule 3: only a stop the Core verified is a stop (reason `stopped`: `invalid_key` or `server_rejected`
                // by 15.3); one it did not verify is `server_rejected` with its error (reason `refused`)
                stop -> {
                    leaseEvent("refused")
                    val why = e.reason ?: e.code
                    val rules = ai.quranlab.tajweedformer.cloud.ErrorRules
                    throw LeaseFailure("refused", (if (verified) ModelGate.error(why, app.bundleId).at("lease", "stopped", r.status, why)
                        else RecognizerException.ServerRejected(why, app.bundleId).at("lease", "refused", r.status, rules.serverCode(e)))
                        .via(QuranLabApi.ROLE, r.fallback), stopped = verified)
                }
                r.status == 401 && e.code == "invalid_token" && !renewedToken -> { renewedToken = true; invalidateToken() }
                r.status == 401 && e.code == "install_cert_invalid" && !attested -> {
                    attested = true
                    dropCert()
                }
                r.status == 401 && e.code == "challenge_invalid" && !newChallenge -> newChallenge = true
                r.status == 403 && e.code == "lease_integrity_failed" && r.json()?.get("why") == "core" && underPiece && !pieceRetry -> pieceRetry = true
                r.status == 404 && e.code == "build_unknown" -> throw LeaseFailure("other", RecognizerException.ModelUnavailable()).also { leaseEvent(it.kind) }
                r.status == 404 -> throw LeaseFailure("other", RecognizerException.ModelUnavailable()).also { leaseEvent(it.kind) }
                r.status == 400 -> {
                    report("other", "bad_request:model-key")
                    throw refused(r, "other").also { leaseEvent(it.kind) }
                }
                else -> throw refused(r, if (r.status == 403) "attestation" else "other").also { leaseEvent(it.kind) }
            }
        }
    }

    // ---------------------------------------------------------------- grace (18.4.7.1, contract 1c971ab)

    /** The answer the last request of this thread's lease attempt got: its status (0: none at all), whether it is the
     *  API's own refusal (a 4xx other than 429 with a JSON `error` string), and its Retry-After. */
    private class Answer(val status: Int, val apiRefusal: Boolean, val retryAfterS: Int, val fallback: Boolean)
    private val lastAnswer = ThreadLocal<Answer?>()
    private fun answered(status: Int, apiRefusal: Boolean, retryAfterS: Int, fallback: Boolean = false) =
        lastAnswer.set(Answer(status, apiRefusal, retryAfterS, fallback))
    /** 28.5: an answer that every host was unreachable for carries the first host's status and the largest Retry-After. */
    private fun answered(e: CloudException) {
        val a = e.api
        if (a == null) answered(0, false, e.retryAfterMax ?: 0, e.fallback == true)
        else answered(a.status, a.apiRefusal, a.retryAfter ?: 0, e.fallback == true)
    }

    private val graceLock = Object()
    private var attempting = 0
    private var attEndMs = 0L
    private var attUnreachable = false
    private var attFailure: LeaseFailure? = null
    private var attStatus = 0
    private var attReason = "network"
    private var attRetryAfterS = 0
    private var attFallback = false
    private val executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "tajweedformer-lease").apply { isDaemon = true } }

    /**
     * One lease attempt (18.4.7.1): [request], and when it brings no lease, its outcome told to the Core by the answer
     * that ended it: refused for the API's own refusal (after 18.4.10's retries inside it), unreachable for anything
     * else (no answer, a stall, a 5xx, a 429, a redirect, a page that is not the API's, a 2xx that is not a lease the
     * Core accepts). An attempt that asked nothing of the server is not told, nor a verified stop (the Core cleared the
     * lease). The Core keeps it with the lease in its saved state.
     */
    private fun attempt(t: LeaseTarget, roots: (Core) -> Unit): Core {
        synchronized(graceLock) { attempting++ }
        lastAnswer.set(null)
        val holds = ArrayList<Core>(1)
        attemptHolds.set(holds)
        try {
            val c = request(t, roots)
            synchronized(graceLock) { attEndMs = 0; attFailure = null; attRetryAfterS = 0 }
            return c
        } catch (f: LeaseFailure) {
            // an attempt the Client's close cancelled is no failed attempt: the Core is told nothing, the row stays
            if (!closing) {
                told(f)
                if (!f.stopped) failedInRow(lastAnswer.get()?.retryAfterS ?: 0)
            }
            throw f
        } finally {
            attemptHolds.remove()
            for (c in holds) registry.release(c)
            lastAnswer.set(null)
            synchronized(graceLock) { attempting--; graceLock.notifyAll() }
        }
    }

    /** The Cores the attempt on this thread holds ([CoreRegistry.retain]). */
    private val attemptHolds = ThreadLocal<MutableList<Core>?>()

    private fun told(f: LeaseFailure) {
        val a = lastAnswer.get() ?: return
        if (f.stopped) return
        try { core()?.leaseAttempt(a.apiRefusal) } catch (_: Exception) {}
        synchronized(graceLock) {
            attEndMs = clock()
            attUnreachable = !a.apiRefusal
            attFailure = f
            attStatus = a.status
            attReason = graceReason(f, a.status)
            attRetryAfterS = a.retryAfterS
            attFallback = a.fallback
        }
    }

    /** The 24.3 reason of the failure that ended an attempt, as the report of an open in grace names it. */
    private fun graceReason(f: LeaseFailure, status: Int): String {
        val r = try { ai.quranlab.tajweedformer.cloud.ErrorCause.of(f.error).reason } catch (_: Exception) { "" }
        if (r in GRACE_REASONS) return r
        return when {
            status == 0 -> "network"
            status == 429 -> "rate_limited"
            status in 300..399 -> "redirect"
            status in 200..299 -> "invalid"
            else -> "http"
        }
    }

    /**
     * A model open past the lease's `exp` (18.4.7.1): it waits for an attempt already running, an unreachable outcome
     * of this process less than [graceReuseMs] old serves it with no request, else it makes one and waits at most
     * [graceWaitMs] for it. With no outcome by then the Core is told unreachable, and the attempt goes on: its answer
     * is used when it comes (a lease accepted, a refusal told). The Core decides the open.
     */
    private fun pastExp(c: Core, t: LeaseTarget, roots: (Core) -> Unit): Core {
        // 18.4.7.3 item 3: an open in the grace while the row waits opens in it with no attempt; with no grace left, the
        // app's call asks at once (item 4)
        if (rowWaits()) inGrace(c)?.let { return it }
        val deadline = System.nanoTime() + graceWaitMs * 1_000_000
        synchronized(graceLock) {
            while (attempting > 0) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                graceLock.wait(left)
            }
            val f = attFailure
            if (attEndMs > 0 && attUnreachable && f != null && clock() - attEndMs < graceReuseMs) return inGrace(c) ?: throw f
        }
        val run = executor.submit(java.util.concurrent.Callable { attempt(t, roots) })
        return try {
            run.get(maxOf(1, (deadline - System.nanoTime()) / 1_000_000), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            val f = e.cause as? LeaseFailure ?: throw (e.cause ?: e)
            inGrace(c) ?: throw f
        } catch (_: java.util.concurrent.TimeoutException) {
            try { c.leaseAttempt(false) } catch (_: Exception) {}
            synchronized(graceLock) { attStatus = 0; attReason = "timeout" }
            inGrace(c) ?: throw LeaseFailure("offline", RecognizerException.NeedsConnection().at("lease", "timeout"))
        }
    }

    /** The Core in its grace, the open reported (24.1 item 2: lease, grace, the status and reason that ended the
     *  attempt); null when the Core gives none. */
    private fun inGrace(c: Core): Core? {
        if (!(try { c.grace().first } catch (_: Exception) { false })) return null
        val (st, why, fb) = synchronized(graceLock) { Triple(attStatus, attReason, attFallback) }
        try { onGrace(ai.quranlab.tajweedformer.cloud.ErrorCause("lease", "grace", st.takeIf { it > 0 }, null, why, hostRole = QuranLabApi.ROLE, fallback = fb)) } catch (_: Exception) {}
        return c
    }

    /** A certificate the server no longer takes: the next request attests again (with a new key). */
    private fun dropCert() = synchronized(lock) {
        certFile.takeIf { it.isFile }?.let { try { write(previousFile, it.readText()) } catch (_: Exception) {} }
        certFile.delete()
    }

    /**
     * A lease answer (18.4.5): the build's manifest from the answer's `base`, the binary lease accepted by the Core for
     * it, the answer's data piece (18.6.7), the renewed certificate and the record. The piece goes to the Core right
     * after the accept of the same answer, always (spec fix 16, quranlab-core d128af8): the Core keeps it pending, in its
     * saved state too, and puts it in force at the next model open, once that open has made the piece then in force the
     * last good one if a lease asked under it was accepted. Never before the accept (the Core would take a piece no
     * proof was made under as the good one). A piece that does not verify changes nothing.
     */
    private fun accept(c: Core, t: LeaseTarget, r: HttpResponse, clearsBefore: Long = registry.clears(dir)) {
        // no lease answer of 18.4.5: that host was unreachable (28.3); when every host was, the first one's failure
        fun notALease(why: String): Nothing = throw NotTaken(LeaseFailure("other", RecognizerException.DownloadFailed("model-key", why)))
        val o = r.json() ?: notALease("the answer is not JSON")
        val lease = (o["lease"] as? String)?.let { try { b64urlDecode(it) } catch (_: Exception) { null } } ?: notALease("the answer carries no lease")
        // the build's folder: only on a host of the allow-list (28.4, [baseOk]); an answer that names none is no lease
        // answer, and no file is fetched
        val base = (o["base"] as? String)?.takeIf { baseOk(it) } ?: notALease("the lease answer names no valid folder for the build")
        val manifest = t.manifest(base)
        val iat = (o["iat"] as? Double)?.toLong() ?: 0
        val exp = (o["exp"] as? Double)?.toLong() ?: 0
        val refresh = (o["refresh_after"] as? Double)?.toLong() ?: 0
        // read, compare and write under the folder's lock: a lease the server issued before the one in force (another
        // client of this folder took a newer one meanwhile) replaces nothing, neither in the Core nor in lease.json
        synchronized(folderLock) {
            // a stop cleared the folder's lease after this request went out: its lease is never put back (the answer is
            // the request's failure; a new request asks the server, which refuses a stopped key)
            if (registry.clears(dir) != clearsBefore) throw CoreException(Core.E_STOPPED, 0, "qlc_lease_accept")
            val cur = record
            if (cur != null && cur.build == t.build && cur.iat > iat && (try { c.tick().valid } catch (_: Exception) { false })) return
            try {
                c.leaseAccept(lease, manifest, t.build)
            } catch (e: CoreException) {
                // a lease the Core refuses is no lease (28.3: unreachable, the next host); a key store that lost the key is
                // the request's own failure, and so is a Core that is closed (no other host is asked after it)
                if (e.status == Core.E_KEYSTORE || e.status == Core.E_CLOSED) throw e
                throw NotTaken(e)
            }
            (o["piece"] as? String)?.takeIf { it.length in 1..8192 }?.let { p -> try { c.leasePiece(b64urlDecode(p)) } catch (_: Exception) {} }
            (o["install_cert"] as? String)?.let { s -> InstallCert.parse(s)?.let { synchronized(lock) { write(certFile, it.encoded) } } }
            saveRecord(LeaseRecord(t.build, base, exp, refresh, iat = iat), accepted = true)
        }
    }

    /** What to send as `core` (18.4.4, R9): the Core's object as it wrote it, `piece` included (its data piece in force,
     *  0 without one); JSON numbers kept as integers. */
    private fun coreObject(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val m = (Json.parse(json) as Map<String, Any?>).toMutableMap()
        for (k in m.keys.toList()) {
            val v = m[k]
            if (v is Double && v == Math.floor(v)) m[k] = v.toLong()
        }
        return m
    }

    /** A request of the lease that did not succeed; its error names the request's stage. */
    private fun failure(e: CloudException, kind: String, stage: String? = null): LeaseFailure {
        val cause = ai.quranlab.tajweedformer.cloud.ErrorCause.ofCloud(e, stage ?: ai.quranlab.tajweedformer.cloud.ErrorRules.stageOfPath(e.path) ?: "lease")
        return when {
            e.notPermitted -> LeaseFailure("offline", RecognizerException.NetworkNotPermitted(e).at(cause))
            e.offline -> LeaseFailure("offline", RecognizerException.NeedsConnection(e).at(cause))
            else -> LeaseFailure(kind, (ai.quranlab.tajweedformer.TajweedError.ofServerCode(e.api!!.refusal, e.api.minSdk, appId = app.bundleId)
                ?: RecognizerException.DownloadFailed("lease", "HTTP ${e.api.status} ${e.api.code}")).at(cause))
        }
    }

    private fun refused(r: HttpResponse, kind: String, stage: String = "lease"): LeaseFailure {
        val e = r.error
        val body = r.json()
        val why = body?.get("why") as? String
        val integrity = e.code == "lease_integrity_failed"
        val err = when {
            e.code == "sdk_too_old" -> TajweedError.ofServerCode("sdk_too_old", e.minSdk, appId = app.bundleId)
            r.status == 429 || r.status >= 500 || r.status in 300..399 -> RecognizerException.DownloadFailed("lease", "HTTP ${r.status} ${e.code}")
            // 18.4.10: the refusal names the check that failed (an unlocked bootloader, the signing digest, ...); the
            // developer's message says it in words and with the server's tokens
            integrity -> RecognizerException.ServerRejected(integrityDetail(why, body?.get("check") as? String), app.bundleId)
            else -> TajweedError.ofServerCode(e.refusal, e.minSdk, appId = app.bundleId)
        } ?: RecognizerException.ServerRejected(why?.let { "${e.code}:$it" } ?: e.code, app.bundleId)
        // contract 24.3: the attest's refusal is the stage `attest`, the lease's `lease`
        val rules = ai.quranlab.tajweedformer.cloud.ErrorRules
        val reason = when {
            e.code == "lease_integrity_failed" -> "integrity_failed"
            e.code == "attestation_failed" || e.code == "integrity_failed" -> e.code
            // 24.3: the lease route's 503 lease_unavailable, a dependency down with no last good value
            stage == "lease" && r.status == 503 && e.code == "lease_unavailable" -> "lease_unavailable"
            else -> rules.reasonOf(r.status, e.code, e.reason)
        }
        err.at(stage, reason, r.status, when {
            reason == "lease_unavailable" -> "lease_unavailable"
            // the reason integrity_failed already says lease_integrity_failed; error_code says which check (24.2)
            integrity -> integrityErrorCode(why, body?.get("check") as? String) ?: rules.serverCode(e)
            else -> rules.serverCode(e)
        }).via(QuranLabApi.ROLE, r.fallback)
        return LeaseFailure(if (r.status == 429 || r.status >= 500) "offline" else kind, err)
    }

    /**
     * Ends the lease client, once (a second call does nothing). An attempt in flight (the background refresh, a model
     * open's attempt, an app's call) ends first, at most [CLOSE_WAIT_MS]: a lease it brings is accepted, and saved with
     * the Core's state. One still running then goes on and holds its Core until it ends ([CoreRegistry]); whatever it
     * ends with is never a failed attempt ([closing]). The Core is destroyed with its last holder, after its calls in
     * flight ([Core.close]).
     */
    override fun close() {
        val deadline = System.nanoTime() + CLOSE_WAIT_MS * 1_000_000
        synchronized(graceLock) {
            if (closeStarted) return
            closeStarted = true
            while (attempting > 0) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                try { graceLock.wait(left) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        closing = true
        // no interrupt: an attempt still running ends on its own, and holds its Core until then (a lease it brings is
        // accepted and kept, in the Core a new client of the folder shares)
        executor.shutdown()
        synchronized(lock) {
            closed = true
            // the destroy waits for the Core's calls in flight within what is left of the same deadline (then on a thread
            // of its own): close() blocks about CLOSE_WAIT_MS in all
            core?.let { registry.release(it, maxOf(0L, (deadline - System.nanoTime()) / 1_000_000)) }
            core = null
        }
    }

    companion object {
        /** How long [close] waits for a lease attempt in flight before it cancels it. */
        const val CLOSE_WAIT_MS = 3_000L

        /**
         * Whether a lease answer's `base` may be used as the build's folder: https, a host of
         * the allow-list (`quranlab.ai`, `tajweedformer.com`, `quran-lab.com` or a subdomain; 4.0.0 took `*.quranlab.ai`
         * only), a path that starts and ends with `/`, and no port, user, query, fragment, backslash, space or `..`. Its
         * path is fetched through the `models` hosts; no other host is ever asked.
         */
        @JvmStatic
        fun baseOk(b: String): Boolean = HostNames.base(b) != null

        /** 18.4.7.1: the reasons a report of an open in grace names (24.3). */
        val GRACE_REASONS = setOf("network", "timeout", "http", "rate_limited", "redirect", "invalid_answer", "invalid", "lease_unavailable")

        /**
         * 18.4.7.3 (SDK 4.0.2): after every failed attempt (a 5xx, a 429, no answer) the next background attempt waits a
         * time drawn uniformly from [RETRY_MIN_S] to [RETRY_MIN_S] + [RETRY_SPREAD_S], afresh at each failure from
         * [random] (uniform in [0, 1)), so installs that failed together do not come back together. [retryAfterS] (the
         * answer's `Retry-After`, up to [MAX_RETRY_AFTER_S]; 0 for none) is the floor: the wait is the larger of the two,
         * never shorter. [n] (the failures in a row) does not change the wait. Before 4.0.2 a `Retry-After` replaced
         * the step of the backoff, so a short one brought every install back at once.
         */
        @Suppress("UNUSED_PARAMETER")
        fun backoffMs(n: Int, retryAfterS: Int, random: () -> Double): Long {
            val drawnS = RETRY_MIN_S + (minOf(maxOf(random(), 0.0), 0.999999) * RETRY_SPREAD_S).toLong()
            val floorS = retryAfterS.toLong().takeIf { it in 1..MAX_RETRY_AFTER_S } ?: 0L
            return maxOf(drawnS, floorS) * 1000
        }

        /** 18.4.7.3: the shortest wait after a failed attempt, and the spread of the draw above it (seconds). */
        const val RETRY_MIN_S = 600L
        const val RETRY_SPREAD_S = 1200L

        /** The longest `Retry-After` read (seconds); a longer one is none. */
        const val MAX_RETRY_AFTER_S = 21_600L

        /** A token of the server's `why` or `check` (never free text in a message or a report). */
        private fun integrityToken(v: String?): String? = v?.takeIf { Regex("^[a-z][a-z0-9_]{0,31}$").matches(it) }

        /**
         * The detail of a `403 lease_integrity_failed` (18.4.10) for the developer's message: which check failed, in
         * words, then the server's own tokens. `why`: `verdict` (with `check`, the failed check of 18.4.8: `software`,
         * `boot`, `origin`, `purpose`, `package`, `digest`; `recorded` while a failed verdict recorded at a lease
         * stands; or Play Integrity's failure: `device_integrity`, `app_*`, `unlicensed`, `package_mismatch`, ...),
         * `downgrade` or `core`.
         */
        @JvmStatic
        fun integrityDetail(why: String?, check: String?): String {
            val w = integrityToken(why)
            val c = integrityToken(check)
            val words = when (w) {
                "verdict" -> when {
                    c == "boot" -> "this phone's bootloader is unlocked or its boot is not verified, and Quran Lab gives such a phone no lease"
                    c == "digest" -> "the app's signing certificate is not one declared for this app on sdk.quranlab.ai (a device lab that re-signs the APK needs its digest on a development key)"
                    c == "software" -> "the phone's key attestation does not come from secure hardware"
                    c == "origin" -> "the install's key was not made inside the phone's secure hardware"
                    c == "purpose" -> "the install's key does not have the purpose the SDK made it with"
                    c == "package" || c == "package_mismatch" -> "the attested package name is not this app's"
                    c == "recorded" -> "a failed check of this install, recorded in the last 24 hours, still stands"
                    c == "device_integrity" -> "Play Integrity does not confirm this device's integrity"
                    c == "unlicensed" -> "Google Play says this install is not licensed"
                    c != null && c.startsWith("app_") -> "Play Integrity does not recognize this app as Google Play delivers it"
                    else -> "the install's attestation did not pass"
                }
                "downgrade" -> "this install attested with a stronger check before and did not this time"
                "core" -> "the Quran Lab Core in this app could not be verified"
                else -> "the install could not be verified"
            }
            val tokens = listOfNotNull(w?.let { "why $it" }, c?.let { "check $it" })
            return "$words (lease_integrity_failed${tokens.joinToString("") { ", $it" }})"
        }

        /** The report's `error_code` (24.2) of a `403 lease_integrity_failed`: `why` and `check`, as `verdict:boot`, or
         *  `why` alone; null when the body names neither (the server's error string is used then). */
        @JvmStatic
        fun integrityErrorCode(why: String?, check: String?): String? {
            val w = integrityToken(why) ?: return null
            val c = integrityToken(check)
            return listOfNotNull(w, c).joinToString(":").takeIf { it.length <= 32 } ?: w
        }

        /** What `install_sig` signs at POST /v1/attest (18.4.3). */
        @JvmStatic
        fun attestText(challenge: String?, installHex: String): ByteArray =
            "quranlab-attest 1\n${challenge ?: "-"}\n$installHex\n".toByteArray(Charsets.UTF_8)

        /** Play Integrity's requestHash for SDK 4.0 (18.4.3, keyattest.json `play`): base64url of SHA-256 of
         *  `challenge \n install hash hex`, no padding. */
        @JvmStatic
        fun requestHash(challenge: String, installHex: String): String = b64url(sha256("$challenge\n$installHex".toByteArray(Charsets.UTF_8)))
    }
}
