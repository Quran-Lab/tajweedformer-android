package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.sharing.ConsentWording
import ai.quranlab.tajweedformer.sharing.SharingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * SDK 4.0, contract section 26 (Quran Lab, 2026-11-19): one sharing sheet for every app. The `audience` option of 3.x
 * is removed, not deprecated and not a no-op, as the analytics option was (22.2), and there is no guardian variant: no
 * public member, parameter, enum or constant named `audience`, `Audience`, `children`, `guardian` or
 * `guardianConfirmed` (26.4), in the code of any module, in docs/api.md or in NAMES.md; the deck's four
 * `sheet.guardian.*` keys are gone (deck 2026-11-19.1) and an app's own wording that names one is refused as an
 * unknown key. A 3.x grant given on a guardian sheet stays a grant: SharingTest.aYesGivenOnA3xGuardianSheetStaysAYesAndItsFieldPassesThrough.
 */
class OneSheetApiTest : OpenChecked() {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /** The names 26.4 rules out, as whole identifiers, in any case (`CHILDREN`, `Audience`, `guardianConfirmed`). */
    private val ruledOut = Regex("(?i)(?<![A-Za-z0-9_])(audience|children|guardian|guardianConfirmed)(?![A-Za-z0-9_])")

    /** Every source of the library (all source sets) and of the Compose module. */
    private fun sources(): List<File> = listOf("tajweedformer/src/main/kotlin", "tajweedformer/src/android/kotlin", "tajweedformer/src/jvm/kotlin",
        "tajweedformer-compose/src/main/kotlin").flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** [src] without its comments and string literals: the code's own names. */
    private fun code(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\""), "\"\"")

    @Test
    fun noDeclarationIsNamedForAnAudienceOrAGuardian() {
        val hits = sources().flatMap { f ->
            code(f.readText()).lines().mapIndexedNotNull { i, l -> ruledOut.find(l)?.let { "${f.name}: ${l.trim().take(120)}" } }
        }
        assertEquals("names of the removed audience option or guardian variant", emptyList<String>(), hits)
        // the check sees such a name where it is code, and not in a comment or a string
        assertTrue(ruledOut.containsMatchIn(code("public val audience: Audience = Audience.GENERAL")))
        assertTrue(ruledOut.containsMatchIn(code("fun yes(guardianConfirmed: Boolean)")))
        assertFalse(ruledOut.containsMatchIn(code("val x = \"guardian\" // the guardian of 3.x\n/** children */")))
    }

    @Test
    fun theClientOptionsHaveNoAudience() {
        val c = TajweedClientOptions::class.java
        val names = c.declaredFields.map { it.name } + c.methods.map { it.name } + c.declaredClasses.map { it.simpleName }
        assertEquals(emptyList<String>(), names.filter { ruledOut.containsMatchIn(it.removePrefix("get")) })
        // 4.0.1 adds appBuild
        assertEquals(setOf("appId", "appVersion", "storage", "unmeteredOnly", "appBuild"),
            c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        // the public sharing API: no member for a variant of the sheet
        for (k in listOf(TajweedClient::class.java, TajweedSharing::class.java)) {
            assertEquals(k.simpleName, emptyList<String>(),
                (k.methods.map { it.name } + k.declaredFields.map { it.name } + k.declaredClasses.map { it.simpleName }).filter { ruledOut.containsMatchIn(it.removePrefix("get")) })
        }
    }

    @Test
    fun theApiPagesNameNoAudienceOrGuardian() {
        for (page in listOf("docs/api.md", "NAMES.md")) {
            val hits = File(root, page).readLines().filter { ruledOut.containsMatchIn(it) }
            assertEquals(page, emptyList<String>(), hits)
        }
    }

    @Test
    fun theDeckHasNoGuardianKeyAndAnAppsWordingForOneIsRefused() {
        val removed = listOf("sheet.guardian.title", "sheet.guardian.body", "sheet.guardian.confirm", "sheet.guardian.share")
        for (lang in SharingCopy.LANGUAGES) {
            val c = SharingCopy.forLanguage(lang)
            assertEquals(lang, "2026-11-19.1", c.version)
            assertEquals(lang, emptyList<String>(), c.keys.filter { it.startsWith("sheet.guardian") })
        }
        assertTrue(removed.none { it in ConsentWording.KEYS })
        for (k in removed) {
            try {
                ConsentWording.validate(mapOf(k to "Share"))
                fail("$k accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals("unknown copy key: $k", e.message)
            }
        }
        // the same rule as for any unknown key; a key the deck has is taken
        assertEquals(mapOf("sheet.title" to "Help other learners"), ConsentWording.validate(mapOf("sheet.title" to "Help other learners")))
    }
}
