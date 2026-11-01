package com.usbguard.firewall

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

data class TetherInterface(val name: String, val ipv4: List<String>)

data class UsbStatus(
    /** USB cable plugged into a host. Null when Android didn't say. */
    val cableConnected: Boolean?,
    /** The USB port is in a tethering mode (RNDIS or NCM). Null when Android didn't say. */
    val tetherFunctionOn: Boolean?,
    /** USB network interfaces that are up and have an address handed out to the PC. */
    val tetherInterfaces: List<TetherInterface>,
) {
    val tetheringActive: Boolean get() = tetherInterfaces.isNotEmpty()
}

/**
 * Android has no public API for "is USB tethering on". This combines two best-effort signals:
 * the sticky USB_STATE broadcast and the list of USB network interfaces (rndis0, ncm0, usb0...).
 */
object TetherDetector {
    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    private val USB_IFACE_PREFIXES = listOf("rndis", "ncm", "usb")

    fun read(context: Context): UsbStatus {
        var connected: Boolean? = null
        var tetherFunction: Boolean? = null
        try {
            val filter = IntentFilter(ACTION_USB_STATE)
            val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(null, filter)
            }
            if (sticky != null) {
                connected = sticky.getBooleanExtra("connected", false)
                tetherFunction = sticky.getBooleanExtra("rndis", false) ||
                    sticky.getBooleanExtra("ncm", false)
            }
        } catch (_: Exception) {
            // Some firmware restricts this broadcast. The interface check below still works.
        }

        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { ni -> ni.isUp && USB_IFACE_PREFIXES.any { ni.name.startsWith(it) } }
                .map { ni ->
                    TetherInterface(
                        name = ni.name,
                        ipv4 = ni.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .mapNotNull { it.hostAddress },
                    )
                }
                .filter { it.ipv4.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }

        return UsbStatus(connected, tetherFunction, interfaces)
    }
}
