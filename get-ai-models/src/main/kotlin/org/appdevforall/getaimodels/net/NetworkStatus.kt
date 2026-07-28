package org.appdevforall.getaimodels.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Whether a download may start, and whether the user has to be warned first. */
enum class NetworkStatus {
    /** No usable connection - block the download with a message. */
    UNAVAILABLE,

    /** Connected over a metered link (cellular, metered hotspot) - warn before downloading. */
    METERED,

    /** Connected over an unmetered link - download straight away. */
    UNMETERED;

    val isConnected: Boolean get() = this != UNAVAILABLE
}

object NetworkProbe {

    fun current(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.UNAVAILABLE
        if (!isConnected(cm)) return NetworkStatus.UNAVAILABLE
        // Covers cellular and user-flagged metered Wi-Fi on every supported API level.
        return if (cm.isActiveNetworkMetered) NetworkStatus.METERED else NetworkStatus.UNMETERED
    }

    private fun isConnected(cm: ConnectivityManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }
}
