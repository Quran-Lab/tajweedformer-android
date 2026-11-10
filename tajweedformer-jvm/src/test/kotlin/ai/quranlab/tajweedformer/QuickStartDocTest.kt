package ai.quranlab.tajweedformer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README's quick start is the sample app's source, character for character: CI compiles
 * `samples/quickstart` (`:samples:quickstart:compileDebugKotlin`), so the code the README shows is code that
 * builds. Compared: the lines between the README:begin and README:end marks of QuickStartActivity.kt, and the
 * first Kotlin block under "## Quick start" in README.md.
 */
class QuickStartDocTest {
    /** The repository's root: the nearest folder above the tests' working directory that holds settings.gradle.kts. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun text(path: String): String = File(root, path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun theReadmeShowsTheSampleApp() {
        val lines = text("samples/quickstart/src/main/kotlin/ai/quranlab/tajweedformer/quickstart/QuickStartActivity.kt").split("\n")
        val begin = lines.indexOfFirst { "README:begin" in it }
        val end = lines.indexOfFirst { "README:end" in it }
        assertTrue("QuickStartActivity.kt has no pair of README marks", begin in 0 until end)
        val code = lines.subList(begin + 1, end).joinToString("\n") + "\n"

        val section = Regex("(?ms)^## Quick start[ \\t]*\\n(.*?)(?=^## |\\z)").find(text("README.md"))
        assertNotNull("README.md has no \"## Quick start\" section", section)
        val block = Regex("(?ms)^```kotlin[ \\t]*\\n(.*?)^```[ \\t]*$").find(section!!.groupValues[1])
        assertNotNull("the quick start has no Kotlin block", block)
        assertEquals("README.md's quick start and samples/quickstart differ", code, block!!.groupValues[1])
    }

    /** The sample's manifest declares the microphone permission the README names. */
    @Test
    fun theSampleDeclaresTheMicrophonePermission() {
        val manifest = text("samples/quickstart/src/main/AndroidManifest.xml")
        assertTrue("the sample manifest lacks RECORD_AUDIO", "android.permission.RECORD_AUDIO" in manifest)
        assertTrue("README.md does not name RECORD_AUDIO", "android.permission.RECORD_AUDIO" in text("README.md"))
    }
}
