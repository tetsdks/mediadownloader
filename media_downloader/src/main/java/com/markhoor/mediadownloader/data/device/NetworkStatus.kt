package com.markhoor.mediadownloader.data.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * Whether the device has a connection at all. Read where a download is about to wait for one, so
 * that the wait can be said out loud rather than looking like nothing happened.
 */
internal class NetworkStatus(private val context: Context) {

    /** Some Android 11 builds throw SecurityException from the capability lookup; then assume one. */
    fun hasConnection(): Boolean = try {
        val connectivity = context.getSystemService<ConnectivityManager>()
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        connectivity == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        true
    }
}
