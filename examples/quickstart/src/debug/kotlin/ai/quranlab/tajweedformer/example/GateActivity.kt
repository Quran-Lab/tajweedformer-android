package ai.quranlab.tajweedformer.example

import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedEvent
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * Not part of the example: the screen of the SDK's release gate on a phone. `scripts/phone_gate.py` builds this app
 * against the AAR under test, starts this activity by name and reads its lines from the log (tag `TJ_GATE`). Debug
 * builds only (`src/debug`). A screen and not an instrumented test, because some phones refuse to start a test
 * process that has no screen.
 *
 * Everything goes through the public API, with the app's own Client (`Recitation.client`, the key the app was built
 * with): the Client is made; `prefetch` downloads and verifies the model, with its progress; `model()` opens it;
 * every sample clip of the model is one take through `model.listen(clip)` at `c16_l64`; `diagnose()` is read. Each
 * step writes one `RESULT` line; the last line is `RESULT done: ok` or `RESULT done: failed`. The take lines carry
 * token ids only, which the script compares with the model build's expected tokens.
 */
class GateActivity : Activity() {
    private val scope = MainScope()
    private lateinit var lines: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the gate takes minutes: the screen stays on while this activity is in front
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lines = TextView(this)
        setContentView(ScrollView(this).apply { addView(lines) })
        scope.launch { gate() }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        lines.append(line + "\n")
    }

    private suspend fun gate() {
        try {
            say("START gate: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            val client = Recitation.client(this)
            say("RESULT client: made")

            // the model: downloaded and verified (nothing is reported when it is on the device already)
            var t0 = System.nanoTime()
            var done = 0L
            var total = 0L
            var reports = 0
            client.prefetch { d, t ->
                done = d
                total = t
                if (reports++ % 16 == 0) Log.i(TAG, "download: $d of $t bytes")
            }
            say(if (reports == 0) "RESULT download: nothing to download, the model was on the device"
                else "RESULT download: $done of $total bytes, $reports progress reports, ${ms(t0)} ms")
            t0 = System.nanoTime()
            val model = client.model()
            say("RESULT model: version ${model.version}, build ${model.build}, contexts ${model.contexts}, default ${model.defaultContext}, " +
                "backend ${model.backend.name}, opened in ${ms(t0)} ms")
            say("RESULT storage: ${client.storage}")

            // every sample clip, one take each, through listen
            val clips = model.sampleClips
            say("RESULT sample clips: ${clips.size}")
            for (clip in clips) {
                t0 = System.nanoTime()
                val session = model.listen(clip, TajweedSessionOptions(context = CONTEXT, pace = TajweedSessionOptions.Pace.FAST))
                val ended = scope.async { session.events.toList().filterIsInstance<TajweedEvent.Ended>().map { it.reason.name } }
                val chunks = session.chunks.toList()
                val heads = session.finish()
                say("RESULT take ${clip.name} $CONTEXT: ${clip.durationMs} ms of audio, ${chunks.size} chunks in ${ms(t0)} ms, ended ${ended.await()}, " +
                    "final ${chunks.lastOrNull()?.decoded?.isFinal}, tokens ${heads.greedy().joinToString(",") { it.id.toString() }}")
            }

            say("RESULT diagnose: ${client.diagnose().toJson()}")
            say("RESULT done: ok")
        } catch (e: TajweedError) {
            // the message and, with it, the fix: never a bare code
            say("RESULT failed: ${e.code}: ${e.message} What to do: ${e.fix}")
            say("RESULT done: failed")
        } catch (e: Throwable) {
            say("RESULT failed: ${e.javaClass.simpleName}: ${e.message}")
            say("RESULT done: failed")
        }
    }

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TJ_GATE"
        const val CONTEXT = "c16_l64"
    }
}
