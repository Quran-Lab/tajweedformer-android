package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.HeaderProvider
import ai.quranlab.tajweedformer.HttpTransport
import ai.quranlab.tajweedformer.ManifestVerifier
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.NetworkPolicy
import ai.quranlab.tajweedformer.TajweedFormerClient
import ai.quranlab.tajweedformer.UrlConnectionTransport
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import ai.quranlab.tajweedformer.npu.LiteRtRunner
import android.content.Context
import java.io.File

/**
 * The SDK on Android in one call:
 *
 * ```
 * val tf = TajweedFormerAndroid.client(context, headers = { mapOf("x-qlab-key" to BuildConfig.QLAB_KEY) })
 * val tj = tf.recognizer()                                  // downloads 56.5 MB once, self-tests, picks the backend
 * ```
 *
 * The model goes to `noBackupFilesDir/tajweedformer/v4.0`. The NPU backend is used when the app
 * ships LiteRT 2.2.0 (`com.google.ai.edge.litert:litert:2.2.0`) and the SoC's vendor dispatch
 * library in its native libraries; without them the recognizer runs on the CPU (reason
 * `runtime_missing` in [ai.quranlab.tajweedformer.SdkObserver.onBackendSelected]).
 *
 * CPU first: the first session runs on the exact CPU engine; the NPU is prepared in
 * the background (WorkManager: idle, charging, unmetered) and used by later sessions once it passed its
 * self-test. `client.accelerator(model)?.state` shows where it is.
 *
 * When the app has called `QuranLabAndroid.configure` (the portal link), the client reports to it
 * ([CloudBridge]), takes the portal's `tajweed` block at every session start and follows the
 * portal's backend deny-list.
 */
object TajweedFormerAndroid {
    @Volatile private var env: AndroidAccelerator? = null

    @Synchronized
    private fun environment(app: Context, debug: Boolean): AndroidAccelerator {
        val e = env
        // the debug overrides (DebugHooks, debuggable apps only) make a new environment when they change
        val soc = if (debug) DebugHooks.soc ?: DeviceSoc.id else DeviceSoc.id
        val now = debug && DebugHooks.scheduleNow
        if (e != null && e.soc == soc && e.scheduleNow == now) return e
        return AndroidAccelerator(app, soc, now).also { env = it }
    }

    @JvmStatic
    @JvmOverloads
    fun client(
        context: Context,
        headers: HeaderProvider? = null,
        baseUrl: String = ModelStore.DEFAULT_BASE_URL,
        transport: HttpTransport = UrlConnectionTransport(),
        verifier: ManifestVerifier = ManifestVerifier(),
        dir: File = File(context.noBackupFilesDir, "tajweedformer/v4.0"),
        npu: Boolean = true,
    ): TajweedFormerClient {
        val app = context.applicationContext
        val libDir = app.applicationInfo.nativeLibraryDir
        // the portal link (QuranLabAndroid.configure): telemetry, sharing, the tajweed block, the deny-list
        CloudBridge.install(app)
        // without explicit headers: the portal link's app key, when configured
        val h = headers ?: HeaderProvider { QuranLabAndroid.cloud?.key?.value?.let { mapOf("x-qlab-key" to it) } ?: emptyMap() }
        val store = ModelStore(dir, baseUrl, transport, h, verifier)
        val debug = TajweedAndroid.isDebuggable(app)
        val network = AndroidNetwork(app)
        return TajweedFormerClient(
            store,
            TajweedFormerClient.Platform(
                soc = if (debug) DebugHooks.soc ?: DeviceSoc.id else DeviceSoc.id,
                network = if (debug && DebugHooks.unmetered) NetworkPolicy { true } else network,
                npuRunner = if (npu) LiteRtRunner.Factory(libDir) else null,
                debugBuild = debug,
                npuDownloads = npu,
                remoteTajweed = { QuranLabAndroid.cloud?.tajweedJson },
                accelerator = if (npu) environment(app, debug) else null,
                forcedBackend = { rev -> QuranLabAndroid.cloud?.forcedBackend(rev) },
            ),
        )
    }
}
