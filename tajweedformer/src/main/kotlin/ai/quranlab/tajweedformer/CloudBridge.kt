package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.TajweedObserver

/**
 * The inference side's [SdkObserver] events, mapped to the client's cloud side ([TajweedObserver]: telemetry, incident
 * reports, recitation sharing). Registered by the [TajweedClient] for its life. Inside the SDK a model is named by
 * its build id; here it becomes the version and the build ([ModelIds]). The mapping:
 *
 * - load, self-test, download: one to one (download profile `android-cpu` / `android-npu` by role;
 *   files already in place are not reported);
 * - backend choice: only a real fallback ([BackendChoice.isFallback]) becomes `onFallback` (a compile or load
 *   failure, a hang or crash = model_load, thermal, memory, a failover past its deadline = deadline, a failover on
 *   NaN or out-of-range values = non_finite, denylist = other, the rest = npu_unavailable); the NPU's time to ready
 *   is an `onModelLoad` of `npu:<soc>`;
 * - promotions, failovers and blanks the CPU confirmed: one to one (`promotions` telemetry and the incident reports);
 * - session start: the model, the input route, [sessionOpen];
 * - every chunk: compute and audio ms, and the tokens' margins and conf-head confidences;
 * - session end: completed = finished; the SDK-measured counters of the take (audio, compute, speech and silence
 *   seconds, tokens per head).
 */
internal class CloudBridge(
    private val cloud: TajweedObserver,
    private val inputRoute: () -> String = { "unknown" },
    /** A take opened or closed (Android refreshes the signed config while one is open). */
    private val sessionOpen: (Boolean) -> Unit = {},
    private val profilePrefix: String = SdkInfo.PLATFORM,
) : SdkObserver {
    private fun backendName(b: Backend, soc: String?) = if (b == Backend.NPU) "npu:${soc ?: "unknown"}" else "cpu"

    override fun onModelLoad(load: ModelLoadInfo) =
        cloud.onModelLoad(TajweedObserver.ModelLoad(ModelIds.version(load.revision), load.context, backendName(load.backend, load.soc), load.ms, load.cold,
            ModelIds.build(load.revision)))

    override fun onSelfTest(result: SelfTestResult) =
        cloud.onSelfTest(ModelIds.version(result.revision), result.context ?: "", result.backend.key, result.passed, ModelIds.build(result.revision))

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
        cloud.onFallback(ModelIds.version(choice.revision), choice.context ?: "", "npu", reason, ModelIds.build(choice.revision))
    }

    /** The NPU's time to ready as a model load of backend `npu:<soc>`. */
    override fun onAcceleratorState(state: ai.quranlab.tajweedformer.accel.AcceleratorState, key: ai.quranlab.tajweedformer.accel.AcceleratorKey) {
        if (state is ai.quranlab.tajweedformer.accel.AcceleratorState.Ready) {
            cloud.onModelLoad(TajweedObserver.ModelLoad(ModelIds.version(key.revision), key.context, "npu:${key.soc ?: "unknown"}",
                state.timeToReadyMs.toDouble(), false, ModelIds.build(key.revision)))
        }
    }

    override fun onPromotion(event: PromotionEvent) = cloud.onPromotion(event)

    override fun onFailover(event: AcceleratorFailoverEvent) = cloud.onFailover(event)

    override fun onBlankConfirmed(event: BlankConfirmedEvent) = cloud.onBlankConfirmed(event)

    override fun onDownloadFinished(result: DownloadResult) {
        if (result.ok && result.networkBytes == 0L) return
        val profile = if (result.role.startsWith("npu")) "$profilePrefix-npu" else "$profilePrefix-cpu"
        cloud.onDownload(TajweedObserver.Download(ModelIds.version(result.revision), profile, result.networkBytes, result.ok, result.resumedFrom > 0,
            ModelIds.build(result.revision)))
    }

    override fun onSessionStart(session: SessionInfo) {
        sessionOpen(true)
        val route = try { inputRoute() } catch (_: Exception) { "unknown" }
        cloud.onSessionStart(TajweedObserver.SessionStart(
            revision = ModelIds.version(session.revision), context = session.context, backend = backendName(session.backend, session.soc),
            sampleRate = session.sampleRate, inputRoute = route, decodeBackend = "exact", weightsSha256 = session.weightsSha256,
            build = ModelIds.build(session.revision),
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

    override fun onSessionEnd(session: SessionInfo, stats: SessionStats) {
        val completed = stats.outcome == "finished"
        cloud.onSessionEnd(TajweedObserver.SessionEnd(stats.audioSeconds, completed, feedbackShown = completed,
            audioMs = stats.audioMs, computeUs = stats.computeUs, speechFrames = stats.speechFrames, silenceFrames = stats.silenceFrames,
            tokens = stats.tokens,
            // no sample reached the take (an app that opens a session only to warm the engine, and cancels it at once)
            withoutAudio = stats.audioSeconds <= 0.0))
        sessionOpen(false)
    }

    override fun onError(where: String, error: Throwable) = cloud.onIssue(where, error.javaClass.simpleName, error)
}
