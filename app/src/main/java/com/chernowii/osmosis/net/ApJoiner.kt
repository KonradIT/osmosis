package com.chernowii.osmosis.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

/**
 * Joins the camera's WiFi AP (WPA2-PSK, internet-less) via WifiNetworkSpecifier (API 29+) and
 * binds the process to it so our HTTP/UDP sockets egress over the camera network. This is the
 * Android-native replacement for osmo-download's macOS `networksetup` juggling.
 */
class ApJoiner(context: Context, private val listener: Listener) {
    interface Listener {
        fun onLog(s: String)
        fun onNetwork(network: Network, link: LinkProperties?)
        fun onFailed(reason: String)
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun join(ssid: String, passphrase: String) {
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotEmpty()) specBuilder.setWpa2Passphrase(passphrase)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val bound = cm.bindProcessToNetwork(network)
                val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
                listener.onLog("WiFi: onAvailable, bindProcessToNetwork=$bound")
                listener.onNetwork(network, link)
            }

            override fun onUnavailable() {
                listener.onFailed("WiFi: onUnavailable (wrong password, AP down, or user cancelled)")
            }

            override fun onLost(network: Network) {
                listener.onLog("WiFi: onLost")
            }
        }
        cb = callback
        listener.onLog("WiFi: requesting \"$ssid\" (WPA2, no-internet)...")
        cm.requestNetwork(request, callback)
    }

    fun release() {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        runCatching { cm.bindProcessToNetwork(null) }
        cb = null
    }
}
