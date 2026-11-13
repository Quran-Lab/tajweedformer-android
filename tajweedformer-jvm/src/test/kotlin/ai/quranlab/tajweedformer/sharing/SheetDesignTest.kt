package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.OpenChecked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent sheet's design (SDK 3.1.1) as both drawings read it ([SheetDesign]): the colour tokens of the design's
 * table, the tint and on-accent derived from the accent, the 4.5 to 1 fallback, the type scale with its Arabic and Urdu
 * line heights, and the name. The drawings themselves are checked by the Compose module's screenshot tests.
 */
class SheetDesignTest : OpenChecked() {
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test fun theTokensAreTheDesignsTable() {
        val light = SheetDesign.colors(dark = false)
        val dark = SheetDesign.colors(dark = true)
        val want = listOf(
            Triple("sheet background", light.background to dark.background, "#FFFFFF" to "#1C1C1E"),
            Triple("text", light.text to dark.text, "#17171A" to "#F4F4F6"),
            Triple("body", light.body to dark.body, "#3D3D44" to "#D2D2D8"),
            Triple("secondary", light.secondary to dark.secondary, "#62626B" to "#A7A7B0"),
            Triple("card", light.card to dark.card, "#F4F4F6" to "#2A2A2D"),
            Triple("divider", light.divider to dark.divider, "#E4E4E9" to "#3B3B40"),
            Triple("bar divider", light.barDivider to dark.barDivider, "#ECECF0" to "#2E2E32"),
            Triple("grabber", light.grabber to dark.grabber, "#D4D4DA" to "#48484D"),
            Triple("accent (default)", light.accent to dark.accent, "#0E6B58" to "#62D2B6"),
            Triple("on-accent", light.onAccent to dark.onAccent, "#FFFFFF" to "#0B0B0C"),
        )
        for ((name, got, expected) in want) {
            assertEquals("$name, light", expected.first, hex(got.first))
            assertEquals("$name, dark", expected.second, hex(got.second))
        }
        // tint: the accent at 11 % over the background (dark 16 %); soft tint 6 % (dark 8 %), as CSS color-mix in sRGB
        assertEquals("#E4EFED", hex(light.tint))
        assertEquals("#F1F6F5", hex(light.softTint))
        assertEquals(hex(SheetDesign.mix(0xFF62D2B6.toInt(), 0xFF1C1C1E.toInt(), 0.16f)), hex(dark.tint))
        assertEquals(hex(SheetDesign.mix(0xFF62D2B6.toInt(), 0xFF1C1C1E.toInt(), 0.08f)), hex(dark.softTint))
        // the default accents pass 4.5 to 1 as a label on their tint: no fallback
        assertFalse(light.fallback)
        assertFalse(dark.fallback)
        assertTrue(SheetDesign.contrast(light.accent, light.tint) >= 4.5)
        assertTrue(SheetDesign.contrast(dark.accent, dark.tint) >= 4.5)
    }

    @Test fun anAccentFromTheAppDerivesTintAndOnAccent() {
        val blue = SheetDesign.colors(dark = false, accent = 0xFF1F5FBF.toInt())
        assertEquals("#1F5FBF", hex(blue.accent))
        assertEquals(hex(SheetDesign.mix(0xFF1F5FBF.toInt(), 0xFFFFFFFF.toInt(), 0.11f)), hex(blue.tint))
        assertEquals("#FFFFFF", hex(blue.onAccent))
        assertFalse(blue.fallback)
        // a light accent takes the near-black label
        val mint = SheetDesign.colors(dark = false, accent = 0xFF62D2B6.toInt())
        assertEquals("#0B0B0C", hex(mint.onAccent))
    }

    @Test fun aThemeWithALowContrastAccentTakesTheFallback() {
        // a yellow accent on the light sheet: as a label on its own tint it is far under 4.5 to 1
        val yellow = SheetDesign.colors(dark = false, accent = 0xFFFFD54F.toInt())
        assertTrue(SheetDesign.contrast(yellow.accent, yellow.tint) < 4.5)
        assertTrue(yellow.fallback)
        assertEquals("the Not now label and the icons take the text colour", yellow.text, yellow.accentInk)
        assertEquals("the fill stays the tint", hex(SheetDesign.mix(0xFFFFD54F.toInt(), 0xFFFFFFFF.toInt(), 0.11f)), hex(yellow.tint))
        assertEquals("the share label is near-black on yellow", "#0B0B0C", hex(yellow.onAccent))
        // the same in the dark: a dark navy accent on the dark sheet
        val navy = SheetDesign.colors(dark = true, accent = 0xFF1A2A5A.toInt())
        assertTrue(navy.fallback)
        assertEquals(navy.text, navy.accentInk)
        assertEquals("#FFFFFF", hex(navy.onAccent))
        // and every label the sheet draws keeps 4.5 to 1 in both cases
        for (c in listOf(yellow, navy)) {
            assertTrue(SheetDesign.contrast(c.accentInk, c.tint) >= 4.5)
            assertTrue(SheetDesign.contrast(c.onAccent, c.accent) >= 4.5)
        }
    }

    /**
     * Contrast (1.12.2): the accent's marks need 3 to 1, or 4.5 to 1 when the device asks for more contrast. The share
     * label is white when white has the bar against the accent, else near-black; "Not now" and the emblem's glyph stay
     * in the accent when it has the bar against the tint, the icons when it has it against the card.
     */
    @Test fun theAccentsMarksNeedThreeToOneOrFourAndAHalfOnRequest() {
        val white = "#FFFFFF"
        val nearBlack = "#0B0B0C"
        fun marks(dark: Boolean, accent: Int?, more: Boolean): List<String> {
            val c = SheetDesign.colors(dark = dark, accent = accent, moreContrast = more)
            return listOf(hex(c.onAccent), if (c.accentInk == c.accent) "accent" else if (c.accentInk == c.text) "text" else hex(c.accentInk),
                if (c.iconInk == c.accent) "accent" else if (c.iconInk == c.text) "text" else hex(c.iconInk))
        }
        // the default teal (and the default mint in the dark) passes even the stricter bar: unchanged by the rule
        for (more in listOf(false, true)) {
            assertEquals("teal, more=$more", listOf(white, "accent", "accent"), marks(false, null, more))
            assertEquals("mint in the dark, more=$more", listOf(nearBlack, "accent", "accent"), marks(true, null, more))
        }
        // the system's blue: 4.0 to 1 with white, so a white label and accent-coloured marks at 3 to 1 ...
        val systemBlue = 0xFF007AFF.toInt()
        val c = SheetDesign.colors(dark = false, accent = systemBlue)
        assertTrue(SheetDesign.contrast(SheetDesign.WHITE, systemBlue) in 3.0..4.5)
        assertTrue(SheetDesign.contrast(systemBlue, c.tint) in 3.0..4.5 && SheetDesign.contrast(systemBlue, c.card) in 3.0..4.5)
        assertEquals("the system's blue", listOf(white, "accent", "accent"), marks(false, systemBlue, false))
        // ... and, when the device asks for more contrast, the 4.5 results: a near-black label, the marks in the text colour
        assertEquals("the system's blue, more contrast", listOf(nearBlack, "text", "text"), marks(false, systemBlue, true))
        // picture 20's earlier blue passes both bars
        assertEquals(listOf(white, "accent", "accent"), marks(false, 0xFF1F5FBF.toInt(), true))
        // a yellow accent: under either bar, a near-black label and the text colour for the marks, as before
        for (more in listOf(false, true)) assertEquals("yellow, more=$more", listOf(nearBlack, "text", "text"), marks(false, 0xFFFFD54F.toInt(), more))
    }

    @Test fun aThemesOwnColoursKeepTheirPlaceAndTheRestFollows() {
        val own = SheetDesign.colors(dark = false, background = 0xFFFAF6EE.toInt(), text = 0xFF20201C.toInt(), secondary = 0xFF6A665C.toInt())
        assertEquals("#FAF6EE", hex(own.background))
        assertEquals("#20201C", hex(own.text))
        assertEquals("#20201C", hex(own.body))
        assertEquals("#6A665C", hex(own.secondary))
        // the cards and dividers are a step off the theme's background, toward its text
        assertTrue(own.card != own.background && SheetDesign.contrast(own.card, own.background) < 1.2)
        assertTrue(SheetDesign.contrast(own.divider, own.background) > SheetDesign.contrast(own.card, own.background))
        assertTrue(SheetDesign.isDark(0xFF121212.toInt()))
        assertFalse(SheetDesign.isDark(0xFFFAF6EE.toInt()))
        assertEquals(21.0, SheetDesign.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test fun theTypeScaleAndTheName() {
        val latin = SheetDesign.Script.LATIN
        val arabic = SheetDesign.Script.ARABIC
        fun t(x: SheetDesign.Type) = listOf(x.sizeSp, x.lineHeightSp, x.weight.toFloat(), x.trackingEm)
        assertEquals(listOf(26f, 31f, 700f, -0.02f), t(SheetDesign.title(latin)))
        assertEquals(listOf(26f, 36f, 700f, 0f), t(SheetDesign.title(arabic)))
        assertEquals(listOf(15.5f, 24f, 400f, 0f), t(SheetDesign.body(latin)))
        assertEquals(listOf(15.5f, 27f, 400f, 0f), t(SheetDesign.body(arabic)))
        assertEquals(listOf(14.5f, 22f, 400f, 0f), t(SheetDesign.row(latin)))
        assertEquals(listOf(14.5f, 25f, 400f, 0f), t(SheetDesign.row(arabic)))
        assertEquals(listOf(15f, 24f, 400f, 0f), t(SheetDesign.hadith(latin)))
        assertEquals(listOf(15f, 28f, 400f, 0f), t(SheetDesign.hadith(arabic)))
        assertEquals(listOf(12f, 16f, 600f, 0.06f), t(SheetDesign.reference(latin)))
        assertTrue(SheetDesign.reference(latin).upper)
        assertFalse("as written outside Latin scripts", SheetDesign.reference(arabic).upper || SheetDesign.reference(SheetDesign.Script.OTHER).upper)
        assertEquals(listOf(15f, 24f, 400f, 0f), t(SheetDesign.mistakes(latin)))
        assertEquals(listOf(16f, 20f, 600f, 0f), t(SheetDesign.button(latin)))
        assertEquals(listOf(15f, 20f, 600f, -0.01f), t(SheetDesign.name))
        assertEquals("Quran Lab", SheetDesign.NAME)
        assertEquals(SheetDesign.Script.ARABIC, SheetDesign.script("ur"))
        assertEquals(SheetDesign.Script.OTHER, SheetDesign.script("bn"))
        assertEquals(SheetDesign.Script.LATIN, SheetDesign.script("tr"))
        // every deck the SDK ships has a script the type scale knows, and Arabic and Urdu are the right to left ones
        for (lang in SharingCopy.LANGUAGES) assertEquals(lang, SharingCopy.forLanguage(lang).rtl, SheetDesign.script(lang) == arabic)
        // line heights follow the size: 24 / 15.5 of the size at any font scale
        assertEquals(24f / 15.5f, SheetDesign.body(latin).lineHeightEm, 1e-6f)
    }
}
