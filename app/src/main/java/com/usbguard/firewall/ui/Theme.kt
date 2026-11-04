package com.usbguard.firewall.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed brand colors (not wallpaper-based) so the app always looks the same.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    background = Color(0xFFF3F6F5),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF3F6F5),
    onSurface = Color(0xFF161D1C),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8EEEC),
    onSurfaceVariant = Color(0xFF5B6664),
    outlineVariant = Color(0xFFDAE2E0),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF5F1111),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00332D),
    primaryContainer = Color(0xFF134E48),
    onPrimaryContainer = Color(0xFFCCF2EC),
    secondary = Color(0xFFB1CCC7),
    background = Color(0xFF0B1211),
    onBackground = Color(0xFFE1E8E6),
    surface = Color(0xFF0B1211),
    onSurface = Color(0xFFE1E8E6),
    surfaceContainer = Color(0xFF141C1B),
    surfaceContainerHigh = Color(0xFF1D2725),
    onSurfaceVariant = Color(0xFF9CA9A6),
    outlineVariant = Color(0xFF2A3432),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5F1111),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Green "as it should be" colors. Material 3 has no built-in success role. */
@Composable
fun goodColor(): Color = if (isSystemInDarkTheme()) Color(0xFF4ADE80) else Color(0xFF15803D)

@Composable
fun goodContainer(): Color = if (isSystemInDarkTheme()) Color(0xFF123524) else Color(0xFFD7F5E3)

@Composable
fun onGoodContainer(): Color = if (isSystemInDarkTheme()) Color(0xFFB7F0CC) else Color(0xFF0B3D22)
