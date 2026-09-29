package com.opensolr.mail.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object Online {

    /** Waits up to [timeoutMs] for a working connection the app may use (validated, not blocked by Doze); true once there is one. */
    suspend fun await(context: Context, timeoutMs: Long): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val ready = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var caps: NetworkCapabilities? = null
            @Volatile private var blocked = false

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { caps = capabilities; check() }
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) { blocked = isBlocked; check() }
            override fun onLost(network: Network) { caps = null }

            private fun check() {
                val c = caps ?: return
                if (!blocked && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ready.complete(Unit)
            }
        }
        // Without a callback slot the state cannot be watched: the request itself finds out.
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isFailure) return true
        return try {
            withTimeoutOrNull(timeoutMs) { ready.await() } != null
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }
}
