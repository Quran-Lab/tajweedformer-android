package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.BackendChoice
import ai.quranlab.tajweedformer.SdkEvents
import ai.quranlab.tajweedformer.SelfTestResult
import ai.quranlab.tajweedformer.SelfTestSet
import ai.quranlab.tajweedformer.TajweedEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * "CPU first, accelerator as a verified upgrade", the same state machine as the Apple SDK:
 *
 * 1. The exact CPU engine serves from the first session; nothing here blocks it.
 * 2. [start] prepares the accelerator in the BACKGROUND ([AcceleratorEnvironment.schedule]: low priority,
 *    idle and charging when it has to be prepared from scratch): download, load with a timeout
 *    ([loadTimeoutMs]), self-test. A verdict is kept per [AcceleratorKey] (model revision + OS build +
 *    device model + SoC + context): an OS update prepares and tests again.
 * 3. Self-test on the shipped golden set ([SelfTestSet.judge]): tokens, site and word flags identical at
 *    the default presets, no NaN or inf, per-chunk p90 <= 0.5 x chunk_ms and the median below the CPU's.
 * 4. Promotion only at the next session ([engineForNextSession]), never mid-stream.
 * 5. Runtime guards: [watchdog] (latency over budget, NaN, all-blank, an exception) drops to the CPU from the
 *    next take; the crash sentinel ([AcceleratorStore]) disables the accelerator for that key if the process
 *    died during a load or first run; serious thermal state and memory warnings prefer the CPU.
 * 6. The signed config's denylist forces the CPU; every fallback and the time to ready go to [SdkEvents].
 * 7. [state] (StateFlow), [addListener], [awaitReady], [force] for debugging; nothing is needed by default.
 */
class AcceleratorController @JvmOverloads constructor(
    private val env: AcceleratorEnvironment,
    private val store: AcceleratorStore,
    private val preparer: AcceleratorPreparer,
    val key: AcceleratorKey,
    private val loadTimeoutMs: Long = 60_000,
) {
    companion object {
        /** Watchdog trips on one key (revision + OS build + device + SoC + context), across processes, that
         *  disable the accelerator for that key: one trip only for the rest of the process. */
        const val MAX_TRIPS = 2
    }

    private val _state = MutableStateFlow<AcceleratorState>(AcceleratorState.Cpu("not_started"))

    /** The state now (also a flow to collect). */
    val state: StateFlow<AcceleratorState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(AcceleratorState) -> Unit>()
    private val lock = Any()
    @Volatile private var prepared: PreparedAccelerator? = null
    @Volatile private var startedAtMs = 0L
    private val cpuChunkMs = ArrayList<Double>()

    /** A backend forced for debugging (null: automatic). [Backend.CPU] keeps the accelerator unused;
     *  [Backend.NPU] uses it whenever it is [AcceleratorState.Ready], ignoring thermal and memory. */
    @Volatile var forced: Backend? = null

    fun addListener(l: (AcceleratorState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (AcceleratorState) -> Unit) { listeners.remove(l) }

    private fun set(s: AcceleratorState) {
        if (_state.value == s) return
        _state.value = s
        for (l in listeners) try { l(s) } catch (_: Throwable) {}
        SdkEvents.emit { it.onAcceleratorState(s, key) }
        if (s is AcceleratorState.Disabled) {
            SdkEvents.emit { it.onBackendSelected(BackendChoice(Backend.CPU, s.reason, s.detail, key.soc, key.context, key.revision)) }
        }
    }

    /** Suspends until the accelerator is ready or will not be (returns that state), or [timeoutMs] passes (null). */
    suspend fun awaitReady(timeoutMs: Long = Long.MAX_VALUE): AcceleratorState? = withTimeoutOrNull(timeoutMs) {
        state.first { it is AcceleratorState.Ready || it is AcceleratorState.Disabled || it is AcceleratorState.Cpu && it.reason != "not_started" }
    }

    /** CPU chunk compute times (ms) from the CPU's own self-test and sessions: the latency yardstick. */
    fun recordCpuChunk(ms: Double) = synchronized(cpuChunkMs) {
        if (cpuChunkMs.size < 2000) cpuChunkMs.add(ms)
    }

    private fun cpuTimes(): List<Double> = synchronized(cpuChunkMs) { ArrayList(cpuChunkMs) }

    /**
     * Startup: a sentinel left by a dead process disables that key; a stored verdict is reused (a READY one
     * is loaded again in the background without constraints); otherwise preparation is scheduled with the
     * idle / charging constraints. Never blocks.
     */
    fun start() {
        startedAtMs = env.nowMs()
        store.leftSentinel()?.let { (id, step) ->
            store.clearSentinel()
            store.put(id.ifEmpty { key.id }, AcceleratorStore.Record(AcceleratorStore.CRASHED, Reasons.CRASHED, "the process died during $step", env.nowMs(), 0))
        }
        if (env.forcedBackend(key.revision)?.let { it == "exact" || it == "cpu" } == true) {
            return set(AcceleratorState.Disabled(Reasons.DENYLIST, "the signed config forces the CPU for ${key.revision}"))
        }
        preparer.unavailable(key)?.let { return set(AcceleratorState.Cpu(it)) }
        val r = store.record(key)
        when (r?.status) {
            AcceleratorStore.CRASHED, AcceleratorStore.FAILED ->
                return set(AcceleratorState.Disabled(r.reason, r.detail, persistent = true))
            AcceleratorStore.READY -> {
                set(AcceleratorState.Preparing)
                env.schedule(constrained = false) { prepareNow(verified = true) }
            }
            else -> {
                set(AcceleratorState.Preparing)
                env.schedule(constrained = true) { prepareNow(verified = false) }
            }
        }
    }

    /**
     * The preparation itself (what the scheduled work runs; an app may call it to skip the constraints):
     * download, load under the crash sentinel and a timeout, self-test (skipped when this key already
     * passed it), verdict. Blocking; safe to call twice.
     */
    fun prepareNow(verified: Boolean = store.record(key)?.status == AcceleratorStore.READY) {
        synchronized(lock) {
            if (prepared != null || _state.value is AcceleratorState.Disabled) return
            set(AcceleratorState.Preparing)
            try {
                preparer.download(key)
            } catch (e: Exception) {
                // nothing persistent: the next start tries again
                SdkEvents.emit { it.onError("npu_download", e) }
                return set(AcceleratorState.Cpu(Reasons.NOT_DOWNLOADED))
            }
            store.armSentinel(key, "load")
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "tajweedformer-npu-load").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            val p: PreparedAccelerator = try {
                pool.submit<PreparedAccelerator> { preparer.load(key) }.get(loadTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                store.clearSentinel()
                return fail(Reasons.LOAD_TIMEOUT, "no load in $loadTimeoutMs ms")
            } catch (e: ExecutionException) {
                store.clearSentinel()
                val c = e.cause ?: e
                val reason = if (c is LinkageError || c.javaClass.simpleName == "BackendUnavailable") Reasons.RUNTIME_MISSING else Reasons.LOAD_FAILED
                // a missing runtime is the app's packaging, not this device: not persisted
                return if (reason == Reasons.RUNTIME_MISSING) set(AcceleratorState.Cpu(reason)) else fail(reason, "${c.javaClass.simpleName}: ${c.message}")
            } finally {
                pool.shutdown()
            }
            if (!verified) {
                store.armSentinel(key, "first run")
                val t0 = env.nowMs()
                val verdict = try {
                    SelfTestSet.judge(p.expected, p.selfTest(), cpuTimes(), p.chunkMs, planted = p.planted)
                } catch (e: Exception) {
                    SelfTestSet.Verdict(false, "exception", "${e.javaClass.simpleName}: ${e.message}", Double.NaN, Double.NaN, Double.NaN)
                }
                store.clearSentinel()
                SdkEvents.emit { it.onSelfTest(SelfTestResult(Backend.NPU, verdict.passed, "${verdict.reason}: ${verdict.detail}", env.nowMs() - t0, key.soc, key.context, key.revision)) }
                if (!verdict.passed) {
                    try { p.close() } catch (_: Exception) {}
                    return fail(Reasons.SELF_TEST, "${verdict.reason}: ${verdict.detail}")
                }
            } else {
                store.clearSentinel()
            }
            prepared = p
            val ttr = env.nowMs() - startedAtMs
            store.put(key.id, AcceleratorStore.Record(AcceleratorStore.READY, "ok", "", env.nowMs(), ttr))
            set(AcceleratorState.Ready(key.soc, ttr))
        }
    }

    private fun fail(reason: String, detail: String) {
        store.put(key.id, AcceleratorStore.Record(AcceleratorStore.FAILED, reason, detail, env.nowMs(), 0))
        set(AcceleratorState.Disabled(reason, detail, persistent = true))
    }

    /**
     * The engine a NEW session should use: the accelerator when it is [AcceleratorState.Ready] and nothing
     * prefers the CPU (forced CPU, thermal, memory, the denylist), else null (the CPU) with the reason in
     * [lastCpuReason]. Called at session start only, so a session never changes engine mid-stream.
     */
    fun engineForNextSession(cpu: TajweedEngine, sampleRate: Int, onFallback: (String) -> Unit): TajweedEngine? {
        val p = prepared
        val s = _state.value
        lastCpuReason = when {
            forced == Backend.CPU -> Reasons.FORCED_CPU
            s !is AcceleratorState.Ready || p == null -> (s as? AcceleratorState.Disabled)?.reason ?: (s as? AcceleratorState.Cpu)?.reason ?: s.key
            env.forcedBackend(key.revision)?.let { it == "exact" || it == "cpu" } == true -> Reasons.DENYLIST
            forced != Backend.NPU && env.thermalSevere() -> Reasons.THERMAL
            forced != Backend.NPU && env.lowMemory() -> Reasons.MEMORY
            else -> null
        }
        return if (lastCpuReason == null) p!!.engine(cpu, sampleRate, onFallback) else null
    }

    /** Why the last [engineForNextSession] gave the CPU (null: it gave the accelerator). */
    @Volatile var lastCpuReason: String? = null
        private set

    /**
     * The runtime watchdog tripped during a session (`latency`, `non_finite`, `all_blank`, `exception`): the
     * accelerator is not used again in this process (the next take runs on the CPU; the current one already
     * moved there when it threw). It is prepared again at the next start.
     */
    fun watchdog(reason: String, detail: String = "") {
        val trips: Int
        synchronized(lock) {
            if (_state.value is AcceleratorState.Disabled) return
            // the take in flight may still be using it: closed with the controller
            prepared?.let { retired.add(it) }
            prepared = null
            // every trip is kept in the key's verdict, across processes
            val r = store.record(key) ?: AcceleratorStore.Record(AcceleratorStore.READY, "ok", "", env.nowMs(), 0)
            store.put(key.id, r.copy(trips = listOf(AcceleratorStore.Trip(reason, env.nowMs()))))
            trips = store.record(key)?.trips?.size ?: 1
        }
        if (trips >= MAX_TRIPS) {
            // the second trip on this revision + OS build: off until either changes, like a crash
            val d = "$trips watchdog trips (last: $reason${if (detail.isEmpty()) "" else ", $detail"})"
            val r = store.record(key)!!
            store.put(key.id, r.copy(status = AcceleratorStore.FAILED, reason = Reasons.WATCHDOG_REPEATED, detail = d, atMs = env.nowMs(), trips = emptyList()))
            set(AcceleratorState.Disabled(Reasons.WATCHDOG_REPEATED, d, persistent = true))
        } else {
            set(AcceleratorState.Disabled("${Reasons.WATCHDOG}_$reason", detail, persistent = false))
        }
    }

    /** Watchdog trips recorded for this key (all processes). */
    val trips: List<AcceleratorStore.Trip> get() = store.record(key)?.trips.orEmpty()

    private val retired = ArrayList<PreparedAccelerator>()

    fun close() {
        synchronized(lock) {
            for (p in retired + listOfNotNull(prepared)) try { p.close() } catch (_: Exception) {}
            retired.clear()
            prepared = null
        }
    }
}
