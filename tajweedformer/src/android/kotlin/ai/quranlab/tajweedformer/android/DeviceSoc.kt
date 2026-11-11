package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.SocIds
import android.os.Build

/** This device's SoC as a manifest `soc` id (see [SocIds] for the mapping and what is verified). */
internal object DeviceSoc {
    /** The SoC id, or null when this SoC has no NPU build or is not recognised. Always null in an x86 process (the
     *  emulator on an Intel or AMD PC, an x86 Chromebook): the CPU engine only. */
    @JvmStatic
    val id: String? by lazy {
        val man = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        SocIds.onArch(SocIds.from(man, model, property("ro.soc.model"), Build.HARDWARE, property("ro.board.platform")), System.getProperty("os.arch"))
    }

    /** The raw strings the id was made from, for logs. */
    @JvmStatic
    fun describe(): String = "soc=" + (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "?") +
        " ro.soc.model=${property("ro.soc.model")} hardware=${Build.HARDWARE} board=${property("ro.board.platform")}"

    /** A system property through the hidden SystemProperties class; null when it cannot be read. */
    private fun property(name: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
