package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.sharing.ConsentState
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The consent sheet's pictures, each drawn twice by Android's own renderer: by the Views dialog (`views_*.png`) and by
 * the Compose sheet (`compose_*.png`), at the screen's full resolution. With `-PsheetPictures=<folder>` they are written
 * there for a person to look at against the design's reference screens; without it the test draws them and checks
 * only their size. Pictures are evidence for a review, committed nowhere.
 */
class SheetPicturesTest : SheetTestBase() {
    private fun draw(name: String, case: Case) {
        use(case)
        val v = shoot(views(case).first)
        val c = shoot(compose(case))
        assertEquals("$name: the two drawings are the screen's size", v.width to v.height, c.width to c.height)
        assertEquals(case.config().screenWidth, v.width)
        Pictures.write("views_$name", v)
        Pictures.write("compose_$name", c)
    }

    /** Light and dark, English and Arabic, as the sheet opens; English scrolled to the end; the re-ask line. */
    @Test fun phone() {
        draw("01_en_light", Case("en"))
        draw("02_en_light_end", Case("en", end = true))
        draw("03_en_dark", Case("en", dark = true))
        draw("04_ar_light", Case("ar"))
        draw("05_ar_dark", Case("ar", dark = true))
        draw("06_en_reask", Case("en", variant = ConsentState.Variant.REASK))
        draw("07_ur_light", Case("ur"))
        draw("08_bn_light", Case("bn"))
    }

    /** The largest text size (Android's 200 %): the column scrolls, the buttons grow and keep their labels whole. */
    @Test fun largestText() {
        draw("09_en_largest", Case("en", fontScale = 2f))
        draw("10_en_largest_end", Case("en", fontScale = 2f, end = true))
        draw("11_ar_largest", Case("ar", fontScale = 2f))
        draw("12_fr_largest_narrow_320", Case("fr", fontScale = 2f, screen = Screens.NARROW))
        // the longest label of the eight decks: at 200 % on 320 dp both labels step down together to fit in two lines
        draw("22_bn_guardian_largest_narrow_320", Case("bn", guardian = true, fontScale = 2f, screen = Screens.NARROW))
    }

    /** The guardian variant: as it opens, and at its end with the switch off (the share button disabled). */
    @Test fun guardian() {
        draw("13_en_guardian", Case("en", guardian = true))
        draw("14_en_guardian_end", Case("en", guardian = true, end = true))
        draw("15_ar_guardian_end_dark", Case("ar", guardian = true, end = true, dark = true))
    }

    /** A 320 dp wide phone, and the centred card of a wide screen. */
    @Test fun narrowAndWide() {
        draw("16_en_narrow_320", Case("en", screen = Screens.NARROW))
        draw("17_en_narrow_320_largest", Case("en", screen = Screens.NARROW, fontScale = 2f))
        draw("18_en_wide_card", Case("en", screen = Screens.TABLET))
        draw("19_ar_wide_card_dark", Case("ar", screen = Screens.TABLET, dark = true))
    }

    /**
     * An app's accent (contrast, 1.12.2): the system's blue, which has 4.0 to 1 with white (a white share label, "Not
     * now" and the icons in the blue); the same with the device asking for more contrast (4.5 to 1: a near-black label,
     * the marks in the text colour); a yellow that fails either bar on its tint (the marks in the text colour).
     */
    @Test fun appAccents() {
        val blue = 0xFF007AFF.toInt()
        val yellow = 0xFFFFD54F.toInt()
        draw("20_en_app_accent_system_blue", Case("en", theme = SharingTheme(accent = blue), style = SharingStyle(accent = Color(blue))))
        draw("21_en_low_contrast_accent", Case("en", theme = SharingTheme(accent = yellow), style = SharingStyle(accent = Color(yellow))))
        draw("23_en_app_accent_system_blue_more_contrast", Case("en", theme = SharingTheme(accent = blue), style = SharingStyle(accent = Color(blue)), moreContrast = true))
    }
}
