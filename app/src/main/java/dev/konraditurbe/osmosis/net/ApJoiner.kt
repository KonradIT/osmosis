package dev.konraditurbe.osmosis.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.net.DatagramSocket

/**
 * Why Android refused to bind this process to the camera network.
 *
 * Without that bind every socket leaves over the default network (mobile data, home Wi-Fi or a VPN),
 * so the camera at 192.168.2.1 never hears from us: the handshake fails on every port and the session
 * reads as an empty camera. Issue #32: four sessions on one phone, every one `bindProcessToNetwork=false`
 * and every one silent, while all six sessions from two other Osmo Action 1 units had `true`.
 */
data class BindFailure(
    /** A VPN applies to this app. One that does not allow bypass makes Android refuse any request to
     *  use another network, which is the usual cause. */
    val vpnActive: Boolean,
    /** What binding a single socket to the network threw (e.g. `EPERM`), or null if that bind worked. */
    val socketBindError: String?,
) {
    fun describe(): String =
        "VPN active: ${if (vpnActive) "yes" else "no"}, single-socket bind: ${socketBindError ?: "worked"}"
}

/**
 * Joins the camera's WiFi AP (WPA2-PSK, internet-less) via WifiNetworkSpecifier (API 29+) and
 * binds the process to it so our HTTP/UDP sockets egress over the camera network. This is the
 * Android-native replacement for osmo-download's macOS `networksetup` juggling.
 */
class ApJoiner(context: Context, private val listener: Listener) {
    interface Listener {
        fun onLog(s: String)
        /** @param bindFailure null when the process is bound to [network]; otherwise why it is not. */
        fun onNetwork(network: Network, link: LinkProperties?, bindFailure: BindFailure?)
        fun onFailed(reason: String)
        /**
         * The AP went away after a successful join. Retry policy lives with the caller, which is the
         * only thing that knows whether a session is still worth saving — call [rejoin] to try again.
         */
        fun onLost()
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var cb: ConnectivityManager.NetworkCallback? = null

    // Remembered so [rejoin] can re-issue the identical request without the caller having to hold
    // the credentials for the whole session.
    private var lastSsid: String? = null
    private var lastPassphrase: String = ""
    private var lastWpa3: Boolean = false

    fun join(ssid: String, passphrase: String, wpa3: Boolean = false) {
        lastSsid = ssid; lastPassphrase = passphrase; lastWpa3 = wpa3
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotEmpty()) {
            // The Osmo 360 AP is WPA3-SAE; the rest are WPA2-PSK.
            if (wpa3) specBuilder.setWpa3Passphrase(passphrase) else specBuilder.setWpa2Passphrase(passphrase)
        }
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
                val failure = if (bound) null else diagnoseBindFailure(network).also {
                    listener.onLog("WiFi: Android refused to route this app over the camera network " +
                        "(${it.describe()}). Traffic will leave over another network and the camera " +
                        "will not answer. Usual cause: a VPN, ad blocker or firewall app that does not " +
                        "allow bypass.")
                }
                listener.onNetwork(network, link, failure)
            }

            override fun onUnavailable() {
                listener.onFailed("WiFi: onUnavailable (wrong password, AP down, or user cancelled)")
            }

            override fun onLost(network: Network) {
                listener.onLog("WiFi: onLost")
                listener.onLost()
            }
        }
        cb = callback
        listener.onLog("WiFi: requesting \"$ssid\" (${if (wpa3) "WPA3" else "WPA2"}, no-internet)...")
        cm.requestNetwork(request, callback)
    }

    /**
     * Re-issue the last [join] after the AP dropped.
     *
     * A `WifiNetworkSpecifier` request does not reconnect on its own — once the camera's AP goes away
     * the process stays bound to a dead network and every socket fails `ENONET`, which is exactly how
     * an interrupted transfer surfaced (a Pocket 4 Pro lost its AP 49 s into a download and nothing
     * downstream could recover). Unregister and request again; `onAvailable` re-binds the process.
     *
     * @return false if there is nothing to retry because [join] was never called.
     */
    fun rejoin(): Boolean {
        val ssid = lastSsid ?: return false
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
        join(ssid, lastPassphrase, lastWpa3)
        return true
    }

    /**
     * [ConnectivityManager.bindProcessToNetwork] only returns false. Binding one socket goes through
     * the same permission check in netd but throws with the errno, which separates a VPN refusal
     * (`EPERM`) from a network that has already gone (`ENONET`).
     */
    private fun diagnoseBindFailure(network: Network): BindFailure {
        val vpn = runCatching {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)
        val socketError = runCatching { DatagramSocket().use { network.bindSocket(it) } }
            .exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        return BindFailure(vpn, socketError)
    }

    fun release() {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        runCatching { cm.bindProcessToNetwork(null) }
        cb = null
    }
}
