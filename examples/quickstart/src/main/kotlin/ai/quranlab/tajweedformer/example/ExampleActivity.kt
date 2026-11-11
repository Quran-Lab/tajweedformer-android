package ai.quranlab.tajweedformer.example

import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClip
import ai.quranlab.tajweedformer.TajweedDecoded
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedEvent
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The one Client of the process: made on first use, kept for the life of the app. The key is yours from
 *  sdk.quranlab.ai; this example takes it from the build (QURANLAB_KEY, see README.md), your app has it as a string. */
object Recitation {
    @Volatile private var made: TajweedClient? = null

    fun client(context: Context): TajweedClient = made ?: synchronized(this) {
        made ?: TajweedClient(context.applicationContext, context.getString(R.string.quranlab_key)).also { made = it }
    }
}

/**
 * The smallest screen around the SDK, top to bottom: the model loading with its download progress, "Play sample" (a
 * sample clip of the model through `listen`, so output appears with no microphone), "Recite" (the microphone) with
 * "Stop", what the model heard as it comes (settled tokens as they are, tentative ones dimmed), and "Diagnose".
 * QuickStart.kt has the four calls the README shows.
 */
class ExampleActivity : Activity() {
    private val scope = MainScope()
    private val options = TajweedSessionOptions(endOnSilenceMs = 1500)   // a take ends by itself after 1.5 s of silence
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var report: TextView
    private var model: TajweedModel? = null
    private var session: TajweedSession? = null
    private var take: Job? = null
    /** A microphone take is running, or the app left the foreground while one was. */
    private var reciting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        heard = TextView(this).apply { textSize = 22f }
        report = TextView(this)
        val sample = Button(this).apply { text = "Play sample"; setOnClickListener { take(model?.sampleClips?.firstOrNull()) } }
        val recite = Button(this).apply { text = "Recite"; setOnClickListener { take() } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stop() } }
        val diagnose = Button(this).apply { text = "Diagnose"; setOnClickListener { diagnose() } }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (view in listOf(status, sample, recite, stop, heard, diagnose, report)) column.addView(view)
        setContentView(ScrollView(this).apply { addView(column) })
        scope.launch {
            try {
                val client = Recitation.client(this@ExampleActivity)
                // the first run downloads and verifies the model; later runs find it on the device and report nothing
                client.prefetch { done, total -> runOnUiThread { status.text = "Downloading the model: ${done shr 20} of ${total shr 20} MB" } }
                status.text = "Opening the model"
                model = client.model().also { status.text = "Ready: model ${it.version}" }
            } catch (e: TajweedError) {
                show(e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (reciting) take()   // back from the background: the take ended there (Ended(BACKGROUND)), start a new one
    }

    /** One take: from the microphone, or from [clip] when there is one. */
    private fun take(clip: TajweedClip? = null) {
        val model = model ?: return
        session?.cancel()   // one open Session per Model
        take?.cancel()
        reciting = clip == null
        take = scope.launch {
            try {
                val s = if (clip == null) model.listen(options) else model.listen(clip, options)
                session = s
                status.text = if (clip == null) "Listening" else "Playing ${clip.name}"
                launch {
                    s.events.collect { event ->
                        if (event !is TajweedEvent.Ended) return@collect
                        status.text = "Ended: ${event.reason}"
                        if (event.reason != TajweedEvent.Ended.Reason.BACKGROUND) reciting = false
                    }
                }
                s.chunks.collect { chunk -> draw(chunk.decoded) }
            } catch (e: TajweedError) {
                show(e)
            }
        }
    }

    /** Ends the take now and keeps what was heard. In a noisy room a take may not end on silence: keep this button. */
    private fun stop() {
        reciting = false
        val s = session ?: return
        scope.launch { try { s.finish() } catch (e: TajweedError) { show(e) } }
    }

    /** What the model heard in the take so far. The first `settled` tokens will not change any more; the ones after
     *  them may still grow or be replaced by the next chunk, and are drawn dimmed. */
    private fun draw(decoded: TajweedDecoded) {
        val names = decoded.tokens.map { it.name }
        val settled = names.take(decoded.settled).joinToString(" ").length
        val text = SpannableString(names.joinToString(" "))
        if (settled < text.length) text.setSpan(ForegroundColorSpan(Color.GRAY), settled, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        heard.text = text
    }

    /** How the SDK stands on this device, as text: safe to paste into an issue. */
    private fun diagnose() {
        scope.launch { report.text = Recitation.client(this@ExampleActivity).diagnose().toJson() }
    }

    /** Every failure is a TajweedError: a code, what happened, and what to do about it. */
    private fun show(e: TajweedError) {
        if (e.code != "cancelled") status.text = "${e.code}: ${e.message}\n${e.fix}\n${e.docs}"
    }

    override fun onDestroy() {
        session?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
