package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.NetworkPolicy
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Unmetered = the active network has NET_CAPABILITY_NOT_METERED (Wi-Fi, Ethernet; not mobile data
 *  or a metered hotspot). Needs ACCESS_NETWORK_STATE (declared by the SDK's manifest). */
internal class AndroidNetwork(context: Context) : NetworkPolicy {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isUnmetered(): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: SecurityException) {
        false
    }

    /** `none` without a network that reaches the internet; `metered` on mobile data, a metered hotspot, or while
     *  the system's data saver is on; else `unmetered`. `unknown` when the state cannot be read. */
    override fun connection(): String = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "none"
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "metered"
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "metered"
            else -> "unmetered"
        }
    } catch (_: Exception) {
        "unknown"
    }
}
