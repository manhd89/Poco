package com.luxysiv.puretube.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Theo dõi mạng cho coordinator. Tách riêng để retry/chất lượng hoạt động cả
 * khi UI đã chết.
 */
class PlaybackNetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isNetworkAvailable(): Boolean {
        val connectivity = manager ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Wi-Fi thường có NET_CAPABILITY_NOT_METERED; dữ liệu di động/hotspot thì không. */
    fun isMeteredNetwork(): Boolean {
        val connectivity = manager ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false)
            ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun start(
        onAvailable: () -> Unit,
        onLost: () -> Unit,
        onCapabilitiesChanged: () -> Unit
    ) {
        val connectivity = manager ?: return
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
            override fun onLost(network: Network) = onLost()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = onCapabilitiesChanged()
        }
        callback = networkCallback
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

    fun stop() {
        val networkCallback = callback ?: return
        callback = null
        runCatching { manager?.unregisterNetworkCallback(networkCallback) }
    }
}
