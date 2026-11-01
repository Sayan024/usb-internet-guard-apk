package com.usbguard.firewall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.usbguard.firewall.ConnectivityProbe
import com.usbguard.firewall.FirewallPhase
import com.usbguard.firewall.FirewallState
import com.usbguard.firewall.FirewallStatus
import com.usbguard.firewall.GuardSettings
import com.usbguard.firewall.PacketCounters
import com.usbguard.firewall.ProbeReport
import com.usbguard.firewall.TetherDetector
import com.usbguard.firewall.UsbStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class DashboardActions(
    val enable: () -> Unit,
    val disable: () -> Unit,
    val emergencyDisable: () -> Unit,
    val openTetherSettings: () -> Unit,
    val openVpnSettings: () -> Unit,
)

private enum class Tone { GOOD, BAD, NEUTRAL }

private val acceptanceTests = listOf(
    "chrome" to "Open Chrome on the phone. Pages should not load.",
    "youtube" to "Open YouTube on the phone. Videos should not load.",
    "pc" to "Turn on USB tethering. The PC should browse normally.",
    "download" to "Download a large file on the PC. It should keep going.",
    "unplug" to "Unplug USB. Phone apps should still be blocked.",
    "reboot" to "Restart the phone. The firewall should come back on by itself.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(actions: DashboardActions) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val status by FirewallState.status.collectAsStateWithLifecycle()
    val checklist by remember(context) { GuardSettings.checklistFlow(context) }
        .collectAsStateWithLifecycle(initialValue = emptySet())

    var counters by remember { mutableStateOf(FirewallState.counters()) }
    var usb by remember { mutableStateOf<UsbStatus?>(null) }
    var probe by remember { mutableStateOf<ProbeReport?>(null) }
    var probing by remember { mutableStateOf(false) }

    // Refresh counters and USB state once a second while the screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                counters = FirewallState.counters()
                usb = withContext(Dispatchers.IO) { TetherDetector.read(context) }
                delay(1_000)
            }
        }
    }

    // An old test result is misleading once the firewall changes state.
    LaunchedEffect(status.phase) { probe = null }

    Scaffold(topBar = { TopAppBar(title = { Text("USB Internet Guard") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status, actions.enable, actions.disable)
            OverviewCard(status, usb)
            CountersCard(counters, status.phase == FirewallPhase.ACTIVE)
            SelfTestCard(probe, probing, status.phase == FirewallPhase.ACTIVE) {
                scope.launch {
                    probing = true
                    probe = ConnectivityProbe.run()
                    probing = false
                }
            }
            ShortcutsCard(actions.openTetherSettings, actions.openVpnSettings)
            ChecklistCard(checklist) { id, done ->
                scope.launch { GuardSettings.setChecklistItem(context, id, done) }
            }
            EmergencyCard(actions.emergencyDisable)
            LimitationsCard()
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun StatusCard(status: FirewallStatus, onEnable: () -> Unit, onDisable: () -> Unit) {
    val active = status.phase == FirewallPhase.ACTIVE
    val busy = status.phase == FirewallPhase.STARTING
    val (label, tone) = when (status.phase) {
        FirewallPhase.ACTIVE -> "Protected. Phone apps are blocked." to Tone.GOOD
        FirewallPhase.STARTING -> "Starting…" to Tone.NEUTRAL
        FirewallPhase.OFF -> "Off. Phone apps can use the internet." to Tone.BAD
        FirewallPhase.ERROR -> "Stopped because of an error." to Tone.BAD
        FirewallPhase.REVOKED -> "Stopped by Android." to Tone.BAD
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FIREWALL STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("USB Only Mode", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (active) Icons.Filled.Lock else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = toneColor(tone),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(label, color = toneColor(tone), fontWeight = FontWeight.Medium)
            }
            status.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (active || busy) {
                OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn off USB Only Mode")
                }
            } else {
                Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable USB Only Mode")
                }
            }
            Text(
                "Requires Android VPN permission. Nothing is sent to any server; blocked traffic is simply dropped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(status: FirewallStatus, usb: UsbStatus?) {
    val active = status.phase == FirewallPhase.ACTIVE
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow(
                "Phone apps: mobile internet",
                if (active) "Blocked" else "Allowed",
                if (active) Tone.GOOD else Tone.BAD,
            )
            StatusRow(
                "VPN firewall",
                when (status.phase) {
                    FirewallPhase.ACTIVE -> "Active"
                    FirewallPhase.STARTING -> "Starting"
                    else -> "Inactive"
                },
                if (active) Tone.GOOD else Tone.NEUTRAL,
            )
            StatusRow(
                "USB cable",
                when (usb?.cableConnected) {
                    true -> "Connected"
                    false -> "Not connected"
                    null -> "Unknown"
                },
                Tone.NEUTRAL,
            )
            val tether = when {
                usb == null -> "Checking…"
                usb.tetheringActive -> usb.tetherInterfaces.joinToString { "On · ${it.name} ${it.ipv4.first()}" }
                usb.tetherFunctionOn == true -> "Turning on…"
                else -> "Off"
            }
            StatusRow("USB tethering", tether, if (usb?.tetheringActive == true) Tone.GOOD else Tone.NEUTRAL)
            Text(
                "This app can't see the PC's traffic. Check that the PC can browse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountersCard(counters: PacketCounters, active: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Blocked packets", style = MaterialTheme.typography.titleMedium)
            Text(
                String.format(Locale.getDefault(), "%,d", counters.total),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                String.format(
                    Locale.getDefault(),
                    "IPv4 %,d · IPv6 %,d · %s dropped",
                    counters.ipv4, counters.ipv6, formatBytes(counters.bytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!active) {
                Text(
                    "Counts reset each time USB Only Mode starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelfTestCard(probe: ProbeReport?, probing: Boolean, active: Boolean, onRun: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Check phone internet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tries to reach 1.1.1.1 from this phone, just like any other app would.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRun, enabled = !probing, modifier = Modifier.fillMaxWidth()) {
                Text(if (probing) "Checking… (up to 3 seconds)" else "Run check")
            }
            if (probe != null) {
                ProbeRow("IPv4", probe.ipv4Reachable)
                ProbeRow("IPv6", probe.ipv6Reachable)
                val leaking = probe.ipv4Reachable || probe.ipv6Reachable
                Text(
                    when {
                        active && leaking -> "Warning: the phone still reached the internet while the firewall is on."
                        active -> "The phone has no internet. The firewall is working."
                        else -> "The firewall is off, so a reachable result is expected."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active && leaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProbeRow(label: String, reachable: Boolean) {
    StatusRow(label, if (reachable) "Reachable" else "No connection", if (reachable) Tone.BAD else Tone.GOOD)
}

@Composable
private fun ShortcutsCard(openTether: () -> Unit, openVpn: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android settings", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = openTether, modifier = Modifier.fillMaxWidth()) {
                Text("Open USB tethering settings")
            }
            OutlinedButton(onClick = openVpn, modifier = Modifier.fillMaxWidth()) {
                Text("Open VPN settings (Always-on)")
            }
            Text(
                "Once test 3 passes: VPN settings → gear next to USB Internet Guard → turn on \"Always-on VPN\". " +
                    "\"Block connections without VPN\" keeps phone apps offline even if this app crashes. " +
                    "Turn it on only after checking that the PC still has internet with it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChecklistCard(done: Set<String>, onToggle: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Acceptance tests", style = MaterialTheme.typography.titleMedium)
            Text(
                "${done.size} of ${acceptanceTests.size} passed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            acceptanceTests.forEachIndexed { index, (id, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = id in done, onCheckedChange = { onToggle(id, it) })
                    Text("${index + 1}. $text", style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "If test 3 fails, this phone's firmware routes tethered traffic through the VPN, and a VPN-only app can't do this job.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmergencyCard(onEmergency: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onEmergency,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Emergency disable")
            }
            Text(
                "Stops the firewall immediately and opens VPN settings, so you can also switch off \"Block connections without VPN\" if it's on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LimitationsCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Limits", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Without root, the app can't change Android's kernel firewall. It works only through the VPN.",
                "Without \"Block connections without VPN\", apps get internet back if this app is stopped.",
                "Some privileged system services may not follow VPN rules.",
                "USB tethering has to be turned on in Android settings. Apps can't switch it on.",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, tone: Tone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = toneColor(tone), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.GOOD -> goodColor()
    Tone.BAD -> MaterialTheme.colorScheme.error
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
