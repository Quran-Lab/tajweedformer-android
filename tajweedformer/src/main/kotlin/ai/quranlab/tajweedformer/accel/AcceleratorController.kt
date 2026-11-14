package ai.quranlab.tajweedformer.accel

import ai.quranlab.tajweedformer.AcceleratorFailoverEvent
import ai.quranlab.tajweedformer.AcceleratorIncident
import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.BackendChoice
import ai.quranlab.tajweedformer.PromotionEvent
import ai.quranlab.tajweedformer.SdkEvents
import ai.quranlab.tajweedformer.SelfTestResult
import ai.quranlab.tajweedformer.SelfTestSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** The pipeline's time limits and waits, in one place. */
internal data class PromotionTimeouts(
    /** Building and loading the accelerator program (Android: LiteRT with the vendor dispatch). */
    val compileMs: Long = 5 * 60_000L,
    val warmupMs: Long = 60_000L,
    /** The golden run (every clip through the accelerator and the CPU). */
    val goldenMs: Long = 10 * 60_000L,
    /** The first install: the pipeline starts when the first session ends, or this long after it started. */
    val idleDelayMs: Long = 30_000L,
    /** A stage waiting for its conditions looks again after this long. */
    val recheckMs: Long = 15 * 60_000L,
    /** How long a starting session waits for the golden run to hand back the engine's one stream (one piece). */
    val yieldMs: Long = 2_000L,
    /** Retries for the same key after the first attempt: 1 h, 6 h, 24 h, then no more. */
    val backoffMs: List<Long> = listOf(3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L),
    /** A failover's retry comes at least this long after it (an exception in a session: the 2.0.1 rule). */
    val failoverRetryMs: Long = 24 * 3_600_000L,
    /** A transient demotion (missed limits in [missSessions] sessions within [missWindowMs], one confirmed blank
     *  disagreement): the pause before the same verified build serves again. Not the pipeline's retry budget. */
    val transientBackoffMs: Long = 6 * 3_600_000L,
    val missWindowMs: Long = 24 * 3_600_000L,
    val missSessions: Int = 3,
    /** Confirmed blank disagreements for one key that turn the build off for good. */
    val blankDisagreementsOff: Int = 2,
)

/**
 * CPU first, then a verified, seamless promotion to the accelerator, with in-session failover. One persisted state
 * machine per [AcceleratorKey] (model revision + context + accelerator build + OS build + device model), the record in
 * [AcceleratorStore]:
 *
 * 1. The exact CPU engine serves every session until the accelerator is promoted and warm; nothing here blocks one.
 *    On a first install the pipeline starts only after the first session (when it ends, or [PromotionTimeouts.idleDelayMs]
 *    after it started); on a later launch, at launch, at low priority.
 * 2. Stages: `download` (unmetered network), `compile` ([PromotionTimeouts.compileMs]), `warmup`, `golden` (every
 *    self-test clip through the accelerator path and the CPU engine: the CPU must equal the shipped expected decisions,
 *    the accelerator the CPU's), `measure` (speedup and the deadline: telemetry, never a reason to stay on the CPU),
 *    `promote`. The record is written after each stage; a restart resumes at the next one. Warm-up, golden and measure
 *    run only while no session is active (a session starting aborts them without a failure). The pipeline waits
 *    (no failure) in battery saver, below 20 % battery unless charging, or above light thermal throttling.
 * 3. The swap: only at a session start ([engineForNextSession]), to an accelerator already loaded and warm.
 * 4. Failures keep the CPU and record the stage and reason; retries for the key after 1 h, 6 h, 24 h, then none;
 *    golden_mismatch, cpu_mismatch and non_finite are never retried for the key. One attempt in flight, at most
 *    one per launch. The crash sentinel is armed around every compile, load, warm-up, golden run and a session's
 *    first accelerator call, with whether the app is in the foreground ([stopFound] reads it at the next launch:
 *    SDK 3.1.5).
 * 5. In a session ([FailoverEngine]): a failed chunk switches the take to the CPU at once. What it does to the key
 *    depends on its cause ([failingOver]): a missed limit nothing, unless it happened in 3 sessions within 24 h (then
 *    a 6 h pause); a confirmed blank disagreement a 6 h pause, the second one for the key off for good; NaN, inf or
 *    out-of-range values off for good; an exception a retryable `failover` at least 24 h later (golden test again). A
 *    paused key stays promoted and verified: after the pause it loads, warms up and serves again.
 * 6. Every attempt that ends, every failover and every blank the CPU confirmed goes to [SdkEvents] (`promotions`
 *    telemetry, incident reports).
 */
internal class AcceleratorController @JvmOverloads constructor(
    private val env: AcceleratorEnvironment,
    private val store: AcceleratorStore,
    private val preparer: AcceleratorPreparer,
    val key: AcceleratorKey,
    val timeouts: PromotionTimeouts = PromotionTimeouts(),
    /** This launch's id ([LAUNCH]; tests give each simulated launch its own). */
    private val launch: String = LAUNCH,
) : FailoverListener {
    companion object {
        /** This process's launch (one attempt per launch and key). */
        @JvmField val LAUNCH: String = java.util.UUID.randomUUID().toString()

        /** Retries after the first attempt. */
        const val MAX_RETRIES = 3

        /** Keys whose session sentinel was handled in this process (a session's first call is guarded once). */
        private val firstCallGuarded = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        /** Stops in the background in a row at one stage that count as a stop in the foreground. */
        const val BACKGROUND_STOPS_COUNTED = 3

        /** Counted stops at one preparation stage that end the key. */
        const val PREPARATION_STOPS_FINAL = 2
    }

    private val activationHooked = AtomicBoolean(false)
    private val foregroundHooked = AtomicBoolean(false)

    private val _state = MutableStateFlow<AcceleratorState>(AcceleratorState.Cpu("not_started"))

    /** The state now (also a flow to collect). */
    val state: StateFlow<AcceleratorState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(AcceleratorState) -> Unit>()
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    /** Held while a stage uses the engine's one stream (warm-up, golden): a starting session takes it over. */
    private val engineLock = ReentrantLock()
    @Volatile private var held: AcceleratorHandle? = null
    private val prepared: PreparedAccelerator? get() = held?.accelerator
    /** A stage thread was abandoned while holding the engine lock: sessions no longer wait for it. */
    @Volatile private var stuck = false
    @Volatile private var startedAtMs = 0L
    /** The first install waits for its first session. */
    @Volatile private var armed = false
    /** A stage stopped for a session: run again when sessions end. */
    @Volatile private var resumeWhenIdle = false
    /** The chunk limits from warm chunks (the golden run's, then the sessions'). */
    @Volatile private var limits = ChunkLimits()
    private val engines = CopyOnWriteArrayList<FailoverEngine>()

    /** Audio replayed through the CPU before a failed chunk ([Failover.REPLAY_WINDOW_MS]; measurements vary it). */
    @Volatile internal var replayWindowMs: Int = Failover.REPLAY_WINDOW_MS

    /** Energetic blank output this long is checked on the CPU ([Failover.BLANK_RUN_MS]; tests on short clips shorten it). */
    @Volatile internal var blankRunMs: Int = Failover.BLANK_RUN_MS

    /** A backend forced for debugging (null: automatic). [Backend.CPU] keeps the accelerator unused; [Backend.NPU]
     *  uses it whenever it is [AcceleratorState.Ready], ignoring thermal and memory. */
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

    /** Suspends until the accelerator is ready or will not be in this launch (returns that state), or [timeoutMs] passes (null). */
    suspend fun awaitReady(timeoutMs: Long = Long.MAX_VALUE): AcceleratorState? = withTimeoutOrNull(timeoutMs) {
        state.first { it is AcceleratorState.Ready || it is AcceleratorState.Disabled || it is AcceleratorState.Cpu && it.reason != "not_started" }
    }

    /** The persisted record of this key (null before anything ran). */
    val record: PromotionRecord? get() = store.record(key)

    // ------------------------------------------------------------------------------------------------- launch

    /**
     * At launch (never blocks): a sentinel left by a process that ended is read ([stopFound]); the denylist and an
     * unavailable accelerator keep the CPU; a promoted key is loaded and warmed again in the background; a failed key
     * waits for its retry time; otherwise the pipeline is scheduled (after the first session on a first install).
     */
    fun start() {
        startedAtMs = env.nowMs()
        if (activationHooked.compareAndSet(false, true)) try { env.onAppActive { appActivated() } } catch (_: Exception) {}
        store.left()?.let { s ->
            store.clearSentinel()
            stopFound(s.keyId.ifEmpty { key.id }, s.stage.ifEmpty { Stages.COMPILE }, s.foreground)
        }
        // while a stage of this process runs, its sentinel says whether the app is in the foreground, also when that
        // changes (hooked after the last process's sentinel is read, which keeps the state that process recorded)
        if (foregroundHooked.compareAndSet(false, true)) try { env.onForegroundChange { fg -> store.updateSentinel(fg) } } catch (_: Exception) {}
        denyReason()?.let { return denyHit(it) }
        preparer.unavailable(key)?.let { return set(AcceleratorState.Cpu(it)) }
        val r = store.record(key) ?: PromotionRecord()
        val now = env.nowMs()
        when (r.status) {
            PromotionRecord.PROMOTED -> {
                if (r.pausedUntilMs > now) {
                    // a transient demotion: the same verified build serves again after the pause
                    set(AcceleratorState.Disabled(Reasons.FAILOVER, r.pauseCause, persistent = false, retryAtMs = r.pausedUntilMs))
                    schedule(r.pausedUntilMs - now, false)
                    return
                }
                if (!programsUnchanged(r)) {
                    // the verified program is gone or another one is in place: the whole pipeline again, golden test included
                    store.put(key, r.copy(status = PromotionRecord.PENDING, stage = "", updatedAtMs = now))
                    set(AcceleratorState.Preparing(Stages.DOWNLOAD))
                } else {
                    set(AcceleratorState.Preparing(Stages.COMPILE))
                }
                schedule(0, false)
            }
            PromotionRecord.FINAL -> set(AcceleratorState.Disabled(r.reason, r.detail, persistent = true))
            PromotionRecord.FAILED -> {
                set(AcceleratorState.Disabled(r.reason, r.detail, persistent = false, retryAtMs = r.nextAttemptAtMs))
                // the retry is this launch's one attempt, at its time (or now when it is due)
                if (r.launch != launch) schedule(maxOf(0, r.nextAttemptAtMs - now), false)
            }
            else -> {
                if (!store.sessionStarted) {
                    armed = true
                    set(AcceleratorState.Preparing(Stages.after(r.stage), "first_session"))
                } else {
                    set(AcceleratorState.Preparing(Stages.after(r.stage)))
                    schedule(0, false)
                }
            }
        }
    }

    /** Why the signed config keeps this accelerator off now (`denylist` with `backend_forced`, or an `accel_deny` rule), or null. */
    private fun denyReason(): String? {
        if (env.forcedBackend(key.revision)?.let { it == "exact" || it == "cpu" } == true) return "the signed config forces the CPU for ${key.revision}"
        return try { env.accelDenied(key.revision, key.build)?.let { "accel_deny: $it" } } catch (_: Exception) { null }
    }

    private fun denied() = denyReason() != null

    private fun inPlaceNow(): Boolean = try { preparer.inPlace(key) } catch (_: Exception) { false }

    private fun programsUnchanged(r: PromotionRecord): Boolean = try {
        preparer.inPlace(key) && (r.programSha.isEmpty() || preparer.programId(key) == r.programSha)
    } catch (_: Exception) {
        false
    }

    /** No promotion for this key while the config says so: `reasons.denylist` once per key, no incident report. A promoted
     *  key keeps its record (it comes back when the rule goes); sessions start on the CPU meanwhile. */
    private fun denyHit(detail: String) {
        set(AcceleratorState.Disabled(Reasons.DENYLIST, detail))
        countDeny()
    }

    /** `reasons.denylist`, once per key. */
    private fun countDeny() {
        synchronized(lock) {
            val r = store.record(key) ?: PromotionRecord()
            if (r.denyCounted) return
            store.put(key, r.copy(denyCounted = true, updatedAtMs = env.nowMs()))
            SdkEvents.emit { it.onPromotion(PromotionEvent(key.build, key.revision, key.context, promoted = false,
                stage = if (r.status == PromotionRecord.PROMOTED) Stages.PROMOTE else Stages.after(r.stage), reason = Reasons.DENYLIST,
                attempt = r.attempt.coerceAtLeast(1), countsAttempt = false)) }
        }
    }

    private fun schedule(delayMs: Long, unmetered: Boolean) = env.schedule(delayMs, unmetered) { runPipeline() }

    /**
     * The process ended at [stage] of key [id] (its sentinel was left; SDK 3.1.5). [foreground]: whether the app was in
     * the foreground when the stage last recorded it, null when not known (a sentinel of 3.1.4 or earlier, the
     * desktop JVM).
     *
     * - In the background a stop counts nothing at any stage: no failure, no wait, the attempt goes on at this launch
     *   from the stage its record says. A promotion's stop is reported (`foreground` false); one at a session's first
     *   call is not (the field is a promotion's). The [BACKGROUND_STOPS_COUNTED]th in a row at the same stage counts as
     *   a stop in the foreground, and the row goes on past it, so the next one is the second counted stop there. A
     *   stage that completes, the load and warm-up at launch, or a first call that comes back, ends its row.
     * - In the foreground, or not known: at a preparation stage ([Stages.PREPARATION]: the model has not run on the
     *   accelerator in this attempt) the retry budget as any failure (1 h, 6 h, 24 h, then none), and the
     *   [PREPARATION_STOPS_FINAL]th counted stop at the same stage ends the key; at golden, measure or a session's
     *   first accelerator call it ends the key.
     */
    private fun stopFound(id: String, stage: String, foreground: Boolean?) {
        var r = store.record(id) ?: PromotionRecord(attempt = 1)
        if (r.status == PromotionRecord.FINAL) return
        val now = env.nowMs()
        val inPipeline = stage != Stages.SESSION
        val reported = if (stage == Stages.LOAD) Stages.COMPILE else stage
        val attempt = r.attempt.coerceAtLeast(1)
        var row: Map<String, Int>? = null
        if (foreground == false) {
            val n = (r.backgroundStops[stage] ?: 0) + 1
            row = r.backgroundStops + (stage to n)
            if (n < BACKGROUND_STOPS_COUNTED) {
                // not a fault of the build: nothing counted, the attempt goes on where its record says
                store.put(id, r.copy(backgroundStops = row, updatedAtMs = now))
                // reported for a promotion (the server opens no incident for it); a session's is not a promotion's
                if (id == key.id && inPipeline) {
                    val inc = incident(AcceleratorIncident.PROMOTION_FAILED, reported, Reasons.CRASH, null, attempt = attempt,
                        message = "the process ended during $stage in the background", foreground = false)
                    SdkEvents.emit { it.onPromotion(PromotionEvent(key.build, key.revision, key.context, promoted = false, stage = reported,
                        reason = Reasons.CRASH, attempt = attempt, countsAttempt = false, incident = inc, background = true)) }
                }
                return
            }
        }
        val preparation = stage in Stages.PREPARATION
        val stops = if (preparation) r.preparationStops + (stage to ((r.preparationStops[stage] ?: 0) + 1)) else r.preparationStops
        val failures = r.failures + 1
        val final = !preparation || (stops[stage] ?: 0) >= PREPARATION_STOPS_FINAL || failures > MAX_RETRIES
        val next = if (final) 0L else now + timeouts.backoffMs[(failures - 1).coerceIn(0, timeouts.backoffMs.size - 1)]
        r = r.copy(status = if (final) PromotionRecord.FINAL else PromotionRecord.FAILED, reason = Reasons.CRASH, failedStage = stage,
            failures = failures, nextAttemptAtMs = next, preparationStops = stops, backgroundStops = row ?: emptyMap(),
            detail = "the process ended during $stage" + if (foreground == false) " in the background" else "", updatedAtMs = now)
        store.put(id, r)
        if (id != key.id) return
        val incident = incident(if (inPipeline) AcceleratorIncident.PROMOTION_FAILED else AcceleratorIncident.FAILOVER, reported,
            Reasons.CRASH, null, attempt = attempt, foreground = if (inPipeline) foreground else null)
        SdkEvents.emit { it.onPromotion(PromotionEvent(key.build, key.revision, key.context, promoted = false,
            stage = reported, reason = Reasons.CRASH, attempt = attempt, countsAttempt = inPipeline, incident = incident)) }
    }

    // ------------------------------------------------------------------------------------------------- sessions

    /**
     * A session starts (the recognizer calls this at every session start). On a first install the pipeline is armed
     * to start after it; a running warm-up or golden stage stops and hands back the engine's one stream (a wait of at
     * most one piece, [PromotionTimeouts.yieldMs] at worst).
     */
    fun sessionStarted() {
        active.incrementAndGet()
        if (!store.sessionStarted) store.sessionStarted = true
        if (armed) env.schedule(timeouts.idleDelayMs, false) { trigger() }
        if (!stuck && engineLock.isLocked && !engineLock.isHeldByCurrentThread) {
            if (engineLock.tryLock(timeouts.yieldMs, TimeUnit.MILLISECONDS)) engineLock.unlock()
        }
    }

    /** A session ended: on a first install the pipeline starts now; a stage stopped for it runs again. */
    fun sessionEnded() {
        if (active.decrementAndGet() > 0) return
        if (armed) return trigger()
        if (resumeWhenIdle) {
            resumeWhenIdle = false
            schedule(0, false)
        }
    }

    private fun trigger() {
        synchronized(lock) {
            if (!armed) return
            armed = false
        }
        schedule(0, false)
    }

    // ------------------------------------------------------------------------------------------------- the pipeline

    /**
     * The pipeline itself (what the scheduled work runs; blocking): resumes the key's attempt at the stage after the
     * last one recorded, or starts a new attempt when the record allows one. At most one in flight; safe to call twice.
     */
    fun runPipeline() {
        if (!running.compareAndSet(false, true)) return
        try {
            pipeline()
        } finally {
            running.set(false)
        }
    }

    private fun pipeline() {
        denyReason()?.let { return denyHit(it) }
        preparer.unavailable(key)?.let { return set(AcceleratorState.Cpu(it)) }
        var r = store.record(key) ?: PromotionRecord()
        val now = env.nowMs()
        when (r.status) {
            PromotionRecord.FINAL -> return
            PromotionRecord.PROMOTED -> return restore(r)
            PromotionRecord.FAILED -> {
                if (now < r.nextAttemptAtMs || r.launch == launch) return
                r = r.copy(status = PromotionRecord.PENDING, stage = "", attempt = r.attempt + 1, launch = launch, reason = "", detail = "",
                    failedStage = "", compileMs = -1, updatedAtMs = now)
                store.put(key, r)
            }
            else -> if (r.attempt == 0 || r.launch != launch) {
                r = r.copy(attempt = maxOf(1, r.attempt), launch = launch, updatedAtMs = now)
                store.put(key, r)
            }
        }
        // a resumed attempt past its download (the 2.0.1 upgrade goes back to the golden stage) whose files are gone
        if (r.stage.isNotEmpty() && Stages.after(r.stage) != Stages.DOWNLOAD && !inPlaceNow()) {
            r = r.copy(stage = "", updatedAtMs = now)
            store.put(key, r)
        }
        while (true) {
            val stage = Stages.after(r.stage)
            denyReason()?.let { return denyHit(it) }
            if (stage == Stages.PROMOTE) return promote(r)
            env.conditions().waitingFor()?.let { w -> return wait(stage, w, false) }
            if (stage in setOf(Stages.WARMUP, Stages.GOLDEN, Stages.MEASURE) && active.get() > 0) return waitForSessions(stage)
            set(AcceleratorState.Preparing(stage))
            r = try {
                // a stage that completes ends its row of stops in the background (the reload with it)
                runStage(stage, r).let { done -> done.copy(backgroundStops = done.backgroundStops - stage - Stages.LOAD) }
            } catch (e: StageWait) {
                return wait(stage, e.what, e.what == "unmetered_network")
            } catch (e: StageAborted) {
                return waitForSessions(stage)
            } catch (e: StageFailure) {
                return fail(r, stage, e)
            }
            store.put(key, r)
        }
    }

    private fun wait(stage: String, what: String, unmetered: Boolean) {
        set(AcceleratorState.Preparing(stage, what))
        schedule(timeouts.recheckMs, unmetered)
    }

    private fun waitForSessions(stage: String) {
        set(AcceleratorState.Preparing(stage, "session"))
        resumeWhenIdle = true
        // the session may have ended meanwhile
        if (active.get() == 0 && resumeWhenIdle) {
            resumeWhenIdle = false
            schedule(0, false)
        }
    }

    /** One stage; returns the record with the stage done. */
    private fun runStage(stage: String, r: PromotionRecord): PromotionRecord {
        val now = { env.nowMs() }
        return when (stage) {
            Stages.DOWNLOAD -> {
                try {
                    preparer.download(key)
                } catch (e: StageFailure) {
                    throw e
                } catch (e: StageWait) {
                    throw e
                } catch (e: Exception) {
                    throw StageFailure(downloadReason(e), "${e.javaClass.simpleName}: ${e.message}", e)
                }
                r.copy(stage = stage, updatedAtMs = now())
            }
            Stages.COMPILE -> {
                val t0 = System.nanoTime()
                load(Stages.COMPILE)
                r.copy(stage = stage, compileMs = (System.nanoTime() - t0) / 1_000_000, updatedAtMs = now())
            }
            Stages.WARMUP -> {
                val p = prepared ?: load("load")
                warmup(p)
                r.copy(stage = stage, updatedAtMs = now())
            }
            Stages.GOLDEN -> {
                val p = prepared ?: load("load").also { warmup(it) }
                val t0 = env.nowMs()
                val g = guarded(Stages.GOLDEN, timeouts.goldenMs, Reasons.HANG) { withEngine { p.golden { active.get() > 0 } } }
                val v = SelfTestSet.judgeGolden(p.expected, g.cpu, g.accelerator, p.planted, p.tolerance)
                // the event's text ends with the measures as the record a device would report (nothing sends it)
                val measures = try { ai.quranlab.tajweedformer.GoldenMeasures.record(key, env, p.expected, p.planted, v) } catch (_: Exception) { null }
                val text = ai.quranlab.tajweedformer.GoldenMeasures.text("${v.reason}: ${v.detail}", measures)
                SdkEvents.emit { it.onSelfTest(SelfTestResult(Backend.NPU, v.passed, text, env.nowMs() - t0, key.soc, key.context, key.revision)) }
                if (!v.passed) throw StageFailure(v.reason, v.detail, null, v.clip, v.divergence)
                // the limits come from WARM chunks only: never a clip's first chunk or its flush
                val cpuMs = g.cpu.values.flatMap { it.warmMs() }
                val accMs = g.accelerator.values.flatMap { it.warmMs() }
                r.copy(stage = stage, cpuMedianMs = SelfTestSet.percentile(cpuMs, 0.5), accMedianMs = SelfTestSet.percentile(accMs, 0.5),
                    accP90Ms = SelfTestSet.percentile(accMs, 0.9), updatedAtMs = now())
            }
            // telemetry only (Quran Lab, 11-10): a build that passed the golden test is promoted whatever its speed
            Stages.MEASURE -> r.copy(stage = stage, updatedAtMs = now())
            else -> r
        }
    }

    private fun downloadReason(e: Exception): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            Regex("(?i)no space|enospc|disk full|storage").containsMatchIn(m) -> Reasons.STORAGE
            Regex("(?i)sha|checksum|size|signature|verif").containsMatchIn(m) -> Reasons.VERIFY
            e is java.io.IOException || Regex("(?i)network|http|timeout|connect|host").containsMatchIn(m) -> Reasons.NETWORK
            else -> Reasons.OTHER
        }
    }

    /** Compile (or, after a restart, load again) under the sentinel and [PromotionTimeouts.compileMs]. */
    private fun load(stage: String): PreparedAccelerator {
        val p = guarded(stage, timeouts.compileMs, Reasons.COMPILE_TIMEOUT, onLate = { late -> (late as? PreparedAccelerator)?.close() }) {
            try {
                preparer.compile(key)
            } catch (e: StageFailure) {
                throw e
            } catch (e: Throwable) {
                // an exception, or a LinkageError of a native library that does not fit
                throw StageFailure(Reasons.COMPILE_ERROR, "${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
        held = AcceleratorHandle(p)
        return p
    }

    private fun warmup(p: PreparedAccelerator) {
        guarded(Stages.WARMUP, timeouts.warmupMs, Reasons.HANG) {
            withEngine {
                try {
                    p.warmup()
                } catch (e: StageFailure) {
                    throw e
                } catch (e: StageAborted) {
                    throw e
                } catch (e: Exception) {
                    throw StageFailure(Reasons.LOAD_ERROR, "${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }
    }

    /** Runs [body] holding the engine lock (a starting session waits for it); a session already active aborts first. */
    private fun <T> withEngine(body: () -> T): T {
        engineLock.lock()
        try {
            if (active.get() > 0) throw StageAborted()
            return body()
        } finally {
            engineLock.unlock()
        }
    }

    /**
     * [body] on its own low-priority thread under the crash sentinel of [stage], within [ms]: late, it is abandoned
     * (its thread is left to finish; [onLate] gets a result that comes after) and the stage fails with [lateReason].
     */
    private fun <T> guarded(stage: String, ms: Long, lateReason: String, onLate: (Any?) -> Unit = {}, body: () -> T): T {
        store.armSentinel(key, stage, foregroundNow())
        val box = LinkedBlockingQueue<Result<T>>(1)
        val abandoned = AtomicBoolean(false)
        val t = Thread({
            val res = try { Result.success(body()) } catch (e: Throwable) { Result.failure(e) }
            synchronized(abandoned) {
                if (abandoned.get()) res.getOrNull()?.let { onLate(it) } else box.offer(res)
            }
        }, "tajweedformer-accelerator-$stage").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        t.start()
        val res = box.poll(ms, TimeUnit.MILLISECONDS) ?: synchronized(abandoned) {
            box.poll() ?: run {
                abandoned.set(true)
                null
            }
        }
        // the thread may still be running (a hang): the sentinel is cleared since this process lives on
        store.clearSentinel()
        if (res == null) {
            stuck = true
            throw StageFailure(lateReason, "$stage gave no result in $ms ms")
        }
        return res.getOrThrow()
    }

    private fun promote(r: PromotionRecord) {
        // resumed after a restart: load and warm up again before the swap is offered
        if (prepared == null) {
            try {
                if (active.get() > 0) return waitForSessions(Stages.PROMOTE)
                set(AcceleratorState.Preparing(Stages.COMPILE))
                val p = load("load")
                set(AcceleratorState.Preparing(Stages.WARMUP))
                warmup(p)
            } catch (e: StageAborted) {
                return waitForSessions(Stages.PROMOTE)
            } catch (e: StageFailure) {
                return fail(r, if (prepared == null) Stages.COMPILE else Stages.WARMUP, e)
            }
        }
        limits = ChunkLimits(r.accMedianMs, r.accP90Ms)
        val rec = r.copy(stage = Stages.PROMOTE, status = PromotionRecord.PROMOTED, promotedAtMs = env.nowMs(), reason = "", detail = "",
            failedStage = "", updatedAtMs = env.nowMs(), programSha = try { preparer.programId(key) } catch (_: Exception) { "" },
            preparationStops = emptyMap(), backgroundStops = emptyMap())
        store.put(key, rec)
        val ttr = env.nowMs() - startedAtMs
        set(AcceleratorState.Ready(key.soc, ttr, rec.speedup))
        SdkEvents.emit { it.onPromotion(PromotionEvent(key.build, key.revision, key.context, promoted = true, stage = Stages.PROMOTE,
            reason = null, attempt = rec.attempt, compileMs = rec.compileMs.takeIf { it >= 0 }, speedup = rec.speedup.takeIf { it.isFinite() })) }
    }

    /** A key promoted in an earlier launch: load and warm up again (no golden test); sessions use the CPU until then. */
    private fun restore(r: PromotionRecord) {
        if (prepared != null && _state.value is AcceleratorState.Ready) return
        val now = env.nowMs()
        if (r.pausedUntilMs > now) {
            set(AcceleratorState.Disabled(Reasons.FAILOVER, r.pauseCause, persistent = false, retryAtMs = r.pausedUntilMs))
            return schedule(r.pausedUntilMs - now, false)
        }
        set(AcceleratorState.Preparing(Stages.COMPILE))
        try {
            val p = prepared ?: load("load")
            if (active.get() > 0) return waitForSessions(Stages.WARMUP)
            set(AcceleratorState.Preparing(Stages.WARMUP))
            warmup(p)
        } catch (e: StageAborted) {
            return waitForSessions(Stages.WARMUP)
        } catch (e: StageFailure) {
            return fail(r.copy(attempt = r.attempt + 1, launch = launch), if (prepared == null) Stages.COMPILE else Stages.WARMUP, e)
        }
        limits = ChunkLimits(r.accMedianMs, r.accP90Ms)
        synchronized(lock) {
            // loaded and warm again: a pause is over, and so is a row of stops in the background at the load or the
            // warm-up (a row at a session's first call goes on: that call has not come back yet)
            val rows = setOf(Stages.LOAD, Stages.WARMUP)
            store.record(key)?.takeIf { it.pausedUntilMs != 0L || it.pauseCause.isNotEmpty() || it.backgroundStops.keys.any { s -> s in rows } }?.let {
                store.put(key, it.copy(pausedUntilMs = 0, pauseCause = "", backgroundStops = it.backgroundStops - rows, updatedAtMs = env.nowMs()))
            }
        }
        set(AcceleratorState.Ready(key.soc, env.nowMs() - startedAtMs, r.speedup))
    }

    private fun fail(r: PromotionRecord, stage: String, f: StageFailure) {
        // a late call may still be running on it: then it is left open (closing a runtime under a call can crash)
        held?.let { h -> held = null; if (f.reason != Reasons.HANG && f.reason != Reasons.COMPILE_TIMEOUT) h.retire() }
        val failures = r.failures + 1
        val final = f.reason in Reasons.DETERMINISTIC || failures > MAX_RETRIES
        val next = if (final) 0L else env.nowMs() + timeouts.backoffMs[(failures - 1).coerceIn(0, timeouts.backoffMs.size - 1)]
        val rec = r.copy(status = if (final) PromotionRecord.FINAL else PromotionRecord.FAILED, failures = failures, reason = f.reason,
            failedStage = stage, detail = f.message ?: "", nextAttemptAtMs = next, launch = launch, updatedAtMs = env.nowMs())
        store.put(key, rec)
        set(AcceleratorState.Disabled(f.reason, f.message ?: "", persistent = final, retryAtMs = next))
        val inc = incident(AcceleratorIncident.PROMOTION_FAILED, stage, f.reason, f.cause ?: f.takeIf { f.cause == null && f.reason == Reasons.COMPILE_ERROR },
            attempt = rec.attempt, clip = f.clip, divergence = f.divergence, message = f.message)
        SdkEvents.emit { it.onPromotion(PromotionEvent(key.build, key.revision, key.context, promoted = false, stage = stage, reason = f.reason,
            attempt = rec.attempt, compileMs = rec.compileMs.takeIf { it >= 0 && stage != Stages.COMPILE },
            speedup = null, incident = inc)) }
    }

    private fun incident(kind: String, stage: String, reason: String, error: Throwable?, attempt: Int? = null, clip: String? = null,
                         divergence: Divergence? = null, message: String? = null, chunk: Int? = null, elapsedMs: Double? = null,
                         deadlineMs: Double? = null, stallMs: Double? = null, foreground: Boolean? = null): AcceleratorIncident {
        val c = try { env.conditions() } catch (_: Exception) { null }
        return AcceleratorIncident(kind = kind, stage = stage, cause = AcceleratorIncident.cause(reason), revision = key.revision,
            context = key.context, accelerator = key.build, error = error, message = error?.message ?: message, chunk = chunk,
            elapsedMs = elapsedMs, deadlineMs = deadlineMs, clip = clip, divergence = divergence, deviceModel = env.deviceModelCode,
            osBuild = env.osBuildReport, chip = key.soc, computeUnits = "npu", delegate = env.delegate, thermal = c?.thermal,
            lowPower = c?.powerSave, stallMs = stallMs, attempt = attempt, foreground = foreground)
    }

    /** Whether the app is in the foreground now; null when the platform cannot tell. */
    private fun foregroundNow(): Boolean? = try { env.foreground() } catch (_: Exception) { null }

    // ------------------------------------------------------------------------------------------------- sessions on it

    /**
     * The engine a NEW session should use: the accelerator (a [FailoverEngine] over [cpu]) when it is
     * [AcceleratorState.Ready] and nothing prefers the CPU (forced CPU, thermal, memory, the denylist), else null (the
     * CPU) with the reason in [lastCpuReason]. Called at session start only, so a session never changes engine except
     * by a failover. [reuse] is the engine the caller got last time (kept while the same accelerator is promoted, so
     * per-engine learning such as batched appends carries over).
     */
    fun engineForNextSession(cpu: CpuStandby, sampleRate: Int, reuse: FailoverEngine? = null, onSwitch: (FailoverEvent) -> Unit = {}): FailoverEngine? {
        val p = prepared
        val s = _state.value
        lastCpuReason = when {
            forced == Backend.CPU -> Reasons.FORCED_CPU
            s !is AcceleratorState.Ready || p == null -> (s as? AcceleratorState.Disabled)?.reason ?: (s as? AcceleratorState.Cpu)?.reason ?: s.key
            denied() -> Reasons.DENYLIST.also { countDeny() }
            forced != Backend.NPU && env.thermalSevere() -> Reasons.THERMAL
            forced != Backend.NPU && env.lowMemory() -> Reasons.MEMORY
            else -> null
        }
        if (lastCpuReason != null) return null
        val h = held ?: return null
        // a late call of an earlier take still holds the accelerator's thread: this take on the CPU, the next may use it
        if (engines.any { it.busy && !h.retired }) {
            lastCpuReason = Reasons.BUSY
            return null
        }
        if (reuse != null && reuse.belongsTo(p!!, cpu, sampleRate) && reuse.backend == Backend.NPU) return reuse
        val me = this
        val e = FailoverEngine(h, cpu, sampleRate, limits, h.accelerator.range, object : FailoverListener {
            override fun firstCall(done: Boolean) = me.firstCall(done)
            override fun failingOver(cause: String, take: Long): String = me.failingOver(cause, take)
            override fun failedOver(event: FailoverEvent) {
                me.failedOver(event)
                try { onSwitch(event) } catch (_: Exception) {}
            }
            override fun blankChecked(check: BlankCheck) = me.blankChecked(check)
        }, windowChunks = replayWindowMs / h.accelerator.chunkMs, blankRunMs = blankRunMs)
        engines.add(e)
        return e
    }

    /**
     * The app became active (Android: a first activity started, [AcceleratorEnvironment.onAppActive]): every session
     * engine over the accelerator runs a warm-up off the audio path, and its next chunk gets the first-chunk limit.
     */
    fun appActivated() {
        for (e in engines) try { e.warmUp(activation = true) } catch (_: Exception) {}
    }

    /** Why the last [engineForNextSession] gave the CPU (null: it gave the accelerator). */
    @Volatile var lastCpuReason: String? = null
        private set

    /** The limit in force for a warm accelerator chunk, ms ([ChunkLimits.warmMs]). */
    val deadlineMs: Double get() = limits.warmMs

    /** The limit of a take's first chunk and of the first chunk after the app became active, ms. */
    val firstChunkMs: Double get() = limits.firstChunkMs

    /** The chunk limits in force (the sessions add their warm chunk times to them). */
    val chunkLimits: ChunkLimits get() = limits

    override fun firstCall(done: Boolean) {
        if (!done) {
            if (firstCallGuarded.add(key.id)) store.armSentinel(key, Stages.SESSION, foregroundNow()) else return
        } else {
            store.clearSentinel()
            // the first call came back: a row of stops in the background at it has ended
            synchronized(lock) {
                store.record(key)?.takeIf { Stages.SESSION in it.backgroundStops }?.let {
                    store.put(key, it.copy(backgroundStops = it.backgroundStops - Stages.SESSION, updatedAtMs = env.nowMs()))
                }
            }
        }
    }

    /**
     * The accelerator failed in take [take] with [cause] (the take switches to the CPU). Demotion by cause:
     *
     * - `deadline` (a missed limit, a hang included): nothing for one; misses in [PromotionTimeouts.missSessions]
     *   different sessions within [PromotionTimeouts.missWindowMs] pause the key ([pause]); never off for good;
     * - `blank` (the CPU confirmed a disagreement): a pause; the [PromotionTimeouts.blankDisagreementsOff]th for the key
     *   (any sessions) turns it off for good;
     * - `non_finite`, `out_of_range`: off for good (a numeric fault of this build on this OS);
     * - `exception`: the 2.0.1 rule, a retryable `failover` at least 24 h later (the golden test again), the 4th off.
     */
    override fun failingOver(cause: String, take: Long): String = synchronized(lock) {
        val now = env.nowMs()
        val r = store.record(key) ?: PromotionRecord(attempt = 1)
        when (cause) {
            Failover.DEADLINE -> {
                // one entry per session (a take switches once), the last 24 h
                val misses = r.missSessionsAtMs.filter { now - it < timeouts.missWindowMs } + now
                if (misses.size >= timeouts.missSessions) {
                    pause(r.copy(missSessionsAtMs = emptyList()), cause, now)
                } else {
                    store.put(key, r.copy(missSessionsAtMs = misses, updatedAtMs = now))
                    Demotion.NONE
                }
            }
            Failover.BLANK -> {
                val n = r.blankDisagreements + 1
                if (n >= timeouts.blankDisagreementsOff) off(r.copy(blankDisagreements = n), cause, now)
                else pause(r.copy(blankDisagreements = n), cause, now)
            }
            Failover.NON_FINITE, Failover.OUT_OF_RANGE -> off(r, cause, now)
            else -> {
                val failures = r.failures + 1
                val final = failures > MAX_RETRIES
                val next = if (final) 0L else now + maxOf(timeouts.failoverRetryMs,
                    timeouts.backoffMs[(failures - 1).coerceIn(0, timeouts.backoffMs.size - 1)])
                store.put(key, r.copy(status = if (final) PromotionRecord.FINAL else PromotionRecord.FAILED, failures = failures,
                    reason = Reasons.FAILOVER, failedStage = Stages.SESSION, detail = cause, nextAttemptAtMs = next, stage = "",
                    launch = launch, updatedAtMs = now))
                // closed once its last call returns (a late call keeps it)
                retireAll()
                set(AcceleratorState.Disabled(Reasons.FAILOVER, cause, persistent = final, retryAtMs = next))
                if (final) Demotion.OFF else Demotion.RETRY
            }
        }
    }

    /** A transient demotion: the key stays promoted and verified, no session uses it for
     *  [PromotionTimeouts.transientBackoffMs], then it loads and warms up again (no golden test) and serves from the
     *  next session boundary. */
    private fun pause(r: PromotionRecord, cause: String, now: Long): String {
        val until = now + timeouts.transientBackoffMs
        store.put(key, r.copy(pausedUntilMs = until, pauseCause = cause, updatedAtMs = now))
        retireAll()
        set(AcceleratorState.Disabled(Reasons.FAILOVER, cause, persistent = false, retryAtMs = until))
        schedule(timeouts.transientBackoffMs, false)
        return Demotion.PAUSED
    }

    /** Off for good for this key (a new model revision or OS build is a new key). */
    private fun off(r: PromotionRecord, cause: String, now: Long): String {
        store.put(key, r.copy(status = PromotionRecord.FINAL, failures = r.failures + 1, reason = Reasons.FAILOVER, failedStage = Stages.SESSION,
            detail = cause, nextAttemptAtMs = 0, stage = "", pausedUntilMs = 0, pauseCause = "", launch = launch, updatedAtMs = now))
        retireAll()
        set(AcceleratorState.Disabled(Reasons.FAILOVER, cause, persistent = true))
        return Demotion.OFF
    }

    /** A blank check finished: one the CPU confirmed (blank there too) is counted (`blank_confirmed_ok`); no failover,
     *  no demotion, no incident. */
    override fun blankChecked(check: BlankCheck) {
        if (!check.agreed || check.failed) return
        SdkEvents.emit { it.onBlankConfirmed(ai.quranlab.tajweedformer.BlankConfirmedEvent(key.build, key.revision, key.context, check)) }
    }

    override fun failedOver(event: FailoverEvent) {
        val r = store.record(key)
        val inc = incident(AcceleratorIncident.FAILOVER, Stages.SESSION, event.cause, event.error, chunk = event.chunk,
            elapsedMs = event.elapsedMs, deadlineMs = event.deadlineMs.takeIf { it > 0 }, stallMs = event.stallMs, attempt = r?.attempt,
            divergence = event.divergence)
        SdkEvents.emit { it.onFailover(AcceleratorFailoverEvent(key.build, key.revision, key.context, event, inc)) }
    }

    private fun retireAll() {
        for (e in engines) e.shutdown()
        engines.clear()
        held?.retire()
        held = null
    }

    /** Releases the accelerator (once no call runs on it). */
    fun close() = synchronized(lock) { retireAll() }
}

/** The stage waits (no failure counted) for [what]: `unmetered_network`, ... */
internal class StageWait(val what: String) : Exception(what)
