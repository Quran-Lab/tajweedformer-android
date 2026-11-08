package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.NetworkPolicy
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Unmetered = the active network has NET_CAPABILITY_NOT_METERED (Wi-Fi, Ethernet; not mobile data
 *  or a metered hotspot). Needs ACCESS_NETWORK_STATE (declared by the SDK's manifest). */
class AndroidNetwork(context: Context) : NetworkPolicy {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isUnmetered(): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: SecurityException) {
        false
    }
}
