package ai.quranlab.tajweedformer

import java.io.File

/**
 * The engine's default thread count on a phone: the big and mid cores, capped at 4 (the engine's
 * measured best; one thread per core, the engine pins them). Cores are ranked as the engine's pool
 * ranks them: `/sys/devices/system/cpu/cpuN/cpu_capacity`, else `cpufreq/cpuinfo_max_freq`.
 *
 * Why not leave 0 to the engine: the engine's own `threads = 0` counts the fast cores of the CALLING
 * thread's cpuset, and an app's thread can be in a reduced cpuset when the recognizer is opened (behind
 * the lock screen Android puts the app in `background`: 2 small cores on the Alcatel 1), which then
 * fixes the team at that size. The sysfs topology does not depend on the cpuset; the engine's pool still
 * follows the cpuset at run time (fewer active threads while it is reduced, all of them in front).
 */
internal object CoreTopology {
    /** The cap: more threads than 4 do not pay on the measured phones. */
    const val CAP = 4

    /**
     * The default thread count for these per-core speeds (capacity or max frequency; 0 or negative =
     * unknown): every core when they are all one cluster, else every core outside the slowest cluster
     * (big + mid), capped at [cap]; 0 (the engine decides) when nothing is known. Speeds within 10 % of
     * each other are one cluster.
     */
    @JvmStatic
    @JvmOverloads
    fun defaultThreads(speeds: List<Long>, cap: Int = CAP): Int {
        val known = speeds.filter { it > 0 }
        if (known.isEmpty()) return 0
        val sorted = known.sorted()
        // the slowest cluster: the cores within 10 % of the slowest one
        val slowest = sorted.first()
        val little = known.count { it * 10 < slowest * 11 }
        val n = if (little == known.size) known.size else known.size - little
        return n.coerceIn(1, cap)
    }

    /** Each CPU's speed from sysfs (empty off Linux or when unreadable). */
    @JvmStatic
    @JvmOverloads
    fun readSpeeds(root: File = File("/sys/devices/system/cpu")): List<Long> {
        val cpus = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) } ?: return emptyList()
        return cpus.sortedBy { it.name.removePrefix("cpu").toInt() }.map { c ->
            fun read(rel: String) = try { File(c, rel).readText().trim().toLong() } catch (_: Exception) { 0L }
            read("cpu_capacity").takeIf { it > 0 } ?: read("cpufreq/cpuinfo_max_freq")
        }
    }

    /** True on Android (the default applies there; the desktop passes 0 through to the engine). */
    internal val isAndroid: Boolean by lazy {
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            try { Class.forName("android.os.Build"); true } catch (_: Throwable) { false }
    }

    /** The thread count to open the engine with: [requested] when the app set one, else on Android the
     *  sysfs default, else 0 (the engine's own choice). */
    @JvmStatic
    fun resolve(requested: Int): Int = when {
        requested > 0 -> requested
        isAndroid -> defaultThreads(readSpeeds())
        else -> 0
    }
}
