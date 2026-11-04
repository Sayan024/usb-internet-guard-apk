package com.usbguard.firewall.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private val acceptanceTests = listOf(
    "chrome" to "Open Chrome on the phone. Pages should not load.",
    "youtube" to "Open YouTube on the phone. Videos should not load.",
    "pc" to "Turn on USB tethering. The PC should browse normally.",
    "download" to "Download a large file on the PC. It should keep going.",
    "unplug" to "Unplug USB. Phone apps should still be blocked.",
    "reboot" to "Restart the phone. The firewall should come back on by itself.",
)

private val CardShape = RoundedCornerShape(24.dp)

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

    val active = status.phase == FirewallPhase.ACTIVE

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            HeroCard(status, actions.enable, actions.disable)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RouteTile(
                    icon = GuardIcons.Smartphone,
                    title = "Phone apps",
                    value = if (active) "Blocked" else "Online",
                    ok = active,
                    modifier = Modifier.weight(1f),
                )
                val current = usb
                RouteTile(
                    icon = GuardIcons.Laptop,
                    title = "PC over USB",
                    value = when {
                        current == null -> "Checking"
                        current.tetheringActive -> "Sharing"
                        current.cableConnected == true -> "Cable only"
                        else -> "Unplugged"
                    },
                    ok = if (current?.tetheringActive == true) true else null,
                    modifier = Modifier.weight(1f),
                )
            }
            StatsCard(counters)
            HealthCheckCard(probe, probing, active) {
                scope.launch {
                    probing = true
                    probe = ConnectivityProbe.run()
                    probing = false
                }
            }
            UsbCard(usb, actions.openTetherSettings)
            ChecklistCard(checklist) { id, done ->
                scope.launch { GuardSettings.setChecklistItem(context, id, done) }
            }
            HardeningCard(actions.openVpnSettings)
            LimitsCard()
            EmergencyButton(actions.emergencyDisable)
            Text(
                "No root · No servers · Nothing leaves your phone",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GuardIcons.Shield, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("USB Internet Guard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Mobile data for your PC only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Hero ----------

private fun heroColors(phase: FirewallPhase): Pair<Color, Color> = when (phase) {
    FirewallPhase.ACTIVE -> Color(0xFF0F766E) to Color(0xFF064E3B)
    FirewallPhase.STARTING -> Color(0xFF0E7490) to Color(0xFF164E63)
    FirewallPhase.OFF -> Color(0xFF475569) to Color(0xFF1E293B)
    FirewallPhase.ERROR, FirewallPhase.REVOKED -> Color(0xFFB91C1C) to Color(0xFF7F1D1D)
}

@Composable
private fun HeroCard(status: FirewallStatus, onEnable: () -> Unit, onDisable: () -> Unit) {
    val phase = status.phase
    val (startColor, endColor) = heroColors(phase)
    val top by animateColorAsState(startColor, tween(600), label = "heroTop")
    val bottom by animateColorAsState(endColor, tween(600), label = "heroBottom")
    val active = phase == FirewallPhase.ACTIVE
    val busy = phase == FirewallPhase.STARTING

    val (title, subtitle) = when (phase) {
        FirewallPhase.ACTIVE -> "Protected" to "Phone apps are cut off. Your PC can still use the internet over USB."
        FirewallPhase.STARTING -> "Starting…" to "Setting up the firewall."
        FirewallPhase.OFF -> "Not protected" to "Phone apps can use mobile data right now."
        FirewallPhase.ERROR -> "Stopped" to "The firewall hit a problem."
        FirewallPhase.REVOKED -> "Turned off by Android" to "Another VPN started, or it was switched off in settings."
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(top, bottom)))
            .padding(24.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            ShieldBadge(phase)
            Spacer(Modifier.height(12.dp))
            Text(
                "USB ONLY MODE",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
            )
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            status.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = if (active || busy) onDisable else onEnable,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = bottom),
            ) {
                Text(
                    if (active || busy) "Turn off" else "Turn on USB Only Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Uses Android's VPN permission",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun ShieldBadge(phase: FirewallPhase) {
    val active = phase == FirewallPhase.ACTIVE
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "pulseValue",
    )
    val icon = when (phase) {
        FirewallPhase.ACTIVE -> GuardIcons.ShieldCheck
        FirewallPhase.ERROR, FirewallPhase.REVOKED -> Icons.Filled.Warning
        else -> GuardIcons.Shield
    }
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(112.dp)
                    .graphicsLayer {
                        val scale = 0.7f + 0.3f * pulse
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - pulse
                    }
                    .background(Color.White.copy(alpha = 0.35f), CircleShape),
            )
        }
        Box(
            Modifier
                .size(80.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
    }
}

// ---------- Tiles & cards ----------

/** [ok]: true = the state we want (green), false = not wanted (red), null = neutral. */
@Composable
private fun RouteTile(icon: ImageVector, title: String, value: String, ok: Boolean?, modifier: Modifier) {
    val container by animateColorAsState(
        when (ok) {
            true -> goodContainer()
            false -> MaterialTheme.colorScheme.errorContainer
            null -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "tileBg",
    )
    val content = when (ok) {
        true -> onGoodContainer()
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier
            .clip(CardShape)
            .background(container)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(content.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.75f))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = content)
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun StatsCard(counters: PacketCounters) {
    SectionCard {
        Row {
            StatBlock("Packets blocked", formatCount(counters.total), Modifier.weight(1f))
            StatBlock("Data dropped", formatBytes(counters.bytes), Modifier.weight(1f))
        }
        SplitBar(counters.ipv4, counters.ipv6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(MaterialTheme.colorScheme.primary)
            Caption("IPv4 ${formatCount(counters.ipv4)}")
            Spacer(Modifier.width(16.dp))
            LegendDot(MaterialTheme.colorScheme.secondary)
            Caption("IPv6 ${formatCount(counters.ipv6)}")
            Spacer(Modifier.weight(1f))
            Caption("Resets on each start")
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Caption(label)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SplitBar(ipv4: Long, ipv6: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (ipv4 + ipv6 > 0) {
            if (ipv4 > 0) {
                Box(Modifier.weight(ipv4.toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.primary))
            }
            if (ipv6 > 0) {
                Box(Modifier.weight(ipv6.toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.secondary))
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(8.dp).background(color, CircleShape))
}

@Composable
private fun HealthCheckCard(probe: ProbeReport?, probing: Boolean, active: Boolean, onRun: () -> Unit) {
    SectionCard(title = "Health check") {
        Caption("Tries to reach 1.1.1.1 from this phone, the same way any other app would.")
        if (probe != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProbeChip("IPv4", probe.ipv4Reachable, Modifier.weight(1f))
                ProbeChip("IPv6", probe.ipv6Reachable, Modifier.weight(1f))
            }
            val leaking = probe.ipv4Reachable || probe.ipv6Reachable
            Caption(
                when {
                    active && leaking -> "Warning: the phone still reached the internet while the firewall is on."
                    active -> "No internet on the phone. The firewall is working."
                    else -> "The firewall is off, so reaching the internet is expected."
                },
                color = if (active && leaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onRun, enabled = !probing, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (probing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking…")
            } else {
                Text("Run check")
            }
        }
    }
}

@Composable
private fun ProbeChip(label: String, reachable: Boolean, modifier: Modifier) {
    val bg = if (reachable) MaterialTheme.colorScheme.errorContainer else goodContainer()
    val fg = if (reachable) MaterialTheme.colorScheme.onErrorContainer else onGoodContainer()
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f))
        Text(
            if (reachable) "Reachable" else "No connection",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

@Composable
private fun UsbCard(usb: UsbStatus?, openTether: () -> Unit) {
    SectionCard(title = "USB connection") {
        InfoRow(
            icon = GuardIcons.Usb,
            label = "USB cable",
            value = when (usb?.cableConnected) {
                true -> "Connected"
                false -> "Not connected"
                null -> "Unknown"
            },
            on = usb?.cableConnected == true,
        )
        InfoRow(
            icon = GuardIcons.Laptop,
            label = "USB tethering",
            value = when {
                usb == null -> "Checking…"
                usb.tetheringActive -> usb.tetherInterfaces.joinToString { "${it.name} · ${it.ipv4.first()}" }
                usb.tetherFunctionOn == true -> "Turning on…"
                else -> "Off"
            },
            on = usb?.tetheringActive == true,
        )
        Caption("This app can't see your PC's traffic. Check that the PC can browse.")
        FilledTonalButton(onClick = openTether, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Open tethering settings")
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Caption(label)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier
                .size(10.dp)
                .background(if (on) goodColor() else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
    }
}

@Composable
private fun ChecklistCard(done: Set<String>, onToggle: (String, Boolean) -> Unit) {
    val passed = acceptanceTests.count { it.first in done }
    SectionCard(title = "Acceptance tests", trailing = "$passed / ${acceptanceTests.size}") {
        val progress by animateFloatAsState(passed / acceptanceTests.size.toFloat(), label = "testsProgress")
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Column {
            acceptanceTests.forEachIndexed { index, (id, text) ->
                val checked = id in done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggle(id, !checked) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckCircle(checked)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${index + 1}. $text",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Caption("If test 3 fails, this phone sends tethered traffic through the VPN, and a VPN-only app can't do this job.")
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    val bg by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "checkBg",
    )
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(bg)
            .border(2.dp, if (checked) bg else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun HardeningCard(openVpn: () -> Unit) {
    SectionCard(title = "Stronger protection") {
        Caption(
            "After test 3 passes, open VPN settings, tap the gear next to USB Internet Guard, and turn on " +
                "\"Always-on VPN\". This also brings the firewall back after a restart.",
        )
        Caption(
            "\"Block connections without VPN\" keeps phone apps offline even if this app crashes. " +
                "Check that the PC still has internet after turning it on.",
        )
        OutlinedButton(onClick = openVpn, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Open VPN settings")
        }
    }
}

@Composable
private fun LimitsCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What this app can't do",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Without root, it can't change Android's kernel firewall. It works only through the VPN.",
                    "Without \"Block connections without VPN\", apps get internet back if this app is stopped.",
                    "Some privileged system services may not follow VPN rules.",
                    "USB tethering has to be turned on in Android settings. Apps can't switch it on.",
                ).forEach { Caption("•  $it") }
            }
        }
    }
}

@Composable
private fun EmergencyButton(onEmergency: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onEmergency,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Warning, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Emergency disable", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Caption("Stops the firewall now and opens VPN settings.")
    }
}

private fun formatCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
