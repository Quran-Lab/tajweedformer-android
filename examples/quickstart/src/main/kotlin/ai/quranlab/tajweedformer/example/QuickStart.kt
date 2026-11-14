package ai.quranlab.tajweedformer.example

import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.content.Context
import android.widget.TextView

// The README's quick start, character for character (QuickStartDocTest compares the two, CI compiles this file).
// ExampleActivity makes the same four calls and keeps the Client and the Model for the next take.
// README:begin
suspend fun quickStart(context: Context, heard: TextView) {
    val client = TajweedClient(context, "qlk_v1_...")       // your key from sdk.quranlab.ai; one Client per process
    val model = client.model()                              // the first call downloads and verifies the model
    val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 1500))   // asks for the microphone itself
    session.chunks.collect { chunk -> heard.text = chunk.decoded.text }       // what the model heard so far
}
// README:end
