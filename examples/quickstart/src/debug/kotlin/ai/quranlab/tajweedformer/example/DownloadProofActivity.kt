package ai.quranlab.tajweedformer.example

import ai.quranlab.tajweedformer.TajweedError
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Not part of the example: the screen that proves the model download on a phone (SDK 3.1.3). A host starts it by name
 * with adb and reads its lines from the log (tag `TJ_DL`). Debug builds only (`src/debug`).
 *
 * Everything goes through the public API with the app's own Client (`Recitation.client`, the key the app was built
 * with) and Quran Lab's server; how the SDK downloads is set through the SDK's debug switches, which only a debuggable
 * app has (read when the Client is made, so one process per setting).
 *
 * Extras:
 * - `mode`: `time` (the default): `runs` times, the models are removed and `prefetch` downloads the model again; one
 *   line per run with the wall time, and one line per file the SDK downloaded (bytes, time, ranges, protocols).
 *   `fetch`: one `prefetch` of what is missing, nothing removed first (a download that was cut goes on from what
 *   the device holds; the file lines say from where). `remove`: the models are removed, nothing else.
 * - `on`, `off`: names of the SDK's debug switches (comma separated) to turn on or off before the Client is made:
 *   `singleStream` (every file in one request, as 3.1.2 did; off: the SDK as it ships, the large file in ranges),
 *   `backgroundDownloads` (on, as shipped: the SDK's background job continues the download when the app leaves).
 * - `runs`: how many downloads in mode `time` (default 1).
 */
class DownloadProofActivity : Activity() {
    private val scope = MainScope()
    private lateinit var lines: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lines = TextView(this)
        setContentView(ScrollView(this).apply { addView(lines) })
        scope.launch { proof() }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        lines.append(line + "\n")
    }

    /** A switch of the SDK's DebugHooks, by name: the example is an app, and an app cannot name the SDK's internals. */
    private fun hook(name: String, value: Boolean): Boolean = try {
        Class.forName(HOOKS).getField(name).setBoolean(null, value)
        true
    } catch (_: Throwable) {
        false
    }

    /** The lines of the files the SDK downloaded since the last call (DebugHooks.downloads), taken out. */
    private fun fileLines(): List<String> = try {
        @Suppress("UNCHECKED_CAST")
        val q = Class.forName(HOOKS).getField("downloads").get(null) as java.util.Deque<String>
        generateSequence { q.pollFirst() }.toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun names(extra: String): List<String> =
        intent.getStringExtra(extra)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private suspend fun proof() {
        val mode = intent.getStringExtra("mode") ?: "time"
        val on = names("on")
        val off = names("off")
        val runs = intent.getIntExtra("runs", 1)
        try {
            say("START download proof: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), mode $mode, on $on, off $off, runs $runs")
            val set = on.map { it to hook(it, true) } + off.map { it to hook(it, false) }
            if (set.isNotEmpty()) say("RESULT switches: " + set.joinToString(", ") { (n, ok) -> "$n ${if (ok) "set" else "NOT IN THIS SDK"}" })
            val client = Recitation.client(this)
            say("RESULT client: made")
            when (mode) {
                "remove" -> {
                    client.removeModels()
                    say("RESULT removed")
                }
                "fetch" -> fetch(client, 1)
                else -> for (run in 1..runs) {
                    client.removeModels()
                    fileLines()
                    fetch(client, run)
                }
            }
            say("RESULT done: ok")
        } catch (e: TajweedError) {
            for (l in fileLines()) say("FILE $l")
            say("RESULT failed: ${e.code}: ${e.message} What to do: ${e.fix}")
            say("RESULT done: failed")
        } catch (e: Throwable) {
            say("RESULT failed: ${e.javaClass.simpleName}: ${e.message}")
            say("RESULT done: failed")
        }
    }

    private suspend fun fetch(client: ai.quranlab.tajweedformer.TajweedClient, run: Int) {
        val t0 = System.nanoTime()
        var done = 0L
        var total = 0L
        var reports = 0
        client.prefetch { d, t ->
            done = d
            total = t
            if (reports++ % 32 == 0) Log.i(TAG, "progress run $run: $d of $t bytes at ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        for (l in fileLines()) say("FILE run $run: $l")
        say(if (reports == 0) "RESULT run $run: nothing to download, the model was on the device, $ms ms"
            else "RESULT run $run: $done of $total bytes, $reports progress reports, $ms ms")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TJ_DL"
        const val HOOKS = "ai.quranlab.tajweedformer.android.DebugHooks"
    }
}
