package ai.quranlab.tajweedformer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README's quick start is the example app's source, character for character: CI builds
 * `examples/quickstart` (`:examples:quickstart:assembleDebug`), so the code the README shows is code that
 * builds. Compared: the lines between the README:begin and README:end marks of QuickStart.kt, and the
 * first Kotlin block under "## Quick start" in README.md. The quick start is the four calls (Client, model,
 * listen, read) in ten lines or fewer, with `endOnSilenceMs` set and no sample rate.
 */
class QuickStartDocTest : OpenChecked() {
    /** The repository's root: the nearest folder above the tests' working directory that holds settings.gradle.kts. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun text(path: String): String = File(root, path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun theReadmeShowsTheSampleApp() {
        val lines = text(QUICK_START).split("\n")
        val begin = lines.indexOfFirst { "README:begin" in it }
        val end = lines.indexOfFirst { "README:end" in it }
        assertTrue("QuickStart.kt has no pair of README marks", begin in 0 until end)
        assertEquals("QuickStart.kt has more than one pair of README marks", 2, lines.count { "README:begin" in it || "README:end" in it })
        val code = lines.subList(begin + 1, end).joinToString("\n") + "\n"
        assertTrue("the quick start is ${end - begin - 1} lines: ten or fewer", end - begin - 1 <= 10)
        for (call in listOf("TajweedClient(", ".model()", ".listen(", "endOnSilenceMs", ".chunks.collect", ".decoded.text")) {
            assertTrue("the quick start lacks $call", call in code)
        }
        assertTrue("the quick start names a sample rate", "sampleRate" !in code && "48_000" !in code && "16_000" !in code)

        val section = Regex("(?ms)^## Quick start[ \\t]*\\n(.*?)(?=^## |\\z)").find(text("README.md"))
        assertNotNull("README.md has no \"## Quick start\" section", section)
        val block = Regex("(?ms)^```kotlin[ \\t]*\\n(.*?)^```[ \\t]*$").find(section!!.groupValues[1])
        assertNotNull("the quick start has no Kotlin block", block)
        assertEquals("README.md's quick start and examples/quickstart differ", code, block!!.groupValues[1])
    }

    /** The SDK's own manifest declares the microphone permission `listen()` asks for, and the README names it. */
    @Test
    fun theSdkDeclaresTheMicrophonePermission() {
        val manifest = text("tajweedformer/src/main/AndroidManifest.xml")
        assertTrue("the SDK's manifest lacks RECORD_AUDIO", "android.permission.RECORD_AUDIO" in manifest)
        assertTrue("README.md does not name RECORD_AUDIO", "android.permission.RECORD_AUDIO" in text("README.md"))
    }

    /**
     * The example is one screen, in this order: the model loading with its download progress, "Play sample" (a sample
     * clip through `listen`, no microphone), "Recite" (the microphone), the decoded tokens with the tentative ones
     * dimmed, and "Diagnose". It also shows a new take after the background and the fix of an error. It judges
     * nothing: no expected ayah, nothing called a mistake.
     */
    @Test
    fun theExampleIsTheOneScreen() {
        val app = text("examples/quickstart/src/main/kotlin/ai/quranlab/tajweedformer/example/ExampleActivity.kt")
        val order = listOf("client.prefetch", "client.model()", "override fun onStart()", "model.listen(clip, options)", "s.finish()", "decoded.settled", ".diagnose()")
        val at = order.map { app.indexOf(it) }
        assertTrue("the example lacks one of $order: $at", at.all { it >= 0 })
        assertEquals("the parts of the example are not in the order of the screen", at.sorted(), at)
        // the screen itself: the status line, the two buttons, what was heard, the diagnosis
        assertTrue("the views are not in the order of the screen", "listOf(status, sample, recite, stop, heard, diagnose, report)" in app)
        assertTrue("no Stop button that ends the take", "\"Stop\"" in app && "s.finish()" in app)
        for (label in listOf("\"Play sample\"", "\"Recite\"", "\"Diagnose\"")) assertTrue("no button $label", label in app)
        assertTrue("no take from a sample clip", "sampleClips" in app)
        assertTrue("the tentative tokens are not drawn apart", "ForegroundColorSpan" in app)
        val onStart = app.substringAfter("override fun onStart() {", "").substringBefore("\n    }")
        assertTrue("no new take in onStart", "if (reciting) take()" in onStart)
        assertTrue("the example does not show the fix of an error", "e.fix" in app)
        val judging = Regex("(?i)mistake|expected|reference|calibration|correct|wrong").find(app)
        assertTrue("the example judges a recitation: ${judging?.value}", judging == null)
        assertTrue("one file an assistant can read in one sitting: ${app.lines().size} lines", app.lines().size <= 150)
    }

    private companion object {
        const val QUICK_START = "examples/quickstart/src/main/kotlin/ai/quranlab/tajweedformer/example/QuickStart.kt"
    }
}
