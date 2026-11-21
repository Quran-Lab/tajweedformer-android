package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * SDK 4.0 (Quran Lab's decision of 11-16): anonymous analytics are always on. No public API of the SDK turns them off:
 * the option of 3.x (`TajweedClientOptions.analytics`, `TajweedClientOptions.Analytics`) is removed, and nothing in the
 * public surface (the classes, their members, every public declaration docs/api.md lists) offers a switch. Quran Lab's
 * signed config (`flags.analytics`) stays the only switch: `CloudTest.theServersSwitchIsTheOnlyOffSwitchAndDropsEverything`.
 */
class AnalyticsAlwaysOnTest : OpenChecked() {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /** A name that would turn analytics or telemetry off, or switch them. */
    private val switch = Regex("(?i)(analytics|telemetry|usage|statistics).*(off|enable|disable|opt|consent|switch|set)|(opt.?out|disable.*(analytics|telemetry))")

    @Test
    fun theClientOptionsHaveNoAnalyticsOption() {
        val c = TajweedClientOptions::class.java
        assertFalse(c.declaredFields.any { it.name.equals("analytics", ignoreCase = true) })
        assertFalse(c.methods.any { it.name.equals("getAnalytics", ignoreCase = true) })
        assertFalse("no Analytics enum", c.declaredClasses.any { it.simpleName == "Analytics" })
        // and no constructor takes an analytics argument: every parameter is one of the four that remain (the audience
        // option went too, contract section 26: OneSheetApiTest), and 4.0.1's appBuild (section 27)
        val kept = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("appId", "appVersion", "storage", "unmeteredOnly", "appBuild"), kept)
        // the Kotlin constructor with every argument named: what an app can pass
        val full = TajweedClientOptions(appId = "a", appVersion = "1", storage = null, unmeteredOnly = false)
        assertEquals("a", full.appId)
    }

    @Test
    fun noPublicMemberTurnsAnalyticsOff() {
        // the app's counters: report calls only
        val a = TajweedAnalytics::class.java.methods.filter { it.declaringClass == TajweedAnalytics::class.java }.map { it.name }
        assertEquals(listOf("reportChecks"), a)
        for (type in listOf(TajweedClient::class.java, TajweedClientOptions::class.java, TajweedSession::class.java, TajweedModel::class.java,
            TajweedSharing::class.java, TajweedAnalytics::class.java)) {
            val bad = type.methods.filter { Modifier.isPublic(it.modifiers) && switch.containsMatchIn(it.name) }.map { "${type.simpleName}.${it.name}" }
            assertTrue("a public switch of analytics: $bad", bad.isEmpty())
        }
    }

    @Test
    fun thePublicApiPageOffersNoAnalyticsSwitch() {
        // docs/api.md lists every public declaration (PublicContractTest keeps it complete)
        val page = File(root, "docs/api.md").readLines()
        val bad = page.filter { l ->
            Regex("(?i)\\banalytics\\b\\s*:\\s*(Analytics|Boolean)").containsMatchIn(l) || Regex("enum class Analytics\\b").containsMatchIn(l) ||
                switch.containsMatchIn(l.substringBefore("//"))
        }
        assertTrue("public declarations that switch analytics: $bad", bad.isEmpty())
        // the one public analytics member is the app's counters
        assertTrue(page.any { it.trim() == "public val analytics: TajweedAnalytics" })
    }
}
