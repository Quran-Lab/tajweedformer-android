package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import ai.quranlab.tajweedformer.cloud.android.SharingDialogs
import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.Sharing
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The look of the Compose sharing sheets (consent, guardian, thanks, settings): background ([container]),
 * text ([content]), secondary text, accent, corner radius (or a whole [shape]) and the title and body
 * fonts. Defaults come from the app's MaterialTheme. A style changes how the words look, never the words
 * (those are the copy deck's, or the app's own set with `Sharing.setWording`), so it does not change the
 * consent version.
 */
data class SharingStyle(
    val container: Color = Color.Unspecified,
    val content: Color = Color.Unspecified,
    /** Both buttons use it: the two choices have equal weight. */
    val accent: Color = Color.Unspecified,
    val shape: Shape? = null,
    /** The re-ask line, the hadith and its reference; unspecified: [content] at 75 %. */
    val secondaryContent: Color = Color.Unspecified,
    /** The corner radius of the dialogs when no [shape] is given. */
    val cornerRadius: Dp? = null,
    val titleFont: FontFamily? = null,
    val bodyFont: FontFamily? = null,
) {
    internal val dialogShape: Shape? get() = shape ?: cornerRadius?.let { RoundedCornerShape(it) }
}

/**
 * The consent sheet's content: the deck's lines in order, two equal choices, nothing pre-ticked;
 * the guardian variant asks the parent or guardian to tick the confirmation first. Place it in a
 * dialog or bottom sheet of the app's choosing, or use [SharingConsentDialog].
 */
@Composable
fun SharingConsentSheet(
    sharing: Sharing,
    variant: ConsentState.Variant,
    guardian: Boolean = sharing.guardian,
    style: SharingStyle = SharingStyle(),
    onDone: (shared: Boolean) -> Unit,
) {
    val c = sharing.copy
    val fg = if (style.content != Color.Unspecified) style.content else MaterialTheme.colorScheme.onSurface
    val accent = if (style.accent != Color.Unspecified) style.accent else MaterialTheme.colorScheme.primary
    val fg2 = if (style.secondaryContent != Color.Unspecified) style.secondaryContent else fg.copy(alpha = 0.75f)
    val body = style.bodyFont
    var confirmed by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides if (c.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (variant == ConsentState.Variant.REASK) Text(c["reask"], color = fg2, fontFamily = body)
            if (guardian) {
                Text(c["sheet.guardian.title"], style = MaterialTheme.typography.titleLarge, color = fg, fontFamily = style.titleFont)
                Text(c["sheet.guardian.body"], color = fg, fontFamily = body)
            } else {
                Text(c["sheet.title"], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg, fontFamily = style.titleFont)
                Text(c["sheet.body"], color = fg, fontFamily = body)
                Text(c["sheet.mistakes"], color = fg, fontFamily = body)
            }
            Text(c["sheet.what"], color = fg, fontFamily = body)
            Text(c["sheet.who"], color = fg, fontFamily = body)
            Text(c["sheet.control"], color = fg, fontFamily = body)
            c.hadith?.let { h ->
                Text(h, color = fg2, fontStyle = FontStyle.Italic, fontFamily = body)
                c.hadithRef?.let { Text(it, color = fg2, fontFamily = body) }
            }
            if (guardian) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text(c["sheet.guardian.confirm"], color = fg, fontFamily = body)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { sharing.decline(); onDone(false) }, modifier = Modifier.weight(1f), border = BorderStroke(1.5.dp, accent)) {
                    Text(c["sheet.not_now"], color = accent, fontWeight = FontWeight.Bold, fontFamily = body)
                }
                OutlinedButton(onClick = { sharing.accept(guardian); onDone(true) }, enabled = !guardian || confirmed,
                    modifier = Modifier.weight(1f), border = BorderStroke(1.5.dp, accent.copy(alpha = if (!guardian || confirmed) 1f else 0.4f))) {
                    Text(c[if (guardian) "sheet.guardian.share" else "sheet.share"], color = accent, fontWeight = FontWeight.Bold, fontFamily = body)
                }
            }
        }
    }
}

/** [SharingConsentSheet] in a dialog; dismissing it counts as "Not now". */
@Composable
fun SharingConsentDialog(sharing: Sharing, variant: ConsentState.Variant, guardian: Boolean = sharing.guardian,
                         style: SharingStyle = SharingStyle(), onDone: (Boolean) -> Unit) {
    Dialog(onDismissRequest = { sharing.decline(); onDone(false) }) {
        Surface(shape = style.dialogShape ?: MaterialTheme.shapes.extraLarge,
            color = if (style.container != Color.Unspecified) style.container else MaterialTheme.colorScheme.surface) {
            SharingConsentSheet(sharing, variant, guardian, style, onDone)
        }
    }
}

/** A short line in a dialog: the thank-you (once) or the impact line. */
@Composable
fun SharingMessage(text: String, rtl: Boolean, style: SharingStyle = SharingStyle(), onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone) {
        Surface(shape = style.dialogShape ?: MaterialTheme.shapes.extraLarge,
            color = if (style.container != Color.Unspecified) style.container else MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Column(Modifier.padding(24.dp).clickable { onDone() }) {
                    Text(text, color = if (style.content != Color.Unspecified) style.content else MaterialTheme.colorScheme.onSurface,
                        fontFamily = style.bodyFont)
                }
            }
        }
    }
}

/**
 * Shows the SDK's sheets in Compose when the SDK asks (after a session with feedback, the
 * thank-you), instead of its Views dialogs. Call once near the root of the app's UI.
 */
@Composable
fun SharingSheetHost(style: SharingStyle = SharingStyle()) {
    var request by remember { mutableStateOf<Sharing.SheetRequest?>(null) }
    DisposableEffect(Unit) {
        QuranLabAndroid.sheets = { request = it }
        onDispose { QuranLabAndroid.sheets = null }
    }
    val sharing = QuranLabAndroid.cloud?.sharing ?: return
    when (val r = request) {
        is Sharing.SheetRequest.Consent -> SharingConsentDialog(sharing, r.variant, r.guardian, style) { request = null }
        is Sharing.SheetRequest.Thanks -> SharingMessage(sharing.copy["thanks"], sharing.copy.rtl, style) { request = null }
        is Sharing.SheetRequest.Impact -> SharingMessage(sharing.copy.impactMinutes(r.minutes), sharing.copy.rtl, style) { request = null }
        null -> {}
    }
}

/**
 * The settings rows: "Share my recitations" (off stops uploads at once and deletes the unsent
 * queue), "What is shared", "Delete what I shared" and the recovery code. Deletion and the code
 * open the SDK's own dialogs: the app never handles either.
 */
@Composable
fun SharingSettings(sharing: Sharing, style: SharingStyle = SharingStyle()) {
    val c = sharing.copy
    val activity = LocalContext.current as? Activity
    var on by remember { mutableStateOf(sharing.isOn) }
    val fg = if (style.content != Color.Unspecified) style.content else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalLayoutDirection provides if (c.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c["settings.toggle"], color = fg, modifier = Modifier.weight(1f), fontFamily = style.bodyFont)
                Switch(checked = on, onCheckedChange = { v ->
                    on = v
                    Thread { sharing.setOn(v) }.start()
                })
            }
            Row(Modifier.fillMaxWidth().clickable { activity?.let { SharingDialogs.whatIsShared(it, sharing, QuranLabAndroid.sharingTheme) } }.padding(vertical = 12.dp)) {
                Text(c["settings.what"], color = fg, fontFamily = style.bodyFont)
            }
            Row(Modifier.fillMaxWidth().clickable { activity?.let { SharingDialogs.confirmDelete(it, sharing, QuranLabAndroid.sharingTheme) { on = sharing.isOn } } }.padding(vertical = 12.dp)) {
                Text(c["settings.delete"], color = fg, fontFamily = style.bodyFont)
            }
            Row(Modifier.fillMaxWidth().clickable { activity?.let { SharingDialogs.showRecoveryCode(it, sharing, QuranLabAndroid.sharingTheme) } }.padding(vertical = 12.dp)) {
                Text(c["recovery.reveal"], color = fg, fontFamily = style.bodyFont)
            }
        }
    }
}
