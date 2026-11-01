package com.usbguard.firewall

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usbguard.firewall.ui.DashboardActions
import com.usbguard.firewall.ui.DashboardScreen
import com.usbguard.firewall.ui.GuardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var askedForNotifications = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startFirewall()
            } else {
                FirewallState.set(
                    FirewallPhase.ERROR,
                    "VPN permission was not granted. If another app is set as Always-on VPN, turn that off first.",
                )
            }
        }

    // Whatever the answer, carry on: the notification is useful but not required.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnPermissionThenStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GuardTheme {
                DashboardScreen(
                    DashboardActions(
                        enable = ::onEnableClicked,
                        disable = ::disableFirewall,
                        emergencyDisable = ::emergencyDisable,
                        openTetherSettings = ::openTetherSettings,
                        openVpnSettings = ::openVpnSettings,
                    ),
                )
            }
        }
    }

    private fun onEnableClicked() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && !askedForNotifications) {
            askedForNotifications = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnPermissionThenStart()
    }

    private fun requestVpnPermissionThenStart() {
        val consentIntent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            FirewallState.set(FirewallPhase.ERROR, "Android could not prepare the VPN: ${e.message}")
            return
        }
        if (consentIntent != null) {
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            startFirewall()
        }
    }

    private fun startFirewall() {
        lifecycleScope.launch { GuardSettings.setEnabled(this@MainActivity, true) }
        FirewallVpnService.start(this)
    }

    private fun disableFirewall() {
        lifecycleScope.launch { GuardSettings.setEnabled(this@MainActivity, false) }
        FirewallVpnService.stop(this)
    }

    /**
     * Stops the firewall and opens Android's VPN settings. If "Block connections without VPN"
     * is on, Android keeps apps offline even after the app stops, so it must be turned off there.
     */
    private fun emergencyDisable() {
        disableFirewall()
        Toast.makeText(
            this,
            "Firewall stopped. If phone internet is still off, turn off \"Block connections without VPN\" here.",
            Toast.LENGTH_LONG,
        ).show()
        openVpnSettings()
    }

    private fun openTetherSettings() {
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        launchFirstWorking(candidates)
    }

    private fun openVpnSettings() {
        launchFirstWorking(listOf(Intent(Settings.ACTION_VPN_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    }

    private fun launchFirstWorking(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "Couldn't open that settings page on this phone.", Toast.LENGTH_SHORT).show()
    }
}
