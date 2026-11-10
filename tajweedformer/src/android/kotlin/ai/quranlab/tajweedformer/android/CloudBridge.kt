package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.AcceleratorFailoverEvent
import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.BackendChoice
import ai.quranlab.tajweedformer.ChunkStats
import ai.quranlab.tajweedformer.Decoded
import ai.quranlab.tajweedformer.DownloadResult
import ai.quranlab.tajweedformer.ModelLoadInfo
import ai.quranlab.tajweedformer.PromotionEvent
import ai.quranlab.tajweedformer.SdkEvents
import ai.quranlab.tajweedformer.SdkObserver
import ai.quranlab.tajweedformer.SelfTestResult
import ai.quranlab.tajweedformer.SessionInfo
import ai.quranlab.tajweedformer.SessionStats
import ai.quranlab.tajweedformer.TajweedResult
import ai.quranlab.tajweedformer.cloud.TajweedObserver
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * The core's [SdkObserver] events, mapped to the cloud module's [TajweedObserver]
 * ([QuranLabAndroid.observer]; a no-op until the app calls `QuranLabAndroid.configure`). Installed
 * once by [TajweedFormerAndroid.client]. The mapping:
 *
 * - load, self-test, download: one to one (download profile `android-cpu` / `android-npu` by role;
 *   files already in place are not reported);
 * - backend choice: only a real fallback ([BackendChoice.isFallback]) becomes `onFallback` (a compile or load
 *   failure, a hang or crash = model_load, thermal, memory, a failover past its deadline = deadline, a failover on
 *   NaN or out-of-range values = non_finite, denylist = other, the rest = npu_unavailable); the NPU's time to ready
 *   is an `onModelLoad` of `npu:<soc>`;
 * - promotions and failovers: one to one (`promotions` telemetry and the incident reports);
 * - session start: the settings in force, the input route, `QuranLabAndroid.sessionOpen(true)`;
 * - every chunk: compute and audio ms, and the tokens' margins and conf-head confidences;
 * - a scored take: per-class sites scored and flagged, madd counts, words scored and flagged, and per rule
 *   the verdicts whose check ran and the flagged ones ([TajweedObserver.Scored.of]);
 * - session end: completed = finished, feedbackShown = scored, mistakeFlagged = something flagged;
 * - thermal: PowerManager's status (API 29+), throttled from MODERATE up.
 */
internal class CloudBridge private constructor(private val app: Context) : SdkObserver {
    private val cloud: TajweedObserver get() = QuranLabAndroid.observer

    private fun backendName(b: Backend, soc: String?) = if (b == Backend.NPU) "npu:${soc ?: "unknown"}" else "cpu"

    override fun onModelLoad(load: ModelLoadInfo) =
        cloud.onModelLoad(TajweedObserver.ModelLoad(load.revision, load.context, backendName(load.backend, load.soc), load.ms, load.cold))

    override fun onSelfTest(result: SelfTestResult) =
        cloud.onSelfTest(result.revision ?: "", result.context ?: "", result.backend.key, result.passed)

    override fun onBackendSelected(choice: BackendChoice) {
        if (!choice.isFallback) return
        val reason = when (choice.reason) {
            "compile_error", "compile_timeout", "load_error", "crash", "hang" -> TajweedObserver.FallbackReason.MODEL_LOAD
            "thermal" -> TajweedObserver.FallbackReason.THERMAL
            "memory" -> TajweedObserver.FallbackReason.MEMORY
            "failover_deadline" -> TajweedObserver.FallbackReason.DEADLINE
            "failover_non_finite", "failover_out_of_range" -> TajweedObserver.FallbackReason.NON_FINITE
            "denylist" -> TajweedObserver.FallbackReason.OTHER
            else -> TajweedObserver.FallbackReason.NPU_UNAVAILABLE
        }
        cloud.onFallback(choice.revision ?: "", choice.context ?: "", "npu", reason)
    }

    /** The NPU's time to ready (the 2.0.0 design point 6) as a model load of backend `npu:<soc>`. */
    override fun onAcceleratorState(state: ai.quranlab.tajweedformer.accel.AcceleratorState, key: ai.quranlab.tajweedformer.accel.AcceleratorKey) {
        if (state is ai.quranlab.tajweedformer.accel.AcceleratorState.Ready) {
            cloud.onModelLoad(TajweedObserver.ModelLoad(key.revision, key.context, "npu:${key.soc ?: "unknown"}", state.timeToReadyMs.toDouble(), false))
        }
    }

    /** A promotion attempt ended (the `promotions` telemetry; a failure is also an incident report). */
    override fun onPromotion(event: PromotionEvent) = cloud.onPromotion(event)

    /** A session switched to the CPU (the `promotions` failovers; an incident report). */
    override fun onFailover(event: AcceleratorFailoverEvent) = cloud.onFailover(event)

    override fun onDownloadFinished(result: DownloadResult) {
        if (result.ok && result.networkBytes == 0L) return
        val profile = if (result.role.startsWith("npu")) "android-npu" else "android-cpu"
        cloud.onDownload(TajweedObserver.Download(result.revision, profile, result.networkBytes, result.ok, result.resumedFrom > 0))
    }

    override fun onSessionStart(session: SessionInfo) {
        QuranLabAndroid.sessionOpen(true)
        val route = try { QuranLabAndroid.inputRoute(app) } catch (_: Exception) { "unknown" }
        cloud.onSessionStart(TajweedObserver.SessionStart(
            revision = session.revision, context = session.context, backend = backendName(session.backend, session.soc),
            preset = session.preset, wordLevel = session.wordLevel,
            wordThreshold = if (session.wordLevel == "custom") session.wordThreshold else null,
            siteOverrides = session.siteOverrides, checksOn = session.checksOn, sampleRate = session.sampleRate,
            inputRoute = route, decodeBackend = "exact", weightsSha256 = session.weightsSha256,
        ))
    }

    override fun onAudio(session: SessionInfo, samples: FloatArray, sampleRate: Int) =
        cloud.onAudio(samples, 0, samples.size, sampleRate)

    override fun onChunk(session: SessionInfo, chunk: ChunkStats, decoded: Decoded) {
        cloud.onChunk(chunk.computeMs, chunk.audioMs)
        val t = decoded.tokens
        if (t.isNotEmpty()) {
            val conf = if (t.all { it.conf != null }) FloatArray(t.size) { t[it].conf!! } else null
            cloud.onTokens(FloatArray(t.size) { t[it].margin }, conf)
        }
    }

    override fun onScored(session: SessionInfo, result: TajweedResult) = cloud.onScored(TajweedObserver.Scored.of(result))

    override fun onSessionEnd(session: SessionInfo, stats: SessionStats) {
        cloud.onSessionEnd(TajweedObserver.SessionEnd(stats.audioSeconds, stats.outcome == "finished", stats.scored, stats.flagged))
        QuranLabAndroid.sessionOpen(false)
    }

    override fun onError(where: String, error: Throwable) = cloud.onIssue(where, error.javaClass.simpleName, error)

    companion object {
        @Volatile private var installed: CloudBridge? = null

        /** Registers the bridge once per process (and the thermal listener on API 29+). */
        @Synchronized
        fun install(context: Context) {
            if (installed != null) return
            val b = CloudBridge(context.applicationContext)
            installed = b
            SdkEvents.add(b)
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.addThermalStatusListener { status -> b.cloud.onThermal(status >= PowerManager.THERMAL_STATUS_MODERATE) }
                } catch (_: Exception) {
                }
            }
        }
    }
}
