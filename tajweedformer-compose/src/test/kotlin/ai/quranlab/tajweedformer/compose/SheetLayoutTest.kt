package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.SheetIconDrawable
import ai.quranlab.tajweedformer.SheetKit
import ai.quranlab.tajweedformer.SheetSwitch
import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.SheetDesign
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the consent sheet's design asks of a drawing, checked on both of the SDK's drawings (the Views dialog and the
 * Compose sheet) as Android's own renderer lays them out: the deck's lines whole and nothing else, the name in every
 * language whatever the theme, both buttons the same size at every text size, the guardian's share button disabled
 * until the switch is on, the direction the deck's and not the device's, and the 4.5 to 1 fallback of an accent.
 */
class SheetLayoutTest : SheetTestBase() {
    private val languages = SharingCopy.LANGUAGES

    private fun List<SemanticsNode>.button(label: String) = single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button && it.text() == label }
    private fun List<SemanticsNode>.withText(t: String) = filter { it.text() == t }

    /** The lines of a TextView, laid out. */
    private fun TextView.lines() = layout?.lineCount ?: 0

    /** The lines of a Compose text node. */
    private fun SemanticsNode.lines(): Int {
        val out = ArrayList<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()?.lineCount ?: 0
    }

    @Test fun everyLineOfTheDeckIsDrawnWholeAndNothingElse() {
        for (lang in languages) for (guardian in listOf(false, true)) for (variant in listOf(ConsentState.Variant.FIRST, ConsentState.Variant.REASK)) {
            val case = Case(lang, variant = variant, guardian = guardian)
            val want = expectedLines(case.copy, variant, guardian)
            use(case)
            val (screen, _) = views(case)
            shoot(screen) {
                assertEquals("views $lang guardian=$guardian $variant", want, screen.texts().map { it.text.toString() }.toSet())
            }
            val c = compose(case)
            shoot(c) {
                val drawn = c.nodes(merged = false).mapNotNull { it.text() }.toSet()
                assertEquals("compose $lang guardian=$guardian $variant", want, drawn)
            }
        }
    }

    /**
     * SPEC 11.8: what both drawings draw, in each of the eight languages, in the learner's and the
     * guardian's variant, first ask and reask, holds nowhere the sentence that deck 2026-11-12.1 added. The sentence is
     * known only by its length and SHA-256 per language (the JVM tests' `sharing_vectors/deck_2026-11-12.1_added.json`,
     * made by the release tooling from the contract's archived deck); a drawn text holds it when a window of that
     * length has that hash.
     */
    @Test fun theSentenceThatDeck2026_11_12_1AddedIsDrawnNowhere() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        @Suppress("UNCHECKED_CAST")
        val doc = ai.quranlab.tajweedformer.Json.parse(java.io.File(root, "tajweedformer-jvm/src/test/resources/sharing_vectors/deck_2026-11-12.1_added.json")
            .readText()) as Map<String, Any?>
        assertEquals("2026-11-12.1", doc["added_by"])
        @Suppress("UNCHECKED_CAST")
        val added = (doc["languages"] as Map<String, Map<String, Any?>>).mapValues { (_, v) -> (v["length"] as Double).toInt() to v["sha256"] as String }
        assertEquals(languages.toSet(), added.keys)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun holds(t: String, f: Pair<Int, String>): Boolean = (0..t.length - f.first).any { i ->
            md.digest(t.substring(i, i + f.first).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } == f.second
        }
        for (lang in languages) for (guardian in listOf(false, true)) for (variant in listOf(ConsentState.Variant.FIRST, ConsentState.Variant.REASK)) {
            val case = Case(lang, variant = variant, guardian = guardian)
            val f = added.getValue(lang)
            use(case)
            val (screen, _) = views(case)
            shoot(screen) {
                val drawn = screen.texts().map { it.text.toString() }
                assertTrue("views $lang: nothing drawn", drawn.size > 5)
                assertTrue("views $lang guardian=$guardian $variant: the sentence that deck 2026-11-12.1 added is drawn", drawn.none { holds(it, f) })
            }
            val c = compose(case)
            shoot(c) {
                val drawn = c.nodes(merged = false).mapNotNull { it.text() }
                assertTrue("compose $lang: nothing drawn", drawn.size > 5)
                assertTrue("compose $lang guardian=$guardian $variant: the sentence that deck 2026-11-12.1 added is drawn", drawn.none { holds(it, f) })
            }
        }
    }

    /** "Quran Lab" at the top of every language's sheet, before the title for a screen reader; a theme that sets everything keeps it. */
    @Test fun theNameIsInEveryLanguagesSheetAndAThemeCannotRemoveIt() {
        val everything = SharingTheme(background = 0xFF101820.toInt(), text = 0xFFF0E6D2.toInt(), secondaryText = 0xFFB0A890.toInt(),
            accent = 0xFFE3B26A.toInt(), cornerRadiusDp = 4f, typeface = Typeface.SERIF, titleSizeSp = 34f, bodySizeSp = 18f,
            titleTypeface = Typeface.MONOSPACE, dark = true)
        val style = SharingStyle(container = Color(0xFF101820), content = Color(0xFFF0E6D2), accent = Color(0xFFE3B26A), shape = CutCornerShape(4.dp),
            secondaryContent = Color(0xFFB0A890), cornerRadius = 4.dp, titleFont = FontFamily.Monospace, bodyFont = FontFamily.Serif, dark = true)
        for (lang in languages) for (themed in listOf(false, true)) for (guardian in listOf(false, true)) {
            val case = if (themed) Case(lang, guardian = guardian, theme = everything, style = style, dark = true) else Case(lang, guardian = guardian)
            val c = case.copy
            val title = c[if (guardian) "sheet.guardian.title" else "sheet.title"]
            use(case)
            val (screen, _) = views(case)
            shoot(screen) {
                val names = screen.texts().filter { it.text.toString() == SheetDesign.NAME }
                assertEquals("views $lang themed=$themed: one name", 1, names.size)
                val name = names.single()
                assertEquals(SheetKit.NAME_TAG, name.tag)
                assertTrue("views $lang: the name is shown", name.isShown && name.width > 0 && name.height > 0)
                assertEquals("views $lang: the name is set left to right", View.TEXT_DIRECTION_LTR, name.textDirection)
                assertEquals("views $lang: the name in the text colour", case.theme.look(ctx, c).colors.text, name.currentTextColor)
                assertEquals("views $lang: the name in the system face, semibold", 600, name.typeface.weight)
                assertEquals("views $lang: 15 sp", 15f * ctx.resources.displayMetrics.scaledDensity, name.textSize, 0.5f)
                val t = screen.texts().single { it.text.toString() == title }
                assertTrue("views $lang: the name is above the title", name.box()[3] <= t.box()[1])
                val all = screen.texts()
                assertTrue("views $lang: the name comes before the title in reading order", all.indexOf(name) < all.indexOf(t))
            }
            val v = compose(case)
            shoot(v) {
                val nodes = v.nodes()
                val names = nodes.withText(SheetDesign.NAME)
                assertEquals("compose $lang themed=$themed: one name", 1, names.size)
                val name = names.single()
                val t = nodes.withText(title).single()
                assertTrue("compose $lang: the name is shown", name.rect().width > 0 && name.rect().height > 0)
                assertTrue("compose $lang: the name is above the title", name.rect().bottom <= t.rect().top)
                assertTrue("compose $lang: the name comes before the title in reading order", nodes.indexOf(name) < nodes.indexOf(t))
            }
        }
    }

    /** At 100 % and 200 % text, on the 320 dp phone, in every language and both variants: same width, same height, two lines at most. */
    @Test fun bothButtonsAreTheSameSize() {
        for (scale in listOf(1f, 2f)) for (lang in languages) for (guardian in listOf(false, true)) {
            val case = Case(lang, guardian = guardian, fontScale = scale, screen = Screens.NARROW)
            val c = case.copy
            use(case)
            val (screen, frame) = views(case)
            shoot(screen) {
                val share = frame.panel.bar.share
                val notNow = frame.panel.bar.notNow
                val what = "views $lang guardian=$guardian ${scale}x"
                assertEquals("$what: width", share.width, notNow.width)
                assertEquals("$what: height", share.height, notNow.height)
                assertEquals("$what: the same type in both labels", share.textSize, notNow.textSize, 0f)
                assertTrue("$what: at least 52 dp", share.height >= px(SheetDesign.BUTTON_HEIGHT) - 1)
                assertTrue("$what: two lines at most (${share.lines()}, ${notNow.lines()})", share.lines() in 1..2 && notNow.lines() in 1..2)
                assertTrue("$what: nothing cut", share.layout.getEllipsisCount(share.lines() - 1) == 0)
                if (scale == 1f) assertTrue("$what: one line at the default size", share.lines() == 1 && notNow.lines() == 1)
            }
            val v = compose(case)
            shoot(v) {
                val nodes = v.nodes()
                val share = nodes.button(c[if (guardian) "sheet.guardian.share" else "sheet.share"])
                val notNow = nodes.button(c["sheet.not_now"])
                val what = "compose $lang guardian=$guardian ${scale}x"
                assertEquals("$what: width", share.rect().width, notNow.rect().width, 0.5f)
                assertEquals("$what: height", share.rect().height, notNow.rect().height, 0.5f)
                assertTrue("$what: at least 52 dp", share.rect().height >= px(SheetDesign.BUTTON_HEIGHT) - 1)
                val labels = v.nodes(merged = false).filter { it.text() == share.text() || it.text() == notNow.text() }.map { it.lines() }
                assertTrue("$what: two lines at most $labels", labels.isNotEmpty() && labels.all { it in 1..2 })
                if (scale == 1f) assertTrue("$what: one line at the default size $labels", labels.all { it == 1 })
            }
        }
    }

    /**
     * Scrolled to the end, nothing of the column shows in the grabber's row: every pixel of that band (8 above the
     * handle, the handle, 8 below) is the sheet's background, the handle's own pixels aside. Both drawings.
     */
    @Test fun nothingOfTheColumnShowsInTheGrabbersRow() {
        for (case in listOf(Case("en", end = true), Case("en", guardian = true, end = true), Case("ar", end = true, dark = true),
            Case("en", fontScale = 2f, end = true))) {
            use(case)
            val bg = case.theme.look(ctx, case.copy).colors.background or (0xFF shl 24)
            val w = case.config().screenWidth
            val top = px(Screens.STATUS_BAR + SheetDesign.SHEET_TOP_GAP)
            val band = top until top + px(SheetDesign.GRABBER_ROW)
            val handleX = (w - px(SheetDesign.GRABBER_W)) / 2 - 2..(w + px(SheetDesign.GRABBER_W)) / 2 + 2
            val handleY = top + px(SheetDesign.GRABBER_TOP) - 2..top + px(SheetDesign.GRABBER_TOP + SheetDesign.GRABBER_H) + 2
            // the rounded corners show the backdrop: the band is checked between them
            val xs = px(SheetDesign.SHEET_RADIUS) until w - px(SheetDesign.SHEET_RADIUS)
            for ((name, picture) in listOf("views" to shoot(views(case).first), "compose" to shoot(compose(case)))) {
                var off = 0
                var first = ""
                for (y in band) for (x in xs) {
                    if (x in handleX && y in handleY) continue
                    val p = picture.at(x, y) or (0xFF shl 24)
                    if (p != bg) {
                        if (off == 0) first = "(%d, %d) %06X".format(x, y, p and 0xFFFFFF)
                        off++
                    }
                }
                assertEquals("$name ${case.lang} ${case.fontScale}x guardian=${case.guardian}: pixels in the grabber's row that are not the background, first $first", 0, off)
            }
        }
    }

    @Test fun theShareButtonIsDisabledUntilTheGuardiansSwitchIsOn() {
        val case = Case("en", guardian = true, end = true)
        val c = case.copy
        use(case)
        var shared = 0
        var declined = 0
        val (screen, frame) = views(case, onShare = { shared++ }, onNotNow = { declined++ })
        shoot(screen) {
            val share = frame.panel.bar.share
            val notNow = frame.panel.bar.notNow
            val sw = screen.all<SheetSwitch>().single()
            val row = sw.parent as View
            assertFalse("the share button starts disabled", share.isEnabled)
            assertEquals(SheetDesign.DISABLED_ALPHA, share.alpha, 0f)
            assertTrue("Not now is always enabled", notNow.isEnabled && notNow.alpha == 1f)
            assertFalse(sw.isChecked)
            val info = row.createAccessibilityNodeInfo()
            assertEquals("the row is one switch for TalkBack", Switch::class.java.name, info.className)
            assertTrue(info.isCheckable && !info.isChecked)
            row.performClick()
            assertTrue("the switch on enables share", sw.isChecked && share.isEnabled && share.alpha == 1f)
            assertTrue(row.createAccessibilityNodeInfo().isChecked)
            row.performClick()
            assertFalse("the switch off disables it again", sw.isChecked || share.isEnabled)
            row.performClick()
            share.performClick()
            notNow.performClick()
            assertEquals("each button calls its own answer once", 1 to 1, shared to declined)
        }
        val v = compose(case, onShare = { shared++ }, onNotNow = { declined++ })
        val enabled = ArrayList<Boolean>()
        shoot(v, count = 4) { frame ->
            val nodes = v.nodes()
            val share = nodes.button(c["sheet.guardian.share"])
            val notNow = nodes.button(c["sheet.not_now"])
            val sw = nodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            enabled += !share.config.contains(SemanticsProperties.Disabled)
            assertFalse("Not now is always enabled", notNow.config.contains(SemanticsProperties.Disabled))
            assertEquals("the switch's row reads its line", c["sheet.guardian.confirm"], sw.text())
            if (frame == 0) {
                assertEquals(ToggleableState.Off, sw.config[SemanticsProperties.ToggleableState])
                sw.config[SemanticsActions.OnClick].action!!.invoke()
                Snapshot.sendApplyNotifications()
            } else if (frame == 3) {
                assertEquals(ToggleableState.On, sw.config[SemanticsProperties.ToggleableState])
                share.config[SemanticsActions.OnClick].action!!.invoke()
                notNow.config[SemanticsActions.OnClick].action!!.invoke()
            }
        }
        assertEquals("compose: disabled until the switch, then enabled: $enabled", listOf(false, true), listOf(enabled.first(), enabled.last()))
        assertEquals("compose: each button calls its own answer once", 2 to 2, shared to declined)
    }

    /** The deck's `dir` decides, on a left to right device and on a right to left one. */
    @Test fun theDirectionFollowsTheDeckNotTheDevice() {
        val w = Screens.PHONE.screenWidth
        val start = px(SheetDesign.SIDE + SheetDesign.CARD_PAD_H + SheetDesign.ICON + SheetDesign.ICON_GAP)
        val nameStart = px(SheetDesign.SIDE + SheetDesign.EMBLEM + SheetDesign.NAME_GAP)
        for (deviceRtl in listOf(false, true)) for (lang in languages) {
            val case = Case(lang, deviceRtl = deviceRtl)
            val c = case.copy
            val rtl = c.rtl
            use(case)
            val (screen, frame) = views(case)
            shoot(screen) {
                val what = "views $lang on a ${if (deviceRtl) "right to left" else "left to right"} device"
                // the drawing mirrors itself: its frame stays left to right, so an app without RTL support gets the same sheet
                assertEquals(what, View.LAYOUT_DIRECTION_LTR, frame.layoutDirection)
                val name = screen.texts().single { it.tag == SheetKit.NAME_TAG }
                val header = name.parent as android.view.ViewGroup
                val emblem = (0 until header.childCount).map { header.getChildAt(it) }.single { it !is TextView }
                if (rtl) assertTrue("$what: the emblem at the right, the name to its left", emblem.box()[0] >= name.box()[2])
                else assertTrue("$what: the emblem at the left, the name to its right", emblem.box()[2] <= name.box()[0])
                val line = screen.texts().single { it.text.toString() == c["sheet.who"] }
                val row = line.parent as android.view.ViewGroup
                val icon = (0 until row.childCount).map { row.getChildAt(it) }.single { it is ImageView }
                if (rtl) assertTrue("$what: the icon at the leading (right) edge", icon.box()[0] >= line.box()[2])
                else assertTrue("$what: the icon at the leading (left) edge", icon.box()[2] <= line.box()[0])
                assertEquals("$what: the line starts where the design says", (if (rtl) w - start else start).toFloat(),
                    (if (rtl) line.box()[2] else line.box()[0]).toFloat(), 2f)
                assertEquals("$what: the paragraph's direction", if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR, line.textDirection)
            }
            val v = compose(case)
            shoot(v) {
                val what = "compose $lang on a ${if (deviceRtl) "right to left" else "left to right"} device"
                val nodes = v.nodes()
                val name = nodes.withText(SheetDesign.NAME).single().rect()
                val line = nodes.withText(c["sheet.who"]).single().rect()
                if (rtl) {
                    assertEquals("$what: the name to the emblem's left", (w - nameStart).toFloat(), name.right, 2f)
                    assertEquals("$what: the line starts at the right", (w - start).toFloat(), line.right, 2f)
                } else {
                    assertEquals("$what: the name to the emblem's right", nameStart.toFloat(), name.left, 2f)
                    assertEquals("$what: the line starts at the left", start.toFloat(), line.left, 2f)
                }
            }
        }
    }

    /**
     * Contrast (1.12.2), drawn: the share label white or near-black, "Not now", the emblem's glyph and the icons in the
     * accent or the text colour, by the 3 to 1 bar or, when the device asks for more contrast, 4.5 to 1. The default
     * teal; the system's blue (white label, accent marks; under more contrast a near-black label and text-coloured
     * marks); a yellow (near-black label, text-coloured marks).
     */
    @Test fun theAccentsMarksFollowTheContrastBar() {
        val blue = 0xFF007AFF.toInt()
        val yellow = 0xFFFFD54F.toInt()
        val white = 0xFFFFFFFF.toInt()
        val nearBlack = 0xFF0B0B0C.toInt()
        fun themed(accent: Int, more: Boolean) = Case("en", theme = SharingTheme(accent = accent), style = SharingStyle(accent = Color(accent)), moreContrast = more)
        // case, the share label, and whether "Not now", the glyph and the icons stay in the accent
        val cases = listOf(
            Triple(Case("en"), white, true), Triple(Case("en", moreContrast = true), white, true),
            Triple(themed(blue, false), white, true), Triple(themed(blue, true), nearBlack, false),
            Triple(themed(yellow, false), nearBlack, false), Triple(themed(yellow, true), nearBlack, false),
        )
        for ((case, label, inAccent) in cases) {
            val c = case.copy
            use(case)
            val look = case.theme.look(ctx, c).colors
            val what = "accent %06X more=%s".format(look.accent and 0xFFFFFF, case.moreContrast)
            assertEquals("$what: the bar the drawing used", case.moreContrast, look.moreContrast)
            val ink = if (inAccent) look.accent else look.text
            val (screen, frame) = views(case)
            shoot(screen) {
                assertEquals("views $what: the share label", label, frame.panel.bar.share.currentTextColor)
                assertEquals("views $what: the Not now label", ink, frame.panel.bar.notNow.currentTextColor)
                val icons = screen.all<ImageView>().map { (it.drawable as SheetIconDrawable).color }
                assertEquals("views $what: the emblem's glyph and the three icons", listOf(ink, ink, ink, ink), icons)
            }
            val v = compose(case)
            var share = androidx.compose.ui.geometry.Rect.Zero
            var notNow = androidx.compose.ui.geometry.Rect.Zero
            val image = shoot(v) {
                share = v.nodes().button(c["sheet.share"]).rect()
                notNow = v.nodes().button(c["sheet.not_now"]).rect()
            }
            // the darkest ink in "Not now" is its label's; in "Share" the label is the darkest or the lightest ink
            val n = darkest(image, notNow.left.toInt(), notNow.top.toInt(), notNow.right.toInt(), notNow.bottom.toInt())
            assertTrue("compose $what: the Not now label is %06X, want %06X".format(n and 0xFFFFFF, ink and 0xFFFFFF), SheetDesign.contrast(n, ink) < 1.25)
            // the button's rounded corners show the bar behind them: the lightest pixel is looked for between them
            val inset = px(SheetDesign.BUTTON_RADIUS + 2f)
            val s = if (label == white) lightest(image, share.left.toInt() + inset, share.top.toInt(), share.right.toInt() - inset, share.bottom.toInt())
                else darkest(image, share.left.toInt(), share.top.toInt(), share.right.toInt(), share.bottom.toInt())
            assertTrue("compose $what: the share label is %06X, want %06X".format(s and 0xFFFFFF, label and 0xFFFFFF), SheetDesign.contrast(s, label) < 1.25)
        }
    }

    /** Compose: the app's MaterialTheme primary is the accent; Material's own baseline (no colour scheme set) is not. */
    @Test fun theAppsMaterialPrimaryIsTheAccentAndMaterialsBaselineIsNot() {
        val en = SharingCopy.forLanguage("en")
        val got = HashMap<String, Int>()
        val blue = Color(0xFF1F5FBF)
        use(Case("en"))
        val v = screen(false) {
            got["none"] = rememberLook(SharingStyle(), en).colors.accent
            MaterialTheme(colorScheme = lightColorScheme()) { got["baseline"] = rememberLook(SharingStyle(), en).colors.accent }
            MaterialTheme(colorScheme = lightColorScheme(primary = blue)) { got["app"] = rememberLook(SharingStyle(), en).colors.accent }
            MaterialTheme(colorScheme = lightColorScheme(primary = blue)) { got["style"] = rememberLook(SharingStyle(accent = Color.Red), en).colors.accent }
        }
        shoot(v)
        assertEquals(mapOf("none" to 0xFF0E6B58.toInt(), "baseline" to 0xFF0E6B58.toInt(), "app" to blue.toArgb(), "style" to Color.Red.toArgb()), got)
        // the Views dialog: the framework theme's own accent is not the app's (Paparazzi's theme is the framework's Material Light)
        assertEquals(0xFF0E6B58.toInt(), SharingTheme().look(ctx, en).colors.accent)
        assertEquals(0xFF1F5FBF.toInt(), SharingTheme(accent = 0xFF1F5FBF.toInt()).look(ctx, en).colors.accent)
    }

    /**
     * The two drawings put the same blocks in the same places (to 3 pixels) on the 390 dp phone, at 100 % text. At larger
     * text they differ in this renderer only: its Views scale sp linearly (it has no copy of Android 14's non-linear
     * font scaling), while Compose applies Android 14's tables itself; on an Android 14 phone the Views get the same
     * tables from the platform. Bengali is left out of this comparison: Compose adds about 2 dp above and below a text
     * block whose fallback font reaches past the design's line height (its rule for fallback fonts), the Views do not;
     * neither cuts a glyph (the pictures 08 show both).
     */
    @Test fun theViewsAndComposeDrawingsAgree() {
        for (case in listOf(Case("en"), Case("ar"), Case("en", guardian = true, end = true), Case("fr", variant = ConsentState.Variant.REASK),
            Case("en", screen = Screens.NARROW), Case("ar", screen = Screens.TABLET, dark = true))) {
            val c = case.copy
            use(case)
            val placed = HashMap<String, IntArray>()
            val (screen, frame) = views(case)
            shoot(screen) {
                for (t in screen.texts()) placed[t.text.toString()] = t.box()
                placed["#bar"] = frame.panel.bar.box()
            }
            val v = compose(case)
            shoot(v) {
                val lines = expectedLines(c, case.variant, case.guardian)
                for (n in v.nodes(merged = false)) {
                    val t = n.text() ?: continue
                    if (t !in lines) continue
                    val want = placed[t] ?: error("views did not draw: $t")
                    val got = n.rect()
                    // a button's label is centred in its button: compare the buttons (merged) instead
                    if (t == c["sheet.not_now"] || t == c[if (case.guardian) "sheet.guardian.share" else "sheet.share"]) continue
                    val what = "${case.lang} ${if (case.guardian) "guardian " else ""}${case.fontScale}x: \"${t.take(24)}\""
                    assertEquals("$what top", want[1].toFloat(), got.top, 3f)
                    assertEquals("$what height", (want[3] - want[1]).toFloat(), got.height, 3f)
                    if (c.rtl) assertEquals("$what right", want[2].toFloat(), got.right, 3f) else assertEquals("$what left", want[0].toFloat(), got.left, 3f)
                }
                val nodes = v.nodes()
                val share = nodes.button(c[if (case.guardian) "sheet.guardian.share" else "sheet.share"]).rect()
                val bar = placed["#bar"]!!
                assertTrue("${case.lang}: the share button sits in the bar in both", abs(share.top - (bar[1] + px(SheetDesign.DIVIDER + SheetDesign.BAR_TOP))) <= 3f)
            }
        }
    }
}
