package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.sharing.SheetColors
import ai.quranlab.tajweedformer.sharing.SheetDesign
import ai.quranlab.tajweedformer.sharing.SheetDesign.Type
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every line the design's height, the glyphs centred in it, the first and last line included (CSS line-height, and
 * Compose's `LineHeightStyle(Center, Trim.None)`, which the Compose sheet uses): the line height is a multiple of
 * the text size, so it follows the font scale, and a fallback font (Arabic, Bengali) does not make a line taller.
 */
internal class SheetLineHeight(private val em: Float) : LineHeightSpan.WithDensity {
    override fun chooseHeight(text: CharSequence?, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt?) {}

    override fun chooseHeight(text: CharSequence?, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt, paint: TextPaint) {
        val h = fm.descent - fm.ascent
        if (h <= 0) return
        val target = ceil(paint.textSize * em).toInt()
        fm.descent += ceil((target - h) / 2.0).toInt()
        fm.ascent = fm.descent - target
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

/**
 * Whether the device asks for more contrast (the design's 4.5 to 1 for the accent's marks instead of 3): high contrast
 * text, the accessibility setting of every Android version this SDK runs on (read as the secure setting
 * `high_text_contrast_enabled`), and from Android 14 also the system's contrast level above standard
 * (`UiModeManager.getContrast`). Where the setting cannot be read the device has not asked: 3 to 1.
 */
internal object SheetContrast {
    /** Tests: the answer, instead of the device's. */
    @Volatile var forTests: Boolean? = null

    fun more(ctx: Context): Boolean {
        forTests?.let { return it }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val ui = ctx.getSystemService(android.app.UiModeManager::class.java)
                if (ui != null && ui.contrast > 0f) return true
            } catch (_: Throwable) {
            }
        }
        return try {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "high_text_contrast_enabled", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }
}

/** The typeface at a weight the sheet sets (400, 600, 700): never the host's. Before Android 9, 600 and up are bold. */
internal fun sheetFace(base: Typeface?, weight: Int): Typeface {
    val b = base ?: Typeface.DEFAULT
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(b, weight, false) else Typeface.create(b, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
}

/**
 * The look of one sheet: the colours for its appearance, the radius factor, the theme's fonts and sizes, the deck's
 * language and direction. Made by [SharingTheme.look]; the Views drawing reads only this.
 */
internal class SheetLook(
    val colors: SheetColors,
    val radiusFactor: Float,
    val face: Typeface?,
    val titleFace: Typeface?,
    /** A theme's title size over the design's 26. */
    val titleScale: Float,
    /** A theme's body size over the design's 15.5 (running text: body, rows, hadith, reference, mistakes). */
    val bodyScale: Float,
    val lang: String,
    val rtl: Boolean,
) {
    val script: SheetDesign.Script get() = SheetDesign.script(lang)
}

/** The Views drawing of the sheet's design ([SheetDesign]): its text, cards, header, buttons and switch. */
internal class SheetKit(val ctx: Context, val look: SheetLook) {
    val c: SheetColors get() = look.colors
    private val s get() = look.script

    fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).roundToInt()
    fun dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private fun r(v: Float) = dpf(v * look.radiusFactor)

    /** A text in one of the design's styles: everything set here, nothing taken from the host's text appearance. */
    fun text(s: String, type: Type, color: Int, face: Typeface? = look.face, ltr: Boolean = !look.rtl): TextView =
        TextView(ctx, null, 0, 0).also { styleText(it, s, type, color, face, ltr) }

    fun styleText(v: TextView, s: String, type: Type, color: Int, face: Typeface?, ltr: Boolean = !look.rtl) {
        val shown = if (type.upper) s.uppercase(Locale(look.lang)) else s
        val sp = SpannableString(shown)
        sp.setSpan(SheetLineHeight(type.lineHeightEm), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        v.text = sp
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sizeSp)
        v.setTextColor(color)
        v.typeface = sheetFace(face, type.weight)
        v.letterSpacing = type.trackingEm
        v.includeFontPadding = false
        v.setLineSpacing(0f, 1f)
        v.isAllCaps = false
        v.isElegantTextHeight = false
        v.fontFeatureSettings = null
        v.setFontVariationSettings(null)
        v.setShadowLayer(0f, 0f, 0f, 0)
        v.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        v.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        v.textDirection = if (ltr) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
        // the start of the text's own paragraph direction (the deck's): right in Arabic and Urdu, whatever the view's
        v.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
    }

    fun title(s: String) = text(s, SheetDesign.title(this.s).scaled(look.titleScale), c.text, look.titleFace ?: look.face)
    fun body(s: String) = text(s, SheetDesign.body(this.s).scaled(look.bodyScale), c.body)
    fun mistakes(s: String) = text(s, SheetDesign.mistakes(this.s).scaled(look.bodyScale), c.body)

    /** The column's vertical gap above a block. */
    fun gap(v: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(v) }

    fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r(radiusDp) }

    /**
     * The drawing mirrors itself in a right to left deck: the order and the margins are set from the deck's direction,
     * not from the view's layout direction, which Android ignores in an app that does not declare RTL support.
     */
    private fun LinearLayout.inOrder(vararg views: Pair<View, LinearLayout.LayoutParams>) {
        for ((v, lp) in if (look.rtl) views.reversed() else views.toList()) addView(v, lp)
    }

    /** A gap of [v] dp on the side of a view that faces the next one in reading order. */
    private fun LinearLayout.LayoutParams.after(v: Float) = apply { if (look.rtl) rightMargin = dp(v) else leftMargin = dp(v) }

    /** The header: the emblem (decoration) and the name, one row, centred, in the deck's direction. */
    fun header(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = (if (look.rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        val emblem = FrameLayout(ctx).apply {
            background = rounded(c.tint, SheetDesign.EMBLEM_RADIUS)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ImageView(ctx).apply { setImageDrawable(SheetIconDrawable(SheetIcon.WAVEFORM, c.accentInk)) },
                FrameLayout.LayoutParams(dp(SheetDesign.GLYPH), dp(SheetDesign.GLYPH), Gravity.CENTER))
        }
        // the name: Latin, left to right, the system's face, the text colour; read before the title
        val name = text(SheetDesign.NAME, SheetDesign.name, c.text, face = null, ltr = true).apply { tag = NAME_TAG }
        inOrder(emblem to LinearLayout.LayoutParams(dp(SheetDesign.EMBLEM), dp(SheetDesign.EMBLEM)),
            name to LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).after(SheetDesign.NAME_GAP))
    }

    /** A card of rows (the disclosure card, the guardian's): the card colour, rows 14 above and below, dividers between. */
    fun card(rows: List<View>): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, SheetDesign.CARD_RADIUS)
        setPadding(dp(SheetDesign.CARD_PAD_H), dp(SheetDesign.CARD_PAD_V), dp(SheetDesign.CARD_PAD_H), dp(SheetDesign.CARD_PAD_V))
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(ctx).apply { setBackgroundColor(c.divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(SheetDesign.DIVIDER))))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A disclosure row: the icon (decoration) at the leading edge, centred on the first line, then the whole line. */
    fun row(icon: SheetIcon, line: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(SheetDesign.ROW_PAD_V), 0, dp(SheetDesign.ROW_PAD_V))
        val type = SheetDesign.row(s).scaled(look.bodyScale)
        val t = text(line, type, c.text)
        val iconTop = (firstLine(t, type) - dp(SheetDesign.ICON)) / 2
        val image = ImageView(ctx).apply {
            setImageDrawable(SheetIconDrawable(icon, c.iconInk, mirrored = look.rtl))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        inOrder(image to LinearLayout.LayoutParams(dp(SheetDesign.ICON), dp(SheetDesign.ICON)).apply { topMargin = max(0, iconTop) },
            t to LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).after(SheetDesign.ICON_GAP).apply { topMargin = max(0, -iconTop) })
    }

    /** A row of buttons of equal width with the design's gap, the first one at the leading edge of the deck's direction. */
    fun buttonRow(vararg b: Button): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        inOrder(*b.mapIndexed { i, v ->
            v to LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) after(SheetDesign.BUTTON_GAP) }
        }.toTypedArray())
    }

    /** The height of one line of [t] in [type] (the size after the font scale, times the line height). */
    fun firstLine(t: TextView, type: Type): Int = ceil(t.textSize * type.lineHeightEm).toInt()

    /** The hadith card: the soft tint, the text, the reference 10 below it. */
    fun hadith(line: String, reference: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.softTint, SheetDesign.CARD_RADIUS)
        val p = dp(SheetDesign.HADITH_PAD)
        setPadding(p, p, p, p)
        addView(text(line, SheetDesign.hadith(s).scaled(look.bodyScale), c.text))
        reference?.let {
            addView(text(it, SheetDesign.reference(s).scaled(look.bodyScale), c.secondary),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(SheetDesign.HADITH_GAP) })
        }
    }

    /**
     * A row with a switch at the trailing edge (the guardian's confirmation, the settings switch). The whole row is one
     * switch for TalkBack (its text, "switch", on or off) and toggles on a tap anywhere in it.
     */
    fun switchRow(line: String, type: Type, padV: Float, onChange: (Boolean) -> Unit): Pair<LinearLayout, SheetSwitch> {
        val sw = SheetSwitch(ctx, c, look.rtl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(padV), 0, dp(padV))
            minimumHeight = dp(SheetDesign.DRAG_ZONE)
            inOrder(text(line, type, c.text) to LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                sw to LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).after(SheetDesign.ICON_GAP))
            isClickable = true
            isFocusable = true
            setOnClickListener { sw.toggle() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = sw.isChecked
                }

                override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onInitializeAccessibilityEvent(host, event)
                    event.className = Switch::class.java.name
                    event.isChecked = sw.isChecked
                }
            }
        }
        sw.onChange = { on -> row.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED); onChange(on) }
        return row to sw
    }

    /**
     * One of the bar's buttons: full width, at least 52 high, corner radius 14, the label 16 / 20 semibold. [primary]:
     * filled with the accent, label on-accent ("Share"); else filled with the tint, label in the accent or its
     * fallback ("Not now"). Same size, same shape, same type, same hue.
     */
    fun button(label: String, primary: Boolean): Button = Button(ctx, null, 0, 0).apply {
        val ink = if (primary) c.onAccent else c.accentInk
        styleText(this, label, SheetDesign.button(s), ink, look.face)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        minHeight = dp(SheetDesign.BUTTON_HEIGHT)
        minimumHeight = dp(SheetDesign.BUTTON_HEIGHT)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(SheetDesign.BUTTON_PAD_H), dp(SheetDesign.BUTTON_PAD_V), dp(SheetDesign.BUTTON_PAD_H), dp(SheetDesign.BUTTON_PAD_V))
        stateListAnimator = null
        val fill = rounded(if (primary) c.accent else c.tint, SheetDesign.BUTTON_RADIUS)
        background = RippleDrawable(ColorStateList.valueOf((ink and 0x00FFFFFF) or (0x2E shl 24)), fill, null)
    }

    /** The column that scrolls: 24 at the sides, 20 above the header, 22 below the last block. */
    fun column(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(SheetDesign.SIDE), dp(SheetDesign.TOP), dp(SheetDesign.SIDE), dp(SheetDesign.BOTTOM))
    }

    /** The sheet around [column] and the bar of [share] over [notNow]: what a dialog's window shows. */
    fun frame(column: LinearLayout, share: Button, notNow: Button): SheetFrame {
        val box = SheetScrollBox(ctx, c.background, column)
        val bar = SheetBar(ctx, this, share, notNow)
        val panel = SheetPanel(ctx, this, box, bar)
        // left to right always: the drawing mirrors itself (see inOrder)
        return SheetFrame(ctx, this, panel).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
    }

    companion object {
        /** The tag of the name's view (tests find it by it). */
        const val NAME_TAG = "quranlab.sheet.name"
    }
}

/** The design's switch, drawn the same in both toolkits: a track in the accent when on, a white thumb. */
internal class SheetSwitch(ctx: Context, private val c: SheetColors, private val rtl: Boolean) : View(ctx), Checkable {
    private var on = false
    var onChange: ((Boolean) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(dpf(SheetDesign.SWITCH_W).roundToInt(), dpf(SheetDesign.SWITCH_H).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = if (on) c.accent else c.switchOff
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, h / 2, h / 2, paint)
        val inset = dpf(SheetDesign.THUMB_INSET)
        val r = h / 2 - inset
        val end = on != rtl
        paint.color = c.thumb
        canvas.drawCircle(if (end) w - inset - r else inset + r, h / 2, r, paint)
    }

    override fun isChecked(): Boolean = on

    override fun setChecked(checked: Boolean) = set(checked, notify = true)

    /** Sets the state; [notify] false for a state read back from the SDK (no answer of the learner). */
    fun set(checked: Boolean, notify: Boolean) {
        if (on == checked) return
        on = checked
        invalidate()
        if (notify) onChange?.invoke(checked)
    }

    override fun toggle() { isChecked = !on }
}

/** The scrolling column with the fades: 40 of the background above the bar while more is below, 28 under the top once scrolled. */
internal class SheetScrollBox(ctx: Context, background: Int, column: View) : FrameLayout(ctx) {
    val scroll = ScrollView(ctx)
    private val clear = background and 0x00FFFFFF
    private val bottomFade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(clear, background))
    private val topFade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, clear))
    /** Tests: open scrolled to the end (the design's second reference screen). */
    var startAtEnd = false

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = false
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidate() }
        setWillNotDraw(false)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (startAtEnd) {
            startAtEnd = false
            scroll.scrollTo(0, max(0, (scroll.getChildAt(0)?.height ?: 0) - scroll.height))
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val d = resources.displayMetrics.density
        if (scroll.canScrollVertically(1)) {
            bottomFade.setBounds(0, height - (SheetDesign.FADE_BOTTOM * d).roundToInt(), width, height)
            bottomFade.draw(canvas)
        }
        if (scroll.canScrollVertically(-1)) {
            topFade.setBounds(0, 0, width, (SheetDesign.FADE_TOP * d).roundToInt())
            topFade.draw(canvas)
        }
    }
}

/**
 * The bar that does not scroll: a 1 dp divider, then 12, the two buttons (always the same height: the taller label
 * decides) with 10 between, 24 at the sides, and 30 below or the system's bottom inset when that is larger.
 */
internal class SheetBar(ctx: Context, private val k: SheetKit, val share: Button, val notNow: Button) : ViewGroup(ctx) {
    var bottomInset = 0
    private val line = Paint().apply { color = k.c.barDivider }

    init {
        setWillNotDraw(false)
        setBackgroundColor(k.c.background)
        addView(share)
        addView(notNow)
    }

    private fun top() = max(1, k.dp(SheetDesign.DIVIDER)) + k.dp(SheetDesign.BAR_TOP)

    /** The labels' size as the design gives it: 16 sp after the font scale. */
    private val designSize = share.textSize
    private var fittedTo = -1

    /**
     * Two lines at most and nothing cut: when a label would need a third line in [width], both labels step down
     * together (the two buttons keep the same type) until both fit in two lines, never below 16 at 100 % text.
     */
    private fun fit(width: Int) {
        if (width == fittedTo) return
        fittedTo = width
        val floor = k.dpf(16f)
        fun lines(b: Button, size: Float): Int {
            val p = android.text.TextPaint(b.paint).apply { textSize = size }
            return StaticLayout.Builder.obtain(b.text, 0, b.text.length, p, max(1, width))
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setIncludePad(false).build().lineCount
        }
        var size = designSize
        while (size * FIT_STEP >= floor && (lines(share, size) > 2 || lines(notNow, size) > 2)) size *= FIT_STEP
        if (share.textSize != size) {
            share.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            notNow.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = max(0, w - 2 * k.dp(SheetDesign.BAR_SIDE))
        fit(inner - share.paddingLeft - share.paddingRight)
        val exact = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        share.measure(exact, free)
        notNow.measure(exact, free)
        val h = max(share.measuredHeight, notNow.measuredHeight)
        val same = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        share.measure(exact, same)
        notNow.measure(exact, same)
        setMeasuredDimension(w, top() + h + k.dp(SheetDesign.BUTTON_GAP) + h + max(k.dp(SheetDesign.BAR_BOTTOM), bottomInset))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = k.dp(SheetDesign.BAR_SIDE)
        val y = top()
        share.layout(x, y, x + share.measuredWidth, y + share.measuredHeight)
        val y2 = y + share.measuredHeight + k.dp(SheetDesign.BUTTON_GAP)
        notNow.layout(x, y2, x + notNow.measuredWidth, y2 + notNow.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), max(1, k.dp(SheetDesign.DIVIDER)).toFloat(), line)
    }

    companion object {
        /** One step of the labels' fitting (the Compose sheet takes the same steps). */
        const val FIT_STEP = 0.94f
    }
}

/**
 * The sheet itself: on a phone the grabber, the scrolling column and the bar, top corners rounded, dragged down from
 * its top to close; on a wide screen ([card]) the same without the grabber, every corner rounded.
 */
internal class SheetPanel(ctx: Context, private val k: SheetKit, val box: SheetScrollBox, val bar: SheetBar) : ViewGroup(ctx) {
    var card = false
        set(v) {
            if (field == v) return
            field = v
            shape()
        }
    /** Called when a drag down from the top closes the sheet (the dialog counts it as "Not now"). */
    var onDragDismiss: (() -> Unit)? = null
    private val bg = GradientDrawable().apply { setColor(k.c.background) }
    private val grab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = k.c.grabber }
    private val radius = k.dpf(SheetDesign.SHEET_RADIUS * k.look.radiusFactor)

    init {
        setWillNotDraw(false)
        background = bg
        addView(box)
        addView(bar)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // a phone sheet's bottom corners are below the screen's edge
                outline.setRoundRect(0, 0, view.width, view.height + if (card) 0 else radius.roundToInt(), radius)
            }
        }
        shape()
    }

    private fun shape() {
        bg.cornerRadii = if (card) FloatArray(8) { radius } else floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        invalidateOutline()
        // on a phone the grabber's row takes the band under the handle, and the column 8 less above the header
        box.scroll.getChildAt(0)?.let { col ->
            val top = k.dp(if (card) SheetDesign.TOP else SheetDesign.TOP - SheetDesign.GRABBER_BELOW)
            if (col.paddingTop != top) col.setPadding(col.paddingLeft, top, col.paddingRight, col.paddingBottom)
        }
    }

    /** The grabber's row on a phone: the scrolling viewport begins below it. */
    private fun grabberSpace() = if (card) 0 else k.dp(SheetDesign.GRABBER_ROW)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        bar.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val top = grabberSpace()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val room = max(0, size - top - bar.measuredHeight)
        if (mode == MeasureSpec.EXACTLY) {
            box.measure(exactW, MeasureSpec.makeMeasureSpec(room, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, size)
        } else {
            box.measure(exactW, if (mode == MeasureSpec.AT_MOST) MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, top + box.measuredHeight + bar.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = grabberSpace()
        box.layout(0, top, box.measuredWidth, top + box.measuredHeight)
        bar.layout(0, top + box.measuredHeight, bar.measuredWidth, top + box.measuredHeight + bar.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (card) return
        val w = k.dpf(SheetDesign.GRABBER_W)
        val h = k.dpf(SheetDesign.GRABBER_H)
        val y = k.dpf(SheetDesign.GRABBER_TOP)
        val x = (width - w) / 2
        canvas.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, grab)
    }

    // ---- a drag down from the top of a phone sheet ----

    private var downY = 0f
    private var armed = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (card || onDragDismiss == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e)
            MotionEvent.ACTION_MOVE -> if (armed && e.rawY - downY > slop) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    private fun start(e: MotionEvent) {
        downY = e.rawY
        dragging = false
        armed = e.y < k.dp(SheetDesign.DRAG_ZONE)
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (card || onDragDismiss == null) return super.onTouchEvent(e)
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { start(e); return armed }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && armed && e.rawY - downY > slop) dragging = true
                if (dragging) translationY = max(0f, e.rawY - downY)
                return armed
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val v = velocity?.yVelocity ?: 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && (translationY > max(k.dpf(96f), height * 0.25f) || v > k.dpf(1000f))) onDragDismiss?.invoke()
                    else animate().translationY(0f).setDuration(160).start()
                }
                dragging = false
                armed = false
                velocity?.recycle()
                velocity = null
                return true
            }
        }
        return armed
    }
}

/**
 * What the sheet's window shows: the space around the sheet (a tap there closes it) and the sheet, at the bottom on a
 * phone (it opens tall: its top 10 below the status bar) or a centred card at most 480 wide on a screen 600 wide or
 * more. [insetTop] and [insetBottom] are the system bars' insets (the window is drawn behind them).
 */
internal class SheetFrame(ctx: Context, private val k: SheetKit, val panel: SheetPanel) : ViewGroup(ctx) {
    var insetTop = 0
    var insetBottom = 0
    var onOutside: (() -> Unit)? = null
    private var pressedOutside = false

    init {
        addView(panel)
        setOnApplyWindowInsetsListener { _, insets ->
            val (t, b) = SheetWindow.barInsets(insets)
            if (t != insetTop || b != insetBottom) {
                insetTop = t
                insetBottom = b
                requestLayout()
            }
            insets
        }
    }

    fun wide(width: Int) = width >= k.dp(SheetDesign.WIDE_FROM)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (wide(w)) {
            panel.card = true
            panel.bar.bottomInset = 0
            val cw = min(k.dp(SheetDesign.CARD_MAX_WIDTH), w - 2 * k.dp(SheetDesign.CARD_MARGIN))
            val maxH = max(0, h - insetTop - insetBottom - 2 * k.dp(SheetDesign.CARD_MARGIN))
            panel.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        } else {
            panel.card = false
            panel.bar.bottomInset = insetBottom
            panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(0, h - insetTop - k.dp(SheetDesign.SHEET_TOP_GAP)), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (panel.card) {
            val x = (w - panel.measuredWidth) / 2
            val y = insetTop + (h - insetTop - insetBottom - panel.measuredHeight) / 2
            panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
        } else {
            val y = insetTop + k.dp(SheetDesign.SHEET_TOP_GAP)
            panel.layout(0, y, w, y + panel.measuredHeight)
        }
    }

    private fun outside(e: MotionEvent) = e.x < panel.left || e.x > panel.right || e.y < panel.top + panel.translationY || e.y > panel.bottom

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressedOutside = outside(e)
            MotionEvent.ACTION_UP -> if (pressedOutside && outside(e)) { pressedOutside = false; onOutside?.invoke() }
            MotionEvent.ACTION_CANCEL -> pressedOutside = false
        }
        return pressedOutside
    }
}

/**
 * The window of a sheet, the same for the Views dialog and the Compose sheet: drawn behind the system bars (the frame
 * pads itself by their insets), bars transparent, the app behind dimmed, the sheet sliding up from the bottom on a
 * phone (the system's window animation; none when the learner turned animations off).
 */
internal object SheetWindow {
    @Suppress("DEPRECATION")
    fun prepare(w: Window, wide: Boolean, lightSheet: Boolean) {
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        w.setDimAmount(SheetDesign.SCRIM_ALPHA)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        val lightNav = lightSheet && !wide
        if (Build.VERSION.SDK_INT >= 30) {
            w.attributes = w.attributes.apply { fitInsetsTypes = 0; fitInsetsSides = 0 }
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.setSystemBarsAppearance(if (lightNav) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (lightNav) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = f
        }
        w.setWindowAnimations(if (wide) android.R.style.Animation_Dialog else android.R.style.Animation_InputMethod)
    }

    /** The system bars' top and bottom insets (with a display cutout at the top), in pixels. */
    @Suppress("DEPRECATION")
    fun barInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            i.top to i.bottom
        } else {
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }

    /** A screen of this many dp across is wide: the sheet is a centred card on it. */
    fun wide(ctx: Context) = ctx.resources.configuration.screenWidthDp >= SheetDesign.WIDE_FROM
}
