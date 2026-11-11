package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.Sharing
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The look of the SDK's sharing sheets (consent, guardian, thanks, settings and their dialogs; Views
 * toolkit; the Compose module's `SharingStyle` is the same for Compose). Defaults follow the activity's
 * theme (`colorBackground`, `textColorPrimary`, `colorAccent`). A theme changes how the words look,
 * never the words, so it does not change the consent version. Set it once with
 * `SharingTheme.current = SharingTheme(...)`: the SDK's own sheets and [SharingSettingsView] use it.
 */
public class SharingTheme @JvmOverloads constructor(
    /** The sheet's background colour (an ARGB int); null: the activity theme's. */
    public val background: Int? = null,
    public val text: Int? = null,
    public val secondaryText: Int? = null,
    /** Both buttons use it: the two choices have equal weight. */
    public val accent: Int? = null,
    public val cornerRadiusDp: Float = 20f,
    /** The body font (and the titles', unless [titleTypeface] is set). */
    public val typeface: Typeface? = null,
    public val titleSizeSp: Float = 20f,
    public val bodySizeSp: Float = 15f,
    /** The titles' font; null: [typeface] in bold. */
    public val titleTypeface: Typeface? = null,
) {
    public companion object {
        /** The look of the SDK's own sharing sheets in this process. */
        @JvmStatic
        @Volatile
        public var current: SharingTheme = SharingTheme()
    }

    internal fun resolve(ctx: Context): Resolved {
        fun attr(id: Int, fallback: Int): Int {
            val tv = TypedValue()
            return if (ctx.theme.resolveAttribute(id, tv, true)) {
                if (tv.resourceId != 0) try { ctx.getColor(tv.resourceId) } catch (_: Exception) { tv.data } else tv.data
            } else fallback
        }
        val bg = background ?: attr(android.R.attr.colorBackground, Color.WHITE)
        val fg = text ?: attr(android.R.attr.textColorPrimary, Color.BLACK)
        return Resolved(bg, fg, secondaryText ?: (fg and 0x00ffffff) or (0xb3 shl 24), accent ?: attr(android.R.attr.colorAccent, 0xff1b6e4f.toInt()),
            cornerRadiusDp, typeface, titleSizeSp, bodySizeSp, titleTypeface)
    }

    internal data class Resolved(val bg: Int, val fg: Int, val fg2: Int, val accent: Int, val radius: Float, val face: Typeface?, val title: Float, val body: Float,
                                 val titleFace: Typeface? = null)
}

/** The one button label the copy deck has no key for (closing a message). */
internal object SdkText {
    const val CLOSE = "OK"
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

    private fun dialog(activity: Activity, copy: SharingCopy, t: SharingTheme.Resolved, content: View, secure: Boolean = false): Dialog {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (secure) d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val card = FrameLayout(activity)
        card.background = GradientDrawable().apply { setColor(t.bg); cornerRadius = dp(activity, t.radius) }
        card.layoutDirection = if (copy.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val scroll = ScrollView(activity)
        scroll.addView(content)
        card.addView(scroll)
        d.setContentView(card)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return d
    }

    internal fun text(ctx: Context, t: SharingTheme.Resolved, s: String, title: Boolean = false, secondary: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(if (secondary) t.fg2 else t.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (title) t.title else t.body)
            typeface = if (title) (t.titleFace ?: Typeface.create(t.face ?: Typeface.DEFAULT, Typeface.BOLD)) else (t.face ?: Typeface.DEFAULT)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, 0, 0, dp(ctx, 12f).toInt())
        }

    /** The two choices look alike: same shape, same colour, same size. */
    internal fun button(ctx: Context, t: SharingTheme.Resolved, s: String): Button = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextColor(t.accent)
        typeface = Typeface.create(t.face ?: Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 1.5f).toInt(), t.accent)
            cornerRadius = dp(ctx, 12f)
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(ctx, 4f).toInt(); marginEnd = dp(ctx, 4f).toInt()
        }
    }

    internal fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    /** The consent sheet: every disclosure line of the deck, two equal choices, nothing pre-ticked. */
    @JvmStatic
    @JvmOverloads
    fun consent(activity: Activity, sharing: Sharing, variant: ConsentState.Variant, guardian: Boolean, theme: SharingTheme = SharingTheme()) {
        val c = sharing.copy
        val t = theme.resolve(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(activity, 24f).toInt()
            setPadding(p, p, p, p)
        }
        if (variant == ConsentState.Variant.REASK) col.addView(text(activity, t, c["reask"], secondary = true))
        if (guardian) {
            col.addView(text(activity, t, c["sheet.guardian.title"], title = true))
            col.addView(text(activity, t, c["sheet.guardian.body"]))
        } else {
            col.addView(text(activity, t, c["sheet.title"], title = true))
            col.addView(text(activity, t, c["sheet.body"]))
            col.addView(text(activity, t, c["sheet.mistakes"]))
        }
        col.addView(text(activity, t, c["sheet.what"]))
        col.addView(text(activity, t, c["sheet.who"]))
        col.addView(text(activity, t, c["sheet.control"]))
        c.hadith?.let { h ->
            col.addView(text(activity, t, h, secondary = true).apply { setTypeface(typeface, Typeface.ITALIC) })
            c.hadithRef?.let { col.addView(text(activity, t, it, secondary = true)) }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val share = button(activity, t, c[if (guardian) "sheet.guardian.share" else "sheet.share"])
        val notNow = button(activity, t, c["sheet.not_now"])
        if (guardian) {
            val confirm = CheckBox(activity).apply {
                text = c["sheet.guardian.confirm"]
                setTextColor(t.fg)
                buttonTintList = ColorStateList.valueOf(t.accent)
                isChecked = false
            }
            share.isEnabled = false
            share.alpha = 0.5f
            confirm.setOnCheckedChangeListener { _, on -> share.isEnabled = on; share.alpha = if (on) 1f else 0.5f }
            col.addView(confirm)
        }
        row.addView(notNow)
        row.addView(share)
        col.addView(row)
        val d = dialog(activity, c, t, col)
        var answered = false
        share.setOnClickListener { answered = true; sharing.accept(guardian); d.dismiss() }
        notNow.setOnClickListener { answered = true; sharing.decline(); d.dismiss() }
        // dismissing the sheet counts as "Not now"
        d.setOnDismissListener { if (!answered) sharing.decline() }
        d.show()
    }

    private fun message(activity: Activity, copy: SharingCopy, line: String, theme: SharingTheme, secure: Boolean = false, title: String? = null,
                        retry: Pair<String, () -> Unit>? = null) {
        val t = theme.resolve(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(activity, 24f).toInt()
            setPadding(p, p, p, p)
        }
        title?.let { col.addView(text(activity, t, it, title = true)) }
        col.addView(text(activity, t, line))
        val ok = button(activity, t, if (retry != null) copy["delete.cancel"] else SdkText.CLOSE)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; addView(ok) }
        col.addView(row)
        val d = dialog(activity, copy, t, col, secure)
        ok.setOnClickListener { d.dismiss() }
        retry?.let { (label, action) ->
            val again = button(activity, t, label)
            row.addView(again)
            again.setOnClickListener { d.dismiss(); action() }
        }
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
        val t = theme.resolve(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(activity, 24f).toInt()
            setPadding(p, p, p, p)
        }
        col.addView(text(activity, t, c["settings.delete"], title = true))
        col.addView(text(activity, t, c.deletion("delete.confirm")))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = button(activity, t, c["delete.cancel"])
        val del = button(activity, t, c["delete.action"])
        row.addView(cancel)
        row.addView(del)
        col.addView(row)
        val d = dialog(activity, c, t, col)
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
                val t = theme.resolve(activity)
                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = dp(activity, 24f).toInt()
                    setPadding(p, p, p, p)
                }
                val c = sharing.copy
                col.addView(text(activity, t, c["recovery.reveal"], title = true))
                col.addView(text(activity, t, c["recovery.keep"]))
                col.addView(text(activity, t, c["recovery.hint"], secondary = true))
                col.addView(TextView(activity).apply {
                    text = code
                    setTextColor(t.fg)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    textDirection = View.TEXT_DIRECTION_LTR
                    inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setTextIsSelectable(false)
                    setPadding(0, 0, 0, dp(activity, 16f).toInt())
                })
                val ok = button(activity, t, SdkText.CLOSE)
                col.addView(LinearLayout(activity).apply { addView(ok) })
                val d = dialog(activity, sharing.copy, t, col, secure = true)
                ok.setOnClickListener { d.dismiss() }
                d.show()
            }
        }
    }
}

/**
 * The settings rows, ready to place in an app's settings screen: "Share my recitations" (a switch:
 * off stops uploads at once and deletes the unsent queue, whatever words the learner agreed to), "What is
 * shared", "Delete what I shared", and the recovery code (once something was shared). Deletion and the code open the
 * SDK's own dialogs, after the learner confirms.
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
    private val toggle = Switch(activity)

    init {
        orientation = VERTICAL
        val t = theme.resolve(activity)
        val c = sharing.copy
        layoutDirection = if (c.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        toggle.text = c["settings.toggle"]
        toggle.setTextColor(t.fg)
        t.face?.let { toggle.typeface = it }
        toggle.isChecked = sharing.isOn
        toggle.setOnCheckedChangeListener { _, on -> Executors.newSingleThreadExecutor().execute { sharing.setOn(on) } }
        addView(toggle, row())
        addView(rowText(t, c["settings.what"]) { SharingDialogs.whatIsShared(activity, sharing, theme) }, row())
        addView(rowText(t, c["settings.delete"]) { SharingDialogs.confirmDelete(activity, sharing, theme) { toggle.isChecked = sharing.isOn } }, row())
        addView(rowText(t, c["recovery.reveal"]) { SharingDialogs.showRecoveryCode(activity, sharing, theme) }, row())
    }

    private fun row() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = SharingDialogs.dp(context, 6f).toInt(); bottomMargin = SharingDialogs.dp(context, 6f).toInt()
    }

    private fun rowText(t: SharingTheme.Resolved, s: String, click: () -> Unit) = TextView(context).apply {
        text = s
        setTextColor(t.fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.body + 1)
        t.face?.let { typeface = it }
        val p = SharingDialogs.dp(context, 10f).toInt()
        setPadding(0, p, 0, p)
        isClickable = true
        setOnClickListener { click() }
    }

    /** Re-reads the state (after the app changed it). */
    public fun refresh() { toggle.isChecked = sharing.isOn }
}
