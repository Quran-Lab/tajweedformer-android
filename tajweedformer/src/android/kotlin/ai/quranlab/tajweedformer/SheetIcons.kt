package ai.quranlab.tajweedformer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The sheet's four icons, drawn from the SVG paths of the design's reference screens (a 24 by 24 box, round caps and
 * joins, no fill), as one description that both drawings use: [SheetIconDrawable] in the Views dialog, the same
 * [path] through `asComposePath()` in the Compose sheet. Each SVG command is written next to its Path call. No icon
 * library and no resource: the paths are the drawing's.
 */
internal enum class SheetIcon(
    /** The SVG's stroke width, in the 24 box. */
    val stroke: Float,
) {
    /** The emblem's glyph: five vertical strokes (`waveform`, `graphic_eq`). */
    WAVEFORM(1.9f),
    /** `sheet.what`: a microphone. */
    MIC(1.8f),
    /** `sheet.who`: a shield with a check. */
    SHIELD(1.8f),
    /** `sheet.control`: a switch; in a right to left sheet its knob is on the other side, as in the Arabic drawing. */
    TOGGLE(1.8f);

    /** The icon's outline in the 24 by 24 box; [mirrored] moves the toggle's knob (the other icons do not change). */
    fun path(mirrored: Boolean = false): Path = Path().apply {
        when (this@SheetIcon) {
            WAVEFORM -> for ((x, y0, y1) in listOf(Triple(4f, 10f, 14f), Triple(8f, 7f, 17f), Triple(12f, 4f, 20f), Triple(16f, 7f, 17f), Triple(20f, 10f, 14f))) {
                moveTo(x, y0)                                          // M4 10 v4, M8 7 v10, M12 4 v16, M16 7 v10, M20 10 v4
                lineTo(x, y1)
            }
            MIC -> {
                addRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, Path.Direction.CW) // rect x9 y3 w6 h11 rx3
                moveTo(5.5f, 11.5f)                                    // M5.5 11.5
                arcTo(RectF(5.5f, 5f, 18.5f, 18f), 180f, -180f, false) // a6.5 6.5 0 0 0 13 0 (the lower half)
                moveTo(12f, 18f)                                       // M12 18
                lineTo(12f, 21f)                                       // v3
            }
            SHIELD -> {
                moveTo(12f, 3f)                                        // M12 3
                rLineTo(7f, 3f)                                        // l7 3
                rLineTo(0f, 5.5f)                                      // v5.5
                rCubicTo(0f, 4.3f, -2.9f, 7.6f, -7f, 9.5f)             // c0 4.3 -2.9 7.6 -7 9.5
                rCubicTo(-4.1f, -1.9f, -7f, -5.2f, -7f, -9.5f)         // -4.1 -1.9 -7 -5.2 -7 -9.5
                lineTo(5f, 6f)                                         // V6
                rLineTo(7f, -3f)                                       // l7 -3
                close()                                                // z
                moveTo(9f, 12f)                                        // M9 12
                rLineTo(2.2f, 2.2f)                                    // l2.2 2.2
                lineTo(15.2f, 10f)                                     // L15.2 10
            }
            TOGGLE -> {
                addRoundRect(RectF(2.5f, 7f, 21.5f, 17f), 5f, 5f, Path.Direction.CW) // rect x2.5 y7 w19 h10 rx5
                addCircle(if (mirrored) 7.5f else 16.5f, 12f, 2.4f, Path.Direction.CW) // circle cx16.5 (Arabic 7.5) cy12 r2.4
            }
        }
    }

    companion object {
        /** The SVG's view box. */
        const val BOX = 24f
    }
}

/** A [SheetIcon] as a Drawable of any size, stroked in one colour (the Views dialog's icons). */
internal class SheetIconDrawable(icon: SheetIcon, ink: Int, mirrored: Boolean = false) : Drawable() {
    /** The stroke's colour. */
    val color: Int = ink
    private val path = icon.path(mirrored)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = icon.stroke
        color = ink
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val n = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / SheetIcon.BOX, b.height() / SheetIcon.BOX)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(n)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
