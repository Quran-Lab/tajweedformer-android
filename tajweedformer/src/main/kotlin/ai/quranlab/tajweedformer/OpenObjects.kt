package ai.quranlab.tajweedformer

import java.util.Collections
import java.util.WeakHashMap

/**
 * What this process holds open: every Client, Model and Session, and the engine objects under them, from the moment
 * each is made until it is closed or ended. The SDK decides nothing from it. It is there so that something left open
 * can be named: the test suite fails a test that ends with one of these still open, by that test's name, so a
 * `session_open` in a later test points at the test (or the SDK path) that caused it.
 *
 * The list holds its objects weakly: one the app dropped without closing is not kept alive by being listed here.
 */
internal object OpenObjects {
    private val open: MutableMap<Any, String> = Collections.synchronizedMap(WeakHashMap())

    /** With it on, an entry also says where the object was made (the tests turn it on). */
    @Volatile var traces = false

    /** [o] is open from now on; [what] names it in a report (`Session`, `Model 4.0.0`). */
    fun opened(o: Any, what: String) {
        open[o] = if (traces) "$what, made at " + where() else what
    }

    /** [o] is closed (a second call is harmless). */
    fun closed(o: Any) {
        open.remove(o)
    }

    /** What is open now, with the name each was listed under. */
    fun snapshot(): Map<Any, String> = synchronized(open) { java.util.IdentityHashMap<Any, String>().also { m -> for ((k, v) in open) m[k] = v } }

    /** The SDK's own frames above the constructor that listed the object, nearest first. */
    private fun where(): String = Throwable().stackTrace.asSequence()
        .filter { it.className != OpenObjects::class.java.name && it.methodName != "<init>" }
        .take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
