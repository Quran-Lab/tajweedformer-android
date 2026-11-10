package ai.quranlab.tajweedformer.quickstart

// README:begin
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedRecognizer
import ai.quranlab.tajweedformer.android.Microphone
import ai.quranlab.tajweedformer.android.TajweedFormerAndroid
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickStartActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val heard = TextView(this).also { setContentView(it) }
        QuranLabAndroid.configure(this, CloudOptions(key = "qlk_v1_..."))   // your key from portal.quranlab.ai
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        scope.launch {
            // the first run downloads and verifies the model; the microphone and the recognizer both run at 48 kHz
            val recognizer = TajweedFormerAndroid.client(this@QuickStartActivity)
                .recognizer(TajweedRecognizer.Options(sampleRate = 48_000))
            val take = recognizer.startSession()
            val chunks = mutableListOf<TajweedHeads>()
            launch {   // every 320 ms: what the model heard so far
                take.chunks.collect { chunk ->
                    if (chunk.index < chunks.size) chunks.subList(chunk.index, chunks.size).clear()   // a chunk sent again
                    chunks += chunk.heads
                    heard.text = recognizer.decode(TajweedHeads.concat(chunks)).text
                }
            }
            Microphone.samples(48_000).collect { samples -> take.append(samples) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
// README:end
