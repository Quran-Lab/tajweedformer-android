package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The production pin (integration, beside the unit tests' test release): the Core release the AAR ships, named by
 * native/core/CORE_RELEASE (4.0.0-c3 and its shipped files' SHA-256), checked in the release folder TJ_CORE_PRODUCTION
 * names (what `-PcoreShip` takes: `ship/android-<abi>/qlcore.so` and `record.json`):
 * - the record is that release, and names each Android file with the SHA-256 CORE_RELEASE pins;
 * - each file is that file, byte for byte;
 * - each file's dynamic symbols name every function native/core/qlcore.h declares (QLC_API), the grace's two among
 *   them, so the header vendored here is that binary's interface and the JNI bridge binds all it asks for.
 * Skipped without TJ_CORE_PRODUCTION: the production Core is never on the CI runner. Every other Core test runs on the
 * test release 4.0.0-c1 (the contract's public test root), built from the same commit.
 */
class CoreReleasePinTest : OpenChecked() {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "native/core/qlcore.h").isFile }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun theShippedCoreIsThePinnedRelease() {
        val rel = (System.getProperty("TJ_CORE_PRODUCTION") ?: System.getenv("TJ_CORE_PRODUCTION"))?.let(::File)
        assumeTrue("TJ_CORE_PRODUCTION names no Core release folder", rel != null)
        val pin = File(root, "native/core/CORE_RELEASE").readLines().filter { it.isNotBlank() }
        val release = pin.first { it.startsWith("release ") }.substringAfter("release ").trim()
        val files = pin.filter { it.startsWith("android-") }.associate { l -> l.split(" ").let { "ship/${it[0]}/${it[1]}" to it[2] } }
        assertEquals(3, files.size)
        @Suppress("UNCHECKED_CAST")
        val record = Json.parse(File(rel, "record.json").readText()) as Map<String, Any?>
        assertEquals(release, record["release"])
        @Suppress("UNCHECKED_CAST")
        val recFiles = record["files"] as Map<String, String>
        val header = File(root, "native/core/qlcore.h").readText()
        val names = Regex("""QLC_API\s+[^;(]*?\b(qlc_[a-z0-9_]+)\s*\(""").findAll(header).map { it.groupValues[1] }.toSet()
        assertTrue("the header's functions (${names.size}), the grace's two among them", names.size >= 40 && "qlc_lease_attempt" in names && "qlc_lease_grace" in names)
        for ((path, sha) in files) {
            assertEquals("$path in the record", sha, recFiles[path])
            val f = File(rel, path)
            assertEquals("$path is the pinned file", sha, sha256(f))
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            val missing = names.filter { "\u0000$it\u0000" !in text }
            assertTrue("$path exports every function of qlcore.h; missing: $missing", missing.isEmpty())
        }
        println("production pin $release: ${files.size} Android files, ${names.size} functions each")
    }
}
