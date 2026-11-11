package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.engine.ExactTajweedEngine
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The base of every test class of this module: a test that ends with a Client, a Model or a Session still open (or
 * the engine objects under them, or the process's one engine stream) FAILS, under its own name, with the list of
 * what it left and where each was made. So the cause of a `session_open` in a later test names itself: it is the test
 * that failed here, not the one that met the error.
 *
 * What counts: everything made during the test (its `@Before` and `@After` included) that is still open when the
 * test and its `@After` methods are done. What a class opens once for all its tests (`@BeforeClass`) was open
 * before the test and is not the test's. An end that is on its way (a take that ends by itself, an engine closing
 * on its own thread) gets [NothingLeftOpen.GRACE_MS] to land. A test that failed keeps its own failure.
 *
 * `EveryTestIsOpenCheckedTest` fails when a test class of the module does not extend this.
 */
abstract class OpenChecked {
    @get:Rule
    val nothingLeftOpen: TestRule = NothingLeftOpen()
}

/** The check of [OpenChecked], as a JUnit rule. [graceMs]: how long an end that is on its way may take. */
internal class NothingLeftOpen(private val graceMs: Long = GRACE_MS) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            OpenObjects.traces = true
            val before = OpenObjects.snapshot()
            val streamBefore = streamLive()
            base.evaluate()
            val left = leftOpen(before, streamBefore, graceMs)
            if (left.isNotEmpty()) {
                throw AssertionError("${description.className}.${description.methodName} left ${left.size} open:\n  " + left.joinToString("\n  "))
            }
        }
    }

    companion object {
        /** How long an end that is on its way may take, ms. */
        const val GRACE_MS = 3_000L

        private fun streamLive(): Boolean = try { ExactTajweedEngine.streamLive } catch (_: Throwable) { false }

        /** What was made since [before] and is still open after the grace time; empty when nothing is. */
        fun leftOpen(before: Map<Any, String>, streamBefore: Boolean, graceMs: Long = GRACE_MS): List<String> {
            val until = System.nanoTime() + graceMs * 1_000_000
            while (true) {
                val left = ArrayList<String>()
                for ((o, what) in OpenObjects.snapshot()) if (!before.containsKey(o)) left.add(what)
                if (!streamBefore && streamLive()) left.add("the process's one engine stream (a take's stream was not closed: every later take is session_open)")
                if (left.isEmpty() || System.nanoTime() >= until) return left.sorted()
                Thread.sleep(10)
            }
        }
    }
}
