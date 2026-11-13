package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.SheetDesign
import ai.quranlab.tajweedformer.sharing.SheetExit
import ai.quranlab.tajweedformer.sharing.Sharing
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * The look of the SDK's sharing sheets (consent, guardian, thanks, settings and their dialogs; Views toolkit; the
 * Compose module's `SharingStyle` is the same for Compose). Since 3.1.1 the sheets follow Quran Lab's sheet design,
 * the same on Android, Apple and web: by default its colours, light or dark with the system's night mode, and the
 * accent of the app's theme (`android:colorAccent`, when the app's theme or a library theme it uses sets it), else
 * Quran Lab's green. A theme changes how the words look, never the words, the order of the sheet or the name "Quran
 * Lab" at its top, so it does not change the consent version. Set it once with
 * `SharingTheme.current = SharingTheme(...)`: the SDK's own sheets and [SharingSettingsView] use it.
 */
public class SharingTheme @JvmOverloads constructor(
    /** The sheet's background colour (an ARGB int); null: the design's (white, or near-black in the dark). */
    public val background: Int? = null,
    /** The text colour; null: the design's. */
    public val text: Int? = null,
    /** The hadith's reference and other secondary text; null: the design's. */
    public val secondaryText: Int? = null,
    /**
     * The accent: the share button's fill, "Not now"'s label and tint, the icons, the switch. Both buttons use it: the
     * two choices have equal weight. null: the app theme's `android:colorAccent`, else Quran Lab's. When it is under
     * 4.5 to 1 as a label on its own tint, the "Not now" label and the icons use the text colour.
     */
    public val accent: Int? = null,
    /** The sheet's corner radius (28 since 3.1.1); the cards and buttons scale theirs by the same factor. */
    public val cornerRadiusDp: Float = 28f,
    /** The body font (and the titles', unless [titleTypeface] is set); the sheet sets the weight. */
    public val typeface: Typeface? = null,
    /** The title's size (26 since 3.1.1); its line height follows. */
    public val titleSizeSp: Float = 26f,
    /** The body's size (15.5 since 3.1.1); the other running text scales with it. */
    public val bodySizeSp: Float = 15.5f,
    /** The titles' font; null: [typeface] in bold. */
    public val titleTypeface: Typeface? = null,
    /**
     * Light or dark (3.1.1): null follows the system's night mode (as the activity sees it), or a dark [background]
     * when one is given; true or false fixes it.
     */
    public val dark: Boolean? = null,
) {
    public companion object {
        /** The look of the SDK's own sharing sheets in this process. */
        @JvmStatic
        @Volatile
        public var current: SharingTheme = SharingTheme()
    }

    /** The look of one sheet in [ctx] (the activity: its night mode and its theme's accent) for the deck [copy]. */
    internal fun look(ctx: Context, copy: SharingCopy): SheetLook {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = dark ?: background?.let { SheetDesign.isDark(SheetDesign.opaque(it, SheetDesign.WHITE)) } ?: night
        val colors = SheetDesign.colors(dark, background, text, secondaryText, accent ?: appAccent(ctx), moreContrast = SheetContrast.more(ctx))
        return SheetLook(colors, SheetDesign.radiusFactor(cornerRadiusDp), typeface, titleTypeface,
            if (titleSizeSp > 0f) titleSizeSp / 26f else 1f, if (bodySizeSp > 0f) bodySizeSp / 15.5f else 1f, copy.lang, copy.rtl)
    }

    /**
     * The accent of the app's theme: `android:colorAccent` as the activity's theme resolves it, when the app's theme (or
     * a library theme it uses, such as AppCompat's) sets it. The framework's own default (a resource of the `android`
     * package) is not the app's: then null, and the sheet takes Quran Lab's.
     */
    private fun appAccent(ctx: Context): Int? {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) return null
        if (tv.resourceId != 0 && (tv.resourceId ushr 24) == 0x01) return null
        if (tv.resourceId != 0) return try { ctx.getColor(tv.resourceId) } catch (_: Exception) { null }
        return if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else null
    }
}

/** The one button label the copy deck has no key for (closing a message). */
internal object SdkText {
    const val CLOSE = "OK"
}

/**
 * The consent sheet as Views, in the design of [SheetDesign]: what [SharingDialogs.consent] puts in its window, and
 * what the screenshot tests draw. [onShare] and [onNotNow] are the two buttons; the frame's [SheetFrame.onOutside] and
 * the panel's [SheetPanel.onDragDismiss] are for the dialog to set.
 */
internal fun consentSheet(ctx: Context, c: SharingCopy, variant: ConsentState.Variant, guardian: Boolean, theme: SharingTheme,
                          onShare: () -> Unit, onNotNow: () -> Unit): SheetFrame {
    val k = SheetKit(ctx, theme.look(ctx, c))
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
    val col = k.column()
    col.addView(k.header())
    col.addView(k.title(title), k.gap(SheetDesign.HEADER_GAP))
    if (variant == ConsentState.Variant.REASK) col.addView(k.body(c["reask"]), k.gap(SheetDesign.BLOCK_GAP))
    col.addView(k.body(body), k.gap(SheetDesign.BLOCK_GAP))
    col.addView(k.card(rows.map { (icon, line) -> k.row(icon, line) }), k.gap(SheetDesign.BLOCK_GAP))
    c.hadith?.let { col.addView(k.hadith(it, c.hadithRef), k.gap(SheetDesign.BLOCK_GAP)) }
    if (!guardian) col.addView(k.mistakes(c["sheet.mistakes"]), k.gap(SheetDesign.BLOCK_GAP))
    val share = k.button(c[if (guardian) "sheet.guardian.share" else "sheet.share"], primary = true)
    val notNow = k.button(c["sheet.not_now"], primary = false)
    if (guardian) {
        // the parent or guardian turns the switch on first; until then "Share" is disabled (and "Not now" never is)
        share.isEnabled = false
        share.alpha = SheetDesign.DISABLED_ALPHA
        val (row, _) = k.switchRow(c["sheet.guardian.confirm"], SheetDesign.row(k.look.script).scaled(k.look.bodyScale), SheetDesign.ROW_PAD_V) { on ->
            share.isEnabled = on
            share.alpha = if (on) 1f else SheetDesign.DISABLED_ALPHA
        }
        col.addView(k.card(listOf(row)), k.gap(SheetDesign.BLOCK_GAP))
    }
    share.setOnClickListener { onShare() }
    notNow.setOnClickListener { onNotNow() }
    return k.frame(col, share, notNow)
}

/** The SDK's own sheets and dialogs over an activity (the Views toolkit; the Compose module has its own). */
internal object SharingDialogs {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "quranlab-sharing-ui").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Shows what the SDK asked for (a consent sheet, the thank-you, the impact line), with the app's own wording where it set one. */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity, sharing: Sharing, request: Sharing.SheetRequest, theme: SharingTheme = SharingTheme()) {
        when (request) {
            is Sharing.SheetRequest.Consent -> consent(activity, sharing, request.variant, request.guardian, theme)
            is Sharing.SheetRequest.Thanks -> message(activity, sharing.copy, sharing.copy["thanks"], theme)
            is Sharing.SheetRequest.Impact -> message(activity, sharing.copy, sharing.copy.impactMinutes(request.minutes), theme)
        }
    }

    internal fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    /**
     * The consent sheet: the deck's lines in the design's order, two equal choices, nothing pre-ticked. A bottom sheet
     * on a phone, a centred card on a wide screen. What each ending records is 3.1.0's rule ([SheetExit]): "Share" a
     * yes, "Not now" a no, and closing it without a button (back, a tap beside it, a drag down) a no, as 3.1.0's
     * cancelable dialog did; a sheet whose window goes with its activity records nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun consent(activity: Activity, sharing: Sharing, variant: ConsentState.Variant, guardian: Boolean, theme: SharingTheme = SharingTheme()) {
        consentDialog(activity, sharing.copy, variant, guardian, theme) { exit -> exit.record(sharing, guardian) }.show()
    }

    /**
     * The consent sheet's dialog, not shown yet; [onExit] hears how it ended, once at most, in 3.1.0's order: a button
     * first records its answer and then closes the dialog; a dismissal that no button caused is [SheetExit.CLOSED].
     * A dialog whose window is removed with its activity (a configuration change, the activity finishing) is not
     * dismissed, and [onExit] hears nothing, as in 3.1.0. [startAtEnd]: the device test's pictures.
     */
    internal fun consentDialog(activity: Activity, c: SharingCopy, variant: ConsentState.Variant, guardian: Boolean, theme: SharingTheme,
                               startAtEnd: Boolean = false, onExit: (SheetExit) -> Unit): Dialog {
        var answered = false
        var d: Dialog? = null
        val frame = consentSheet(activity, c, variant, guardian, theme,
            onShare = { answered = true; onExit(SheetExit.SHARE); d?.dismiss() },
            onNotNow = { answered = true; onExit(SheetExit.NOT_NOW); d?.dismiss() })
        frame.panel.box.startAtEnd = startAtEnd
        // a cancelable dialog, as 3.1.0's: the back gesture closes it
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d = dialog
        dialog.setCancelable(true)
        dialog.setContentView(frame)
        dialog.window?.let { SheetWindow.prepare(it, SheetWindow.wide(activity), !theme.look(activity, c).colors.dark) }
        frame.onOutside = { dialog.dismiss() }
        frame.panel.onDragDismiss = { dialog.dismiss() }
        // dismissing the sheet without a button counts as "Not now", as in 3.1.0
        dialog.setOnDismissListener { if (!answered) onExit(SheetExit.CLOSED) }
        return dialog
    }

    /** A centred card dialog (the thank-you, the impact line, What is shared, the deletion's steps, the recovery code). */
    private fun dialog(activity: Activity, k: SheetKit, content: View, secure: Boolean = false): Dialog {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (secure) d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val card = FrameLayout(activity)
        card.background = GradientDrawable().apply { setColor(k.c.background); cornerRadius = k.dpf(SheetDesign.SHEET_RADIUS * k.look.radiusFactor) }
        // left to right always: the drawing mirrors itself in a right to left deck (SheetKit)
        card.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val scroll = ScrollView(activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content)
        card.addView(scroll)
        d.setContentView(card)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = activity.resources.displayMetrics
        d.window?.setLayout(min((dm.widthPixels * 0.92).toInt(), k.dp(SheetDesign.CARD_MAX_WIDTH)), ViewGroup.LayoutParams.WRAP_CONTENT)
        return d
    }

    private fun column(k: SheetKit) = LinearLayout(k.ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = k.dp(SheetDesign.SIDE)
        setPadding(p, p, p, p)
    }

    /** A row of buttons of equal width (the design's buttons; the last one is the action), in the deck's direction. */
    private fun buttons(k: SheetKit, vararg b: Button): LinearLayout = k.buttonRow(*b)

    private fun message(activity: Activity, copy: SharingCopy, line: String, theme: SharingTheme, secure: Boolean = false, title: String? = null,
                        retry: Pair<String, () -> Unit>? = null) {
        val k = SheetKit(activity, theme.look(activity, copy))
        val col = column(k)
        title?.let { col.addView(k.title(it), k.gap(0f)) }
        col.addView(k.body(line), k.gap(if (title != null) SheetDesign.HEADER_GAP else 0f))
        val ok = k.button(if (retry != null) copy["delete.cancel"] else SdkText.CLOSE, primary = retry == null)
        val again = retry?.let { k.button(it.first, primary = true) }
        col.addView(if (again != null) buttons(k, ok, again) else buttons(k, ok), k.gap(SheetDesign.BLOCK_GAP + 4f))
        val d = dialog(activity, k, col, secure)
        ok.setOnClickListener { d.dismiss() }
        retry?.let { (_, action) -> again?.setOnClickListener { d.dismiss(); action() } }
        d.show()
    }

    /** "What is shared": the full disclosure. */
    @JvmStatic
    @JvmOverloads
    fun whatIsShared(activity: Activity, sharing: Sharing, theme: SharingTheme = SharingTheme()) =
        message(activity, sharing.copy, sharing.copy["what.detail"], theme, title = sharing.copy["settings.what"])

    /**
     * "Delete what I shared": the SDK's confirmation, then the signed request (the device key signs;
     * no app credential can delete). The only way an app reaches deletion is to open this dialog;
     * the learner confirms.
     */
    @JvmStatic
    @JvmOverloads
    fun confirmDelete(activity: Activity, sharing: Sharing, theme: SharingTheme = SharingTheme(), done: (() -> Unit)? = null) {
        val c = sharing.copy
        val k = SheetKit(activity, theme.look(activity, c))
        val col = column(k)
        col.addView(k.title(c["settings.delete"]))
        col.addView(k.body(c.deletion("delete.confirm")), k.gap(SheetDesign.HEADER_GAP))
        val cancel = k.button(c["delete.cancel"], primary = false)
        val del = k.button(c["delete.action"], primary = true)
        col.addView(buttons(k, cancel, del), k.gap(SheetDesign.BLOCK_GAP + 4f))
        val d = dialog(activity, k, col)
        cancel.setOnClickListener { d.dismiss() }
        del.setOnClickListener {
            del.isEnabled = false
            d.dismiss()
            delete(activity, sharing, theme, done)
        }
        d.show()
    }

    /** The signed deletion request, then its outcome: done, or failed with "Try again". */
    private fun delete(activity: Activity, sharing: Sharing, theme: SharingTheme, done: (() -> Unit)?) {
        io.execute {
            val r = sharing.deleteEverything()
            main.post {
                if (activity.isFinishing) return@post
                val c = sharing.copy
                when (r) {
                    Sharing.Deletion.ACCEPTED, Sharing.Deletion.NOTHING_SHARED -> message(activity, c, c.deletion("delete.done"), theme)
                    else -> message(activity, c, c["delete.failed.network"], theme, title = c["delete.failed.title"],
                        retry = c["delete.retry"] to { delete(activity, sharing, theme, done) })
                }
                done?.invoke()
            }
        }
    }

    /** The recovery code, shown only to the learner, in a window screenshots and screen recording cannot capture. */
    @JvmStatic
    @JvmOverloads
    fun showRecoveryCode(activity: Activity, sharing: Sharing, theme: SharingTheme = SharingTheme()) {
        io.execute {
            val code = sharing.recoveryCode()
            main.post {
                if (code == null || activity.isFinishing) return@post
                val c = sharing.copy
                val k = SheetKit(activity, theme.look(activity, c))
                val col = column(k)
                col.addView(k.title(c["recovery.reveal"]))
                col.addView(k.body(c["recovery.keep"]), k.gap(SheetDesign.HEADER_GAP))
                col.addView(k.text(c["recovery.hint"], SheetDesign.row(k.look.script), k.c.secondary), k.gap(SheetDesign.HADITH_GAP))
                col.addView(TextView(activity, null, 0, 0).apply {
                    text = code
                    setTextColor(k.c.text)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    textDirection = View.TEXT_DIRECTION_LTR
                    inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setTextIsSelectable(false)
                }, k.gap(SheetDesign.BLOCK_GAP))
                val ok = k.button(SdkText.CLOSE, primary = true)
                col.addView(buttons(k, ok), k.gap(SheetDesign.BLOCK_GAP + 4f))
                val d = dialog(activity, k, col, secure = true)
                ok.setOnClickListener { d.dismiss() }
                d.show()
            }
        }
    }
}

/**
 * The settings rows, ready to place in an app's settings screen: "Share my recitations" (a switch:
 * off ends the upload in progress and deletes the unsent queue, whatever words the learner agreed to), "What is
 * shared", "Delete what I shared", and the recovery code (once something was shared). Deletion and the code open the
 * SDK's own dialogs, after the learner confirms. Since 3.1.1 in the colours and type of the sharing sheet.
 *
 * ```
 * settingsLayout.addView(SharingSettingsView(activity, client))
 * ```
 */
public class SharingSettingsView @JvmOverloads constructor(
    private val activity: Activity,
    client: TajweedClient,
    private val theme: SharingTheme = SharingTheme.current,
) : LinearLayout(activity) {
    private val sharing: Sharing = client.cloud.sharing
    private val toggle: SheetSwitch

    init {
        orientation = VERTICAL
        val c = sharing.copy
        val k = SheetKit(activity, theme.look(activity, c))
        // left to right always: the rows mirror themselves in a right to left deck (SheetKit)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        val type = SheetDesign.body(k.look.script).scaled(k.look.bodyScale)
        val (switchRow, sw) = k.switchRow(c["settings.toggle"], type, 8f) { on -> Executors.newSingleThreadExecutor().execute { sharing.setOn(on) } }
        toggle = sw
        toggle.set(sharing.isOn, notify = false)
        switchRow.minimumHeight = k.dp(SheetDesign.SETTINGS_ROW)
        addView(switchRow, rowParams())
        addView(rowText(k, type, c["settings.what"]) { SharingDialogs.whatIsShared(activity, sharing, theme) }, rowParams())
        addView(rowText(k, type, c["settings.delete"]) { SharingDialogs.confirmDelete(activity, sharing, theme) { refresh() } }, rowParams())
        addView(rowText(k, type, c["recovery.reveal"]) { SharingDialogs.showRecoveryCode(activity, sharing, theme) }, rowParams())
    }

    private fun rowParams() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun rowText(k: SheetKit, type: SheetDesign.Type, s: String, click: () -> Unit) = k.text(s, type, k.c.text).apply {
        val p = k.dp(12f)
        setPadding(0, p, 0, p)
        minHeight = k.dp(SheetDesign.SETTINGS_ROW)
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    /** Re-reads the state (after the app changed it). */
    public fun refresh() { toggle.set(sharing.isOn, notify = false) }
}
