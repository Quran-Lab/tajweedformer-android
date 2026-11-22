package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.NetworkPolicy
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The network as `diagnose().connection` reports it. Needs ACCESS_NETWORK_STATE (declared by the SDK's manifest).
 *  SDK 4.0.2: it gates nothing but `none` ([NetworkPolicy]); no request waits for Wi-Fi or for the data saver. */
internal class AndroidNetwork(context: Context) : NetworkPolicy {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
