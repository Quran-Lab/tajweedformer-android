package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Backend
import ai.quranlab.tajweedformer.BackendChoice
import ai.quranlab.tajweedformer.CloudBridge
import ai.quranlab.tajweedformer.ModelLoadInfo
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.PromotionEvent
import ai.quranlab.tajweedformer.SelfTestResult
import ai.quranlab.tajweedformer.SessionInfo
import ai.quranlab.tajweedformer.SessionStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The backend names of what [CloudBridge] hands the cloud, through to the body `POST /v1/events` gets ([StubQlab]
 * checks it as qlab-api src/events2.js does). A cohort's backend is `cpu` or `npu:<soc>`, a promotion's `npu:<soc>`.
 * SDK 2.0.0 to 3.1.5 named an NPU fallback and the NPU's self-test outside a take `npu`, and a promotion of a key
 * whose SoC is unknown `npu:-`: the server refused the whole report (400, `bad_request:events`), takes included.
 */
class BridgeBackendTest : OpenChecked() {
    private lateinit var stub: StubQlab
    private lateinit var dir: File
    private val build = "0123456789abcdef0123456789abcdef01234567"

    @Before fun up() {
        stub = StubQlab()
        dir = Files.createTempDirectory("tj-bridge").toFile()
    }

    @After fun down() {
        stub.close()
        dir.deleteRecursively()
    }

    private fun take(bridge: CloudBridge, cloud: QuranLabCloud, backend: Backend = Backend.CPU, soc: String? = null) {
        val s = SessionInfo(1, "tajweedformer", "c16_l64", backend, 16_000, stub.clock(), "4.0.0", soc)
        bridge.onSessionStart(s)
        repeat(5) { cloud.onChunk(12.0, 320.0) }
        bridge.onSessionEnd(s, SessionStats(1.6, 0.06, 5, "finished", backend, speechFrames = 30, silenceFrames = 10,
            tokens = mapOf("log_probs" to 9), audioMs = 1_600, computeUs = 60_000))
    }

    /** One launch of [steps], a send point and a delivery; the one body sent and the stub's answer to it. */
    @Suppress("UNCHECKED_CAST")
    private fun send(name: String, steps: (CloudBridge, QuranLabCloud) -> Unit): Pair<Map<String, Any?>, Int> {
        val c = stub.cloud(File(dir, name))
        c.refreshConfig()
        val bridge = CloudBridge(c)
        steps(bridge, c)
        c.background()
        c.deliver()
        val ev = stub.seen("/v1/events").last()
        // what the stub answered: 400 drops the report and queues bad_request:events
        val refused = c.telemetry.pendingReports().isEmpty() && stub.seen("/v1/issues").any { (it.json?.get("code")) == "bad_request:events" }
        return ev.json!! to (if (refused) 400 else 202)
    }

    @Suppress("UNCHECKED_CAST")
    private fun backends(b: Map<String, Any?>) = (b["cohorts"] as List<Map<String, Any?>>).map { it["backend"] as String }

    @Test fun aTakeOnTheCpuAlone() {
        val (b, status) = send("cpu_take") { br, c -> take(br, c) }
        assertEquals(listOf("cpu"), backends(b))
        assertEquals(202, status)
    }

    /** The accelerator is unavailable when the recognizer opens (no LiteRT in the app, a deny rule, a failed golden test):
     *  the fallback is counted in a cohort of its own, as the accelerator `npu:<soc>` it falls back from. */
    @Test fun aFallbackBeforeTheTakeNamesTheAccelerator() {
        val (b, status) = send("fallback_runtime_missing") { br, c ->
            br.onBackendSelected(BackendChoice(Backend.CPU, "runtime_missing", "", "s5e8835", "c16_l64", "4.0.0"))
            take(br, c)
        }
        assertTrue(backends(b).toString(), backends(b).all { it == "cpu" || it.startsWith("npu:") })
        assertEquals(202, status)
    }

    @Test fun anNpuSelfTestBeforeTheTakeNamesTheAccelerator() {
        val (b, status) = send("npu_self_test") { br, c ->
            br.onModelLoad(ModelLoadInfo("4.0.0", "c16_l64", Backend.NPU, 900.0, false, "s5e8835"))
            br.onSelfTest(SelfTestResult(Backend.NPU, false, "golden_mismatch", 40, "s5e8835", "c16_l64", "4.0.0"))
            take(br, c)
        }
        assertTrue(backends(b).toString(), backends(b).all { it == "cpu" || it.startsWith("npu:") })
        assertEquals(202, status)
    }

    /** A fallback whose SoC the SDK does not know: `npu:unknown`, as a model load names it. */
    @Test fun aFallbackWithNoSocNamesNpuUnknown() {
        val (b, status) = send("fallback_no_soc") { br, c ->
            br.onBackendSelected(BackendChoice(Backend.CPU, "denylist", "", null, "c16_l64", "4.0.0"))
            take(br, c)
        }
        assertTrue(backends(b).toString(), "npu:unknown" in backends(b))
        assertEquals(202, status)
    }

    /** The sample clips fetched beside a model at its first open (Downloader.fetch without a revision) are no download of
     *  a model version: not reported. Until 3.1.5 they went out as version "" and the report was refused. */
    @Suppress("UNCHECKED_CAST")
    @Test fun aFileOfNoModelVersionIsNotADownload() {
        val (b, status) = send("download_no_version") { br, c ->
            br.onDownloadFinished(ai.quranlab.tajweedformer.DownloadResult("4.0.0/cpu/layers.zfw", "cpu", 57_000_000, 57_000_000, 0, true, revision = "4.0.0"))
            br.onDownloadFinished(ai.quranlab.tajweedformer.DownloadResult("4.0.0/selftest/clips.bin", "selftest", 600_000, 600_000, 0, true))
            take(br, c)
        }
        val dl = b["downloads"] as List<Map<String, Any?>>
        assertEquals(listOf("4.0.0"), dl.map { it["revision"] })
        assertEquals(202, status)
    }

    /** A promotion of an accelerator whose SoC is unknown (its key's build is `npu:-`): `npu:unknown`. */
    @Suppress("UNCHECKED_CAST")
    @Test fun aPromotionWithNoSocNamesNpuUnknown() {
        val (b, status) = send("promotion_no_soc") { br, c ->
            br.onPromotion(PromotionEvent("npu:-", "4.0.0", "c16_l64", promoted = false, stage = "golden", reason = "denylist", attempt = 1, countsAttempt = false))
            take(br, c)
        }
        assertEquals(listOf("npu:unknown"), (b["promotions"] as List<Map<String, Any?>>).map { it["backend"] })
        assertEquals(202, status)
    }
}
