package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.CountingEngines
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files

/**
 * The suite's check that nothing is left open ([OpenChecked]): every test class of the module has it; it fails a test
 * that leaves a Session or a Model open, by that test's name, saying what was left and where it was made; it lets a
 * test that closes what it opened pass, and a test that fails keep its own failure. And the SDK path that did leave
 * the process's one engine stream taken: a Model closed while a take that had run a chunk was open.
 */
class EveryTestIsOpenCheckedTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val models = ArrayList<TajweedModel>()

    @After
    fun cleanUp() {
        models.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    /** A Model over a small folder, with a stand-in engine that counts its open streams. */
    private fun model(engines: CountingEngines): TajweedModel {
        val dir = Files.createTempDirectory("tj-open-").toFile().also { dirs.add(it) }
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        return TajweedModel.of(ModelFolder.open(dir, TestKit.verifier), ModelHost(verifier = TestKit.verifier, engine = engines)).also { models.add(it) }
    }

    @Test
    fun everyTestClassOfTheModuleExtendsOpenChecked() {
        val root = File(OpenChecked::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue("the test classes are not in a folder: $root", root.isDirectory)
        val without = ArrayList<String>()
        var testClasses = 0
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }.forEach { f ->
            val name = f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
            val c = Class.forName(name, false, javaClass.classLoader)
            if (c.declaredMethods.none { it.isAnnotationPresent(Test::class.java) }) return@forEach
            testClasses++
            if (!OpenChecked::class.java.isAssignableFrom(c)) without.add(name)
        }
        assertTrue("only $testClasses test classes were found under $root", testClasses >= 40)
        assertEquals("test classes that do not extend OpenChecked", emptyList<String>(), without)
    }

    @Test
    fun aTestThatLeavesASessionOpenFailsUnderItsOwnName() {
        val engines = CountingEngines()
        val description = Description.createTestDescription("SomeTest", "leavesATakeOpen")
        var left: TajweedModel? = null
        val leaves = object : Statement() {
            override fun evaluate() {
                left = model(engines)
                runBlocking { left!!.session().push(FloatArray(5120)) }
            }
        }
        val error = try {
            NothingLeftOpen(graceMs = 200).apply(leaves, description).evaluate()
            null
        } catch (e: AssertionError) {
            e
        }
        val text = error?.message ?: ""
        assertTrue("the test that left a take open passed", error != null)
        assertTrue("the failure does not name the test: $text", text.startsWith("SomeTest.leavesATakeOpen left "))
        assertTrue("it does not name the Session: $text", text.contains("Session (push)"))
        assertTrue("it does not name the Model: $text", text.contains("Model 4.0.0"))
        assertTrue("it does not say where each was made: $text", text.contains("made at ") && text.contains("TajweedModel"))
        // closing the Model ends its Session: the same body with the close passes
        left!!.close()
        NothingLeftOpen(graceMs = 2_000).apply(object : Statement() {
            override fun evaluate() {
                val m = model(engines)
                runBlocking {
                    val s = m.session()
                    s.push(FloatArray(5120))
                    s.finish()
                }
                m.close()
            }
        }, description).evaluate()

        // a test that fails keeps its own failure, whatever it left
        var orphan: TajweedModel? = null
        try {
            NothingLeftOpen(graceMs = 200).apply(object : Statement() {
                override fun evaluate() {
                    orphan = model(engines)
                    throw IllegalStateException("the test's own failure")
                }
            }, description).evaluate()
            fail("the test's own failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("the test's own failure", e.message)
        }
        orphan!!.close()
        assertEquals("every stream of the stand-in engines is closed", 0, engines.openStreams)
    }

    /**
     * The path that left the process's one engine stream taken for good: a Model closed while a take was open that
     * had already run a chunk. The take's stream was closed by a coroutine which the close of the engine cancelled
     * before it ran. Many rounds: it was a race.
     */
    @Test
    fun closingAModelWithATakeOpenClosesTheTakesEngineStream() = runBlocking {
        withTimeout(120_000) {
            repeat(25) { round ->
                val engines = CountingEngines()
                val m = model(engines)
                val s = m.session()
                s.push(FloatArray(5120 * 2))
                assertEquals("round $round: the take's stream is open on the engine", 1, engines.openStreams)
                m.close()
                val until = System.nanoTime() + 5_000_000_000L
                while (engines.openStreams != 0 && System.nanoTime() < until) delay(2)
                assertEquals("round $round: closing the Model left the take's engine stream open", 0, engines.openStreams)
                try {
                    s.finish()
                    fail("round $round: a take whose Model was closed finished")
                } catch (e: TajweedError) {
                    assertEquals("cancelled", e.code)
                }
            }
        }
    }
}
