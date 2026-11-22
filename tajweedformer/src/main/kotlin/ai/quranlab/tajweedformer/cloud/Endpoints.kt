package ai.quranlab.tajweedformer.cloud

/**
 * Quran Lab's hosts: the allow-list, a lease's `base`, and
 * a signed config's `endpoints`. Every host the SDK sends a key, a token or a lease request to passes [allowed].
 */
internal object HostNames {
    /** 28.1: Quran Lab's own domains. */
    val SUFFIXES = listOf("quranlab.ai", "tajweedformer.com", "quran-lab.com")

    /** 28.1, in this order, built into the SDK: the first hosts, then the standby hosts (28.9); the `-a` hosts were added
     *  last, so every earlier name keeps its place. (`web_frame` is the web SDK's role only.) */
    val API = listOf("api.quranlab.ai", "api-b.tajweedformer.com", "api-g.tajweedformer.com", "api-a.tajweedformer.com")
    val MODELS = listOf("cdn.quranlab.ai", "cdn-b.tajweedformer.com", "cdn-g.tajweedformer.com", "cdn-a.tajweedformer.com")

    /** 28.2: a config's list of more entries than this for a role is refused whole. */
    const val MAX_CONFIG_HOSTS = 8

    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    /** ASCII lowercase only (a host name is ASCII; anything else stays as it is and fails [allowed]). */
    fun lower(s: String): String = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    /**
     * 28.1: labels of `a-z`, `0-9` and `-` (1 to 63 characters, not starting or ending with `-`), at most 253 characters,
     * and one of [SUFFIXES] or under one on a label boundary. Not an IP literal, a port, user information, a trailing
     * dot or non-ASCII.
     */
    fun allowed(host: String?): Boolean {
        if (host == null || host.length !in 1..253) return false
        val h = lower(host)
        if (!h.split('.').all { LABEL.matches(it) }) return false
        return SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /**
     * 28.4: the host (ASCII lowercase) and the path of a lease answer's `base`, or null when it is not one: `https://`,
     * an allowed host, a path that starts and ends with `/`, printable ASCII, at most 511 characters, and no port, user
     * information, query, fragment, backslash, space or `..`.
     */
    fun base(b: String?): Pair<String, String>? {
        if (b == null || b.length !in 9..511 || !b.startsWith("https://") || !b.endsWith("/")) return null
        if (b.any { it.code !in 0x21..0x7e || it in "?#@\\" } || ".." in b) return null
        val rest = b.substring(8)
        val i = rest.indexOf('/')
        val host = rest.substring(0, i)
        if (':' in host || !allowed(host)) return null
        return lower(host) to rest.substring(i)
    }

    /**
     * 28.2: a role's hosts while a config is in force: its `endpoints` list for the role, each entry a host of the
     * allow-list (ASCII lowercase, repeats dropped), which may reorder, disable and add hosts; [builtIn] when the config
     * names none, gives no array, more than [MAX_CONFIG_HOSTS] entries, or nothing that is allowed. [endpoints] is null
     * when no config is in force.
     */
    fun listFor(role: String, builtIn: List<String>, endpoints: Any?): List<String> {
        val e = (endpoints as? Map<*, *>)?.get(role) as? List<*> ?: return builtIn
        if (e.size > MAX_CONFIG_HOSTS) return builtIn
        val out = LinkedHashSet<String>()
        for (h in e) if (h is String && allowed(h)) out.add(lower(h))
        return out.toList().ifEmpty { builtIn }
    }
}

/** 28.3: how one host's answer ends a request, or does not. */
internal enum class AnswerClass { TAKEN, REFUSED, SLOW_DOWN, UNREACHABLE }

/**
 * One role's hosts in force and its sticky host `P` (28.3), in memory for the process. [builtIn] is the SDK's list, or
 * a test build's replacement (a stand-in's `127.0.0.1:port`).
 */
internal class HostRole(val name: String, builtIn: List<String>) {
    val builtIn: List<String> = builtIn.toList()

    init {
        require(this.builtIn.isNotEmpty()) { "a role has at least one host" }
    }

    @Volatile var hosts: List<String> = this.builtIn
        private set
    private var p: String = this.builtIn[0]
    private var since: Long = 0

    /** 28.2, 28.3 item 5: a list that changes starts again at its first host. */
    @Synchronized fun setHosts(list: List<String>) {
        if (list.isEmpty() || list == hosts) return
        hosts = list.toList()
        p = hosts[0]
        since = 0
    }

    /** The hosts a request at [nowMs] asks, in order, and whether it is the probe back to the first host (28.3 item 1). */
    @Synchronized fun order(nowMs: Long): Pair<List<String>, Boolean> {
        if (p != hosts[0] && nowMs - since >= PROBE_MS) return hosts to true
        return (listOf(p) + hosts.filter { it != p }) to false
    }

    /** 28.3 item 5: the host whose answer ended a request becomes `P`; its time is now when it changed or on a probe. */
    @Synchronized fun settle(host: String, nowMs: Long, probe: Boolean) {
        if (host in hosts && (host != p || probe)) {
            p = host
            since = nowMs
        }
    }

    /** `P`, and the time it became preferred (tests). */
    @Synchronized fun preferred(): Pair<String, Long> = p to since

    /** Tests: `P` set as the vectors set it. */
    @Synchronized fun prefer(host: String, sinceMs: Long) {
        p = host
        since = sinceMs
    }

    companion object {
        /** 28.3 item 1: 30 minutes after `P` became preferred, the first host is asked first again. */
        const val PROBE_MS = 1_800_000L
    }
}

/**
 * Thrown by a route's check of a 2xx answer (or a 304) that is not the answer the route asks for (28.3: a config that
 * does not verify, a lease the Core refuses, a challenge without one): that host was unreachable. [failure] is what the
 * request fails with when every host was unreachable and this host was the first; null: the answer itself.
 */
internal class NotTaken(val failure: Throwable? = null) : Exception(failure?.message) {
    override fun fillInStackTrace(): Throwable = this
}

/** The last request of the process that found its first host unreachable (28.7 `lastFailover`). */
internal class Failover(val role: String, val from: String, val to: String?, val reason: String, val status: Int?, val atSec: Long,
                        /** The stage of the request (24.3), or null for one of no stage (an issue report). */
                        val stage: String?) {
    fun map(): Map<String, Any?> = linkedMapOf("role" to role, "from" to from, "to" to to, "reason" to reason, "status" to status, "at" to atSec)
}

/** One host's answer to a request of [Endpoints.request]: it ends the request ([value], [cls]), or the host was unreachable. */
internal class HostAnswer<out T> private constructor(val cls: AnswerClass, val value: T?, val failure: Throwable?, val reason: String?,
                                                    val status: Int?, val retryAfter: Int?,
                                                    /** The host was asked (a request went out); false: a path held there (28.11 item 2). */
                                                    val asked: Boolean) {
    companion object {
        /** [asked] false: the answer is a held one (a held 429, 28.11 item 2), given without a request to the host. */
        fun <T> ends(cls: AnswerClass, value: T, asked: Boolean = true): HostAnswer<T> = HostAnswer(cls, value, null, null, null, null, asked)

        /** Unreachable: the answer [value] or the error [failure] is the request's when this host was the first and every
         *  host was unreachable; [reason] its 24.3 reason (network, timeout, http, redirect, invalid_answer). [asked] false:
         *  the host was not asked (a path held there), and no `failover` report names it (28.7). */
        fun <T> unreachable(value: T?, failure: Throwable?, reason: String, status: Int?, retryAfter: Int? = null, asked: Boolean = true): HostAnswer<T> =
            HostAnswer(AnswerClass.UNREACHABLE, value, failure, reason, status, retryAfter, asked)
    }
}

/** What a request across the hosts came to: the answer that ended it ([by] its host), or the first host's when every host
 *  was unreachable ([by] null), the hosts in the order they were taken, and then the largest Retry-After any host gave
 *  (28.5). [requested]: the hosts a request went to (a model file's path held on a host is not asked there, 28.11 item 2). */
internal class Asked<out T>(val answer: HostAnswer<T>, val asked: List<String>, val by: String?, val retryAfterMax: Int?,
                            val requested: List<String> = asked) {
    /** 28.7: more than one host was asked (a request went to each). */
    val fallback: Boolean get() = requested.size > 1
}

/**
 * The SDK's endpoint sets (section 28): the `api` and `models` roles, the sticky host of each, the hosts a signed config
 * names while it is in force ([configured]), and one request across a role's hosts ([request]). One per Client (the
 * process's). [origin] makes a host's origin: `https://<host>` (28.1); a test build maps hosts to its stand-ins.
 */
internal class Endpoints(
    val api: HostRole = HostRole("api", HostNames.API),
    val models: HostRole = HostRole("models", HostNames.MODELS),
    private val origin: (String) -> String = { "https://$it" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The `endpoints` of the config in force, or null when none is in force (28.2). Read at every request's start. */
    @Volatile var configured: () -> Any? = { null }

    /** A request ended on another host after its first host was unreachable (28.7: the silent `<stage>`, `failover`). */
    @Volatile var onFailover: (Failover) -> Unit = {}

    /** 34.6: where the counts of each model request to a `models` host go (the Client's telemetry); null: nowhere. */
    @Volatile var meter: ModelHostMeter? = null

    /** 28.11 items 2 and 3: the holds of model files per path and host (the Client's, kept across restarts); null: none. */
    @Volatile var holds: RouteHolds? = null

    /** One model request to [host] for [path] (the URL's path): measured (34.6) and held (28.11 item 2), or null (then
     *  nothing holds it, and the request goes out). [sent]: the request went out to the host. */
    fun tap(host: String, path: String?, lastByte: (String) -> Unit = {}, sent: () -> Unit = {}): HostTap? {
        val m = meter
        val h = holds
        if (m == null && (h == null || path == null)) return null
        return HostTap(m, host, lastByte, h, path?.let { RouteHolds.modelKey(host, it) }, sent)
    }

    @Volatile var lastFailover: Failover? = null
        private set

    fun originOf(host: String): String = origin(host)

    fun now(): Long = clock()

    /** The lists in force now (28.2): the config's while it is in force, else the built-in ones. */
    fun refresh() {
        val ep = try { configured() } catch (_: Exception) { null }
        for (r in listOf(api, models)) r.setHosts(HostNames.listFor(r.name, r.builtIn, ep))
    }

    /** 28.7, 25.3: the host each role's next request goes to first, and the last failover. */
    fun diagnosis(): Map<String, Any?> {
        refresh()
        val now = clock()
        return linkedMapOf("api" to api.order(now).first[0], "models" to models.order(now).first[0], "lastFailover" to lastFailover?.map())
    }

    /**
     * One request of 28.3 on [role]: `P` first, then the others in list order (the first host first at the probe), with
     * [first] (a lease base's host that is not one of the role's, 28.4) before them all. Each host is given to [ask] at
     * once after the one before it was unreachable; the first answer that is taken, refused or a slow down ends the
     * request and its host becomes `P`. [stage] names the request in the `failover` report.
     */
    fun <T> request(role: HostRole, stage: String?, first: String? = null, ask: (host: String, origin: String) -> HostAnswer<T>): Asked<T> {
        refresh()
        val (order0, probe) = role.order(clock())
        val order = if (first != null && first !in role.hosts) listOf(first) + order0 else order0
        val asked = ArrayList<String>()
        val requested = ArrayList<String>()
        var head: HostAnswer<T>? = null
        var retryAfter: Int? = null
        for (h in order) {
            asked += h
            val a = ask(h, origin(h))
            if (a.asked) requested += h
            if (a.cls != AnswerClass.UNREACHABLE) {
                role.settle(h, clock(), probe)
                // 28.7: a report needs the first host asked and the host it ended on asked (not a held 429's answer)
                head?.let { f -> failedOver(Failover(role.name, asked[0], h, f.reason ?: "network", f.status, clock() / 1000, stage), report = f.asked && a.asked) }
                return Asked(a, asked, h, null, requested)
            }
            if (head == null) head = a
            a.retryAfter?.let { r -> retryAfter = maxOf(retryAfter ?: 0, r) }
        }
        val f = head!!
        failedOver(Failover(role.name, asked[0], null, f.reason ?: "network", f.status, clock() / 1000, stage))
        return Asked(f, asked, null, retryAfter, requested)
    }

    /** The host that ended a model file's attempts (28.4: each resume of 16.2 on the next host) becomes `P`. */
    fun settled(role: HostRole, host: String, probe: Boolean) = role.settle(host, clock(), probe)

    /**
     * A request whose first host was unreachable: noted for diagnose(), and when another host ended it ([Failover.to]),
     * the silent `failover` report of its stage. [report] false: the first host, or the host the request ended on, was not
     * asked (a model file's path held there, 28.11 item 2), and no report goes out (28.7: both were asked).
     */
    fun failedOver(f: Failover, report: Boolean = true) {
        lastFailover = f
        if (report && f.to != null && f.stage in FAILOVER_STAGES) try { onFailover(f) } catch (_: Exception) {}
    }

    companion object {
        /** 28.7: the stages whose requests go to a host of 28.1, each with its silent `failover` report. */
        val FAILOVER_STAGES = setOf("config", "register", "attest", "lease", "manifest", "download", "sharing", "events")

        /**
         * A Client's endpoints: the built-in sets, or, for a test build's API URL (a stand-in), that one `api` host and the
         * built-in `models` set.
         */
        @JvmStatic
        fun of(apiUrl: String, clock: () -> Long = System::currentTimeMillis): Endpoints {
            val u = apiUrl.trimEnd('/')
            if (u == QuranLabApi.DEFAULT_API) return Endpoints(clock = clock)
            val name = u.substringAfter("://")
            return Endpoints(HostRole("api", listOf(name)), origin = { h -> if (h == name) u else "https://$h" }, clock = clock)
        }

        /** A test build's hosts: each role's names, and where each name is reached (28.1: a test build may replace them). */
        @JvmStatic
        fun mapped(api: List<String>, models: List<String>, origins: Map<String, String>, clock: () -> Long = System::currentTimeMillis): Endpoints =
            Endpoints(HostRole("api", api), HostRole("models", models), { h -> origins[h] ?: "https://$h" }, clock)
    }
}
