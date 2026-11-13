package ai.quranlab.tajweedformer.sharing

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The consent sheet's design (SDK 3.1.1): its colour tokens, type scale and sizes, as Quran Lab wrote them for
 * every SDK (`sharing_copy/design/README.md` of the shared contract, version 1.12.1). Both of the SDK's drawings read
 * this one description: the Views dialog (`SharingViews.kt`, `SheetViews.kt`) and the Compose sheet
 * (`SharingCompose.kt`). Colours are ARGB ints, sizes dp, type sp. Nothing here is a word of the sheet: the words are
 * the deck's ([SharingCopy]), and [NAME] is the one constant the sheet shows.
 *
 * The sheet, top to bottom: the header (the emblem and [NAME]), the title, the re-ask line on a re-ask, the body,
 * the disclosure card (`sheet.what`, `sheet.who`, `sheet.control`, each with its icon), the hadith card when the
 * deck's hadith is verified, `sheet.mistakes` (not in the guardian variant, as in 3.1.0), the guardian's confirmation
 * card; then the bar that does not scroll, with "Share" over "Not now", the same size.
 */
internal object SheetDesign {
    /**
     * The name at the top of the sheet, beside the emblem: a constant of the SDK, not a deck string. It is written in
     * Latin letters in every language (as the decks write it), left to right, in the system's Latin face, and a theme
     * cannot change or hide it.
     */
    const val NAME = "Quran Lab"

    /** The colour tokens of one appearance. */
    class Palette(
        val background: Int,
        val text: Int,
        val body: Int,
        val secondary: Int,
        val card: Int,
        val divider: Int,
        val barDivider: Int,
        val grabber: Int,
        /** The accent when neither the app nor its theme gives one. */
        val accent: Int,
        /** The tint is the accent at this share over the background; the soft tint at [softShare]. */
        val tintShare: Float,
        val softShare: Float,
    )

    val LIGHT = Palette(
        background = 0xFFFFFFFF.toInt(), text = 0xFF17171A.toInt(), body = 0xFF3D3D44.toInt(), secondary = 0xFF62626B.toInt(),
        card = 0xFFF4F4F6.toInt(), divider = 0xFFE4E4E9.toInt(), barDivider = 0xFFECECF0.toInt(), grabber = 0xFFD4D4DA.toInt(),
        accent = 0xFF0E6B58.toInt(), tintShare = 0.11f, softShare = 0.06f,
    )

    val DARK = Palette(
        background = 0xFF1C1C1E.toInt(), text = 0xFFF4F4F6.toInt(), body = 0xFFD2D2D8.toInt(), secondary = 0xFFA7A7B0.toInt(),
        card = 0xFF2A2A2D.toInt(), divider = 0xFF3B3B40.toInt(), barDivider = 0xFF2E2E32.toInt(), grabber = 0xFF48484D.toInt(),
        accent = 0xFF62D2B6.toInt(), tintShare = 0.16f, softShare = 0.08f,
    )

    /** On-accent is white or this near-black, whichever has the higher contrast with the accent. */
    const val WHITE = 0xFFFFFFFF.toInt()
    const val NEAR_BLACK = 0xFF0B0B0C.toInt()

    /**
     * Contrast (1.12.2). Running text is in the text, body or secondary colour, which keep [MIN_CONTRAST] on the sheet
     * and on the cards. The accent carries only the two button labels, the icons and the emblem's glyph: for those the
     * bar is [MARK_CONTRAST] against what is behind them (bold labels of 16, graphical marks), or [MIN_CONTRAST] when
     * the device asks for more contrast.
     */
    const val MIN_CONTRAST = 4.5
    const val MARK_CONTRAST = 3.0

    /** The share button of the guardian variant until the switch is on. */
    const val DISABLED_ALPHA = 0.45f

    /** The dim over the app behind the sheet (the window's dim amount). */
    const val SCRIM_ALPHA = 0.4f

    // ---- sizes, dp ----

    /** The column: 24 at the sides, 20 above the header, 14 between header and title, 18 between blocks, 22 below. */
    const val SIDE = 24f
    const val TOP = 20f
    const val HEADER_GAP = 14f
    const val BLOCK_GAP = 18f
    const val BOTTOM = 22f

    /** The header: the emblem (a square in the tint with the waveform glyph in the accent), a gap, the name. */
    const val EMBLEM = 40f
    const val EMBLEM_RADIUS = 12f
    const val GLYPH = 22f
    const val NAME_GAP = 10f

    /** The cards: the disclosure card and the guardian's (card colour) and the hadith card (soft tint). */
    const val CARD_RADIUS = 18f
    const val CARD_PAD_H = 16f
    /** The drawing's 2 above and below the rows of a card (`padding: 2px 16px`). */
    const val CARD_PAD_V = 2f
    const val ROW_PAD_V = 14f
    const val ICON = 22f
    const val ICON_GAP = 12f
    /** The rows' dividers and the bar's top divider (the drawing's `1px`, one dp here). */
    const val DIVIDER = 1f
    const val HADITH_PAD = 16f
    const val HADITH_GAP = 10f

    /** The bar: 12 above, 24 at the sides, 30 below (or the system's inset when larger), two buttons with 10 between. */
    const val BAR_TOP = 12f
    const val BAR_SIDE = 24f
    const val BAR_BOTTOM = 30f
    const val BUTTON_GAP = 10f
    const val BUTTON_HEIGHT = 52f
    const val BUTTON_RADIUS = 14f
    /** Inside a button: room for a label that wraps at the largest text sizes. */
    const val BUTTON_PAD_H = 16f
    const val BUTTON_PAD_V = 12f

    /** The fades: of the sheet's background, above the bar's divider (40) and, once scrolled, under the top (28). */
    const val FADE_BOTTOM = 40f
    const val FADE_TOP = 28f

    /** The grabber of the sheet on a phone. */
    const val GRABBER_W = 36f
    const val GRABBER_H = 5f
    const val GRABBER_TOP = 8f
    /**
     * The clear band under the handle: the grabber's row is [GRABBER_TOP] + [GRABBER_H] + this, and the scrolling
     * column's viewport begins below the row, so a scrolled line never shows behind the handle. The column's top
     * padding on a phone is [TOP] less this, so the header opens 20 below the handle, as in the drawing.
     */
    const val GRABBER_BELOW = 8f
    const val GRABBER_ROW = GRABBER_TOP + GRABBER_H + GRABBER_BELOW
    /** The top of a phone sheet where a downward drag moves the sheet (the grabber's touch target). */
    const val DRAG_ZONE = 44f

    /** A phone sheet's top corners and every corner of the card on a wide screen. */
    const val SHEET_RADIUS = 28f
    /** From this window width the sheet is a centred card... */
    const val WIDE_FROM = 600f
    /** ...at most this wide, with this margin around it. */
    const val CARD_MAX_WIDTH = 480f
    const val CARD_MARGIN = 24f
    /** A phone sheet opens tall: its top is this far below the top of the window (below the status bar). */
    const val SHEET_TOP_GAP = 10f

    /** The switch of the guardian's card and of the settings rows. */
    const val SWITCH_W = 52f
    const val SWITCH_H = 32f
    const val THUMB_INSET = 3f

    /** A rows' least height in the settings view (a touch target). */
    const val SETTINGS_ROW = 48f

    /** The radii scale with a theme's corner radius by the factor radius / [SHEET_RADIUS]. */
    fun radiusFactor(themeRadius: Float?): Float = if (themeRadius == null || themeRadius < 0f) 1f else themeRadius / SHEET_RADIUS

    // ---- type ----

    /** The scripts the type scale distinguishes: Latin, Arabic (Arabic and Urdu decks), and the rest (Bengali). */
    enum class Script { LATIN, ARABIC, OTHER }

    fun script(lang: String): Script = when (lang) {
        "ar", "ur" -> Script.ARABIC
        "en", "id", "ms", "tr", "fr" -> Script.LATIN
        else -> Script.OTHER
    }

    /**
     * One text style: size and line height in sp (both follow the font scale), weight (400, 600, 700, set by the
     * sheet, never inherited), tracking in em, and upper case. The line height is drawn as a multiple of the size
     * ([lineHeightEm]) so that it grows with the size at every font scale; each line is that tall with the glyphs
     * centred in it, the first and the last line included (no trim), as in the drawing.
     */
    class Type(val sizeSp: Float, val lineHeightSp: Float, val weight: Int, val trackingEm: Float = 0f, val upper: Boolean = false) {
        val lineHeightEm: Float get() = lineHeightSp / sizeSp

        /** The same style at [factor] times the size (a theme's title or body size). */
        fun scaled(factor: Float): Type = if (factor == 1f) this else Type(sizeSp * factor, lineHeightSp * factor, weight, trackingEm, upper)
    }

    const val REGULAR = 400
    const val SEMIBOLD = 600
    const val BOLD = 700

    /** The name: 15 / 20 semibold, tracking -0.01 em, always (it is Latin in every language). */
    val name = Type(15f, 20f, SEMIBOLD, -0.01f)

    /** The title: 26 / 31 bold, tracking -0.02 em in Latin scripts; Arabic and Urdu 36 and no tracking. */
    fun title(s: Script) = Type(26f, if (s == Script.ARABIC) 36f else 31f, BOLD, if (s == Script.LATIN) -0.02f else 0f)

    /** The body and the re-ask line: 15.5 / 24 regular; Arabic and Urdu 27. */
    fun body(s: Script) = Type(15.5f, if (s == Script.ARABIC) 27f else 24f, REGULAR)

    /** A disclosure row and the guardian's confirmation: 14.5 / 22 regular; Arabic and Urdu 25. */
    fun row(s: Script) = Type(14.5f, if (s == Script.ARABIC) 25f else 22f, REGULAR)

    /** The hadith: 15 / 24 regular; Arabic and Urdu 28. */
    fun hadith(s: Script) = Type(15f, if (s == Script.ARABIC) 28f else 24f, REGULAR)

    /** The hadith's reference: 12 / 16 semibold, upper case with tracking 0.06 em in Latin scripts, as written elsewhere. */
    fun reference(s: Script) = Type(12f, 16f, SEMIBOLD, if (s == Script.LATIN) 0.06f else 0f, upper = s == Script.LATIN)

    /** `sheet.mistakes`: 15 / 24 regular; Arabic and Urdu 27 (the body's). */
    fun mistakes(s: Script) = Type(15f, if (s == Script.ARABIC) 27f else 24f, REGULAR)

    /** The buttons' labels: 16 / 20 semibold; Arabic and Urdu 22 (the drawing's). */
    fun button(s: Script) = Type(16f, if (s == Script.ARABIC) 22f else 20f, SEMIBOLD)

    // ---- colours ----

    /**
     * The sheet's colours for one appearance. [dark]: the dark tokens. [background], [text], [secondary]: a theme's
     * own colours (null: the token). When a theme gives a background or a text colour, the card, dividers and
     * grabber are mixed from those two in the tokens' own proportions, so that they stay a step off the background the
     * theme gave. [accent]: the app's or its theme's accent, null for the token's. Tint, soft tint and on-accent come
     * from the accent. The accent's marks need [MARK_CONTRAST], or [MIN_CONTRAST] when [moreContrast] (the device asks
     * for more contrast): the share label is white when white has that against the accent, else near-black; the "Not
     * now" label and the emblem's glyph are in the accent when it has that against the tint, the disclosure icons when
     * it has it against the card, else in the text colour ([SheetColors.accentInk], [SheetColors.iconInk]). The fills
     * stay the accent's.
     */
    fun colors(dark: Boolean, background: Int? = null, text: Int? = null, secondary: Int? = null, accent: Int? = null,
               moreContrast: Boolean = false): SheetColors {
        val p = if (dark) DARK else LIGHT
        val bg = opaque(background ?: p.background, p.background)
        val fg = text ?: p.text
        val own = background != null || text != null
        fun step(share: Float, token: Int) = if (own) mix(opaque(fg, bg), bg, share) else token
        val acc = opaque(accent ?: p.accent, bg)
        val tint = mix(acc, bg, p.tintShare)
        val bar = if (moreContrast) MIN_CONTRAST else MARK_CONTRAST
        val onAccent = if (contrast(WHITE, acc) >= bar) WHITE else NEAR_BLACK
        val card = step(if (dark) 0.065f else 0.045f, p.card)
        return SheetColors(
            dark = dark,
            background = bg,
            text = fg,
            body = text ?: p.body,
            secondary = secondary ?: if (own) mix(opaque(fg, bg), bg, if (dark) 0.64f else 0.68f) else p.secondary,
            card = card,
            divider = step(if (dark) 0.145f else 0.11f, p.divider),
            barDivider = step(if (dark) 0.083f else 0.08f, p.barDivider),
            grabber = step(if (dark) 0.2f else 0.185f, p.grabber),
            accent = acc,
            onAccent = onAccent,
            tint = tint,
            softTint = mix(acc, bg, p.softShare),
            accentInk = if (contrast(acc, tint) >= bar) acc else fg,
            iconInk = if (contrast(acc, card) >= bar) acc else fg,
            moreContrast = moreContrast,
        )
    }

    /** A background is dark when white has the higher contrast with it (the rule on-accent uses). */
    fun isDark(background: Int): Boolean = contrast(WHITE, background) > contrast(0xFF000000.toInt(), background)

    /** [a] at [share] over [b], channel by channel in sRGB (CSS `color-mix(in srgb, a share, b)`), opaque. */
    fun mix(a: Int, b: Int, share: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * share + ((b shr shift) and 0xFF) * (1f - share)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [c] over [under] when it has alpha (contrast is measured on what the eye sees). */
    fun opaque(c: Int, under: Int): Int {
        val a = (c ushr 24) and 0xFF
        return if (a == 0xFF) c else mix(c or (0xFF shl 24), under, a / 255f)
    }

    /** The WCAG 2 contrast ratio of two opaque colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The WCAG 2 relative luminance of an sRGB colour. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }
}

/** The sheet's colours, resolved for one appearance and one theme ([SheetDesign.colors]). ARGB ints. */
internal class SheetColors(
    val dark: Boolean,
    val background: Int,
    val text: Int,
    val body: Int,
    val secondary: Int,
    val card: Int,
    val divider: Int,
    val barDivider: Int,
    val grabber: Int,
    val accent: Int,
    /** The share button's label: white or near-black, whichever has the higher contrast with the accent. */
    val onAccent: Int,
    /** The emblem's square and the "Not now" button: the accent at 11 % (dark 16 %) over the background. */
    val tint: Int,
    /** The hadith card: the accent at 6 % (dark 8 %) over the background. */
    val softTint: Int,
    /** The "Not now" label and the emblem's glyph: the accent, or the text colour when the accent is under the bar on the tint. */
    val accentInk: Int,
    /** The disclosure icons: the accent, or the text colour when the accent is under the bar on the card. */
    val iconInk: Int,
    /** The device asks for more contrast: the bar is 4.5 to 1, not 3. */
    val moreContrast: Boolean = false,
) {
    /** The accent is under the bar as a label on the tint, and the text colour stands in for it. */
    val fallback: Boolean get() = accentInk != accent

    /** The switch's track when off. */
    val switchOff: Int get() = grabber

    /** The switch's thumb. */
    val thumb: Int get() = SheetDesign.WHITE
}
