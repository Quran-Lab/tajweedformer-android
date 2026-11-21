package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.Endpoints
import ai.quranlab.tajweedformer.cloud.ErrorCause
import ai.quranlab.tajweedformer.cloud.Failover
import ai.quranlab.tajweedformer.cloud.HostNames
import java.io.File
import java.io.IOException

/**
 * Where the model requests of a build's folder go: the folder is
 * the path of the lease answer's `base`, and every request for it (the manifest, each file, each range) goes to the
 * `models` hosts in their failover order, the base's own host first when it is not one of them. A folder that is not
 * on a host of Quran Lab's allow-list (a test's stand-in, the device speed check's own folder) is one fixed URL, asked
 * there only, as before 4.0.1.
 */
internal class ModelHosts private constructor(
    private val endpoints: Endpoints?,
    /** The base's host (ASCII lowercase); for a fixed folder, the folder URL itself. */
    val baseHost: String,
    /** The folder's path, starting and ending with `/`; empty for a fixed folder. */
    val path: String,
) {
    val fixed: Boolean get() = endpoints == null

    /** The hosts of one file's attempts, from `P` now (28.4: the next file of the build starts at the host that answered
     *  last), or the fixed folder. */
    fun plan(): FilePlan {
        val e = endpoints ?: return FilePlan(listOf(baseHost), listOf(baseHost), baseHost, false, null)
        e.refresh()
        val (order0, probe) = e.models.order(e.now())
        val order = if (baseHost !in e.models.hosts) listOf(baseHost) + order0 else order0
        return FilePlan(order, order.map { e.originOf(it) + path }, baseHost, probe, e)
    }

    companion object {
        /** The hosts of the folder [base] (a URL ending in `/`). */
        fun of(base: String, endpoints: Endpoints?): ModelHosts {
            val parts = endpoints?.let { HostNames.base(base) } ?: return ModelHosts(null, base, "")
            return ModelHosts(endpoints, parts.first, parts.second)
        }
    }
}

/**
 * One file's attempts across the model hosts (28.4): the attempt that 16.2's retry rule makes after its `g`-th failure
 * goes to host `g` of the order, cycling, so each resume after an unreachable failure asks the next host; 16.2's five
 * resumes and pauses are unchanged. [folders] are the folder URLs of [hosts], in the same order.
 */
internal class FilePlan(val hosts: List<String>, private val folders: List<String>, private val baseHost: String, private val probe: Boolean,
                        private val endpoints: Endpoints?) {
    /** More than one host: a failure that is not the model host's own answer goes to the next one (28.3). */
    val many: Boolean get() = hosts.size > 1

    fun host(g: Int): String = hosts[g % hosts.size]

    fun url(g: Int, path: String): String = folders[g % folders.size] + path

    @Volatile private var firstReason: String? = null
    @Volatile private var firstStatus: Int? = null

    /** The attempt of generation [g] failed with [x]: the first host's failure is what a failover report names. */
    fun failed(g: Int, x: Throwable) {
        if (g != 0 || firstReason != null) return
        val c = try { ErrorCause.of(x) } catch (_: Exception) { null }
        firstReason = c?.reason?.takeIf { it in FIRST_REASONS } ?: if (x.cause is IOException) "network" else "http"
        firstStatus = c?.httpStatus
    }

    /** 28.7 `fallback`: the file was asked of more than one host by its attempt of generation [g]. */
    fun fallback(g: Int): Boolean = many && g > 0

    /** The attempt of generation [g] ended the file (taken, refused or a slow down): its host becomes `P`, and when the
     *  first host had been unreachable, the silent `failover` report of [stage]. */
    fun ended(g: Int, stage: String) {
        val e = endpoints ?: return
        val h = host(g)
        e.settled(e.models, h, probe)
        if (g > 0 && many && h != hosts[0]) firstReason?.let { e.failedOver(Failover("models", hosts[0], h, it, firstStatus, e.now() / 1000, stage)) }
    }

    /** Every attempt failed without an answer of a host: the last failover names no host that answered. */
    fun unanswered(stage: String) {
        val e = endpoints ?: return
        firstReason?.let { e.failedOver(Failover("models", hosts[0], null, it, firstStatus, e.now() / 1000, stage)) }
    }

    /**
     * The validator of a part (`<path>.part.v`): its strong ETag and the host that gave it. 28.4: it is sent as If-Range
     * only to that host; on another host the Range goes alone, and the size and SHA-256 decide. A part of SDK 4.0.0 (the
     * ETag alone) came from the folder's own host. On a fixed folder the ETag alone is written, as before.
     */
    fun readValidator(f: File): Pair<String, String>? = try {
        f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.lines()?.let { l ->
            l[0].trim() to (l.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: baseHost)
        }
    } catch (_: IOException) {
        null
    }

    fun writeValidator(f: File, etag: String, g: Int) = f.writeText(if (endpoints == null) etag else "$etag\n${host(g)}")

    private companion object {
        /** 28.7: the reasons of a first host's failure a failover names. */
        val FIRST_REASONS = setOf("network", "timeout", "http", "redirect", "invalid_answer")
    }
}
