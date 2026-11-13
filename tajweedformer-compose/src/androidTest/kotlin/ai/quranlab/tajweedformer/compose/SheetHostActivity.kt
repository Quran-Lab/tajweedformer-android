package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.SharingDialogs
import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.SheetFrame
import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView

/**
 * The sheet's device check: an app's screen with the SDK's consent sheet over it, as the SDK shows it, the Views
 * dialog ([SharingDialogs.consentDialog], what `SharingDialogs.consent` shows) or the Compose sheet ([ConsentDialog],
 * what `SharingSheetHost` shows). A host starts it with the extras `kit` (views or compose), `lang`, `end` (open
 * scrolled to the end) and `guardian`, then ends the sheet with the system's own key and touch events (`adb shell
 * input`), a configuration change, the home key or a force-stop. Every step goes to the log under the tag `TJ_SHEET`:
 * `shown`, each `exit` with its [ai.quranlab.tajweedformer.sharing.SheetExit], `recreated`, `resumed` and the sheet's
 * geometry. No client, no network, no key: an ending is written to the log, not recorded anywhere.
 */
class SheetHostActivity : ComponentActivity() {
    private val logTag = "TJ_SHEET"
    private var dialog: Dialog? = null

    private class Shown(val copy: SharingCopy, val guardian: Boolean, val end: Boolean)

    private var compose by mutableStateOf<Shown?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        val d = resources.displayMetrics.density
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        screen.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * d).toInt()
            setPadding(p, p, p, p)
            addView(TextView(this@SheetHostActivity).apply { text = "An app"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.BLACK) })
            repeat(14) { i -> addView(TextView(this@SheetHostActivity).apply { text = "Line ${i + 1} of the app's own screen"; setTextColor(Color.DKGRAY) }) }
        }, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        screen.addView(ComposeView(this).apply {
            setContent {
                compose?.let { s ->
                    ConsentDialog(s.copy, ConsentState.Variant.FIRST, s.guardian, SharingStyle(), startAtEnd = s.end) { exit ->
                        // as SharingSheetHost: the request is done
                        Log.i(logTag, "exit compose $exit")
                        compose = null
                    }
                }
            }
        })
        setContentView(screen)
        // a recreated activity (a configuration change) does not show the sheet again, as an app does not
        if (savedInstanceState != null) Log.i(logTag, "recreated") else show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    override fun onResume() {
        super.onResume()
        Log.i(logTag, "resumed sheet=${dialog?.isShowing == true || compose != null}")
    }

    private fun show(i: Intent) {
        val kit = i.getStringExtra("kit") ?: return
        val copy = SharingCopy.forLanguage(i.getStringExtra("lang") ?: "en")
        val end = i.getBooleanExtra("end", false)
        val guardian = i.getBooleanExtra("guardian", false)
        if (kit == "views") {
            val dlg = SharingDialogs.consentDialog(this, copy, ConsentState.Variant.FIRST, guardian, SharingTheme(), end) { Log.i(logTag, "exit views $it") }
            dialog = dlg
            dlg.show()
            dlg.window?.decorView?.postDelayed({ geometry(dlg) }, 1200)
        } else {
            compose = Shown(copy, guardian, end)
        }
        Log.i(logTag, "shown $kit ${copy.lang} end=$end guardian=$guardian")
    }

    /** The Views sheet as laid out on this screen: the insets it got, its top, its bar, the labels' lines. */
    private fun geometry(dlg: Dialog) {
        val frame = (dlg.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? SheetFrame) ?: return
        val at = IntArray(2).also { frame.panel.getLocationOnScreen(it) }
        val bar = frame.panel.bar
        val barAt = IntArray(2).also { bar.getLocationOnScreen(it) }
        val m = windowManager.currentWindowMetrics.bounds
        Log.i(logTag, "geometry screen ${m.width()}x${m.height()} density ${resources.displayMetrics.density} insets ${frame.insetTop}/${frame.insetBottom} " +
            "sheet top ${at[1]} bar ${barAt[1]}..${barAt[1] + bar.height} share ${barAt[1] + bar.share.top}..${barAt[1] + bar.share.bottom} " +
            "notNow ${barAt[1] + bar.notNow.top}..${barAt[1] + bar.notNow.bottom} lines ${bar.share.lineCount}/${bar.notNow.lineCount}")
    }
}
