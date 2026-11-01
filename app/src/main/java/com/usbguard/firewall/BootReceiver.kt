package com.usbguard.firewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns USB Only Mode back on after a reboot or app update, if it was on before.
 * Android's "Always-on VPN" setting does the same thing more reliably; this is a fallback.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // prepare() returns null only when VPN permission is still granted.
                if (GuardSettings.isEnabled(appContext) && VpnService.prepare(appContext) == null) {
                    FirewallVpnService.start(appContext)
                }
            } catch (e: Exception) {
                Log.w("UsbGuardBoot", "Could not restart firewall", e)
            } finally {
                pending.finish()
            }
        }
    }
}
