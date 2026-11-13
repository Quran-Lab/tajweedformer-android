package ai.quranlab.tajweedformer.compose

import ai.quranlab.tajweedformer.SharingTheme
import ai.quranlab.tajweedformer.SheetContrast
import ai.quranlab.tajweedformer.SheetFrame
import ai.quranlab.tajweedformer.consentSheet
import ai.quranlab.tajweedformer.sharing.ConsentState
import ai.quranlab.tajweedformer.sharing.SheetDesign
import ai.quranlab.tajweedformer.sharing.SharingCopy
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.ide.common.rendering.api.RenderSession
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.resources.Density
import com.android.resources.LayoutDirection
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import org.junit.Rule
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

/**
 * The kit of the sharing sheet's screenshot and layout tests. Paparazzi renders with Android's own renderer
 * (layoutlib) on the JVM: the Compose sheet ([ConsentSheet]) and the core's Views dialog ([consentSheet]), each in the
 * frame the dialog's window shows, below a 44 dp status bar (the window is drawn behind the system bars), over the
 * reference screens' backdrop (the app behind, dimmed).
 */

/** The screens: a 390 by 844 dp phone at xxhdpi (1170 by 2532 px), a 320 dp wide phone, a tablet held sideways. */
internal object Screens {
    val PHONE: DeviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1170, screenHeight = 2532, xdpi = 460, ydpi = 460,
        density = Density.XXHIGH, softButtons = false, locale = "en")
    val NARROW: DeviceConfig = PHONE.copy(screenWidth = 960, screenHeight = 1704)
    val TABLET: DeviceConfig = PHONE.copy(screenWidth = 2560, screenHeight = 1600, xdpi = 320, ydpi = 320, density = Density.XHIGH,
        orientation = ScreenOrientation.LANDSCAPE, size = ScreenSize.XLARGE, ratio = ScreenRatio.NOTLONG)

    /** The status bar's height above the window's content in the pictures (a phone's, about). */
    const val STATUS_BAR = 44f

    /** The reference screens' backdrop: the app behind the sheet, dimmed. */
    fun backdrop(dark: Boolean): Int = if (dark) 0xFF0B0B0C.toInt() else 0xFF5E5E63.toInt()
}

/** One sheet to draw: the deck's language, the variant, the appearance, the text size, the screen, the theme. */
internal data class Case(
    val lang: String,
    val variant: ConsentState.Variant = ConsentState.Variant.FIRST,
    val guardian: Boolean = false,
    val dark: Boolean = false,
    val fontScale: Float = 1f,
    val screen: DeviceConfig = Screens.PHONE,
    val end: Boolean = false,
    val theme: SharingTheme = SharingTheme(),
    val style: SharingStyle = SharingStyle(),
    /** The device's own direction is right to left (an Arabic system), whatever the deck. */
    val deviceRtl: Boolean = false,
    /** The device asks for more contrast (high contrast text): the accent's marks need 4.5 to 1. */
    val moreContrast: Boolean = false,
) {
    val copy: SharingCopy get() = SharingCopy.forLanguage(lang)

    fun config(): DeviceConfig = screen.copy(fontScale = fontScale, nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
        locale = if (deviceRtl) "ar" else "en", layoutDirection = if (deviceRtl) LayoutDirection.RTL else LayoutDirection.LTR)
}

/**
 * A rendered frame as ARGB pixels. The renderer hands frames over as `java.awt` images, which an Android unit test's
 * sources cannot name (its compile classpath is Android's, without `java.awt`): they are read here by reflection.
 */
internal class Picture(val width: Int, val height: Int, val argb: IntArray) {
    fun at(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        /** The pixels of a `java.awt.image.BufferedImage`. */
        fun of(image: Any): Picture {
            val c = image.javaClass
            val w = c.getMethod("getWidth").invoke(image) as Int
            val h = c.getMethod("getHeight").invoke(image) as Int
            val px = IntArray(w * h)
            val i = Int::class.javaPrimitiveType
            c.getMethod("getRGB", i, i, i, i, IntArray::class.java, i, i).invoke(image, 0, 0, w, h, px, 0, w)
            return Picture(w, h, px)
        }
    }
}

/** A snapshot handler that hands each rendered frame to the test while the view is still attached. */
internal class Frames : SnapshotHandler {
    var onFrame: (Int, Any) -> Unit = { _, _ -> }

    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
        var i = 0
        val type = SnapshotHandler.FrameHandler::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
            when (m.name) {
                "handle" -> onFrame(i++, args!![0])
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "frames"
                else -> null
            }
        } as SnapshotHandler.FrameHandler
    }

    override fun close() {}
}

/** The pictures' folder (`-PsheetPictures=<folder>`); without it nothing is written. */
internal object Pictures {
    private val dir: File? = System.getProperty("sheet.pictures")?.let { File(it).apply { mkdirs() } }

    fun write(name: String, picture: Picture) {
        dir?.let { File(it, "$name.png").writeBytes(png(picture)) }
    }

    /** A PNG of the picture: 8-bit RGB, one IDAT, filter 0 on every row. */
    fun png(p: Picture): ByteArray {
        val raw = ByteArrayOutputStream(p.height * (1 + 3 * p.width))
        for (y in 0 until p.height) {
            raw.write(0)
            for (x in 0 until p.width) {
                val c = p.at(x, y)
                raw.write((c shr 16) and 0xFF)
                raw.write((c shr 8) and 0xFF)
                raw.write(c and 0xFF)
            }
        }
        val z = ByteArrayOutputStream()
        DeflaterOutputStream(z, Deflater(6)).use { it.write(raw.toByteArray()) }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val d = DataOutputStream(out)
            val t = type.toByteArray(Charsets.US_ASCII)
            d.writeInt(data.size)
            d.write(t)
            d.write(data)
            val crc = CRC32()
            crc.update(t)
            crc.update(data)
            d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(p.width)
            writeInt(p.height)
            write(byteArrayOf(8, 2, 0, 0, 0))
        }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}

abstract class SheetTestBase {
    internal val frames = Frames()

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = Screens.PHONE, theme = "android:Theme.Material.Light.NoActionBar", supportsRtl = true,
        snapshotHandler = frames, renderingMode = RenderingMode.NORMAL)

    private var config: DeviceConfig = Screens.PHONE

    /** The device of [case]: its screen, night mode, text size and direction. */
    internal fun use(case: Case) {
        SheetContrast.forTests = case.moreContrast
        val c = case.config()
        if (c != config) {
            paparazzi.unsafeUpdateConfig(deviceConfig = c)
            config = c
        }
    }

    internal val ctx: Context get() = paparazzi.context
    internal fun px(dp: Float): Int = (dp * ctx.resources.displayMetrics.density).roundToInt()

    /**
     * Renders [view] for [count] frames and returns the last frame at full size (Paparazzi hands a handler a copy shrunk
     * to 1000 px; the session's own image is the full one). [each] runs after every frame, with the view attached.
     */
    internal fun shoot(view: View, count: Int = 1, each: (Int) -> Unit = {}): Picture {
        var last: Picture? = null
        frames.onFrame = { i, shrunk ->
            each(i)
            last = fullImage() ?: Picture.of(shrunk)
        }
        if (count == 1) paparazzi.snapshot(view) else paparazzi.gif(view, start = 0, end = (count - 1) * 250L, fps = 4)
        return last ?: error("no frame was rendered")
    }

    private fun fullImage(): Picture? = try {
        val f = Paparazzi::class.java.getDeclaredField("bridgeRenderSession")
        f.isAccessible = true
        val session = f.get(paparazzi) as RenderSession
        // the pixels are copied out: the session draws its next frame into the same image
        Picture.of(RenderSession::class.java.getMethod("getImage").invoke(session)!!)
    } catch (_: Exception) {
        null
    }

    /** The Views dialog's sheet for [case], in a screen with its backdrop, and the frame itself. */
    internal fun views(case: Case, onShare: () -> Unit = {}, onNotNow: () -> Unit = {}): Pair<View, SheetFrame> {
        val frame = consentSheet(ctx, case.copy, case.variant, case.guardian, case.theme, onShare, onNotNow)
        frame.panel.box.startAtEnd = case.end
        // the test's window: no insets come from the system, the status bar's is set here
        frame.setOnApplyWindowInsetsListener(null)
        frame.insetTop = px(Screens.STATUS_BAR)
        val screen = FrameLayout(ctx).apply {
            setBackgroundColor(Screens.backdrop(case.dark))
            addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return screen to frame
    }

    /** The Compose sheet for [case], in a screen with its backdrop. */
    internal fun compose(case: Case, onShare: () -> Unit = {}, onNotNow: () -> Unit = {}, wrap: (@Composable (@Composable () -> Unit) -> Unit)? = null): ComposeView =
        screen(case.dark) {
            val sheet = @Composable {
                ConsentSheet(case.copy, case.variant, case.guardian, case.style, insetTop = Screens.STATUS_BAR.dp, insetBottom = 0.dp,
                    onShare = onShare, onNotNow = onNotNow, onDismiss = {}, startAtEnd = case.end)
            }
            if (wrap != null) wrap(sheet) else sheet()
        }

    internal fun screen(dark: Boolean, content: @Composable () -> Unit): ComposeView = ComposeView(ctx).apply {
        setContent { Box(Modifier.fillMaxSize().background(Color(Screens.backdrop(dark)))) { content() } }
    }
}

// ---- reading what was drawn ----

/** Every TextView under this view (buttons included), in drawing order. */
internal fun View.texts(): List<TextView> {
    val out = ArrayList<TextView>()
    fun walk(v: View) {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(this)
    return out
}

/** Every view under this one of type [T]. */
internal inline fun <reified T : View> View.all(): List<T> = allOf(T::class.java)

internal fun <T : View> View.allOf(type: Class<T>): List<T> {
    val out = ArrayList<T>()
    fun walk(v: View) {
        if (type.isInstance(v)) out.add(type.cast(v)!!)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(this)
    return out
}

/** Where a view is on the screen, in pixels: left, top, right, bottom. */
internal fun View.box(): IntArray {
    val at = IntArray(2)
    getLocationInWindow(at)
    return intArrayOf(at[0], at[1], at[0] + width, at[1] + height)
}

/** The Compose sheet's semantics nodes, depth first (the order a screen reader takes them in). */
internal fun ComposeView.nodes(merged: Boolean = true): List<SemanticsNode> {
    val owner = (getChildAt(0) as ViewRootForTest).semanticsOwner
    val out = ArrayList<SemanticsNode>()
    fun walk(n: SemanticsNode) {
        out.add(n)
        n.children.forEach(::walk)
    }
    walk(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
    return out
}

internal fun SemanticsNode.text(): String? = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

/** Where a node is, unclipped (`boundsInRoot` is cut by the scrolling column's viewport), in pixels. */
internal fun SemanticsNode.rect(): androidx.compose.ui.geometry.Rect =
    androidx.compose.ui.geometry.Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

/** The darkest pixel's colour in a rectangle of [image] (a label's ink against a lighter fill). */
internal fun darkest(image: Picture, l: Int, t: Int, r: Int, b: Int): Int {
    var best = 0
    var bestL = Double.MAX_VALUE
    for (y in maxOf(0, t) until minOf(image.height, b)) for (x in maxOf(0, l) until minOf(image.width, r)) {
        val c = image.at(x, y) or (0xFF shl 24)
        val lum = SheetDesign.luminance(c)
        if (lum < bestL) { bestL = lum; best = c }
    }
    return best
}

/** The lightest pixel's colour in a rectangle of [image] (a white label on a darker fill). */
internal fun lightest(image: Picture, l: Int, t: Int, r: Int, b: Int): Int {
    var best = 0
    var bestL = -1.0
    for (y in maxOf(0, t) until minOf(image.height, b)) for (x in maxOf(0, l) until minOf(image.width, r)) {
        val c = image.at(x, y) or (0xFF shl 24)
        val lum = SheetDesign.luminance(c)
        if (lum > bestL) { bestL = lum; best = c }
    }
    return best
}

/** The lines a sheet draws for the deck [c] and the variant: the name, the deck's lines whole, and the two labels. */
internal fun expectedLines(c: SharingCopy, variant: ConsentState.Variant, guardian: Boolean): Set<String> {
    val latin = SheetDesign.script(c.lang) == SheetDesign.Script.LATIN
    val l = mutableSetOf(
        SheetDesign.NAME,
        c[if (guardian) "sheet.guardian.title" else "sheet.title"],
        c[if (guardian) "sheet.guardian.body" else "sheet.body"],
        c["sheet.what"], c["sheet.who"], c["sheet.control"],
        c[if (guardian) "sheet.guardian.share" else "sheet.share"], c["sheet.not_now"],
    )
    c.hadith?.let { l += it }
    c.hadithRef?.let { l += if (latin) it.uppercase(java.util.Locale(c.lang)) else it }
    if (!guardian) l += c["sheet.mistakes"]
    if (guardian) l += c["sheet.guardian.confirm"]
    if (variant == ConsentState.Variant.REASK) l += c["reask"]
    return l
}
