package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.SharingDialogs
import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.SheetBar
import ai.quranlab.tajweedformer.SheetContrast
import ai.quranlab.tajweedformer.SheetIcon
import ai.quranlab.tajweedformer.SheetWindow
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.SheetColors
import ai.quranlab.tajweedformer.sharing.SheetDesign
import ai.quranlab.tajweedformer.sharing.SheetExit
import ai.quranlab.tajweedformer.sharing.Sharing
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.app.Activity
import android.content.ContextWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The look of the Compose sharing sheets (consent, guardian, thanks, settings): background ([container]),
 * text ([content]), secondary text, accent, corner radius (or a whole [shape]) and the title and body
 * fonts. Since 3.1.1 the sheets follow Quran Lab's sheet design, the same on Android, Apple and web: by default its
 * colours, light or dark with the system's night mode, and the app's `MaterialTheme` primary colour as the accent
 * (Quran Lab's green when the app sets no colour scheme). A style changes how the words look, never the words (those
 * are the copy deck's, or the app's own set with `client.sharing.setWording`), the order of the sheet or the name
 * "Quran Lab" at its top, so it does not change the consent version.
 */
public class SharingStyle(
    public val container: Color = Color.Unspecified,
    public val content: Color = Color.Unspecified,
    /**
     * Both buttons use it: the two choices have equal weight. Unspecified: the app's `MaterialTheme` primary. When it
     * is under 4.5 to 1 as a label on its own tint, the "Not now" label and the icons use the text colour.
     */
    public val accent: Color = Color.Unspecified,
    /** The sheet's outline; the cards and buttons keep theirs. */
    public val shape: Shape? = null,
    /** The hadith's reference and other secondary text; unspecified: the design's. */
    public val secondaryContent: Color = Color.Unspecified,
    /** The sheet's corner radius when no [shape] is given (28 by default); the cards and buttons scale by the same factor. */
    public val cornerRadius: Dp? = null,
    public val titleFont: FontFamily? = null,
    public val bodyFont: FontFamily? = null,
    /** Light or dark (3.1.1): null follows the system's night mode, or a dark [container] when one is given. */
    public val dark: Boolean? = null,
)

/** The look of one Compose sheet: the colours for its appearance, the radius factor, the style's fonts, the deck's language. */
internal class ComposeLook(
    val colors: SheetColors,
    val radiusFactor: Float,
    val shape: Shape?,
    val titleFont: FontFamily?,
    val bodyFont: FontFamily?,
    val lang: String,
    val rtl: Boolean,
) {
    val script: SheetDesign.Script get() = SheetDesign.script(lang)
    fun radius(v: Float): Dp = (v * radiusFactor).dp
}

private val baselinePrimaries = setOf(lightColorScheme().primary, darkColorScheme().primary)

private fun Color.argbOrNull(): Int? = if (this == Color.Unspecified) null else toArgb()

/**
 * The look for [style] and the deck [copy]: night mode from the system unless the style says otherwise; the accent is
 * the style's, else the app's `MaterialTheme` primary, else (no colour scheme set: Material's own baseline primary)
 * Quran Lab's.
 */
@Composable
internal fun rememberLook(style: SharingStyle, copy: SharingCopy): ComposeLook {
    val night = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val more = remember(context) { SheetContrast.more(context) }
    return remember(style, copy, night, primary, more) {
        val container = style.container.argbOrNull()
        val dark = style.dark ?: container?.let { SheetDesign.isDark(SheetDesign.opaque(it, SheetDesign.WHITE)) } ?: night
        val accent = style.accent.argbOrNull() ?: primary.takeUnless { it in baselinePrimaries }?.toArgb()
        val colors = SheetDesign.colors(dark, container, style.content.argbOrNull(), style.secondaryContent.argbOrNull(), accent, moreContrast = more)
        ComposeLook(colors, SheetDesign.radiusFactor(style.cornerRadius?.value), style.shape, style.titleFont, style.bodyFont, copy.lang, copy.rtl)
    }
}

/** A text style of the design: everything set here, nothing taken from the app's typography or content colour. */
internal fun sheetStyle(type: SheetDesign.Type, color: Color, family: FontFamily?, ltr: Boolean, align: TextAlign = TextAlign.Start) = TextStyle(
    color = color,
    fontSize = type.sizeSp.sp,
    fontWeight = FontWeight(type.weight),
    fontFamily = family ?: FontFamily.Default,
    letterSpacing = type.trackingEm.em,
    lineHeight = type.lineHeightEm.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    textDirection = if (ltr) TextDirection.Ltr else TextDirection.Rtl,
    textAlign = align,
    lineBreak = LineBreak.Paragraph,
    hyphens = Hyphens.None,
)

@Composable
private fun SheetText(text: String, type: SheetDesign.Type, color: Int, look: ComposeLook, modifier: Modifier = Modifier,
                      family: FontFamily? = look.bodyFont, ltr: Boolean = !look.rtl, align: TextAlign = TextAlign.Start) {
    BasicText(if (type.upper) text.uppercase(Locale(look.lang)) else text, modifier, style = sheetStyle(type, Color(color), family, ltr, align))
}

/** One of the design's icons (decoration: no semantics), from the same paths as the Views dialog's. */
@Composable
private fun SheetIconImage(icon: SheetIcon, color: Int, size: Dp, modifier: Modifier = Modifier, mirrored: Boolean = false) {
    val path = remember(icon, mirrored) { icon.path(mirrored).asComposePath() }
    Canvas(modifier.size(size)) {
        val f = this.size.width / SheetIcon.BOX
        scale(f, f, pivot = Offset.Zero) {
            drawPath(path, Color(color), style = Stroke(width = icon.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The design's switch, drawn as in the Views dialog: a track in the accent when on, a white thumb. */
@Composable
private fun SheetSwitchImage(on: Boolean, look: ComposeLook) {
    val k = look.colors
    Canvas(Modifier.size(SheetDesign.SWITCH_W.dp, SheetDesign.SWITCH_H.dp)) {
        val h = size.height
        drawRoundRect(Color(if (on) k.accent else k.switchOff), cornerRadius = CornerRadius(h / 2, h / 2))
        val inset = SheetDesign.THUMB_INSET.dp.toPx()
        val r = h / 2 - inset
        val end = on != look.rtl
        drawCircle(Color(k.thumb), r, Offset(if (end) size.width - inset - r else inset + r, h / 2))
    }
}

/** The header: the emblem (decoration) and the name, one row, centred; the name Latin, left to right, the system's face. */
@Composable
private fun SheetHeader(look: ComposeLook) {
    val k = look.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(SheetDesign.EMBLEM.dp).clip(RoundedCornerShape(look.radius(SheetDesign.EMBLEM_RADIUS))).background(Color(k.tint))
            .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            SheetIconImage(SheetIcon.WAVEFORM, k.accentInk, SheetDesign.GLYPH.dp)
        }
        Spacer(Modifier.width(SheetDesign.NAME_GAP.dp))
        SheetText(SheetDesign.NAME, SheetDesign.name, k.text, look, family = null, ltr = true)
    }
}

/** A card of rows: the card colour, 2 above and below, 16 at the sides; dividers between the rows. */
@Composable
private fun SheetCard(look: ComposeLook, modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    val k = look.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(look.radius(SheetDesign.CARD_RADIUS))).background(Color(k.card))
        .padding(horizontal = SheetDesign.CARD_PAD_H.dp, vertical = SheetDesign.CARD_PAD_V.dp)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(SheetDesign.DIVIDER.dp).background(Color(k.divider)))
            row()
        }
    }
}

/** A disclosure row: the icon at the leading edge, centred on the first line, then the whole line. */
@Composable
private fun SheetRow(icon: SheetIcon, line: String, look: ComposeLook) {
    val k = look.colors
    val type = SheetDesign.row(look.script)
    val d = LocalDensity.current
    val iconTop = with(d) { (ceil(type.sizeSp.sp.toPx() * type.lineHeightEm) - SheetDesign.ICON.dp.toPx()) / 2f / density }.dp
    Row(Modifier.fillMaxWidth().padding(vertical = SheetDesign.ROW_PAD_V.dp)) {
        SheetIconImage(icon, k.iconInk, SheetDesign.ICON.dp, Modifier.padding(top = max(0f, iconTop.value).dp), mirrored = look.rtl)
        Spacer(Modifier.width(SheetDesign.ICON_GAP.dp))
        SheetText(line, type, k.text, look, Modifier.weight(1f).padding(top = max(0f, -iconTop.value).dp))
    }
}

/** The bar's two buttons, always the same size: the taller label decides the height of both. */
@Composable
private fun EqualButtons(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Layout(content = { first(); second() }) { measurables, constraints ->
        val w = constraints.maxWidth
        val gap = SheetDesign.BUTTON_GAP.dp.roundToPx()
        val h = max(SheetDesign.BUTTON_HEIGHT.dp.roundToPx(), measurables.maxOf { it.minIntrinsicHeight(w) })
        val placed = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(w, h * 2 + gap) {
            placed[0].place(0, 0)
            placed[1].place(0, h + gap)
        }
    }
}

/**
 * The buttons' type for [labels] in [width]: the design's, or, when a label would need a third line, a step smaller
 * for both together until both fit in two lines (never below 16 at 100 % text): two lines at most, nothing cut. The
 * Views dialog takes the same steps.
 */
@Composable
private fun fittedButtonType(labels: List<String>, look: ComposeLook, width: Dp): SheetDesign.Type {
    val measurer = rememberTextMeasurer()
    val d = LocalDensity.current
    val base = SheetDesign.button(look.script)
    return remember(labels, width, d, look) {
        val px = with(d) { width.roundToPx() }.coerceAtLeast(1)
        val floor = with(d) { 16.dp.toPx() }
        fun lines(t: SheetDesign.Type) = labels.maxOf {
            measurer.measure(AnnotatedString(it), sheetStyle(t, Color.Black, look.bodyFont, !look.rtl, TextAlign.Center),
                constraints = Constraints(maxWidth = px)).lineCount
        }
        var f = 1f
        while (with(d) { (base.sizeSp * f * SheetBar.FIT_STEP).sp.toPx() } >= floor && lines(base.scaled(f)) > 2) f *= SheetBar.FIT_STEP
        base.scaled(f)
    }
}

/** One of the bar's buttons: [primary] filled with the accent, label on-accent; else the tint, label in the accent or its fallback. */
@Composable
private fun SheetButton(label: String, primary: Boolean, look: ComposeLook, type: SheetDesign.Type, enabled: Boolean = true, onClick: () -> Unit) {
    val k = look.colors
    Box(Modifier.alpha(if (enabled) 1f else SheetDesign.DISABLED_ALPHA).clip(RoundedCornerShape(look.radius(SheetDesign.BUTTON_RADIUS)))
        .background(Color(if (primary) k.accent else k.tint)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = SheetDesign.BUTTON_PAD_H.dp, vertical = SheetDesign.BUTTON_PAD_V.dp), contentAlignment = Alignment.Center) {
        SheetText(label, type, if (primary) k.onAccent else k.accentInk, look, align = TextAlign.Center)
    }
}

/**
 * The sheet around a column and a bar: on a phone (narrower than 600) at the bottom, tall (its top 10 below the
 * status bar), with the grabber, its top dragged down to close; on a wide screen a centred card at most 480 wide.
 * The column scrolls under the bar with the fades. A tap beside the sheet closes it ([onDismiss]).
 */
@Composable
private fun SheetFrame(look: ComposeLook, insetTop: Dp, insetBottom: Dp, startAtEnd: Boolean, onDismiss: () -> Unit,
                       bar: @Composable (labelWidth: Dp) -> Unit, column: @Composable ColumnScope.() -> Unit) {
    val k = look.colors
    val bg = Color(k.background)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= SheetDesign.WIDE_FROM.dp
        val screenHeight = maxHeight
        // the space beside the sheet: a tap there closes it (no semantics: back closes it for TalkBack)
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        val scroll = rememberScrollState(if (startAtEnd) Int.MAX_VALUE else 0)
        // the end stays the end while the window's insets arrive and the viewport changes (the device check's pictures)
        if (startAtEnd) LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val r = look.radius(SheetDesign.SHEET_RADIUS)
        val shape = look.shape ?: if (wide) RoundedCornerShape(r) else RoundedCornerShape(topStart = r, topEnd = r)
        val sheetWidth = if (wide) min(SheetDesign.CARD_MAX_WIDTH, maxWidth.value - 2 * SheetDesign.CARD_MARGIN).dp else maxWidth
        val place = if (wide) {
            Modifier.align(Alignment.Center).padding(top = insetTop, bottom = insetBottom)
                .width(sheetWidth)
                .heightIn(max = (maxHeight - insetTop - insetBottom - (2 * SheetDesign.CARD_MARGIN).dp).coerceAtLeast(0.dp))
        } else {
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height((maxHeight - insetTop - SheetDesign.SHEET_TOP_GAP.dp).coerceAtLeast(0.dp))
                .offset { IntOffset(0, drag.value.roundToInt()) }
        }
        Box(place.clip(shape).background(bg).pointerInput(Unit) { detectTapGestures { } }) {
            Column {
                // the grabber's row: the scrolling viewport begins below it, so nothing scrolled shows behind the handle
                if (!wide) Box(Modifier.fillMaxWidth().height(SheetDesign.GRABBER_ROW.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(top = SheetDesign.GRABBER_TOP.dp).size(SheetDesign.GRABBER_W.dp, SheetDesign.GRABBER_H.dp)
                        .clip(RoundedCornerShape(50)).background(Color(k.grabber)))
                }
                Box(Modifier.weight(1f, fill = !wide).drawWithContent {
                    drawContent()
                    if (scroll.value < scroll.maxValue) {
                        val h = SheetDesign.FADE_BOTTOM.dp.toPx()
                        drawRect(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    }
                    if (scroll.value > 0) {
                        val h = SheetDesign.FADE_TOP.dp.toPx()
                        drawRect(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)), startY = 0f, endY = h), size = Size(size.width, h))
                    }
                }) {
                    Column(Modifier.verticalScroll(scroll).padding(start = SheetDesign.SIDE.dp, end = SheetDesign.SIDE.dp,
                        top = (if (wide) SheetDesign.TOP else SheetDesign.TOP - SheetDesign.GRABBER_BELOW).dp, bottom = SheetDesign.BOTTOM.dp), content = column)
                }
                // the bar: a 1 dp divider, 12, the buttons, 30 below (or the bottom inset when larger), 24 at the sides
                Column(Modifier.fillMaxWidth().background(bg).drawBehind {
                    drawRect(Color(k.barDivider), size = Size(size.width, max(1f, SheetDesign.DIVIDER.dp.toPx())))
                }.padding(start = SheetDesign.BAR_SIDE.dp, end = SheetDesign.BAR_SIDE.dp, top = (SheetDesign.DIVIDER + SheetDesign.BAR_TOP).dp,
                    bottom = if (wide) SheetDesign.BAR_BOTTOM.dp else maxOf(SheetDesign.BAR_BOTTOM.dp, insetBottom))) {
                    bar(sheetWidth - (2 * (SheetDesign.BAR_SIDE + SheetDesign.BUTTON_PAD_H)).dp)
                }
            }
            if (!wide) {
                // the grabber's touch target: a drag down closes the sheet, a short one springs back
                val d = LocalDensity.current
                val sheetHeight = screenHeight - insetTop
                Box(Modifier.fillMaxWidth().height(SheetDesign.DRAG_ZONE.dp).draggable(
                    state = rememberDraggableState { delta -> scope.launch { drag.snapTo(max(0f, drag.value + delta)) } },
                    orientation = Orientation.Vertical,
                    onDragStopped = { v ->
                        val far = with(d) { drag.value > max(96.dp.toPx(), sheetHeight.toPx() * 0.25f) || v > 1000.dp.toPx() }
                        if (far) onDismiss() else drag.animateTo(0f)
                    },
                ))
            }
        }
    }
}

/**
 * The consent sheet, in the design of [SheetDesign]: the deck's lines in the design's order, two equal choices,
 * nothing pre-ticked; the guardian variant asks the parent or guardian to turn the switch on first. [onShare] and
 * [onNotNow] are the buttons, [onDismiss] a tap beside the sheet or a drag down. [insetTop] and [insetBottom]: the
 * system bars (the window is drawn behind them); [startAtEnd]: open scrolled to the end (the screenshot tests).
 */
@Composable
internal fun ConsentSheet(
    c: SharingCopy,
    variant: ConsentState.Variant,
    guardian: Boolean,
    style: SharingStyle,
    insetTop: Dp,
    insetBottom: Dp,
    onShare: () -> Unit,
    onNotNow: () -> Unit,
    onDismiss: () -> Unit,
    startAtEnd: Boolean = false,
) {
    val look = rememberLook(style, c)
    val k = look.colors
    val s = look.script
    var confirmed by remember { mutableStateOf(false) }
    val title: String
    val body: String
    if (guardian) {
        title = c["sheet.guardian.title"]
        body = c["sheet.guardian.body"]
    } else {
        title = c["sheet.title"]
        body = c["sheet.body"]
    }
    // the disclosure, each line whole, in this order, with its icon
    val rows = listOf(SheetIcon.MIC to c["sheet.what"], SheetIcon.SHIELD to c["sheet.who"], SheetIcon.TOGGLE to c["sheet.control"])
    CompositionLocalProvider(LocalLayoutDirection provides if (c.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        SheetFrame(look, insetTop, insetBottom, startAtEnd, onDismiss, bar = { labelWidth ->
            val share = c[if (guardian) "sheet.guardian.share" else "sheet.share"]
            val notNow = c["sheet.not_now"]
            val type = fittedButtonType(listOf(share, notNow), look, labelWidth)
            EqualButtons(
                { SheetButton(share, primary = true, look, type, enabled = !guardian || confirmed, onClick = onShare) },
                { SheetButton(notNow, primary = false, look, type, onClick = onNotNow) },
            )
        }) {
            SheetHeader(look)
            Spacer(Modifier.height(SheetDesign.HEADER_GAP.dp))
            SheetText(title, SheetDesign.title(s), k.text, look, family = look.titleFont ?: look.bodyFont)
            if (variant == ConsentState.Variant.REASK) {
                Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
                SheetText(c["reask"], SheetDesign.body(s), k.body, look)
            }
            Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
            SheetText(body, SheetDesign.body(s), k.body, look)
            Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
            SheetCard(look, rows = rows.map { (icon, line) -> @Composable { SheetRow(icon, line, look) } })
            c.hadith?.let { h ->
                Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(look.radius(SheetDesign.CARD_RADIUS))).background(Color(k.softTint))
                    .padding(SheetDesign.HADITH_PAD.dp), verticalArrangement = Arrangement.spacedBy(SheetDesign.HADITH_GAP.dp)) {
                    SheetText(h, SheetDesign.hadith(s), k.text, look)
                    c.hadithRef?.let { SheetText(it, SheetDesign.reference(s), k.secondary, look) }
                }
            }
            if (!guardian) {
                Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
                SheetText(c["sheet.mistakes"], SheetDesign.mistakes(s), k.body, look)
            }
            if (guardian) {
                // the parent or guardian turns the switch on first; until then "Share" is disabled (and "Not now" never is)
                Spacer(Modifier.height(SheetDesign.BLOCK_GAP.dp))
                SheetCard(look, rows = listOf(@Composable {
                    Row(Modifier.fillMaxWidth().toggleable(value = confirmed, role = Role.Switch, onValueChange = { confirmed = it })
                        .padding(vertical = SheetDesign.ROW_PAD_V.dp), verticalAlignment = Alignment.CenterVertically) {
                        SheetText(c["sheet.guardian.confirm"], SheetDesign.row(s), k.text, look, Modifier.weight(1f))
                        Spacer(Modifier.width(SheetDesign.ICON_GAP.dp))
                        SheetSwitchImage(confirmed, look)
                    }
                }))
            }
        }
    }
}

/**
 * [ConsentSheet] in its own window (drawn behind the system bars, the app dimmed). What each ending records is 3.1.0's
 * rule ([SheetExit]): "Share" a yes, "Not now" a no, and a dismissal (back, a tap beside the sheet, a drag down) a no,
 * as 3.1.0's dialog did on back and on a click outside; a sheet that leaves the composition records nothing.
 */
@Composable
internal fun SharingConsentDialog(sharing: Sharing, variant: ConsentState.Variant, guardian: Boolean = sharing.guardian,
                                  style: SharingStyle = SharingStyle(), onDone: (Boolean) -> Unit) {
    ConsentDialog(sharing.copy, variant, guardian, style) { exit ->
        exit.record(sharing, guardian)
        onDone(exit == SheetExit.SHARE)
    }
}

/**
 * The consent sheet's dialog; [onExit] hears how it ended. The same window as the Views dialog's (a platform Dialog
 * with [SheetWindow]'s setup, drawn behind the system bars): Compose's own Dialog caps its content at the screen
 * height without the bars, which keeps an edge-to-edge sheet off the screen's bottom. The sheet is composed in that
 * window as part of the app's composition (its CompositionLocals, MaterialTheme included). Back cancels the dialog,
 * as 3.1.0's dialog closed on back; the sheet leaving the composition closes the window and records nothing.
 * [startAtEnd]: the device check's pictures.
 */
@Composable
internal fun ConsentDialog(c: SharingCopy, variant: ConsentState.Variant, guardian: Boolean, style: SharingStyle, startAtEnd: Boolean = false,
                           onExit: (SheetExit) -> Unit) {
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val light = !rememberLook(style, c).colors.dark
    val exit by rememberUpdatedState(onExit)
    val sheet by rememberUpdatedState<@Composable (Dp, Dp) -> Unit> { top, bottom ->
        ConsentSheet(c, variant, guardian, style, insetTop = top, insetBottom = bottom,
            onShare = { exit(SheetExit.SHARE) },
            onNotNow = { exit(SheetExit.NOT_NOW) },
            onDismiss = { exit(SheetExit.CLOSED) },
            startAtEnd = startAtEnd)
    }
    DisposableEffect(view) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity
            ?: return@DisposableEffect onDispose {}
        val bars = mutableStateOf(0 to 0)
        val host = FrameLayout(activity)
        host.setOnApplyWindowInsetsListener { _, insets ->
            bars.value = SheetWindow.barInsets(insets)
            insets
        }
        host.setViewTreeLifecycleOwner(view.findViewTreeLifecycleOwner())
        host.setViewTreeSavedStateRegistryOwner(view.findViewTreeSavedStateRegistryOwner())
        host.addView(ComposeView(activity).apply {
            setParentCompositionContext(parent)
            setContent {
                val d = LocalDensity.current
                val (top, bottom) = bars.value
                sheet(with(d) { top.toDp() }, with(d) { bottom.toDp() })
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(host)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { exit(SheetExit.CLOSED) }
        dialog.window?.let { SheetWindow.prepare(it, SheetWindow.wide(activity), light) }
        dialog.show()
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
    }
}

/** A short line in a dialog: the thank-you (once) or the impact line, in the sheet's colours and type. */
@Composable
internal fun SharingMessage(text: String, copy: SharingCopy, style: SharingStyle = SharingStyle(), onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone) {
        val look = rememberLook(style, copy)
        CompositionLocalProvider(LocalLayoutDirection provides if (copy.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.clip(look.shape ?: RoundedCornerShape(look.radius(SheetDesign.SHEET_RADIUS))).background(Color(look.colors.background))
                .clickable { onDone() }.padding(SheetDesign.SIDE.dp)) {
                SheetText(text, SheetDesign.body(look.script), look.colors.body, look)
            }
        }
    }
}

/**
 * Shows the SDK's sharing sheets in Compose when the SDK asks (the consent sheet after a take, the thank-you),
 * instead of its Views dialogs. Call once near the root of the app's UI, with the app's client:
 *
 * ```
 * SharingSheetHost(client)
 * ```
 */
@Composable
public fun SharingSheetHost(client: TajweedClient, style: SharingStyle = SharingStyle()) {
    val sharing = client.cloud.sharing
    var request by remember { mutableStateOf<Sharing.SheetRequest?>(null) }
    DisposableEffect(client) {
        // while this host is on screen the SDK's requests come here; afterwards its own dialogs take them again
        val before = sharing.prompts
        sharing.prompts = { request = it }
        onDispose { sharing.prompts = before }
    }
    when (val r = request) {
        is Sharing.SheetRequest.Consent -> SharingConsentDialog(sharing, r.variant, r.guardian, style) { request = null }
        is Sharing.SheetRequest.Thanks -> SharingMessage(sharing.copy["thanks"], sharing.copy, style) { request = null }
        is Sharing.SheetRequest.Impact -> SharingMessage(sharing.copy.impactMinutes(r.minutes), sharing.copy, style) { request = null }
        null -> {}
    }
}

/**
 * The settings rows: "Share my recitations" (off ends the upload in progress and deletes the unsent
 * queue), "What is shared", "Delete what I shared" and the recovery code. Deletion and the code
 * open the SDK's own dialogs: the app never handles either. Since 3.1.1 in the colours and type of the sharing sheet.
 *
 * ```
 * SharingSettings(client)
 * ```
 */
@Composable
public fun SharingSettings(client: TajweedClient, style: SharingStyle = SharingStyle()) {
    val sharing = client.cloud.sharing
    val c = sharing.copy
    val activity = LocalContext.current as? Activity
    var on by remember { mutableStateOf(sharing.isOn) }
    val look = rememberLook(style, c)
    val type = SheetDesign.body(look.script)
    val k = look.colors
    val row = Modifier.fillMaxWidth().heightIn(min = SheetDesign.SETTINGS_ROW.dp)
    CompositionLocalProvider(LocalLayoutDirection provides if (c.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            Row(row.toggleable(value = on, role = Role.Switch, onValueChange = { v ->
                on = v
                Thread { sharing.setOn(v) }.start()
            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetText(c["settings.toggle"], type, k.text, look, Modifier.weight(1f))
                Spacer(Modifier.width(SheetDesign.ICON_GAP.dp))
                SheetSwitchImage(on, look)
            }
            Row(row.clickable { activity?.let { SharingDialogs.whatIsShared(it, sharing, SharingTheme.current) } }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SheetText(c["settings.what"], type, k.text, look)
            }
            Row(row.clickable { activity?.let { SharingDialogs.confirmDelete(it, sharing, SharingTheme.current) { on = sharing.isOn } } }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SheetText(c["settings.delete"], type, k.text, look)
            }
            Row(row.clickable { activity?.let { SharingDialogs.showRecoveryCode(it, sharing, SharingTheme.current) } }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SheetText(c["recovery.reveal"], type, k.text, look)
            }
        }
    }
}
