package io.github.trvny.wambridge.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

internal object WifiLan {
    enum class EndpointChange { UNBOUND, STABLE, MOVED, LOST }
    data class Endpoint(val networkHandle: Long, val address: String)

    data class Target(
        val network: Network,
        val address: Inet4Address,
        val prefixLength: Int,
    )

    fun targets(context: Context): List<Target> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val result = mutableListOf<Target>()

        val activeNetwork = connectivity.activeNetwork
        val networks = connectivity.allNetworks.sortedBy { if (it == activeNetwork) 0 else 1 }
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val properties = connectivity.getLinkProperties(network) ?: continue

            for (linkAddress in properties.linkAddresses) {
                val address = linkAddress.address as? Inet4Address ?: continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val target = Target(network, address, linkAddress.prefixLength)
                if (result.none { it.network == network && it.address == address }) result += target
            }
        }
        return result
    }

    fun endpoints(context: Context): Set<Endpoint> = targets(context).mapNotNull { target ->
        target.address.hostAddress?.let { Endpoint(target.network.networkHandle, it) }
    }.toSet()

    fun addresses(context: Context): Set<String> = endpoints(context).mapTo(mutableSetOf()) { it.address }

    internal fun endpointChange(bound: Endpoint?, available: Set<Endpoint>): EndpointChange = when {
        bound == null -> EndpointChange.UNBOUND
        bound in available -> EndpointChange.STABLE
        available.isEmpty() -> EndpointChange.LOST
        else -> EndpointChange.MOVED
    }

    fun watch(context: Context, onChanged: () -> Unit): AutoCloseable =
        WifiChangeWatcher(context.applicationContext, onChanged)

    fun connectSocket(context: Context, host: String, port: Int, timeoutMs: Int): Socket {
        var lastError: Exception? = null
        for (target in targets(context)) {
            val socket = Socket()
            try {
                target.network.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                return socket
            } catch (error: Exception) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IllegalStateException("No active Wi-Fi network")
    }

    fun openHttpConnections(context: Context, url: URL): Sequence<HttpURLConnection> = sequence {
        for (target in targets(context)) {
            val connection = target.network.openConnection(url) as HttpURLConnection
            yield(connection)
        }
    }
}


private class WifiChangeWatcher(
    context: Context,
    private val onChanged: () -> Unit,
) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private val notifyChange = Runnable { if (!closed) onChanged() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = schedule()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    private fun schedule() {
        if (closed) return
        handler.removeCallbacks(notifyChange)
        handler.postDelayed(notifyChange, DEBOUNCE_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(notifyChange)
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    companion object {
        private const val DEBOUNCE_MS = 900L
    }
}
