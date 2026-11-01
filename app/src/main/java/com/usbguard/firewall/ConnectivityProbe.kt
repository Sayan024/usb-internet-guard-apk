package com.usbguard.firewall

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class ProbeReport(val ipv4Reachable: Boolean, val ipv6Reachable: Boolean)

/**
 * Tries to open TCP connections from this app, which is treated like any other phone app
 * (it is not excluded from its own VPN). If these fail while the firewall is on, phone apps
 * are blocked. Uses IP literals so no DNS lookup is needed.
 */
object ConnectivityProbe {
    private const val TIMEOUT_MS = 3000

    suspend fun run(): ProbeReport = withContext(Dispatchers.IO) {
        coroutineScope {
            val v4 = async { canConnect("1.1.1.1", 443) }
            val v6 = async { canConnect("2606:4700:4700::1111", 443) }
            ProbeReport(v4.await(), v6.await())
        }
    }

    private fun canConnect(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), port), TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }
}
