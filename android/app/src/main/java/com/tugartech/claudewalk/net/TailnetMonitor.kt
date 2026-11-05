package com.tugartech.claudewalk.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Watches whether a VPN is carrying traffic, which on this phone means Tailscale.
 *
 * Without this the app could only say "disconnected", and that one word covered
 * three completely different situations: no session is running, the laptop is
 * asleep, or the tailnet has dropped. Only the last one is fixed by doing
 * something on the phone, and it was indistinguishable from the other two, so a
 * walk could be spent waiting on a link that needed one tap to restore.
 *
 * Android does not expose "is Tailscale up" and this does not pretend to. It
 * reports whether the default network has VPN transport, and the app only draws a
 * conclusion from that when the configured host is a tailnet address, where no VPN
 * genuinely does mean unreachable.
 */
class TailnetMonitor(
    context: Context,
    private val onVpnChanged: (up: Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var isVpnUp: Boolean = false
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun hasVpn(caps: NetworkCapabilities?): Boolean =
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    fun start() {
        if (callback != null) return
        isVpnUp = hasVpn(cm.getNetworkCapabilities(cm.activeNetwork))
        Log.i(TAG, "initial vpn state: $isVpnUp")
        // Report a tailnet that is ALREADY down at startup, not just one that falls
        // over while we watch. Opening the app to a dead tailnet is the common case
        // (the phone rebooted, the VPN was switched off yesterday) and it was the
        // one case that stayed silent, because a callback that only fires on change
        // never fires when the bad state is the starting state.
        if (!isVpnUp) onVpnChanged(false)

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update(hasVpn(caps))
            }

            // The default network going away entirely is also the tailnet going
            // away, from the point of view of anything trying to reach the laptop.
            override fun onLost(network: Network) {
                update(hasVpn(cm.getNetworkCapabilities(cm.activeNetwork)))
            }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { Log.e(TAG, "could not register network callback", it) }
    }

    private fun update(up: Boolean) {
        if (up == isVpnUp) return
        isVpnUp = up
        Log.i(TAG, "vpn ${if (up) "up" else "down"}")
        onVpnChanged(up)
    }

    fun stop() {
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
    }

    /** Bring Tailscale to the foreground so the user can switch it back on. */
    fun openTailscale(): Boolean {
        val intent = app.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            Log.w(TAG, "Tailscale is not installed, or not visible to package queries")
            return false
        }
        app.startActivity(intent)
        return true
    }

    companion object {
        private const val TAG = "TailnetMonitor"
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

        /**
         * Whether [host] is only reachable over the tailnet.
         *
         * Tailscale hands out addresses from the 100.64.0.0/10 carrier-grade NAT
         * range, and MagicDNS names are bare labels with no dots. A host with dots
         * that is not in that range is an ordinary address that may well be
         * reachable without a VPN, so no conclusion is drawn from it.
         */
        fun isTailnetHost(host: String): Boolean {
            val h = host.trim()
            if (h.isEmpty()) return false
            if (h == "localhost" || h.startsWith("127.")) return false

            val octets = h.split('.')
            if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
                val first = octets[0].toIntOrNull() ?: return false
                val second = octets[1].toIntOrNull() ?: return false
                return first == 100 && second in 64..127
            }
            // A bare label with no dots is a MagicDNS name, e.g. "my-laptop".
            return !h.contains('.')
        }
    }
}
