package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** The default engine thread count from the core topology (big + mid cores, capped at 4). */
class CoreTopologyTest : OpenChecked() {
    @Test
    fun theDefaultIsTheBigAndMidCoresCappedAtFour() {
        // 4 little cores (the Alcatel 1: 4 x Cortex-A53, all one cluster): all 4
        assertEquals(4, CoreTopology.defaultThreads(listOf(1_495_000, 1_495_000, 1_495_000, 1_495_000)))
        // 4 + 4 big.LITTLE (capacities): the 4 big
        assertEquals(4, CoreTopology.defaultThreads(listOf(400, 400, 400, 400, 1024, 1024, 1024, 1024)))
        // 1 + 3 + 4 (prime, mid, little): 1 + 3
        assertEquals(4, CoreTopology.defaultThreads(listOf(160, 160, 160, 160, 700, 700, 700, 1024)))
        // 8 equal cores: capped at 4
        assertEquals(4, CoreTopology.defaultThreads(List(8) { 2_000_000L }))
        // 2 + 6 (two big, six little): the 2 big
        assertEquals(2, CoreTopology.defaultThreads(listOf(1_800_000, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_200_000, 2_200_000)))
        // 2 equal cores; nothing known (the engine decides)
        assertEquals(2, CoreTopology.defaultThreads(listOf(1000, 1000)))
        assertEquals(0, CoreTopology.defaultThreads(listOf(0, 0, 0)))
        assertEquals(0, CoreTopology.defaultThreads(emptyList()))
        // speeds within 10 % are one cluster
        assertEquals(4, CoreTopology.defaultThreads(listOf(1000, 1050, 1000, 1080)))
        // an app's explicit count wins; the desktop JVM passes 0 through
        assertEquals(3, CoreTopology.resolve(3))
        assertEquals(0, CoreTopology.resolve(0))
    }

    @Test
    fun speedsComeFromCapacityElseMaxFrequency() {
        val root = Files.createTempDirectory("tj-cpu-").toFile()
        try {
            fun cpu(i: Int, capacity: Long?, freq: Long?) {
                val d = java.io.File(root, "cpu$i").also { it.mkdirs() }
                capacity?.let { java.io.File(d, "cpu_capacity").writeText("$it\n") }
                freq?.let { java.io.File(d, "cpufreq").mkdirs(); java.io.File(d, "cpufreq/cpuinfo_max_freq").writeText("$it\n") }
            }
            cpu(0, 400, 1_800_000); cpu(1, 400, 1_800_000); cpu(2, null, 2_800_000); cpu(10, 1024, null)
            java.io.File(root, "cpufreq").mkdirs()   // not a cpuN folder
            assertEquals(listOf(400L, 400L, 2_800_000L, 1024L), CoreTopology.readSpeeds(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
